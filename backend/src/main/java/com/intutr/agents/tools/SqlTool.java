package com.intutr.agents.tools;

import com.intutr.agents.config.Cfg.ToolDef;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.util.J;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Runs the tool's fixed, parameterised, validated SELECT in a read-only transaction with a statement timeout and row cap. */
@Component
public class SqlTool implements ToolHandler {
    private static final Pattern PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");
    private final JdbcTemplate jdbc;
    private final ConfigRegistry cfg;

    public SqlTool(JdbcTemplate jdbc, ConfigRegistry cfg) { this.jdbc = jdbc; this.cfg = cfg; }
    @Override public String type() { return "sql"; }

    @Override
    public String run(ToolDef t, Map<String, Object> args, RunCtx ctx) {
        List<Object> vals = new ArrayList<>();
        Matcher m = PARAM.matcher(t.query());
        StringBuilder sb = new StringBuilder();
        while (m.find()) { vals.add(args.get(m.group(1))); m.appendReplacement(sb, "?"); }
        m.appendTail(sb);
        String sql = sb.toString();
        int maxRows = cfg.guardrails().sqlMaxRows() == null ? 50 : cfg.guardrails().sqlMaxRows();
        int timeout = cfg.guardrails().sqlTimeoutMs() == null ? 5000 : cfg.guardrails().sqlTimeoutMs();
        try {
            List<Map<String, Object>> rows = jdbc.execute((ConnectionCallback<List<Map<String, Object>>>) con -> {
                boolean ro = con.isReadOnly();
                boolean ac = con.getAutoCommit();
                try {
                    con.setReadOnly(true);
                    con.setAutoCommit(false);
                    try (Statement st = con.createStatement()) { st.execute("SET LOCAL statement_timeout = " + timeout); }
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setMaxRows(maxRows);
                        for (int i = 0; i < vals.size(); i++) ps.setObject(i + 1, vals.get(i));
                        List<Map<String, Object>> out = new ArrayList<>();
                        try (ResultSet rs = ps.executeQuery()) {
                            ResultSetMetaData md = rs.getMetaData();
                            while (rs.next()) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                for (int c = 1; c <= md.getColumnCount(); c++) {
                                    Object v = rs.getObject(c);
                                    if (!(v == null || v instanceof Number || v instanceof Boolean)) v = String.valueOf(v);
                                    row.put(md.getColumnLabel(c), v);
                                }
                                out.add(row);
                            }
                        }
                        return out;
                    }
                } finally {
                    con.rollback();
                    con.setAutoCommit(ac);
                    con.setReadOnly(ro);
                }
            });
            return rows == null || rows.isEmpty() ? "No rows." : J.str(rows);
        } catch (Exception e) {
            throw new ToolException("sql failed: " + J.clip(String.valueOf(e.getMessage()), 200));
        }
    }
}

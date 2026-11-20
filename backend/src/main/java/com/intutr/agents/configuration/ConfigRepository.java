package com.intutr.agents.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intutr.agents.util.J;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConfigRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = J.OM;
    public ConfigRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ConfigRecord> list(String tenant, String type) {
        return jdbc.query("SELECT * FROM agent_config WHERE tenant_id=? AND config_type=? ORDER BY config_name", this::record, tenant, type);
    }
    public ConfigRecord get(String tenant, String type, String identifier) {
        List<ConfigRecord> rows = jdbc.query("SELECT * FROM agent_config WHERE tenant_id=? AND config_type=? AND config_identifier=?", this::record, tenant, type, identifier);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public ConfigRecord get(UUID id) {
        List<ConfigRecord> rows = jdbc.query("SELECT * FROM agent_config WHERE id=?", this::record, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public List<ConfigVersion> versions(String tenant, String type, String identifier) {
        return jdbc.query("SELECT v.* FROM config_versions v JOIN agent_config c ON c.id=v.config_id WHERE c.tenant_id=? AND c.config_type=? AND c.config_identifier=? ORDER BY v.version DESC",
            (rs, n) -> new ConfigVersion(rs.getObject("id", UUID.class), rs.getObject("config_id", UUID.class), rs.getInt("version"), node(rs.getString("configuration")), rs.getString("changed_by"), rs.getString("change_description"), rs.getObject("created_at", java.time.OffsetDateTime.class)), tenant, type, identifier);
    }
    public ConfigRecord create(String tenant, String type, ConfigRequest req, String by) {
        jdbc.update("INSERT INTO agent_config(tenant_id,config_type,config_identifier,config_name,configuration,source,version,status,created_by) VALUES (?,?,?,?,?::jsonb,'DATABASE',1,?,?)",
            tenant, type, req.identifier(), req.name(), req.configuration().toString(), status(req.status()), by);
        ConfigRecord saved = get(tenant, type, req.identifier());
        jdbc.update("INSERT INTO config_versions(config_id,version,configuration,changed_by,change_description) VALUES (?,1,?::jsonb,?,?)",
            saved.id(), req.configuration().toString(), by, req.changeDescription() == null ? "Created configuration" : req.changeDescription());
        return saved;
    }
    public ConfigRecord update(ConfigRecord current, ConfigRequest req, String by) {
        jdbc.update("INSERT INTO config_versions(config_id,version,configuration,changed_by,change_description) VALUES (?,? ,?::jsonb,?,?)",
            current.id(), current.version() + 1, req.configuration().toString(), by,
            req.changeDescription() == null ? "Updated configuration" : req.changeDescription());
        jdbc.update("UPDATE agent_config SET config_name=?,configuration=?::jsonb,version=version+1,status=?,updated_at=now() WHERE id=?",
            req.name(), req.configuration().toString(), status(req.status()), current.id());
        return get(current.id());
    }
    public void delete(ConfigRecord row) { jdbc.update("DELETE FROM agent_config WHERE id=?", row.id()); }
    public ConfigRecord setStatus(ConfigRecord row, String status, String changedBy) {
        jdbc.update("INSERT INTO config_versions(config_id,version,configuration,changed_by,change_description) VALUES (?,? ,?::jsonb,?,?)",
            row.id(), row.version() + 1, row.configuration().toString(), changedBy, "Status changed to " + status);
        jdbc.update("UPDATE agent_config SET status=?,version=version+1,updated_at=now() WHERE id=?", status, row.id());
        return get(row.id());
    }
    public boolean agentReferenced(String tenant, String identifier) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_config a, jsonb_array_elements_text(COALESCE(a.configuration->'tools','[]'::jsonb)) t(value) WHERE a.tenant_id=? AND a.config_type='agent' AND t.value=?)", Boolean.class, tenant, identifier));
    }
    public boolean referencedAsPrompt(String identifier) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_config WHERE config_type='agent' AND split_part(configuration->>'prompt','@',1)=?)", Boolean.class, identifier));
    }
    public boolean agentReferencesSkill(String tenant, String identifier) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_config a, jsonb_array_elements_text(COALESCE(a.configuration->'skills','[]'::jsonb)) s(value) WHERE a.tenant_id=? AND a.config_type='agent' AND s.value=?)", Boolean.class, tenant, identifier));
    }
    public boolean toolReferencesAgent(String tenant, String identifier) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_config WHERE tenant_id=? AND config_type='tool' AND configuration->>'agent'=?)", Boolean.class, tenant, identifier));
    }
    private ConfigRecord record(ResultSet rs, int n) throws SQLException {
        return new ConfigRecord(rs.getObject("id", UUID.class), rs.getString("tenant_id"), rs.getString("config_type"), rs.getString("config_identifier"), rs.getString("config_name"), node(rs.getString("configuration")), rs.getString("source"), rs.getInt("version"), rs.getString("status"), rs.getString("created_by"), rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
    private JsonNode node(String raw) { try { return json.readTree(raw); } catch (Exception e) { throw new IllegalStateException("Invalid JSON in configuration row", e); } }
    private static String status(String s) { return s == null || s.isBlank() ? "ACTIVE" : s.toUpperCase(java.util.Locale.ROOT); }
}

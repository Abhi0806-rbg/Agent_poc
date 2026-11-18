package com.intutr.agents.tools;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.llm.Types.ToolSpec;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {
    private final ConfigRegistry cfg;
    private final Map<String, ToolHandler> handlers = new HashMap<>();

    public ToolExecutor(ConfigRegistry cfg, List<ToolHandler> hs) {
        this.cfg = cfg;
        for (ToolHandler h : hs) handlers.put(h.type(), h);
    }

    /** What the model is allowed to see: context-bound params (user_id, tenant) are hidden from it. */
    public List<ToolSpec> specsFor(List<String> names) {
        List<ToolSpec> out = new ArrayList<>();
        for (String n : names) {
            ToolDef t = cfg.tool(n);
            if (t == null) continue;
            Map<String, Param> visible = new LinkedHashMap<>();
            if (t.params() != null) t.params().forEach((k, p) -> { if (p.source() == null) visible.put(k, p); });
            out.add(new ToolSpec(t.name(), t.description(), visible));
        }
        return out;
    }

    /** Context-bound params come from the session, never from the model. Others are defaulted, clamped and type-checked. */
    public Map<String, Object> bind(ToolDef t, Map<String, Object> modelArgs, RunCtx ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (t.params() == null) return out;
        for (var e : t.params().entrySet()) {
            String name = e.getKey();
            Param p = e.getValue();
            if (p.source() != null) { out.put(name, ctx.value(p.source().substring("context.".length()))); continue; }
            Object v = modelArgs == null ? null : modelArgs.get(name);
            if (v == null) v = p.defaultValue();
            if (v == null) {
                if (Boolean.TRUE.equals(p.required())) throw new ToolException("missing required param '" + name + "'");
                out.put(name, null);
                continue;
            }
            if ("integer".equals(p.type())) {
                int i;
                try { i = v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).trim()); }
                catch (NumberFormatException ex) { throw new ToolException("param '" + name + "' must be an integer"); }
                if (p.max() != null && i > p.max()) i = p.max();
                if (i < 0) i = 0;
                v = i;
            } else {
                v = String.valueOf(v);
            }
            out.put(name, v);
        }
        return out;
    }

    public String execute(ToolDef t, Map<String, Object> boundArgs, RunCtx ctx) {
        ToolHandler h = handlers.get(t.type());
        if (h == null) throw new ToolException("no handler for tool type " + t.type());
        return h.run(t, boundArgs, ctx);
    }
}

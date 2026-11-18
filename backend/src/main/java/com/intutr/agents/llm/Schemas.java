package com.intutr.agents.llm;

import com.intutr.agents.llm.Types.ToolSpec;
import java.util.*;

public final class Schemas {
    private Schemas() {}
    public static Map<String, Object> of(ToolSpec s) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> req = new ArrayList<>();
        if (s.params() != null) s.params().forEach((k, p) -> {
            Map<String, Object> pr = new LinkedHashMap<>();
            pr.put("type", jsonType(p.type()));
            props.put(k, pr);
            if (Boolean.TRUE.equals(p.required())) req.add(k);
        });
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("type", "object");
        o.put("properties", props);
        o.put("required", req);
        return o;
    }
    static String jsonType(String t) {
        return switch (t == null ? "string" : t) { case "integer", "int" -> "integer"; case "number" -> "number"; case "boolean" -> "boolean"; default -> "string"; };
    }
}

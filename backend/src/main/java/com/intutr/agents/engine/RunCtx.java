package com.intutr.agents.engine;

import java.util.*;
import java.util.function.Consumer;

/** Per-run context. Identity values here are the ONLY source for context-bound tool params. */
public class RunCtx {
    public String tenant = "acme", userId = "u1", userName = "Learner", language = "en", role = "Learner";
    public String runId, parentRunId, chatId, agentName, modelOverride;
    public String correlationId = UUID.randomUUID().toString().substring(0, 8);
    public int depth = 0;
    public Consumer<Map<String, Object>> sink = e -> { };

    public String value(String key) {
        return switch (key) { case "user_id" -> userId; case "tenant" -> tenant; case "language" -> language; default -> null; };
    }
    public RunCtx child() {
        RunCtx c = new RunCtx();
        c.tenant = tenant; c.userId = userId; c.userName = userName; c.language = language; c.role = role;
        c.correlationId = correlationId; c.modelOverride = modelOverride; c.depth = depth + 1; c.parentRunId = runId; c.sink = sink;
        return c;
    }
    public void emit(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        sink.accept(m);
    }
}

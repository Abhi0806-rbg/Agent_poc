package com.intutr.agents.api;

import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.AgentEngine;
import com.intutr.agents.engine.AgentEngine.RunResult;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.llm.Types.Msg;
import com.intutr.agents.trace.TraceRepo;
import com.intutr.agents.util.J;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ChatController {
    private final AgentEngine engine;
    private final ConfigRegistry cfg;
    private final TraceRepo trace;
    private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    public ChatController(AgentEngine engine, ConfigRegistry cfg, TraceRepo trace) {
        this.engine = engine; this.cfg = cfg; this.trace = trace; this.jdbc = trace.jdbc;
    }

    public record ChatReq(String chatId, String agent, String message, String mode) {}

    @GetMapping("/chats")
    public List<Map<String, Object>> chats(@RequestHeader(value = "X-Tenant", defaultValue = "acme") String tenant,
                                           @RequestHeader(value = "X-User", defaultValue = "u1") String user) {
        return jdbc.queryForList("SELECT id, title, agent, project, pinned, created_at FROM chat WHERE tenant_id=? AND user_id=? ORDER BY pinned DESC, created_at DESC LIMIT 100", tenant, user);
    }

    @GetMapping("/chats/{id}")
    public Map<String, Object> chat(@PathVariable String id, @RequestHeader(value = "X-Tenant", defaultValue = "acme") String tenant) {
        List<Map<String, Object>> c = jdbc.queryForList("SELECT id, title, agent, project, pinned FROM chat WHERE id=? AND tenant_id=?", id, tenant);
        if (c.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);
        Map<String, Object> out = new LinkedHashMap<>(c.get(0));
        out.put("messages", jdbc.queryForList("SELECT role, content, run_id FROM chat_message WHERE chat_id=? ORDER BY id", id));
        return out;
    }

    @PatchMapping("/chats/{id}")
    public void patch(@PathVariable String id, @RequestBody Map<String, Object> b, @RequestHeader(value = "X-Tenant", defaultValue = "acme") String tenant) {
        if (b.containsKey("title")) jdbc.update("UPDATE chat SET title=? WHERE id=? AND tenant_id=?", b.get("title"), id, tenant);
        if (b.containsKey("pinned")) jdbc.update("UPDATE chat SET pinned=? WHERE id=? AND tenant_id=?", Boolean.TRUE.equals(b.get("pinned")), id, tenant);
        if (b.containsKey("project")) jdbc.update("UPDATE chat SET project=? WHERE id=? AND tenant_id=?", b.get("project"), id, tenant);
    }

    @DeleteMapping("/chats/{id}")
    public void delete(@PathVariable String id, @RequestHeader(value = "X-Tenant", defaultValue = "acme") String tenant) {
        jdbc.update("DELETE FROM chat_message WHERE chat_id IN (SELECT id FROM chat WHERE id=? AND tenant_id=?)", id, tenant);
        jdbc.update("DELETE FROM chat WHERE id=? AND tenant_id=?", id, tenant);
    }

    /**
     * Server-sent events: chat, step, approval, delta, done.
     * The model call itself is not token-streamed in the POC; the final answer is streamed word by word.
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody ChatReq req,
                             @RequestHeader(value = "X-Tenant", defaultValue = "acme") String tenant,
                             @RequestHeader(value = "X-User", defaultValue = "u1") String user,
                             @RequestHeader(value = "X-Role", defaultValue = "Learner") String role) {
        SseEmitter em = new SseEmitter(180_000L);
        pool.submit(() -> {
            try {
                String chatId = req.chatId();
                List<Msg> history = new ArrayList<>();
                if (chatId == null || chatId.isBlank()) {
                    chatId = UUID.randomUUID().toString();
                    String title = J.clip(req.message().replaceAll("\\s+", " ").trim(), 48);
                    jdbc.update("INSERT INTO chat(id,tenant_id,user_id,title,agent) VALUES (?,?,?,?,?)", chatId, tenant, user, title, req.agent());
                } else {
                    List<Map<String, Object>> rows = jdbc.queryForList("SELECT role, content FROM chat_message WHERE chat_id=? AND role IN ('user','assistant') ORDER BY id DESC LIMIT ?",
                        chatId, cfg.platform().historyTurns() * 2);
                    Collections.reverse(rows);
                    for (Map<String, Object> r : rows) history.add(new Msg((String) r.get("role"), (String) r.get("content"), null, null));
                }
                send(em, "chat", Map.of("chatId", chatId));
                jdbc.update("INSERT INTO chat_message(chat_id,role,content) VALUES (?,?,?)", chatId, "user", req.message());

                RunCtx ctx = new RunCtx();
                ctx.tenant = tenant; ctx.userId = user; ctx.role = role; ctx.userName = trace.userName(tenant, user); ctx.chatId = chatId;
                ctx.modelOverride = "smart".equals(req.mode()) ? "smart" : "fast".equals(req.mode()) ? "fast" : null;
                ctx.sink = e -> send(em, String.valueOf(e.get("type")), e);

                RunResult r = engine.run(req.agent(), req.message(), history, ctx);
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("runId", r.runId()); done.put("status", r.status()); done.put("errorType", r.errorType());
                done.put("tokens", r.tokens()); done.put("cost", r.cost()); done.put("ms", r.ms());
                if ("COMPLETED".equals(r.status())) {
                    jdbc.update("INSERT INTO chat_message(chat_id,role,content,run_id) VALUES (?,?,?,?)", chatId, "assistant", r.text(), r.runId());
                    for (String w : r.text().split("(?<=\\s)")) { send(em, "delta", Map.of("text", w)); Thread.sleep(12); }
                } else if (!"WAITING_APPROVAL".equals(r.status())) {
                    done.put("text", r.text());
                    jdbc.update("INSERT INTO chat_message(chat_id,role,content,run_id) VALUES (?,?,?,?)", chatId, "error", r.text(), r.runId());
                }
                send(em, "done", done);
                em.complete();
            } catch (Exception e) {
                send(em, "done", Map.of("status", "FAILED", "errorType", "engine_error", "text", String.valueOf(e.getMessage())));
                em.complete();
            }
        });
        return em;
    }

    private static void send(SseEmitter em, String name, Object data) {
        try { synchronized (em) { em.send(SseEmitter.event().name(name).data(J.str(data))); } } catch (Exception ignored) { }
    }
}

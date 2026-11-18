package com.intutr.agents.api;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.AgentEngine;
import com.intutr.agents.engine.AgentEngine.RunResult;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.eval.EvalRunner;
import com.intutr.agents.knowledge.Retrieval;
import com.intutr.agents.llm.AnthropicClient;
import com.intutr.agents.llm.LlmGateway;
import com.intutr.agents.llm.OpenAiClient;
import com.intutr.agents.tools.ToolException;
import com.intutr.agents.tools.ToolExecutor;
import com.intutr.agents.trace.TraceRepo;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Console + config REST API. SQL rows come back snake_case; maps built here are camelCase. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final String T = "X-Tenant", U = "X-User", R = "X-Role";
    private final ConfigRegistry cfg;
    private final AgentEngine engine;
    private final TraceRepo trace;
    private final JdbcTemplate jdbc;
    private final ToolExecutor tools;
    private final Retrieval retrieval;
    private final EvalRunner evals;
    private final LlmGateway gateway;
    private final OpenAiClient openai;
    private final AnthropicClient anthropic;

    public ApiController(ConfigRegistry cfg, AgentEngine engine, TraceRepo trace, JdbcTemplate jdbc, ToolExecutor tools, Retrieval retrieval, EvalRunner evals,
                         LlmGateway gateway, OpenAiClient openai, AnthropicClient anthropic) {
        this.cfg = cfg; this.engine = engine; this.trace = trace; this.jdbc = jdbc; this.tools = tools; this.retrieval = retrieval;
        this.evals = evals; this.gateway = gateway; this.openai = openai; this.anthropic = anthropic;
    }

    private RunCtx ctx(String tenant, String user, String role) {
        RunCtx c = new RunCtx();
        c.tenant = tenant; c.userId = user; c.role = role; c.userName = trace.userName(tenant, user);
        return c;
    }

    // ---------- identity / health ----------
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader(value = T, defaultValue = "acme") String tenant, @RequestHeader(value = U, defaultValue = "u1") String user,
                                  @RequestHeader(value = R, defaultValue = "Developer") String role) {
        boolean live = cfg.models().values().stream().anyMatch(gateway::isLive);
        return Map.of("tenant", tenant, "user", user, "name", trace.userName(tenant, user), "role", role,
            "providers", Map.of("openai", openai.enabled(), "anthropic", anthropic.enabled()), "mockMode", !live);
    }

    // ---------- agents ----------
    @GetMapping("/agents")
    public List<Map<String, Object>> agents(@RequestHeader(value = T, defaultValue = "acme") String tenant) {
        Map<String, Map<String, Object>> stats = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT agent, count(*) AS runs, round(100.0*sum(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END)/count(*),0) AS success_rate, coalesce(sum(cost_usd),0) AS cost FROM agent_run WHERE tenant_id=? AND created_at > now() - interval '24 hours' GROUP BY agent", tenant))
            stats.put((String) r.get("agent"), r);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentDef a : cfg.agents().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> s = stats.getOrDefault(a.name(), Map.of());
            m.put("name", a.name()); m.put("description", a.description()); m.put("enabled", cfg.isEnabled(a)); m.put("model", a.model());
            m.put("prompt", a.prompt()); m.put("tools", cfg.toolsFor(a)); m.put("skills", a.skills() == null ? List.of() : a.skills());
            m.put("suggestions", a.suggestions() == null ? List.of() : a.suggestions());
            m.put("runs24h", s.getOrDefault("runs", 0)); m.put("successRate", s.getOrDefault("success_rate", 0)); m.put("cost24h", s.getOrDefault("cost", 0));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/agents/{name}")
    public Map<String, Object> agent(@PathVariable String name) {
        AgentDef a = cfg.agent(name);
        if (a == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("def", a, "yaml", cfg.agentYaml(name), "enabled", cfg.isEnabled(a), "configHash", cfg.hash(a), "limits", cfg.limits(a), "tools", cfg.toolsFor(a));
    }

    @PostMapping("/agents/{name}/enabled")
    public Map<String, Object> enabled(@PathVariable String name, @RequestBody Map<String, Object> b, @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        if (cfg.agent(name) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        cfg.setEnabled(name, Boolean.TRUE.equals(b.get("enabled")));
        return Map.of("name", name, "enabled", cfg.isEnabled(cfg.agent(name)));
    }

    /** Create or update agents/<name>.yaml. Validated; rolled back if invalid. */
    @PutMapping(value = "/agents/{name}/yaml", consumes = {"text/plain", "application/yaml"})
    public Map<String, Object> saveYaml(@PathVariable String name, @RequestBody String yaml, @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        List<String> errs = cfg.saveAgentYaml(name, yaml);
        return Map.of("ok", errs.isEmpty(), "errors", errs);
    }

    @GetMapping("/config/validate")
    public Map<String, Object> validate() { List<String> e = cfg.reload(); return Map.of("ok", e.isEmpty(), "errors", e); }

    // ---------- read-only config views ----------
    @GetMapping("/prompts") public Collection<Prompt> prompts() { return new LinkedHashMap<>(cfg.prompts()).entrySet().stream().filter(e -> e.getKey().contains("@")).map(Map.Entry::getValue).toList(); }
    @GetMapping("/tools") public Collection<ToolDef> toolDefs() { return cfg.tools().values(); }
    @GetMapping("/skills") public Collection<SkillDef> skills() { return cfg.skills().values(); }
    @GetMapping("/guardrails") public Guardrails guardrails() { return cfg.guardrails(); }

    @GetMapping("/config/source/{module}")
    public Map<String, Object> singletonConfigSource(@PathVariable String module, @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        return Map.of("yaml", cfg.singletonYaml(module));
    }

    @GetMapping("/config/source/{module}/{fileName}")
    public Map<String, Object> moduleConfigSource(@PathVariable String module, @PathVariable String fileName,
                                                  @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        return Map.of("yaml", cfg.moduleYaml(module, fileName));
    }

    @GetMapping("/evals/config")
    public List<Map<String, Object>> evalConfigs(@RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        return cfg.evalConfigs();
    }

    @PutMapping(value = "/config/{module}/{fileName}", consumes = {"text/plain", "application/yaml"})
    public Map<String, Object> saveModuleConfig(@PathVariable String module, @PathVariable String fileName, @RequestBody String yaml,
                                                @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        List<String> errors = cfg.saveModuleYaml(module, fileName, yaml);
        return Map.of("ok", errors.isEmpty(), "errors", errors);
    }

    @PutMapping(value = "/config/{module}", consumes = {"text/plain", "application/yaml"})
    public Map<String, Object> saveSingletonConfig(@PathVariable String module, @RequestBody String yaml,
                                                   @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        List<String> errors = cfg.saveSingletonYaml(module, yaml);
        return Map.of("ok", errors.isEmpty(), "errors", errors);
    }

    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        List<Map<String, Object>> out = new ArrayList<>();
        cfg.models().forEach((alias, m) -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("alias", alias); o.put("provider", m.provider()); o.put("name", m.name()); o.put("kind", m.kind()); o.put("fallback", m.fallback());
            o.put("inputCostPerMillion", m.inputCostPerMillion()); o.put("outputCostPerMillion", m.outputCostPerMillion());
            o.put("live", gateway.isLive(m)); // false = served by the mock client
            out.add(o);
        });
        return out;
    }

    @PostMapping("/tools/{name}/test")
    public Map<String, Object> testTool(@PathVariable String name, @RequestBody(required = false) Map<String, Object> args,
                                        @RequestHeader(value = T, defaultValue = "acme") String tenant, @RequestHeader(value = U, defaultValue = "u1") String user,
                                        @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        ToolDef t = cfg.tool(name);
        if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if ("agent".equals(t.type()) || Boolean.TRUE.equals(t.requiresApproval())) return Map.of("error", "This tool type is tested through an agent run (approval/agent tools are not run directly).");
        try {
            RunCtx c = ctx(tenant, user, role);
            c.agentName = "tool-test";
            return Map.of("output", tools.execute(t, tools.bind(t, args, c), c));
        } catch (ToolException e) { return Map.of("error", e.getMessage()); }
    }

    // ---------- runs ----------
    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestHeader(value = T, defaultValue = "acme") String tenant, @RequestParam(required = false) String agent,
                                          @RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("SELECT id, agent, status, error_type, total_tokens, cost_usd, duration_ms, parent_run_id, user_id, config_hash, created_at, left(input, 120) AS input FROM agent_run WHERE tenant_id=? AND (?::text IS NULL OR agent=?) AND (?::text IS NULL OR status=?) ORDER BY created_at DESC LIMIT ?",
            tenant, blank(agent), blank(agent), blank(status), blank(status), Math.min(limit, 200));
    }

    @GetMapping("/runs/{id}")
    public Map<String, Object> run(@PathVariable String id, @RequestHeader(value = T, defaultValue = "acme") String tenant) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT id, agent, status, input, output, error_type, error_message, total_tokens, cost_usd, duration_ms, parent_run_id, user_id, correlation_id, config_hash, chat_id, created_at FROM agent_run WHERE id=? AND tenant_id=?", id, tenant);
        if (r.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Map<String, Object> out = new LinkedHashMap<>(r.get(0));
        out.put("steps", jdbc.queryForList("SELECT step_no, type, name, input, output, error, duration_ms FROM agent_step WHERE run_id=? ORDER BY id", id));
        out.put("llmCalls", jdbc.queryForList("SELECT step_no, provider, model, prompt_tokens, completion_tokens, cost_usd, latency_ms, finish_reason, error_type, retry_no, estimated FROM llm_call WHERE run_id=? ORDER BY id", id));
        out.put("children", jdbc.queryForList("SELECT id, agent, status, duration_ms, total_tokens FROM agent_run WHERE parent_run_id=? ORDER BY created_at", id));
        return out;
    }

    /** Re-runs the same input, optionally with another agent or model alias, so you can compare traces side by side. */
    @PostMapping("/runs/{id}/replay")
    public RunResult replay(@PathVariable String id, @RequestBody(required = false) Map<String, String> b, @RequestHeader(value = T, defaultValue = "acme") String tenant,
                            @RequestHeader(value = U, defaultValue = "u1") String user, @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        Map<String, Object> run = trace.run(id);
        if (run == null || !tenant.equals(run.get("tenant_id"))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        RunCtx c = ctx(tenant, (String) run.get("user_id"), role);
        if (b != null && b.get("model") != null && !b.get("model").isBlank()) c.modelOverride = b.get("model");
        String agent = b != null && b.get("agent") != null && !b.get("agent").isBlank() ? b.get("agent") : (String) run.get("agent");
        RunResult r = engine.run(agent, (String) run.get("input"), null, c);
        if (r.approvalId() != null) engine.resume(r.approvalId(), false, "replay-auto-reject");
        return r;
    }

    // ---------- approvals ----------
    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(@RequestHeader(value = T, defaultValue = "acme") String tenant, @RequestParam(defaultValue = "PENDING") String status) {
        return jdbc.queryForList("SELECT a.id, a.run_id, a.tool, a.args, a.status, a.created_at, r.agent, left(r.input,120) AS input FROM approval a JOIN agent_run r ON r.id=a.run_id WHERE a.tenant_id=? AND a.status=? ORDER BY a.created_at DESC LIMIT 100", tenant, status);
    }

    @PostMapping("/approvals/{id}/decision")
    public RunResult decide(@PathVariable String id, @RequestBody Map<String, Object> b, @RequestHeader(value = U, defaultValue = "u1") String user,
                            @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Manager", "Developer", "Admin");
        RunResult r;
        try { r = engine.resume(id, Boolean.TRUE.equals(b.get("approved")), user); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage()); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()); }
        Map<String, Object> run = trace.run(r.runId());
        String chatId = run == null ? null : (String) run.get("chat_id");
        if (chatId != null) {
            boolean ok = "COMPLETED".equals(r.status());
            jdbc.update("INSERT INTO chat_message(chat_id,role,content,run_id) VALUES (?,?,?,?)", chatId, ok ? "assistant" : "error", r.text(), r.runId());
        }
        return r;
    }

    // ---------- knowledge ----------
    @GetMapping("/knowledge/chunks")
    public List<Map<String, Object>> chunks(@RequestHeader(value = T, defaultValue = "acme") String tenant, @RequestParam(defaultValue = "") String q) {
        return jdbc.queryForList("SELECT id, collection, title, left(text, 160) AS text, embed_model, embed_version, is_synthetic FROM knowledge_chunk WHERE tenant_id=? AND (title ILIKE ? OR text ILIKE ?) ORDER BY title LIMIT 100", tenant, "%" + q + "%", "%" + q + "%");
    }

    @PostMapping("/knowledge/search")
    public List<Map<String, Object>> search(@RequestBody Map<String, Object> b, @RequestHeader(value = T, defaultValue = "acme") String tenant) {
        return retrieval.search(tenant, "lob_content", String.valueOf(b.get("query")), ((Number) b.getOrDefault("topK", 5)).intValue(), ((Number) b.getOrDefault("minScore", 0.0)).doubleValue());
    }

    @PostMapping("/admin/reindex")
    public Map<String, Object> reindex(@RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        return Map.of("reindexed", retrieval.reindex());
    }

    // ---------- evals ----------
    @PostMapping("/evals/run")
    public Map<String, Object> runEvals(@RequestHeader(value = T, defaultValue = "acme") String tenant, @RequestHeader(value = R, defaultValue = "Developer") String role) {
        Roles.require(role, "Developer", "Admin");
        return evals.runAll(tenant);
    }
    @GetMapping("/evals/latest") public Object latestEval() { Map<String, Object> l = evals.latest(); return l == null ? Map.of() : l; }

    // ---------- dashboard / cost ----------
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestHeader(value = T, defaultValue = "acme") String tenant) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("kpi", jdbc.queryForMap("SELECT count(*) AS runs, coalesce(round(100.0*sum(CASE WHEN status='COMPLETED' THEN 1 ELSE 0 END)/nullif(count(*),0),0),0) AS success_rate, coalesce(sum(total_tokens),0) AS tokens, coalesce(sum(cost_usd),0) AS cost, coalesce(round(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)),0) AS p95_ms FROM agent_run WHERE tenant_id=? AND created_at > now() - interval '24 hours'", tenant));
        o.put("errorsByType", jdbc.queryForList("SELECT error_type, count(*) AS n FROM agent_run WHERE tenant_id=? AND error_type IS NOT NULL AND created_at > now() - interval '7 days' GROUP BY error_type ORDER BY n DESC", tenant));
        o.put("costByAgentDay", jdbc.queryForList("SELECT agent, date_trunc('day', created_at)::date AS day, sum(cost_usd) AS cost, count(*) AS runs FROM agent_run WHERE tenant_id=? AND created_at > now() - interval '7 days' GROUP BY 1,2 ORDER BY 2", tenant));
        o.put("recentFailed", jdbc.queryForList("SELECT id, agent, error_type, created_at FROM agent_run WHERE tenant_id=? AND status IN ('FAILED','BUDGET_EXCEEDED') ORDER BY created_at DESC LIMIT 8", tenant));
        o.put("pendingApprovals", jdbc.queryForObject("SELECT count(*) FROM approval WHERE tenant_id=? AND status='PENDING'", Integer.class, tenant));
        o.put("configOk", cfg.reload().isEmpty());
        return o;
    }

    @GetMapping("/cost")
    public Map<String, Object> cost(@RequestHeader(value = T, defaultValue = "acme") String tenant) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("byAgent", jdbc.queryForList("SELECT agent, count(*) AS runs, sum(total_tokens) AS tokens, sum(cost_usd) AS cost FROM agent_run WHERE tenant_id=? GROUP BY agent ORDER BY tokens DESC", tenant));
        o.put("byModel", jdbc.queryForList("SELECT l.provider, l.model, count(*) AS calls, sum(l.prompt_tokens) AS prompt_tokens, sum(l.completion_tokens) AS completion_tokens, sum(l.cost_usd) AS cost FROM llm_call l JOIN agent_run r ON r.id=l.run_id WHERE r.tenant_id=? GROUP BY 1,2 ORDER BY calls DESC", tenant));
        o.put("topRuns", jdbc.queryForList("SELECT id, agent, total_tokens, cost_usd, status, left(input,80) AS input FROM agent_run WHERE tenant_id=? ORDER BY total_tokens DESC LIMIT 10", tenant));
        o.put("note", "Cost is 0 until you fill input_cost_per_million / output_cost_per_million in config/models.yaml. Seed rows use made-up prices. Mock-mode token counts are estimates.");
        return o;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}

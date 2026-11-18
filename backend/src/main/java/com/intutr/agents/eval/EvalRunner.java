package com.intutr.agents.eval;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.AgentEngine;
import com.intutr.agents.engine.AgentEngine.RunResult;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.knowledge.Retrieval;
import com.intutr.agents.llm.LlmGateway;
import com.intutr.agents.trace.TraceRepo;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Runs eval cases from config/evals and retrieval pairs from config/knowledge. Latest report is kept in memory. */
@Component
public class EvalRunner {
    private final ConfigRegistry cfg;
    private final AgentEngine engine;
    private final Retrieval retrieval;
    private final TraceRepo trace;
    private final LlmGateway gateway;
    private final AtomicReference<Map<String, Object>> latest = new AtomicReference<>();

    public EvalRunner(ConfigRegistry cfg, AgentEngine engine, Retrieval retrieval, TraceRepo trace, LlmGateway gateway) {
        this.cfg = cfg; this.engine = engine; this.retrieval = retrieval; this.trace = trace; this.gateway = gateway;
    }
    public Map<String, Object> latest() { return latest.get(); }

    public Map<String, Object> runAll(String tenant) {
        boolean live = cfg.models().values().stream().anyMatch(gateway::isLive);
        List<Map<String, Object>> cases = new ArrayList<>();
        int pass = 0, total = 0, tokens = 0;
        double cost = 0;
        List<Long> lat = new ArrayList<>();
        for (EvalSet set : cfg.evals()) {
            for (EvalCase c : set.cases()) {
                total++;
                RunCtx ctx = new RunCtx();
                ctx.tenant = tenant; ctx.userId = "u1"; ctx.userName = trace.userName(tenant, "u1"); ctx.role = "Admin";
                RunResult r = engine.run(set.agent(), c.input(), null, ctx);
                Set<String> called = new HashSet<>(trace.jdbc.queryForList("SELECT name FROM agent_step WHERE run_id=? AND type='tool'", String.class, r.runId()));
                List<String> pendingTools = trace.jdbc.queryForList("SELECT tool FROM approval WHERE run_id=? AND status='PENDING'", String.class, r.runId());
                called.addAll(pendingTools);
                if (r.approvalId() != null) engine.resume(r.approvalId(), false, "eval"); // keep the demo queue clean
                int llmSteps = trace.jdbc.queryForObject("SELECT count(*) FROM agent_step WHERE run_id=? AND type='llm'", Integer.class, r.runId());
                List<String> fails = new ArrayList<>();
                if (c.callsTools() != null) for (String t : c.callsTools()) if (!called.contains(t)) fails.add("expected tool " + t);
                if (c.notCallsTools() != null) for (String t : c.notCallsTools()) if (called.contains(t)) fails.add("forbidden tool " + t);
                if (c.answerNotContains() != null) for (String s : c.answerNotContains()) if (r.text() != null && r.text().toLowerCase().contains(s.toLowerCase())) fails.add("answer contains '" + s + "'");
                if (c.maxSteps() != null && llmSteps > c.maxSteps()) fails.add("too many steps: " + llmSteps);
                if ("FAILED".equals(r.status()) || "BUDGET_EXCEEDED".equals(r.status())) fails.add("run " + r.status() + " " + r.errorType());
                boolean ok = fails.isEmpty();
                if (ok) pass++;
                tokens += r.tokens(); cost += r.cost(); lat.add(r.ms());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("agent", set.agent()); row.put("case", c.id()); row.put("pass", ok); row.put("failures", fails);
                row.put("steps", llmSteps); row.put("tokens", r.tokens()); row.put("cost", r.cost()); row.put("ms", r.ms()); row.put("runId", r.runId());
                cases.add(row);
            }
        }
        // retrieval: recall@5 and MRR
        int hits = 0; double rr = 0; int pairs = 0;
        if (cfg.retrieval() != null && cfg.retrieval().pairs() != null) {
            for (RetrievalPair p : cfg.retrieval().pairs()) {
                pairs++;
                var res = retrieval.search(tenant, "lob_content", p.query(), 5, 0.0);
                for (int i = 0; i < res.size(); i++) if (p.expectedTitle().equals(res.get(i).get("title"))) { hits++; rr += 1.0 / (i + 1); break; }
            }
        }
        Collections.sort(lat);
        long p95 = lat.isEmpty() ? 0 : lat.get((int) Math.min(lat.size() - 1, Math.ceil(lat.size() * 0.95) - 1));
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("ranAt", Instant.now().toString());
        rep.put("questionBeingEvaluated", "Do the configured agents call the right tools, respect guardrails and stay in budget, and does retrieval find the right content?");
        rep.put("baseline", live ? "Live providers are configured. No stored baseline yet: run again after a config change and compare." : "Mock provider (no API keys). Numbers describe the plumbing, NOT model quality.");
        rep.put("metrics", Map.of("agentCasesPassed", pass, "agentCasesTotal", total, "retrievalRecallAt5", pairs == 0 ? 0 : (double) hits / pairs,
            "retrievalMRR", pairs == 0 ? 0 : rr / pairs, "retrievalPairs", pairs, "totalTokens", tokens, "totalCostUsd", cost, "p95LatencyMs", p95));
        rep.put("confidence", total < 30 ? "Low: " + total + " cases and " + pairs + " retrieval pairs are too few for statistical claims. Treat as a smoke test." : "Moderate");
        rep.put("gapToRealWorld", "Cases are hand-written and run as one demo user. They do not cover real learner phrasing, long conversations, or concurrent load.");
        rep.put("cases", cases);
        latest.set(rep);
        return rep;
    }
}

package com.intutr.agents.engine;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.llm.LlmException;
import com.intutr.agents.llm.LlmGateway;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.tools.ToolException;
import com.intutr.agents.tools.ToolExecutor;
import com.intutr.agents.trace.TraceRepo;
import com.intutr.agents.util.J;
import java.time.LocalDate;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * The agent loop. It knows nothing about specific agents or tools: everything comes from config.
 * Guarantees: step / token / cost budgets, identical-call loop detection, human approval pause + resume,
 * tool args bound from the session (not the model), tool output truncated, every step traced.
 */
@Component
public class AgentEngine {
    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);
    private final ConfigRegistry cfg;
    private final LlmGateway llm;
    private final ToolExecutor tools;
    private final TraceRepo trace;

    /** For FAILED runs, `text` carries the error message. approvalId is set when status = WAITING_APPROVAL. */
    public record RunResult(String runId, String status, String text, String errorType, int tokens, double cost, long ms, String approvalId) {}

    public AgentEngine(ConfigRegistry cfg, LlmGateway llm, ToolExecutor tools, TraceRepo trace) {
        this.cfg = cfg; this.llm = llm; this.tools = tools; this.trace = trace;
    }

    public RunResult run(String agentName, String input, List<Msg> history, RunCtx ctx) {
        AgentDef a = cfg.agent(agentName);
        if (a == null) throw new IllegalArgumentException("Unknown agent: " + agentName);
        ctx.runId = UUID.randomUUID().toString();
        ctx.agentName = agentName;
        trace.createRun(ctx.runId, ctx.tenant, agentName, input, ctx.userId, ctx.parentRunId, ctx.correlationId, cfg.hash(a), ctx.chatId);
        RunState st = new RunState();
        if (!cfg.isEnabled(a)) return fail(ctx, st, System.currentTimeMillis(), "agent_disabled", "Agent '" + agentName + "' is disabled");
        Map<String, String> vars = Map.of("tenant.name", cap(ctx.tenant), "user.name", ctx.userName, "today", LocalDate.now().toString(), "language", ctx.language);
        st.modelOverride = ctx.modelOverride;
        st.messages.add(Msg.system(cfg.renderSystem(a, vars)));
        if (history != null) st.messages.addAll(history);
        st.messages.add(Msg.user(input));
        return loop(a, st, ctx, System.currentTimeMillis());
    }

    /** Continues a run that was paused for approval. */
    public RunResult resume(String approvalId, boolean approved, String decidedBy) {
        Map<String, Object> ap = trace.approval(approvalId);
        if (ap == null) throw new IllegalArgumentException("Unknown approval");
        if (!"PENDING".equals(ap.get("status"))) throw new IllegalStateException("Approval already decided");
        String runId = (String) ap.get("run_id");
        Map<String, Object> run = trace.run(runId);
        RunState st = J.read((String) run.get("state"), RunState.class);
        RunCtx ctx = new RunCtx();
        ctx.runId = runId;
        ctx.tenant = (String) run.get("tenant_id");
        ctx.userId = (String) run.get("user_id");
        ctx.chatId = (String) run.get("chat_id");
        ctx.correlationId = (String) run.get("correlation_id");
        ctx.agentName = (String) run.get("agent");
        ctx.userName = trace.userName(ctx.tenant, ctx.userId);
        trace.decideApproval(approvalId, approved ? "APPROVED" : "REJECTED", decidedBy);
        ToolCall first = st.pending.get(0);
        if (approved) st.approved.add(first.id());
        else { st.messages.add(Msg.tool(first.id(), "The human approver REJECTED this action. Do not retry it; tell the user it was not done.")); st.pending.remove(0); }
        trace.setStatus(runId, "RUNNING");
        return loop(cfg.agent(ctx.agentName), st, ctx, System.currentTimeMillis());
    }

    private RunResult loop(AgentDef a, RunState st, RunCtx ctx, long t0) {
        MDC.put("run_id", ctx.runId.substring(0, 8));
        try {
            Limits lim = cfg.limits(a);
            List<ToolSpec> specs = tools.specsFor(cfg.toolsFor(a));
            while (true) {
                if (!st.pending.isEmpty()) { RunResult x = processCalls(a, st, ctx, t0); if (x != null) return x; }
                if (over(st, lim)) return fail(ctx, st, t0, "budget_exceeded", "Token or cost budget exceeded");
                if (st.step >= lim.maxSteps()) return fail(ctx, st, t0, "max_steps", "Reached max steps (" + lim.maxSteps() + ")");
                st.step++;
                String alias = st.modelOverride != null ? st.modelOverride : (a.model() != null ? a.model() : cfg.platform().defaultModel());
                long s0 = System.currentTimeMillis();
                LlmGateway.Result r;
                try {
                    r = llm.call(alias, new LlmRequest(st.messages, specs), ctx, st.step);
                } catch (LlmException e) {
                    long ms = System.currentTimeMillis() - s0;
                    trace.step(ctx.runId, st.step, "llm", alias, null, null, e.type + ": " + e.getMessage(), ms);
                    ctx.emit("type", "step", "kind", "llm", "agent", a.name(), "name", alias, "summary", "ERROR " + e.type, "ms", ms);
                    return fail(ctx, st, t0, e.type, e.getMessage());
                }
                LlmResponse resp = r.resp();
                st.tokens += resp.promptTokens() + resp.completionTokens();
                st.cost += r.cost();
                String label = r.model() + (r.fallback() ? " (fallback)" : "");
                trace.step(ctx.runId, st.step, "llm", label, null, J.clip(resp.text(), 500), null, r.ms());
                ctx.emit("type", "step", "kind", "llm", "agent", a.name(), "name", label, "summary",
                    resp.toolCalls().isEmpty() ? "answer" : "wants " + resp.toolCalls().stream().map(ToolCall::name).toList(), "ms", r.ms(),
                    "tokens", resp.promptTokens() + resp.completionTokens());
                if (over(st, lim)) return fail(ctx, st, t0, "budget_exceeded", "Token or cost budget exceeded");
                if (resp.toolCalls().isEmpty()) {
                    long ms = st.elapsed + System.currentTimeMillis() - t0;
                    trace.finishRun(ctx.runId, "COMPLETED", resp.text(), null, null, st.tokens, st.cost, ms, null);
                    return new RunResult(ctx.runId, "COMPLETED", resp.text(), null, st.tokens, st.cost, ms, null);
                }
                st.messages.add(Msg.assistant(resp.text(), resp.toolCalls()));
                st.pending = new ArrayList<>(resp.toolCalls());
            }
        } catch (RuntimeException ex) {
            log.error("engine error", ex);
            return fail(ctx, st, t0, "engine_error", String.valueOf(ex.getMessage()));
        } finally {
            MDC.remove("run_id");
        }
    }

    /** Executes queued tool calls. Returns a RunResult when the run must stop (paused / failed), otherwise null. */
    private RunResult processCalls(AgentDef a, RunState st, RunCtx ctx, long t0) {
        List<ToolCall> work = new ArrayList<>(st.pending);
        List<String> allowed = cfg.toolsFor(a);
        for (int i = 0; i < work.size(); i++) {
            ToolCall c = work.get(i);
            ToolDef t = cfg.tool(c.name());
            if (t == null || !allowed.contains(c.name())) {
                st.messages.add(Msg.tool(c.id(), "ERROR: unknown tool '" + c.name() + "'"));
                trace.step(ctx.runId, st.step, "tool", c.name(), J.str(c.args()), null, "unknown tool", 0);
                continue;
            }
            String key = c.name() + J.str(c.args());
            if (st.seen.merge(key, 1, Integer::sum) >= 3) { st.pending = new ArrayList<>(); return fail(ctx, st, t0, "loop_detected", "Same tool call repeated 3 times: " + c.name()); }

            boolean needsApproval = Boolean.TRUE.equals(t.requiresApproval())
                || (cfg.guardrails().approvalsRequiredFor() != null && cfg.guardrails().approvalsRequiredFor().contains(t.name()));
            if (needsApproval && !st.approved.contains(c.id())) {
                st.pending = new ArrayList<>(work.subList(i, work.size()));
                String aid = UUID.randomUUID().toString();
                long ms = st.elapsed + System.currentTimeMillis() - t0;
                st.elapsed = ms;
                trace.createApproval(aid, ctx.runId, ctx.tenant, t.name(), J.str(c.args()));
                trace.finishRun(ctx.runId, "WAITING_APPROVAL", null, null, null, st.tokens, st.cost, ms, J.str(st));
                ctx.emit("type", "approval", "approvalId", aid, "tool", t.name(), "args", c.args());
                return new RunResult(ctx.runId, "WAITING_APPROVAL", "", null, st.tokens, st.cost, ms, aid);
            }

            long s0 = System.currentTimeMillis();
            String out, err = null;
            try {
                out = tools.execute(t, tools.bind(t, c.args(), ctx), ctx);
            } catch (ToolException e) {
                err = e.getMessage();
                out = "ERROR: " + err;
            }
            out = J.clip(out, cfg.platform().toolResultMaxChars());
            long ms = System.currentTimeMillis() - s0;
            trace.step(ctx.runId, st.step, "tool", t.name(), J.str(c.args()), out, err, ms);
            ctx.emit("type", "step", "kind", "tool", "agent", a.name(), "name", t.name(), "summary", J.clip(out, 160), "ms", ms);
            st.messages.add(Msg.tool(c.id(), out));
        }
        st.pending = new ArrayList<>();
        return null;
    }

    private RunResult fail(RunCtx ctx, RunState st, long t0, String type, String msg) {
        long ms = st.elapsed + System.currentTimeMillis() - t0;
        String status = "budget_exceeded".equals(type) ? "BUDGET_EXCEEDED" : "FAILED";
        trace.finishRun(ctx.runId, status, null, type, msg, st.tokens, st.cost, ms, null);
        return new RunResult(ctx.runId, status, msg, type, st.tokens, st.cost, ms, null);
    }

    private static boolean over(RunState st, Limits lim) {
        return st.tokens > lim.maxTokens() || (lim.maxCostUsd() > 0 && st.cost > lim.maxCostUsd());
    }
    private static String cap(String s) { return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}

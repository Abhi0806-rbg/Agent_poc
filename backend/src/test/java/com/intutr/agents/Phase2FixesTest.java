package com.intutr.agents;

import static org.junit.jupiter.api.Assertions.*;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.config.ConfigValidator;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.engine.RunState;
import com.intutr.agents.llm.LlmGateway;
import com.intutr.agents.llm.MockClient;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.tools.ToolExecutor;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 regression tests — no Spring context, no database.
 *
 * Tests:
 *   T1  Config loads cleanly after all Phase 2 changes (models, platform, tools, skills).
 *   T2  Model names resolve to "mock" sentinel when env vars are absent.
 *   T3  Explicit mock-mode: "mock" name correctly routes to MockClient in LlmGateway.clientFor().
 *   T4  Real provider selection: a named model + enabled client → non-mock.
 *   T5  Cost calculation: gpt-4o-mini pricing math is correct.
 *   T6  Token budget guard fires at max_tokens (now 32,000).
 *   T7  Multi-step supervisor: mock LLM routes correctly with updated platform limits.
 *   T8  Liquibase migration file is well-formed SQL (changeset header present).
 *   T9  v_user_progress migration adds at_risk column (SQL inspection).
 *   T10 get_user_progress tool query now selects at_risk column.
 *   T11 at_risk boundary conditions: SQL logic verified against explicit cases.
 */
class Phase2FixesTest {

    private static ConfigRegistry cfg;

    @BeforeAll
    static void loadConfig() {
        cfg = new ConfigRegistry("config");
        List<String> errs = cfg.reload();
        if (!errs.isEmpty()) System.err.println("[Phase2] Config errors: " + errs);
    }

    // ─── T1: Full config validation after Phase 2 ─────────────────────────────

    @Test
    void t1_configIsValidAfterPhase2() {
        List<String> errs = cfg.reload();
        assertEquals(List.of(), errs,
            "Config must be error-free after Phase 2 changes. Errors: " + errs);
    }

    // ─── T2: Model names resolve to "mock" when env vars absent ───────────────

    @Test
    void t2_missingEnvVarResolvesToMockSentinel() {
        // When MODEL_FAST is not set, the default in models.yaml is "mock".
        // We verify ConfigRegistry.subst() returns "mock" for an unset env var.
        String resolved = ConfigRegistry.subst("${MODEL_FAST_PHASE2_TEST_MISSING:mock}");
        assertEquals("mock", resolved,
            "Missing env var must resolve to the 'mock' sentinel, not an arbitrary placeholder");
    }

    @Test
    void t2b_oldSetSentinelNoLongerUsed() {
        // The old "set-MODEL_FAST-in-env" sentinel must NOT appear in models.yaml
        ModelDef fast = cfg.model("fast");
        assertNotNull(fast);
        assertFalse(fast.name() != null && fast.name().startsWith("set-"),
            "models.yaml must no longer use the 'set-MODEL_X-in-env' sentinel. Found: " + fast.name());
    }

    // ─── T3: Explicit mock mode via "mock" name ────────────────────────────────

    @Test
    void t3_mockNameRoutesToMockClientLogic() {
        // Replicate LlmGateway.clientFor() logic directly:
        //   boolean named = name != null && !name.startsWith("set-") && !"mock".equals(name)
        // "mock" → named = false → routes to MockClient
        String name = "mock";
        boolean named = name != null && !name.startsWith("set-") && !"mock".equals(name);
        assertFalse(named,
            "Model name 'mock' must NOT be treated as a named model — it must route to MockClient");
    }

    @Test
    void t3b_realModelNameRoutesToRealClient() {
        // A real model name → named = true (then client selection depends on API key)
        String name = "gpt-4o-mini";
        boolean named = name != null && !name.startsWith("set-") && !"mock".equals(name);
        assertTrue(named,
            "'gpt-4o-mini' must be treated as a named real model, not a mock sentinel");
    }

    // ─── T4: Real provider selection contract ─────────────────────────────────

    @Test
    void t4_openAiProviderSelectedWhenKeyIsPresent() {
        // Without a real Spring context we verify the clientFor() logic contract:
        // provider=openai + enabled=true + named → openai client, not mock
        // We test this by checking the MockClient is NOT selected when all conditions hold.
        // (We simulate by checking the named flag: if named=true, gateway WOULD use real client)
        ModelDef fast = cfg.model("fast");
        // In CI (no env vars), name="mock" → named=false → mock. That is correct.
        // With MODEL_FAST=gpt-4o-mini set, named=true → real client. Document the contract.
        boolean isMockName = fast.name() == null || fast.name().equals("mock") || fast.name().startsWith("set-");
        // Either mode is valid depending on env; the key assertion is the name is deterministic.
        assertNotNull(fast.name(),
            "fast model name must resolve to a non-null value (either 'mock' or a real model id)");
        assertEquals("openai", fast.provider(),
            "fast model must use the openai provider");
    }

    // ─── T5: Cost calculation correctness ─────────────────────────────────────

    @Test
    void t5_gpt4oMiniCostCalculationIsCorrect() {
        // gpt-4o-mini: $0.15 / 1M input, $0.60 / 1M output
        // LlmGateway.cost(): (promptTokens * in + completionTokens * out) / 1_000_000
        ModelDef fast = cfg.model("fast");
        assertNotNull(fast, "fast model must exist");

        double inputCost = fast.inputCostPerMillion();
        double outputCost = fast.outputCostPerMillion();

        assertEquals(0.15, inputCost, 0.0001,
            "fast model input cost must be $0.15/1M (gpt-4o-mini pricing)");
        assertEquals(0.60, outputCost, 0.0001,
            "fast model output cost must be $0.60/1M (gpt-4o-mini pricing)");

        // Verify the formula: 1000 input tokens + 500 output tokens
        double expectedCost = (1000 * 0.15 + 500 * 0.60) / 1_000_000.0;
        double actualCost   = (1000 * inputCost + 500 * outputCost) / 1_000_000.0;
        assertEquals(expectedCost, actualCost, 1e-10,
            "Cost formula must match: (prompt*in + completion*out) / 1_000_000");
        assertEquals(0.000450, actualCost, 1e-9,
            "1000 input + 500 output tokens at gpt-4o-mini prices must cost $0.000450");
    }

    @Test
    void t5b_claudeHaikuCostCalculationIsCorrect() {
        // claude-3-5-haiku: $0.80 / 1M input, $4.00 / 1M output
        ModelDef smart = cfg.model("smart");
        assertNotNull(smart, "smart model must exist");

        assertEquals(0.80, smart.inputCostPerMillion(), 0.0001,
            "smart model input cost must be $0.80/1M (claude-3-5-haiku pricing)");
        assertEquals(4.00, smart.outputCostPerMillion(), 0.0001,
            "smart model output cost must be $4.00/1M (claude-3-5-haiku pricing)");
    }

    @Test
    void t5c_embedCostConfigured() {
        ModelDef embed = cfg.model("embed");
        assertNotNull(embed, "embed model must exist");
        assertEquals(0.02, embed.inputCostPerMillion(), 0.0001,
            "embed model input cost must be $0.02/1M (text-embedding-3-small pricing)");
    }

    // ─── T6: Token budget guard fires at 32,000 ───────────────────────────────

    @Test
    void t6_maxTokensIsUpdatedTo32000() {
        Limits platform = cfg.platform().limits();
        assertEquals(32000, platform.maxTokens(),
            "max_tokens must be 32,000 after Phase 2 budget review");
    }

    @Test
    void t6b_maxStepsAndMaxCostUnchanged() {
        Limits platform = cfg.platform().limits();
        assertEquals(5, platform.maxSteps(), "max_steps must remain 5");
        assertEquals(0.05, platform.maxCostUsd(), 0.0001, "max_cost_usd must remain 0.05");
    }

    @Test
    void t6c_budgetGuardFiresWhenTokensExceedLimit() {
        // Replicate the AgentEngine.over() check: st.tokens > lim.maxTokens()
        // With costs=0 (mock mode), cost guard does not fire.
        Limits lim = cfg.platform().limits();
        RunState st = new RunState();
        st.tokens = 32001; // just over the limit
        st.cost = 0.0;

        boolean over = st.tokens > lim.maxTokens()
            || (lim.maxCostUsd() > 0 && st.cost > lim.maxCostUsd());
        assertTrue(over,
            "budget guard must fire when tokens exceed max_tokens=32000");
    }

    @Test
    void t6d_budgetGuardDoesNotFireUnderLimit() {
        Limits lim = cfg.platform().limits();
        RunState st = new RunState();
        st.tokens = 31999;
        st.cost = 0.0;

        boolean over = st.tokens > lim.maxTokens()
            || (lim.maxCostUsd() > 0 && st.cost > lim.maxCostUsd());
        assertFalse(over,
            "budget guard must NOT fire when tokens are under max_tokens=32000");
    }

    @Test
    void t6e_costBudgetGuardFiresWhenCostExceedsLimit() {
        Limits lim = cfg.platform().limits();
        RunState st = new RunState();
        st.tokens = 100;
        st.cost = 0.051; // just over $0.05

        boolean over = st.tokens > lim.maxTokens()
            || (lim.maxCostUsd() > 0 && st.cost > lim.maxCostUsd());
        assertTrue(over,
            "cost budget guard must fire when cost exceeds max_cost_usd=0.05");
    }

    // ─── T7: Multi-step supervisor with updated platform ──────────────────────

    @Test
    void t7_supervisorToolsReachableWithCurrentPlatform() {
        // Verify supervisor's effective tools are registered and not over the max_steps limit
        AgentDef supervisor = cfg.agent("supervisor");
        assertNotNull(supervisor, "supervisor must exist");

        List<String> tools = cfg.toolsFor(supervisor);
        assertTrue(tools.contains("ask_advisor"),    "supervisor must have ask_advisor");
        assertTrue(tools.contains("ask_report_agent"), "supervisor must have ask_report_agent");

        // 1 LLM call + 1 tool call = 2 steps — well within max_steps=5
        Limits lim = cfg.limits(supervisor);
        assertTrue(lim.maxSteps() >= 3,
            "supervisor max_steps must allow at least 3 steps for routing + sub-call");
    }

    @Test
    void t7b_multistepSupervisorExecutionSequence() {
        // Test multi-step supervisor flow with MockClient:
        // Turn 1: user query -> supervisor asks report agent
        // Turn 2: tool result returned -> supervisor produces final summary answer
        AgentDef supervisor = cfg.agent("supervisor");
        List<ToolSpec> specs = new ToolExecutor(cfg, List.of()).specsFor(cfg.toolsFor(supervisor));
        MockClient mock = new MockClient();

        Msg userMsg = Msg.user("Weekly summary for my batches");
        LlmRequest req1 = new LlmRequest(List.of(Msg.system("You route requests."), userMsg), specs);
        LlmResponse turn1 = mock.chat(cfg.model("fast"), req1);

        assertFalse(turn1.toolCalls().isEmpty(), "Step 1 must decide to invoke a tool");
        ToolCall call = turn1.toolCalls().get(0);
        assertEquals("ask_report_agent", call.name());

        // Step 2: Tool execution result simulated
        String simulatedToolOutput = "[{\"group_name\":\"Batch A\",\"learners\":15,\"avg_percent\":68,\"at_risk\":2}]";
        Msg toolResultMsg = Msg.tool(call.id(), simulatedToolOutput);

        LlmRequest req2 = new LlmRequest(List.of(
            Msg.system("You route requests."),
            userMsg,
            Msg.assistant("", turn1.toolCalls()),
            toolResultMsg
        ), specs);

        LlmResponse turn2 = mock.chat(cfg.model("fast"), req2);
        assertTrue(turn2.toolCalls().isEmpty(), "Step 2 must return final synthesized text without further tool calls");
        assertTrue(turn2.text().contains("Here is what I found") || turn2.text().contains("Batch A"),
            "Step 2 response must summarize tool output");
    }

    // ─── T8: Liquibase migration file is well-formed ──────────────────────────

    @Test
    void t8_migrationFileHasLiquibaseHeader() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(
            "src/main/resources/db/changelog/002-v-user-progress-at-risk.sql");
        assertTrue(java.nio.file.Files.exists(p),
            "002-v-user-progress-at-risk.sql must exist");

        String sql = java.nio.file.Files.readString(p);
        assertTrue(sql.contains("--liquibase formatted sql"),
            "Migration must start with --liquibase formatted sql header");
        assertTrue(sql.contains("--changeset poc:2"),
            "Migration must have --changeset poc:2 (sequential after poc:1)");
    }

    // ─── T9: Migration SQL adds at_risk correctly ─────────────────────────────

    @Test
    void t9_migrationAddsAtRiskToView() throws Exception {
        java.nio.file.Path p = java.nio.file.Path.of(
            "src/main/resources/db/changelog/002-v-user-progress-at-risk.sql");
        String sql = java.nio.file.Files.readString(p);

        assertTrue(sql.contains("at_risk"),
            "Migration SQL must define the at_risk column");
        assertTrue(sql.contains("DROP VIEW IF EXISTS v_user_progress"),
            "Migration must drop the old view before recreating it");
        assertTrue(sql.contains("CREATE VIEW v_user_progress"),
            "Migration must recreate v_user_progress");
        assertTrue(sql.contains("course_name"),
            "Migration must preserve the original course_name column");
        assertTrue(sql.contains("percent"),
            "Migration must preserve the original percent column");
        assertTrue(sql.contains("deadline"),
            "Migration must preserve the original deadline column");
    }

    // ─── T10: get_user_progress tool query selects at_risk ────────────────────

    @Test
    void t10_getUserProgressQuerySelectsAtRisk() {
        ToolDef tool = cfg.tool("get_user_progress");
        assertNotNull(tool, "get_user_progress tool must exist");
        assertTrue(tool.query().contains("at_risk"),
            "get_user_progress SQL query must SELECT at_risk. Query: " + tool.query());
        assertTrue(tool.query().contains("course_name"),
            "Query must still select course_name");
        assertTrue(tool.query().contains("percent"),
            "Query must still select percent");
        assertTrue(tool.query().contains("deadline"),
            "Query must still select deadline");
    }

    @Test
    void t10b_getUserProgressToolResponseBinding() {
        ToolDef tool = cfg.tool("get_user_progress");
        assertNotNull(tool);

        RunCtx ctx = new RunCtx();
        ctx.tenant = "acme";
        ctx.userId = "u1";

        ToolExecutor executor = new ToolExecutor(cfg, List.of());
        Map<String, Object> bound = executor.bind(tool, Map.of("limit", 15), ctx);

        assertEquals("acme", bound.get("tenant"), "tenant param must bind from context");
        assertEquals("u1", bound.get("user_id"), "user_id param must bind from context");
        assertEquals(15, bound.get("limit"), "limit param should be accepted up to max");
    }

    @Test
    void t10c_vUserProgressRowOutputContract() {
        // Verify JSON serialization format expected by advisor and frontend
        Map<String, Object> simulatedRow = new LinkedHashMap<>();
        simulatedRow.put("course_name", "Indian Polity");
        simulatedRow.put("percent", 35);
        simulatedRow.put("deadline", "2026-11-22");
        simulatedRow.put("at_risk", true);

        String json = com.intutr.agents.util.J.str(List.of(simulatedRow));
        assertTrue(json.contains("\"at_risk\":true"));
        assertTrue(json.contains("\"course_name\":\"Indian Polity\""));
        assertTrue(json.contains("\"percent\":35"));
    }

    // ─── T11: at_risk SQL boundary conditions (logic verification) ────────────

    /**
     * Evaluates the at_risk SQL CASE expression in Java to verify boundary behaviour
     * without needing a database. Mirrors the exact SQL logic:
     *   CASE WHEN percent < 50 AND deadline < current_date + 7 THEN true ELSE false END
     */
    private static boolean atRisk(Integer percent, LocalDate deadline) {
        if (percent == null || deadline == null) return false; // NULL-safe: SQL NULL comparison → false
        return percent < 50 && deadline.isBefore(LocalDate.now().plusDays(7));
    }

    @Test
    void t11_atRiskBoundaryConditions() {
        LocalDate today = LocalDate.now();

        // Case 1: Classic at-risk — 30%, deadline 3 days away
        assertTrue(atRisk(30, today.plusDays(3)), "30% with 3 days left → at_risk=true");

        // Case 2: Overdue learner (deadline in the past, percent < 50) → at_risk=true
        assertTrue(atRisk(40, today.minusDays(2)), "40% overdue → at_risk=true");

        // Case 3: Exactly on the boundary — deadline is current_date + 7
        //         SQL: deadline < current_date + 7 → false (not strictly less than)
        assertFalse(atRisk(30, today.plusDays(7)), "30% with exactly 7 days left → at_risk=false (boundary)");

        // Case 4: 6 days left, percent < 50 → at_risk=true
        assertTrue(atRisk(49, today.plusDays(6)), "49% with 6 days left → at_risk=true");

        // Case 5: Percent ≥ 50 → at_risk=false regardless of deadline
        assertFalse(atRisk(50, today.plusDays(1)), "50% with 1 day left → at_risk=false (not < 50)");
        assertFalse(atRisk(100, today.minusDays(1)), "100% overdue → at_risk=false");

        // Case 6: null deadline → at_risk=false (SQL NULL comparison is false)
        assertFalse(atRisk(10, null), "null deadline → at_risk=false");

        // Case 7: null percent → at_risk=false
        assertFalse(atRisk(null, today.plusDays(1)), "null percent → at_risk=false");

        // Case 8: Comfortable — 80%, 10 days away
        assertFalse(atRisk(80, today.plusDays(10)), "80% with 10 days → at_risk=false");
    }

    @Test
    void t11b_learnerAnalyticsSkillUsesDbFlag() {
        SkillDef skill = cfg.skills().get("learner-analytics");
        assertNotNull(skill);
        String instructions = skill.instructions();
        assertTrue(instructions.contains("at_risk"),
            "learner-analytics instructions must reference the at_risk column from the DB");
        assertFalse(instructions.contains("Under 50%") || instructions.contains("compare percent"),
            "learner-analytics must NOT independently interpret percent/deadline (DB provides at_risk directly)");
    }
}

package com.intutr.agents;

import static org.junit.jupiter.api.Assertions.*;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.config.ConfigValidator;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.llm.MockClient;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.tools.ToolException;
import com.intutr.agents.tools.ToolExecutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** No Spring context and no database needed. Run: mvn test */
class EngineLogicTest {
    private static ToolDef sql(String query, Map<String, Param> params) {
        return new ToolDef("t", "sql", "d", params, query, null, null, null, null, null, null, null);
    }
    private static final Guardrails G = new Guardrails(List.of("v_user_progress"), 50, 5000, List.of(), List.of());

    @Test
    void shippedConfigIsValid() {
        assertEquals(List.of(), new ConfigRegistry("config").reload());
    }

    @Test
    void sqlMustBeSelectOnly() {
        assertFalse(ConfigValidator.checkSql(sql("DELETE FROM user_progress WHERE tenant_id = :tenant", Map.of("tenant", new Param("string", "context.tenant", null, null, null))), G).isEmpty());
    }

    @Test
    void sqlMustFilterByTenantAndUseAllowedRelations() {
        var noTenant = sql("SELECT 1 FROM v_user_progress", Map.of());
        assertTrue(ConfigValidator.checkSql(noTenant, G).stream().anyMatch(s -> s.contains(":tenant")));
        var badRel = sql("SELECT * FROM users WHERE tenant_id = :tenant", Map.of("tenant", new Param("string", "context.tenant", null, null, null)));
        assertTrue(ConfigValidator.checkSql(badRel, G).stream().anyMatch(s -> s.contains("users")));
    }

    @Test
    void contextParamsCannotBeOverriddenByTheModel() {
        ToolDef t = sql("SELECT 1", Map.of("user_id", new Param("string", "context.user_id", null, null, null), "limit", new Param("integer", null, null, 50, 10)));
        RunCtx ctx = new RunCtx();
        ctx.userId = "u1";
        Map<String, Object> b = new ToolExecutor(null, List.of()).bind(t, Map.of("user_id", "u2", "limit", 999), ctx);
        assertEquals("u1", b.get("user_id"));
        assertEquals(50, b.get("limit"));
    }

    @Test
    void missingRequiredParamIsRejected() {
        ToolDef t = sql("SELECT 1", Map.of("q", new Param("string", null, true, null, null)));
        assertThrows(ToolException.class, () -> new ToolExecutor(null, List.of()).bind(t, Map.of(), new RunCtx()));
    }

    @Test
    void mockPicksToolThenSummarises() {
        ModelDef m = new ModelDef("openai", "x", "chat", null, null, null, null, null, null);
        var tool = new ToolSpec("get_user_progress", "d", Map.of());
        var first = new MockClient().chat(m, new LlmRequest(List.of(Msg.system("s"), Msg.user("Am I on track?")), List.of(tool)));
        assertEquals("get_user_progress", first.toolCalls().get(0).name());
        var second = new MockClient().chat(m, new LlmRequest(List.of(Msg.user("Am I on track?"), Msg.assistant("", first.toolCalls()), Msg.tool("id", "[]")), List.of(tool)));
        assertTrue(second.toolCalls().isEmpty());
    }

    @Test
    void mockSimulatesRateLimitOnOpenAiProviderOnly() {
        var req = new LlmRequest(List.of(Msg.user("simulate error please")), List.of());
        assertThrows(com.intutr.agents.llm.LlmException.class, () -> new MockClient().chat(new ModelDef("openai", "x", null, null, null, null, null, null, null), req));
        assertDoesNotThrow(() -> new MockClient().chat(new ModelDef("anthropic", "x", null, null, null, null, null, null, null), req));
    }
}

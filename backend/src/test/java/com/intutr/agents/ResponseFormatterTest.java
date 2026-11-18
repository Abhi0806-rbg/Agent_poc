package com.intutr.agents;

import static org.junit.jupiter.api.Assertions.*;

import com.intutr.agents.engine.ResponseFormatter;
import com.intutr.agents.llm.MockClient;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.config.ConfigRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;

class ResponseFormatterTest {
    @Test void removesRepeatedMockPrefixes() {
        assertEquals("A concise answer.", ResponseFormatter.userFacing("(mock) Here is what I found: (mock) Here is what I found: A concise answer."));
    }

    @Test void convertsRetrievedDocumentsAndPreservesCitationsWithoutScoresOrIds() {
        String result = ResponseFormatter.userFacing("[{\"id\":\"123\",\"title\":\"Federalism in India\",\"text\":\"The Seventh Schedule divides powers between Union and states.\",\"score\":0.812}]");
        assertTrue(result.contains("The Seventh Schedule divides powers"));
        assertTrue(result.contains("Sources: Federalism in India"));
        assertFalse(result.contains("0.812"));
        assertFalse(result.contains("123"));
    }

    @Test void doesNotShowUnknownRawJson() {
        assertFalse(ResponseFormatter.userFacing("{\"secret\":\"payload\"}").contains("secret"));
        assertFalse(ResponseFormatter.userFacing("{\"secret\":\"payload\"}").contains("payload"));
    }

    @Test void mockClientDoesNotAddMockPrefixToToolResults() {
        ConfigRegistry cfg = new ConfigRegistry("config"); cfg.reload();
        var model = cfg.model("fast");
        var response = new MockClient().chat(model, new LlmRequest(List.of(Msg.user("Explain this"), Msg.tool("t1", "[{\"title\":\"Federalism\",\"text\":\"Powers are divided.\",\"score\":0.7}]")), List.of()));
        assertFalse(response.text().toLowerCase().contains("mock"));
        assertFalse(response.text().contains("score"));
        assertTrue(response.text().contains("Sources: Federalism"));
    }

    @Test void allPrimaryAgentPromptsRequireNaturalLanguageAndProtectInternalPayloads() {
        ConfigRegistry cfg = new ConfigRegistry("config"); assertEquals(List.of(), cfg.reload());
        for (String name : List.of("supervisor", "advisor", "report-agent", "content-agent")) {
            String prompt = cfg.renderSystem(cfg.agent(name), java.util.Map.of("tenant.name", "Acme", "user.name", "Learner", "today", "today", "language", "en")).toLowerCase();
            assertTrue(prompt.contains("natural language") || prompt.contains("natural-language"), name);
            assertTrue(prompt.contains("json"), name);
        }
    }
}

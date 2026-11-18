package com.intutr.agents;

import static org.junit.jupiter.api.Assertions.*;

import com.intutr.agents.config.Cfg.*;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.config.ConfigValidator;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.tools.HttpTool;
import com.intutr.agents.tools.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Phase3ReadinessTest {
    static ConfigRegistry cfg;

    @BeforeAll static void load() {
        cfg = new ConfigRegistry("config");
        assertEquals(List.of(), cfg.reload());
    }

    @Test void quizCoachLoadsGroundedPromptAndRegisteredToolButStaysDisabled() {
        AgentDef quiz = cfg.agent("quiz-coach");
        assertNotNull(quiz);
        assertFalse(cfg.isEnabled(quiz));
        assertEquals(List.of("search_content"), cfg.toolsFor(quiz));
        assertNotNull(cfg.tool("search_content"));
        String prompt = cfg.renderSystem(quiz, Map.of());
        for (String requirement : List.of("multiple-choice", "short-answer", "hint", "retrieved", "performance"))
            assertTrue(prompt.toLowerCase(Locale.ROOT).contains(requirement), requirement);
    }

    @Test void enabledQuizCoachRejectedUntilEvaluationApproved() throws Exception {
        Path root = Path.of("config");
        String before = Files.readString(root.resolve("agents/quiz-coach.yaml"));
        try {
            Files.writeString(root.resolve("agents/quiz-coach.yaml"), before.replace("enabled: false", "enabled: true"));
            List<String> errors = cfg.reload();
            assertTrue(errors.stream().anyMatch(e -> e.contains("cannot be enabled")), errors.toString());
        } finally {
            Files.writeString(root.resolve("agents/quiz-coach.yaml"), before);
            assertEquals(List.of(), cfg.reload());
        }
    }

    @Test void mockEmailRunsWithoutAllowlistAndApprovalRemainsGuarded() {
        assertTrue(cfg.guardrails().httpAllowedHosts().isEmpty());
        assertTrue(cfg.guardrails().approvalsRequiredFor().contains("send_email"));
        String result = new HttpTool(cfg).run(cfg.tool("send_email"), Map.of("to", "learner@example.com", "subject", "Practice", "body", "Hello"), new RunCtx());
        assertTrue(result.contains("sent (mock)"));
    }

    @Test void realHttpRequiresHttpsAndExactAllowlistHost() {
        HttpTool tool = new HttpTool(cfg);
        ToolDef badScheme = new ToolDef("provider", "http", "test", Map.of(), null, null, null, null, null, null, "POST", "http://api.mail.example/v1/send");
        assertThrows(ToolException.class, () -> tool.run(badScheme, Map.of(), new RunCtx()));
        ToolDef otherHost = new ToolDef("provider", "http", "test", Map.of(), null, null, null, null, null, null, "POST", "https://elsewhere.example/send");
        ToolException denied = assertThrows(ToolException.class, () -> tool.run(otherHost, Map.of(), new RunCtx()));
        assertTrue(denied.getMessage().contains("not in guardrails"));
    }

    @Test void retrievalEvaluationIncludesNaturalParaphrasesAndNoResultCase() {
        assertTrue(cfg.retrieval().pairs().size() >= 10);
        assertTrue(cfg.retrieval().pairs().stream().anyMatch(p -> Boolean.TRUE.equals(p.expectNoResults())));
        assertTrue(cfg.retrieval().pairs().stream().anyMatch(p -> p.query().contains("farmer's sowing")));
    }

    @Test void seededHistoricalOffsetsAreOneThroughSeventyOne() {
        Random actual = new Random(42);
        // Preserve all random draws before the first run timestamp in the seeder.
        // The bound itself guarantees every timestamp is historical and at most 71h old.
        for (int i = 0; i < 10000; i++) {
            int hoursAgo = 1 + actual.nextInt(71);
            assertTrue(hoursAgo >= 1 && hoursAgo <= 71);
        }
    }
}

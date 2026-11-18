package com.intutr.agents.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Shapes of the YAML config files. Keys are snake_case in YAML; unknown keys are rejected. */
public final class Cfg {
    private Cfg() {}
    public record Limits(Integer maxSteps, Integer maxTokens, Double maxCostUsd) {}
    public record Platform(Integer schemaVersion, String defaultModel, Limits limits, Integer toolResultMaxChars, Integer historyTurns, Integer maxAgentDepth) {}
    public record ModelDef(String provider, String name, String kind, Double temperature, Integer maxOutputTokens, String fallback,
                           Integer dimensions, Double inputCostPerMillion, Double outputCostPerMillion) {}
    public record ModelsFile(Map<String, ModelDef> models) {}
    public record Prompt(String name, Integer version, String system) {}
    public record Param(String type, String source, Boolean required, Integer max, @JsonProperty("default") Object defaultValue) {}
    public record ToolDef(String name, String type, String description, Map<String, Param> params, String query, String collection,
                          Integer topK, Double minScore, Boolean requiresApproval, String agent, String method, String url) {}
    public record SkillDef(String name, String description, String instructions, List<String> tools) {}
    public record AgentDef(String name, Boolean enabled, String description, String model, String prompt, List<String> skills,
                           List<String> tools, Limits limits, List<String> suggestions) {}
    public record Guardrails(List<String> sqlAllowedRelations, Integer sqlMaxRows, Integer sqlTimeoutMs, List<String> httpAllowedHosts,
                             List<String> approvalsRequiredFor) {}
    public record EvalCase(String id, String input, List<String> callsTools, List<String> notCallsTools, List<String> answerNotContains, Integer maxSteps) {}
    public record EvalSet(String agent, List<EvalCase> cases) {}
    public record RetrievalPair(String query, String expectedTitle, List<String> expectedTitles, Boolean expectNoResults) {}
    public record RetrievalEval(List<RetrievalPair> pairs) {}
}

package com.intutr.agents.config;

import com.intutr.agents.config.Cfg.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Startup validation. Any error stops the app (or rejects a reload), so bad config never reaches runtime. */
public final class ConfigValidator {
    public static final int EMBED_DIM = 384;
    private static final Set<String> TOOL_TYPES = Set.of("sql", "vector_search", "http", "agent");
    private static final Set<String> PROVIDERS = Set.of("openai", "anthropic", "mock");
    private static final Set<String> CONTEXT = Set.of("context.user_id", "context.tenant", "context.language");
    private static final Pattern REL = Pattern.compile("(?i)\\b(?:from|join)\\s+([a-z_][a-z0-9_.]*)");
    private static final Pattern PARAM = Pattern.compile("(?<!:):([a-zA-Z_][a-zA-Z0-9_]*)");
    private ConfigValidator() {}

    public static List<String> validate(ConfigRegistry.Snapshot s) {
        List<String> e = new ArrayList<>();
        if (s.platform() == null || s.platform().limits() == null || s.platform().limits().maxSteps() == null
                || s.platform().limits().maxTokens() == null || s.platform().limits().maxCostUsd() == null
                || s.platform().defaultModel() == null || s.platform().toolResultMaxChars() == null
                || s.platform().historyTurns() == null || s.platform().maxAgentDepth() == null) {
            e.add("platform.yaml: default_model, limits(max_steps,max_tokens,max_cost_usd), tool_result_max_chars, history_turns, max_agent_depth are required");
            return e;
        }
        if (s.guardrails() == null || s.guardrails().sqlAllowedRelations() == null) { e.add("guardrails.yaml: sql_allowed_relations is required"); return e; }
        if (!s.models().containsKey(s.platform().defaultModel())) e.add("platform.default_model '" + s.platform().defaultModel() + "' is not in models.yaml");
        s.models().forEach((k, m) -> {
            if (m.provider() == null || !PROVIDERS.contains(m.provider())) e.add("model " + k + ": provider must be one of " + PROVIDERS);
            if (m.name() == null) e.add("model " + k + ": name is required");
            if (m.fallback() != null && !s.models().containsKey(m.fallback())) e.add("model " + k + ": fallback '" + m.fallback() + "' not found");
        });
        ModelDef embed = s.models().get("embed");
        if (embed == null) e.add("models.yaml: alias 'embed' is required");
        else if (embed.dimensions() == null || embed.dimensions() != EMBED_DIM) e.add("model embed: dimensions must be " + EMBED_DIM + " (matches vector(384) column)");

        s.tools().forEach((k, t) -> {
            if (t.type() == null || !TOOL_TYPES.contains(t.type())) { e.add("tool " + k + ": type must be one of " + TOOL_TYPES); return; }
            if (t.params() != null) t.params().forEach((pn, p) -> {
                if (p.source() != null && !CONTEXT.contains(p.source())) e.add("tool " + k + ": param " + pn + " source '" + p.source() + "' must be one of " + CONTEXT);
            });
            switch (t.type()) {
                case "sql" -> e.addAll(checkSql(t, s.guardrails()));
                case "vector_search" -> { if (t.collection() == null) e.add("tool " + k + ": collection is required"); }
                case "http" -> { if (t.url() == null) e.add("tool " + k + ": url is required"); }
                case "agent" -> { if (t.agent() == null || !s.agents().containsKey(t.agent())) e.add("tool " + k + ": agent '" + t.agent() + "' not found"); }
                default -> { }
            }
        });
        s.skills().forEach((k, sk) -> { if (sk.tools() != null) sk.tools().forEach(t -> { if (!s.tools().containsKey(t)) e.add("skill " + k + ": tool '" + t + "' not found"); }); });
        s.agents().forEach((k, a) -> {
            if (a.model() != null && !s.models().containsKey(a.model())) e.add("agent " + k + ": model '" + a.model() + "' not found");
            if (a.prompt() == null || !s.prompts().containsKey(a.prompt())) e.add("agent " + k + ": prompt '" + a.prompt() + "' not found (use name or name@version)");
            if (a.tools() != null) a.tools().forEach(t -> { if (!s.tools().containsKey(t)) e.add("agent " + k + ": tool '" + t + "' not found"); });
            if (a.skills() != null) a.skills().forEach(t -> { if (!s.skills().containsKey(t)) e.add("agent " + k + ": skill '" + t + "' not found"); });
        });
        if (e.isEmpty()) e.addAll(cycles(s));
        return e;
    }

    /** SELECT only, allowlisted relations, tenant filter required, placeholders must be declared params. */
    public static List<String> checkSql(ToolDef t, Guardrails g) {
        List<String> e = new ArrayList<>();
        String q = t.query() == null ? "" : t.query().trim();
        String low = q.toLowerCase();
        if (!low.startsWith("select")) e.add("tool " + t.name() + ": sql must start with SELECT");
        if (q.contains(";")) e.add("tool " + t.name() + ": sql must not contain ';'");
        if (!low.contains(":tenant")) e.add("tool " + t.name() + ": sql must filter by :tenant (tenant isolation)");
        Matcher m = REL.matcher(q);
        while (m.find()) {
            if (!g.sqlAllowedRelations().contains(m.group(1).toLowerCase())) e.add("tool " + t.name() + ": relation '" + m.group(1) + "' is not in guardrails.sql_allowed_relations");
        }
        Matcher p = PARAM.matcher(q);
        while (p.find()) {
            if (t.params() == null || !t.params().containsKey(p.group(1))) e.add("tool " + t.name() + ": placeholder :" + p.group(1) + " has no matching param");
        }
        return e;
    }

    private static List<String> cycles(ConfigRegistry.Snapshot s) {
        List<String> e = new ArrayList<>();
        for (String a : s.agents().keySet()) if (dfs(s, a, new ArrayDeque<>())) e.add("agent cycle detected through '" + a + "' (agent-as-tool loop)");
        return e;
    }

    private static boolean dfs(ConfigRegistry.Snapshot s, String agent, Deque<String> path) {
        if (path.contains(agent)) return true;
        path.push(agent);
        AgentDef a = s.agents().get(agent);
        Set<String> toolNames = new LinkedHashSet<>();
        if (a.tools() != null) toolNames.addAll(a.tools());
        if (a.skills() != null) a.skills().forEach(sk -> { var def = s.skills().get(sk); if (def != null && def.tools() != null) toolNames.addAll(def.tools()); });
        for (String tn : toolNames) {
            ToolDef t = s.tools().get(tn);
            if (t != null && "agent".equals(t.type()) && dfs(s, t.agent(), path)) { path.pop(); return true; }
        }
        path.pop();
        return false;
    }
}

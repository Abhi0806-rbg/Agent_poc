package com.intutr.agents.llm;

import com.intutr.agents.config.Cfg.ModelDef;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.util.J;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Deterministic fake model used when no API key is configured. It picks a tool by keyword, then summarises the tool result.
 * Magic words: "simulate error" (rate-limit on the openai-provider model, so the fallback kicks in),
 * "simulate budget" (reports a huge token count so the budget guard trips).
 */
@Component
public class MockClient implements LlmClient {
    private static final String[][] ROUTES = {
        {"email|mail", "send_email"},
        {"progress|on track|behind|deadline|my courses", "get_user_progress", "ask_advisor"},
        {"report|batch|group|weekly|summary|summar", "get_group_summary", "ask_report_agent"},
    };
    private static final String[] DEFAULT_TOOLS = {"search_content", "ask_advisor"};

    @Override
    public LlmResponse chat(ModelDef m, LlmRequest r) {
        List<Msg> ms = r.messages();
        Msg last = ms.get(ms.size() - 1);
        String user = lastUser(ms);
        String low = user.toLowerCase();
        if (low.contains("simulate error") && "openai".equals(m.provider())) throw new LlmException("provider_rate_limit", "mock rate limit", true);
        int extra = low.contains("simulate budget") ? 900_000 : 0;
        int in = tokens(ms) + extra;

        if ("tool".equals(last.role())) {
            String text = summarizeToolResult(last.content());
            return new LlmResponse(text, List.of(), in, est(text), "stop", true);
        }
        ToolSpec pick = pickTool(low, r.tools());
        if (pick != null) {
            Map<String, Object> args = new LinkedHashMap<>();
            if (pick.params() != null) pick.params().forEach((k, p) -> args.put(k, argFor(k, p.type(), user)));
            ToolCall tc = new ToolCall("call_" + UUID.randomUUID().toString().substring(0, 8), pick.name(), args);
            return new LlmResponse("", List.of(tc), in, est(J.str(args)), "tool_calls", true);
        }
        String text = "(mock, no API keys) You asked: \"" + J.clip(user, 200) + "\". Add provider keys in .env for real answers.";
        return new LlmResponse(text, List.of(), in, est(text), "stop", true);
    }

    private static String summarizeToolResult(String raw) {
        try {
            var node = J.OM.readTree(raw);
            if (node.isArray() && !node.isEmpty()) {
                List<String> lines = new ArrayList<>();
                List<String> titles = new ArrayList<>();
                for (var doc : node) {
                    String title = doc.path("title").asText("");
                    String text = doc.path("text").asText("");
                    if (!text.isBlank()) lines.add(text);
                    if (!title.isBlank() && !titles.contains(title)) titles.add(title);
                }
                if (!lines.isEmpty()) return String.join(" ", lines) + (titles.isEmpty() ? "" : " Sources: " + String.join("; ", titles));
            }
            if (node.isObject()) {
                if (node.has("status") && node.has("to")) return "Email status: " + node.path("status").asText();
                if (node.has("total")) return "Found " + node.path("total").asText() + " matching records.";
                return "The tool returned a structured result. Summarize its useful facts in a concise natural-language answer without showing JSON.";
            }
        } catch (Exception ignored) { }
        return raw;
    }

    private static ToolSpec pickTool(String low, List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return null;
        Set<String> names = new HashSet<>();
        tools.forEach(t -> names.add(t.name()));
        for (String[] route : ROUTES) {
            if (!java.util.regex.Pattern.compile(route[0]).matcher(low).find()) continue;
            for (int i = 1; i < route.length; i++) if (names.contains(route[i])) return find(tools, route[i]);
        }
        for (String d : DEFAULT_TOOLS) if (names.contains(d)) return find(tools, d);
        return tools.get(0);
    }

    private static ToolSpec find(List<ToolSpec> tools, String n) { return tools.stream().filter(t -> t.name().equals(n)).findFirst().orElse(null); }

    private static Object argFor(String name, String type, String user) {
        return switch (name) {
            case "to" -> "manager@example.com";
            case "subject" -> "Learning update";
            case "limit" -> 5;
            default -> "integer".equals(type) ? (Object) 5 : user;
        };
    }

    private static String lastUser(List<Msg> ms) {
        for (int i = ms.size() - 1; i >= 0; i--) if ("user".equals(ms.get(i).role())) return ms.get(i).content();
        return "";
    }
    private static int tokens(List<Msg> ms) { int n = 0; for (Msg m : ms) n += est(m.content()); return n; }
    private static int est(String s) { return s == null ? 0 : s.length() / 4 + 1; }
}

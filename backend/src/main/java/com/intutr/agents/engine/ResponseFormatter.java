package com.intutr.agents.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intutr.agents.util.J;
import java.util.*;
import java.util.regex.Pattern;

/** Sanitizes model final text for chat while leaving tool messages and traces untouched. */
public final class ResponseFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MOCK_PREFIX = Pattern.compile("(?i)^\\s*(?:\\(mock(?:, no api keys)?\\)\\s*)?(?:here is what i found:\\s*)+");
    private ResponseFormatter() {}

    public static String userFacing(String raw) {
        if (raw == null || raw.isBlank()) return "I couldn't produce a response. Please try again.";
        String text = raw.trim();
        for (int i = 0; i < 3; i++) {
            String cleaned = MOCK_PREFIX.matcher(text).replaceFirst("").trim();
            if (cleaned.equals(text)) break;
            text = cleaned;
        }
        String docs = documents(text);
        if (docs != null) return docs;
        if (isJson(text)) return "I found relevant information, but couldn't turn it into a clear answer. Please try rephrasing your question.";
        return text;
    }

    private static boolean isJson(String text) {
        try { JSON.readTree(text); return text.startsWith("{") || text.startsWith("["); }
        catch (Exception ignored) { return false; }
    }

    private static String documents(String text) {
        try {
            JsonNode root = JSON.readTree(text);
            if (!root.isArray() || root.isEmpty()) return null;
            List<String> snippets = new ArrayList<>(), titles = new ArrayList<>();
            for (JsonNode d : root) {
                String content = first(d, "text", "content", "snippet");
                String title = first(d, "title", "source", "name");
                if (content != null && !content.isBlank()) snippets.add(content.trim());
                if (title != null && !title.isBlank() && !titles.contains(title.trim())) titles.add(title.trim());
            }
            if (snippets.isEmpty()) return null;
            String answer = String.join("\n\n", snippets);
            if (!titles.isEmpty()) answer += "\n\nSources: " + String.join("; ", titles);
            return answer;
        } catch (Exception ignored) { return null; }
    }

    private static String first(JsonNode n, String... keys) {
        for (String key : keys) if (n.hasNonNull(key) && n.get(key).isValueNode()) return n.get(key).asText();
        return null;
    }
}

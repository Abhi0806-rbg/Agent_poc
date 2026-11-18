package com.intutr.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intutr.agents.config.Cfg.ModelDef;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.util.J;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Anthropic Messages API with tool use. Only used when ANTHROPIC_API_KEY is set. */
@Component
public class AnthropicClient implements LlmClient {
    @Value("${agent.anthropic-api-key:}") private String key;
    public boolean enabled() { return key != null && !key.isBlank(); }

    @Override
    public LlmResponse chat(ModelDef m, LlmRequest r) {
        ObjectNode body = J.OM.createObjectNode();
        body.put("model", m.name());
        body.put("max_tokens", m.maxOutputTokens() != null ? m.maxOutputTokens() : 1024);
        if (m.temperature() != null) body.put("temperature", m.temperature());
        StringBuilder system = new StringBuilder();
        ArrayNode msgs = body.putArray("messages");
        ObjectNode pendingResults = null;
        for (Msg x : r.messages()) {
            if ("system".equals(x.role())) { system.append(x.content()).append("\n"); continue; }
            if ("tool".equals(x.role())) {
                if (pendingResults == null) { pendingResults = msgs.addObject(); pendingResults.put("role", "user"); pendingResults.putArray("content"); }
                ObjectNode tr = ((ArrayNode) pendingResults.get("content")).addObject();
                tr.put("type", "tool_result"); tr.put("tool_use_id", x.toolCallId()); tr.put("content", x.content());
                continue;
            }
            pendingResults = null;
            ObjectNode o = msgs.addObject();
            o.put("role", x.role());
            if ("assistant".equals(x.role()) && x.toolCalls() != null && !x.toolCalls().isEmpty()) {
                ArrayNode blocks = o.putArray("content");
                if (x.content() != null && !x.content().isBlank()) blocks.addObject().put("type", "text").put("text", x.content());
                for (ToolCall tc : x.toolCalls()) {
                    ObjectNode b = blocks.addObject();
                    b.put("type", "tool_use"); b.put("id", tc.id()); b.put("name", tc.name());
                    b.set("input", J.OM.valueToTree(tc.args()));
                }
            } else {
                o.put("content", x.content() == null || x.content().isBlank() ? "(empty)" : x.content());
            }
        }
        if (system.length() > 0) body.put("system", system.toString().trim());
        if (r.tools() != null && !r.tools().isEmpty()) {
            ArrayNode ts = body.putArray("tools");
            for (ToolSpec s : r.tools()) {
                ObjectNode t = ts.addObject();
                t.put("name", s.name()); t.put("description", s.description());
                t.set("input_schema", J.OM.valueToTree(Schemas.of(s)));
            }
        }
        JsonNode j = Http.post("https://api.anthropic.com/v1/messages",
            Map.of("x-api-key", key, "anthropic-version", "2023-06-01"), body, 60);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode b : j.path("content")) {
            String type = b.path("type").asText();
            if ("text".equals(type)) text.append(b.path("text").asText());
            else if ("tool_use".equals(type)) calls.add(new ToolCall(b.path("id").asText(), b.path("name").asText(), J.map(b.path("input"))));
        }
        JsonNode u = j.path("usage");
        return new LlmResponse(text.toString(), calls, u.path("input_tokens").asInt(0), u.path("output_tokens").asInt(0), j.path("stop_reason").asText(""), false);
    }
}

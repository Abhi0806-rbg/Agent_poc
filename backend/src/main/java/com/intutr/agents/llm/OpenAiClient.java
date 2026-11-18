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

/** OpenAI Chat Completions with function calling. Only used when OPENAI_API_KEY is set. */
@Component
public class OpenAiClient implements LlmClient {
    @Value("${agent.openai-api-key:}") private String key;
    public boolean enabled() { return key != null && !key.isBlank(); }

    @Override
    public LlmResponse chat(ModelDef m, LlmRequest r) {
        ObjectNode body = J.OM.createObjectNode();
        body.put("model", m.name());
        ArrayNode msgs = body.putArray("messages");
        for (Msg x : r.messages()) {
            ObjectNode o = msgs.addObject();
            o.put("role", x.role());
            if ("tool".equals(x.role())) { o.put("tool_call_id", x.toolCallId()); o.put("content", x.content()); continue; }
            o.put("content", x.content() == null ? "" : x.content());
            if (x.toolCalls() != null && !x.toolCalls().isEmpty()) {
                ArrayNode tcs = o.putArray("tool_calls");
                for (ToolCall tc : x.toolCalls()) {
                    ObjectNode t = tcs.addObject();
                    t.put("id", tc.id()); t.put("type", "function");
                    ObjectNode f = t.putObject("function");
                    f.put("name", tc.name()); f.put("arguments", J.str(tc.args()));
                }
            }
        }
        if (r.tools() != null && !r.tools().isEmpty()) {
            ArrayNode ts = body.putArray("tools");
            for (ToolSpec s : r.tools()) {
                ObjectNode t = ts.addObject();
                t.put("type", "function");
                ObjectNode f = t.putObject("function");
                f.put("name", s.name()); f.put("description", s.description());
                f.set("parameters", J.OM.valueToTree(Schemas.of(s)));
            }
        }
        if (m.maxOutputTokens() != null) body.put("max_completion_tokens", m.maxOutputTokens());
        if (m.temperature() != null) body.put("temperature", m.temperature()); // omit for models that only accept the default

        JsonNode j = Http.post("https://api.openai.com/v1/chat/completions", Map.of("Authorization", "Bearer " + key), body, 60);
        JsonNode ch = j.path("choices").path(0);
        JsonNode msg = ch.path("message");
        String text = msg.hasNonNull("content") ? msg.get("content").asText() : "";
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            String args = tc.path("function").path("arguments").asText("{}");
            calls.add(new ToolCall(tc.path("id").asText(), tc.path("function").path("name").asText(), J.map(J.tree(args.isBlank() ? "{}" : args))));
        }
        JsonNode u = j.path("usage");
        return new LlmResponse(text, calls, u.path("prompt_tokens").asInt(0), u.path("completion_tokens").asInt(0), ch.path("finish_reason").asText(""), false);
    }
}

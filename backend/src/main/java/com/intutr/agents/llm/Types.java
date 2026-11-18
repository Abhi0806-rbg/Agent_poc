package com.intutr.agents.llm;

import com.intutr.agents.config.Cfg.Param;
import java.util.List;
import java.util.Map;

public final class Types {
    private Types() {}
    public record ToolCall(String id, String name, Map<String, Object> args) {}
    public record Msg(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        public static Msg system(String c) { return new Msg("system", c, null, null); }
        public static Msg user(String c) { return new Msg("user", c, null, null); }
        public static Msg assistant(String c, List<ToolCall> tc) { return new Msg("assistant", c, null, tc); }
        public static Msg tool(String id, String c) { return new Msg("tool", c, id, null); }
    }
    /** What the model sees about a tool. Context-bound params are removed before this is built. */
    public record ToolSpec(String name, String description, Map<String, Param> params) {}
    public record LlmRequest(List<Msg> messages, List<ToolSpec> tools) {}
    public record LlmResponse(String text, List<ToolCall> toolCalls, int promptTokens, int completionTokens, String finishReason, boolean estimated) {}
}

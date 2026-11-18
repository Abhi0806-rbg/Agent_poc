package com.intutr.agents.llm;

public class LlmException extends RuntimeException {
    public final String type;
    public final boolean retryable;
    public LlmException(String type, String message, boolean retryable) { super(message); this.type = type; this.retryable = retryable; }
}

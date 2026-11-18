package com.intutr.agents.knowledge;

public interface Embedder {
    float[] embed(String text);
    String modelId();
}

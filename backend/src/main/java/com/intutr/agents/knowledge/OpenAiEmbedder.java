package com.intutr.agents.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intutr.agents.llm.Http;
import com.intutr.agents.util.J;
import java.util.Map;

public class OpenAiEmbedder implements Embedder {
    private final String key, model;
    private final int dims;
    public OpenAiEmbedder(String key, String model, int dims) { this.key = key; this.model = model; this.dims = dims; }
    @Override public String modelId() { return model; }

    @Override
    public float[] embed(String text) {
        ObjectNode body = J.OM.createObjectNode();
        body.put("model", model);
        body.put("input", text);
        body.put("dimensions", dims);
        JsonNode j = Http.post("https://api.openai.com/v1/embeddings", Map.of("Authorization", "Bearer " + key), body, 30);
        JsonNode arr = j.path("data").path(0).path("embedding");
        float[] v = new float[arr.size()];
        for (int i = 0; i < v.length; i++) v[i] = (float) arr.get(i).asDouble();
        return v;
    }
}

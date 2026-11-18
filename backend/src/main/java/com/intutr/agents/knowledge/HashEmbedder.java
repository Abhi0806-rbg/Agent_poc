package com.intutr.agents.knowledge;

import java.util.Set;

/** Offline embedder: hashed bag-of-words, L2-normalised. Good enough for keyword-ish retrieval without any API key. */
public class HashEmbedder implements Embedder {
    private static final Set<String> STOP = Set.of("the", "and", "for", "are", "was", "with", "that", "this", "from", "what", "which", "how", "why", "does", "into", "can", "its", "has", "have", "who", "not");
    private final int dims;
    public HashEmbedder(int dims) { this.dims = dims; }
    @Override public String modelId() { return "mock-hash-" + dims; }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dims];
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            String tok = raw.replaceAll("(ing|ed|es|s)$", "");
            if (tok.length() < 3 || STOP.contains(raw)) continue;
            int h = tok.hashCode();
            v[Math.floorMod(h, dims)] += ((h >>> 16) & 1) == 0 ? 1f : -1f;
        }
        double n = 0;
        for (float x : v) n += x * x;
        n = Math.sqrt(n);
        if (n > 0) for (int i = 0; i < dims; i++) v[i] /= (float) n;
        return v;
    }
}

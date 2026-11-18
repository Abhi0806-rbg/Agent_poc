package com.intutr.agents.knowledge;

import com.intutr.agents.config.Cfg.ModelDef;
import com.intutr.agents.config.ConfigRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real embeddings when OPENAI_API_KEY and MODEL_EMBED are set; otherwise the offline hash embedder. */
@Component
public class Embedders {
    private final ConfigRegistry cfg;
    @Value("${agent.openai-api-key:}") private String openaiKey;
    @Value("${agent.embed-dimensions:384}") private int dims;
    public Embedders(ConfigRegistry cfg) { this.cfg = cfg; }

    public Embedder current() {
        ModelDef m = cfg.model("embed");
        if (m != null && "openai".equals(m.provider()) && openaiKey != null && !openaiKey.isBlank()
                && m.name() != null && !m.name().startsWith("set-") && !"mock".equalsIgnoreCase(m.name()))
            return new OpenAiEmbedder(openaiKey, m.name(), dims);
        return new HashEmbedder(dims);
    }
    public static String toVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }
}

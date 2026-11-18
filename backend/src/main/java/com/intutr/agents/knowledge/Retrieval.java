package com.intutr.agents.knowledge;

import com.intutr.agents.util.J;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Cosine-similarity search over knowledge_chunk (pgvector). Always scoped to one tenant and one collection. */
@Component
public class Retrieval {
    private final JdbcTemplate jdbc;
    private final Embedders embedders;
    public Retrieval(JdbcTemplate jdbc, Embedders embedders) { this.jdbc = jdbc; this.embedders = embedders; }

    public List<Map<String, Object>> search(String tenant, String collection, String query, int topK, double minScore) {
        String vec = Embedders.toVector(embedders.current().embed(query));
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, title, text, 1 - (embedding <=> ?::vector) AS score FROM knowledge_chunk WHERE tenant_id = ? AND collection = ? ORDER BY embedding <=> ?::vector LIMIT ?",
            vec, tenant, collection, vec, topK);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            double s = ((Number) r.get("score")).doubleValue();
            if (s < minScore) continue;
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("id", r.get("id"));
            o.put("title", r.get("title"));
            o.put("text", J.clip(String.valueOf(r.get("text")), 300));
            o.put("score", Math.round(s * 1000) / 1000.0);
            out.add(o);
        }
        return out;
    }

    /** Re-embeds every chunk with the current embedder (use after adding a real API key). */
    public int reindex() {
        Embedder e = embedders.current();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, title, text FROM knowledge_chunk");
        for (Map<String, Object> r : rows) {
            String vec = Embedders.toVector(e.embed(r.get("title") + ". " + r.get("text")));
            jdbc.update("UPDATE knowledge_chunk SET embedding = ?::vector, embed_model = ?, embed_version = embed_version + 1 WHERE id = ?", vec, e.modelId(), r.get("id"));
        }
        return rows.size();
    }
}

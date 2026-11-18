package com.intutr.agents.tools;

import com.intutr.agents.config.Cfg.ToolDef;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.knowledge.Retrieval;
import com.intutr.agents.util.J;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class VectorSearchTool implements ToolHandler {
    private final Retrieval retrieval;
    public VectorSearchTool(Retrieval retrieval) { this.retrieval = retrieval; }
    @Override public String type() { return "vector_search"; }

    @Override
    public String run(ToolDef t, Map<String, Object> args, RunCtx ctx) {
        List<Map<String, Object>> hits = retrieval.search(ctx.tenant, t.collection(), String.valueOf(args.get("query")),
            t.topK() == null ? 4 : t.topK(), t.minScore() == null ? 0.0 : t.minScore());
        return hits.isEmpty() ? "No relevant content found." : J.str(hits);
    }
}

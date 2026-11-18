package com.intutr.agents.tools;

import com.intutr.agents.config.Cfg.ToolDef;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.AgentEngine;
import com.intutr.agents.engine.AgentEngine.RunResult;
import com.intutr.agents.engine.RunCtx;
import java.util.Map;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Lets one agent call another as a tool (supervisor pattern). Depth is capped by platform.max_agent_depth. */
@Component
public class AgentTool implements ToolHandler {
    private final AgentEngine engine;
    private final ConfigRegistry cfg;
    public AgentTool(@Lazy AgentEngine engine, ConfigRegistry cfg) { this.engine = engine; this.cfg = cfg; }
    @Override public String type() { return "agent"; }

    @Override
    public String run(ToolDef t, Map<String, Object> args, RunCtx ctx) {
        if (ctx.depth + 1 > cfg.platform().maxAgentDepth()) throw new ToolException("max agent depth reached");
        ctx.emit("type", "step", "kind", "handoff", "agent", ctx.agentName, "name", t.agent(), "summary", "delegating", "ms", 0);
        RunResult r = engine.run(t.agent(), String.valueOf(args.get("task")), null, ctx.child());
        if (!"COMPLETED".equals(r.status()))
            throw new ToolException("sub-agent " + t.agent() + " ended with " + r.status() + (r.errorType() != null ? " (" + r.errorType() + ")" : ""));
        return r.text();
    }
}

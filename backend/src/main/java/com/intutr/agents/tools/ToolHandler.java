package com.intutr.agents.tools;

import com.intutr.agents.config.Cfg.ToolDef;
import com.intutr.agents.engine.RunCtx;
import java.util.Map;

/** One implementation per tool TYPE. New tool of an existing type = a YAML file. New type = one class like this. */
public interface ToolHandler {
    String type();
    String run(ToolDef tool, Map<String, Object> args, RunCtx ctx);
}

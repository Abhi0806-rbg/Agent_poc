package com.intutr.agents.engine;

import com.intutr.agents.llm.Types.*;
import java.util.*;

/** Everything needed to pause a run (waiting for approval) and resume it later. Stored as JSON in agent_run.state. */
public class RunState {
    public List<Msg> messages = new ArrayList<>();
    public int step;
    public int tokens;
    public double cost;
    public long elapsed;
    public List<ToolCall> pending = new ArrayList<>();
    public Map<String, Integer> seen = new HashMap<>();
    public List<String> approved = new ArrayList<>();
    public String modelOverride;
}

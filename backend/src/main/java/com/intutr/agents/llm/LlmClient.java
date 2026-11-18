package com.intutr.agents.llm;

import com.intutr.agents.config.Cfg.ModelDef;
import com.intutr.agents.llm.Types.*;

public interface LlmClient {
    LlmResponse chat(ModelDef model, LlmRequest request);
}

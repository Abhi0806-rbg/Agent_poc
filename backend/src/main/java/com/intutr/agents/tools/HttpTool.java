package com.intutr.agents.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.intutr.agents.config.Cfg.ToolDef;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.llm.Http;
import com.intutr.agents.llm.LlmException;
import com.intutr.agents.util.J;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;

/** mock:// URLs are simulated (nothing leaves the machine). Real URLs must be on guardrails.http_allowed_hosts. */
@Component
public class HttpTool implements ToolHandler {
    private final ConfigRegistry cfg;
    public HttpTool(ConfigRegistry cfg) { this.cfg = cfg; }
    @Override public String type() { return "http"; }

    @Override
    public String run(ToolDef t, Map<String, Object> args, RunCtx ctx) {
        if (t.url().startsWith("mock://")) {
            return J.str(Map.of("status", "sent (mock)", "endpoint", t.url(), "to", String.valueOf(args.get("to")), "subject", String.valueOf(args.get("subject"))));
        }
        String host = URI.create(t.url()).getHost();
        var allowed = cfg.guardrails().httpAllowedHosts();
        if (allowed == null || !allowed.contains(host)) throw new ToolException("host '" + host + "' is not in guardrails.http_allowed_hosts");
        try {
            JsonNode r = Http.post(t.url(), Map.of(), J.OM.valueToTree(args), 10);
            return J.clip(r.toString(), 2000);
        } catch (LlmException e) {
            throw new ToolException("http failed: " + e.getMessage());
        }
    }
}

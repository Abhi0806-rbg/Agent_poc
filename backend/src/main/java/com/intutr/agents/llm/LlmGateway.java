package com.intutr.agents.llm;

import com.intutr.agents.config.Cfg.ModelDef;
import com.intutr.agents.config.ConfigRegistry;
import com.intutr.agents.engine.RunCtx;
import com.intutr.agents.llm.Types.*;
import com.intutr.agents.trace.TraceRepo;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Retry with backoff, circuit breaker, fallback model, token + cost logging. Falls back to the mock client when no key is set. */
@Component
public class LlmGateway {
    private static final long[] BACKOFF = {300, 900, 2000};
    private final ConfigRegistry cfg;
    private final MockClient mock;
    private final OpenAiClient openai;
    private final AnthropicClient anthropic;
    private final TraceRepo trace;
    private final Map<String, Integer> fails = new ConcurrentHashMap<>();
    private final Map<String, Long> openUntil = new ConcurrentHashMap<>();

    public record Result(LlmResponse resp, String model, double cost, long ms, boolean fallback) {}

    public LlmGateway(ConfigRegistry cfg, MockClient mock, OpenAiClient openai, AnthropicClient anthropic, TraceRepo trace) {
        this.cfg = cfg; this.mock = mock; this.openai = openai; this.anthropic = anthropic; this.trace = trace;
    }

    public boolean isLive(ModelDef m) {
        if (!isNamed(m)) return false;
        if ("openai".equals(m.provider())) return openai.enabled();
        if ("anthropic".equals(m.provider())) return anthropic.enabled();
        return false;
    }

    private boolean isNamed(ModelDef m) {
        return m != null && m.name() != null && !m.name().isBlank()
            && !m.name().startsWith("set-") && !"mock".equalsIgnoreCase(m.name());
    }

    LlmClient clientFor(ModelDef m) {
        if (!isNamed(m)) {
            return mock;
        }
        if ("openai".equals(m.provider())) {
            if (openai.enabled()) return openai;
            throw new LlmException("missing_api_key", "OPENAI_API_KEY is not configured for model '" + m.name() + "'", false);
        }
        if ("anthropic".equals(m.provider())) {
            if (anthropic.enabled()) return anthropic;
            throw new LlmException("missing_api_key", "ANTHROPIC_API_KEY is not configured for model '" + m.name() + "'", false);
        }
        throw new LlmException("unsupported_provider", "Unsupported provider '" + m.provider() + "' for model '" + m.name() + "'", false);
    }

    public Result call(String alias, LlmRequest req, RunCtx ctx, int stepNo) {
        String cur = alias;
        boolean fb = false;
        LlmException last = null;
        for (int round = 0; round < 2; round++) {
            ModelDef m = cfg.model(cur);
            if (m == null) throw new LlmException("config", "unknown model alias " + cur, false);
            for (int i = 0; i < BACKOFF.length; i++) {
                if (circuitOpen(m.provider())) { last = new LlmException("circuit_open", m.provider() + " circuit is open", false); break; }
                long t0 = System.currentTimeMillis();
                LlmClient c;
                try {
                    c = clientFor(m);
                } catch (LlmException e) {
                    last = e;
                    break;
                }
                String label = c instanceof MockClient ? m.provider() + "(mock)" : m.provider();
                try {
                    LlmResponse r = c.chat(m, req);
                    fails.put(m.provider(), 0);
                    long ms = System.currentTimeMillis() - t0;
                    double cost = cost(m, r);
                    trace.llmCall(ctx.runId, stepNo, label, m.name(), r.promptTokens(), r.completionTokens(), cost, ms, r.finishReason(), null, i, r.estimated());
                    return new Result(r, cur, cost, ms, fb);
                } catch (LlmException e) {
                    last = e;
                    onFail(m.provider());
                    trace.llmCall(ctx.runId, stepNo, label, m.name(), 0, 0, 0.0, System.currentTimeMillis() - t0, null, e.type, i, c instanceof MockClient);
                    if (!e.retryable) break;
                    sleep(c instanceof MockClient ? 100 : BACKOFF[i]);
                }
            }
            if (m.fallback() == null || round == 1) break;
            cur = m.fallback();
            fb = true;
        }
        throw last != null ? last : new LlmException("provider_unavailable", "no attempt made", false);
    }

    private static double cost(ModelDef m, LlmResponse r) {
        double in = m.inputCostPerMillion() == null ? 0 : m.inputCostPerMillion();
        double out = m.outputCostPerMillion() == null ? 0 : m.outputCostPerMillion();
        return (r.promptTokens() * in + r.completionTokens() * out) / 1_000_000.0;
    }
    private boolean circuitOpen(String p) { return openUntil.getOrDefault(p, 0L) > System.currentTimeMillis(); }
    private void onFail(String p) {
        int n = fails.merge(p, 1, Integer::sum);
        if (n >= 5) { openUntil.put(p, System.currentTimeMillis() + 30_000); fails.put(p, 0); }
    }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
}

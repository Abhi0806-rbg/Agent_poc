package com.intutr.agents.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.intutr.agents.util.J;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/** POST JSON and map HTTP failures to LlmException types the gateway understands. */
public final class Http {
    private static final HttpClient C = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private Http() {}

    public static JsonNode post(String url, Map<String, String> headers, JsonNode body, int timeoutSeconds) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json");
            headers.forEach(b::header);
            HttpResponse<String> r = C.send(b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
            int s = r.statusCode();
            if (s == 429) throw new LlmException("provider_rate_limit", J.clip(r.body(), 300), true);
            if (s >= 500) throw new LlmException("provider_5xx", "HTTP " + s + ": " + J.clip(r.body(), 300), true);
            if (s >= 400) throw new LlmException("provider_error", "HTTP " + s + ": " + J.clip(r.body(), 300), false);
            return J.tree(r.body());
        } catch (HttpTimeoutException e) {
            throw new LlmException("provider_timeout", String.valueOf(e.getMessage()), true);
        } catch (IOException e) {
            throw new LlmException("provider_error", e.toString(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("provider_error", "interrupted", false);
        }
    }
}

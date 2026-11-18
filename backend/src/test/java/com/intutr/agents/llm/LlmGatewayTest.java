package com.intutr.agents.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.intutr.agents.config.Cfg.ModelDef;
import com.intutr.agents.config.ConfigRegistry;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for LlmGateway provider selection, explicit mock mode, and missing API key enforcement.
 */
class LlmGatewayTest {

    private ConfigRegistry cfg;
    private MockClient mockClient;
    private OpenAiClient openAiClient;
    private AnthropicClient anthropicClient;
    private LlmGateway gateway;

    @BeforeEach
    void setup() {
        cfg = new ConfigRegistry("config");
        cfg.reload();
        mockClient = new MockClient();
        openAiClient = new OpenAiClient();
        anthropicClient = new AnthropicClient();
        gateway = new LlmGateway(cfg, mockClient, openAiClient, anthropicClient, null);
    }

    private void setKey(Object client, String fieldName, String value) throws Exception {
        Field f = client.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(client, value);
    }

    @Test
    void explicitMockModeReturnsMockClient() {
        ModelDef mockModel = new ModelDef("openai", "mock", "chat", null, 800, null, null, 0.15, 0.60);
        LlmClient client = gateway.clientFor(mockModel);
        assertSame(mockClient, client, "Explicit model name 'mock' must route to MockClient");
        assertFalse(gateway.isLive(mockModel), "Explicit mock model must report isLive = false");
    }

    @Test
    void missingOpenAiApiKeyThrowsWhenModelIsNamed() {
        // Model has a real name "gpt-4o-mini", but openAiClient has no key set
        ModelDef realModel = new ModelDef("openai", "gpt-4o-mini", "chat", null, 800, null, null, 0.15, 0.60);

        LlmException ex = assertThrows(LlmException.class, () -> gateway.clientFor(realModel),
            "Must throw LlmException when real OpenAI model is configured without API key");
        assertEquals("missing_api_key", ex.type);
        assertTrue(ex.getMessage().contains("OPENAI_API_KEY"),
            "Error message must specify missing OPENAI_API_KEY");
        assertFalse(ex.retryable, "Missing API key error must not be marked retryable");
        assertFalse(gateway.isLive(realModel), "Model without key must report isLive = false");
    }

    @Test
    void missingAnthropicApiKeyThrowsWhenModelIsNamed() {
        // Model has a real name "claude-3-5-haiku-20241022", but anthropicClient has no key set
        ModelDef realModel = new ModelDef("anthropic", "claude-3-5-haiku-20241022", "chat", null, 1500, null, null, 0.80, 4.00);

        LlmException ex = assertThrows(LlmException.class, () -> gateway.clientFor(realModel),
            "Must throw LlmException when real Anthropic model is configured without API key");
        assertEquals("missing_api_key", ex.type);
        assertTrue(ex.getMessage().contains("ANTHROPIC_API_KEY"),
            "Error message must specify missing ANTHROPIC_API_KEY");
        assertFalse(ex.retryable, "Missing API key error must not be marked retryable");
        assertFalse(gateway.isLive(realModel), "Model without key must report isLive = false");
    }

    @Test
    void realOpenAiProviderSelectedWhenKeyPresent() throws Exception {
        setKey(openAiClient, "key", "sk-test-mock-key");
        assertTrue(openAiClient.enabled());

        ModelDef realModel = new ModelDef("openai", "gpt-4o-mini", "chat", null, 800, null, null, 0.15, 0.60);
        LlmClient client = gateway.clientFor(realModel);

        assertSame(openAiClient, client, "Named model with active key must select OpenAiClient");
        assertTrue(gateway.isLive(realModel), "Named model with active key must report isLive = true");
    }

    @Test
    void realAnthropicProviderSelectedWhenKeyPresent() throws Exception {
        setKey(anthropicClient, "key", "sk-ant-test-mock-key");
        assertTrue(anthropicClient.enabled());

        ModelDef realModel = new ModelDef("anthropic", "claude-3-5-haiku-20241022", "chat", null, 1500, null, null, 0.80, 4.00);
        LlmClient client = gateway.clientFor(realModel);

        assertSame(anthropicClient, client, "Named model with active key must select AnthropicClient");
        assertTrue(gateway.isLive(realModel), "Named model with active key must report isLive = true");
    }

    @Test
    void unsupportedProviderThrowsException() {
        ModelDef badProvider = new ModelDef("unknown-provider", "some-model", "chat", null, 800, null, null, 0.0, 0.0);
        LlmException ex = assertThrows(LlmException.class, () -> gateway.clientFor(badProvider));
        assertEquals("unsupported_provider", ex.type);
    }
}

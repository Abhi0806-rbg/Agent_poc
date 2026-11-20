package com.intutr.agents.configuration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "PHASE1_DATABASE_URL", matches = ".+")
class ConfigPersistenceIntegrationTest {
    @Autowired ConfigService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tx;
    @Autowired TestRestTemplate http;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String tenant = "phase1-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach void clearTenantRows() { jdbc.update("DELETE FROM config_versions WHERE config_id IN (SELECT id FROM agent_config WHERE tenant_id=?)", tenant); jdbc.update("DELETE FROM agent_config WHERE tenant_id=?", tenant); }

    @Test void createReadUpdateAndVersionHistoryUsePostgresWithoutCreatingYaml() throws Exception {
        String id = "integration-agent";
        Path yaml = Path.of("config/agents/" + id + ".yaml");
        assertFalse(Files.exists(yaml));
        var first = request(id, "first");
        var created = service.create(tenant, "agent", first, "integration-test");
        assertEquals("DATABASE", created.source());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM agent_config WHERE tenant_id=? AND config_type='agent' AND config_identifier=?", Integer.class, tenant, id));
        assertEquals(created.id(), service.get(tenant, "agent", id).id());
        var updated = service.update(tenant, "agent", id, request(id, "second"), "integration-test");
        assertEquals(2, updated.version());
        assertEquals(2, service.versions(tenant, "agent", id).size());
        assertFalse(Files.exists(yaml));
    }

    @Test void duplicateIdentifierIsRejectedAndFailureRollsBackRecord() throws Exception {
        var req = request("duplicate-agent", "initial");
        service.create(tenant, "agent", req, "integration-test");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.create(tenant, "agent", req, "integration-test"));
        jdbc.execute("ALTER TABLE config_versions ADD CONSTRAINT phase1_rollback_test CHECK (changed_by <> 'rollback-trigger')");
        try {
            var failed = request("rollback-agent", "rollback");
            assertThrows(DataIntegrityViolationException.class, () -> new TransactionTemplate(tx).execute(s -> service.create(tenant, "agent", failed, "rollback-trigger")));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM agent_config WHERE tenant_id=? AND config_identifier='rollback-agent'", Integer.class, tenant));
        } finally { jdbc.execute("ALTER TABLE config_versions DROP CONSTRAINT phase1_rollback_test"); }
    }

    @Test void legacyAgentsEditorApiCreatesDatabaseRecordAndKeepsYamlDefaults() throws Exception {
        String id = "phase1-http-agent";
        Path createdYaml = Path.of("config/agents/" + id + ".yaml");
        assertFalse(Files.exists(createdYaml));
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.TEXT_PLAIN); headers.set("X-Tenant", tenant); headers.set("X-Role", "Developer"); headers.set("X-User", "integration-test");
        String yaml = "name: " + id + "\nenabled: true\ndescription: API agent\nmodel: fast\nprompt: advisor@1\ntools: [search_content]\n";
        String url = "http://localhost:" + port + "/api/agents/" + id + "/yaml";
        ResponseEntity<String> response = http.exchange(url, HttpMethod.PUT, new HttpEntity<>(yaml, headers), String.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(), response.toString());
        assertTrue(response.getBody().contains("DATABASE"), response.getBody());
        assertFalse(Files.exists(createdYaml));
        var listed = http.exchange("http://localhost:" + port + "/api/agents", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertTrue(listed.getBody().contains(id));
        assertTrue(cfgExists("advisor.yaml"));
    }

    @Test void configurationFamiliesShareJsonbPersistenceAndExposeDomainViews() throws Exception {
        for (String type : new String[]{"prompt", "tool", "skill", "model", "guardrail", "eval", "approval_rule", "model_pricing"}) {
            String id = "cfg-" + type;
            service.create(tenant, type, request(id, "integration " + type), "integration-test");
            String view = switch (type) {
                case "approval_rule" -> "approval_rule";
                case "model_pricing" -> "model_pricing";
                default -> type + "_config";
            };
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + view + " WHERE tenant_id=? AND config_identifier=?", Integer.class, tenant, id), type);
        }
        assertNotNull(jdbc.queryForObject("SELECT count(*) FROM token_usage", Long.class));
    }

    private ConfigRequest request(String id, String change) throws Exception {
        return new ConfigRequest(id, id, mapper.readTree("{\"enabled\":true,\"model\":\"fast\",\"tools\":[]}"), "ACTIVE", change);
    }
    private boolean cfgExists(String file) { return Files.exists(Path.of("config/agents/" + file)); }
}

package com.intutr.agents.configuration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

class ConfigServiceTest {
    private static final class FakeRepository extends ConfigRepository {
        ConfigRecord row; int creates, updates, deletes; boolean dependent, duplicate;
        FakeRepository() { super(new JdbcTemplate()); }
        @Override public ConfigRecord create(String tenant, String type, ConfigRequest req, String by) {
            creates++; if (duplicate) throw new DuplicateKeyException("duplicate");
            row = new ConfigRecord(UUID.randomUUID(), tenant, type, req.identifier(), req.name(), req.configuration(), "DATABASE", 1, "ACTIVE", by, null, null); return row;
        }
        @Override public ConfigRecord get(String tenant, String type, String id) { return row != null && row.identifier().equals(id) && row.type().equals(type) ? row : null; }
        @Override public ConfigRecord update(ConfigRecord current, ConfigRequest req, String by) {
            updates++; row = new ConfigRecord(current.id(), current.tenantId(), current.type(), current.identifier(), req.name(), req.configuration(), current.source(), current.version()+1, "ACTIVE", by, null, null); return row;
        }
        @Override public void delete(ConfigRecord record) { deletes++; }
        @Override public ConfigRecord setStatus(ConfigRecord record, String status, String changedBy) { row = new ConfigRecord(record.id(), record.tenantId(), record.type(), record.identifier(), record.name(), record.configuration(), record.source(), record.version()+1, status, record.createdBy(), null, null); return row; }
        @Override public boolean agentReferenced(String tenant, String id) { return dependent; }
        @Override public boolean referencedAsPrompt(String id) { return dependent; }
        @Override public boolean agentReferencesSkill(String tenant, String id) { return dependent; }
        @Override public boolean toolReferencesAgent(String tenant, String id) { return dependent; }
    }
    private final FakeRepository repo = new FakeRepository();
    private final ConfigService service = new ConfigService(repo);
    private final ObjectMapper mapper = new ObjectMapper();
    private ConfigRequest request(String id, String description) throws Exception {
        return new ConfigRequest(id, id + " name", mapper.readTree("{\"enabled\":true,\"tools\":[]}"), "ACTIVE", description);
    }

    @Test void createPersistsDatabaseRecordWithoutYamlWorkflow() throws Exception {
        var req = request("new-agent", "initial");
        assertEquals("DATABASE", service.create("acme", "agent", req, "dev").source());
        assertEquals(1, repo.creates);
    }
    @Test void duplicateIdentifiersReturnConflict() throws Exception {
        repo.duplicate = true;
        var error = assertThrows(ResponseStatusException.class, () -> service.create("acme", "tool", request("duplicate", null), "dev"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }
    @Test void updateCreatesNextVersion() throws Exception {
        var req = request("agent-a", "modify");
        repo.row = new ConfigRecord(UUID.randomUUID(), "acme", "agent", "agent-a", "old", mapper.readTree("{\"enabled\":false}"), "DATABASE", 1, "ACTIVE", "dev", null, null);
        assertEquals(2, service.update("acme", "agent", "agent-a", req, "dev").version());
        assertEquals(1, repo.updates);
    }
    @Test void yamlDefaultsCannotBeDeletedAndDependenciesBlockDelete() throws Exception {
        repo.row = new ConfigRecord(UUID.randomUUID(), "acme", "tool", "search_content", "Search", mapper.readTree("{}"), "YAML", 1, "ACTIVE", "system", null, null);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class, () -> service.delete("acme", "tool", "search_content", "dev")).getStatusCode());
        repo.row = new ConfigRecord(UUID.randomUUID(), "acme", "tool", "custom", "Custom", mapper.readTree("{}"), "DATABASE", 1, "ACTIVE", "dev", null, null); repo.dependent = true;
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class, () -> service.delete("acme", "tool", "custom", "dev")).getStatusCode());
        assertEquals(0, repo.deletes);
    }
    @Test void mutationsAreTransactional() throws Exception {
        assertNotNull(ConfigService.class.getMethod("create", String.class, String.class, ConfigRequest.class, String.class).getAnnotation(Transactional.class));
        assertNotNull(ConfigService.class.getMethod("update", String.class, String.class, String.class, ConfigRequest.class, String.class).getAnnotation(Transactional.class));
        assertNotNull(ConfigService.class.getMethod("delete", String.class, String.class, String.class, String.class).getAnnotation(Transactional.class));
    }
    @Test void unsupportedOperationalTypesAreNotConfigurationRecords() {
        var error = assertThrows(ResponseStatusException.class, () -> service.list("acme", "token_usage"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}

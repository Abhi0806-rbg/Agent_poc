package com.intutr.agents.configuration;

import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConfigService {
    private static final List<String> TYPES = List.of("agent", "prompt", "tool", "skill", "model", "guardrail", "eval", "approval_rule", "model_pricing");
    private final ConfigRepository repo;
    public ConfigService(ConfigRepository repo) { this.repo = repo; }

    public List<ConfigRecord> list(String tenant, String type) { type(type); return repo.list(tenant, type); }
    public ConfigRecord get(String tenant, String type, String id) { type(type); ConfigRecord r = repo.get(tenant, type, id); if (r == null) throw missing(); return r; }
    public List<ConfigVersion> versions(String tenant, String type, String id) { get(tenant, type, id); return repo.versions(tenant, type, id); }
    @Transactional
    public ConfigRecord setStatus(String tenant, String type, String id, String status, String changedBy) {
        type(type);
        if (status == null || !List.of("ACTIVE", "DISABLED", "DRAFT", "ARCHIVED").contains(status.toUpperCase(java.util.Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be ACTIVE, DISABLED, DRAFT, or ARCHIVED");
        return repo.setStatus(get(tenant, type, id), status.toUpperCase(java.util.Locale.ROOT), changedBy);
    }

    @Transactional
    public ConfigRecord create(String tenant, String type, ConfigRequest req, String by) {
        type(type); validate(req);
        try { return repo.create(tenant, type, req, by); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Configuration identifier already exists", e); }
    }

    @Transactional
    public ConfigRecord update(String tenant, String type, String id, ConfigRequest req, String by) {
        type(type); validate(req);
        if (!id.equals(req.identifier())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "identifier cannot be changed");
        ConfigRecord current = get(tenant, type, id);
        return repo.update(current, req, by);
    }

    @Transactional
    public void delete(String tenant, String type, String id, String changedBy) {
        type(type); ConfigRecord row = get(tenant, type, id);
        if (!"DATABASE".equals(row.source())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "YAML defaults cannot be deleted through database CRUD");
        if ("tool".equals(type) && repo.agentReferenced(tenant, id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Tool is referenced by a database-managed agent");
        if ("prompt".equals(type) && repo.referencedAsPrompt(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Prompt is referenced by a database-managed agent");
        if ("skill".equals(type) && repo.agentReferencesSkill(tenant, id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill is referenced by a database-managed agent");
        if ("agent".equals(type) && repo.toolReferencesAgent(tenant, id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent is referenced by a database-managed agent tool");
        repo.setStatus(row, "ARCHIVED", changedBy);
    }

    private static void validate(ConfigRequest req) {
        if (req == null || req.identifier() == null || !req.identifier().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
            || req.name() == null || req.name().isBlank() || req.name().length() > 200 || req.configuration() == null || !req.configuration().isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "identifier, name, and object configuration are required");
        if (req.status() != null && !List.of("ACTIVE", "DISABLED", "DRAFT", "ARCHIVED").contains(req.status().toUpperCase(java.util.Locale.ROOT)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be ACTIVE, DISABLED, DRAFT, or ARCHIVED");
    }
    private static void type(String type) { if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported configuration type"); }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuration not found"); }
}

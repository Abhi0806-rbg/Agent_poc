package com.intutr.agents.configuration;

import com.intutr.agents.api.Roles;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** CRUD for user-managed configurations. YAML defaults remain served by the existing config endpoints. */
@RestController
@RequestMapping("/api/configs")
public class ConfigCrudController {
    private final ConfigService configs;
    public ConfigCrudController(ConfigService configs) { this.configs = configs; }

    @GetMapping("/{type}")
    public List<ConfigRecord> list(@PathVariable String type, @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                                   @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); return configs.list(tenant, type);
    }
    @GetMapping("/{type}/{identifier}")
    public ConfigRecord get(@PathVariable String type, @PathVariable String identifier, @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                            @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); return configs.get(tenant, type, identifier);
    }
    @GetMapping("/{type}/{identifier}/versions")
    public List<ConfigVersion> versions(@PathVariable String type, @PathVariable String identifier, @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                                        @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); return configs.versions(tenant, type, identifier);
    }
    @PostMapping("/{type}")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfigRecord create(@PathVariable String type, @RequestBody ConfigRequest body,
                               @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                               @RequestHeader(value="X-User", defaultValue="developer") String user,
                               @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); return configs.create(tenant, type, body, user);
    }
    @PutMapping("/{type}/{identifier}")
    public ConfigRecord update(@PathVariable String type, @PathVariable String identifier, @RequestBody ConfigRequest body,
                               @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                               @RequestHeader(value="X-User", defaultValue="developer") String user,
                               @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); return configs.update(tenant, type, identifier, body, user);
    }
    @DeleteMapping("/{type}/{identifier}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String type, @PathVariable String identifier, @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                       @RequestHeader(value="X-User", defaultValue="developer") String user,
                       @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); configs.delete(tenant, type, identifier, user);
    }

    @PatchMapping("/{type}/{identifier}/status")
    public ConfigRecord status(@PathVariable String type, @PathVariable String identifier, @RequestBody Map<String, String> body,
                               @RequestHeader(value="X-Tenant", defaultValue="acme") String tenant,
                               @RequestHeader(value="X-User", defaultValue="developer") String user,
                               @RequestHeader(value="X-Role", defaultValue="Developer") String role) {
        Roles.require(role, "Developer", "Admin"); return configs.setStatus(tenant, type, identifier, body.get("status"), user);
    }
}

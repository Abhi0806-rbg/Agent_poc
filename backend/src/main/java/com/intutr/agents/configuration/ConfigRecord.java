package com.intutr.agents.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ConfigRecord(UUID id, String tenantId, String type, String identifier, String name, JsonNode configuration,
                           String source, int version, String status, String createdBy, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

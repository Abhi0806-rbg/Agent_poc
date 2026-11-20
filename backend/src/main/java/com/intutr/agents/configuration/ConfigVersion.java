package com.intutr.agents.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ConfigVersion(UUID id, UUID configId, int version, JsonNode configuration, String changedBy,
                            String changeDescription, OffsetDateTime createdAt) {}

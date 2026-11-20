package com.intutr.agents.configuration;

import com.fasterxml.jackson.databind.JsonNode;

public record ConfigRequest(String identifier, String name, JsonNode configuration,
                            String status, String changeDescription) {}

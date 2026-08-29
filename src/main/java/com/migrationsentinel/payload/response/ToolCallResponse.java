package com.migrationsentinel.payload.response;

import com.migrationsentinel.model.enums.AgentRole;

import java.util.UUID;

public record ToolCallResponse(
        UUID id,
        AgentRole agentRole,
        int stepNo,
        String toolName,
        String argumentsJson,
        String resultJson,
        long durationMs,
        boolean ok
) {
}

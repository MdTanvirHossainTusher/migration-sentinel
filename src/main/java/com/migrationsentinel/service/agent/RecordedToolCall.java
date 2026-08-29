package com.migrationsentinel.service.agent;

import com.migrationsentinel.model.enums.AgentRole;

public record RecordedToolCall(
        AgentRole agentRole,
        int stepNo,
        String toolName,
        String argumentsJson,
        String resultJson,
        long durationMs,
        boolean ok
) {
}

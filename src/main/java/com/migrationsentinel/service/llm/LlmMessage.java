package com.migrationsentinel.service.llm;

import java.util.List;

public record LlmMessage(
        Role role,
        String content,
        List<LlmToolCall> toolCalls,
        String toolCallId,
        String toolName
) {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content, List.of(), null, null);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content, List.of(), null, null);
    }

    public static LlmMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, toolCalls == null ? List.of() : toolCalls, null, null);
    }

    public static LlmMessage tool(String toolCallId, String toolName, String content) {
        return new LlmMessage(Role.TOOL, content, List.of(), toolCallId, toolName);
    }
}

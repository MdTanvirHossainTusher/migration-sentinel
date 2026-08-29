package com.migrationsentinel.service.agent;

import com.migrationsentinel.model.enums.AgentRole;

import java.util.ArrayList;
import java.util.List;

/** Collects every tool call an agent makes so the review's trajectory can be persisted verbatim. */
public class TrajectoryRecorder {

    private final List<RecordedToolCall> calls = new ArrayList<>();
    private int step = 0;

    public void record(AgentRole role, String toolName, String argumentsJson, String resultJson,
                       long durationMs, boolean ok) {
        calls.add(new RecordedToolCall(role, ++step, toolName, argumentsJson, resultJson, durationMs, ok));
    }

    public List<RecordedToolCall> calls() {
        return List.copyOf(calls);
    }

    public int count() {
        return calls.size();
    }
}

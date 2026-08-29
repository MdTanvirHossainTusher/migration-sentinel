package com.migrationsentinel.service.agent;

import com.migrationsentinel.config.properties.LlmProperties;
import com.migrationsentinel.model.enums.AgentRole;
import com.migrationsentinel.service.llm.LlmClient;
import com.migrationsentinel.service.llm.LlmMessage;
import com.migrationsentinel.service.llm.LlmResponse;
import com.migrationsentinel.service.llm.LlmToolCall;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The explicit agent loop shared by every provider: call the model, run any tools it
 * asks for, feed the results back, repeat until it returns a final answer or the
 * iteration budget runs out. Every tool call is timed and handed to the recorder, so
 * the trajectory is a faithful transcript regardless of which LLM (or the heuristic
 * client) produced it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final LlmProperties llmProperties;
    private final AgentJsonMapper mapper;

    public String run(AgentRole role, LlmClient client, String systemPrompt, String userPrompt,
                      List<ToolSpec> tools, TrajectoryRecorder recorder) {

        Map<String, ToolSpec> toolIndex = new java.util.HashMap<>();
        tools.forEach(t -> toolIndex.put(t.name(), t));

        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt));
        messages.add(LlmMessage.user(userPrompt));

        int maxIterations = Math.max(1, llmProperties.getMaxToolIterations());
        for (int iteration = 0; iteration < maxIterations; iteration++) {
            LlmResponse response = client.chat(messages, tools);
            if (!response.wantsTools()) {
                return response.content() == null ? "" : response.content();
            }
            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            for (LlmToolCall call : response.toolCalls()) {
                ToolSpec spec = toolIndex.get(call.name());
                long start = System.nanoTime();
                String result;
                boolean ok = true;
                if (spec == null) {
                    ok = false;
                    result = "{\"error\":\"unknown tool: " + call.name() + "\"}";
                } else {
                    try {
                        result = spec.handler().execute(parseArgs(call.argumentsJson()));
                    } catch (Exception ex) {
                        ok = false;
                        result = "{\"error\":" + mapper.valueToTree(String.valueOf(ex.getMessage())) + "}";
                        log.warn("tool {} failed: {}", call.name(), ex.getMessage());
                    }
                }
                long ms = (System.nanoTime() - start) / 1_000_000;
                recorder.record(role, call.name(), call.argumentsJson(), result, ms, ok);
                messages.add(LlmMessage.tool(call.id(), call.name(), result));
            }
        }
        log.warn("agent loop for {} hit the {}-iteration budget", role, maxIterations);
        // One last call with no tools to force a final answer.
        LlmResponse forced = client.chat(withNoToolReminder(messages), List.of());
        return forced.content() == null ? "" : forced.content();
    }

    private com.fasterxml.jackson.databind.JsonNode parseArgs(String json) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private List<LlmMessage> withNoToolReminder(List<LlmMessage> messages) {
        List<LlmMessage> copy = new ArrayList<>(messages);
        copy.add(LlmMessage.user("Stop calling tools now. Return your final JSON answer only."));
        return copy;
    }
}

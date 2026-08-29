package com.migrationsentinel.service.support;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * A camelCase Jackson mapper for everything internal to the agent: tool arguments and
 * results, the analyzer/verifier JSON contracts, the evaluation case files. Kept separate
 * from the primary (snake_case) mapper the REST API uses, so the wire format and the
 * agent's own JSON never fight over a naming strategy.
 */
@Component
public class AgentJsonMapper extends ObjectMapper {

    public AgentJsonMapper() {
        registerModule(new JavaTimeModule());
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}

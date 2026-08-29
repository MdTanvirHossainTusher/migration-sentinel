package com.migrationsentinel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The primary Jackson mapper for the REST wire format (snake_case, per {@code spring.jackson.*}).
 *
 * <p>Without this bean Spring MVC would fall back to {@code AgentJsonMapper} — which extends
 * {@code ObjectMapper}, so it satisfies {@code @ConditionalOnMissingBean(ObjectMapper.class)} and
 * suppresses Boot's auto-configured mapper. That would serialize every response in camelCase and
 * silently ignore {@code spring.jackson.property-naming-strategy}. Marking this one {@code @Primary}
 * keeps {@code AgentJsonMapper} available for injection by name while MVC uses this one.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }
}

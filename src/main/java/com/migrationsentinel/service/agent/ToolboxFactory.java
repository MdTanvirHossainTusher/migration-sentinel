package com.migrationsentinel.service.agent;

import com.migrationsentinel.config.properties.SentinelProperties;
import com.migrationsentinel.payload.dto.MigrationInput;
import com.migrationsentinel.service.rules.StaticRuleScanner;
import com.migrationsentinel.service.sandbox.JpaMappingValidator;
import com.migrationsentinel.service.sandbox.MigrationReplayer;
import com.migrationsentinel.service.sandbox.SandboxSession;
import com.migrationsentinel.service.sandbox.SchemaIntrospector;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ToolboxFactory {

    private final AgentJsonMapper mapper;
    private final SentinelProperties properties;
    private final SchemaIntrospector introspector;
    private final MigrationReplayer replayer;
    private final JpaMappingValidator mappingValidator;
    private final StaticRuleScanner staticScanner;

    public Toolbox create(MigrationInput input, SandboxSession sandbox, boolean applyCandidate) {
        return new Toolbox(input, sandbox, properties.getLargeTableRowThreshold(), applyCandidate, mapper,
                introspector, replayer, mappingValidator, staticScanner);
    }
}

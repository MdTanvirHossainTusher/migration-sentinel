package com.migrationsentinel.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.migrationsentinel.exception.AppException;
import com.migrationsentinel.model.enums.RuleCode;
import com.migrationsentinel.model.enums.Severity;
import com.migrationsentinel.service.support.AgentJsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.migrationsentinel.constant.code.ErrorCodes.EVAL_CORPUS_ERROR;

/**
 * Loads the migration-safety evaluation corpus from {@code classpath:eval/cases/<id>/}.
 * Each case directory holds: {@code case.json}, {@code migration.sql}, and optionally
 * {@code baseline.sql}, {@code seed.sql}, {@code entity.java}/{@code entity.json}, plus
 * {@code labels.json} with the expected findings.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationCorpus {

    private final AgentJsonMapper objectMapper;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private volatile Map<String, EvaluationCase> cache;

    public List<EvaluationCase> all() {
        return new ArrayList<>(load().values());
    }

    public List<EvaluationCase> subset(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return all();
        }
        Map<String, EvaluationCase> loaded = load();
        List<EvaluationCase> out = new ArrayList<>();
        for (String id : ids) {
            EvaluationCase c = loaded.get(id);
            if (c == null) {
                throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, EVAL_CORPUS_ERROR,
                        "Unknown evaluation case id: " + id);
            }
            out.add(c);
        }
        return out;
    }

    public Optional<EvaluationCase> byId(String id) {
        return Optional.ofNullable(load().get(id));
    }

    public int size() {
        return load().size();
    }

    private Map<String, EvaluationCase> load() {
        Map<String, EvaluationCase> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            Map<String, EvaluationCase> loaded = new LinkedHashMap<>();
            try {
                Resource[] labels = resolver.getResources("classpath*:eval/cases/*/labels.json");
                List<Resource> ordered = new ArrayList<>(List.of(labels));
                ordered.sort(Comparator.comparing(r -> caseIdOf(r)));
                for (Resource labelResource : ordered) {
                    EvaluationCase c = parseCase(labelResource);
                    loaded.put(c.id(), c);
                }
            } catch (IOException ex) {
                throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, EVAL_CORPUS_ERROR,
                        "Could not load the evaluation corpus: " + ex.getMessage());
            }
            if (loaded.isEmpty()) {
                log.warn("No evaluation cases found on the classpath under eval/cases/*");
            }
            cache = loaded;
            return loaded;
        }
    }

    private String caseIdOf(Resource labelResource) {
        try {
            String url = labelResource.getURL().toString();
            String path = url.replace("/labels.json", "");
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (IOException e) {
            return labelResource.getFilename();
        }
    }

    private EvaluationCase parseCase(Resource labelResource) throws IOException {
        String id = caseIdOf(labelResource);
        JsonNode labels = objectMapper.readTree(read(labelResource));

        String migration = readSibling(labelResource, "migration.sql")
                .orElseThrow(() -> new AppException(HttpStatus.INTERNAL_SERVER_ERROR, EVAL_CORPUS_ERROR,
                        "case " + id + " has no migration.sql"));
        String baseline = readSibling(labelResource, "baseline.sql").orElse(null);
        String seed = readSibling(labelResource, "seed.sql").orElse(null);
        String entity = readSibling(labelResource, "entity.java")
                .or(() -> readSibling(labelResource, "entity.json")).orElse(null);

        JsonNode caseMeta = readSibling(labelResource, "case.json")
                .map(this::readTreeQuiet).orElse(objectMapper.createObjectNode());

        List<EvaluationCase.ExpectedFinding> expected = new ArrayList<>();
        for (JsonNode e : labels.path("expected")) {
            expected.add(new EvaluationCase.ExpectedFinding(
                    RuleCode.valueOf(e.path("ruleCode").asText()),
                    e.path("targetObject").asText(null),
                    e.hasNonNull("severity") ? Severity.valueOf(e.path("severity").asText()) : null,
                    e.path("note").asText(null)));
        }
        boolean mustBeClean = labels.path("mustBeClean").asBoolean(expected.isEmpty());

        return new EvaluationCase(
                id,
                caseMeta.path("title").asText(id),
                caseMeta.path("description").asText(""),
                caseMeta.path("hard").asBoolean(false),
                migration, baseline, seed, entity,
                expected, mustBeClean);
    }

    private Optional<String> readSibling(Resource labelResource, String name) {
        try {
            Resource sibling = labelResource.createRelative(name);
            if (sibling.exists()) {
                return Optional.of(read(sibling));
            }
        } catch (IOException ignored) {
            // absent
        }
        return Optional.empty();
    }

    private String read(Resource resource) throws IOException {
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JsonNode readTreeQuiet(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}

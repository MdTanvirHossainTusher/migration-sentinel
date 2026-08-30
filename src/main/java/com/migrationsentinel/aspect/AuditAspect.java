package com.migrationsentinel.aspect;

import com.migrationsentinel.service.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.time.temporal.Temporal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns {@link Audited} into an {@code audit_event}. Runs at {@code @Order(100)} — inside
 * the {@code @Transactional} advice ({@link com.migrationsentinel.config.AuditConfig}) — so
 * {@code AuditService.record} (propagation REQUIRED) joins the business transaction and the
 * two commit atomically. Mirrors the identity service's {@code AuditAspect}, trimmed to this
 * domain (no auth, no entity snapshots).
 */
@Slf4j
@Aspect
@Component
@Order(100)
@RequiredArgsConstructor
public class AuditAspect {

    private static final int MAX_STRING = 512;
    private static final Set<String> SKIP_FIELDS = Set.of(
            "reportMarkdown", "report_markdown", "migrationSql", "migration_sql", "baselineSql",
            "seedSql", "entitySource", "downloadUrl", "download_url", "uploadUrl", "upload_url",
            "note", "evidence", "suggestedRewrite", "llmApiKey", "llm_api_key");

    private final AuditService auditService;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        Object result = joinPoint.proceed();

        try {
            String aggregateId = resolveId(audited.id(), paramNames, args, result);
            auditService.record(
                    audited.action(),
                    audited.aggregateType(),
                    aggregateId,
                    resolveActor(paramNames, args),
                    audited.action() + " " + (aggregateId == null ? "" : aggregateId),
                    payload(result));
        } catch (Exception ex) {
            // In-transaction failure to record means the business change should not commit
            // silently without a trail — let it propagate.
            throw ex;
        }
        return result;
    }

    private String resolveId(String spec, String[] paramNames, Object[] args, Object result) {
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (spec.equals(paramNames[i]) && args[i] != null) {
                    return String.valueOf(args[i]);
                }
            }
        }
        String accessor = "result".equals(spec) ? "id" : spec;
        Object value = invokeAccessor(result, accessor);
        return value == null ? null : String.valueOf(value);
    }

    private String resolveActor(String[] paramNames, Object[] args) {
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (("actor".equals(paramNames[i]) || "approvedBy".equals(paramNames[i]))
                        && args[i] instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        for (Object arg : args) {
            Object approvedBy = invokeAccessor(arg, "approvedBy");
            if (approvedBy instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        HttpServletRequest request = requestProvider.getIfAvailable();
        if (request != null) {
            String header = request.getHeader("X-Actor");
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        return "api";
    }

    /** Small scalar fields off the return value; big text, URLs and secrets are skipped. */
    private Map<String, Object> payload(Object result) {
        Map<String, Object> out = new HashMap<>();
        if (result == null) {
            return out;
        }
        if (result.getClass().isRecord()) {
            for (RecordComponent component : result.getClass().getRecordComponents()) {
                addScalar(out, component.getName(), invokeAccessor(result, component.getName()));
            }
        } else {
            for (var method : result.getClass().getMethods()) {
                if (method.getParameterCount() != 0 || method.getName().equals("getClass")) {
                    continue;
                }
                String name = method.getName();
                if (name.startsWith("get") && name.length() > 3) {
                    name = Character.toLowerCase(name.charAt(3)) + name.substring(4);
                } else if (!name.startsWith("is")) {
                    continue;
                }
                try {
                    addScalar(out, name, method.invoke(result));
                } catch (Exception ignored) {
                    // skip
                }
            }
        }
        return out;
    }

    private void addScalar(Map<String, Object> out, String name, Object value) {
        if (value == null || SKIP_FIELDS.contains(name)) {
            return;
        }
        if (value instanceof String s) {
            if (s.length() <= MAX_STRING && !name.toLowerCase().contains("url")) {
                out.put(name, s);
            }
        } else if (value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof UUID || value instanceof Temporal) {
            out.put(name, value instanceof Temporal || value instanceof UUID || value instanceof Enum<?>
                    ? value.toString() : value);
        }
    }

    private Object invokeAccessor(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (String candidate : new String[]{name, "get" + capitalize(name)}) {
            try {
                return target.getClass().getMethod(candidate).invoke(target);
            } catch (Exception ignored) {
                // try the next form
            }
        }
        return null;
    }

    private String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

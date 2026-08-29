package com.migrationsentinel.support;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that needs a real Docker daemon (Testcontainers). Excluded from the fast
 * {@code test} task; run with {@code ./gradlew sandboxTest}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Tag("sandbox")
public @interface SandboxTest {
}

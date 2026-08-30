package com.migrationsentinel.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose successful completion is an auditable action.
 * {@link AuditAspect} records one {@code audit_event} — in the same transaction as the
 * method when it is {@code @Transactional} — with a payload built from the return value.
 * Nothing is recorded if the method throws.
 *
 * <p>Terminal states of async work (a review completing or failing on a worker thread) are
 * <em>not</em> annotated — those are recorded explicitly by the runners, because they are
 * domain state transitions rather than "an API call succeeded".
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** e.g. {@code "review.submitted"}, {@code "rewrite.applied"}, {@code "artifact.confirmed"}. */
    String action();

    /** The aggregate the event is about: {@code "review"}, {@code "evaluation"}, {@code "artifact"}. */
    String aggregateType();

    /**
     * How to find the aggregate id. Either the name of a method parameter, or an accessor on
     * the return value: {@code "result"} → {@code result.id()}; {@code "reviewJobId"} →
     * {@code result.reviewJobId()}.
     */
    String id() default "result";
}

package com.migrationsentinel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the {@code @Transactional} advisor to the highest precedence (order 0) so it is the
 * outermost advice. {@code AuditAspect} ({@code @Order(100)}) then runs <em>inside</em> the
 * transaction, which is what lets {@code AuditService.record} (propagation REQUIRED) join
 * the business transaction — the change and its audit event commit or roll back together.
 *
 * <p>Without this, Spring's auto-configured advisor sits at {@code LOWEST_PRECEDENCE} and
 * the aspect would open its own transaction for the audit write. Mirrors the identity
 * service's {@code OutboxConfig}.
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class AuditConfig {
}

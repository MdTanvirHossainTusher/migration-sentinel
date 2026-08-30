package com.migrationsentinel.util;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;

/**
 * A console appender that runs every formatted message through {@link SecretMasker} before
 * it is written. Configured in {@code logback-spring.xml}. Mirrors the masking-appender
 * approach used by the identity service.
 */
public class MaskingConsoleAppender extends ConsoleAppender<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        super.append(new MaskingLoggingEventWrapper(event));
    }
}

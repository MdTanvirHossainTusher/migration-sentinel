package com.migrationsentinel.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.LoggerContextVO;
import org.slf4j.Marker;
import org.slf4j.event.KeyValuePair;

import java.util.List;
import java.util.Map;

/** Delegates everything to the real event except the formatted message, which is masked. */
public class MaskingLoggingEventWrapper implements ILoggingEvent {

    private final ILoggingEvent delegate;
    private final String maskedMessage;

    public MaskingLoggingEventWrapper(ILoggingEvent delegate) {
        this.delegate = delegate;
        this.maskedMessage = SecretMasker.mask(delegate.getFormattedMessage());
    }

    @Override public String getFormattedMessage() { return maskedMessage; }
    @Override public String getMessage() { return delegate.getMessage(); }
    @Override public Object[] getArgumentArray() { return delegate.getArgumentArray(); }
    @Override public String getThreadName() { return delegate.getThreadName(); }
    @Override public Level getLevel() { return delegate.getLevel(); }
    @Override public String getLoggerName() { return delegate.getLoggerName(); }
    @Override public LoggerContextVO getLoggerContextVO() { return delegate.getLoggerContextVO(); }
    @Override public IThrowableProxy getThrowableProxy() { return delegate.getThrowableProxy(); }
    @Override public StackTraceElement[] getCallerData() { return delegate.getCallerData(); }
    @Override public boolean hasCallerData() { return delegate.hasCallerData(); }
    @Override public List<Marker> getMarkerList() { return delegate.getMarkerList(); }
    @Override public List<KeyValuePair> getKeyValuePairs() { return delegate.getKeyValuePairs(); }
    @Override public Map<String, String> getMDCPropertyMap() { return delegate.getMDCPropertyMap(); }
    @Override public Map<String, String> getMdc() { return delegate.getMdc(); }
    @Override public long getTimeStamp() { return delegate.getTimeStamp(); }
    @Override public int getNanoseconds() { return delegate.getNanoseconds(); }
    @Override public long getSequenceNumber() { return delegate.getSequenceNumber(); }
    @Override public void prepareForDeferredProcessing() { delegate.prepareForDeferredProcessing(); }
}

package io.quarkus.events.runtime;

import jakarta.enterprise.invoke.Invoker;

import io.quarkus.runtime.RuntimeValue;

/**
 * Runtime data for a single event consumer registration.
 * Produced at build time, consumed by the recorder at runtime.
 */
public class EventConsumerInfo {

    private final String metadataClassName;
    private final RuntimeValue<Invoker<Object, Object>> invoker;
    private final boolean blocking;
    private final boolean ordered;

    public EventConsumerInfo(String metadataClassName,
            RuntimeValue<Invoker<Object, Object>> invoker, boolean blocking, boolean ordered) {
        this.metadataClassName = metadataClassName;
        this.invoker = invoker;
        this.blocking = blocking;
        this.ordered = ordered;
    }

    public String getMetadataClassName() {
        return metadataClassName;
    }

    public RuntimeValue<Invoker<Object, Object>> getInvoker() {
        return invoker;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public boolean isOrdered() {
        return ordered;
    }
}

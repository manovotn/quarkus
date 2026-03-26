package io.quarkus.events.runtime;

import java.util.List;

import jakarta.enterprise.invoke.Invoker;

import io.quarkus.runtime.RuntimeValue;

/**
 * Runtime data for a single event consumer registration.
 * Produced at build time, consumed by the recorder at runtime.
 */
public class EventConsumerInfo {

    private final String observedType;
    private final List<String> qualifierNames;
    private final RuntimeValue<Invoker<Object, Object>> invoker;
    private final boolean blocking;
    private final boolean ordered;

    public EventConsumerInfo(String observedType, List<String> qualifierNames,
            RuntimeValue<Invoker<Object, Object>> invoker, boolean blocking, boolean ordered) {
        this.observedType = observedType;
        this.qualifierNames = qualifierNames;
        this.invoker = invoker;
        this.blocking = blocking;
        this.ordered = ordered;
    }

    public String getObservedType() {
        return observedType;
    }

    public List<String> getQualifierNames() {
        return qualifierNames;
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

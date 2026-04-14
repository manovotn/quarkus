package io.quarkus.events.runtime;

import jakarta.enterprise.invoke.Invoker;

import io.quarkus.runtime.RuntimeValue;

/**
 * Runtime data for a single event consumer registration.
 * Produced at build time, consumed by the recorder at runtime.
 */
public class EventConsumerInfo {

    private final RuntimeValue<Invoker<Object, Object>> invoker;
    private final boolean blocking;
    private final int parameterCount;
    private final int eventInfoPosition;

    public EventConsumerInfo(RuntimeValue<Invoker<Object, Object>> invoker, boolean blocking,
            int parameterCount, int eventInfoPosition) {
        this.invoker = invoker;
        this.blocking = blocking;
        this.parameterCount = parameterCount;
        this.eventInfoPosition = eventInfoPosition;
    }

    public RuntimeValue<Invoker<Object, Object>> getInvoker() {
        return invoker;
    }

    public boolean isBlocking() {
        return blocking;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    public int getEventInfoPosition() {
        return eventInfoPosition;
    }
}

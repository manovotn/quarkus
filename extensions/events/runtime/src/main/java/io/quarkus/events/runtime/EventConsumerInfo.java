package io.quarkus.events.runtime;

import java.util.List;

import jakarta.enterprise.invoke.Invoker;

import io.quarkus.runtime.RuntimeValue;

/**
 * Runtime data for a single event consumer registration.
 * Produced at build time, consumed by the recorder at runtime.
 */
public class EventConsumerInfo {

    private final List<String> addresses;
    private final RuntimeValue<Invoker<Object, Object>> invoker;
    private final boolean blocking;
    private final boolean ordered;

    public EventConsumerInfo(List<String> addresses, RuntimeValue<Invoker<Object, Object>> invoker,
            boolean blocking, boolean ordered) {
        this.addresses = addresses;
        this.invoker = invoker;
        this.blocking = blocking;
        this.ordered = ordered;
    }

    public List<String> getAddresses() {
        return addresses;
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

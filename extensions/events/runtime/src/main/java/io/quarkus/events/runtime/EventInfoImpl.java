package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

import io.quarkus.events.EventInfo;

/**
 * Implementation of {@link EventInfo} backed by an {@link EventEnvelope}.
 */
public class EventInfoImpl implements EventInfo {

    private final EventEnvelope envelope;

    public EventInfoImpl(EventEnvelope envelope) {
        this.envelope = envelope;
    }

    @Override
    public Object getMetadata(String key) {
        return envelope.metadata() != null ? envelope.metadata().get(key) : null;
    }

    @Override
    public Type getEventType() {
        return envelope.eventType();
    }

    @Override
    public Set<Annotation> getQualifiers() {
        return envelope.qualifiers();
    }
}

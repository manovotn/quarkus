package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

/**
 * Internal transport wrapper that carries the event payload along with metadata,
 * event type, and qualifiers through the Vert.x event bus.
 */
public record EventEnvelope(Object event, Map<String, Object> metadata, Type eventType, Set<Annotation> qualifiers) {
}

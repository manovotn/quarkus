package io.quarkus.events;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * Provides contextual information about an event delivery.
 * <p>
 * Can be used as an optional parameter in {@code @OnEvent} methods to access
 * metadata attached by the emitter, as well as the event's type and qualifiers.
 *
 * <pre>
 * &#64;OnEvent
 * void handle(Order order, EventInfo info) {
 *     String traceId = (String) info.getMetadata("traceId");
 *     Type eventType = info.getEventType();
 * }
 * </pre>
 *
 * @see QuarkusEvent#withMetadata(String, Object)
 */
public interface EventInfo {

    /**
     * Returns the metadata value for the given key, or {@code null} if not present.
     *
     * @param key the metadata key
     * @return the metadata value, or {@code null}
     */
    Object getMetadata(String key);

    /**
     * @return the event type as specified at the emission point (may be parameterized)
     */
    Type getEventType();

    /**
     * @return the qualifiers specified at the emission point
     */
    Set<Annotation> getQualifiers();
}

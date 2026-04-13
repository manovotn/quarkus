package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;

/**
 * Provides runtime type and qualifier metadata for a consumer.
 * Implementations are generated at build time via Gizmo2.
 */
public interface ConsumerMetadata {

    /**
     * @return the observed event type, preserving parameterized type info
     */
    Type observedType();

    /**
     * @return the qualifier annotations declared on the event parameter
     */
    Set<Annotation> qualifiers();
}

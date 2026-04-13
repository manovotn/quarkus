package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

/**
 * Provides runtime type and qualifier metadata for all declarative consumers.
 * A single implementation is generated at build time via Gizmo2 containing
 * metadata entries for every {@code @OnEvent} consumer method.
 */
public interface ConsumerMetadata {

    /**
     * @return metadata entries for all consumers, in declaration order
     */
    List<Entry> entries();

    /**
     * Metadata for a single consumer.
     */
    record Entry(Type observedType, Set<Annotation> qualifiers) {
    }
}

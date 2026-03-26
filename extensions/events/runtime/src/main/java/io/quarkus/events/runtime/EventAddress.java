package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * Utility for computing deterministic Vert.x event bus addresses from event types and qualifiers.
 * <p>
 * Address format: {@code __qx_event__/<canonical-type-name>} or
 * {@code __qx_event__/<canonical-type-name>#<qualifier1>#<qualifier2>} when qualifiers are present.
 * <p>
 * Qualifiers are sorted lexicographically by their annotation type name to ensure
 * deterministic address generation regardless of declaration order.
 */
public final class EventAddress {

    public static final String PREFIX = "__qx_event__/";

    private EventAddress() {
    }

    /**
     * Compute the address for an event's runtime type (no qualifiers).
     */
    public static String of(Class<?> eventType) {
        return PREFIX + eventType.getName();
    }

    /**
     * Compute the address for an event type name (no qualifiers).
     * Used at build time when we have the class name but not the Class object.
     */
    public static String of(String eventTypeName) {
        return PREFIX + eventTypeName;
    }

    /**
     * Compute the address for an event's runtime type with qualifiers.
     */
    public static String of(Class<?> eventType, Collection<Annotation> qualifiers) {
        if (qualifiers == null || qualifiers.isEmpty()) {
            return of(eventType);
        }
        return PREFIX + eventType.getName() + qualifierSuffix(qualifiers);
    }

    /**
     * Compute the address for an event type name with qualifier names.
     * Used at build time.
     */
    public static String of(String eventTypeName, Collection<String> qualifierNames) {
        if (qualifierNames == null || qualifierNames.isEmpty()) {
            return of(eventTypeName);
        }
        Set<String> sorted = new TreeSet<>(qualifierNames);
        StringBuilder sb = new StringBuilder(PREFIX).append(eventTypeName);
        for (String q : sorted) {
            sb.append('#').append(q);
        }
        return sb.toString();
    }

    private static String qualifierSuffix(Collection<Annotation> qualifiers) {
        Set<String> sorted = new TreeSet<>();
        for (Annotation a : qualifiers) {
            sorted.add(a.annotationType().getName());
        }
        StringBuilder sb = new StringBuilder();
        for (String name : sorted) {
            sb.append('#').append(name);
        }
        return sb.toString();
    }
}

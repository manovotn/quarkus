package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Qualifier;

import io.quarkus.events.QuarkusEvent;

/**
 * CDI producer for {@link QuarkusEvent} injection points.
 * Produces a {@code QuarkusEvent<T>} for any injection point type,
 * extracting qualifiers and the full parameterized event type from the injection point.
 * <p>
 * For {@code @Inject QuarkusEvent<Envelope<Order>>}, the event type is
 * {@code Envelope<Order>} (not just {@code Envelope}), enabling parameterized type matching.
 */
@ApplicationScoped
public class QuarkusEventProducer {

    @Produces
    @SuppressWarnings({ "unchecked", "rawtypes" })
    <T> QuarkusEvent<T> produceQuarkusEvent(InjectionPoint injectionPoint) {
        Set<Annotation> qualifiers = extractQualifiers(injectionPoint);
        Type eventType = extractEventType(injectionPoint);
        return new QuarkusEventImpl(eventType, qualifiers);
    }

    /**
     * Extract the event type from the injection point's type parameter.
     * For {@code QuarkusEvent<Envelope<Order>>}, returns {@code Envelope<Order>}.
     * For {@code QuarkusEvent<String>}, returns {@code String.class}.
     */
    private Type extractEventType(InjectionPoint injectionPoint) {
        Type type = injectionPoint.getType();
        if (type instanceof ParameterizedType pt && pt.getRawType().equals(QuarkusEvent.class)) {
            return pt.getActualTypeArguments()[0];
        }
        // Fallback: raw type (shouldn't happen with proper injection)
        return Object.class;
    }

    private Set<Annotation> extractQualifiers(InjectionPoint injectionPoint) {
        Set<Annotation> qualifiers = new HashSet<>();
        for (Annotation annotation : injectionPoint.getQualifiers()) {
            if (isEventQualifier(annotation)) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers;
    }

    private boolean isEventQualifier(Annotation annotation) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        // Skip CDI built-in qualifiers
        if (annotationType.getName().equals("jakarta.enterprise.inject.Default")
                || annotationType.getName().equals("jakarta.enterprise.inject.Any")) {
            return false;
        }
        return annotationType.isAnnotationPresent(Qualifier.class);
    }
}

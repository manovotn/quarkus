package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
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
 * extracting qualifiers from the injection point annotations.
 * <p>
 * The EventBus and EventDispatcher are resolved lazily from {@link EventsRecorder}
 * to avoid accessing runtime-init beans during static init.
 */
@ApplicationScoped
public class QuarkusEventProducer {

    @Produces
    @SuppressWarnings({ "unchecked", "rawtypes" })
    <T> QuarkusEvent<T> produceQuarkusEvent(InjectionPoint injectionPoint) {
        Set<Annotation> qualifiers = extractQualifiers(injectionPoint);
        // EventBus and dispatcher are resolved lazily inside QuarkusEventImpl
        // to avoid accessing runtime-init synthetic beans during static init
        return new QuarkusEventImpl(qualifiers);
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

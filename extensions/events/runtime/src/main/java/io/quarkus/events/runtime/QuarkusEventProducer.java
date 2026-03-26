package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;

import io.quarkus.events.QuarkusEvent;
import io.vertx.mutiny.core.eventbus.EventBus;

/**
 * CDI producer for {@link QuarkusEvent} injection points.
 * Produces a {@code QuarkusEvent<T>} for any injection point type,
 * extracting qualifiers from the injection point annotations.
 */
@ApplicationScoped
public class QuarkusEventProducer {

    @Inject
    EventBus eventBus;

    @Produces
    @SuppressWarnings({ "unchecked", "rawtypes" })
    <T> QuarkusEvent<T> produceQuarkusEvent(InjectionPoint injectionPoint) {
        Set<Annotation> qualifiers = extractQualifiers(injectionPoint);
        return new QuarkusEventImpl(eventBus, qualifiers);
    }

    private Set<Annotation> extractQualifiers(InjectionPoint injectionPoint) {
        Set<Annotation> qualifiers = new HashSet<>();
        for (Annotation annotation : injectionPoint.getQualifiers()) {
            // Skip built-in CDI qualifiers (@Default, @Any) — they're not event qualifiers
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
        // Must be annotated with @Qualifier
        return annotationType.isAnnotationPresent(Qualifier.class);
    }
}

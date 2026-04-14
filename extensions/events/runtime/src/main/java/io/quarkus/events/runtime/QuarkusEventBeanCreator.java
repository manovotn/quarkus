package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Qualifier;

import io.quarkus.arc.BeanCreator;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.events.QuarkusEvent;

/**
 * Bean creator for the synthetic {@link QuarkusEvent} bean.
 * Extracts the event type and qualifiers from the injection point.
 */
public class QuarkusEventBeanCreator implements BeanCreator<Object> {

    @Override
    public Object create(SyntheticCreationalContext<Object> context) {
        InjectionPoint ip = context.getInjectedReference(InjectionPoint.class);
        Type eventType = extractEventType(ip);
        Set<Annotation> qualifiers = extractQualifiers(ip);
        return new QuarkusEventImpl<>(eventType, qualifiers);
    }

    private Type extractEventType(InjectionPoint ip) {
        Type type = ip.getType();
        if (type instanceof ParameterizedType pt && pt.getRawType().equals(QuarkusEvent.class)) {
            return pt.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private Set<Annotation> extractQualifiers(InjectionPoint ip) {
        Set<Annotation> qualifiers = new HashSet<>();
        for (Annotation annotation : ip.getQualifiers()) {
            if (isEventQualifier(annotation)) {
                qualifiers.add(annotation);
            }
        }
        return qualifiers;
    }

    private boolean isEventQualifier(Annotation annotation) {
        Class<? extends Annotation> annotationType = annotation.annotationType();
        if (annotationType.getName().equals("jakarta.enterprise.inject.Default")
                || annotationType.getName().equals("jakarta.enterprise.inject.Any")) {
            return false;
        }
        return annotationType.isAnnotationPresent(Qualifier.class);
    }
}

package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import io.quarkus.events.EventConsumerRegistration;
import io.quarkus.events.QuarkusEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;

/**
 * Runtime implementation of {@link QuarkusEvent}.
 * Delegates to the Vert.x event bus using type-safe address mapping.
 */
public class QuarkusEventImpl<T> implements QuarkusEvent<T> {

    private final EventBus eventBus;
    private final Set<Annotation> qualifiers;

    public QuarkusEventImpl(EventBus eventBus, Set<Annotation> qualifiers) {
        this.eventBus = eventBus;
        this.qualifiers = qualifiers != null ? Set.copyOf(qualifiers) : Set.of();
    }

    @Override
    public void publish(T event) {
        String address = resolveAddress(event);
        eventBus.publish(address, event);
    }

    @Override
    public void send(T event) {
        String address = resolveAddress(event);
        eventBus.send(address, event);
    }

    @Override
    public <R> Uni<R> request(T event, Class<R> replyType) {
        String address = resolveAddress(event);
        return eventBus.<R> request(address, event)
                .onItem().transform(Message::body);
    }

    @Override
    public <U extends T> QuarkusEvent<U> select(Class<U> subtype, Annotation... qualifiers) {
        Set<Annotation> merged = mergeQualifiers(qualifiers);
        return new QuarkusEventImpl<>(eventBus, merged);
    }

    @Override
    public QuarkusEvent<T> select(Annotation... qualifiers) {
        Set<Annotation> merged = mergeQualifiers(qualifiers);
        return new QuarkusEventImpl<>(eventBus, merged);
    }

    @Override
    public EventConsumerRegistration consumer(Class<T> eventType, Consumer<T> handler) {
        // Programmatic consumers register on the exact type address only — no subtype fan-out.
        //
        // Subtype fan-out (registering on all subtype addresses) is not supported here because:
        // 1. Discovering subtypes at runtime requires classpath scanning, which is not available
        //    in native mode.
        // 2. A build-time precomputed subtype registry could help, but it would only contain
        //    types that were known at build time. If the registry was populated only for types
        //    with @OnEvent consumers, the behavior would be non-deterministic — users couldn't
        //    predict whether fan-out would work depending on what other consumers/extensions exist.
        // 3. Populating the registry for ALL types in the index is too expensive for large apps.
        // 4. An explicit opt-in mechanism (e.g., @EventType annotation on the event class, or
        //    an EventTypeBuildItem from extensions) could solve this predictably but adds ceremony.
        //
        // For now, programmatic consumers are exact-type only. Use @OnEvent for subtype fan-out.
        throw new UnsupportedOperationException(
                "Programmatic consumer registration is not yet implemented in this POC. "
                        + "Use @OnEvent annotated methods for declarative consumers.");
    }

    private String resolveAddress(T event) {
        // Always use the runtime type, not the declared generic type
        return EventAddress.of(event.getClass(), qualifiers);
    }

    private Set<Annotation> mergeQualifiers(Annotation[] additional) {
        if (additional == null || additional.length == 0) {
            return this.qualifiers;
        }
        Set<Annotation> merged = new HashSet<>(this.qualifiers);
        for (Annotation a : additional) {
            merged.add(a);
        }
        return merged;
    }
}

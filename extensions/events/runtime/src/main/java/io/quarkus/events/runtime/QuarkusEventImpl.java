package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import io.quarkus.events.EventConsumerRegistration;
import io.quarkus.events.QuarkusEvent;
import io.smallrye.mutiny.Uni;

/**
 * Runtime implementation of {@link QuarkusEvent}.
 * Delegates to the {@link EventDispatcher} for type-safe routing.
 * <p>
 * Stores the full event {@link Type} (including parameterized type info)
 * captured from the injection point by {@link QuarkusEventProducer}.
 */
public class QuarkusEventImpl<T> implements QuarkusEvent<T> {

    private static final AtomicLong CONSUMER_ID = new AtomicLong(0);

    private final Type eventType;
    private final Set<Annotation> qualifiers;

    public QuarkusEventImpl(Type eventType, Set<Annotation> qualifiers) {
        this.eventType = eventType;
        this.qualifiers = qualifiers != null ? Set.copyOf(qualifiers) : Set.of();
    }

    @Override
    public void publish(T event) {
        dispatcher().publish(event, eventType, qualifiers);
    }

    @Override
    public void send(T event) {
        dispatcher().send(event, eventType, qualifiers);
    }

    @Override
    public <R> Uni<R> request(T event, Class<R> replyType) {
        return dispatcher().request(event, eventType, qualifiers);
    }

    @Override
    public <U extends T> QuarkusEvent<U> select(Class<U> subtype, Annotation... qualifiers) {
        Set<Annotation> merged = mergeQualifiers(qualifiers);
        return new QuarkusEventImpl<>(subtype, merged);
    }

    @Override
    public QuarkusEvent<T> select(Annotation... qualifiers) {
        Set<Annotation> merged = mergeQualifiers(qualifiers);
        return new QuarkusEventImpl<>(eventType, merged);
    }

    @Override
    public EventConsumerRegistration consumer(Class<T> eventType, Consumer<T> handler) {
        String address = "__qx_event__/programmatic/" + CONSUMER_ID.getAndIncrement();

        // Ensure a codec is registered for this type
        EventsRecorder.registerCodecForType(eventType);

        // Register a Vert.x consumer on the unique address
        var eventBus = io.quarkus.arc.Arc.container()
                .instance(io.vertx.mutiny.core.eventbus.EventBus.class).get();
        var vertxConsumer = eventBus.localConsumer(address);
        vertxConsumer.handler(message -> {
            @SuppressWarnings("unchecked")
            T body = (T) message.body();
            handler.accept(body);
        });

        // Register in the dispatcher for type-based routing
        EventConsumerRegistration dispatcherReg = dispatcher().registerConsumer(eventType, this.qualifiers, address);

        return () -> {
            vertxConsumer.unregister();
            return dispatcherReg.unregister();
        };
    }

    private EventDispatcher dispatcher() {
        return EventsRecorder.dispatcher;
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

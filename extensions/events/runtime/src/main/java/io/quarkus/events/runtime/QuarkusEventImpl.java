package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import io.quarkus.arc.Arc;
import io.quarkus.events.EventConsumerRegistration;
import io.quarkus.events.QuarkusEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.MessageConsumer;

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
    private final Map<String, Object> metadata;

    public QuarkusEventImpl(Type eventType, Set<Annotation> qualifiers) {
        this(eventType, qualifiers, null);
    }

    private QuarkusEventImpl(Type eventType, Set<Annotation> qualifiers, Map<String, Object> metadata) {
        this.eventType = eventType;
        this.qualifiers = qualifiers != null ? Set.copyOf(qualifiers) : Set.of();
        this.metadata = metadata;
    }

    @Override
    public void publish(T event) {
        dispatcher().publish(createEnvelope(event));
    }

    @Override
    public void send(T event) {
        dispatcher().send(createEnvelope(event));
    }

    @Override
    public <R> Uni<R> request(T event, Class<R> replyType) {
        return dispatcher().request(createEnvelope(event), replyType);
    }

    @Override
    public <U extends T> QuarkusEvent<U> select(Class<U> subtype, Annotation... qualifiers) {
        return new QuarkusEventImpl<>(subtype, mergeQualifiers(qualifiers), metadata);
    }

    @Override
    public QuarkusEvent<T> select(Annotation... qualifiers) {
        return new QuarkusEventImpl<>(eventType, mergeQualifiers(qualifiers), metadata);
    }

    @Override
    public QuarkusEvent<T> withMetadata(String key, Object value) {
        // Copy the map on each call to ensure the base instance is not mutated.
        // Without the copy, reusing the base instance after withMetadata() would leak
        // metadata entries into unrelated emissions. The map is small (typically 1-2 entries)
        // so the copy cost is negligible.
        Map<String, Object> newMetadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        newMetadata.put(key, value);
        return new QuarkusEventImpl<>(eventType, qualifiers, newMetadata);
    }

    @Override
    public EventConsumerRegistration consumer(Class<T> eventType, Consumer<T> handler) {
        String address = "__qx_event__/programmatic/" + CONSUMER_ID.getAndIncrement();

        // Register a Vert.x consumer on the unique address
        // (EventEnvelope codec is already registered at init time)
        EventBus eventBus = Arc.container().instance(EventBus.class).get();
        MessageConsumer<Object> vertxConsumer = eventBus.localConsumer(address);
        vertxConsumer.handler(message -> {
            @SuppressWarnings("unchecked")
            T event = (T) ((EventEnvelope) message.body()).event();
            handler.accept(event);
        });

        // Register in the dispatcher for type-based routing
        EventConsumerRegistration dispatcherReg = dispatcher().registerConsumer(eventType, this.qualifiers, address);

        return () -> {
            vertxConsumer.unregister();
            return dispatcherReg.unregister();
        };
    }

    @Override
    public <R> EventConsumerRegistration replyingConsumer(Class<T> eventType, Function<T, R> handler,
            Class<R> responseType) {
        String address = "__qx_event__/programmatic/" + CONSUMER_ID.getAndIncrement();

        EventBus eventBus = Arc.container().instance(EventBus.class).get();
        MessageConsumer<Object> vertxConsumer = eventBus.localConsumer(address);
        vertxConsumer.handler(message -> {
            @SuppressWarnings("unchecked")
            T event = (T) ((EventEnvelope) message.body()).event();
            R result = handler.apply(event);
            message.reply(result);
        });

        EventConsumerRegistration dispatcherReg = dispatcher().registerConsumer(eventType, this.qualifiers,
                responseType, address);

        return () -> {
            vertxConsumer.unregister();
            return dispatcherReg.unregister();
        };
    }

    @Override
    public <R> EventConsumerRegistration replyingConsumerAsync(Class<T> eventType, Function<T, Uni<R>> handler,
            Class<R> responseType) {
        String address = "__qx_event__/programmatic/" + CONSUMER_ID.getAndIncrement();

        EventBus eventBus = Arc.container().instance(EventBus.class).get();
        MessageConsumer<Object> vertxConsumer = eventBus.localConsumer(address);
        vertxConsumer.handler(message -> {
            @SuppressWarnings("unchecked")
            T event = (T) ((EventEnvelope) message.body()).event();
            handler.apply(event).subscribe().with(
                    result -> message.reply(result),
                    failure -> message.fail(500, failure.getMessage()));
        });

        EventConsumerRegistration dispatcherReg = dispatcher().registerConsumer(eventType, this.qualifiers,
                responseType, address);

        return () -> {
            vertxConsumer.unregister();
            return dispatcherReg.unregister();
        };
    }

    private EventEnvelope createEnvelope(T event) {
        return new EventEnvelope(event,
                metadata != null ? Map.copyOf(metadata) : Map.of(),
                eventType, qualifiers);
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

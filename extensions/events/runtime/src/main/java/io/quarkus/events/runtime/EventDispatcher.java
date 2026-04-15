package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.inject.spi.BeanContainer;

import io.quarkus.events.EventConsumerRegistration;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;

/**
 * Central event dispatcher that uses CDI's {@code isMatchingEvent} for type-safe resolution.
 * <p>
 * Each consumer registers with its observed {@link Type}, qualifier {@link Annotation}s,
 * and optional response {@link Type}. When an event is sent, the dispatcher iterates all
 * consumers and uses {@link BeanContainer#isMatchingEvent} to find matches. Results are
 * cached and invalidated when consumers are added or removed.
 */
public class EventDispatcher {

    private final EventBus eventBus;
    private final BeanContainer beanContainer;

    private final ConcurrentMap<String, ConsumerRecord> consumers = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResolutionKey, List<ConsumerRecord>> resolvedConsumers = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResolutionKey, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    public EventDispatcher(EventBus eventBus, BeanContainer beanContainer) {
        this.eventBus = eventBus;
        this.beanContainer = beanContainer;
    }

    /**
     * Register a consumer with no response type (fire-and-forget or programmatic).
     */
    public EventConsumerRegistration registerConsumer(Type observedType, Set<Annotation> qualifiers, String address) {
        return registerConsumer(observedType, qualifiers, null, address);
    }

    /**
     * Register a consumer with its observed type, qualifiers, response type, and Vert.x address.
     *
     * @param responseType the consumer's return type, or {@code null} for void consumers
     */
    public EventConsumerRegistration registerConsumer(Type observedType, Set<Annotation> qualifiers,
            Type responseType, String address) {
        ConsumerRecord record = new ConsumerRecord(observedType, qualifiers, responseType, address);
        consumers.put(address, record);
        invalidateCache(record);
        return () -> {
            consumers.remove(address);
            invalidateCache(record);
            return Uni.createFrom().voidItem();
        };
    }

    /**
     * Publish an event to ALL matching consumers (regardless of response type).
     */
    public void publish(EventEnvelope envelope) {
        List<ConsumerRecord> matching = resolveConsumers(
                new ResolutionKey(envelope.eventType(), envelope.qualifiers(), null));
        for (ConsumerRecord record : matching) {
            eventBus.publish(record.address, envelope);
        }
    }

    /**
     * Send an event to ONE matching consumer, round-robin (regardless of response type).
     */
    public void send(EventEnvelope envelope) {
        ConsumerRecord selected = nextConsumer(
                new ResolutionKey(envelope.eventType(), envelope.qualifiers(), null));
        if (selected != null) {
            eventBus.send(selected.address, envelope);
        }
    }

    /**
     * Send an event to ONE matching consumer that can produce the requested response type.
     * Void consumers and consumers with incompatible return types are excluded.
     */
    public <R> Uni<R> request(EventEnvelope envelope, Type responseType) {
        ConsumerRecord selected = nextConsumer(
                new ResolutionKey(envelope.eventType(), envelope.qualifiers(), responseType));
        if (selected == null) {
            return Uni.createFrom().failure(
                    new IllegalStateException("No consumers registered for event type: " + envelope.eventType()
                            + " with response type: " + responseType));
        }
        return eventBus.<R> request(selected.address, envelope)
                .onItem().transform(Message::body);
    }

    private ConsumerRecord nextConsumer(ResolutionKey key) {
        List<ConsumerRecord> matching = resolvedConsumers.computeIfAbsent(key, this::computeMatching);
        if (matching.isEmpty()) {
            return null;
        }
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % matching.size());
        return matching.get(index);
    }

    private List<ConsumerRecord> resolveConsumers(ResolutionKey key) {
        return resolvedConsumers.computeIfAbsent(key, this::computeMatching);
    }

    private List<ConsumerRecord> computeMatching(ResolutionKey key) {
        List<ConsumerRecord> matching = new ArrayList<>();
        for (ConsumerRecord record : consumers.values()) {
            if (!beanContainer.isMatchingEvent(key.eventType, key.qualifiers,
                    record.observedType, record.qualifiers)) {
                continue;
            }
            // If a response type is requested, filter by assignability
            if (key.responseType != null) {
                if (record.responseType == null) {
                    // void consumer cannot satisfy a request
                    continue;
                }
                if (!beanContainer.isMatchingEvent(record.responseType, Set.of(),
                        key.responseType, Set.of())) {
                    // consumer's return type is not assignable to the requested type
                    continue;
                }
            }
            matching.add(record);
        }
        return matching;
    }

    private void invalidateCache(ConsumerRecord record) {
        resolvedConsumers.keySet().removeIf(key -> beanContainer.isMatchingEvent(key.eventType, key.qualifiers,
                record.observedType, record.qualifiers));
    }

    private record ConsumerRecord(Type observedType, Set<Annotation> qualifiers, Type responseType, String address) {
    }

    private record ResolutionKey(Type eventType, Set<Annotation> qualifiers, Type responseType) {
    }
}

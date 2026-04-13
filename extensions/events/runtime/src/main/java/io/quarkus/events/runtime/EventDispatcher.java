package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
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
 * Each consumer registers with its observed {@link Type} and qualifier {@link Annotation}s.
 * When an event is sent, the dispatcher iterates all consumers and uses
 * {@link BeanContainer#isMatchingEvent} to find matches. Results are cached and
 * invalidated when consumers are added or removed.
 */
public class EventDispatcher {

    private final EventBus eventBus;
    private final BeanContainer beanContainer;

    /**
     * All registered consumers, keyed by unique consumer ID.
     */
    private final ConcurrentMap<String, ConsumerRecord> consumers = new ConcurrentHashMap<>();

    /**
     * Resolution cache: maps (eventType, qualifiers) to matching consumer records.
     */
    private final ConcurrentMap<ResolutionKey, List<ConsumerRecord>> resolvedConsumers = new ConcurrentHashMap<>();

    /**
     * Round-robin counters keyed by resolution key.
     */
    private final ConcurrentMap<ResolutionKey, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    public EventDispatcher(EventBus eventBus, BeanContainer beanContainer) {
        this.eventBus = eventBus;
        this.beanContainer = beanContainer;
    }

    /**
     * Register a consumer for the given observed type and qualifiers.
     *
     * @param observedType the type this consumer observes (may be parameterized)
     * @param qualifiers qualifier annotations for routing
     * @param address the unique Vert.x address for this consumer
     * @return a registration handle for unregistering
     */
    public EventConsumerRegistration registerConsumer(Type observedType, Set<Annotation> qualifiers, String address) {
        ConsumerRecord record = new ConsumerRecord(observedType, qualifiers, address);
        consumers.put(address, record);
        invalidateCache(record);
        return () -> {
            consumers.remove(address);
            invalidateCache(record);
            return Uni.createFrom().voidItem();
        };
    }

    /**
     * Publish an event to ALL matching consumers.
     */
    public void publish(Object event, Type eventType, Collection<Annotation> qualifiers) {
        ensureCodec(event.getClass());
        List<ConsumerRecord> matching = resolveConsumers(eventType, qualifierSet(qualifiers));
        for (ConsumerRecord record : matching) {
            eventBus.publish(record.address, event);
        }
    }

    /**
     * Send an event to ONE matching consumer (round-robin).
     */
    public void send(Object event, Type eventType, Collection<Annotation> qualifiers) {
        ensureCodec(event.getClass());
        ConsumerRecord selected = nextConsumer(eventType, qualifierSet(qualifiers));
        if (selected != null) {
            eventBus.send(selected.address, event);
        }
    }

    /**
     * Send an event to ONE matching consumer and wait for a reply.
     */
    public <R> Uni<R> request(Object event, Type eventType, Collection<Annotation> qualifiers) {
        ensureCodec(event.getClass());
        ConsumerRecord selected = nextConsumer(eventType, qualifierSet(qualifiers));
        if (selected == null) {
            return Uni.createFrom().failure(
                    new IllegalStateException("No consumers registered for event type: " + eventType));
        }
        return eventBus.<R> request(selected.address, event)
                .onItem().transform(Message::body);
    }

    /**
     * Select the next consumer in round-robin order from matching consumers.
     *
     * @return the selected consumer, or {@code null} if no consumers match
     */
    private ConsumerRecord nextConsumer(Type eventType, Set<Annotation> qualifiers) {
        ResolutionKey key = new ResolutionKey(eventType, qualifiers);
        List<ConsumerRecord> matching = resolvedConsumers.computeIfAbsent(key, this::computeMatching);
        if (matching.isEmpty()) {
            return null;
        }
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % matching.size());
        return matching.get(index);
    }

    /**
     * Resolve all consumers matching the given event type and qualifiers using CDI's isMatchingEvent.
     */
    private List<ConsumerRecord> resolveConsumers(Type eventType, Set<Annotation> qualifiers) {
        return resolvedConsumers.computeIfAbsent(new ResolutionKey(eventType, qualifiers), this::computeMatching);
    }

    private List<ConsumerRecord> computeMatching(ResolutionKey key) {
        List<ConsumerRecord> matching = new ArrayList<>();
        for (ConsumerRecord record : consumers.values()) {
            if (beanContainer.isMatchingEvent(key.eventType, key.qualifiers,
                    record.observedType, record.qualifiers)) {
                matching.add(record);
            }
        }
        return matching;
    }

    /**
     * Invalidate cached resolutions that could be affected by a consumer change.
     */
    private void invalidateCache(ConsumerRecord record) {
        resolvedConsumers.keySet().removeIf(key -> beanContainer.isMatchingEvent(key.eventType, key.qualifiers,
                record.observedType, record.qualifiers));
    }

    private void ensureCodec(Class<?> eventType) {
        EventsRecorder.registerCodecForType(eventType);
    }

    private static Set<Annotation> qualifierSet(Collection<Annotation> qualifiers) {
        if (qualifiers == null || qualifiers.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(qualifiers);
    }

    private record ConsumerRecord(Type observedType, Set<Annotation> qualifiers, String address) {
    }

    private record ResolutionKey(Type eventType, Set<Annotation> qualifiers) {
    }
}

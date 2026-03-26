package io.quarkus.events.runtime;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.events.EventConsumerRegistration;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.core.eventbus.Message;

/**
 * Central event dispatcher that performs CDI-style type resolution at send time.
 * <p>
 * Instead of pre-registering consumers on multiple Vert.x addresses (fan-out),
 * each consumer registers once with its observed type. When an event is sent,
 * the dispatcher computes the type closure of the event's runtime type and
 * finds all consumers whose observed type is in that closure.
 * <p>
 * This approach supports both build-time ({@code @OnEvent}) and runtime
 * (programmatic) consumers with full subtype matching.
 */
public class EventDispatcher {

    private final EventBus eventBus;

    /**
     * Registry of consumers keyed by their observed type.
     * A consumer of {@code Animal} is stored under {@code Animal.class}.
     * When a {@code Dog} event is sent, we walk up Dog's type hierarchy,
     * find {@code Animal} in the closure, and include this consumer.
     */
    private final Map<ConsumerKey, List<ConsumerEntry>> consumers = new ConcurrentHashMap<>();

    /**
     * Round-robin counter for point-to-point (send) and request-response delivery.
     * Keyed by the event's runtime type + qualifiers to ensure fair distribution.
     * <p>
     * Note: this is a simplified implementation. The matching consumer list is recomputed
     * on every send, so if consumers are added/removed between sends, the modulo shifts
     * and may skip or repeat consumers. Vert.x solves this with ConcurrentCyclicSequence —
     * an immutable array where add/remove creates a new sequence but the position counter
     * carries over. A production implementation should adopt a similar approach.
     */
    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    /**
     * Cache for type closures to avoid recomputing on every send.
     */
    private final Map<Class<?>, Set<Class<?>>> typeClosureCache = new ConcurrentHashMap<>();

    public EventDispatcher(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    /**
     * Register a consumer for the given observed type and qualifiers.
     * The consumer will receive events whose runtime type has {@code observedType}
     * in its type closure, and whose qualifiers match.
     *
     * @param observedType the type this consumer observes
     * @param qualifiers qualifier names for routing (sorted deterministically)
     * @param address the unique Vert.x address for this consumer
     * @return a registration handle for unregistering
     */
    public EventConsumerRegistration registerConsumer(Class<?> observedType, Set<String> qualifiers, String address) {
        ConsumerKey key = new ConsumerKey(observedType, qualifiers);
        ConsumerEntry entry = new ConsumerEntry(address);
        consumers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(entry);
        return () -> {
            List<ConsumerEntry> entries = consumers.get(key);
            if (entries != null) {
                entries.remove(entry);
            }
            return Uni.createFrom().voidItem();
        };
    }

    /**
     * Publish an event to ALL matching consumers.
     */
    public void publish(Object event, Collection<Annotation> qualifiers) {
        ensureCodec(event.getClass());
        List<ConsumerEntry> matching = resolveConsumers(event.getClass(), qualifierNames(qualifiers));
        for (ConsumerEntry entry : matching) {
            eventBus.publish(entry.address, event);
        }
    }

    /**
     * Send an event to ONE matching consumer (round-robin).
     */
    public void send(Object event, Collection<Annotation> qualifiers) {
        ensureCodec(event.getClass());
        Set<String> qualifierNames = qualifierNames(qualifiers);
        List<ConsumerEntry> matching = resolveConsumers(event.getClass(), qualifierNames);
        if (matching.isEmpty()) {
            return;
        }
        String counterKey = event.getClass().getName() + qualifierSuffix(qualifierNames);
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % matching.size());
        eventBus.send(matching.get(index).address, event);
    }

    /**
     * Send an event to ONE matching consumer and wait for a reply.
     * <p>
     * TODO: The reply type is not validated — if the consumer returns a type that doesn't match
     * what the caller expects, a ClassCastException will occur at the call site. Consider adding
     * an assignability check on the reply before returning it.
     */
    public <R> Uni<R> request(Object event, Collection<Annotation> qualifiers) {
        ensureCodec(event.getClass());
        Set<String> qualifierNames = qualifierNames(qualifiers);
        List<ConsumerEntry> matching = resolveConsumers(event.getClass(), qualifierNames);
        if (matching.isEmpty()) {
            return Uni.createFrom().failure(
                    new IllegalStateException("No consumers registered for event type: " + event.getClass().getName()));
        }
        String counterKey = event.getClass().getName() + qualifierSuffix(qualifierNames);
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % matching.size());
        return eventBus.<R> request(matching.get(index).address, event)
                .onItem().transform(Message::body);
    }

    /**
     * Resolve all consumers that match the given event type and qualifiers.
     * Computes the type closure of {@code eventType} and finds consumers
     * whose observed type is in that closure with matching qualifiers.
     */
    private List<ConsumerEntry> resolveConsumers(Class<?> eventType, Set<String> qualifierNames) {
        Set<Class<?>> typeClosure = getTypeClosure(eventType);
        List<ConsumerEntry> result = new ArrayList<>();
        for (Class<?> type : typeClosure) {
            ConsumerKey key = new ConsumerKey(type, qualifierNames);
            List<ConsumerEntry> entries = consumers.get(key);
            if (entries != null) {
                result.addAll(entries);
            }
        }
        return result;
    }

    /**
     * Compute the type closure of a class — all supertypes and interfaces, recursively.
     * Uses {@code Class.getSuperclass()} and {@code Class.getInterfaces()} which work in native mode.
     */
    private Set<Class<?>> getTypeClosure(Class<?> type) {
        return typeClosureCache.computeIfAbsent(type, t -> {
            Set<Class<?>> closure = new HashSet<>();
            collectTypeClosure(t, closure);
            return closure;
        });
    }

    private void collectTypeClosure(Class<?> type, Set<Class<?>> closure) {
        if (type == null || type == Object.class || !closure.add(type)) {
            return;
        }
        collectTypeClosure(type.getSuperclass(), closure);
        for (Class<?> iface : type.getInterfaces()) {
            collectTypeClosure(iface, closure);
        }
    }

    /**
     * Ensure a codec is registered for the given event type.
     * Needed for types not known at build time (e.g., programmatic consumers).
     */
    private void ensureCodec(Class<?> eventType) {
        EventsRecorder.registerCodecForType(eventType);
    }

    private Set<String> qualifierNames(Collection<Annotation> qualifiers) {
        if (qualifiers == null || qualifiers.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (Annotation a : qualifiers) {
            names.add(a.annotationType().getName());
        }
        return names;
    }

    private String qualifierSuffix(Set<String> qualifierNames) {
        if (qualifierNames.isEmpty()) {
            return "";
        }
        // Sort for deterministic keys regardless of iteration order
        List<String> sorted = new ArrayList<>(qualifierNames);
        sorted.sort(null);
        StringBuilder sb = new StringBuilder();
        for (String name : sorted) {
            sb.append('#').append(name);
        }
        return sb.toString();
    }

    /**
     * Key for the consumer registry — observed type + qualifier names.
     */
    private record ConsumerKey(Class<?> observedType, Set<String> qualifierNames) {
    }

    /**
     * Entry in the consumer registry — the unique Vert.x address for a consumer.
     */
    private record ConsumerEntry(String address) {
    }
}

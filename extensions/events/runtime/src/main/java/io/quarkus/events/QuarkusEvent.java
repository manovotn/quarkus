package io.quarkus.events;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;

import io.smallrye.mutiny.Uni;

/**
 * Type-safe event emitter for in-process messaging.
 * <p>
 * Modeled after CDI's {@code Event<T>}, this is an injectable, type-safe event emitter
 * that supports publish/subscribe, point-to-point, and request-response patterns.
 * <p>
 * Usage:
 *
 * <pre>
 * // Typed injection
 * &#64;Inject
 * QuarkusEvent&lt;OrderCreated&gt; orderEvent;
 *
 * void placeOrder(Order order) {
 *     orderEvent.publish(new OrderCreated(order)); // all consumers
 *     orderEvent.send(new OrderCreated(order));    // one consumer (round-robin)
 * }
 *
 * // Qualifiers at injection point
 * &#64;Inject &#64;Premium
 * QuarkusEvent&lt;Order&gt; premiumOrderEvent;
 *
 * // Dynamic qualifier narrowing
 * orderEvent.select(new PremiumLiteral()).send(order);
 *
 * // Request-response
 * Uni&lt;Confirmation&gt; confirm(Order order) {
 *     return orderEvent.request(new OrderCreated(order), Confirmation.class);
 * }
 * </pre>
 *
 * @param <T> the event type
 */
public interface QuarkusEvent<T> {

    /**
     * Publish the event to all matching consumers. Fire-and-forget.
     *
     * @param event the event payload
     */
    void publish(T event);

    /**
     * Send the event to one matching consumer (round-robin). Fire-and-forget.
     *
     * @param event the event payload
     */
    void send(T event);

    /**
     * Send the event and wait for a reply from one consumer.
     *
     * @param event the event payload
     * @param replyType the expected reply type (needed for generic type inference)
     * @param <R> the reply type
     * @return a {@link Uni} that completes with the reply
     */
    <R> Uni<R> request(T event, Class<R> replyType);

    /**
     * Narrow the event type and/or add qualifiers.
     * Returns a new {@link QuarkusEvent} for the subtype with the given qualifiers
     * merged with any qualifiers already present on this instance.
     *
     * @param subtype the event subtype
     * @param qualifiers additional qualifiers
     * @param <U> the subtype
     * @return a narrowed {@link QuarkusEvent}
     */
    <U extends T> QuarkusEvent<U> select(Class<U> subtype, Annotation... qualifiers);

    /**
     * Add qualifiers without narrowing the type.
     * Returns a new {@link QuarkusEvent} with the given qualifiers
     * merged with any qualifiers already present on this instance.
     *
     * @param qualifiers additional qualifiers
     * @return a narrowed {@link QuarkusEvent}
     */
    QuarkusEvent<T> select(Annotation... qualifiers);

    /**
     * Attach a metadata entry to subsequent emissions from this event instance.
     * Returns a new {@link QuarkusEvent} with the metadata added.
     * <p>
     * Metadata is accessible in consumers via an {@link EventInfo} parameter.
     *
     * @param key the metadata key
     * @param value the metadata value
     * @return a new {@link QuarkusEvent} with the metadata entry added
     */
    QuarkusEvent<T> withMetadata(String key, Object value);

    /**
     * Programmatically register a fire-and-forget consumer for events of the given type.
     * The consumer receives events of the specified type and all subtypes, following the
     * same matching rules as {@code @OnEvent} consumers. Qualifiers from this
     * {@link QuarkusEvent} instance are applied to the consumer.
     *
     * @param eventType the event type to consume
     * @param handler the event handler
     * @return a registration handle that can be used to unregister the consumer
     */
    EventConsumerRegistration consumer(Class<T> eventType, Consumer<T> handler);
}

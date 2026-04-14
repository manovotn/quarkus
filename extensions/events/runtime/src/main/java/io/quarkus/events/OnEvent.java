package io.quarkus.events;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a business method to be automatically registered as an event consumer.
 * <p>
 * The event type (address) is derived from the method parameter type — there is no string-based address.
 * The method can accept the event payload directly or wrapped in additional context.
 * <p>
 * If the method returns a value, it becomes the reply for request-response interactions.
 * For fire-and-forget ({@code send}/{@code publish}), the return value is discarded.
 *
 * <pre>
 * &#64;ApplicationScoped
 * class OrderHandlers {
 *
 *     &#64;OnEvent
 *     void onOrderCreated(OrderCreated event) {
 *         // fire-and-forget consumer
 *     }
 *
 *     &#64;OnEvent
 *     Uni&lt;OrderConfirmation&gt; processOrder(OrderCreated event) {
 *         // reply-capable consumer
 *         return Uni.createFrom().item(new OrderConfirmation(...));
 *     }
 * }
 * </pre>
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface OnEvent {

    // TODO: blocking() is carried over from the original Vert.x @ConsumeEvent design.
    // It should be replaced with return type analysis and @Blocking/@NonBlocking annotations,
    // consistent with other Quarkus extensions. Also add @RunOnVirtualThread support.

    /**
     * If {@code true}, the consumer is invoked as a blocking operation using a worker thread.
     */
    boolean blocking() default false;
}

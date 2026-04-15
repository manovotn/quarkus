package io.quarkus.events;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Identifies the event parameter of a consumer method on a CDI bean.
 * <p>
 * The annotated parameter determines the event type for routing. The method may have
 * additional parameters: {@link EventInfo} for event metadata, or any CDI bean for injection.
 * <p>
 * If the method returns a value, it becomes the reply for request-response interactions.
 * For fire-and-forget ({@code send}/{@code publish}), the return value is discarded.
 *
 * <pre>
 * &#64;ApplicationScoped
 * class OrderHandlers {
 *
 *     void onOrderCreated(&#64;OnEvent OrderCreated event) {
 *         // fire-and-forget consumer
 *     }
 *
 *     Uni&lt;OrderConfirmation&gt; processOrder(&#64;OnEvent OrderCreated event, MyService service) {
 *         // reply-capable consumer with CDI-injected parameter
 *         return Uni.createFrom().item(new OrderConfirmation(...));
 *     }
 * }
 * </pre>
 */
@Target(PARAMETER)
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

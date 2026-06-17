package io.quarkus.events;

import io.smallrye.mutiny.Uni;

/**
 * Handle to a programmatically registered event consumer.
 * Used to unregister the consumer when it is no longer needed.
 */
public interface EventConsumerRegistration {

    /**
     * Unregister this consumer.
     *
     * @return a {@link Uni} that completes when the consumer is unregistered
     */
    Uni<Void> unregister();
}

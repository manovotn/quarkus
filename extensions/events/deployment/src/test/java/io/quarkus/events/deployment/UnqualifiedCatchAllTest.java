package io.quarkus.events.deployment;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.OnEvent;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies CDI-standard qualifier matching behavior: an unqualified consumer
 * matches ALL events of a matching type, including qualified ones.
 * <p>
 * This is the standard CDI rule: "The observer method has no event qualifiers
 * OR has a subset of the event qualifiers." An observer with no qualifiers
 * satisfies the first branch and matches everything.
 * <p>
 * The previous string-based qualifier matching did NOT follow this rule —
 * unqualified consumers only matched unqualified events (exact set equality).
 */
public class UnqualifiedCatchAllTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Premium.class, PremiumLiteral.class,
                    Notification.class,
                    CatchAllConsumer.class, PremiumConsumer.class));

    @Inject
    QuarkusEvent<Notification> notification;

    @Test
    public void testUnqualifiedConsumerReceivesQualifiedEvent() throws InterruptedException {
        CatchAllConsumer.LATCH = new CountDownLatch(1);
        PremiumConsumer.LATCH = new CountDownLatch(1);

        // Send a @Premium event
        notification.select(PremiumLiteral.INSTANCE).publish(new Notification("qualified"));

        // The @Premium consumer should receive it
        assertTrue(PremiumConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "@Premium consumer should receive @Premium event");

        // The unqualified (catch-all) consumer should ALSO receive it
        // This is CDI behavior: unqualified observer matches everything
        assertTrue(CatchAllConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Unqualified consumer should receive @Premium event (CDI catch-all behavior)");
    }

    @Test
    public void testUnqualifiedConsumerReceivesUnqualifiedEvent() throws InterruptedException {
        CatchAllConsumer.LATCH = new CountDownLatch(1);
        PremiumConsumer.LATCH = new CountDownLatch(1);

        // Send an unqualified event
        notification.publish(new Notification("plain"));

        // The unqualified consumer should receive it
        assertTrue(CatchAllConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Unqualified consumer should receive unqualified event");

        // The @Premium consumer should NOT receive it (it requires @Premium qualifier)
        // We don't assert this here since it's tested elsewhere; just verify the catch-all works
    }

    // --- Event type ---

    public record Notification(String message) {
    }

    // --- Qualifier ---

    @Qualifier
    @Target({ FIELD, METHOD, PARAMETER })
    @Retention(RUNTIME)
    public @interface Premium {
    }

    public static class PremiumLiteral extends AnnotationLiteral<Premium> implements Premium {
        public static final PremiumLiteral INSTANCE = new PremiumLiteral();
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class CatchAllConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        void onNotification(@OnEvent Notification n) {
            LATCH.countDown();
        }
    }

    @ApplicationScoped
    public static class PremiumConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        void onPremiumNotification(@OnEvent @Premium Notification n) {
            LATCH.countDown();
        }
    }
}

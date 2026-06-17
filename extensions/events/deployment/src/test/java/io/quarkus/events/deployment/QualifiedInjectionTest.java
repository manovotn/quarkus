package io.quarkus.events.deployment;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Verifies that {@code @Inject @SomeQualifier QuarkusEvent<T>} works correctly.
 * This requires a synthetic bean (not a CDI producer) because a producer with only
 * {@code @Default} qualifier won't satisfy qualified injection points.
 */
public class QualifiedInjectionTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Priority.class, PriorityLiteral.class,
                    Alert.class,
                    HighPriorityConsumer.class, NormalConsumer.class));

    @Inject
    @Priority
    QuarkusEvent<Alert> highPriorityAlert;

    @Inject
    QuarkusEvent<Alert> normalAlert;

    @Test
    public void testQualifiedInjectionSendsWithQualifier() throws InterruptedException {
        HighPriorityConsumer.LATCH = new CountDownLatch(1);
        NormalConsumer.LATCH = new CountDownLatch(1);

        // Send via the @Priority-qualified injection point
        highPriorityAlert.publish(new Alert("urgent"));

        // The @Priority consumer should receive it
        assertTrue(HighPriorityConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "@Priority consumer should receive event from @Priority injection point");

        // The unqualified consumer should also receive it (CDI catch-all)
        assertTrue(NormalConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Unqualified consumer should receive event from @Priority injection point (CDI catch-all)");
    }

    @Test
    public void testUnqualifiedInjectionDoesNotReachQualifiedConsumer() throws InterruptedException {
        HighPriorityConsumer.LATCH = new CountDownLatch(1);
        NormalConsumer.LATCH = new CountDownLatch(1);

        // Send via the unqualified injection point
        normalAlert.publish(new Alert("routine"));

        // The unqualified consumer should receive it
        assertTrue(NormalConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Unqualified consumer should receive unqualified event");

        // The @Priority consumer should NOT receive it
        assertFalse(HighPriorityConsumer.LATCH.await(500, TimeUnit.MILLISECONDS),
                "@Priority consumer should NOT receive unqualified event");
    }

    @Test
    public void testQualifiedRequestReply() {
        String reply = highPriorityAlert
                .request(new Alert("critical"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        // Both consumers match (unqualified is catch-all), but only HighPriorityConsumer
        // and NormalConsumer return String. Round-robin picks one.
        assertTrue(reply.equals("HIGH:critical") || reply.equals("normal:critical"),
                "Expected reply from either consumer, got: " + reply);
    }

    // --- Event type ---

    public record Alert(String message) {
    }

    // --- Qualifier ---

    @Qualifier
    @Target({ FIELD, METHOD, PARAMETER })
    @Retention(RUNTIME)
    public @interface Priority {
    }

    public static class PriorityLiteral extends AnnotationLiteral<Priority> implements Priority {
        public static final PriorityLiteral INSTANCE = new PriorityLiteral();
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class HighPriorityConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        String onHighPriority(@OnEvent @Priority Alert alert) {
            LATCH.countDown();
            return "HIGH:" + alert.message();
        }
    }

    @ApplicationScoped
    public static class NormalConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        String onAlert(@OnEvent Alert alert) {
            LATCH.countDown();
            return "normal:" + alert.message();
        }
    }
}

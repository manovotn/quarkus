package io.quarkus.events.deployment;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Qualifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.OnEvent;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;

public class QualifiedEventsTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Premium.class, PremiumLiteral.class,
                    Express.class, ExpressLiteral.class,
                    // Event types — each test scenario uses its own type to avoid round-robin interference
                    RequestEvent.class, SendEvent.class, PublishEvent.class,
                    IsolationEvent.class, MultiQualifierEvent.class,
                    // Consumers
                    QualifiedRequestConsumer.class, UnqualifiedRequestConsumer.class,
                    QualifiedSendConsumer.class,
                    QualifiedPublishConsumer1.class, QualifiedPublishConsumer2.class,
                    IsolationQualifiedConsumer.class, IsolationUnqualifiedConsumer.class,
                    MultiQualifierConsumer.class));

    @Inject
    QuarkusEvent<RequestEvent> requestEvent;

    @Inject
    QuarkusEvent<SendEvent> sendEvent;

    @Inject
    QuarkusEvent<PublishEvent> publishEvent;

    @Inject
    QuarkusEvent<IsolationEvent> isolationEvent;

    @Inject
    QuarkusEvent<MultiQualifierEvent> multiQualifierEvent;

    // --- Qualifiers ---

    @Qualifier
    @Retention(RUNTIME)
    @Target({ METHOD, FIELD, PARAMETER })
    public @interface Premium {
    }

    public static class PremiumLiteral implements Premium, Annotation {
        public static final PremiumLiteral INSTANCE = new PremiumLiteral();

        @Override
        public Class<? extends Annotation> annotationType() {
            return Premium.class;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Premium;
        }
    }

    @Qualifier
    @Retention(RUNTIME)
    @Target({ METHOD, FIELD, PARAMETER })
    public @interface Express {
    }

    public static class ExpressLiteral implements Express, Annotation {
        public static final ExpressLiteral INSTANCE = new ExpressLiteral();

        @Override
        public Class<? extends Annotation> annotationType() {
            return Express.class;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Express;
        }
    }

    // --- Event types (one per test scenario to avoid round-robin interference) ---

    public static class RequestEvent {
        public final String id;

        public RequestEvent(String id) {
            this.id = id;
        }
    }

    public static class SendEvent {
        public final String id;

        public SendEvent(String id) {
            this.id = id;
        }
    }

    public static class PublishEvent {
        public final String id;

        public PublishEvent(String id) {
            this.id = id;
        }
    }

    public static class IsolationEvent {
        public final String id;

        public IsolationEvent(String id) {
            this.id = id;
        }
    }

    public static class MultiQualifierEvent {
        public final String id;

        public MultiQualifierEvent(String id) {
            this.id = id;
        }
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class QualifiedRequestConsumer {
        @OnEvent
        String onPremiumRequest(@Premium RequestEvent event) {
            return "Premium: " + event.id;
        }
    }

    @ApplicationScoped
    public static class UnqualifiedRequestConsumer {
        @OnEvent
        String onRequest(RequestEvent event) {
            return "Standard: " + event.id;
        }
    }

    @ApplicationScoped
    public static class QualifiedSendConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        void onPremiumSend(@Premium SendEvent event) {
            LATCH.countDown();
        }
    }

    @ApplicationScoped
    public static class QualifiedPublishConsumer1 {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        void onPremiumPublish(@Premium PublishEvent event) {
            LATCH.countDown();
        }
    }

    @ApplicationScoped
    public static class QualifiedPublishConsumer2 {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        void onPremiumPublish(@Premium PublishEvent event) {
            LATCH.countDown();
        }
    }

    @ApplicationScoped
    public static class IsolationQualifiedConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onPremiumIsolation(@Premium IsolationEvent event) {
            LATCH.countDown();
            return "Premium: " + event.id;
        }
    }

    @ApplicationScoped
    public static class IsolationUnqualifiedConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onIsolation(IsolationEvent event) {
            LATCH.countDown();
            return "Standard: " + event.id;
        }
    }

    /**
     * Consumer with BOTH qualifiers. Note the qualifier order here is @Express @Premium
     * (alphabetically reversed) — the address generation must sort them deterministically.
     */
    @ApplicationScoped
    public static class MultiQualifierConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onMultiQualified(@Express @Premium MultiQualifierEvent event) {
            LATCH.countDown();
            return "MultiQualified: " + event.id;
        }
    }

    // --- Tests: Qualified event delivery (all three styles) ---

    @Test
    public void testQualifiedRequestReply() {
        String reply = requestEvent.select(new PremiumLiteral())
                .request(new RequestEvent("Q-REQ-1"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        // CDI behavior: unqualified consumer also matches qualified events (catch-all).
        // Both QualifiedRequestConsumer and UnqualifiedRequestConsumer match,
        // so round-robin may pick either one.
        assertTrue(reply.equals("Premium: Q-REQ-1") || reply.equals("Standard: Q-REQ-1"),
                "Expected reply from either @Premium or unqualified consumer, got: " + reply);
    }

    @Test
    public void testQualifiedSendDelivery() throws InterruptedException {
        QualifiedSendConsumer.LATCH = new CountDownLatch(1);

        sendEvent.select(new PremiumLiteral()).send(new SendEvent("Q-SEND-1"));

        assertTrue(QualifiedSendConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Qualified send() should deliver to @Premium consumer");
    }

    @Test
    public void testQualifiedPublishDelivery() throws InterruptedException {
        QualifiedPublishConsumer1.LATCH = new CountDownLatch(1);
        QualifiedPublishConsumer2.LATCH = new CountDownLatch(1);

        publishEvent.select(new PremiumLiteral()).publish(new PublishEvent("Q-PUB-1"));

        assertTrue(QualifiedPublishConsumer1.LATCH.await(2, TimeUnit.SECONDS),
                "Qualified publish() should deliver to first @Premium consumer");
        assertTrue(QualifiedPublishConsumer2.LATCH.await(2, TimeUnit.SECONDS),
                "Qualified publish() should deliver to second @Premium consumer");
    }

    // --- Tests: Qualified vs unqualified isolation ---

    @Test
    public void testUnqualifiedDoesNotReachQualifiedConsumer() {
        // Sending without qualifier should reach only the unqualified consumer
        String reply = requestEvent
                .request(new RequestEvent("U-ISO-1"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("Standard: U-ISO-1", reply,
                "Unqualified request should reach unqualified consumer, not @Premium");
    }

    @Test
    public void testQualifiedAlsoReachesUnqualifiedConsumer() throws InterruptedException {
        // CDI behavior: unqualified consumer matches ALL events (catch-all).
        // Send a @Premium event via publish() — both consumers should receive it.
        IsolationQualifiedConsumer.LATCH = new CountDownLatch(1);
        IsolationUnqualifiedConsumer.LATCH = new CountDownLatch(1);

        isolationEvent.select(new PremiumLiteral()).publish(new IsolationEvent("Q-ISO-1"));

        assertTrue(IsolationQualifiedConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Qualified event should reach @Premium consumer");
        assertTrue(IsolationUnqualifiedConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Qualified event should also reach unqualified consumer (CDI catch-all)");
    }

    // --- Tests: Multiple qualifiers with different ordering ---

    @Test
    public void testMultipleQualifiersWithDifferentOrder() {
        // Send with @Premium, @Express (in this order)
        // Consumer declares @Express @Premium (reverse order)
        // Address generation must sort them deterministically so they match
        MultiQualifierConsumer.LATCH = new CountDownLatch(1);

        String reply = multiQualifierEvent
                .select(PremiumLiteral.INSTANCE, ExpressLiteral.INSTANCE)
                .request(new MultiQualifierEvent("MQ-1"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("MultiQualified: MQ-1", reply);
    }

    @Test
    public void testMultipleQualifiersReversedOrder() {
        // Same as above but send with @Express, @Premium (reversed)
        // Must produce the same address
        MultiQualifierConsumer.LATCH = new CountDownLatch(1);

        String reply = multiQualifierEvent
                .select(ExpressLiteral.INSTANCE, PremiumLiteral.INSTANCE)
                .request(new MultiQualifierEvent("MQ-2"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("MultiQualified: MQ-2", reply);
    }
}

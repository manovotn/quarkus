package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.OnEvent;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;

public class EventsTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    DomainEvent.class, OrderCreated.class, OrderCancelled.class,
                    PaymentReceived.class, SendTestEvent.class, PublishTestEvent.class,
                    Consumers.class, SendConsumer.class, PublishConsumer.class, PublishConsumer2.class));

    @Inject
    QuarkusEvent<OrderCreated> orderCreatedEvent;

    @Inject
    QuarkusEvent<PaymentReceived> paymentReceivedEvent;

    @Inject
    QuarkusEvent<OrderCancelled> orderCancelledEvent;

    @Inject
    QuarkusEvent<SendTestEvent> sendTestEvent;

    @Inject
    QuarkusEvent<PublishTestEvent> publishTestEvent;

    // --- Event type hierarchy ---

    public interface DomainEvent {
    }

    public static class OrderCreated implements DomainEvent {
        public final String orderId;

        public OrderCreated(String orderId) {
            this.orderId = orderId;
        }
    }

    public static class OrderCancelled implements DomainEvent {
        public final String orderId;

        public OrderCancelled(String orderId) {
            this.orderId = orderId;
        }
    }

    public static class PaymentReceived implements DomainEvent {
        public final String paymentId;

        public PaymentReceived(String paymentId) {
            this.paymentId = paymentId;
        }
    }

    // Event types dedicated to fire-and-forget tests (no other consumers registered)
    public static class SendTestEvent {
        public final String id;

        public SendTestEvent(String id) {
            this.id = id;
        }
    }

    public static class PublishTestEvent {
        public final String id;

        public PublishTestEvent(String id) {
            this.id = id;
        }
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class Consumers {

        /**
         * Observes OrderCreated and returns a reply.
         */
        String onOrderCreated(@OnEvent OrderCreated event) {
            return "Order received: " + event.orderId;
        }

        /**
         * Observes all DomainEvent subtypes and returns a reply.
         */
        String auditAll(@OnEvent DomainEvent event) {
            return "Audited: " + event.getClass().getSimpleName();
        }

        /**
         * Observes PaymentReceived and returns a reply via Uni.
         */
        Uni<String> onPaymentReceived(@OnEvent PaymentReceived event) {
            return Uni.createFrom().item("Payment processed: " + event.paymentId);
        }
    }

    /**
     * Dedicated consumer for verifying send() delivery via a static latch.
     */
    @ApplicationScoped
    public static class SendConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        void onSendTest(@OnEvent SendTestEvent event) {
            LATCH.countDown();
        }
    }

    /**
     * First consumer for verifying publish() delivers to all consumers.
     */
    @ApplicationScoped
    public static class PublishConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        void onPublishTest(@OnEvent PublishTestEvent event) {
            LATCH.countDown();
        }
    }

    /**
     * Second consumer for verifying publish() delivers to all consumers.
     */
    @ApplicationScoped
    public static class PublishConsumer2 {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        void onPublishTest(@OnEvent PublishTestEvent event) {
            LATCH.countDown();
        }
    }

    // --- Tests ---

    @Test
    public void testRequestReplyOrderCreated() {
        // OrderCreated has multiple matching consumers (onOrderCreated + auditAll via fan-out + SendConsumer)
        // request() picks one (round-robin), so we just verify a reply comes back
        String reply = orderCreatedEvent
                .request(new OrderCreated("ORD-001"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        // Either reply-capable consumer could respond due to round-robin
        assertEquals(true,
                reply.equals("Order received: ORD-001") || reply.equals("Audited: OrderCreated"),
                "Expected reply from either onOrderCreated or auditAll, got: " + reply);
    }

    @Test
    public void testRequestReplyOrderCancelled() {
        // OrderCancelled has auditAll (via fan-out) + PublishConsumer
        String reply = orderCancelledEvent
                .request(new OrderCancelled("ORD-002"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("Audited: OrderCancelled", reply);
    }

    @Test
    public void testRequestReplyPayment() {
        // PaymentReceived has two matching consumers (onPaymentReceived + auditAll via fan-out)
        String reply = paymentReceivedEvent
                .request(new PaymentReceived("PAY-001"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals(true,
                reply.equals("Payment processed: PAY-001") || reply.equals("Audited: PaymentReceived"),
                "Expected reply from either onPaymentReceived or auditAll, got: " + reply);
    }

    @Test
    public void testSendDelivery() throws InterruptedException {
        SendConsumer.LATCH = new CountDownLatch(1);

        sendTestEvent.send(new SendTestEvent("SEND-001"));

        assertTrue(SendConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "send() should have delivered the message to the consumer");
    }

    @Test
    public void testPublishDelivery() throws InterruptedException {
        PublishConsumer.LATCH = new CountDownLatch(1);
        PublishConsumer2.LATCH = new CountDownLatch(1);

        publishTestEvent.publish(new PublishTestEvent("PUB-001"));

        assertTrue(PublishConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "publish() should have delivered the message to the first consumer");
        assertTrue(PublishConsumer2.LATCH.await(2, TimeUnit.SECONDS),
                "publish() should have delivered the message to the second consumer");
    }
}

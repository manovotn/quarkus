package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Verifies that parameterized types are correctly distinguished during matching.
 * <p>
 * With the old approach using {@code Class<?>} (which erases generics),
 * {@code Envelope<Order>} and {@code Envelope<Payment>} are both just
 * {@code Envelope.class} — indistinguishable. With CDI's {@code isMatchingEvent}
 * using full {@code Type} objects, they can be matched separately.
 */
public class ParameterizedTypeMatchingTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Envelope.class, Order.class, Payment.class,
                    OrderEnvelopeConsumer.class, PaymentEnvelopeConsumer.class));

    @Inject
    QuarkusEvent<Envelope<Order>> orderEnvelope;

    @Inject
    QuarkusEvent<Envelope<Payment>> paymentEnvelope;

    @Test
    public void testOrderEnvelopeReachesOnlyOrderConsumer() throws InterruptedException {
        OrderEnvelopeConsumer.LATCH = new CountDownLatch(1);
        PaymentEnvelopeConsumer.LATCH = new CountDownLatch(1);

        orderEnvelope.publish(new Envelope<>(new Order("ORD-1")));

        assertTrue(OrderEnvelopeConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Envelope<Order> should reach the Order consumer");
        assertFalse(PaymentEnvelopeConsumer.LATCH.await(500, TimeUnit.MILLISECONDS),
                "Envelope<Order> should NOT reach the Payment consumer");
    }

    @Test
    public void testPaymentEnvelopeReachesOnlyPaymentConsumer() throws InterruptedException {
        OrderEnvelopeConsumer.LATCH = new CountDownLatch(1);
        PaymentEnvelopeConsumer.LATCH = new CountDownLatch(1);

        paymentEnvelope.publish(new Envelope<>(new Payment("PAY-1")));

        assertTrue(PaymentEnvelopeConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Envelope<Payment> should reach the Payment consumer");
        assertFalse(OrderEnvelopeConsumer.LATCH.await(500, TimeUnit.MILLISECONDS),
                "Envelope<Payment> should NOT reach the Order consumer");
    }

    @Test
    public void testRequestReplyWithParameterizedType() {
        String reply = orderEnvelope
                .request(new Envelope<>(new Order("ORD-2")), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("Order:ORD-2", reply);
    }

    // --- Event types ---

    public static class Envelope<T> {
        private final T payload;

        public Envelope(T payload) {
            this.payload = payload;
        }

        public T payload() {
            return payload;
        }
    }

    public record Order(String id) {
    }

    public record Payment(String id) {
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class OrderEnvelopeConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onOrderEnvelope(Envelope<Order> envelope) {
            LATCH.countDown();
            return "Order:" + envelope.payload().id();
        }
    }

    @ApplicationScoped
    public static class PaymentEnvelopeConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onPaymentEnvelope(Envelope<Payment> envelope) {
            LATCH.countDown();
            return "Payment:" + envelope.payload().id();
        }
    }
}

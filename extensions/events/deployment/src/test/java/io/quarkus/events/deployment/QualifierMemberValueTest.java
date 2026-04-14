package io.quarkus.events.deployment;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Verifies that qualifier member values are used during matching.
 * <p>
 * With string-based qualifier matching, {@code @Channel("orders")} and
 * {@code @Channel("payments")} are both just "io.quarkus...Channel" — indistinguishable.
 * With CDI's {@code isMatchingEvent}, member values are compared (except @Nonbinding members).
 */
public class QualifierMemberValueTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Channel.class, ChannelLiteral.class,
                    TradeEvent.class,
                    OrdersConsumer.class, PaymentsConsumer.class));

    @Inject
    QuarkusEvent<TradeEvent> tradeEvent;

    @Test
    public void testChannelOrdersReachesOnlyOrdersConsumer() throws InterruptedException {
        OrdersConsumer.LATCH = new CountDownLatch(1);
        PaymentsConsumer.LATCH = new CountDownLatch(1);

        // Send with @Channel("orders")
        tradeEvent.select(new ChannelLiteral("orders")).send(new TradeEvent("ORD-1"));

        // Orders consumer should receive it
        assertTrue(OrdersConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "@Channel(\"orders\") consumer should receive the event");

        // Payments consumer should NOT receive it
        assertFalse(PaymentsConsumer.LATCH.await(500, TimeUnit.MILLISECONDS),
                "@Channel(\"payments\") consumer should NOT receive @Channel(\"orders\") event");
    }

    @Test
    public void testChannelPaymentsReachesOnlyPaymentsConsumer() throws InterruptedException {
        OrdersConsumer.LATCH = new CountDownLatch(1);
        PaymentsConsumer.LATCH = new CountDownLatch(1);

        // Send with @Channel("payments")
        tradeEvent.select(new ChannelLiteral("payments")).send(new TradeEvent("PAY-1"));

        // Payments consumer should receive it
        assertTrue(PaymentsConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "@Channel(\"payments\") consumer should receive the event");

        // Orders consumer should NOT receive it
        assertFalse(OrdersConsumer.LATCH.await(500, TimeUnit.MILLISECONDS),
                "@Channel(\"orders\") consumer should NOT receive @Channel(\"payments\") event");
    }

    @Test
    public void testRequestReplyRespectsChannelValue() {
        // Request with @Channel("orders") — should get reply from orders consumer
        String reply = tradeEvent.select(new ChannelLiteral("orders"))
                .request(new TradeEvent("ORD-2"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("orders:ORD-2", reply);
    }

    // --- Event type ---

    public record TradeEvent(String id) {
    }

    // --- Qualifier with member ---

    @Qualifier
    @Target({ FIELD, METHOD, PARAMETER })
    @Retention(RUNTIME)
    public @interface Channel {
        String value();
    }

    public static class ChannelLiteral extends AnnotationLiteral<Channel> implements Channel {
        private final String value;

        public ChannelLiteral(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class OrdersConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        String onOrderTrade(@OnEvent @Channel("orders") TradeEvent event) {
            LATCH.countDown();
            return "orders:" + event.id();
        }
    }

    @ApplicationScoped
    public static class PaymentsConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        String onPaymentTrade(@OnEvent @Channel("payments") TradeEvent event) {
            LATCH.countDown();
            return "payments:" + event.id();
        }
    }
}

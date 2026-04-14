package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.EventConsumerRegistration;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.mutiny.Uni;

/**
 * Verifies that programmatically registered consumers can handle request-reply interactions,
 * both with synchronous and asynchronous (Uni) handlers.
 */
public class ProgrammaticRequestReplyTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(Ping.class));

    @Inject
    QuarkusEvent<Ping> pingEvent;

    @Test
    public void testSyncReplyingConsumer() {
        EventConsumerRegistration reg = pingEvent.replyingConsumer(Ping.class,
                ping -> "pong:" + ping.value(), String.class);

        String reply = pingEvent
                .request(new Ping("hello"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("pong:hello", reply);
        reg.unregister();
    }

    @Test
    public void testAsyncReplyingConsumer() {
        EventConsumerRegistration reg = pingEvent.replyingConsumerAsync(Ping.class,
                ping -> Uni.createFrom().item("async-pong:" + ping.value()), String.class);

        String reply = pingEvent
                .request(new Ping("world"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("async-pong:world", reply);
        reg.unregister();
    }

    @Test
    public void testResponseTypeFiltering() {
        // Register a String-replying consumer and an Integer-replying consumer
        EventConsumerRegistration stringReg = pingEvent.replyingConsumer(Ping.class,
                ping -> "str:" + ping.value(), String.class);
        EventConsumerRegistration intReg = pingEvent.replyingConsumer(Ping.class,
                ping -> ping.value().length(), Integer.class);

        // Request String — should only match the String consumer
        String strReply = pingEvent
                .request(new Ping("test"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        assertEquals("str:test", strReply);

        // Request Integer — should only match the Integer consumer
        Integer intReply = pingEvent
                .request(new Ping("test"), Integer.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        assertEquals(4, intReply);

        stringReg.unregister();
        intReg.unregister();
    }

    @Test
    public void testFireAndForgetConsumerExcludedFromRequest() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        // Register a fire-and-forget consumer (no reply capability)
        EventConsumerRegistration fireAndForgetReg = pingEvent.consumer(Ping.class,
                ping -> latch.countDown());

        // Register a replying consumer
        EventConsumerRegistration replyReg = pingEvent.replyingConsumer(Ping.class,
                ping -> "reply:" + ping.value(), String.class);

        // request() should only pick the replying consumer
        String reply = pingEvent
                .request(new Ping("filtered"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        assertEquals("reply:filtered", reply);

        // publish() should reach both
        pingEvent.publish(new Ping("both"));
        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "publish() should reach the fire-and-forget consumer");

        fireAndForgetReg.unregister();
        replyReg.unregister();
    }

    // --- Event type ---

    public record Ping(String value) {
    }
}

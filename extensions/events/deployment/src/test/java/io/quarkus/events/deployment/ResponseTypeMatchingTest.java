package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

/**
 * Verifies that {@code request()} filters consumers by response type assignability.
 * Only consumers whose return type is assignable to the requested type should be selected.
 */
public class ResponseTypeMatchingTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Command.class, Consumers.class));

    @Inject
    QuarkusEvent<Command> commandEvent;

    @Test
    public void testRequestMatchesStringConsumers() {
        // Request String reply twice — round-robin should hit both String-returning consumers
        // but never the void or Integer consumers
        String reply1 = commandEvent
                .request(new Command("str"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        String reply2 = commandEvent
                .request(new Command("str"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertTrue(
                (reply1.equals("string:str") && reply2.equals("async:str"))
                        || (reply1.equals("async:str") && reply2.equals("string:str")),
                "Expected both String-returning consumers via round-robin, got: " + reply1 + " and " + reply2);
    }

    @Test
    public void testRequestMatchesIntegerConsumer() {
        // Request Integer reply — should match only the Integer-returning consumer
        Integer reply = commandEvent
                .request(new Command("num"), Integer.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals(42, reply);
    }

    @Test
    public void testOnlyMatchingResponseTypeConsumersAreSelected() {
        // Send 3 requests for String — if only 2 consumers match (stringReply, asyncStringReply),
        // the third request cycles back to the first. If void or Integer consumers leaked in,
        // we'd get a null reply or a ClassCastException.
        String reply1 = commandEvent
                .request(new Command("r1"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        String reply2 = commandEvent
                .request(new Command("r2"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        String reply3 = commandEvent
                .request(new Command("r3"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        // All three must come from String consumers
        for (String reply : new String[] { reply1, reply2, reply3 }) {
            assertTrue(reply.startsWith("string:") || reply.startsWith("async:"),
                    "Expected reply from a String consumer, got: " + reply);
        }
        // The first two should be different (round-robin), the third cycles back to the first
        assertNotEquals(reply1, reply2, "First two should hit different consumers");
        assertEquals(reply1.substring(0, reply1.indexOf(':')), reply3.substring(0, reply3.indexOf(':')),
                "Third request should cycle back to the same consumer type as the first");
    }

    @Test
    public void testPublishStillReachesAllConsumers() throws InterruptedException {
        // publish() should reach ALL consumers regardless of return type
        Consumers.VOID_LATCH = new CountDownLatch(1);
        Consumers.STRING_LATCH = new CountDownLatch(1);
        Consumers.INTEGER_LATCH = new CountDownLatch(1);
        Consumers.ASYNC_LATCH = new CountDownLatch(1);

        commandEvent.publish(new Command("all"));

        assertTrue(Consumers.VOID_LATCH.await(2, TimeUnit.SECONDS),
                "publish() should reach the void consumer");
        assertTrue(Consumers.STRING_LATCH.await(2, TimeUnit.SECONDS),
                "publish() should reach the String consumer");
        assertTrue(Consumers.INTEGER_LATCH.await(2, TimeUnit.SECONDS),
                "publish() should reach the Integer consumer");
        assertTrue(Consumers.ASYNC_LATCH.await(2, TimeUnit.SECONDS),
                "publish() should reach the async String consumer");
    }

    // --- Event type ---

    public record Command(String value) {
    }

    // --- Consumers with different return types ---

    @ApplicationScoped
    public static class Consumers {
        static volatile CountDownLatch VOID_LATCH = new CountDownLatch(1);
        static volatile CountDownLatch STRING_LATCH = new CountDownLatch(1);
        static volatile CountDownLatch INTEGER_LATCH = new CountDownLatch(1);
        static volatile CountDownLatch ASYNC_LATCH = new CountDownLatch(1);

        void fireAndForget(@OnEvent Command cmd) {
            VOID_LATCH.countDown();
        }

        String stringReply(@OnEvent Command cmd) {
            STRING_LATCH.countDown();
            return "string:" + cmd.value();
        }

        Integer integerReply(@OnEvent Command cmd) {
            INTEGER_LATCH.countDown();
            return 42;
        }

        Uni<String> asyncStringReply(@OnEvent Command cmd) {
            ASYNC_LATCH.countDown();
            return Uni.createFrom().item("async:" + cmd.value());
        }
    }
}

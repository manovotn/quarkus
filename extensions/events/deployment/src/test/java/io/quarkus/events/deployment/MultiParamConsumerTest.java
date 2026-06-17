package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.EventInfo;
import io.quarkus.events.OnEvent;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies that methods with an {@code @OnEvent}-annotated parameter can have additional
 * CDI-injected parameters beyond the event parameter and the optional {@link EventInfo}.
 */
public class MultiParamConsumerTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Task.class, TaskService.class,
                    CdiParamConsumer.class, AllParamsConsumer.class, NonZeroPositionConsumer.class));

    @Inject
    QuarkusEvent<Task> taskEvent;

    @Test
    public void testCdiInjectedParameter() throws InterruptedException {
        CdiParamConsumer.LATCH = new CountDownLatch(1);
        CdiParamConsumer.BM_WAS_NON_NULL.set(false);

        taskEvent.publish(new Task("inject-test"));

        assertTrue(CdiParamConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Consumer with CDI parameter should receive the event");
        assertTrue(CdiParamConsumer.BM_WAS_NON_NULL.get(),
                "BeanManager parameter should have been injected (non-null)");
    }

    @Test
    public void testAllThreeParamKinds() {
        // Multiple String-returning consumers exist (AllParamsConsumer, NonZeroPositionConsumer).
        // Send enough requests to hit AllParamsConsumer via round-robin.
        boolean found = false;
        for (int i = 0; i < 3; i++) {
            String reply = taskEvent
                    .withMetadata("priority", "high")
                    .request(new Task("all-params"), String.class)
                    .await().atMost(java.time.Duration.ofSeconds(2));
            if (reply.equals("all-params:high:true")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "AllParamsConsumer (event + EventInfo + CDI service) should have been reached");
    }

    @Test
    public void testEventParameterAtNonZeroPosition() {
        // The NonZeroPositionConsumer has @OnEvent on the second parameter.
        // AllParamsConsumer and CdiParamConsumer also match — use request() with
        // String response type to include all String-returning consumers.
        // NonZeroPositionConsumer returns "injected:<name>", AllParamsConsumer returns "<name>:null:true"
        // Send 3 requests to cycle through all String-returning consumers via round-robin.
        String reply1 = taskEvent.request(new Task("pos"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        String reply2 = taskEvent.request(new Task("pos"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));
        String reply3 = taskEvent.request(new Task("pos"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        // At least one reply should come from the non-zero position consumer
        boolean foundNonZero = reply1.startsWith("injected:") || reply2.startsWith("injected:")
                || reply3.startsWith("injected:");
        assertTrue(foundNonZero,
                "Expected at least one reply from NonZeroPositionConsumer, got: "
                        + reply1 + ", " + reply2 + ", " + reply3);
    }

    // --- Event type ---

    public record Task(String name) {
    }

    // --- CDI bean for injection ---

    @ApplicationScoped
    public static class TaskService {
        public boolean isAvailable() {
            return true;
        }
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class CdiParamConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);
        static final AtomicBoolean BM_WAS_NON_NULL = new AtomicBoolean(false);

        void onTask(@OnEvent Task task, BeanManager bm) {
            BM_WAS_NON_NULL.set(bm != null);
            LATCH.countDown();
        }
    }

    @ApplicationScoped
    public static class AllParamsConsumer {
        String onTaskFull(@OnEvent Task task, EventInfo info, TaskService service) {
            String priority = (String) info.getMetadata("priority");
            return task.name() + ":" + priority + ":" + service.isAvailable();
        }
    }

    /**
     * Consumer with the event parameter at a non-zero position.
     * Verifies that @OnEvent can identify the event at any position.
     */
    @ApplicationScoped
    public static class NonZeroPositionConsumer {
        String onTaskNonZero(BeanManager bm, @OnEvent Task task) {
            return (bm != null ? "injected" : "null") + ":" + task.name();
        }
    }
}

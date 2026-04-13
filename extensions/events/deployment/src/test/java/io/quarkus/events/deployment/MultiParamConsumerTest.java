package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Verifies that {@code @OnEvent} methods can have additional CDI-injected parameters
 * beyond the event (at position 0) and the optional {@link EventInfo}.
 * <p>
 * TODO: Consider whether the event parameter should be identified by a marker annotation
 * (like CDI's {@code @Observes}) rather than by convention (always position 0).
 * A marker annotation would allow the event at any position and be more explicit.
 */
public class MultiParamConsumerTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Task.class, TaskService.class,
                    CdiParamConsumer.class, AllParamsConsumer.class));

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
        String reply = taskEvent
                .withMetadata("priority", "high")
                .request(new Task("all-params"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("all-params:high:true", reply);
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

        @OnEvent
        void onTask(Task task, BeanManager bm) {
            BM_WAS_NON_NULL.set(bm != null);
            LATCH.countDown();
        }
    }

    @ApplicationScoped
    public static class AllParamsConsumer {
        @OnEvent
        String onTaskFull(Task task, EventInfo info, TaskService service) {
            String priority = (String) info.getMetadata("priority");
            return task.name() + ":" + priority + ":" + service.isAvailable();
        }
    }
}

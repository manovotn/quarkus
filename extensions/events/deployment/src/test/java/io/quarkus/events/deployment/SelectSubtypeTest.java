package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.OnEvent;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies that {@code select(Class<U> subtype, ...)} narrows the event type correctly.
 */
public class SelectSubtypeTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    BaseEvent.class, SubEventA.class, SubEventB.class,
                    SubEventAConsumer.class, SubEventBConsumer.class));

    // Inject a broad QuarkusEvent<Object> and narrow via select()
    @Inject
    QuarkusEvent<Object> broadEvent;

    // --- Event types ---

    public interface BaseEvent {
    }

    public static class SubEventA implements BaseEvent {
        public final String value;

        public SubEventA(String value) {
            this.value = value;
        }
    }

    public static class SubEventB implements BaseEvent {
        public final String value;

        public SubEventB(String value) {
            this.value = value;
        }
    }

    // --- Consumers ---

    @ApplicationScoped
    public static class SubEventAConsumer {
        String onA(@OnEvent SubEventA event) {
            return "A: " + event.value;
        }
    }

    @ApplicationScoped
    public static class SubEventBConsumer {
        String onB(@OnEvent SubEventB event) {
            return "B: " + event.value;
        }
    }

    // --- Tests ---

    @Test
    public void testSelectSubtypeA() {
        // Narrow from QuarkusEvent<Object> to QuarkusEvent<SubEventA>
        String reply = broadEvent.select(SubEventA.class)
                .request(new SubEventA("hello"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("A: hello", reply);
    }

    @Test
    public void testSelectSubtypeB() {
        // Narrow from QuarkusEvent<Object> to QuarkusEvent<SubEventB>
        String reply = broadEvent.select(SubEventB.class)
                .request(new SubEventB("world"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("B: world", reply);
    }
}

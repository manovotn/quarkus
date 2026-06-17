package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.EventConsumerRegistration;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies that programmatic consumer registration works with subtype matching.
 */
public class ProgrammaticConsumerTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Animal.class, Dog.class, Cat.class));

    @Inject
    QuarkusEvent<Object> events;

    // --- Type hierarchy ---

    public static class Animal {
        public final String name;

        public Animal(String name) {
            this.name = name;
        }
    }

    public static class Dog extends Animal {
        public Dog(String name) {
            super(name);
        }
    }

    public static class Cat extends Animal {
        public Cat(String name) {
            super(name);
        }
    }

    // --- Tests ---

    @Test
    @SuppressWarnings("unchecked")
    public void testProgrammaticConsumerReceivesExactType() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        QuarkusEvent<Dog> dogEvent = (QuarkusEvent<Dog>) (QuarkusEvent<?>) events.select(Dog.class);
        EventConsumerRegistration reg = dogEvent.consumer(Dog.class, dog -> {
            received.set(dog.name);
            latch.countDown();
        });

        events.select(Dog.class).send(new Dog("Rex"));

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "Programmatic consumer should receive exact type event");
        assertEquals("Rex", received.get());

        reg.unregister();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testProgrammaticConsumerReceivesSubtype() throws InterruptedException {
        // Register a consumer for Animal — should also receive Dog events
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        QuarkusEvent<Animal> animalEvent = (QuarkusEvent<Animal>) (QuarkusEvent<?>) events.select(Animal.class);
        EventConsumerRegistration reg = animalEvent.consumer(Animal.class, animal -> {
            received.set(animal.name);
            latch.countDown();
        });

        // Send a Dog — the Animal consumer should receive it via type closure
        events.select(Dog.class).send(new Dog("Buddy"));

        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "Programmatic Animal consumer should receive Dog event via subtype matching");
        assertEquals("Buddy", received.get());

        reg.unregister();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testProgrammaticConsumerUnregister() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        QuarkusEvent<Dog> dogEvent = (QuarkusEvent<Dog>) (QuarkusEvent<?>) events.select(Dog.class);
        EventConsumerRegistration reg = dogEvent.consumer(Dog.class, dog -> latch.countDown());

        // Unregister before sending
        reg.unregister().await().atMost(java.time.Duration.ofSeconds(2));

        events.select(Dog.class).send(new Dog("Rex"));

        // The latch should NOT count down since we unregistered
        Thread.sleep(500);
        assertEquals(1, latch.getCount(),
                "Unregistered consumer should not receive events");
    }
}

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

/**
 * Verifies that subtype fan-out works for class hierarchies (not just interfaces).
 */
public class ClassHierarchyFanOutTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Animal.class, Dog.class, Cat.class,
                    AnimalConsumer.class, DogConsumer.class));

    @Inject
    QuarkusEvent<Dog> dogEvent;

    @Inject
    QuarkusEvent<Cat> catEvent;

    // --- Class hierarchy ---

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

    // --- Consumers ---

    @ApplicationScoped
    public static class AnimalConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onAnimal(Animal animal) {
            LATCH.countDown();
            return "Animal: " + animal.name;
        }
    }

    @ApplicationScoped
    public static class DogConsumer {
        static volatile CountDownLatch LATCH = new CountDownLatch(1);

        @OnEvent
        String onDog(Dog dog) {
            LATCH.countDown();
            return "Dog: " + dog.name;
        }
    }

    // --- Tests ---

    @Test
    public void testDogReachesBothDogAndAnimalConsumers() throws InterruptedException {
        AnimalConsumer.LATCH = new CountDownLatch(1);
        DogConsumer.LATCH = new CountDownLatch(1);

        // publish() should deliver to both AnimalConsumer (via fan-out) and DogConsumer
        dogEvent.publish(new Dog("Rex"));

        assertTrue(DogConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Dog event should reach DogConsumer");
        assertTrue(AnimalConsumer.LATCH.await(2, TimeUnit.SECONDS),
                "Dog event should reach AnimalConsumer via class hierarchy fan-out");
    }

    @Test
    public void testCatReachesAnimalButNotDogConsumer() {
        // Cat should reach AnimalConsumer but not DogConsumer
        String reply = catEvent
                .request(new Cat("Whiskers"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("Animal: Whiskers", reply,
                "Cat event should reach AnimalConsumer, not DogConsumer");
    }

    @Test
    public void testDogRequestReply() {
        // request() on Dog — either DogConsumer or AnimalConsumer could reply (round-robin)
        String reply = dogEvent
                .request(new Dog("Buddy"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertTrue(reply.equals("Dog: Buddy") || reply.equals("Animal: Buddy"),
                "Expected reply from DogConsumer or AnimalConsumer, got: " + reply);
    }
}

package io.quarkus.arc.test.producer.exception;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.test.ArcTestContainer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class ExceptionThrowingProducerTest {

    @Rule
    public ArcTestContainer container = new ArcTestContainer(Foo.class, Bar.class, FaultyProducer.class);

    @Test
    public void testProducerDoesNotMaskException() {
        ArcContainer arc = Arc.container();
        // select dep. scoped bean
        try {
            arc.instance(Bar.class).get().ping();
            Assert.fail("Expected IllegalStateException which is thrown by the Bar producer method!");
        } catch (IllegalStateException e) {
            // ok
        }
        // select app. scoped bean, e.g. with proxy in use
        try {
            arc.instance(Foo.class).get().ping();
            Assert.fail("Expected IllegalStateException which is thrown by the Foo producer method!");
        } catch (IllegalStateException e) {
            // ok
        }
    }
}

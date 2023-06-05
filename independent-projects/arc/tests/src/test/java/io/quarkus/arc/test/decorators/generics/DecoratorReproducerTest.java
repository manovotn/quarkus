package io.quarkus.arc.test.decorators.generics;

import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Arc;
import io.quarkus.arc.test.ArcTestContainer;

public class DecoratorReproducerTest {

    @RegisterExtension
    public ArcTestContainer container = new ArcTestContainer(DecoratorReproducerTest.class, IfaceWithGenerics.class,
            SomeBean.class, DecoratorWithGenerics.class);

    @Test
    public void testDecoration() {
        SomeBean bean = Arc.container().select(SomeBean.class).get();
        Assertions.assertFalse(DecoratorWithGenerics.decoratorInvoked);
        bean.get();
        Assertions.assertTrue(DecoratorWithGenerics.decoratorInvoked);
    }

    interface IfaceWithGenerics<T> {
        T get();
    }

    @Priority(1)
    @Decorator
    static class DecoratorWithGenerics<T> implements IfaceWithGenerics<T> {

        public static boolean decoratorInvoked = false;
        @Inject
        @Any
        @Delegate
        IfaceWithGenerics<T> delegate;

        @Override
        public T get() {
            decoratorInvoked = true;
            return delegate.get();
        }
    }

    @ApplicationScoped
    static class SomeBean implements IfaceWithGenerics<String> {

        @Override
        public String get() {
            return "Hello";
        }
    }

    @ApplicationScoped
    static class SomeOtherBean implements IfaceWithGenerics<Integer> { // TODO not added to the deployment now

        @Override
        public Integer get() {
            return 42;
        }
    }
}

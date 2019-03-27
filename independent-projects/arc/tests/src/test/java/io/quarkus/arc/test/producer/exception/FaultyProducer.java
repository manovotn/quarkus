package io.quarkus.arc.test.producer.exception;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;

@ApplicationScoped
public class FaultyProducer {

    @Produces
    @ApplicationScoped
    public Foo produceFoo() {
        throw new IllegalStateException("Cannot produce ApplicationScoped Foo");
    }

    @Produces
    @Dependent
    public Bar produceDependentBar() {
        throw new IllegalStateException("Cannot produce Dependent Bar");
    }
}

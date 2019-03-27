package io.quarkus.arc.test.producer.exception;

import javax.enterprise.inject.Vetoed;

@Vetoed
public class Foo {

    public String ping() {
        return Foo.class.toString();
    }
}

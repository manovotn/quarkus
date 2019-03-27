package io.quarkus.arc.test.producer.exception;

import javax.enterprise.inject.Vetoed;

@Vetoed
public class Bar {

    public String ping() {
        return Bar.class.toString();
    }
}

package io.quarkus.scheduler.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.properties.UnlessBuildProperty;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

public class UnlessPropertySchedulerTest {

    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(ScheduledBean.class, SomeServiceConfig.class, Counter.class)
                    .addAsResource(new StringAsset("UnlessPropertyScheduler.property=true"),
                            "application.properties"));

    @Test
    public void testNoSchedulerInvocations() throws InterruptedException {
        assertTrue(Counter.LATCH.await(3, TimeUnit.SECONDS));
        assertEquals(0, Counter.executionCounter);
    }

    @UnlessBuildProperty(name = "someservice.enabled", stringValue = "false", enableIfMissing = true)
    @ApplicationScoped
    public static class ScheduledBean {

        @Scheduled(every = "1s")
        public void process() {
            Config config = ConfigProviderResolver.instance().getConfig();
            Optional<Boolean> optionalValue = config.getOptionalValue("someservice.enabled", Boolean.class);
            System.err.println("Optional present: " + optionalValue.isPresent());
            if (optionalValue.isPresent()) {
                System.err.println("Optional val: " + optionalValue.get());
            }
            Counter.executionCounter++;
        }
    }

    @UnlessBuildProperty(name = "UnlessPropertyScheduler.property", stringValue = "false", enableIfMissing = true)
    @ApplicationScoped
    public static class ScheduledBean2 {

        @Scheduled(every = "2s")
        public void process() {
            Counter.LATCH.countDown();
        }
    }

    @ConfigMapping(prefix = "someservice")
    @Unremovable
    public interface SomeServiceConfig {
        @WithName("enabled")
        @WithDefault("false")
        Boolean enabled();
    }

    public static class Counter {
        static final CountDownLatch LATCH = new CountDownLatch(1);

        static volatile int executionCounter = 0;
    }
}

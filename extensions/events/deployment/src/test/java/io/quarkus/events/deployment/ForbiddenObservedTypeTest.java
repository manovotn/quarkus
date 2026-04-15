package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.enterprise.context.ApplicationScoped;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.OnEvent;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies that observing overly broad types like Object produces a build-time error.
 */
public class ForbiddenObservedTypeTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(ObjectConsumer.class))
            .assertException(t -> {
                assertTrue(t.getMessage().contains("must not observe java.lang.Object"),
                        "Expected error about observing Object, got: " + t.getMessage());
            });

    @ApplicationScoped
    public static class ObjectConsumer {
        void onAny(@OnEvent Object event) {
            fail("Should not be reached — build should fail");
        }
    }

    @Test
    public void testBuildFails() {
        // The test passes if the build fails with the expected exception (handled by assertException above)
        fail("Build should have failed");
    }
}

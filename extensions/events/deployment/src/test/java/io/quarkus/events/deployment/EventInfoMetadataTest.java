package io.quarkus.events.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.events.EventInfo;
import io.quarkus.events.OnEvent;
import io.quarkus.events.QuarkusEvent;
import io.quarkus.test.QuarkusUnitTest;

/**
 * Verifies that metadata attached via {@code withMetadata()} is accessible
 * in the consumer through an {@link EventInfo} parameter.
 */
public class EventInfoMetadataTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot(root -> root.addClasses(
                    Msg.class, MetadataConsumer.class));

    @Inject
    QuarkusEvent<Msg> msgEvent;

    @Test
    public void testMetadataIsAccessible() {
        MetadataConsumer.RESULT.set(null);

        String reply = msgEvent
                .withMetadata("traceId", "abc-123")
                .withMetadata("source", "test")
                .request(new Msg("hello"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        assertEquals("hello:abc-123", reply);
    }

    @Test
    public void testMissingMetadataReturnsNull() {
        MetadataConsumer.RESULT.set(null);

        String reply = msgEvent
                .request(new Msg("plain"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        // No metadata was attached, so getMetadata("traceId") should return null
        assertEquals("plain:null", reply);
    }

    @Test
    public void testEventInfoProvidesTypeAndQualifiers() {
        MetadataConsumer.RESULT.set(null);

        msgEvent.request(new Msg("typed"), String.class)
                .await().atMost(java.time.Duration.ofSeconds(2));

        EventInfo captured = MetadataConsumer.RESULT.get();
        assertNotNull(captured, "EventInfo should have been captured");
        assertNotNull(captured.getEventType(), "Event type should be available");
        assertNotNull(captured.getQualifiers(), "Qualifiers should be available");
    }

    // --- Event type ---

    public record Msg(String text) {
    }

    // --- Consumer ---

    @ApplicationScoped
    public static class MetadataConsumer {
        static final AtomicReference<EventInfo> RESULT = new AtomicReference<>();

        @OnEvent
        String onMsg(Msg msg, EventInfo info) {
            RESULT.set(info);
            String traceId = (String) info.getMetadata("traceId");
            return msg.text() + ":" + traceId;
        }
    }
}

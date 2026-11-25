package io.github.stevdrey.realtimevoicelab.realtime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventInspectorTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void evictsOldestEntriesAndKeepsImmutableChronologicalSnapshots() {
        var now = Instant.parse("2026-01-02T03:04:05.678Z");
        var inspector = new EventInspector(Clock.fixed(now, ZoneOffset.UTC));
        for (int index = 0; index < EventInspector.CAPACITY; index++) {
            inspector.incoming("rate_limits.updated", null, null);
        }
        var original = inspector.snapshot();
        inspector.outgoing("response.cancel", null, null);
        var current = inspector.snapshot();

        assertEquals(EventInspector.CAPACITY, current.size());
        assertEquals(original.subList(1, original.size()), current.subList(0, current.size() - 1));
        assertEquals(now, current.getLast().timestamp());
        assertEquals("response.cancel", current.getLast().type());
        assertSame(current, inspector.snapshot());
        assertThrows(UnsupportedOperationException.class, current::clear);
        inspector.clear();
        assertTrue(inspector.snapshot().isEmpty());
        assertEquals(EventInspector.CAPACITY, current.size());
    }

    @Test
    void capturesOnlySafeMetadataFromBothDirectionsAfterFrameAssembly() throws Exception {
        var transport = new FakeTransport();
        var session = new RealtimeSession(transport, () -> {});
        try (session) {
            session.connect(RealtimeSession.DEFAULT_MODEL);
            var socket = transport.attempts.getFirst();
            socket.ready();
            assertEquals(List.of("session.created", "session.update", "session.updated"),
                    session.inspectedEvents().stream().map(EventInspector.Entry::type).toList());
            assertFalse(session.inspectedEvents().get(1).jsonDetail().contains("Respond briefly"));

            socket.text("""
                    {"event_id":"synthetic-private-id","type":"new.private.event",
                     "nested":{"authorization":"Bearer synthetic-secret","audio":"c2VjcmV0",
                    """, false);
            assertEquals(3, session.inspectedEvents().size());
            socket.text("\"transcript\":\"synthetic-personal-text\"}}", true);
            var unknown = session.inspectedEvents().getLast();
            assertEquals("Unknown event", unknown.type());
            assertTrue(unknown.eventId().matches("server-[0-9a-f]{24}"));
            assertEquals(RealtimeSession.State.READY, session.status().state());

            // The delta may precede the type. Its byte count is of the decoded audio.
            socket.text("""
                    {"delta":"AQIDBA==","type":"response.output_audio.delta",
                     "event_id":"synthetic-private-id","response_id":"synthetic-private-response"}
                    """, true);
            var audio = session.inspectedEvents().getLast();
            var audioJson = json.readTree(audio.jsonDetail());
            assertEquals(4, audioJson.path("audio_bytes").intValue());
            assertEquals("incoming", audioJson.path("direction").textValue());
            assertEquals(unknown.eventId(), audioJson.path("event_id").textValue());
            assertEquals(5, audioJson.size());

            assertTrue(session.sendText("synthetic-user-message"));
            var item = session.inspectedEvents().getLast();
            assertEquals(EventInspector.Direction.OUTGOING, item.direction());
            assertEquals("conversation.item.create", item.type());
            assertEquals(json.readTree(socket.writes.getLast()).path("event_id").textValue(), item.eventId());
            assertEquals(4, json.readTree(item.jsonDetail()).size());

            var allDetails = session.inspectedEvents().stream().map(EventInspector.Entry::jsonDetail).toList().toString();
            for (var suppressed : List.of("new.private.event", "synthetic-private-id", "synthetic-private-response",
                    "synthetic-secret", "c2VjcmV0", "AQIDBA==", "synthetic-personal-text", "synthetic-user-message")) {
                assertFalse(allDetails.contains(suppressed));
            }
        }
        assertTrue(session.inspectedEvents().isEmpty());
        transport.attempts.getFirst().text("{\"type\":\"rate_limits.updated\"}", true);
        assertTrue(session.inspectedEvents().isEmpty());
    }
}

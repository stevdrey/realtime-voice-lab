package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.DEFAULT_MODEL;
import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.Failure;
import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.State;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeSafetyTest {
    private final FakeTransport transport = new FakeTransport();
    private final FakeClock clock = new FakeClock();
    private final RealtimeSession session = new RealtimeSession(transport, () -> {},
            Duration.ofSeconds(15), Duration.ofSeconds(3), clock::nanoTime, clock::schedule);

    @AfterEach
    void cleanup() {
        session.close();
        assertEquals(0, clock.pendingTimers());
    }

    @Test
    void expiresFromConnectEvenWithoutUsageOrFurtherEvents() {
        var socket = connect();
        clock.advance(Duration.ofSeconds(10));
        socket.ready();
        responseDone(socket, "response_1");

        clock.advance(Duration.ofSeconds(290).minusNanos(1));
        assertEquals(State.READY, session.status().state());
        clock.advance(Duration.ofNanos(1));

        assertEquals(State.CLOSING, session.status().state());
        assertEquals("{\"type\":\"response.cancel\"}", socket.writes.getLast());
        assertEquals(1, socket.closeWrites);
        session.connect(DEFAULT_MODEL);
        assertEquals(1, transport.attempts.size());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertLimit(Failure.SESSION_LIMIT);
        assertFalse(socket.aborted);
        assertEquals(0, clock.pendingTimers());

        clock.advance(Duration.ofMinutes(5));
        assertEquals(1, transport.attempts.size());
        connect().ready();
        assertEquals(State.READY, session.status().state());
    }

    @Test
    void enforcesElapsedTimeBeforeProcessingAnEventWhenTimerDeliveryIsLate() {
        var socket = connect();
        socket.ready();
        clock.elapse(Duration.ofMinutes(5));
        functionCall(socket);

        assertEquals(State.CLOSING, session.status().state());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertLimit(Failure.SESSION_LIMIT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void manualCloseKeepsItsOwnDeadlineAcrossSessionExpiry(boolean peerReplies) {
        var socket = connect();
        socket.ready();
        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        session.disconnect();
        assertEquals(1, socket.closeWrites);

        clock.advance(Duration.ofSeconds(1));
        assertEquals(new RealtimeSession.Status(State.CLOSING, null), session.status());
        assertFalse(socket.aborted);

        if (peerReplies) {
            socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        } else {
            clock.advance(Duration.ofSeconds(2));
        }
        assertEquals(new RealtimeSession.Status(State.DISCONNECTED, null), session.status());
        assertEquals(!peerReplies, socket.aborted);
        assertEquals(0, clock.pendingTimers());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortsWhenCancellationOrPeerCloseStalls(boolean stallCancelWrite) {
        var socket = connect();
        socket.ready();
        if (stallCancelWrite) {
            socket.cancelWrite = new CompletableFuture<>();
        }
        clock.advance(Duration.ofMinutes(5));
        assertEquals(stallCancelWrite ? 0 : 1, socket.closeWrites);

        clock.advance(Duration.ofSeconds(3));
        assertTrue(socket.aborted);
        assertLimit(Failure.SESSION_LIMIT);
        assertEquals(0, clock.pendingTimers());
        assertEquals(1, transport.attempts.size());

        var retry = connect();
        socket.cancelWrite.complete(socket);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        socket.fail(new IOException("late synthetic error"));
        retry.ready();
        assertEquals(State.READY, session.status().state());
        assertEquals(stallCancelWrite ? 0 : 1, socket.closeWrites);
    }

    @Test
    void checksTheToolCapAcrossResponsesAndResetsOnlyOnReconnect() {
        var socket = connect();
        socket.ready();
        for (int i = 0; i < 10; i++) {
            functionCall(socket);
            responseDone(socket, "response_" + i);
            assertEquals(State.READY, session.status().state());
        }

        functionCall(socket);
        assertEquals("{\"type\":\"response.cancel\"}", socket.writes.getLast());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertLimit(Failure.TOOL_LIMIT);

        var retry = connect();
        retry.ready();
        functionCall(retry);
        assertEquals(State.READY, session.status().state());
    }

    @Test
    void allowsThirtySecondsOfPcmPerResponseAndStopsOnExcess() {
        var socket = connect();
        socket.ready();
        var secondOfPcm = Base64.getEncoder().encodeToString(new byte[48_000]);
        for (var response : new String[] {"response_1", "response_2"}) {
            var delta = audioDelta(response, secondOfPcm);
            for (int i = 0; i < 30; i++) {
                socket.text(delta, true);
            }
            assertEquals(State.READY, session.status().state());
            if (response.equals("response_1")) {
                responseDone(socket, response);
            }
        }

        responseDone(socket, "unrelated_response");
        var extraSample = audioDelta("response_2", "AAA=");
        socket.text(extraSample.substring(0, 20), false);
        socket.text(extraSample.substring(20), true);
        assertEquals("{\"type\":\"response.cancel\"}", socket.writes.getLast());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertLimit(Failure.AUDIO_LIMIT);
    }

    @Test
    void failsClosedOnMalformedAudio() {
        var socket = connect();
        socket.ready();
        socket.text(audioDelta("response_1", "%%%="), true);
        assertLimit(Failure.PROTOCOL);
        assertTrue(socket.aborted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"max_output_tokens\":\"inf\"", ",\"max_output_tokens\":513"})
    void refusesReadinessWithoutAnEffectiveOutputCap(String cap) {
        var socket = acknowledgeConfiguration("""
                ,"audio":{"output":{"format":{"type":"audio/pcm","rate":24000}}}%s
                """.formatted(cap));
        assertLimit(Failure.PROTOCOL);
        assertTrue(socket.aborted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"type\":\"audio/pcmu\"}", "{\"type\":\"audio/pcma\"}",
            "{\"type\":\"audio/pcm\",\"rate\":16000}", "{\"type\":\"audio/pcm\",\"rate\":\"24000\"}"})
    void refusesReadinessWithoutSupportedPcmOutput(String format) {
        var socket = acknowledgeConfiguration("""
                ,"max_output_tokens":512,"audio":{"output":{"format":%s}}
                """.formatted(format));
        assertLimit(Failure.PROTOCOL);
        assertTrue(socket.aborted);
    }

    @Test
    void refusesReadinessWhenTheOutputFormatIsMissing() {
        var socket = acknowledgeConfiguration(",\"max_output_tokens\":512");
        assertLimit(Failure.PROTOCOL);
        assertTrue(socket.aborted);
    }

    @Test
    void acceptsPcmWithTheImplicitGaSampleRate() {
        acknowledgeConfiguration("""
                ,"max_output_tokens":512,"audio":{"output":{"format":{"type":"audio/pcm"}}}
                """);
        assertEquals(new RealtimeSession.Status(State.READY, null), session.status());
    }

    @Test
    void closesWhenTheProviderEndsAResponseAtItsOutputCapWithoutUsage() {
        var socket = connect();
        socket.ready();
        socket.text("""
                {"response":{"id":"response_1","status":"incomplete",
                 "status_details":{"type":"incomplete","reason":"max_output_tokens"}},
                 "type":"response.done"}
                """, true);
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(1, socket.writes.size());
        assertEquals(1, socket.closeWrites);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertLimit(Failure.OUTPUT_LIMIT);
    }

    private FakeTransport.Socket connect() {
        session.connect(DEFAULT_MODEL);
        return transport.attempts.getLast();
    }

    private FakeTransport.Socket acknowledgeConfiguration(String fields) {
        var socket = connect();
        socket.opened();
        socket.created();
        socket.textWrite.complete(socket);
        socket.text("""
                {"type":"session.updated","session":{"type":"realtime","id":"test-session"%s}}
                """.formatted(fields), true);
        return socket;
    }

    private void assertLimit(Failure expected) {
        assertEquals(new RealtimeSession.Status(State.ERROR, expected), session.status());
    }

    private void responseDone(FakeTransport.Socket socket, String id) {
        socket.text("""
                {"type":"response.done","response":{"id":"%s","status":"completed"}}
                """.formatted(id), true);
    }

    private void functionCall(FakeTransport.Socket socket) {
        socket.text("""
                {"type":"response.function_call_arguments.done","call_id":"call_fixture","arguments":"{}"}
                """, true);
    }

    private String audioDelta(String responseId, String base64) {
        return """
                {"delta":"%s","response_id":"%s","type":"response.output_audio.delta"}
                """.formatted(base64, responseId);
    }

    private static final class FakeClock {
        // Start close to wraparound so elapsed-time checks cannot compare absolute nanoTime values.
        private long now = Long.MAX_VALUE - Duration.ofSeconds(1).toNanos();
        private final ArrayList<Alarm> alarms = new ArrayList<>();

        long nanoTime() {
            return now;
        }

        CompletableFuture<Void> schedule(long delay) {
            var result = new CompletableFuture<Void>();
            alarms.add(new Alarm(now + delay, result));
            return result;
        }

        void elapse(Duration time) {
            now += time.toNanos();
        }

        void advance(Duration time) {
            elapse(time);
            for (var alarm : new ArrayList<>(alarms)) {
                if (now - alarm.deadline() >= 0 && !alarm.result().isDone()) {
                    alarm.result().completeExceptionally(new TimeoutException());
                }
            }
            alarms.removeIf(alarm -> alarm.result().isDone());
        }

        long pendingTimers() {
            return alarms.stream().filter(alarm -> !alarm.result().isDone()).count();
        }

        private record Alarm(long deadline, CompletableFuture<Void> result) {
        }
    }
}

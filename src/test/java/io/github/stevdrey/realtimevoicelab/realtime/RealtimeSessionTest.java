package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class RealtimeSessionTest {
    private final FakeTransport transport = new FakeTransport();
    private final RealtimeSession session = new RealtimeSession(transport, () -> {});

    @AfterEach
    void cleanup() {
        session.close();
    }

    @Test
    void waitsForCompleteMatchingAcknowledgementAndSuccessfulWrite() throws Exception {
        assertEquals(State.DISCONNECTED, session.status().state());
        assertTrue(transport.attempts.isEmpty());

        var socket = connect();
        session.connect(DEFAULT_MODEL);
        assertEquals(1, transport.attempts.size());
        assertEquals(DEFAULT_MODEL, socket.model);
        socket.opened();
        assertEquals(1, socket.demand);
        assertTrue(socket.writes.isEmpty());

        socket.updated();
        assertEquals(State.CONNECTING, session.status().state());
        socket.text("{\"type\":\"session.cre", false);
        assertTrue(socket.writes.isEmpty());
        socket.text("ated\",\"session\":{\"type\":\"realtime\",\"id\":\"test-session\"}}", true);
        socket.created();
        assertEquals(5, socket.demand);
        assertEquals(1, socket.writes.size());

        var config = new ObjectMapper().readTree(socket.writes.getFirst());
        assertEquals("session.update", config.path("type").asText());
        assertEquals("realtime", config.at("/session/type").asText());
        assertEquals("audio", config.at("/session/output_modalities/0").asText());
        assertEquals(512, config.at("/session/max_output_tokens").asInt());
        assertTrue(config.at("/session/audio/input/turn_detection").isNull());
        assertEquals(24000, config.at("/session/audio/input/format/rate").asInt());
        assertEquals("audio/pcm", config.at("/session/audio/output/format/type").asText());

        socket.text("""
                {"type":"session.updated","session":{"type":"realtime","id":"other-session"}}
                """, true);
        assertEquals(State.CONNECTING, session.status().state());
        socket.text("{\"type\":\"session.updated\",", false);
        assertEquals(State.CONNECTING, session.status().state());
        socket.text("""
                "session":{"type":"realtime","id":"test-session","max_output_tokens":512,
                 "model":"gpt-realtime-2.1-mini","instructions":"Respond briefly.","output_modalities":["audio"],
                 "audio":{"output":{"voice":"marin","format":{"type":"audio/pcm","rate":24000}}}}}
                """, true);
        assertEquals(State.CONNECTING, session.status().state());
        socket.textWrite.complete(socket);
        assertEquals(State.READY, session.status().state());

        socket.text("{\"type\":\"future.event\"}", true);
        assertEquals(State.READY, session.status().state());
        session.disconnect();
        session.disconnect();
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(1, socket.closeWrites);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(State.DISCONNECTED, session.status().state());
        assertFalse(socket.aborted);
    }

    @Test
    void cancelsHandshakeAndIgnoresLateCallbacksAfterReconnect() {
        var cancelled = connect();
        session.disconnect();
        assertTrue(cancelled.opening.isCancelled());
        assertEquals(State.DISCONNECTED, session.status().state());

        var current = connect();
        cancelled.opened();
        cancelled.created();
        cancelled.fail(new IOException("private synthetic detail"));
        assertTrue(cancelled.aborted);
        assertTrue(cancelled.writes.isEmpty());
        assertEquals(State.CONNECTING, session.status().state());

        current.ready();
        assertEquals(State.READY, session.status().state());
        session.close();
        assertTrue(current.aborted);
        assertTrue(transport.closed);
        session.connect(DEFAULT_MODEL);
        assertEquals(2, transport.attempts.size());
    }

    @Test
    void abortsRatherThanOverlappingCloseWithAnUnfinishedConfigurationWrite() {
        var socket = connect();
        socket.opened();
        socket.created();
        session.disconnect();
        assertEquals(State.DISCONNECTED, session.status().state());
        assertTrue(socket.aborted);
        assertEquals(0, socket.closeWrites);
        socket.textWrite.complete(socket);
        socket.updated();
        assertEquals(State.DISCONNECTED, session.status().state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{} {}", "{\"type\":{}}", "{\"type\":\"error\",\"type\":\"ignored\"}",
            "{\"type\":\"session.updated\",\"session\":{\"type\":\"realtime\"}}"})
    void rejectsInvalidEvents(String message) {
        var socket = connect();
        socket.opened();
        socket.text(message, true);
        assertFailure(Failure.PROTOCOL, socket);
    }

    @Test
    void boundsCombinedFragmentsAndRejectsBinaryFrames() {
        var socket = connect();
        socket.opened();
        socket.text(" ".repeat(SessionProtocol.MAX_MESSAGE_CHARS), false);
        assertEquals(State.CONNECTING, session.status().state());
        socket.text(" ", true);
        assertFailure(Failure.PROTOCOL, socket);

        var retry = connect();
        retry.opened();
        retry.binary();
        assertFailure(Failure.PROTOCOL, retry);
    }

    @Test
    void mapsProviderAndWriteErrorsToSafeFailures() {
        var provider = connect();
        provider.opened();
        provider.text("""
                {"type":"error","error":{"message":"private synthetic detail"}}
                """, true);
        assertFailure(Failure.PROVIDER, provider);

        var write = connect();
        write.opened();
        write.created();
        write.textWrite.completeExceptionally(new IOException("private synthetic detail"));
        assertFailure(Failure.CONNECTION, write);
    }

    @Test
    void handlesServerInitiatedAndAbnormalClose() {
        var normal = connect();
        normal.ready();
        normal.peerClose(WebSocket.NORMAL_CLOSURE, "private synthetic detail");
        assertEquals(State.DISCONNECTED, session.status().state());

        var abnormal = connect();
        abnormal.ready();
        abnormal.peerClose(1011, "private synthetic detail");
        assertEquals(new RealtimeSession.Status(State.ERROR, Failure.REMOTE_CLOSE), session.status());
    }

    @ParameterizedTest
    @ValueSource(ints = {1001, 1005})
    void operatorDisconnectEndsCleanlyForAnyPeerCloseCode(int statusCode) {
        var socket = connect();
        socket.ready();
        session.disconnect();
        socket.peerClose(statusCode, "");
        assertEquals(new RealtimeSession.Status(State.DISCONNECTED, null), session.status());
        assertFalse(socket.aborted);
    }

    @Test
    void ignoresProviderErrorOnceReady() {
        var socket = connect();
        socket.ready();
        socket.text("""
                {"type":"error","error":{"message":"private synthetic detail"}}
                """, true);
        assertEquals(new RealtimeSession.Status(State.READY, null), session.status());
        assertFalse(socket.aborted);
    }

    @Test
    void waitsForCloseWriteWhenThePeerClosesFirst() {
        var socket = connect();
        socket.ready();
        socket.closeWrite = new CompletableFuture<>();
        session.disconnect();
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(State.CLOSING, session.status().state());
        session.connect(DEFAULT_MODEL);
        assertEquals(1, transport.attempts.size());
        socket.closeWrite.complete(socket);
        assertEquals(State.DISCONNECTED, session.status().state());
        assertFalse(socket.aborted);
    }

    @Test
    void cancelsAnExpiredHandshake() throws InterruptedException {
        var expired = new CountDownLatch(2);
        try (var timed = new RealtimeSession(transport, expired::countDown,
                Duration.ofMillis(100), Duration.ofSeconds(3))) {
            timed.connect(DEFAULT_MODEL);
            assertTrue(expired.await(5, TimeUnit.SECONDS));
            assertEquals(new RealtimeSession.Status(State.ERROR, Failure.TIMEOUT), timed.status());
            assertTrue(transport.attempts.getFirst().opening.isCancelled());
        }
    }

    @Test
    void abortsWhenPeerDoesNotAcknowledgeClose() throws InterruptedException {
        var expired = new CountDownLatch(4);
        try (var timed = new RealtimeSession(transport, expired::countDown,
                Duration.ofSeconds(15), Duration.ofMillis(100))) {
            timed.connect(DEFAULT_MODEL);
            var socket = transport.attempts.getFirst();
            socket.ready();
            timed.disconnect();
            assertTrue(expired.await(5, TimeUnit.SECONDS));
            assertEquals(new RealtimeSession.Status(State.DISCONNECTED, null), timed.status());
            assertTrue(socket.aborted);
        }
    }

    private FakeTransport.Socket connect() {
        session.connect(DEFAULT_MODEL);
        return transport.attempts.getLast();
    }

    private void assertFailure(Failure failure, FakeTransport.Socket socket) {
        assertEquals(new RealtimeSession.Status(State.ERROR, failure), session.status());
        assertTrue(socket.aborted);
    }
}

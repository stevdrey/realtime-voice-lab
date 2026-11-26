package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stevdrey.realtimevoicelab.audio.FakeMicrophone;
import io.github.stevdrey.realtimevoicelab.audio.FakeSpeaker;
import io.github.stevdrey.realtimevoicelab.realtime.FakeTransport.Socket;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.TurnDetection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeBargeInTest {
    private final FakeTransport transport = new FakeTransport();
    private final FakeMicrophone microphone = new FakeMicrophone();
    private final FakeSpeaker speaker = new FakeSpeaker();
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(value = TurnDetection.class, names = {"SERVER_VAD", "SEMANTIC_VAD"})
    void stopsGenerationOnceAndWaitsForPlayedDurationToBeAcknowledged(TurnDetection detection) throws Exception {
        try (var session = session()) {
            var socket = listen(session, detection);
            utterance(socket, "input-1");
            assertEquals(1, count(socket, "response.create"));
            bindResponse(socket, "reply-1");
            audio(socket, "reply-1", "assistant-1");
            await(() -> !speaker.devices.isEmpty()
                    && speaker.devices.getFirst().written().length == FakeSpeaker.BUFFER_BYTES);
            var device = speaker.devices.getFirst();
            device.playFrames(601); // 25.041 ms of 40 ms written to Java Sound.
            audio(socket, "reply-1", "assistant-1"); // Some PCM is still in the application queue.
            transcript(socket, "reply-1", "Generated audio transcript");

            speech(socket, "speech_started", "input-2");
            speech(socket, "speech_started", "input-2");
            session.cancelTurn();
            assertEquals(1, count(socket, "response.cancel"));
            assertEquals("reply-1", last(socket).path("response_id").asText());
            assertEquals("", session.workspace().turns().getFirst().output());
            audio(socket, "reply-1", "assistant-1");
            transcript(socket, "reply-1", "Stale transcript");
            speech(socket, "speech_stopped", "input-2");
            speech(socket, "committed", "input-2");
            assertEquals(1, count(socket, "response.create"));
            // A second assistant item was generated before cancel took effect and had no admitted PCM.
            done(socket, "reply-1", "cancelled",
                    assistant("assistant-1") + "," + assistant("assistant-unsounded"));
            await(() -> count(socket, "conversation.item.truncate") == 1);
            assertTrue(device.closed && device.flushed);
            assertCut(last(socket), "assistant-1", 25);
            assertEquals("", session.workspace().turns().getFirst().output());

            socket.truncated("unrelated", 25);
            socket.truncated("assistant-1", 40);
            assertEquals(1, count(socket, "response.create"));
            socket.truncated("assistant-1", 25);
            assertCut(last(socket), "assistant-unsounded", 0);
            socket.truncated("assistant-unsounded", 0);
            assertEquals(2, count(socket, "response.create"));
            assertEquals(1, count(socket, "response.cancel"));
            assertEquals(0, count(socket, "input_audio_buffer.commit"));

            bindResponse(socket, "reply-2");
            speech(socket, "speech_started", "input-2"); // Replay must not cut the next reply.
            transcript(socket, "reply-1", "Still stale");
            audio(socket, "reply-2", "assistant-2");
            await(() -> speaker.devices.size() == 2 && speaker.devices.getLast().written().length > 0);
            assertEquals(1, count(socket, "response.cancel"));
            assertEquals("", session.workspace().turns().getLast().output());
        }
    }

    @Test
    void interruptsQueuedPlaybackAfterDoneWithoutCancellingAndTruncatesAtZeroBeforeDeviceOpen() throws Exception {
        var opening = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(() -> {
            opening.complete(null);
            release.join();
        });
        try (var session = new RealtimeSession(transport, () -> {}, microphone.microphone(), delayedSpeaker.playback())) {
            var socket = listen(session, TurnDetection.SERVER_VAD);
            utterance(socket, "input-1");
            bindResponse(socket, "reply-1");
            audio(socket, "reply-1", "assistant-1");
            await(opening::isDone);
            done(socket, "reply-1", "completed", assistant("assistant-1"));
            utterance(socket, "input-2");
            assertEquals(0, count(socket, "response.cancel"));
            assertEquals(1, count(socket, "response.create"));

            release.complete(null);
            await(() -> count(socket, "conversation.item.truncate") == 1);
            assertCut(last(socket), "assistant-1", 0);
            assertEquals(0, delayedSpeaker.devices.getFirst().written().length);
            assertEquals(RealtimeSession.TurnState.INTERRUPTED, session.workspace().turns().getFirst().state());
            socket.truncated("assistant-1", 0);
            assertEquals(2, count(socket, "response.create"));
            assertEquals(0, count(socket, "response.cancel"));
        } finally {
            release.complete(null);
        }
    }

    @Test
    void clearsUncommittedVadAudioAndCanReturnToManualPushToTalk() throws Exception {
        try (var session = session()) {
            var socket = listen(session, TurnDetection.SEMANTIC_VAD);
            speech(socket, "speech_started", "partial");
            assertTrue(session.stopListening());
            assertEquals(1, count(socket, "input_audio_buffer.clear"));
            assertFalse(session.startAudio());
            socket.text("{\"type\":\"input_audio_buffer.cleared\"}", true);
            await(() -> session.workspace().microphone().canStart());
            assertEquals(1, microphone.closes.get());
            assertEquals(0, count(socket, "response.create"));

            assertTrue(session.configure(SessionSettings.DEFAULT, RealtimeSession.State.READY));
            assertTrue(last(socket).at("/session/audio/input/turn_detection").isNull());
            socket.updated();
            assertTrue(session.startAudio());
            microphone.queueBlocks(3);
            await(() -> session.workspace().microphone().seconds() >= 0.12);
            assertEquals(1, count(socket, "input_audio_buffer.append"));
            session.releaseAudio();
            await(() -> count(socket, "input_audio_buffer.commit") == 1);
            speech(socket, "committed", "manual-input");
            assertEquals(1, count(socket, "response.create"));
            assertEquals("audio", last(socket).at("/response/output_modalities/0").asText());
        }
    }

    @Test
    void abortsOnTruncateRejectionBeforeAQueuedTurnCanUseUnheardAudio() throws Exception {
        try (var session = session()) {
            var socket = listen(session, TurnDetection.SERVER_VAD);
            utterance(socket, "input-1");
            bindResponse(socket, "reply-1");
            utterance(socket, "input-2");
            // The response had generated audio even though no delta reached the client.
            done(socket, "reply-1", "cancelled", assistant("assistant-1"));
            var cut = last(socket);
            assertCut(cut, "assistant-1", 0);
            socket.text("""
                    {"type":"error","error":{"event_id":"%s","code":"invalid_request_error"}}
                    """.formatted(cut.path("event_id").asText()), true);
            assertTrue(socket.aborted);
            assertEquals(RealtimeSession.Failure.PROVIDER, session.status().failure());
            assertEquals(1, count(socket, "response.create"));
        }
    }

    @Test
    void serializesCancellationBehindAPendingAppendAndSkipsItIfGenerationEndsFirst() throws Exception {
        try (var session = session()) {
            var socket = listen(session, TurnDetection.SEMANTIC_VAD);
            utterance(socket, "input-1");
            bindResponse(socket, "reply-1");
            var append = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = append;
            microphone.queueBlocks(1);
            await(() -> count(socket, "input_audio_buffer.append") == 2);
            utterance(socket, "input-2");
            assertEquals(0, count(socket, "response.cancel"));

            done(socket, "reply-1", "completed", assistant("assistant-1"));
            utterance(socket, "input-3");
            assertEquals(0, count(socket, "conversation.item.truncate"));
            append.complete(socket);
            assertCut(last(socket), "assistant-1", 0);
            assertEquals(0, count(socket, "response.cancel"));
            socket.truncated("assistant-1", 0);

            assertEquals(2, count(socket, "response.create"));
            var turns = session.workspace().turns();
            assertEquals(RealtimeSession.TurnState.INTERRUPTED, turns.get(1).state());
            assertEquals("rvl-" + turns.getLast().number(), last(socket).at("/response/metadata/rvl_turn").asText());
        }
    }

    @Test
    void abortsOnMissingTruncateAcknowledgmentBeforeCreatingAnotherResponse() throws Exception {
        var cancelDeadlines = new CopyOnWriteArrayList<CompletableFuture<Void>>();
        try (var session = new RealtimeSession(transport, () -> {}, Duration.ofSeconds(15), Duration.ofSeconds(3),
                System::nanoTime, nanos -> {
                    var deadline = new CompletableFuture<Void>();
                    if (nanos == RealtimeSession.CANCEL_TIMEOUT.toNanos()) {
                        cancelDeadlines.add(deadline);
                    }
                    return deadline;
                }, microphone.microphone(), speaker.playback())) {
            var socket = listen(session, TurnDetection.SERVER_VAD);
            utterance(socket, "input-1");
            bindResponse(socket, "reply-1");
            utterance(socket, "input-2");
            done(socket, "reply-1", "cancelled", assistant("assistant-1"));
            assertCut(last(socket), "assistant-1", 0);

            cancelDeadlines.getLast().completeExceptionally(new TimeoutException());
            assertEquals(RealtimeSession.Failure.CANCEL_TIMEOUT, session.status().failure());
            assertTrue(socket.aborted);
            assertEquals(1, count(socket, "response.create"));
        }
    }

    @Test
    void doesNotCancelTwiceWhenDisconnectRacesWithAnInterruptedReply() throws Exception {
        try (var session = session()) {
            var socket = listen(session, TurnDetection.SERVER_VAD);
            utterance(socket, "input-1");
            bindResponse(socket, "reply-1");
            speech(socket, "speech_started", "input-2");
            session.disconnect();
            assertEquals(1, count(socket, "response.cancel"));
            assertEquals(1, socket.closeWrites);
            assertEquals(0, count(socket, "conversation.item.truncate"));
        }
    }

    @Test
    void doesNotOpenTheMicrophoneWhenProviderHasNotConfirmedClientOwnedInterruption() throws Exception {
        try (var session = session()) {
            session.configure(settings(TurnDetection.SERVER_VAD), RealtimeSession.State.DISCONNECTED);
            session.connectSelected(TranscriptionModel.OFF);
            var socket = transport.attempts.getFirst();
            socket.opened();
            socket.created();
            socket.textWrite.complete(socket);
            socket.updated();
            assertEquals(RealtimeSession.Failure.SETTINGS_MISMATCH, session.status().failure());
            assertFalse(session.startAudio());
            assertEquals(0, microphone.opens.get());
        }
    }

    private RealtimeSession session() {
        return new RealtimeSession(transport, () -> {}, microphone.microphone(), speaker.playback());
    }

    private SessionSettings settings(TurnDetection detection) {
        var defaults = SessionSettings.DEFAULT;
        return new SessionSettings(defaults.model(), defaults.voice(), defaults.mode(), defaults.instructions(), detection);
    }

    private Socket listen(RealtimeSession session, TurnDetection detection) throws Exception {
        var settings = settings(detection);
        assertTrue(session.configure(settings, RealtimeSession.State.DISCONNECTED));
        session.connectSelected(TranscriptionModel.OFF);
        var socket = transport.attempts.getFirst();
        socket.opened();
        socket.created();
        socket.textWrite.complete(socket);
        var vad = last(socket).at("/session/audio/input/turn_detection");
        assertEquals(detection.type(), vad.path("type").asText());
        assertFalse(vad.path("create_response").asBoolean(true));
        assertFalse(vad.path("interrupt_response").asBoolean(true));
        socket.updated(settings, null);
        assertEquals(0, microphone.opens.get());
        assertTrue(session.startAudio());
        microphone.queueBlocks(1);
        await(() -> count(socket, "input_audio_buffer.append") == 1);
        assertEquals(0, count(socket, "response.create"));
        return socket;
    }

    private void utterance(Socket socket, String itemId) {
        speech(socket, "speech_started", itemId);
        speech(socket, "speech_stopped", itemId);
        speech(socket, "committed", itemId);
    }

    private void speech(Socket socket, String type, String itemId) {
        socket.text("""
                {"type":"input_audio_buffer.%s","item_id":"%s"}
                """.formatted(type, itemId), true);
    }

    private void bindResponse(Socket socket, String responseId) throws Exception {
        var metadata = last(socket).at("/response/metadata");
        socket.text("""
                {"type":"response.created","response":{"id":"%s","metadata":%s}}
                """.formatted(responseId, metadata), true);
    }

    private void audio(Socket socket, String responseId, String itemId) {
        var pcm = Base64.getEncoder().encodeToString(microphone.block(1));
        socket.text("""
                {"type":"response.output_audio.delta","response_id":"%s","item_id":"%s",
                "output_index":0,"content_index":0,"delta":"%s"}
                """.formatted(responseId, itemId, pcm), true);
    }

    private void transcript(Socket socket, String responseId, String text) {
        socket.text("""
                {"type":"response.output_audio_transcript.delta","response_id":"%s","item_id":"assistant-1",
                "output_index":0,"content_index":0,"delta":"%s"}
                """.formatted(responseId, text), true);
    }

    private String assistant(String itemId) {
        return """
                {"id":"%s","type":"message","role":"assistant","content":[{"type":"output_audio","transcript":"Unheard"}]}
                """.formatted(itemId);
    }

    private void done(Socket socket, String responseId, String status, String output) {
        socket.text("""
                {"type":"response.done","response":{"id":"%s","status":"%s","output":[%s]}}
                """.formatted(responseId, status, output), true);
    }

    private void assertCut(JsonNode event, String itemId, int milliseconds) {
        assertEquals("conversation.item.truncate", event.path("type").asText());
        assertEquals(itemId, event.path("item_id").asText());
        assertEquals(0, event.path("content_index").asInt(-1));
        assertEquals(milliseconds, event.path("audio_end_ms").asInt(-1));
    }

    private JsonNode last(Socket socket) throws Exception {
        return json.readTree(socket.writes.getLast());
    }

    private long count(Socket socket, String type) {
        var prefix = "{\"type\":\"" + type + "\"";
        return socket.writes.stream().filter(write -> write.startsWith(prefix)).count();
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Expected synthetic state did not arrive");
    }
}

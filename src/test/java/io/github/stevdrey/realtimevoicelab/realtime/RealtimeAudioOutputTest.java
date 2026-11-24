package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.LineUnavailableException;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stevdrey.realtimevoicelab.audio.FakeMicrophone;
import io.github.stevdrey.realtimevoicelab.audio.FakeSpeaker;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import org.junit.jupiter.api.Test;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.InputTranscriptState.*;
import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.SpeakerState.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeAudioOutputTest {
    private final FakeTransport transport = new FakeTransport();
    private final FakeMicrophone microphone = new FakeMicrophone();
    private final FakeSpeaker speaker = new FakeSpeaker();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void playsFragmentedPcmProgressivelyInOrderAndFinishesAfterDeviceDrains() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            assertTrue(json.readTree(socket.writes.getFirst()).at("/session/audio/input/transcription").isNull());
            startVoice(session, socket, "input-1", "response-1");
            inputTranscript(socket, "completed", "input-1", ",\"transcript\":\"unsolicited\"");
            assertEquals(OFF, session.workspace().turns().getFirst().inputTranscript());
            assertEquals("Voice input", session.workspace().turns().getFirst().input());
            var first = microphone.block(0);
            var second = microphone.block(1);
            socket.text(audio("unrelated", second), true);
            assertTrue(speaker.devices.isEmpty());

            var event = audio("response-1", first);
            socket.text(event.substring(0, event.length() / 2), false);
            assertTrue(speaker.devices.isEmpty());
            socket.text(event.substring(event.length() / 2), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == first.length);
            var device = speaker.devices.getFirst();
            assertArrayEquals(first, device.written());
            assertEquals(SPEAKING, session.workspace().speaker().state());
            assertTrue(session.workspace().canCancel());

            socket.text(audio("response-1", second), true);
            socket.text(output("response.output_audio.done", "response-1", 0, ""), true);
            socket.text(audio("response-1", first), true);
            responseDone(socket, "response-1", "completed");
            assertArrayEquals(first, device.written());
            assertFalse(device.closed);
            assertFalse(session.workspace().canSend());
            assertEquals(RealtimeSession.TurnState.COMPLETED, session.workspace().turns().getFirst().state());

            device.playBufferedFrames();
            await(() -> device.written().length == first.length + second.length);
            var expected = new byte[first.length + second.length];
            System.arraycopy(first, 0, expected, 0, first.length);
            System.arraycopy(second, 0, expected, first.length, second.length);
            assertArrayEquals(expected, device.written());
            device.playBufferedFrames();
            await(() -> session.workspace().speaker().state() == IDLE);
            assertTrue(device.closed);
            assertTrue(session.workspace().canSend());
            assertFalse(session.workspace().canCancel());

            socket.text(output("response.output_audio.delta", "response-1", 0, ",\"delta\":\"invalid!\""), true);
            assertEquals(RealtimeSession.State.READY, session.status().state());
            assertEquals(1, speaker.devices.size());
        }
    }

    @Test
    void audioBudgetClosesTheSessionEvenWhileTheSpeakerIsOpening() throws Exception {
        var opening = new CompletableFuture<Void>();
        var unblock = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(() -> {
            opening.complete(null);
            unblock.join();
        });
        try (var session = session(delayedSpeaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var second = audio("response-1", new byte[48_000]);
            socket.text(second, true);
            await(opening::isDone);
            for (int count = 1; count < 30; count++) {
                socket.text(second, true);
            }
            assertEquals(RealtimeSession.State.READY, session.status().state());
            assertEquals(OPENING, session.workspace().speaker().state());
            socket.text(audio("response-1", new byte[2]), true);
            assertEquals(RealtimeSession.State.CLOSING, session.status().state());
            socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
            assertEquals(RealtimeSession.Failure.AUDIO_LIMIT, session.status().failure());
            unblock.complete(null);
            await(() -> !delayedSpeaker.devices.isEmpty() && delayedSpeaker.devices.getFirst().closed);
            assertEquals(0, delayedSpeaker.devices.getFirst().written().length);
        } finally {
            unblock.complete(null);
        }
    }

    @Test
    void stopsAndFlushesBufferedPlaybackAfterTheProviderAlreadyFinished() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == pcm.length);
            var device = speaker.devices.getFirst();
            socket.text(audio("response-1", pcm), true);
            responseDone(socket, "response-1", "completed");
            int writes = socket.writes.size();
            session.cancelTurn();
            socket.text(audio("response-1", pcm), true);
            await(() -> device.closed);
            assertTrue(device.stopped);
            assertTrue(device.flushed);
            assertArrayEquals(pcm, device.written());
            assertEquals(writes, socket.writes.size());
            assertTrue(session.workspace().canSend());
        }
    }

    @Test
    void cancellingBeforeResponseDoneFlushesPlaybackAndBlocksSubsequentDeltas() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == pcm.length);
            var device = speaker.devices.getFirst();
            session.cancelTurn();
            var cancel = json.readTree(socket.writes.getLast());
            assertEquals("response.cancel", cancel.path("type").asText());
            assertEquals("response-1", cancel.path("response_id").asText());
            socket.text(audio("response-1", pcm), true);
            responseDone(socket, "response-1", "cancelled");
            await(() -> device.closed);
            assertTrue(device.flushed);
            assertEquals(1, speaker.devices.size());
            assertEquals(RealtimeSession.TurnState.CANCELLED, session.workspace().turns().getFirst().state());
        }
    }

    @Test
    void disconnectDuringDeviceOpenRejectsLatePcmAndClosesTheLateDevice() throws Exception {
        var opening = new CompletableFuture<Void>();
        var unblock = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(() -> {
            opening.complete(null);
            unblock.join();
        });
        try (var session = session(delayedSpeaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(opening::isDone);
            assertEquals(OPENING, session.workspace().speaker().state());
            session.disconnect();
            socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
            var reconnected = ready(session, TranscriptionModel.OFF);
            socket.text(audio("response-1", pcm), true);
            unblock.complete(null);
            await(() -> !delayedSpeaker.devices.isEmpty() && delayedSpeaker.devices.getFirst().closed);
            assertEquals(0, delayedSpeaker.devices.getFirst().written().length);
            assertFalse(reconnected.aborted);
            assertEquals(IDLE, session.workspace().speaker().state());
        } finally {
            unblock.complete(null);
        }
    }

    @Test
    void finalizesOutputPartsOnceAndKeepsTheTranscriptWhenTheSpeakerIsUnavailable() throws Exception {
        try (var session = session(new SpeakerPlayback(() -> { throw new LineUnavailableException(); }))) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            socket.text(audio("response-1", microphone.block(1)), true);
            await(() -> session.workspace().speaker().state() == UNAVAILABLE);
            transcript(socket, "delta", 0, "Partial ", "response-1");
            assertEquals("Partial ", session.workspace().turns().getFirst().output());
            transcript(socket, "done", 0, "First sentence.", "response-1");
            transcript(socket, "delta", 0, "late", "response-1");
            transcript(socket, "done", 0, "duplicate", "response-1");
            transcript(socket, "delta", 1, "Second", "response-1");
            assertEquals("First sentence.\nSecond", session.workspace().turns().getFirst().output());
            socket.text("""
                    {"type":"response.done","response":{"id":"response-1","status":"completed","output":[
                     {"type":"message","role":"assistant","content":[
                      {"type":"output_audio","transcript":"First sentence."},
                      {"type":"output_audio","transcript":"Second sentence."}]}]}}
                    """, true);
            transcript(socket, "delta", 1, "too late", "response-1");
            assertEquals("First sentence.\nSecond sentence.", session.workspace().turns().getFirst().output());
            assertTrue(session.workspace().canSend());
        }
    }

    @Test
    void associatesOutOfOrderInputResultsWithCommittedItemsAndRedactsFailure() throws Exception {
        try (var session = session(speaker.playback())) {
            var model = TranscriptionModel.REALTIME_WHISPER;
            var socket = ready(session, model);
            assertEquals(model.modelId(), json.readTree(socket.writes.getFirst()).at("/session/audio/input/transcription/model").asText());
            startVoice(session, socket, "input-1", "response-1");
            responseDone(socket, "response-1", "completed");
            startVoice(session, socket, "input-2", "response-2");
            assertEquals(PENDING, session.workspace().turns().getFirst().inputTranscript());
            assertEquals(PENDING, session.workspace().turns().getLast().inputTranscript());

            inputTranscript(socket, "completed", "unknown", ",\"transcript\":\"wrong item\"");
            inputTranscript(socket, "failed", "input-2", ",\"error\":{\"message\":\"private provider detail\"}");
            inputTranscript(socket, "completed", "input-2", ",\"transcript\":\"late duplicate\"");
            inputTranscript(socket, "completed", "input-1", ",\"transcript\":\"Synthetic input\u002e\"");
            responseDone(socket, "response-2", "completed");
            var turns = session.workspace().turns();
            assertEquals(COMPLETED, turns.getFirst().inputTranscript());
            assertEquals("Synthetic input.", turns.getFirst().input());
            assertEquals(FAILED, turns.getLast().inputTranscript());
            assertEquals("Voice input", turns.getLast().input());
            assertEquals(RealtimeSession.TurnState.COMPLETED, turns.getLast().state());
            assertTrue(session.workspace().canSend());
        }
    }

    @Test
    void acceptsInputTranscriptWhileTheCommitWriteIsPending() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.TRANSCRIBE);
            var appendWrite = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = appendWrite;
            record(session);
            await(() -> socket.writes.size() == 2);
            var commitWrite = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = commitWrite;
            appendWrite.complete(socket);
            committed(socket, "input-1");
            inputTranscript(socket, "completed", "input-1", ",\"transcript\":\"Early synthetic transcript\"");
            assertTrue(session.workspace().turns().isEmpty());
            commitWrite.complete(socket);
            assertEquals("Early synthetic transcript", session.workspace().turns().getFirst().input());
        }
    }

    @Test
    void rejectsUnexpectedTranscriptionConfigurationAndUnalignedPcm() throws Exception {
        try (var session = session(speaker.playback())) {
            session.connect("test-model");
            var socket = transport.attempts.getLast();
            socket.ready(TranscriptionModel.LIVE_TRANSCRIBE.modelId());
            assertEquals(RealtimeSession.Failure.PROTOCOL, session.status().failure());
            assertTrue(socket.aborted);
            socket = ready(session, TranscriptionModel.OFF);
            inputTranscript(socket, "completed", "unknown", ",\"transcript\":\"unexpected\"");
            assertTrue(session.workspace().turns().isEmpty());
            socket.text(audio("response-1", new byte[1]), true);
            assertEquals(RealtimeSession.Failure.PROTOCOL, session.status().failure());
            assertTrue(speaker.devices.isEmpty());
        }
    }

    private RealtimeSession session(SpeakerPlayback playback) {
        return new RealtimeSession(transport, () -> {}, microphone.microphone(), playback);
    }

    private FakeTransport.Socket ready(RealtimeSession session, TranscriptionModel transcription) {
        session.connect("test-model", transcription);
        var socket = transport.attempts.getLast();
        socket.ready(transcription == TranscriptionModel.OFF ? null : transcription.modelId());
        assertEquals(RealtimeSession.State.READY, session.status().state());
        return socket;
    }

    private void startVoice(RealtimeSession session, FakeTransport.Socket socket, String itemId, String responseId)
            throws Exception {
        int previousWrites = socket.writes.size();
        record(session);
        await(() -> socket.writes.size() == previousWrites + 2);
        committed(socket, itemId);
        var response = json.readTree(socket.writes.getLast()).path("response");
        assertEquals("audio", response.at("/output_modalities/0").asText());
        socket.text("""
                {"type":"response.created","response":{"id":"%s","metadata":%s}}
                """.formatted(responseId, response.path("metadata")), true);
    }

    private void record(RealtimeSession session) throws InterruptedException {
        assertTrue(session.startAudio());
        microphone.queueBlocks(3);
        await(() -> session.workspace().microphone().seconds() >= 0.12);
        session.releaseAudio();
    }

    private void committed(FakeTransport.Socket socket, String itemId) {
        socket.text("""
                {"type":"input_audio_buffer.committed","item_id":"%s"}
                """.formatted(itemId), true);
    }

    private void responseDone(FakeTransport.Socket socket, String responseId, String status) {
        socket.text("""
                {"type":"response.done","response":{"id":"%s","status":"%s","output":[]}}
                """.formatted(responseId, status), true);
    }

    private String audio(String responseId, byte[] pcm) {
        return output("response.output_audio.delta", responseId, 0,
                ",\"delta\":\"" + Base64.getEncoder().encodeToString(pcm) + "\"");
    }

    private void transcript(FakeTransport.Socket socket, String kind, int content, String text, String responseId) {
        var property = "delta".equals(kind) ? "delta" : "transcript";
        socket.text(output("response.output_audio_transcript." + kind, responseId, content,
                ",\"" + property + "\":\"" + text + "\""), true);
    }

    private String output(String type, String responseId, int content, String fields) {
        return """
                {"type":"%s","response_id":"%s","item_id":"assistant-item","output_index":0,"content_index":%d%s}
                """.formatted(type, responseId, content, fields);
    }

    private void inputTranscript(FakeTransport.Socket socket, String kind, String itemId, String fields) {
        socket.text("""
                {"type":"conversation.item.input_audio_transcription.%s","item_id":"%s","content_index":0%s}
                """.formatted(kind, itemId, fields), true);
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Expected state did not arrive");
    }
}

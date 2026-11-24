package io.github.stevdrey.realtimevoicelab.realtime;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.AudioFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stevdrey.realtimevoicelab.audio.FakeMicrophone;
import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import org.junit.jupiter.api.Test;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.MicState.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeAudioInputTest {
    private final FakeTransport transport = new FakeTransport();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void opensOnlyOnPressAndSendsExactOrderedPcmAfterClosingTheDevice() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            var inputConfig = json.readTree(socket.writes.getFirst()).path("session").path("audio").path("input");
            assertTrue(inputConfig.path("turn_detection").isNull());
            assertEquals("audio/pcm", inputConfig.path("format").path("type").asText());
            assertEquals(24_000, inputConfig.path("format").path("rate").asInt());
            assertEquals(0, device.opens.get());
            assertTrue(session.workspace().microphone().canStart());

            assertTrue(session.startAudio());
            assertFalse(session.startAudio());
            assertFalse(session.sendText("synthetic concurrent turn"));
            device.queueBlocks(7);
            await(() -> session.workspace().microphone().seconds() >= 0.28);
            assertEquals(RECORDING, session.workspace().microphone().state());
            assertTrue(session.workspace().microphone().level() >= 0.25);
            assertEquals(List.of("session.update"), types(socket));

            socket.nextTurnWrite = new CompletableFuture<>();
            var firstAppend = socket.nextTurnWrite;
            session.releaseAudio();
            await(() -> socket.writes.size() == 2);
            assertEquals(1, device.closes.get());
            assertEquals(List.of("session.update", "input_audio_buffer.append"), types(socket));
            firstAppend.complete(socket);
            await(() -> socket.writes.size() == 4);
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.append",
                    "input_audio_buffer.commit"), types(socket));
            for (int sequence = 0; sequence < 7; sequence++) {
                var append = json.readTree(socket.writes.get(1 + sequence / 5)).path("audio").binaryValue();
                int offset = sequence % 5 * MicrophoneCapture.BLOCK_BYTES;
                assertArrayEquals(device.block(sequence),
                        Arrays.copyOfRange(append, offset, offset + MicrophoneCapture.BLOCK_BYTES));
            }
            assertEquals(5 * MicrophoneCapture.BLOCK_BYTES,
                    json.readTree(socket.writes.get(1)).path("audio").binaryValue().length);
            assertEquals(2 * MicrophoneCapture.BLOCK_BYTES,
                    json.readTree(socket.writes.get(2)).path("audio").binaryValue().length);
            committed(socket);
            var response = json.readTree(socket.writes.getLast());
            assertEquals("response.create", response.path("type").asText());
            assertEquals("text", response.path("response").path("output_modalities").get(0).asText());
            assertEquals(512, response.path("response").path("max_output_tokens").asInt());
            assertEquals("Voice input", session.workspace().turns().getFirst().input());
            socket.text("""
                    {"type":"response.done","response":{"id":"audio-response","status":"completed",
                     "metadata":{"rvl_turn":"rvl-1"},"output":[{"type":"message","role":"assistant",
                     "content":[{"type":"output_text","text":"Synthetic reply"}]}]}}
                    """, true);
            assertEquals("Synthetic reply", session.workspace().turns().getFirst().output());
            assertTrue(session.workspace().canSend());
        }
    }

    @Test
    void emptyAndShortTakesNeverUploadOrCommit() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            for (int blocks : List.of(0, 1)) {
                assertTrue(session.startAudio());
                device.queueBlocks(blocks);
                await(() -> session.workspace().microphone().state() == RECORDING
                        && session.workspace().microphone().seconds() >= blocks * 0.04);
                session.releaseAudio();
                await(() -> session.workspace().microphone().state() == TOO_SHORT);
            }
            assertEquals(2, device.closes.get());
            assertEquals(List.of("session.update"), types(socket));
            assertTrue(session.workspace().turns().isEmpty());
        }
    }

    @Test
    void cancelDuringCaptureDisconnectAndCloseDiscardTheTakeAndReleaseTheDevice() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 3);
            session.cancelTurn();
            await(() -> session.workspace().microphone().canStart());
            assertEquals(1, device.closes.get());
            assertEquals(List.of("session.update"), types(socket));

            record(session, device, 3);
            session.disconnect();
            await(() -> device.closes.get() == 2);
            assertEquals(List.of("session.update"), types(socket));
            socket.peerClose(1000, "");

            var second = ready(session);
            record(session, device, 3);
            session.close();
            await(() -> device.closes.get() == 3);
            assertEquals(List.of("session.update"), types(second));
            assertTrue(second.aborted);
        }
    }

    @Test
    void cancelWhileOpeningClosesTheLateLineWithoutStartingIt() throws Exception {
        var finishOpen = new CompletableFuture<Void>();
        var device = new FakeMicrophone(new AudioFormat(24_000, 16, 1, true, false), finishOpen::join);
        try (var session = newSession(device)) {
            var socket = ready(session);
            assertTrue(session.startAudio());
            await(() -> device.opens.get() == 1);
            session.cancelTurn();
            assertFalse(session.startAudio());
            finishOpen.complete(null);
            await(() -> session.workspace().microphone().canStart());
            assertEquals(0, device.starts.get());
            assertEquals(1, device.closes.get());
            assertEquals(List.of("session.update"), types(socket));
        } finally {
            finishOpen.complete(null);
        }
    }

    @Test
    void rejectsUnsupportedDeviceAndUnconfirmedManualSessionFormat() throws Exception {
        var device = new FakeMicrophone(new AudioFormat(48_000, 16, 1, true, false), () -> {});
        try (var session = newSession(device)) {
            session.connect(RealtimeSession.DEFAULT_MODEL);
            var socket = transport.attempts.getLast();
            socket.opened();
            socket.created();
            socket.textWrite.complete(socket);
            socket.text("""
                    {"type":"session.updated","session":{"type":"realtime","id":"test-session","max_output_tokens":512,
                     "audio":{"input":{"format":{"type":"audio/pcm","rate":24000},"turn_detection":{"type":"server_vad"}},
                              "output":{"format":{"type":"audio/pcm","rate":24000}}}}}
                    """, true);
            assertEquals(UNCONFIRMED, session.workspace().microphone().state());
            assertTrue(session.workspace().canSend());
            assertFalse(session.startAudio());
            assertEquals(0, device.opens.get());
            session.disconnect();
            socket.peerClose(1000, "");

            var supportedSession = ready(session);
            assertTrue(session.startAudio());
            await(() -> session.workspace().microphone().state() == UNSUPPORTED);
            assertEquals(0, device.starts.get());
            assertEquals(1, device.closes.get());
            assertEquals(List.of("session.update"), types(supportedSession));
        }
    }

    @Test
    void cancellingAnUploadClearsTheProviderBufferBeforeAnotherTurnCanStart() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 7);
            var append = new CompletableFuture<java.net.http.WebSocket>();
            socket.nextTurnWrite = append;
            session.releaseAudio();
            await(() -> socket.writes.size() == 2);
            session.cancelTurn();
            assertFalse(session.startAudio());
            append.complete(socket);
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.clear"),
                    types(socket));
            assertFalse(session.workspace().canSend());
            socket.text("{\"type\":\"input_audio_buffer.cleared\",\"event_id\":\"synthetic\"}", true);
            assertTrue(session.workspace().canSend());
            assertTrue(session.workspace().turns().isEmpty());
        }
    }

    @Test
    void rejectedCommitAbortsTheSessionWithoutCreatingAResponse() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 3);
            session.releaseAudio();
            await(() -> socket.writes.size() == 3);
            var eventId = json.readTree(socket.writes.getLast()).path("event_id").asText();
            socket.text("""
                    {"type":"error","error":{"event_id":"%s","message":"synthetic provider detail"}}
                    """.formatted(eventId), true);
            assertEquals(RealtimeSession.Failure.PROVIDER, session.status().failure());
            assertTrue(socket.aborted);
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit"),
                    types(socket));
        }
    }

    @Test
    void keepsAtMostThirtySecondsAndWaitsForReleaseAtTheLimit() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            assertTrue(session.startAudio());
            device.queueBlocks(1_000);
            await(() -> session.workspace().microphone().state() == LIMIT);
            assertEquals(30, session.workspace().microphone().seconds());
            assertEquals(1, device.closes.get());
            assertEquals(List.of("session.update"), types(socket));
            session.releaseAudio();
            int bytes = 0;
            for (var write : socket.writes) {
                var event = json.readTree(write);
                if (event.path("type").asText().equals("input_audio_buffer.append")) {
                    int length = event.path("audio").binaryValue().length;
                    assertEquals(0, length % Short.BYTES);
                    bytes += length;
                }
            }
            assertEquals(1_440_000, bytes);
            assertEquals("input_audio_buffer.commit", types(socket).getLast());
        }
    }

    private RealtimeSession newSession(FakeMicrophone microphone) {
        return new RealtimeSession(transport, () -> {}, microphone.microphone());
    }

    private FakeTransport.Socket ready(RealtimeSession session) {
        session.connect(RealtimeSession.DEFAULT_MODEL);
        var socket = transport.attempts.getLast();
        socket.ready();
        assertEquals(RealtimeSession.State.READY, session.status().state());
        return socket;
    }

    private void record(RealtimeSession session, FakeMicrophone device, int blocks) throws Exception {
        assertTrue(session.startAudio());
        device.queueBlocks(blocks);
        await(() -> session.workspace().microphone().seconds() >= blocks * 0.04);
    }

    private void committed(FakeTransport.Socket socket) {
        socket.text("""
                {"type":"input_audio_buffer.committed","event_id":"synthetic","item_id":"voice-item"}
                """, true);
    }

    private List<String> types(FakeTransport.Socket socket) throws Exception {
        var types = new java.util.ArrayList<String>();
        for (var write : socket.writes) {
            types.add(json.readTree(write).path("type").asText());
        }
        return types;
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long endsAt = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < endsAt) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for capture or transport");
    }
}

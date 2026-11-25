package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            var inspectedAppends = session.inspectedEvents().stream()
                    .filter(event -> "input_audio_buffer.append".equals(event.type())).toList();
            assertEquals(List.of(5 * MicrophoneCapture.BLOCK_BYTES, 2 * MicrophoneCapture.BLOCK_BYTES),
                    inspectedAppends.stream().map(EventInspector.Entry::audioBytes).toList());
            for (var entry : inspectedAppends) {
                var detail = json.readTree(entry.jsonDetail());
                assertFalse(detail.has("audio"));
                assertEquals(entry.audioBytes(), detail.path("audio_bytes").intValue());
            }
            committed(socket);
            var response = json.readTree(socket.writes.getLast());
            assertEquals("response.create", response.path("type").asText());
            assertEquals("audio", response.path("response").path("output_modalities").get(0).asText());
            assertEquals(512, response.path("response").path("max_output_tokens").asInt());
            assertEquals("Voice input", session.workspace().turns().getFirst().input());
            socket.text("""
                    {"type":"response.done","response":{"id":"audio-response","status":"completed",
                     "metadata":{"rvl_turn":"rvl-1"},"output":[{"type":"message","role":"assistant",
                     "content":[{"type":"output_audio","transcript":"Synthetic reply"}]}]}}
                    """, true);
            assertEquals("Synthetic reply", session.workspace().turns().getFirst().output());
            assertTrue(session.workspace().canSend());
        }
    }

    @Test
    void keepsThePartialBlockInFlightAtReleaseAndCommitsTheWholeTake() throws Exception {
        int tailBytes = MicrophoneCapture.BYTES_PER_SECOND * 30 / 1_000;
        var tailStarted = new CompletableFuture<Void>();
        var finishRead = new CompletableFuture<Void>();
        var device = new FakeMicrophone(length -> {
            if (length == tailBytes) {
                tailStarted.complete(null);
                finishRead.join();
            }
        });

        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 2);
            device.queueBytes(tailBytes);
            await(tailStarted::isDone);
            session.releaseAudio();
            assertEquals(1, socket.writes.size());
            finishRead.complete(null);
            await(() -> socket.writes.size() == 3);

            var pcm = json.readTree(socket.writes.get(1)).path("audio").binaryValue();
            int fullBytes = 2 * MicrophoneCapture.BLOCK_BYTES;
            assertEquals(fullBytes + tailBytes, pcm.length);
            assertArrayEquals(Arrays.copyOf(device.block(2), tailBytes), Arrays.copyOfRange(pcm, fullBytes, pcm.length));
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit"),
                    types(socket));
            assertEquals(1, device.closes.get());
        } finally {
            finishRead.complete(null);
        }
    }

    @Test
    void releasesBetweenAvailableAndReadAndOnlySendsTheObservedPcm() throws Exception {
        int fullBytes = 2 * MicrophoneCapture.BLOCK_BYTES;
        int tailBytes = MicrophoneCapture.BYTES_PER_SECOND * 30 / 1_000;
        var tailObserved = new CompletableFuture<Void>();
        var resumeCapture = new CompletableFuture<Void>();
        var bytesRead = new AtomicInteger();
        var device = new FakeMicrophone(available -> {
            if (available == tailBytes) {
                tailObserved.complete(null);
                resumeCapture.join();
            }
        }, bytesRead::addAndGet);

        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 2);
            device.queueBytes(tailBytes);
            await(tailObserved::isDone);
            session.releaseAudio();
            assertEquals(fullBytes, bytesRead.get());
            assertEquals(1, socket.writes.size());
            // New PCM arrives after release but before the worker can read the previously observed tail.
            device.queueBlocks(1);
            resumeCapture.complete(null);
            await(() -> session.workspace().microphone().state() != STOPPING);

            assertEquals(fullBytes + tailBytes, bytesRead.get());
            assertEquals(1, device.closes.get());
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit"),
                    types(socket));
            var pcm = json.readTree(socket.writes.get(1)).path("audio").binaryValue();
            assertEquals(fullBytes + tailBytes, pcm.length);
            assertArrayEquals(device.block(0), Arrays.copyOfRange(pcm, 0, MicrophoneCapture.BLOCK_BYTES));
            assertArrayEquals(device.block(1), Arrays.copyOfRange(pcm, MicrophoneCapture.BLOCK_BYTES, fullBytes));
            assertArrayEquals(Arrays.copyOf(device.block(2), tailBytes), Arrays.copyOfRange(pcm, fullBytes, pcm.length));
        } finally {
            resumeCapture.complete(null);
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
            var append = new CompletableFuture<WebSocket>();
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
    void cancellationDuringCommitDeletesTheExactItemBeforeEnablingAnotherTurn() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 3);
            var append = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = append;
            session.releaseAudio();
            await(() -> socket.writes.size() == 2 && session.workspace().microphone().state() == SENDING);

            var commitWrite = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = commitWrite;
            append.complete(socket);
            session.cancelTurn();
            committed(socket);
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit"),
                    types(socket));
            assertFalse(session.workspace().canSend());

            var deleteWrite = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = deleteWrite;
            commitWrite.complete(socket);
            var deletion = json.readTree(socket.writes.getLast());
            assertEquals("conversation.item.delete", deletion.path("type").asText());
            assertEquals("voice-item", deletion.path("item_id").asText());
            socket.text("""
                    {"type":"input_audio_buffer.cleared","event_id":"synthetic-clear"}
                    """, true);
            deleted(socket, "other-item");
            assertFalse(session.sendText("synthetic text during cancellation"));
            assertFalse(session.startAudio());
            deleted(socket, "voice-item");
            assertFalse(session.workspace().canSend());
            deleteWrite.complete(socket);

            assertTrue(session.workspace().canSend());
            assertTrue(session.workspace().microphone().canStart());
            assertTrue(session.workspace().turns().isEmpty());
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit",
                    "conversation.item.delete"), types(socket));
        }
    }

    @Test
    void cancellingAfterCommitAcknowledgmentButBeforeWriteCompletionDeletesTheAudioItem() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 3);
            var append = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = append;
            session.releaseAudio();
            await(() -> socket.writes.size() == 2 && session.workspace().microphone().state() == SENDING);

            var commitWrite = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = commitWrite;
            append.complete(socket);
            committed(socket);
            assertTrue(session.workspace().canCancel());
            session.cancelTurn();
            assertFalse(session.workspace().canSend());
            commitWrite.complete(socket);

            var deletion = json.readTree(socket.writes.getLast());
            assertEquals("conversation.item.delete", deletion.path("type").asText());
            assertEquals("voice-item", deletion.path("item_id").asText());
            assertFalse(session.workspace().canSend());
            deleted(socket, "voice-item");

            assertTrue(session.workspace().canSend());
            assertTrue(session.workspace().turns().isEmpty());
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit",
                    "conversation.item.delete"), types(socket));
        }
    }

    @Test
    void rejectedDeleteAbortsTheSessionBeforeAnotherTurnCanUseTheCancelledAudio() throws Exception {
        var device = new FakeMicrophone();
        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 3);
            session.releaseAudio();
            await(() -> socket.writes.size() == 3);
            session.cancelTurn();
            committed(socket);
            var deletion = json.readTree(socket.writes.getLast());
            assertEquals("conversation.item.delete", deletion.path("type").asText());
            socket.text("""
                    {"type":"error","error":{"event_id":"%s","message":"synthetic provider detail"}}
                    """.formatted(deletion.path("event_id").asText()), true);
            assertEquals(RealtimeSession.Failure.PROVIDER, session.status().failure());
            assertTrue(socket.aborted);
            assertFalse(session.workspace().canSend());
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

    @Test
    void openTimeoutDetachesAHungDeviceSoTextAndCancelAreNotBlocked() throws Exception {
        var finishOpen = new CompletableFuture<Void>();
        var device = new FakeMicrophone(new AudioFormat(24_000, 16, 1, true, false), finishOpen::join);
        var deadlines = new CopyOnWriteArrayList<Deadline>();
        try (var session = scheduledSession(device, deadlines)) {
            var socket = ready(session);
            assertTrue(session.startAudio());
            await(() -> device.opens.get() == 1);
            session.cancelTurn();
            assertFalse(session.workspace().canSend());

            expire(deadlines, RealtimeSession.OPEN_TIMEOUT);
            assertEquals(UNAVAILABLE, session.workspace().microphone().state());
            assertTrue(session.workspace().canSend());
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
    void restartsTheAcknowledgmentDeadlineForEveryUploadWrite() throws Exception {
        var device = new FakeMicrophone();
        var deadlines = new CopyOnWriteArrayList<Deadline>();
        try (var session = scheduledSession(device, deadlines)) {
            var socket = ready(session);
            record(session, device, 7);
            session.releaseAudio();
            await(() -> socket.writes.size() == 4);
            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.append",
                    "input_audio_buffer.commit"), types(socket));
            var ackDeadlines = deadlines.stream()
                    .filter(deadline -> deadline.nanos() == RealtimeSession.ITEM_ACK_TIMEOUT.toNanos()).toList();
            assertEquals(3, ackDeadlines.size());
            assertTrue(ackDeadlines.stream().limit(2)
                    .allMatch(deadline -> deadline.future().isDone() && !deadline.future().isCompletedExceptionally()));
        }
    }

    @Test
    void deviceErrorAfterReleaseStillSendsTheCapturedTake() throws Exception {
        int tailBytes = MicrophoneCapture.BYTES_PER_SECOND * 30 / 1_000;
        var tailObserved = new CompletableFuture<Void>();
        var resumeCapture = new CompletableFuture<Void>();
        var failRead = new AtomicBoolean();
        var device = new FakeMicrophone(available -> {
            if (available == tailBytes) {
                tailObserved.complete(null);
                resumeCapture.join();
            }
        }, length -> {
            if (failRead.get()) {
                throw new IllegalStateException("synthetic device removal");
            }
        });

        try (var session = newSession(device)) {
            var socket = ready(session);
            record(session, device, 3);
            device.queueBytes(tailBytes);
            await(tailObserved::isDone);
            session.releaseAudio();
            failRead.set(true);
            resumeCapture.complete(null);
            await(() -> socket.writes.size() == 3);

            assertEquals(List.of("session.update", "input_audio_buffer.append", "input_audio_buffer.commit"),
                    types(socket));
            assertEquals(3 * MicrophoneCapture.BLOCK_BYTES,
                    json.readTree(socket.writes.get(1)).path("audio").binaryValue().length);
            assertEquals(1, device.closes.get());
        } finally {
            resumeCapture.complete(null);
        }
    }

    private record Deadline(long nanos, CompletableFuture<Void> future) {
    }

    private RealtimeSession scheduledSession(FakeMicrophone microphone, List<Deadline> deadlines) {
        return new RealtimeSession(transport, () -> {}, Duration.ofSeconds(10), Duration.ofSeconds(3),
                System::nanoTime, nanos -> {
                    var future = new CompletableFuture<Void>();
                    deadlines.add(new Deadline(nanos, future));
                    return future;
                }, microphone.microphone());
    }

    private void expire(List<Deadline> deadlines, Duration timeout) {
        deadlines.stream().filter(deadline -> deadline.nanos() == timeout.toNanos()).findFirst().orElseThrow()
                .future().completeExceptionally(new TimeoutException());
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

    private void deleted(FakeTransport.Socket socket, String itemId) {
        socket.text("""
                {"type":"conversation.item.deleted","event_id":"synthetic-delete","item_id":"%s"}
                """.formatted(itemId), true);
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

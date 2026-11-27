package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stevdrey.realtimevoicelab.audio.FakeMicrophone;
import io.github.stevdrey.realtimevoicelab.audio.FakeSpeaker;
import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.InputTranscriptState.*;
import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.SpeakerState.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeAudioOutputTest {
    private final FakeTransport transport = new FakeTransport();
    private final FakeMicrophone microphone = new FakeMicrophone();
    private final FakeSpeaker speaker = new FakeSpeaker();
    private final ObjectMapper json = new ObjectMapper();

    private enum PlaybackExit {
        STOP, DISCONNECT, CLOSE
    }

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
            assertFalse(device.flushed);
            assertTrue(session.workspace().canSend());
            assertFalse(session.workspace().canCancel());

            socket.text(output("response.output_audio.delta", "response-1", 0, ",\"delta\":\"invalid!\""), true);
            assertEquals(RealtimeSession.State.READY, session.status().state());
            assertEquals(1, speaker.devices.size());
        }
    }

    @ParameterizedTest
    @EnumSource(PlaybackExit.class)
    void stopsDuringFinalDeviceBufferPlayback(PlaybackExit exit) throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == pcm.length);
            var device = speaker.devices.getFirst();
            try {
                responseDone(socket, "response-1", "completed");
                awaitFinalBufferPlayback(device);
                assertFalse(device.closed);

                switch (exit) {
                    case STOP -> session.cancelTurn();
                    case DISCONNECT -> session.disconnect();
                    case CLOSE -> session.close();
                }
                socket.text(audio("response-1", pcm), true);
                await(() -> device.closed);
                assertTrue(device.stopped);
                assertTrue(device.flushed);
                assertArrayEquals(pcm, device.written());
            } finally {
                device.playBufferedFrames();
            }
        }
    }

    @Test
    void playsBothVoiceResponsesAndKeepsTheirTranscriptsInOneTurnAfterAToolCall() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "initial");
            var first = microphone.block(0);
            var second = microphone.block(1);
            socket.text(audio("initial", first), true);
            transcript(socket, "done", 0, "First.", "initial");
            socket.text(output("response.output_audio.done", "initial", 0, ""), true);
            var toolItem = toolOutput(socket);
            assertEquals("function_call_output", toolItem.path("type").textValue());
            assertEquals("call_audio", toolItem.path("call_id").textValue());
            assertEquals(7, json.readTree(toolItem.path("output").textValue()).path("value").intValue());
            assertFalse(session.workspace().canSend());
            int writesBeforeFollowUp = socket.writes.size();
            acknowledgeToolOutput(socket, toolItem);
            await(() -> socket.writes.size() > writesBeforeFollowUp);
            bindVoiceFollowUp(socket);
            socket.text(audio("follow_up", second).replace("assistant-item", "second-assistant-item"), true);
            transcript(socket, "done", 0, "Seven.", "follow_up");
            responseDone(socket, "follow_up", "completed");

            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == first.length);
            var device = speaker.devices.getFirst();
            device.playBufferedFrames();
            await(() -> device.written().length == first.length + second.length);
            var expected = new byte[first.length + second.length];
            System.arraycopy(first, 0, expected, 0, first.length);
            System.arraycopy(second, 0, expected, first.length, second.length);
            assertArrayEquals(expected, device.written());
            device.playBufferedFrames();
            await(() -> session.workspace().canSend());
            assertEquals(1, speaker.devices.size());
            assertEquals(1, session.workspace().turns().size());
            assertEquals("First.\nSeven.", session.workspace().turns().getFirst().output());
            assertEquals(RealtimeSession.TurnState.COMPLETED, session.workspace().turns().getFirst().state());
        }
    }

    @Test
    void waitsForQueueCapacityBeforeRequestingAFullLengthToolFollowUp() throws Exception {
        var openSpeaker = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(openSpeaker::join);
        var deadlines = new CopyOnWriteArrayList<Deadline>();
        try (var session = session(delayedSpeaker.playback(), () -> {}, deadlines)) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "initial");
            socket.text(audio("initial", microphone.block(0)), true);
            var toolItem = toolOutput(socket);
            int writes = socket.writes.size();
            acknowledgeToolOutput(socket, toolItem);
            assertEquals(writes, socket.writes.size());
            assertTrue(session.workspace().canCancel());
            assertFalse(session.workspace().canSend());
            assertFalse(deadlines.stream().anyMatch(deadline -> deadline.nanos() == RealtimeSession.TOOL_OUTPUT_TIMEOUT.toNanos()
                    && !deadline.future().isDone()));

            openSpeaker.complete(null);
            await(() -> socket.writes.size() > writes);
            bindVoiceFollowUp(socket);
            var twoSeconds = audio("follow_up", new byte[2 * MicrophoneCapture.BYTES_PER_SECOND])
                    .replace("assistant-item", "second-assistant-item");
            for (int index = 0; index < 15; index++) {
                socket.text(twoSeconds, true);
            }
            responseDone(socket, "follow_up", "completed");
            assertEquals(writes + 1, socket.writes.size());
            assertEquals(SPEAKING, session.workspace().speaker().state());
            assertEquals(RealtimeSession.TurnState.COMPLETED, session.workspace().turns().getFirst().state());
        } finally {
            openSpeaker.complete(null);
        }
    }

    @Test
    void cancellingBeforeToolAcknowledgmentKeepsTheFullyPlayedFirstResponse() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "initial");
            var pcm = microphone.block(0);
            socket.text(audio("initial", pcm), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == pcm.length);
            var device = speaker.devices.getFirst();
            device.playBufferedFrames();
            var toolItem = toolOutput(socket);
            int writes = socket.writes.size();

            session.cancelTurn();
            await(() -> session.workspace().canSend() || socket.writes.size() > writes);
            acknowledgeToolOutput(socket, toolItem);
            assertEquals(writes, socket.writes.size());
            assertTrue(device.closed);
            assertTrue(session.workspace().canSend());
            assertEquals(RealtimeSession.TurnState.CANCELLED, session.workspace().turns().getFirst().state());
        }
    }

    @Test
    void cancellingWhileWaitingForQueueCapacityTruncatesUnheardAudioAndDropsTheFollowUp() throws Exception {
        var openSpeaker = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(openSpeaker::join);
        try (var session = session(delayedSpeaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "initial");
            socket.text(audio("initial", microphone.block(0)), true);
            var toolItem = toolOutput(socket);
            int writes = socket.writes.size();
            acknowledgeToolOutput(socket, toolItem);
            assertEquals(writes, socket.writes.size());

            session.cancelTurn();
            openSpeaker.complete(null);
            await(() -> socket.writes.size() > writes);
            assertTruncate(socket, 0);
            socket.truncated("assistant-item", 0);
            await(() -> session.workspace().canSend());
            assertEquals(writes + 1, socket.writes.size());
        } finally {
            openSpeaker.complete(null);
        }
    }

    @Test
    void cancellingDuringToolFollowUpTruncatesOnlyTheStillGeneratingResponse() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "initial");
            var pcm = microphone.block(0);
            socket.text(audio("initial", pcm), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == pcm.length);
            var device = speaker.devices.getFirst();
            device.playBufferedFrames();
            var toolItem = toolOutput(socket);
            acknowledgeToolOutput(socket, toolItem);
            bindVoiceFollowUp(socket);
            socket.text(audio("follow_up", pcm).replace("assistant-item", "second-assistant-item"), true);
            await(() -> device.written().length == 2 * pcm.length);
            device.playBufferedFrames();

            session.cancelTurn();
            var cancel = json.readTree(socket.writes.getLast());
            assertEquals("response.cancel", cancel.path("type").asText());
            assertEquals("follow_up", cancel.path("response_id").asText());
            int writes = socket.writes.size();
            responseDone(socket, "follow_up", "cancelled");
            await(() -> socket.writes.size() > writes);
            var truncate = json.readTree(socket.writes.getLast());
            assertEquals("conversation.item.truncate", truncate.path("type").asText());
            assertEquals("second-assistant-item", truncate.path("item_id").asText());
            assertEquals(40, truncate.path("audio_end_ms").asInt());
            socket.truncated("second-assistant-item", 40);
            await(() -> session.workspace().canSend());
            assertEquals(writes + 1, socket.writes.size());
            assertEquals("First.", session.workspace().turns().getFirst().output());
        }
    }

    private JsonNode toolOutput(FakeTransport.Socket socket) throws Exception {
        socket.text("""
                {"type":"response.done","response":{"id":"initial","status":"completed","output":[
                 {"id":"assistant-item","type":"message","role":"assistant","content":[{"type":"output_audio",
                  "transcript":"First."}]},
                 {"type":"function_call","status":"completed","call_id":"call_audio",
                  "name":"calculate_workspace_counter","arguments":%s}]}}
                """.formatted(json.writeValueAsString("{\"current\":3,\"delta\":4}")), true);
        return json.readTree(socket.writes.getLast()).path("item");
    }

    private void acknowledgeToolOutput(FakeTransport.Socket socket, JsonNode item) {
        socket.text("""
                {"type":"conversation.item.added","item":%s}
                """.formatted(item), true);
    }

    private void bindVoiceFollowUp(FakeTransport.Socket socket) throws Exception {
        var response = json.readTree(socket.writes.getLast()).path("response");
        assertEquals("audio", response.at("/output_modalities/0").asText());
        socket.text("""
                {"type":"response.created","response":{"id":"follow_up","metadata":%s}}
                """.formatted(response.path("metadata")), true);
    }

    private void awaitFinalBufferPlayback(FakeSpeaker.Device device) throws InterruptedException {
        int reads = device.positionReads();
        // Two samples after response.done rule out a poll that was already in progress.
        await(() -> device.draining || device.positionReads() >= reads + 2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 601})
    void truncatesUnheardAudioAfterSpeakerFailureInPushToTalk(int playedFrames) throws Exception {
        var clock = new AtomicLong();
        try (var session = session(speaker.playback(clock::get))) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(() -> !speaker.devices.isEmpty() && speaker.devices.getFirst().written().length == pcm.length);
            var device = speaker.devices.getFirst();
            try {
                device.playFrames(playedFrames);
                responseDone(socket, "response-1", "completed");
                awaitFinalBufferPlayback(device);
                assertFalse(device.closed);
                int writes = socket.writes.size();

                clock.set(Duration.ofSeconds(3).toNanos());
                await(() -> session.workspace().speaker().state() == UNAVAILABLE);
                assertTrue(device.stopped);
                assertTrue(device.flushed);
                assertTrue(device.closed);
                int playedMs = playedFrames / 24;
                assertEquals(writes + 1, socket.writes.size());
                assertTruncate(socket, playedMs);
                assertFalse(session.workspace().microphone().canStart());
                socket.truncated("assistant-item", playedMs);
                assertTrue(session.workspace().microphone().canStart());
            } finally {
                device.playBufferedFrames();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blocksTypedTurnsUntilFailedPlaybackIsReconciled(boolean framesStall) throws Exception {
        var clock = new AtomicLong();
        var stopping = new CompletableFuture<Void>();
        var allowStop = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(() -> {}, () -> {
            stopping.complete(null);
            allowStop.join();
        });
        var deadlines = new CopyOnWriteArrayList<Deadline>();
        try (var session = session(delayedSpeaker.playback(clock::get), () -> {}, deadlines)) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(() -> !delayedSpeaker.devices.isEmpty()
                    && delayedSpeaker.devices.getFirst().written().length == pcm.length);
            var device = delayedSpeaker.devices.getFirst();
            responseDone(socket, "response-1", "completed");
            awaitFinalBufferPlayback(device);

            if (framesStall) {
                clock.set(Duration.ofSeconds(3).toNanos());
            } else {
                device.playBufferedFrames();
            }
            await(stopping::isDone);
            assertEquals(CLEANING_UP, session.workspace().speaker().state());
            assertEquals(!framesStall, session.workspace().canSend());
            assertFalse(session.workspace().canCancel());
            assertFalse(session.startAudio());

            expire(deadlines, RealtimeSession.SPEAKER_CLEANUP_TIMEOUT);
            assertEquals(CLEANUP_TIMEOUT, session.workspace().speaker().state());
            assertFalse(device.closed);
            assertFalse(session.startAudio());
            assertEquals(!framesStall, session.sendText("Synthetic text during automatic speaker cleanup."));
            if (!framesStall) {
                completeTextTurn(socket, "text-during-cleanup");
                assertTrue(session.workspace().canSend());
            }
            assertEquals(1, delayedSpeaker.devices.size());

            int writes = socket.writes.size();
            allowStop.complete(null);
            if (framesStall) {
                await(() -> socket.writes.size() > writes);
                assertTruncate(socket, 0);
                assertFalse(session.sendText("Synthetic text before truncation acknowledgment."));
                socket.truncated("assistant-item", 0);
            }
            await(() -> session.workspace().microphone().canStart());
            assertEquals(framesStall ? writes + 1 : writes, socket.writes.size());
            assertTrue(device.closed);
            assertEquals(framesStall, device.flushed);
            assertEquals(framesStall ? UNAVAILABLE : IDLE, session.workspace().speaker().state());
            assertTrue(session.sendText("Synthetic text after speaker cleanup."));
            completeTextTurn(socket, "text-after-cleanup");
        } finally {
            allowStop.complete(null);
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
            await(() -> socket.writes.size() == writes + 1);
            var truncate = json.readTree(socket.writes.getLast());
            assertEquals("conversation.item.truncate", truncate.path("type").asText());
            assertEquals("assistant-item", truncate.path("item_id").asText());
            assertEquals(0, truncate.path("audio_end_ms").asInt(-1));
            assertFalse(session.workspace().canSend());
            socket.truncated("assistant-item", 0);
            assertTrue(session.workspace().canSend());
        }
    }

    @Test
    void blocksPushToTalkUntilTheSpeakerHasStoppedFlushedAndClosed() throws Exception {
        var stopping = new CompletableFuture<Void>();
        var allowStop = new CompletableFuture<Void>();
        var delayedSpeaker = new FakeSpeaker(() -> {}, () -> {
            stopping.complete(null);
            allowStop.join();
        });
        var statusChanges = new AtomicInteger();
        var deadlines = new CopyOnWriteArrayList<Deadline>();
        try (var session = session(delayedSpeaker.playback(), statusChanges::incrementAndGet, deadlines)) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            var pcm = microphone.block(1);
            socket.text(audio("response-1", pcm), true);
            await(() -> !delayedSpeaker.devices.isEmpty()
                    && delayedSpeaker.devices.getFirst().written().length == pcm.length);
            var device = delayedSpeaker.devices.getFirst();
            session.cancelTurn();
            responseDone(socket, "response-1", "cancelled");
            await(stopping::isDone);

            int previousOpens = microphone.opens.get();
            assertEquals(CLEANING_UP, session.workspace().speaker().state());
            assertFalse(device.flushed);
            assertFalse(session.workspace().canSend());
            assertFalse(session.workspace().microphone().canStart());
            assertFalse(session.startAudio());
            assertEquals(previousOpens, microphone.opens.get());

            expire(deadlines, RealtimeSession.SPEAKER_CLEANUP_TIMEOUT);
            assertEquals(CLEANUP_TIMEOUT, session.workspace().speaker().state());
            assertFalse(session.workspace().microphone().canStart());
            assertFalse(session.startAudio());
            assertFalse(session.workspace().canSend());

            for (int reconnects = 0; reconnects < 2; reconnects++) {
                session.disconnect();
                socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
                socket = ready(session, TranscriptionModel.OFF);
                assertEquals(CLEANUP_TIMEOUT, session.workspace().speaker().state());
                assertFalse(session.startAudio());
                assertTrue(session.sendText("Synthetic text while closing the speaker."));
                completeTextTurn(socket, "text-" + reconnects);
                socket.text(audio("late-voice-response", pcm), true);
                assertTrue(session.workspace().canSend());
                assertEquals(1, delayedSpeaker.devices.size());
                assertEquals(previousOpens, microphone.opens.get());
                assertFalse(device.closed);
            }

            int previousChanges = statusChanges.get();
            allowStop.complete(null);
            await(() -> statusChanges.get() > previousChanges && session.workspace().microphone().canStart());
            assertTrue(device.flushed);
            assertTrue(device.closed);
            assertEquals(IDLE, session.workspace().speaker().state());
            assertTrue(session.startAudio());
            await(() -> microphone.opens.get() == previousOpens + 1);
        } finally {
            allowStop.complete(null);
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
            assertFalse(session.workspace().microphone().canStart());
            socket.text(audio("response-1", pcm), true);
            unblock.complete(null);
            await(() -> !delayedSpeaker.devices.isEmpty() && delayedSpeaker.devices.getFirst().closed);
            await(() -> session.workspace().microphone().canStart());
            assertEquals(0, delayedSpeaker.devices.getFirst().written().length);
            assertFalse(reconnected.aborted);
            assertEquals(IDLE, session.workspace().speaker().state());
        } finally {
            unblock.complete(null);
        }
    }

    @Test
    void finalizesOutputPartsOnce() throws Exception {
        try (var session = session(speaker.playback())) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
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
            assertEquals(IDLE, session.workspace().speaker().state());
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
            session.connectSelected(TranscriptionModel.OFF);
            var socket = transport.attempts.getLast();
            socket.ready(TranscriptionModel.LIVE_TRANSCRIBE.modelId());
            assertEquals(RealtimeSession.Failure.PROTOCOL, session.status().failure());
            assertTrue(socket.aborted);
            socket = ready(session, TranscriptionModel.OFF);
            inputTranscript(socket, "completed", "unknown", ",\"transcript\":\"unexpected\"");
            assertTrue(session.workspace().turns().isEmpty());
            socket.text(audio("unrelated", new byte[1]), true);
            assertEquals(RealtimeSession.State.READY, session.status().state());
            startVoice(session, socket, "input-1", "response-1");
            socket.text(audio("response-1", new byte[1]), true);
            assertEquals(RealtimeSession.Failure.PROTOCOL, session.status().failure());
            assertTrue(speaker.devices.isEmpty());
        }
    }

    @Test
    void acceptsAProviderNormalizedTranscriptionModel() {
        try (var session = session(speaker.playback())) {
            session.connectSelected(TranscriptionModel.TRANSCRIBE);
            transport.attempts.getLast().ready("gpt-transcribe-2026-01-01");
            assertEquals(RealtimeSession.State.READY, session.status().state());
        }
    }

    @Test
    void keepsAudioBlockedUntilAStalledSpeakerOpenUnwinds() throws Exception {
        var unblock = new CompletableFuture<Void>();
        var opening = new CompletableFuture<Void>();
        var stuckSpeaker = new FakeSpeaker(() -> {
            opening.complete(null);
            unblock.join();
        });
        var deadlines = new CopyOnWriteArrayList<Deadline>();
        try (var session = session(stuckSpeaker.playback(), () -> {}, deadlines)) {
            var socket = ready(session, TranscriptionModel.OFF);
            startVoice(session, socket, "input-1", "response-1");
            socket.text(audio("response-1", microphone.block(1)), true);
            await(opening::isDone);

            expire(deadlines, RealtimeSession.OPEN_TIMEOUT);
            assertEquals(CLEANING_UP, session.workspace().speaker().state());
            assertEquals("response.cancel", json.readTree(socket.writes.getLast()).path("type").asText());
            responseDone(socket, "response-1", "completed");
            assertFalse(session.workspace().canSend());
            assertFalse(session.workspace().microphone().canStart());

            expire(deadlines, RealtimeSession.SPEAKER_CLEANUP_TIMEOUT);
            assertEquals(CLEANUP_TIMEOUT, session.workspace().speaker().state());
            assertFalse(session.startAudio());
            int writes = socket.writes.size();
            unblock.complete(null);
            await(() -> socket.writes.size() > writes);
            assertTruncate(socket, 0);
            assertFalse(session.workspace().microphone().canStart());
            socket.truncated("assistant-item", 0);
            await(() -> session.workspace().microphone().canStart());
            var device = stuckSpeaker.devices.getFirst();
            assertTrue(device.closed);
            assertEquals(0, device.written().length);
            assertEquals(IDLE, session.workspace().speaker().state());
        } finally {
            unblock.complete(null);
        }
    }

    private void assertTruncate(FakeTransport.Socket socket, int milliseconds) throws Exception {
        var truncate = json.readTree(socket.writes.getLast());
        assertEquals("conversation.item.truncate", truncate.path("type").asText());
        assertEquals("assistant-item", truncate.path("item_id").asText());
        assertEquals(milliseconds, truncate.path("audio_end_ms").asInt(-1));
    }

    private record Deadline(long nanos, CompletableFuture<Void> future) {
    }

    private void expire(List<Deadline> deadlines, Duration timeout) {
        deadlines.stream().filter(deadline -> deadline.nanos() == timeout.toNanos() && !deadline.future().isDone())
                .findFirst().orElseThrow().future().completeExceptionally(new TimeoutException());
    }

    private RealtimeSession session(SpeakerPlayback playback) {
        return new RealtimeSession(transport, () -> {}, microphone.microphone(), playback);
    }

    private RealtimeSession session(SpeakerPlayback playback, Runnable changed, List<Deadline> deadlines) {
        return new RealtimeSession(transport, changed, Duration.ofSeconds(15), Duration.ofSeconds(3),
                System::nanoTime, nanos -> {
                    var future = new CompletableFuture<Void>();
                    deadlines.add(new Deadline(nanos, future));
                    return future;
                }, microphone.microphone(), playback);
    }

    private FakeTransport.Socket ready(RealtimeSession session, TranscriptionModel transcription) {
        session.connectSelected(transcription);
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

    private void completeTextTurn(FakeTransport.Socket socket, String responseId) throws Exception {
        var itemId = json.readTree(socket.writes.getLast()).at("/item/id").asText();
        socket.text("""
                {"type":"conversation.item.added","item":{"id":"%s","role":"user","type":"message"}}
                """.formatted(itemId), true);
        socket.text("""
                {"type":"response.done","response":{"id":"%s","status":"completed",
                 "metadata":{"rvl_turn":"%s"},"output":[]}}
                """.formatted(responseId, itemId), true);
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

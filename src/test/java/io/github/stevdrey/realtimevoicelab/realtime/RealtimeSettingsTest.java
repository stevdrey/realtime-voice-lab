package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.Failure;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.State;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Mode;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Preset;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Voice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeSettingsTest {
    private static final SessionSettings DEFAULT = SessionSettings.DEFAULT;
    private final FakeTransport transport = new FakeTransport();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void appliesSelectedModelVoiceModeAndEditedPresetOnConnect() throws Exception {
        try (var session = new RealtimeSession(transport, () -> {})) {
            var requested = new SessionSettings(Model.STANDARD, Voice.CEDAR, Mode.TEXT,
                    Preset.ENGLISH_COACH.instructions() + "\nUse the example \"hello\".");
            assertTrue(session.configure(requested, State.DISCONNECTED));
            var socket = open(session);
            assertEquals(Model.STANDARD.id(), socket.model);
            var update = json.readTree(socket.writes.getFirst());
            assertEquals("cedar", update.at("/session/audio/output/voice").textValue());
            assertEquals("text", update.at("/session/output_modalities/0").textValue());
            assertEquals(requested.instructions(), update.at("/session/instructions").textValue());
            assertTrue(update.at("/session/model").isMissingNode());
            assertEquals(State.CONNECTING, session.status().state());
            assertFalse(session.workspace().canSend());

            socket.textWrite.complete(socket);
            socket.updated(requested, null);
            assertEquals(State.READY, session.status().state());
            assertFalse(session.workspace().microphone().canStart());
            assertFalse(session.startAudio());
            assertTrue(session.sendText("Synthetic text."));
        }
    }

    @Test
    void serializesLiveUpdatesAndKeepsThePreviousSettingsUntilTheWriteAndAckComplete() throws Exception {
        try (var session = new RealtimeSession(transport, () -> {})) {
            var socket = ready(session);
            var requested = new SessionSettings(Model.MINI, Voice.CEDAR, Mode.TEXT, "");
            socket.text("""
                    {"type":"response.output_audio.done","response_id":"cancelled-before-audio","item_id":"item",
                     "output_index":0,"content_index":0}
                    """, true);
            assertFalse(session.workspace().settings().voiceLocked());
            var write = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = write;
            assertTrue(session.configure(requested, State.READY));
            assertEquals("cedar", json.readTree(socket.writes.getLast()).at("/session/audio/output/voice").textValue());
            assertTrue(session.workspace().settings().applying());
            assertFalse(session.configure(DEFAULT, State.READY));
            assertFalse(session.sendText("Too early."));
            assertFalse(session.startAudio());

            socket.text("""
                    {"type":"session.updated","session":{"type":"realtime","id":"unrelated"}}
                    """, true);
            socket.updated(requested, null);
            assertEquals(DEFAULT, session.workspace().settings().values());
            assertTrue(session.workspace().settings().applying());
            write.complete(socket);
            assertEquals(requested, session.workspace().settings().values());
            assertFalse(session.workspace().settings().applying());
            assertTrue(session.workspace().canSend());
            assertFalse(session.workspace().microphone().canStart());
        }
    }

    @Test
    void disconnectsDuringASettingsWriteAndIgnoresTheOldSocketsConfirmation() {
        try (var session = new RealtimeSession(transport, () -> {})) {
            var socket = ready(session);
            var requested = new SessionSettings(Model.MINI, Voice.CEDAR, Mode.TEXT, "");
            var write = new CompletableFuture<WebSocket>();
            socket.nextTurnWrite = write;
            assertTrue(session.configure(requested, State.READY));
            session.disconnect();
            assertTrue(socket.aborted);
            assertEquals(0, socket.closeWrites);
            var retry = ready(session);

            write.complete(socket);
            socket.updated(requested, null);
            assertEquals(State.READY, session.status().state());
            assertEquals(DEFAULT, session.workspace().settings().values());
            assertFalse(retry.aborted);
        }
    }

    @Test
    void requiresReconnectForModelAndLocksVoiceAfterAudioEvenWithoutLocalPlayback() throws Exception {
        try (var session = new RealtimeSession(transport, () -> {})) {
            var socket = ready(session);
            var modelChange = new SessionSettings(Model.STANDARD, Voice.CEDAR, Mode.AUDIO, DEFAULT.instructions());
            var voiceChange = new SessionSettings(Model.MINI, Voice.CEDAR, Mode.AUDIO, DEFAULT.instructions());
            assertFalse(session.configure(modelChange, State.READY));
            socket.text("""
                    {"type":"response.output_audio.delta","response_id":"synthetic-audio","item_id":"item",
                     "output_index":0,"content_index":0,"delta":"AAA="}
                    """, true);
            assertTrue(session.workspace().settings().voiceLocked());
            assertFalse(session.configure(voiceChange, State.READY));
            assertEquals(1, socket.writes.size());

            var promptChange = new SessionSettings(Model.MINI, Voice.MARIN, Mode.AUDIO, "Synthetic prompt.");
            assertTrue(session.configure(promptChange, State.READY));
            assertTrue(json.readTree(socket.writes.getLast()).at("/session/audio").isMissingNode());
            socket.updated(promptChange, null);
            assertEquals(promptChange, session.workspace().settings().values());
            assertTrue(session.workspace().settings().voiceLocked());
            assertTrue(session.inspectedEvents().stream().noneMatch(event -> event.jsonDetail()
                    .contains(promptChange.instructions())));
            assertFalse(promptChange.toString().contains(promptChange.instructions()));

            session.disconnect();
            socket.peerClose(1000, "");
            assertTrue(session.configure(modelChange, State.DISCONNECTED));
            var retry = open(session);
            retry.textWrite.complete(retry);
            retry.updated(modelChange, null);
            assertEquals(Model.STANDARD.id(), retry.model);
            assertFalse(session.workspace().settings().voiceLocked());
        }
    }

    @Test
    void reportsCorrelatedProviderRejectionAndIgnoresLateAcknowledgements() throws Exception {
        try (var session = new RealtimeSession(transport, () -> {})) {
            var socket = ready(session);
            var requested = new SessionSettings(Model.MINI, Voice.MARIN, Mode.TEXT, "Synthetic edit.");
            assertTrue(session.configure(requested, State.READY));
            reject(socket, "unrelated");
            assertTrue(session.workspace().settings().applying());
            var eventId = json.readTree(socket.writes.getLast()).path("event_id").textValue();
            reject(socket, eventId);
            assertEquals(new RealtimeSession.Status(State.ERROR, Failure.SETTINGS_REJECTED), session.status());
            assertTrue(socket.aborted);
            socket.updated(requested, null);
            assertEquals(DEFAULT, session.workspace().settings().values());
            assertFalse(session.workspace().canSend());
            assertTrue(session.inspectedEvents().stream().noneMatch(event -> event.jsonDetail().contains("private")));
        }
    }

    @Test
    void abortsAnUnacknowledgedLiveUpdateAtItsDeadline() {
        var deadlines = new ArrayList<CompletableFuture<Void>>();
        try (var session = new RealtimeSession(transport, () -> {}, Duration.ofSeconds(15), Duration.ofSeconds(3),
                () -> 0, nanos -> {
                    var deadline = new CompletableFuture<Void>();
                    deadlines.add(deadline);
                    return deadline;
                })) {
            var socket = ready(session);
            assertTrue(session.configure(new SessionSettings(Model.MINI, Voice.CEDAR, Mode.TEXT, ""), State.READY));
            deadlines.getLast().completeExceptionally(new IOException("Synthetic deadline"));
            assertEquals(new RealtimeSession.Status(State.ERROR, Failure.SETTINGS_TIMEOUT), session.status());
            assertTrue(socket.aborted);
            assertEquals(DEFAULT, session.workspace().settings().values());
        }
    }

    @ParameterizedTest
    @EnumSource(Mismatch.class)
    void refusesAnAcknowledgementThatChangesEffectiveSettings(Mismatch mismatch) {
        try (var session = new RealtimeSession(transport, () -> {})) {
            var socket = open(session);
            socket.textWrite.complete(socket);
            var acknowledged = switch (mismatch) {
                case MODEL -> new SessionSettings(Model.STANDARD, Voice.MARIN, Mode.AUDIO, DEFAULT.instructions());
                case VOICE -> new SessionSettings(Model.MINI, Voice.CEDAR, Mode.AUDIO, DEFAULT.instructions());
                case MODE -> new SessionSettings(Model.MINI, Voice.MARIN, Mode.TEXT, DEFAULT.instructions());
                case INSTRUCTIONS -> new SessionSettings(Model.MINI, Voice.MARIN, Mode.AUDIO, "");
            };
            socket.updated(acknowledged, null);
            assertEquals(new RealtimeSession.Status(State.ERROR, Failure.SETTINGS_MISMATCH), session.status());
            assertTrue(socket.aborted);
        }
    }

    @Test
    void rejectsNonAllowlistedModelsAndOversizedInstructionsBeforeTransport() {
        try (var session = new RealtimeSession(transport, () -> {})) {
            session.connect("unsupported-model");
            assertEquals(new RealtimeSession.Status(State.ERROR, Failure.SETTINGS_REJECTED), session.status());
            assertThrows(IllegalArgumentException.class, () -> new SessionSettings(Model.MINI, Voice.MARIN, Mode.AUDIO,
                    "a".repeat(SessionSettings.MAX_INSTRUCTION_CHARS + 1)));
            assertTrue(transport.attempts.isEmpty());
        }
    }

    private FakeTransport.Socket open(RealtimeSession session) {
        session.connectSelected(TranscriptionModel.OFF);
        var socket = transport.attempts.getLast();
        socket.opened();
        socket.created();
        return socket;
    }

    private FakeTransport.Socket ready(RealtimeSession session) {
        var socket = open(session);
        socket.textWrite.complete(socket);
        socket.updated();
        assertEquals(State.READY, session.status().state());
        return socket;
    }

    private void reject(FakeTransport.Socket socket, String id) {
        socket.text("""
                {"type":"error","error":{"event_id":"%s","message":"private synthetic provider detail"}}
                """.formatted(id), true);
    }

    private enum Mismatch {
        MODEL, VOICE, MODE, INSTRUCTIONS
    }
}

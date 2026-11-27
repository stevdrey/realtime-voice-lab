package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Mode;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.ToolPreset;

public final class RealtimeSession implements AutoCloseable {
    public static final String DEFAULT_MODEL = Model.MINI.id();
    public static final int MAX_INPUT_CHARS = 4_000;
    private static final int MAX_HISTORY_TURNS = 60;
    static final Duration MAX_SESSION_TIME = Duration.ofMinutes(5);
    static final Duration ITEM_ACK_TIMEOUT = Duration.ofSeconds(15);
    static final Duration RESPONSE_CORRELATION_TIMEOUT = Duration.ofSeconds(15);
    static final Duration TOOL_OUTPUT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MAX_AUDIO_TIME = Duration.ofSeconds(30);
    static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);
    static final Duration SPEAKER_CLEANUP_TIMEOUT = Duration.ofSeconds(4);
    static final int MAX_INPUT_AUDIO_BYTES = (int) (SessionProtocol.PCM_BYTES_PER_SECOND * MAX_AUDIO_TIME.toSeconds());
    private static final int MAX_OUTPUT_AUDIO_BYTES = MAX_INPUT_AUDIO_BYTES;
    // Ignore very short taps instead of asking the provider to commit a sub-100 ms buffer.
    static final int MIN_INPUT_AUDIO_BYTES = SessionProtocol.PCM_BYTES_PER_SECOND / 10;
    private static final int MAX_VAD_BUFFER_BYTES = SessionProtocol.PCM_BYTES_PER_SECOND;
    private static final int AUDIO_APPEND_BYTES = SessionProtocol.PCM_BYTES_PER_SECOND / 5;
    static final int MAX_TOOL_CALLS = 10;
    static final int MAX_AUDIO_RESPONSES = 4;

    public enum State {
        DISCONNECTED, CONNECTING, READY, CLOSING, ERROR
    }

    public enum Failure {
        MISSING_CREDENTIAL, AUTHENTICATION, CONNECTION, TIMEOUT, ITEM_ACK_TIMEOUT, RESPONSE_TIMEOUT, CANCEL_TIMEOUT,
        PROTOCOL, PROVIDER, REMOTE_CLOSE, SETTINGS_REJECTED, SETTINGS_MISMATCH, SETTINGS_TIMEOUT,
        SESSION_LIMIT, AUDIO_LIMIT, OUTPUT_LIMIT, TOOL_LIMIT, TRANSCRIPTION_CONFLICT,
        SPEND_LIMIT, SPEND_ESTIMATE_UNAVAILABLE, SPEND_CONFIGURATION
    }

    public record Status(State state, Failure failure) {
    }

    public enum TurnState {
        WAITING, RESPONDING, CANCELLING, COMPLETED, CANCELLED, FAILED, INCOMPLETE, INTERRUPTED, CANCEL_REJECTED
    }

    public enum TranscriptionModel {
        OFF(""), TRANSCRIBE("gpt-transcribe"), LIVE_TRANSCRIBE("gpt-live-transcribe"),
        REALTIME_WHISPER("gpt-realtime-whisper");

        private final String modelId;

        TranscriptionModel(String modelId) {
            this.modelId = modelId;
        }

        public String modelId() {
            return modelId;
        }
    }

    public enum InputTranscriptState {
        OFF, PENDING, COMPLETED, FAILED
    }

    public record TurnView(long number, String input, String output, TurnState state, boolean voice,
                           InputTranscriptState inputTranscript) {
    }

    public enum MicState {
        IDLE, UNCONFIRMED, OPENING, RECORDING, LIMIT, STOPPING, SENDING, TOO_SHORT, UNSUPPORTED, UNAVAILABLE, BACKLOG
    }

    public record MicrophoneView(MicState state, boolean canStart, double seconds, double level) {
    }

    public enum SpeakerState {
        IDLE, OPENING, SPEAKING, CLEANING_UP, CLEANUP_TIMEOUT, UNAVAILABLE
    }

    public record SpeakerView(SpeakerState state, double level) {
    }

    public record SettingsView(SessionSettings values, boolean canEdit, boolean applying, boolean voiceLocked) {
    }

    public record RateLimit(String name, long limit, long remaining, double resetSeconds) {
    }

    public record UsageView(Model model, BigDecimal estimatedUsd, LocalDate pricingDate, long reportedInputTokens,
                            long reportedOutputTokens, long reportedCachedTokens, Long reportedReasoningTokens,
                            boolean pending, boolean missingUsage, boolean transcriptionEnabled,
                            boolean transcriptionUsed, List<RateLimit> rateLimits) {
    }

    public record Workspace(Status status, List<TurnView> turns, boolean canSend, boolean canCancel,
                            MicrophoneView microphone, SpeakerView speaker, SettingsView settings,
                            ToyWorkspace toy, UsageView usage, Duration audioLatency) {
    }

    private final RealtimeTransport transport;
    private final MicrophoneCapture microphone;
    private final SpeakerPlayback speaker;
    private final Runnable statusChanged;
    private final Runnable eventsChanged;
    private final Consumer<Runnable> dispatchWorkspaceTools;
    private final Duration setupTimeout;
    private final Duration closeTimeout;
    private final LongSupplier nanoTime;
    private final SessionPricing pricing;
    // The timer completes exceptionally on expiry. Normal completion cancels the timeout.
    private final LongFunction<CompletableFuture<Void>> scheduleTimeout;
    private final LocalTools localTools = new LocalTools();
    private final SessionProtocol protocol = new SessionProtocol(localTools);
    private final EventInspector inspector = new EventInspector(Clock.systemUTC());

    // Commands, listener callbacks, write completions and deadlines share this monitor.
    private Status status = new Status(State.DISCONNECTED, null);
    private SessionSettings settings = SessionSettings.DEFAULT;
    private ToyWorkspace toyWorkspace = ToyWorkspace.DEFAULT;
    private final ArrayDeque<ConversationTurn> history = new ArrayDeque<>();
    private long turnNumber;
    private Attempt active;
    private UsageView closedUsage;
    private Duration closedElapsed;
    private Duration audioLatency;
    private AudioInput captureWorker;
    private AudioOutput playbackWorker;
    private boolean disposed;

    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged) {
        this(transport, statusChanged, new MicrophoneCapture());
    }

    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged, MicrophoneCapture microphone) {
        this(transport, statusChanged, microphone, new SpeakerPlayback());
    }

    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged, MicrophoneCapture microphone,
                           SpeakerPlayback speaker) {
        this(transport, statusChanged, microphone, speaker, () -> {});
    }

    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged, MicrophoneCapture microphone,
                           SpeakerPlayback speaker, Runnable eventsChanged) {
        this(transport, statusChanged, microphone, speaker, eventsChanged, Runnable::run);
    }

    /**
     * Creates a session whose toy workspace batches run through {@code dispatchWorkspaceTools}. The desktop passes
     * {@code Platform::runLater}. Constructors without a dispatcher run each batch on the thread that delivered the
     * provider event.
     */
    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged, MicrophoneCapture microphone,
                           SpeakerPlayback speaker, Runnable eventsChanged, Consumer<Runnable> dispatchWorkspaceTools) {
        this(transport, statusChanged, Duration.ofSeconds(15), Duration.ofSeconds(3),
                System::nanoTime, RealtimeSession::timeoutAfter, microphone, speaker, eventsChanged,
                dispatchWorkspaceTools, SessionPricing.configured());
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout, Duration closeTimeout) {
        this(transport, statusChanged, setupTimeout, closeTimeout, System::nanoTime, RealtimeSession::timeoutAfter);
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout, Duration closeTimeout,
                    LongSupplier nanoTime, LongFunction<CompletableFuture<Void>> scheduleTimeout) {
        this(transport, statusChanged, setupTimeout, closeTimeout, nanoTime, scheduleTimeout, new MicrophoneCapture());
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout,
                            Duration closeTimeout, LongSupplier nanoTime,
                            LongFunction<CompletableFuture<Void>> scheduleTimeout, MicrophoneCapture microphone) {
        this(transport, statusChanged, setupTimeout, closeTimeout, nanoTime, scheduleTimeout, microphone,
                new SpeakerPlayback());
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout,
                    Duration closeTimeout, LongSupplier nanoTime, LongFunction<CompletableFuture<Void>> scheduleTimeout,
                    MicrophoneCapture microphone, SpeakerPlayback speaker) {
        this(transport, statusChanged, setupTimeout, closeTimeout, nanoTime, scheduleTimeout, microphone, speaker,
                () -> {}, Runnable::run);
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout,
                            Duration closeTimeout, LongSupplier nanoTime,
                            LongFunction<CompletableFuture<Void>> scheduleTimeout, MicrophoneCapture microphone,
                            SpeakerPlayback speaker, Runnable eventsChanged, Consumer<Runnable> dispatchWorkspaceTools) {
        this(transport, statusChanged, setupTimeout, closeTimeout, nanoTime, scheduleTimeout, microphone, speaker,
                eventsChanged, dispatchWorkspaceTools, SessionPricing.load(null, null));
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout, Duration closeTimeout,
                    LongSupplier nanoTime, LongFunction<CompletableFuture<Void>> scheduleTimeout,
                    MicrophoneCapture microphone, SpeakerPlayback speaker, Runnable eventsChanged,
                    Consumer<Runnable> dispatchWorkspaceTools, SessionPricing pricing) {
        this.pricing = pricing;
        this.transport = transport;
        this.microphone = microphone;
        this.speaker = speaker;
        this.statusChanged = statusChanged;
        this.eventsChanged = eventsChanged;
        this.dispatchWorkspaceTools = dispatchWorkspaceTools;
        this.setupTimeout = setupTimeout;
        this.closeTimeout = closeTimeout;
        this.nanoTime = nanoTime;
        this.scheduleTimeout = scheduleTimeout;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized List<EventInspector.Entry> inspectedEvents() {
        return inspector.snapshot();
    }

    public synchronized Duration elapsedTime() {
        if (active == null || closedElapsed != null) {
            return closedElapsed;
        }
        return Duration.ofNanos(nanoTime.getAsLong() - active.startedAt);
    }

    public synchronized Workspace workspace() {
        boolean ready = status.state() == State.READY;
        var input = ready ? active.input : null;
        var micState = ready ? active.micState : MicState.IDLE;
        var output = ready && hasPendingPlayback(active) ? active.output : null;
        var speakerState = ready ? speakerState(active) : SpeakerState.IDLE;
        return new Workspace(status, history.stream().map(ConversationTurn::view).toList(),
                ready && canStartTurn(active),
                ready && (input != null && input.vad == null && !input.discard
                        || active.turn != null && active.turn.state != TurnState.CANCELLING || output != null),
                new MicrophoneView(micState, ready && canStartAudio(active),
                        input == null ? 0 : (double) input.size / SessionProtocol.PCM_BYTES_PER_SECOND,
                        micState == MicState.RECORDING ? input.level : 0),
                new SpeakerView(speakerState, output == null ? 0 : output.level),
                new SettingsView(settings, active == null || ready && canStartTurn(active),
                        ready && active.settingUpdate != null, ready && active.voiceLocked), toyWorkspace,
                active == null ? closedUsage : active.usage.view(), audioLatency);
    }

    private SpeakerState speakerState(Attempt attempt) {
        if (playbackWorker != null && playbackWorker.cleanupDeadline != null) {
            return playbackWorker.cleanupTimedOut ? SpeakerState.CLEANUP_TIMEOUT : SpeakerState.CLEANING_UP;
        }
        return attempt.speakerState;
    }

    private boolean hasPendingPlayback(Attempt attempt) {
        return attempt.output != null && attempt.output.cleanupDeadline == null;
    }

    private boolean canStartTurn(Attempt attempt) {
        // Cleanup can still discover unheard audio that needs truncation.
        return attempt.turn == null && attempt.input == null && attempt.interruption == null
                && attempt.pendingVadTurn == null && (attempt.output == null || attempt.output.drained)
                && !attempt.writing && attempt.settingUpdate == null;
    }

    private boolean canStartAudio(Attempt attempt) {
        if (settings.effectiveTurnDetection().automatic()) {
            return attempt.inputConfigured && attempt.input == null && captureWorker == null
                    && attempt.settingUpdate == null;
        }
        // Stopped playback can still own the speaker while its worker flushes and closes the line.
        return settings.mode() == Mode.AUDIO && canStartTurn(attempt) && attempt.inputConfigured
                && captureWorker == null && playbackWorker == null;
    }

    public synchronized boolean sendText(String input) {
        var attempt = active;
        if (status.state() != State.READY || !canStartTurn(attempt)
                || input == null || input.isBlank() || input.length() > MAX_INPUT_CHARS || !withinSessionTime(attempt)) {
            return false;
        }
        var turn = new ConversationTurn(++turnNumber, input);
        addTurn(attempt, turn);
        startDeadline(attempt, ITEM_ACK_TIMEOUT, Failure.ITEM_ACK_TIMEOUT);
        if (active == attempt) {
            writeTurnEvent(attempt, protocol.createItem(turn));
        }
        statusChanged.run();
        return true;
    }

    private void addTurn(Attempt attempt, ConversationTurn turn) {
        attempt.turn = turn;
        appendHistory(turn);
    }

    private void appendHistory(ConversationTurn turn) {
        if (history.size() == MAX_HISTORY_TURNS) {
            history.removeFirst();
        }
        history.addLast(turn);
    }

    public synchronized void cancelTurn() {
        if (status.state() != State.READY) {
            return;
        }
        if (active.input != null && active.input.vad == null) {
            cancelAudio();
        } else if (hasVoiceToInterrupt(active)) {
            if (withinSessionTime(active)) {
                interruptVoice(active);
            }
        } else {
            cancelText();
        }
    }

    public synchronized void cancelText() {
        var attempt = active;
        if (status.state() != State.READY || attempt.turn == null || attempt.turn.state == TurnState.CANCELLING
                || !withinSessionTime(attempt)) {
            return;
        }
        if (attempt.turn.isVoice()) {
            interruptVoice(attempt);
            return;
        }
        attempt.turn.state = TurnState.CANCELLING;
        attempt.turn.playbackStopped = true;
        stopPlayback(attempt);
        if (attempt.turn.responseRequested) {
            startDeadline(attempt, CANCEL_TIMEOUT, Failure.CANCEL_TIMEOUT);
        }
        if (active == attempt) {
            advanceTurn(attempt);
        }
        statusChanged.run();
    }

    public synchronized boolean startAudio() {
        var attempt = active;
        if (status.state() != State.READY || !canStartAudio(attempt) || !withinSessionTime(attempt)) {
            return false;
        }
        var input = new AudioInput(++turnNumber, settings.effectiveTurnDetection().automatic());
        attempt.input = input;
        captureWorker = input;
        attempt.micState = MicState.OPENING;
        // A device open cannot be interrupted. Detach the take so text and cancel are not blocked behind it.
        var openDeadline = scheduleTimeout.apply(OPEN_TIMEOUT.toNanos());
        input.openDeadline = openDeadline;
        openDeadline.whenComplete((ignored, error) -> {
            if (error != null) {
                openExpired(attempt, input);
            }
        });
        Thread.ofVirtual().name("rvl-microphone").start(() -> {
            var result = MicrophoneCapture.Result.UNAVAILABLE;
            try {
                result = microphone.capture(MAX_AUDIO_TIME, () -> input.stop,
                        () -> captureStarted(attempt, input),
                        (pcm, count) -> captureChunk(attempt, input, pcm, count));
            } finally {
                captureEnded(attempt, input, result);
            }
        });
        statusChanged.run();
        return true;
    }

    public synchronized void releaseAudio() {
        var attempt = active;
        if (status.state() != State.READY || attempt.input == null || attempt.input.vad != null || attempt.input.discard
                || attempt.input.released || !withinSessionTime(attempt)) {
            return;
        }
        var input = attempt.input;
        input.released = true;
        input.stop = true;
        if (captureWorker == input) {
            attempt.micState = MicState.STOPPING;
        } else {
            submitAudio(attempt, input);
        }
        statusChanged.run();
    }

    private synchronized void openExpired(Attempt attempt, AudioInput input) {
        if (active == attempt && attempt.input == input && !input.started && withinSessionTime(attempt)) {
            if (input.vad != null) {
                stopVad(attempt, input, MicState.UNAVAILABLE);
            } else {
                resetInput(attempt, MicState.UNAVAILABLE);
                statusChanged.run();
            }
        }
    }

    private synchronized void captureStarted(Attempt attempt, AudioInput input) {
        input.started = true;
        input.openDeadline.complete(null);
        if (active == attempt && attempt.input == input && !input.stop && withinSessionTime(attempt)) {
            attempt.micState = MicState.RECORDING;
            statusChanged.run();
        }
    }

    private synchronized void captureChunk(Attempt attempt, AudioInput input, byte[] pcm, int count) {
        // A normal release still accepts frames from the capture worker's last buffer snapshot.
        if (active != attempt || attempt.input != input || input.discard || !withinSessionTime(attempt)) {
            return;
        }
        int accepted = Math.min(count, MAX_INPUT_AUDIO_BYTES - input.size);
        if (input.vad != null) {
            if (accepted == 0 || input.stop) {
                return;
            }
            if (input.vad.queuedBytes + accepted > MAX_VAD_BUFFER_BYTES) {
                stopVad(attempt, input, MicState.BACKLOG);
                return;
            }
            input.vad.chunks.addLast(Arrays.copyOf(pcm, accepted));
            input.vad.queuedBytes += accepted;
        } else {
            if (input.pcm == null) {
                input.pcm = ByteBuffer.allocate(MAX_INPUT_AUDIO_BYTES);
            }
            input.pcm.put(pcm, 0, accepted);
        }
        int previousSize = input.size;
        input.size += accepted;
        int peak = 0;
        for (int index = 0; index < accepted; index += Short.BYTES) {
            int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        input.level = peak / 32768.0;
        if (input.size >= MAX_INPUT_AUDIO_BYTES) {
            input.stop = true;
        }
        if (input.vad != null) {
            advanceVad(attempt);
        }
        // Small device reads can arrive every poll. Keep meter updates at one per 40 ms of PCM.
        if (input.size / MicrophoneCapture.BLOCK_BYTES != previousSize / MicrophoneCapture.BLOCK_BYTES) {
            statusChanged.run();
        }
    }

    private synchronized void captureEnded(Attempt attempt, AudioInput input, MicrophoneCapture.Result result) {
        captureWorker = null;
        input.openDeadline.complete(null);
        if (active == attempt && attempt.input == input && withinSessionTime(attempt)) {
            if (input.vad != null) {
                if (!input.discard) {
                    stopVad(attempt, input, switch (result) {
                        case UNSUPPORTED -> MicState.UNSUPPORTED;
                        case UNAVAILABLE -> MicState.UNAVAILABLE;
                        case LIMIT, STOPPED -> MicState.LIMIT;
                    });
                }
            } else if (input.discard) {
                resetInput(attempt, MicState.IDLE);
            } else if (result == MicrophoneCapture.Result.UNSUPPORTED) {
                resetInput(attempt, MicState.UNSUPPORTED);
            } else if (result == MicrophoneCapture.Result.UNAVAILABLE
                    && !(input.released && input.size >= MIN_INPUT_AUDIO_BYTES)) {
                // A device error after release still sends the audio already captured.
                resetInput(attempt, MicState.UNAVAILABLE);
            } else if (input.released) {
                submitAudio(attempt, input);
            } else {
                attempt.micState = MicState.LIMIT;
            }
        }
        statusChanged.run();
    }

    private void submitAudio(Attempt attempt, AudioInput input) {
        if (input.size < MIN_INPUT_AUDIO_BYTES) {
            resetInput(attempt, MicState.TOO_SHORT);
            return;
        }
        input.pcm.flip();
        attempt.micState = MicState.SENDING;
        advanceInput(attempt);
    }

    public synchronized void cancelAudio() {
        var attempt = active;
        if (status.state() != State.READY || attempt.input == null || attempt.input.discard
                || !withinSessionTime(attempt)) {
            return;
        }
        var input = attempt.input;
        if (input.vad != null) {
            stopVad(attempt, input, MicState.IDLE);
            return;
        }
        input.discard = true;
        input.stop = true;
        input.pcm = null;
        attempt.micState = MicState.STOPPING;
        if (captureWorker != input) {
            advanceInput(attempt);
        }
        statusChanged.run();
    }

    public synchronized boolean stopListening() {
        var attempt = active;
        if (status.state() == State.READY && attempt.input != null && attempt.input.vad != null
                && !attempt.input.discard && withinSessionTime(attempt)) {
            stopVad(attempt, attempt.input, MicState.IDLE);
            return true;
        }
        return false;
    }

    private void stopVad(Attempt attempt, AudioInput input, MicState finalState) {
        input.discard = true;
        input.stop = true;
        input.vad.chunks.clear();
        input.vad.queuedBytes = 0;
        input.vad.finalState = finalState;
        attempt.micState = MicState.STOPPING;
        advanceVad(attempt);
        statusChanged.run();
    }

    private void advanceVad(Attempt attempt) {
        var input = attempt.input;
        if (input == null || input.vad == null || attempt.pumpingAudio) {
            return;
        }
        attempt.pumpingAudio = true;
        try {
            while (active == attempt && attempt.input == input && !attempt.writing
                    && status.state() == State.READY && !input.cleanupSent && withinSessionTime(attempt)) {
                if (input.discard) {
                    if (input.sentChunks == 0) {
                        finishVad(attempt);
                    } else {
                        input.cleanupSent = true;
                        input.vad.clearDeadline = scheduleTimeout.apply(CANCEL_TIMEOUT.toNanos());
                        input.vad.clearDeadline.whenComplete((ignored, error) -> {
                            synchronized (RealtimeSession.this) {
                                if (error != null && active == attempt && attempt.input == input) {
                                    fail(attempt, Failure.CANCEL_TIMEOUT);
                                }
                            }
                        });
                        if (active == attempt) {
                            writeTurnEvent(attempt, protocol.clearAudio(input.eventPrefix + "clear"));
                        }
                    }
                } else if (input.vad.queuedBytes >= AUDIO_APPEND_BYTES) {
                    // Batch device reads like push to talk so appends do not flood the socket and inspector.
                    var batch = new java.io.ByteArrayOutputStream(AUDIO_APPEND_BYTES);
                    do {
                        batch.writeBytes(input.vad.chunks.removeFirst());
                    } while (!input.vad.chunks.isEmpty()
                            && batch.size() + input.vad.chunks.getFirst().length <= AUDIO_APPEND_BYTES);
                    var chunk = batch.toByteArray();
                    input.vad.queuedBytes -= chunk.length;
                    writeTurnEvent(attempt, protocol.appendAudio(input.eventPrefix + ++input.sentChunks, chunk));
                } else {
                    break;
                }
            }
        } finally {
            attempt.pumpingAudio = false;
        }
    }

    private void finishVad(Attempt attempt) {
        var input = attempt.input;
        attempt.input = null;
        attempt.micState = input.vad.finalState;
        if (input.vad.clearDeadline != null) {
            input.vad.clearDeadline.complete(null);
        }
        advanceVadTurn(attempt);
        statusChanged.run();
    }

    private void advanceVadTurn(Attempt attempt) {
        var input = attempt.input;
        if (status.state() == State.READY && attempt.turn == null && attempt.interruption == null
                && attempt.output == null && !attempt.writing && attempt.settingUpdate == null
                && (input == null || input.vad != null && input.vad.speechItemId == null && !input.discard)
                && attempt.pendingVadTurn != null) {
            attempt.turn = attempt.pendingVadTurn;
            attempt.pendingVadTurn = null;
            attempt.speakerState = SpeakerState.IDLE;
            advanceTurn(attempt);
        }
    }

    private void advanceInput(Attempt attempt) {
        var input = attempt.input;
        if (input == null || attempt.pumpingAudio || attempt.writing || captureWorker == input
                || input.committing || input.cleanupSent) {
            return;
        }
        // A WebSocket write may complete inline. Let the outer loop drain it without recursion.
        attempt.pumpingAudio = true;
        try {
            while (active == attempt && attempt.input == input && status.state() == State.READY
                    && !attempt.writing && !input.committing && !input.cleanupSent && withinSessionTime(attempt)) {
                if (input.discard) {
                    discardUploadedAudio(attempt, input);
                } else if (input.committedItemId != null) {
                    startAudioResponse(attempt, input);
                } else if (input.pcm.hasRemaining()) {
                    var chunk = new byte[Math.min(input.pcm.remaining(), AUDIO_APPEND_BYTES)];
                    input.pcm.get(chunk);
                    // Bound each write rather than the whole upload, so long takes on slow links can finish.
                    startDeadline(attempt, ITEM_ACK_TIMEOUT, Failure.ITEM_ACK_TIMEOUT);
                    if (active == attempt) {
                        writeTurnEvent(attempt, protocol.appendAudio(input.eventPrefix + ++input.sentChunks, chunk));
                    }
                } else {
                    input.pcm = null;
                    input.committing = true;
                    startDeadline(attempt, ITEM_ACK_TIMEOUT, Failure.ITEM_ACK_TIMEOUT);
                    if (active == attempt) {
                        writeTurnEvent(attempt, protocol.commitAudio(input.eventPrefix + "commit"));
                    }
                }
            }
        } finally {
            attempt.pumpingAudio = false;
        }
    }

    private void startAudioResponse(Attempt attempt, AudioInput input) {
        resetInput(attempt, MicState.IDLE);
        attempt.speakerState = SpeakerState.IDLE;
        var turn = input.turn;
        turn.itemAcknowledged = true;
        addTurn(attempt, turn);
        advanceTurn(attempt);
        statusChanged.run();
    }

    private void discardUploadedAudio(Attempt attempt, AudioInput input) {
        if (input.sentChunks == 0) {
            resetInput(attempt, MicState.IDLE);
            return;
        }
        // Acknowledged commits have moved from the input buffer to conversation history.
        input.cleanupSent = true;
        startDeadline(attempt, CANCEL_TIMEOUT, Failure.CANCEL_TIMEOUT);
        if (active == attempt) {
            var event = input.committedItemId == null
                    ? protocol.clearAudio(input.eventPrefix + "clear")
                    : protocol.deleteItem(input.eventPrefix + "delete", input.committedItemId);
            writeTurnEvent(attempt, event);
        }
    }

    private void resetInput(Attempt attempt, MicState state) {
        attempt.micState = state;
        var input = attempt.input;
        if (input == null) {
            return;
        }
        input.stop = true;
        input.pcm = null;
        if (input.vad != null) {
            input.vad.chunks.clear();
            if (input.vad.clearDeadline != null) {
                input.vad.clearDeadline.complete(null);
            }
        }
        attempt.input = null;
        if (!attempt.writing) {
            clearPhaseDeadline(attempt);
        }
    }

    public synchronized void connect(String model) {
        connect(model, TranscriptionModel.OFF);
    }

    public synchronized void connect(String model, TranscriptionModel transcriptionModel) {
        if (disposed || active != null) {
            return;
        }
        var allowedModel = Arrays.stream(Model.values()).filter(value -> value.id().equals(model))
                .findFirst().orElse(null);
        if (allowedModel == null) {
            publish(State.ERROR, Failure.SETTINGS_REJECTED);
            return;
        }
        settings = new SessionSettings(allowedModel, settings.voice(), settings.mode(), settings.instructions(),
                settings.turnDetection(), settings.toolPreset());
        connectSelected(transcriptionModel);
    }

    public synchronized void connectSelected(TranscriptionModel transcriptionModel) {
        if (disposed || active != null) {
            return;
        }

        Objects.requireNonNull(transcriptionModel);
        if (!compatibleInput(settings, transcriptionModel)) {
            publish(State.ERROR, Failure.TRANSCRIPTION_CONFLICT);
            return;
        }
        var rates = pricing.models().get(settings.model());
        if (pricing.invalidCutoff() || pricing.cutoff() != null && rates == null) {
            publish(State.ERROR, Failure.SPEND_CONFIGURATION);
            return;
        }
        var attempt = new Attempt(transcriptionModel, rates);
        closedUsage = null;
        closedElapsed = null;
        audioLatency = null;
        history.clear();
        active = attempt;
        attempt.sessionDeadline = scheduleTimeout.apply(MAX_SESSION_TIME.toNanos());
        startDeadline(attempt, setupTimeout, Failure.TIMEOUT);
        if (active != attempt) {
            return;
        }
        attempt.sessionDeadline.whenComplete((ignored, error) -> {
            if (error != null) {
                sessionExpired(attempt);
            }
        });
        publish(State.CONNECTING, null);
        if (active != attempt || !withinSessionTime(attempt)) {
            return;
        }
        try {
            attempt.opening = transport.open(settings.model().id(), attempt);
            attempt.opening.whenComplete((socket, error) -> {
                if (error != null) {
                    fail(attempt, connectionFailure(error));
                }
            });
        } catch (RuntimeException error) {
            fail(attempt, connectionFailure(error));
        }
    }

    public synchronized boolean configure(SessionSettings requested, State expectedState) {
        Objects.requireNonNull(requested);
        if (disposed || status.state() != expectedState) {
            return false;
        }
        var attempt = active;
        if (attempt == null) {
            settings = requested;
            statusChanged.run();
            return true;
        }
        if (status.state() != State.READY || !canStartTurn(attempt) || requested.model() != settings.model()
                || requested.toolPreset() != settings.toolPreset()
                || attempt.voiceLocked && requested.voice() != settings.voice() || !withinSessionTime(attempt)) {
            return false;
        }
        if (!compatibleInput(requested, attempt.transcriptionModel)) {
            return false;
        }
        if (!requested.equals(settings)) {
            sendConfiguration(attempt, requested);
        }
        return true;
    }

    private boolean compatibleInput(SessionSettings requested, TranscriptionModel transcription) {
        // The Realtime Whisper model requires manual turn boundaries.
        return transcription != TranscriptionModel.REALTIME_WHISPER || !requested.effectiveTurnDetection().automatic();
    }

    public synchronized void disconnect() {
        var attempt = active;
        if (attempt == null || status.state() == State.CLOSING || !withinSessionTime(attempt)) {
            return;
        }

        if (status.state() == State.CONNECTING) {
            finish(attempt, null, true);
            return;
        }

        beginClose(attempt, null);
    }

    private void beginClose(Attempt attempt, Failure limit) {
        if (attempt.settingUpdate != null && !attempt.settingUpdate.written) {
            finish(attempt, limit, true);
            return;
        }
        attempt.sessionDeadline.complete(null);
        attempt.closeFailure = limit;
        closedElapsed = elapsedTime();
        boolean generationPending = attempt.usage.hasPending();
        attempt.usage.close();
        resetInput(attempt, MicState.IDLE);
        stopPlayback(attempt);
        // OUTPUT_LIMIT arrives after the provider has already stopped generation.
        boolean spendStop = limit == Failure.SPEND_LIMIT || limit == Failure.SPEND_ESTIMATE_UNAVAILABLE;
        attempt.cancelBeforeClose = limit != Failure.OUTPUT_LIMIT
                && (attempt.turn == null || !attempt.turn.cancelSent)
                && (spendStop ? generationPending : limit != null || generationPending);
        finishTurn(attempt, TurnState.INTERRUPTED);
        finishPendingVadTurn(attempt);
        startDeadline(attempt, closeTimeout, limit);
        if (active != attempt) {
            return;
        }
        publish(State.CLOSING, null);
        if (active == attempt && !attempt.writing) {
            closeSocket(attempt);
        }
    }

    private void closeSocket(Attempt attempt) {
        if (!attempt.cancelBeforeClose) {
            sendClose(attempt);
            return;
        }
        try {
            sendEvent(attempt, SessionProtocol.CANCEL_RESPONSE)
                    .whenComplete((socket, error) -> cancelWritten(attempt, error));
        } catch (RuntimeException error) {
            fail(attempt, attempt.closeFailure);
        }
    }

    private synchronized void cancelWritten(Attempt attempt, Throwable error) {
        if (active != attempt) {
            return;
        }
        if (error != null) {
            fail(attempt, attempt.closeFailure);
            return;
        }
        sendClose(attempt);
    }

    private void sendClose(Attempt attempt) {
        try {
            attempt.socket.sendClose(WebSocket.NORMAL_CLOSURE, "")
                    .whenComplete((socket, error) -> closeWritten(attempt, error));
        } catch (RuntimeException error) {
            fail(attempt, Failure.CONNECTION);
        }
    }

    private synchronized void closeWritten(Attempt attempt, Throwable error) {
        if (active != attempt) {
            return;
        }
        if (error != null) {
            fail(attempt, Failure.CONNECTION);
            return;
        }
        attempt.closeSent = true;
        checkClosed(attempt);
    }

    private void checkClosed(Attempt attempt) {
        // Any response to a client-initiated close completes it.
        if (attempt.closeSent && attempt.peerCloseCode != null) {
            finish(attempt, attempt.closeFailure, false);
        }
    }

    private void finishPeerClose(Attempt attempt, int statusCode) {
        finish(attempt, statusCode == WebSocket.NORMAL_CLOSURE ? null : Failure.REMOTE_CLOSE, false);
    }

    @Override
    public synchronized void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        history.clear();
        inspector.clear();
        if (active != null) {
            finish(active, null, true);
        }
        transport.close();
    }

    private void publish(State state, Failure failure) {
        status = new Status(state, failure);
        statusChanged.run();
    }

    private static CompletableFuture<Void> timeoutAfter(long nanos) {
        return new CompletableFuture<Void>().orTimeout(nanos, TimeUnit.NANOSECONDS);
    }

    private void startDeadline(Attempt attempt, Duration timeout, Failure onExpiry) {
        clearPhaseDeadline(attempt);
        var deadline = scheduleTimeout.apply(timeout.toNanos());
        attempt.phaseDeadline = deadline;
        deadline.whenComplete((ignored, error) -> {
            if (error != null) {
                phaseExpired(attempt, deadline, onExpiry);
            }
        });
    }

    private synchronized void phaseExpired(Attempt attempt, CompletableFuture<Void> deadline, Failure failure) {
        if (active == attempt && attempt.phaseDeadline == deadline) {
            fail(attempt, failure);
        }
    }

    private void clearPhaseDeadline(Attempt attempt) {
        var deadline = attempt.phaseDeadline;
        attempt.phaseDeadline = null;
        if (deadline != null) {
            deadline.complete(null);
        }
    }

    private synchronized void sessionExpired(Attempt attempt) {
        // The timeout may have completed while its callback was waiting for this monitor.
        if (active == attempt && status.state() != State.CLOSING) {
            stopAtLimit(attempt, Failure.SESSION_LIMIT);
        }
    }

    // Also enforce the time at the point of use if the scheduled callback has not run yet.
    private boolean withinSessionTime(Attempt attempt) {
        return withinSessionTime(attempt, nanoTime.getAsLong());
    }

    private boolean withinSessionTime(Attempt attempt, long observedAt) {
        if (observedAt - attempt.startedAt < MAX_SESSION_TIME.toNanos()) {
            return true;
        }
        stopAtLimit(attempt, Failure.SESSION_LIMIT);
        return false;
    }

    private boolean withinToolOutputTime(Attempt attempt, ToolExchange exchange, long observedAt) {
        if (!exchange.expired(observedAt, TOOL_OUTPUT_TIMEOUT.toNanos())) {
            return true;
        }
        fail(attempt, Failure.ITEM_ACK_TIMEOUT);
        return false;
    }

    private void stopAtLimit(Attempt attempt, Failure limit) {
        if (status.state() == State.READY) {
            beginClose(attempt, limit);
        } else {
            fail(attempt, limit);
        }
    }

    private synchronized void fail(Attempt attempt, Failure failure) {
        if (active == attempt) {
            finish(attempt, attempt.closeFailure != null ? attempt.closeFailure : failure, true);
        }
    }

    private void finish(Attempt attempt, Failure failure, boolean abort) {
        closedElapsed = elapsedTime();
        attempt.usage.close();
        closedUsage = attempt.usage.view();
        active = null;
        clearPhaseDeadline(attempt);
        attempt.sessionDeadline.complete(null);
        attempt.message = null;
        resetInput(attempt, MicState.IDLE);
        stopPlayback(attempt);
        finishTurn(attempt, TurnState.INTERRUPTED);
        finishPendingVadTurn(attempt);
        history.forEach(ConversationTurn::abandonInputTranscript);
        if (abort) {
            if (attempt.socket != null) {
                attempt.socket.abort();
            }
            if (attempt.opening != null) {
                attempt.opening.cancel(true);
            }
        }
        publish(failure == null ? State.DISCONNECTED : State.ERROR, failure);
    }

    private Failure connectionFailure(Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        if (failure instanceof RealtimeTransport.MissingCredential) {
            return Failure.MISSING_CREDENTIAL;
        }
        if (failure instanceof WebSocketHandshakeException handshake) {
            int code = handshake.getResponse().statusCode();
            if (code == 401 || code == 403) {
                return Failure.AUTHENTICATION;
            }
        }
        if (failure instanceof HttpTimeoutException) {
            return Failure.TIMEOUT;
        }
        return Failure.CONNECTION;
    }

    private void receive(Attempt attempt, CharSequence fragment, boolean last) throws IOException {
        if (attempt.discardingMessage) {
            attempt.discardingMessage = !last;
            checkSessionTime(attempt);
            return;
        }
        int buffered = attempt.message == null ? 0 : attempt.message.length();
        if (fragment.length() > SessionProtocol.MAX_MESSAGE_CHARS - buffered) {
            // Closing sockets drain the rest of an oversized message so its tail cannot start a new one.
            attempt.message = null;
            attempt.discardingMessage = !last;
            rejectIncoming(attempt, new IOException("Realtime message limit exceeded"));
            return;
        }
        String message;
        if (last && attempt.message == null) {
            message = fragment.toString();
        } else {
            if (attempt.message == null) {
                attempt.message = new StringBuilder();
            }
            attempt.message.append(fragment);
            if (!last) {
                // No inspector row precedes a partial frame, so the limit can stop the session here.
                checkSessionTime(attempt);
                return;
            }
            message = attempt.message.toString();
            attempt.message = null;
        }

        SessionProtocol.Event event;
        try {
            event = protocol.parse(message);
        } catch (IOException invalidEvent) {
            rejectIncoming(attempt, invalidEvent);
            return;
        }
        if ("response.output_audio.delta".equals(event.type())) {
            receiveAudioEvent(attempt, event);
            return;
        }
        inspectIncoming(event, null);
        if (!admitIncoming(attempt)) {
            return;
        }
        switch (event.type()) {
            case "error" -> {
                var error = protocol.textEvent(message).path("error");
                var id = error.path("event_id").textValue();
                var pending = attempt.settingUpdate;
                if (pending != null && pending.eventId.equals(id)) {
                    fail(attempt, Failure.SETTINGS_REJECTED);
                } else if (status.state() == State.CONNECTING || pending != null && id == null) {
                    // Setup cannot complete after any error, and an uncorrelated one leaves a live update uncertain.
                    fail(attempt, Failure.PROVIDER);
                } else if (attempt.interruption != null && id != null && id.equals(attempt.interruption.eventId)) {
                    fail(attempt, Failure.PROVIDER);
                } else if (attempt.input != null && attempt.input.vad != null && attempt.input.sentChunks > 0
                        && id != null && id.startsWith(attempt.input.eventPrefix)) {
                    fail(attempt, Failure.PROVIDER);
                } else if (attempt.turn != null || (attempt.input != null && attempt.input.sentChunks > 0)) {
                    if (attempt.turn != null) {
                        turnError(attempt, error);
                    } else {
                        if (id == null || id.startsWith(attempt.input.eventPrefix)) {
                            // A partial or rejected upload leaves the provider buffer uncertain.
                            fail(attempt, Failure.PROVIDER);
                        }
                    }
                }
            }
            case "session.created" -> {
                if (status.state() == State.CONNECTING && attempt.sessionId == null) {
                    attempt.sessionId = event.configuration().sessionId();
                    sendConfiguration(attempt, settings);
                }
            }
            case "session.updated" -> {
                acknowledgeSettings(attempt, event.configuration());
            }
            case "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped" ->
                    receiveSpeech(attempt, event);
            case "conversation.item.truncated" -> acknowledgeCut(attempt, protocol.textEvent(message));
            case "input_audio_buffer.committed" -> {
                if (status.state() == State.READY && attempt.usage.audioSubmitted()) {
                    statusChanged.run();
                }
                var input = attempt.input;
                if (input != null && input.vad != null) {
                    vadCommitted(attempt, input, event.itemId());
                } else if (input != null && input.committing) {
                    var itemId = protocol.requiredId(event.itemId());
                    if (history.stream().anyMatch(turn -> itemId.equals(turn.audioItemId))) {
                        return;
                    }
                    // Keep the item cancellable until the commit write completes and response creation can start.
                    input.committing = false;
                    input.committedItemId = itemId;
                    if (!input.discard) {
                        input.turn = new ConversationTurn(input.number, itemId,
                                attempt.transcriptionModel != TranscriptionModel.OFF);
                    }
                    advanceInput(attempt);
                }
            }
            case "conversation.item.input_audio_transcription.completed",
                 "conversation.item.input_audio_transcription.failed" -> receiveInputTranscript(attempt, event);
            case "input_audio_buffer.cleared" -> {
                var input = attempt.input;
                if (input != null && input.cleanupSent && input.vad != null) {
                    finishVad(attempt);
                } else if (input != null && input.cleanupSent && input.committedItemId == null) {
                    resetInput(attempt, MicState.IDLE);
                    statusChanged.run();
                }
            }
            case "conversation.item.deleted" -> {
                var input = attempt.input;
                if (input != null && input.cleanupSent && input.committedItemId != null
                        && input.committedItemId.equals(protocol.textEvent(message).path("item_id").textValue())) {
                    resetInput(attempt, MicState.IDLE);
                    statusChanged.run();
                }
            }
            case "response.function_call_arguments.done" -> {
                if (status.state() == State.READY) {
                    var signal = protocol.textEvent(message);
                    var responseId = signal.path("response_id").textValue();
                    var callId = signal.path("call_id").textValue();
                    // These signals may also come from cancelled responses. Count them without dispatching.
                    if (!attempt.toolCalls.recordSignal(
                            responseId != null && responseId.length() <= SessionProtocol.MAX_ID_CHARS
                                    ? responseId : null,
                            localTools.validCallId(callId) ? callId : null)) {
                        stopAtLimit(attempt, Failure.TOOL_LIMIT);
                    }
                }
            }
            case "response.output_audio.done", "response.output_audio_transcript.delta",
                 "response.output_audio_transcript.done" -> {
                if (status.state() == State.READY && isVoiceResponse(attempt.turn, event.responseId())
                        && !attempt.turn.playbackStopped) {
                    receiveVoiceEvent(attempt.turn, event);
                }
            }
            case "response.done" -> {
                if (status.state() == State.READY) {
                    responseDone(attempt, protocol.textEvent(message).path("response"));
                }
            }
            case "response.created" -> {
                if (status.state() == State.READY) {
                    responseCreated(attempt, protocol.textEvent(message).path("response"));
                }
            }
            case "rate_limits.updated" -> {
                if (status.state() == State.READY) {
                    attempt.usage.updateRateLimits(event.eventId(), protocol.textEvent(message).path("rate_limits"));
                    statusChanged.run();
                }
            }
            case "conversation.item.added" -> {
                if (status.state() == State.READY && attempt.turn != null) {
                    receiveItemAdded(attempt, protocol.textEvent(message).path("item"));
                }
            }
            case "response.output_text.delta" -> {
                var turn = attempt.turn;
                if (status.state() == State.READY && turn != null && !turn.isVoice()
                        && event.responseId().equals(turn.responseId)) {
                    turn.append(event.part());
                    statusChanged.run();
                }
            }
            default -> {
                // Future GA events do not participate in this lifecycle.
            }
        }
    }

    private void receiveSpeech(Attempt attempt, SessionProtocol.Event event) throws IOException {
        var input = attempt.input;
        if (input == null || input.vad == null || input.sentChunks == 0) {
            return;
        }
        var itemId = protocol.requiredId(event.itemId());
        if ("input_audio_buffer.speech_started".equals(event.type())) {
            if (input.vad.speechItemId != null || !attempt.vadSpeechItems.add(itemId)) {
                return;
            }
            if (attempt.vadSpeechItems.size() > MAX_HISTORY_TURNS) {
                attempt.vadSpeechItems.removeFirst();
            }
            input.vad.speechItemId = itemId;
            input.vad.speechStopped = false;
            // A complete turn may already be in flight when the operator stops listening.
            if (input.discard) {
                return;
            }
            if (hasVoiceToInterrupt(attempt)) {
                interruptVoice(attempt);
            } else if (attempt.turn != null && attempt.turn.state != TurnState.CANCEL_REJECTED) {
                cancelText();
            }
        } else if (itemId.equals(input.vad.speechItemId)) {
            input.vad.speechStopped = true;
        }
    }

    private void vadCommitted(Attempt attempt, AudioInput input, String value) throws IOException {
        var itemId = protocol.requiredId(value);
        if (!itemId.equals(input.vad.speechItemId) || !input.vad.speechStopped) {
            return;
        }
        input.vad.speechItemId = null;
        // Both committed inputs are already in context. Respond once to the latest one.
        finishPendingVadTurn(attempt);
        var turn = new ConversationTurn(++turnNumber, itemId, attempt.transcriptionModel != TranscriptionModel.OFF);
        turn.itemAcknowledged = true;
        appendHistory(turn);
        attempt.pendingVadTurn = turn;
        advanceVadTurn(attempt);
        statusChanged.run();
    }

    private void finishPendingVadTurn(Attempt attempt) {
        if (attempt.pendingVadTurn != null) {
            attempt.pendingVadTurn.finish(TurnState.INTERRUPTED);
            attempt.pendingVadTurn = null;
        }
    }

    private void receiveInputTranscript(Attempt attempt, SessionProtocol.Event event) {
        if (status.state() != State.READY || attempt.transcriptionModel == TranscriptionModel.OFF
                || event.itemId() == null || event.part().contentIndex() != 0) {
            return;
        }
        var transcript = "conversation.item.input_audio_transcription.completed".equals(event.type())
                ? event.part().text() : null;
        // The commit acknowledgment can arrive while its write is still pending.
        var input = attempt.input;
        if (input != null && !input.discard && input.turn != null
                && input.turn.finishInputTranscript(event.itemId(), transcript)) {
            return;
        }
        for (var turn : history) {
            if (turn.finishInputTranscript(event.itemId(), transcript)) {
                statusChanged.run();
                return;
            }
        }
    }

    // The provider may report a normalized model name, so only the enabled or disabled state must match.
    private static boolean transcriptionConfirmed(TranscriptionModel requested, String acknowledged) {
        return requested == TranscriptionModel.OFF
                ? "".equals(acknowledged)
                : acknowledged != null && !acknowledged.isBlank();
    }

    private boolean isVoiceResponse(ConversationTurn turn, String responseId) {
        return turn != null && turn.isVoice() && responseId.equals(turn.responseId);
    }

    private void receiveVoiceEvent(ConversationTurn turn, SessionProtocol.Event event) throws IOException {
        switch (event.type()) {
            case "response.output_audio.done" -> turn.finishAudioPart(event.part());
            case "response.output_audio_transcript.delta" -> {
                turn.append(event.part());
                statusChanged.run();
            }
            case "response.output_audio_transcript.done" -> {
                turn.finishTranscriptPart(event.part());
                statusChanged.run();
            }
            default -> throw new IllegalArgumentException("Unexpected Realtime voice event");
        }
    }

    private void inspectIncoming(SessionProtocol.Event event, Integer audioBytes) {
        inspector.incoming(event.type(), event.eventId(), audioBytes);
        eventsChanged.run();
    }

    // Record the frame before limits run so a resulting cancellation follows its cause in the inspector.
    private void rejectIncoming(Attempt attempt, IOException error) throws IOException {
        inspector.invalidIncoming();
        eventsChanged.run();
        if (admitIncoming(attempt)) {
            throw error;
        }
    }

    private boolean admitIncoming(Attempt attempt) {
        return status.state() != State.CLOSING && withinSessionTime(attempt);
    }

    private void checkSessionTime(Attempt attempt) {
        if (status.state() != State.CLOSING) {
            withinSessionTime(attempt);
        }
    }

    private void receiveAudioEvent(Attempt attempt, SessionProtocol.Event event) throws IOException {
        long receivedAt = nanoTime.getAsLong();
        var turn = attempt.turn;
        byte[] pcm;
        try {
            pcm = protocol.decodeAudio(event.encodedAudio());
        } catch (IOException invalidAudio) {
            inspectIncoming(event, null);
            if (admitIncoming(attempt) && status.state() == State.READY
                    && !attempt.usage.isFinished(event.responseId())
                    && !(isVoiceResponse(turn, event.responseId()) && turn.playbackStopped)) {
                throw invalidAudio;
            }
            return;
        }
        inspectIncoming(event, pcm.length);
        if (!admitIncoming(attempt) || status.state() != State.READY) {
            return;
        }
        if (pcm.length != 0) {
            lockVoice(attempt);
        }
        if (active != attempt || attempt.usage.isFinished(event.responseId())) {
            return;
        }
        accountForAudio(attempt, event.responseId(), pcm.length);
        if (status.state() == State.READY && pcm.length != 0 && isVoiceResponse(turn, event.responseId())
                && turn.canPlayPart(event.part()) && attempt.speakerState != SpeakerState.UNAVAILABLE) {
            if (pcm.length % Short.BYTES != 0) {
                throw new IOException("Invalid Realtime PCM frame");
            }
            if (turn.assistantAudio == null) {
                turn.assistantAudio = new AssistantAudio();
            }
            turn.assistantAudio.receive(protocol.requiredId(event.itemId()), event.part().contentIndex(),
                    pcm.length / Short.BYTES);
            if (!turn.firstAudioReceived) {
                turn.firstAudioReceived = true;
                audioLatency = Duration.ofNanos(receivedAt - turn.responseRequestedAt);
                statusChanged.run();
            }
            turn.markResponding();
            queuePlayback(attempt, turn, pcm);
        }
    }

    private void lockVoice(Attempt attempt) {
        if (!attempt.voiceLocked) {
            attempt.voiceLocked = true;
            if (attempt.settingUpdate != null && attempt.settingUpdate.requested.voice() != settings.voice()) {
                fail(attempt, Failure.SETTINGS_MISMATCH);
            } else {
                statusChanged.run();
            }
        }
    }

    private boolean hasVoiceToInterrupt(Attempt attempt) {
        return attempt.turn == null ? hasPendingPlayback(attempt) : attempt.turn.isVoice();
    }

    private void interruptVoice(Attempt attempt) {
        if (attempt.interruption != null) {
            return;
        }
        var output = attempt.output;
        var turn = attempt.turn == null ? output.turn : attempt.turn;
        boolean turnPending = attempt.turn != null;
        boolean generating = turnPending && turn.responseRequested;
        if (output == null && playbackWorker != null && playbackWorker.turn == turn) {
            output = playbackWorker;
        }
        turn.discardAudioTranscript();
        turn.state = turnPending ? TurnState.CANCELLING : TurnState.INTERRUPTED;
        stopVoiceOutput(attempt, turn, output, generating);
        // The tool deadline still applies while an output write or acknowledgment is outstanding.
        if (turn.toolExchange == null) {
            startDeadline(attempt, CANCEL_TIMEOUT, Failure.CANCEL_TIMEOUT);
        }
        if (active == attempt) {
            if (turnPending) {
                advanceTurn(attempt);
            } else {
                advanceInterruption(attempt);
            }
        }
        statusChanged.run();
    }

    private void stopVoiceOutput(Attempt attempt, ConversationTurn turn, AudioOutput output, boolean generating) {
        attempt.interruption = new Interruption(turn, output, generating);
        turn.playbackStopped = true;
        stopPlayback(attempt);
    }

    private void advanceInterruption(Attempt attempt) {
        var interruption = attempt.interruption;
        if (status.state() != State.READY || interruption == null || attempt.writing || !interruption.generationDone
                || interruption.turn.toolExchange != null || interruption.pending != null
                || !withinSessionTime(attempt)) {
            return;
        }
        if (interruption.output != null && interruption.output == playbackWorker) {
            // A stalled device close is reported as speaker cleanup. Bound only provider round trips.
            clearPhaseDeadline(attempt);
            return;
        }
        if (interruption.cuts == null) {
            long playedFrames = interruption.output == null
                    ? interruption.turn.playedFrames : interruption.output.stream.playedFrames();
            interruption.cuts = interruption.audio.cuts(playedFrames, interruption.interruptedGeneration);
            if (!interruption.cuts.isEmpty()) {
                interruption.turn.discardAudioTranscript();
                // Keep a fully heard first response from a tool follow-up. The provider keeps it too.
                if (interruption.cuts.stream().anyMatch(cut -> interruption.audio.isCompleted(cut.itemId()))) {
                    interruption.turn.discardEarlierText();
                }
            }
        }
        interruption.pending = interruption.cuts.pollFirst();
        if (interruption.pending == null) {
            interruption.turn.assistantAudio = null;
            attempt.interruption = null;
            clearPhaseDeadline(attempt);
            advanceVadTurn(attempt);
        } else {
            var cut = interruption.pending;
            interruption.eventId = interruption.turn.id + "-truncate-" + ++interruption.sequence;
            startDeadline(attempt, CANCEL_TIMEOUT, Failure.CANCEL_TIMEOUT);
            writeTurnEvent(attempt, protocol.truncateAudio(interruption.eventId, cut.itemId(), cut.milliseconds()));
        }
        statusChanged.run();
    }

    private void acknowledgeCut(Attempt attempt, JsonNode event) {
        var interruption = attempt.interruption;
        var cut = interruption == null ? null : interruption.pending;
        var index = event.path("content_index");
        var end = event.path("audio_end_ms");
        if (cut != null && cut.itemId().equals(event.path("item_id").textValue())
                && (index.isMissingNode() || index.isIntegralNumber() && index.canConvertToInt() && index.intValue() == 0)
                // The provider may round the cut down. It never keeps more than was requested.
                && end.isIntegralNumber() && end.canConvertToInt()
                && end.intValue() >= 0 && end.intValue() <= cut.milliseconds()) {
            interruption.pending = null;
            advanceInterruption(attempt);
        }
    }

    private void queuePlayback(Attempt attempt, ConversationTurn turn, byte[] pcm) {
        if (attempt.output != null) {
            if (hasPendingPlayback(attempt) && !attempt.output.stream.offer(pcm)) {
                playbackUnavailable(attempt);
            }
            return;
        }
        var output = new AudioOutput(turn);
        output.stream.offer(pcm);
        attempt.output = output;
        attempt.speakerState = SpeakerState.OPENING;
        output.openDeadline = scheduleTimeout.apply(OPEN_TIMEOUT.toNanos());
        output.openDeadline.whenComplete((ignored, error) -> {
            if (error != null) {
                playbackOpenExpired(attempt, output);
            }
        });
        startPlaybackIfAvailable();
        statusChanged.run();
    }

    private void startPlaybackIfAvailable() {
        // A previous device open or close may still be unwinding. Keep one speaker owner.
        var attempt = active;
        if (playbackWorker != null || attempt == null || attempt.output == null) {
            return;
        }
        var output = attempt.output;
        playbackWorker = output;
        Thread.ofVirtual().name("rvl-speaker").start(() -> {
            var result = SpeakerPlayback.Result.UNAVAILABLE;
            try {
                result = speaker.play(output.stream, () -> playbackStarted(attempt, output),
                        level -> playbackLevel(attempt, output, level), resultAtCleanup ->
                                playbackCleaningUp(output, resultAtCleanup));
            } finally {
                playbackEnded(attempt, output, result);
            }
        });
    }

    private synchronized void playbackOpenExpired(Attempt attempt, AudioOutput output) {
        if (active == attempt && attempt.output == output && hasPendingPlayback(attempt)
                && withinSessionTime(attempt)) {
            playbackUnavailable(attempt);
        }
    }

    private synchronized void playbackStarted(Attempt attempt, AudioOutput output) {
        if (active == attempt && attempt.output == output && withinSessionTime(attempt)) {
            if (output.openDeadline.complete(null)) {
                attempt.speakerState = SpeakerState.SPEAKING;
                statusChanged.run();
            } else {
                playbackUnavailable(attempt);
            }
        }
    }

    private synchronized void playbackLevel(Attempt attempt, AudioOutput output, double level) {
        if (active == attempt && attempt.output == output && output.level != level) {
            output.level = level;
            statusChanged.run();
        }
    }

    private synchronized void playbackQueueEmpty(Attempt attempt, AudioOutput output) {
        if (active == attempt && attempt.output == output && status.state() == State.READY) {
            advanceTurn(attempt);
        }
    }

    private synchronized void playbackCleaningUp(AudioOutput output, SpeakerPlayback.Result result) {
        output.drained = result == SpeakerPlayback.Result.COMPLETED;
        if (watchPlaybackCleanup(output)) {
            output.openDeadline.complete(null);
            if (status.state() == State.READY) {
                statusChanged.run();
            }
        }
    }

    private synchronized void playbackEnded(Attempt attempt, AudioOutput output, SpeakerPlayback.Result result) {
        if (playbackWorker != output) {
            return;
        }
        playbackWorker = null;
        output.turn.playedFrames = output.stream.playedFrames();
        if (output.cleanupDeadline != null) {
            output.cleanupDeadline.complete(null);
        }
        if (active == attempt && attempt.output == output) {
            if (result == SpeakerPlayback.Result.UNAVAILABLE) {
                playbackUnavailable(attempt);
            } else {
                stopPlayback(attempt);
                statusChanged.run();
            }
        } else if (status.state() == State.READY) {
            statusChanged.run();
        }
        if (attempt.turn != output.turn) {
            output.turn.assistantAudio = null;
        }
        if (active == attempt) {
            advanceInterruption(attempt);
            advanceVadTurn(attempt);
        }
        startPlaybackIfAvailable();
    }

    private void playbackUnavailable(Attempt attempt) {
        var output = attempt.output;
        if (output != null && !output.drained
                && (attempt.turn == null || attempt.turn == output.turn)
                && (attempt.turn != null || playbackWorker == output
                    || !output.turn.assistantAudio.cuts(output.turn.playedFrames, false).isEmpty())) {
            // Keep the failed output's item and frame count until the provider confirms truncation.
            interruptVoice(attempt);
        } else {
            stopPlayback(attempt);
        }
        attempt.speakerState = SpeakerState.UNAVAILABLE;
        statusChanged.run();
    }

    private void stopPlayback(Attempt attempt) {
        var output = attempt.output;
        attempt.output = null;
        attempt.speakerState = SpeakerState.IDLE;
        if (output != null) {
            output.stream.stop();
            output.openDeadline.complete(null);
            watchPlaybackCleanup(output);
        }
    }

    private boolean watchPlaybackCleanup(AudioOutput output) {
        if (playbackWorker != output || output.cleanupDeadline != null) {
            return false;
        }
        var cleanup = scheduleTimeout.apply(SPEAKER_CLEANUP_TIMEOUT.toNanos());
        output.cleanupDeadline = cleanup;
        cleanup.whenComplete((ignored, error) -> {
            if (error != null) {
                playbackCleanupExpired(output);
            }
        });
        return true;
    }

    private synchronized void playbackCleanupExpired(AudioOutput output) {
        if (playbackWorker != output) {
            return;
        }
        // A slow native call still owns the line. Only the worker's return can release it.
        output.cleanupTimedOut = true;
        if (status.state() == State.READY) {
            statusChanged.run();
        }
    }

    private void accountForAudio(Attempt attempt, String responseId, int bytes) throws IOException {
        // Each response has its own budget. The number tracked at once stays bounded.
        if (!attempt.audioBytes.containsKey(responseId) && attempt.audioBytes.size() >= MAX_AUDIO_RESPONSES) {
            throw new IOException("Too many concurrent audio responses");
        }
        long total = attempt.audioBytes.merge(responseId, (long) bytes, Long::sum);
        if (total > MAX_OUTPUT_AUDIO_BYTES) {
            stopAtLimit(attempt, Failure.AUDIO_LIMIT);
        }
    }

    private void responseDone(Attempt attempt, JsonNode response) throws IOException {
        var id = protocol.requiredId(response.path("id").textValue());
        var turn = attempt.turn;
        boolean matchesRequest = turn != null && matchesUnboundResponse(turn, response);
        if (!attempt.usage.responseDone(id, response.path("usage"), matchesRequest)) {
            return;
        }
        attempt.audioBytes.remove(id);
        var outcome = response.path("status").asText();
        boolean completed = "completed".equals(outcome);
        var limit = responseLimit(attempt, response, outcome);
        boolean matchesTurn = turn != null && (id.equals(turn.responseId) || matchesRequest);
        if (matchesTurn) {
            if (limit == null && completed && attempt.interruption == null
                    && (turn.state == TurnState.WAITING || turn.state == TurnState.RESPONDING)
                    && prepareToolFollowUp(attempt, turn, id, response)) {
                return;
            }
            // The terminal output is what the provider keeps, including text generated before a cancel applied.
            if (turn.isVoice()) {
                finishVoiceResponse(attempt, turn, response, completed, limit != null);
            } else {
                turn.reconcile(protocol.terminalText(response));
            }
            finishTurn(attempt, switch (outcome) {
                case "completed" -> attempt.interruption == null ? TurnState.COMPLETED : TurnState.INTERRUPTED;
                case "cancelled" -> TurnState.CANCELLED;
                case "incomplete" -> TurnState.INCOMPLETE;
                default -> TurnState.FAILED;
            });
        }
        if (limit != null) {
            stopAtLimit(attempt, limit);
            return;
        }
        if (matchesTurn) {
            advanceInterruption(attempt);
            advanceVadTurn(attempt);
        }
        statusChanged.run();
    }

    private Failure responseLimit(Attempt attempt, JsonNode response, String outcome) {
        if ("incomplete".equals(outcome)
                && "max_output_tokens".equals(response.path("status_details").path("reason").textValue())) {
            return Failure.OUTPUT_LIMIT;
        }
        if (pricing.cutoff() != null && attempt.usage.missingUsage()) {
            return Failure.SPEND_ESTIMATE_UNAVAILABLE;
        }
        return attempt.usage.reachedCutoff(pricing.cutoff()) ? Failure.SPEND_LIMIT : null;
    }

    private boolean prepareToolFollowUp(Attempt attempt, ConversationTurn turn, String responseId,
                                       JsonNode response) throws IOException {
        var calls = new ArrayList<JsonNode>();
        var batchIds = new LinkedHashSet<String>();
        var output = response.path("output");
        if (!output.isArray()) {
            return false;
        }
        for (var item : output) {
            if (!"function_call".equals(item.path("type").textValue())) {
                continue;
            }
            var callId = item.path("call_id").textValue();
            if (turn.followingTools || !localTools.validCallId(callId)) {
                throw new IOException("Unexpected Realtime function call");
            }
            if (batchIds.add(callId)) {
                if (!attempt.toolCalls.reserve(responseId, callId)) {
                    stopAtLimit(attempt, Failure.TOOL_LIMIT);
                    return true;
                }
                calls.add(item);
            }
        }
        calls.removeIf(call -> !attempt.toolCalls.claim(call.path("call_id").textValue()));
        if (calls.isEmpty()) {
            return false;
        }
        if (turn.isVoice()) {
            protocol.reconcileVoiceTranscript(turn, response);
            if (turn.assistantAudio == null) {
                turn.assistantAudio = new AssistantAudio();
            }
            turn.assistantAudio.completeFirstResponse(response, protocol);
        } else {
            turn.reconcile(protocol.terminalText(response));
        }
        var exchange = new ToolExchange(turn.id, nanoTime.getAsLong());
        turn.prepareToolFollowUp(exchange);
        startDeadline(attempt, TOOL_OUTPUT_TIMEOUT, Failure.ITEM_ACK_TIMEOUT);
        if (active != attempt) {
            return true;
        }
        if (settings.toolPreset() == ToolPreset.TOY_WORKSPACE) {
            // The desktop dispatches one bounded batch to the FX thread. Cancellation can win while it is queued.
            dispatchWorkspaceTools.accept(() -> executeTools(attempt, turn, exchange, calls));
            // Render the reconciled first response while the batch waits for its thread.
            statusChanged.run();
        } else {
            executeTools(attempt, turn, exchange, calls);
        }
        return true;
    }

    private synchronized void executeTools(Attempt attempt, ConversationTurn turn, ToolExchange exchange,
                                           List<JsonNode> calls) {
        if (active != attempt || status.state() != State.READY || attempt.turn != turn
                || turn.toolExchange != exchange || turn.state == TurnState.CANCELLING || !withinSessionTime(attempt)) {
            return;
        }
        if (!withinToolOutputTime(attempt, exchange, nanoTime.getAsLong())) {
            return;
        }
        var outputs = new ArrayList<ToolExchange.Output>();
        var preset = settings.toolPreset();
        var workspace = toyWorkspace;
        for (var call : calls) {
            var result = localTools.execute(preset, call, workspace);
            workspace = result.workspace();
            outputs.add(new ToolExchange.Output(call.path("call_id").textValue(), result.output().toString()));
        }
        if (preset == ToolPreset.TOY_WORKSPACE) {
            // A deadline callback can be waiting for this monitor while the batch is evaluated.
            long observedAt = nanoTime.getAsLong();
            if (!withinSessionTime(attempt, observedAt) || !withinToolOutputTime(attempt, exchange, observedAt)) {
                return;
            }
            toyWorkspace = workspace;
        }
        exchange.completeExecution(outputs);
        advanceTurn(attempt);
        statusChanged.run();
    }

    private void finishVoiceResponse(Attempt attempt, ConversationTurn turn, JsonNode response,
                                     boolean completed, boolean closingAtLimit) throws IOException {
        var interruption = attempt.interruption;
        if (interruption != null && interruption.turn == turn) {
            interruption.audio.reconcile(response, protocol);
            interruption.generationDone = true;
            return;
        }
        protocol.reconcileVoiceTranscript(turn, response);
        var output = attempt.output;
        if (!completed && !closingAtLimit) {
            if (turn.assistantAudio == null) {
                turn.assistantAudio = new AssistantAudio();
            }
            turn.assistantAudio.reconcile(response, protocol);
            // Generation already ended. Stop local audio and reconcile without sending response.cancel.
            stopVoiceOutput(attempt, turn, output, false);
        } else if (completed && output != null) {
            turn.assistantAudio.reconcile(response, protocol);
            output.stream.complete();
        }
    }

    private void receiveItemAdded(Attempt attempt, JsonNode item) {
        var turn = attempt.turn;
        if (turn.id.equals(item.path("id").textValue()) && "message".equals(item.path("type").textValue())
                && "user".equals(item.path("role").textValue())) {
            turn.itemAcknowledged = true;
            advanceTurn(attempt);
        } else if (turn.toolExchange != null && turn.toolExchange.acknowledge(item)) {
            advanceTurn(attempt);
        }
    }

    private void responseCreated(Attempt attempt, JsonNode response) throws IOException {
        var id = protocol.requiredId(response.path("id").textValue());
        if (attempt.usage.isFinished(id)) {
            return;
        }
        var turn = attempt.turn;
        boolean matchesRequest = turn != null && matchesUnboundResponse(turn, response);
        attempt.usage.responseCreated(id, matchesRequest);
        if (matchesRequest) {
            turn.responseId = id;
            advanceTurn(attempt);
        }
        statusChanged.run();
    }

    private boolean matchesUnboundResponse(ConversationTurn turn, JsonNode response) {
        return turn.responseRequested && turn.responseId == null
                && turn.responseRequestKey().equals(
                        response.path("metadata").path(SessionProtocol.TURN_METADATA).textValue());
    }

    private void turnError(Attempt attempt, JsonNode error) {
        var turn = attempt.turn;
        var eventId = error.path("event_id").textValue();
        if (eventId == null) {
            // Once a response is requested, the provider may still be generating. Reconnect before sending again.
            if (turn.responseRequested || turn.toolExchange != null) {
                fail(attempt, Failure.PROVIDER);
            }
            return;
        }
        boolean itemRejected = !turn.itemAcknowledged && turn.itemEventId().equals(eventId);
        boolean responseRejected = turn.responseRequested && turn.responseId == null
                && turn.responseEventId().equals(eventId);
        if (responseRejected) {
            attempt.usage.requestRejected();
        }
        if (turn.toolExchange != null && turn.toolExchange.rejectsPendingOutput(eventId)
                || responseRejected && turn.followingTools) {
            fail(attempt, Failure.PROVIDER);
            return;
        } else if (itemRejected || responseRejected) {
            finishTurn(attempt, TurnState.FAILED);
        } else if (turn.cancelSent && turn.cancelEventId().equals(eventId)) {
            if (attempt.interruption != null) {
                // Generation may have ended before the cancel arrived. Wait for its terminal event.
                return;
            }
            turn.cancelSent = false;
            turn.state = TurnState.CANCEL_REJECTED;
            advanceTurn(attempt);
        } else {
            return;
        }
        if (responseRejected && attempt.interruption != null) {
            attempt.interruption.generationDone = true;
            advanceInterruption(attempt);
        }
        advanceVadTurn(attempt);
        statusChanged.run();
    }

    private void finishTurn(Attempt attempt, TurnState outcome) {
        if (attempt.turn != null) {
            // A terminal event can arrive before the write completes. Keep that write bounded.
            if (!attempt.writing && attempt.interruption == null) {
                clearPhaseDeadline(attempt);
            }
            // Keep the speaker error for the failed reply only, then restore the microphone hint.
            if (attempt.turn.isVoice() && attempt.speakerState == SpeakerState.UNAVAILABLE) {
                attempt.speakerState = SpeakerState.IDLE;
            }
            if (attempt.output == null) {
                attempt.turn.assistantAudio = null;
            }
            attempt.turn.toolExchange = null;
            attempt.turn.finish(outcome);
            attempt.turn = null;
        }
    }

    private void advanceTurn(Attempt attempt) {
        if (attempt.writing) {
            return;
        }
        var turn = attempt.turn;
        if (turn == null) {
            if (attempt.interruption == null) {
                clearPhaseDeadline(attempt);
            }
            return;
        }
        if (!withinSessionTime(attempt)) {
            return;
        }
        var exchange = turn.toolExchange;
        if (exchange != null && !withinToolOutputTime(attempt, exchange, nanoTime.getAsLong())) {
            return;
        }
        if (!turn.responseRequested && turn.state == TurnState.CANCELLING) {
            // The write is done, but the provider can still reject an output it has not acknowledged.
            if (exchange != null && exchange.awaitingAcknowledgment()) {
                return;
            }
            finishTurn(attempt, TurnState.CANCELLED);
            if (attempt.interruption != null) {
                attempt.interruption.generationDone = true;
                advanceInterruption(attempt);
            } else {
                advanceVadTurn(attempt);
            }
            statusChanged.run();
        } else if (exchange != null && !exchange.complete()) {
            var command = exchange.nextWrite(protocol);
            if (command != null) {
                writeTurnEvent(attempt, command);
            }
        } else if (turn.itemAcknowledged && !turn.responseRequested) {
            if (exchange != null) {
                turn.toolExchange = null;
                clearPhaseDeadline(attempt);
            }
            var output = attempt.output;
            // Reserve the entire bounded queue for the follow-up. The line can finish playing its own buffer.
            if (turn.followingTools && output != null
                    && output.stream.waitForEmptyQueue(() -> Thread.ofVirtual().name("rvl-speaker-queue")
                            .start(() -> playbackQueueEmpty(attempt, output)))) {
                return;
            }
            turn.responseRequested = true;
            // An unidentified response may still be running. Abort on timeout before allowing a new session.
            startDeadline(attempt, RESPONSE_CORRELATION_TIMEOUT, Failure.RESPONSE_TIMEOUT);
            if (active == attempt) {
                turn.responseRequestedAt = nanoTime.getAsLong();
                turn.firstAudioReceived = false;
                attempt.usage.requestSent();
                statusChanged.run();
                writeTurnEvent(attempt, protocol.createResponse(turn));
            }
        } else if (turn.responseId != null && turn.state == TurnState.CANCELLING && !turn.cancelSent) {
            turn.cancelSent = true;
            turn.cancelRequests++;
            writeTurnEvent(attempt, protocol.cancelResponse(turn));
        } else if (turn.responseId != null && turn.state != TurnState.CANCELLING) {
            clearPhaseDeadline(attempt);
        }
    }

    private CompletableFuture<WebSocket> sendEvent(Attempt attempt, SessionProtocol.Command event) {
        boolean streamingTranscription = attempt.transcriptionModel == TranscriptionModel.LIVE_TRANSCRIBE
                || attempt.transcriptionModel == TranscriptionModel.REALTIME_WHISPER;
        if (("input_audio_buffer.commit".equals(event.type())
                || streamingTranscription && "input_audio_buffer.append".equals(event.type()))
                && attempt.usage.audioSubmitted()) {
            statusChanged.run();
        }
        inspector.outgoing(event.type(), event.eventId(), event.audioBytes());
        eventsChanged.run();
        return attempt.socket.sendText(event.message(), true);
    }

    private void writeTurnEvent(Attempt attempt, SessionProtocol.Command event) {
        attempt.writing = true;
        boolean vadCaptureWrite = attempt.input != null && attempt.input.vad != null
                && ("input_audio_buffer.append".equals(event.type()) || "input_audio_buffer.clear".equals(event.type()));
        var deadline = vadCaptureWrite ? scheduleTimeout.apply(ITEM_ACK_TIMEOUT.toNanos()) : null;
        if (deadline != null) {
            deadline.whenComplete((ignored, error) -> {
                if (error != null) {
                    fail(attempt, Failure.ITEM_ACK_TIMEOUT);
                }
            });
        }
        if (active != attempt) {
            return;
        }
        try {
            sendEvent(attempt, event).whenComplete((socket, error) -> {
                if (deadline == null || deadline.complete(null)) {
                    turnEventWritten(attempt, error);
                }
            });
        } catch (RuntimeException error) {
            if (deadline != null) {
                deadline.complete(null);
            }
            fail(attempt, Failure.CONNECTION);
        }
    }

    private synchronized void turnEventWritten(Attempt attempt, Throwable error) {
        if (active != attempt) {
            return;
        }
        attempt.writing = false;
        if (error != null) {
            fail(attempt, Failure.CONNECTION);
        } else if (status.state() == State.CLOSING) {
            closeSocket(attempt);
        } else if (attempt.input != null && attempt.input.vad == null) {
            advanceInput(attempt);
        } else {
            advanceInterruption(attempt);
            if (active == attempt) {
                advanceTurn(attempt);
                advanceVadTurn(attempt);
                advanceVad(attempt);
                statusChanged.run();
            }
        }
    }

    private void sendConfiguration(Attempt attempt, SessionSettings requested) {
        boolean initial = status.state() == State.CONNECTING;
        var update = new SettingsUpdate("rvl-settings-" + ++attempt.settingsSequence, requested);
        attempt.settingUpdate = update;
        if (!initial) {
            startDeadline(attempt, setupTimeout, Failure.SETTINGS_TIMEOUT);
        }
        if (active != attempt || !withinSessionTime(attempt)) {
            return;
        }
        try {
            var command = protocol.configuration(update.eventId, requested, initial ? null : settings,
                    attempt.transcriptionModel);
            sendEvent(attempt, command).whenComplete((socket, error) -> {
                synchronized (RealtimeSession.this) {
                    if (active != attempt || status.state() == State.CLOSING) {
                        return;
                    }
                    if (error != null) {
                        fail(attempt, Failure.CONNECTION);
                        return;
                    }
                    update.written = true;
                    commitSettings(attempt, update);
                }
            });
        } catch (RuntimeException error) {
            fail(attempt, Failure.CONNECTION);
        }
        if (!initial && active == attempt) {
            statusChanged.run();
        }
    }

    private void acknowledgeSettings(Attempt attempt, SessionProtocol.Configuration acknowledged) {
        var update = attempt.settingUpdate;
        if (update == null || !acknowledged.sessionId().equals(attempt.sessionId)) {
            return;
        }
        var requestedDetection = update.requested.effectiveTurnDetection();
        var appliedDetection = acknowledged.turnDetection();
        // Historic manual sessions can still use text if PCM/turn detection was not confirmed.
        if (appliedDetection != requestedDetection
                && (requestedDetection.automatic() || appliedDetection != null
                    || settings.effectiveTurnDetection().automatic())) {
            fail(attempt, Failure.SETTINGS_MISMATCH);
        } else if (!acknowledged.boundedOutput() || update.requested.mode() == Mode.AUDIO && !acknowledged.pcmOutput()
                || !transcriptionConfirmed(attempt.transcriptionModel, acknowledged.transcriptionModel())) {
            fail(attempt, Failure.PROTOCOL);
        } else if (!acknowledged.matches(update.requested)) {
            fail(attempt, Failure.SETTINGS_MISMATCH);
        } else {
            update.acknowledged = acknowledged;
            commitSettings(attempt, update);
        }
    }

    private void commitSettings(Attempt attempt, SettingsUpdate update) {
        if (!update.written || update.acknowledged == null || !withinSessionTime(attempt)
                || !attempt.phaseDeadline.complete(null)) {
            return;
        }
        attempt.settingUpdate = null;
        settings = update.requested;
        attempt.inputConfigured = settings.mode() == Mode.AUDIO && update.acknowledged.pcmInput()
                && update.acknowledged.turnDetection() == settings.effectiveTurnDetection();
        attempt.micState = attempt.inputConfigured || settings.mode() == Mode.TEXT
                ? MicState.IDLE : MicState.UNCONFIRMED;
        if (status.state() == State.CONNECTING) {
            publish(State.READY, null);
        } else {
            statusChanged.run();
        }
    }

    private static final class SettingsUpdate {
        private final String eventId;
        private final SessionSettings requested;
        private boolean written;
        private SessionProtocol.Configuration acknowledged;

        SettingsUpdate(String eventId, SessionSettings requested) {
            this.eventId = eventId;
            this.requested = requested;
        }
    }

    private static final class AudioInput {
        private final long number;
        private final String eventPrefix;
        private final VadCapture vad;
        private volatile boolean stop;
        private CompletableFuture<Void> openDeadline;
        private boolean started;
        // Accessed under the session monitor. The byte FIFO is filled before it is drained.
        private ByteBuffer pcm;
        private int size;
        private double level;
        private boolean released;
        private boolean discard;
        private int sentChunks;
        private boolean committing;
        private boolean cleanupSent;
        private String committedItemId;
        private ConversationTurn turn;

        AudioInput(long number, boolean vad) {
            this.number = number;
            this.vad = vad ? new VadCapture() : null;
            this.eventPrefix = "rvl-" + number + "-audio-";
        }
    }

    private static final class VadCapture {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int queuedBytes;
        private String speechItemId;
        private boolean speechStopped;
        private MicState finalState;
        private CompletableFuture<Void> clearDeadline;
    }

    private static final class Interruption {
        private final ConversationTurn turn;
        private final AudioOutput output;
        private final AssistantAudio audio;
        private final boolean interruptedGeneration;
        private boolean generationDone;
        private ArrayDeque<AssistantAudio.Cut> cuts;
        private AssistantAudio.Cut pending;
        private String eventId;
        private int sequence;

        Interruption(ConversationTurn turn, AudioOutput output, boolean generating) {
            this.turn = turn;
            this.output = output;
            this.audio = turn.assistantAudio == null ? new AssistantAudio() : turn.assistantAudio;
            this.interruptedGeneration = generating;
            this.generationDone = !generating;
        }
    }

    private static final class AudioOutput {
        private final ConversationTurn turn;
        private final SpeakerPlayback.Stream stream = new SpeakerPlayback.Stream(MAX_OUTPUT_AUDIO_BYTES);
        private CompletableFuture<Void> openDeadline;
        private CompletableFuture<Void> cleanupDeadline;
        private boolean cleanupTimedOut;
        private boolean drained;
        private double level;

        AudioOutput(ConversationTurn turn) {
            this.turn = turn;
        }
    }

    private final class Attempt implements WebSocket.Listener {
        private final long startedAt = nanoTime.getAsLong();
        private final TranscriptionModel transcriptionModel;
        private CompletableFuture<WebSocket> opening;
        private CompletableFuture<Void> phaseDeadline;
        private CompletableFuture<Void> sessionDeadline;
        private WebSocket socket;
        private StringBuilder message;
        private boolean discardingMessage;
        private String sessionId;
        private ConversationTurn turn;
        private AudioInput input;
        private AudioOutput output;
        private Interruption interruption;
        private ConversationTurn pendingVadTurn;
        private final LinkedHashSet<String> vadSpeechItems = new LinkedHashSet<>();
        private MicState micState = MicState.IDLE;
        private SpeakerState speakerState = SpeakerState.IDLE;
        private boolean inputConfigured;
        private boolean pumpingAudio;
        private boolean writing;
        private boolean cancelBeforeClose;
        private final Map<String, Long> audioBytes = new HashMap<>();
        private final SessionUsage usage;
        private final ToolCallBudget toolCalls = new ToolCallBudget();
        private Failure closeFailure;
        private long settingsSequence;
        private SettingsUpdate settingUpdate;
        private boolean voiceLocked;
        private boolean closeSent;
        private Integer peerCloseCode;

        Attempt(TranscriptionModel transcriptionModel, SessionPricing.Rates rates) {
            this.transcriptionModel = transcriptionModel;
            usage = new SessionUsage(settings.model(), rates, transcriptionModel != TranscriptionModel.OFF);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            synchronized (RealtimeSession.this) {
                if (active != this) {
                    webSocket.abort();
                    return;
                }
                socket = webSocket;
                if (withinSessionTime(this)) {
                    socket.request(1);
                }
            }
        }

        @Override
        public CompletableFuture<Void> onText(WebSocket webSocket, CharSequence fragment, boolean last) {
            synchronized (RealtimeSession.this) {
                // receive checks the session time after inspecting each complete frame.
                if (active == this) {
                    try {
                        receive(this, fragment, last);
                    } catch (IOException error) {
                        // The peer acknowledgment and close deadline still govern shutdown.
                        if (status.state() != State.CLOSING) {
                            fail(this, Failure.PROTOCOL);
                        }
                    }
                }
                // Demand must continue during close so a peer close frame can be delivered.
                if (active == this) {
                    webSocket.request(1);
                }
            }
            return null;
        }

        @Override
        public CompletableFuture<Void> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            synchronized (RealtimeSession.this) {
                if (active == this && status.state() == State.CLOSING) {
                    webSocket.request(1);
                } else {
                    fail(this, Failure.PROTOCOL);
                }
            }
            return null;
        }

        @Override
        public CompletableFuture<Void> onClose(WebSocket webSocket, int statusCode, String reason) {
            synchronized (RealtimeSession.this) {
                if (active == this) {
                    switch (status.state()) {
                        case CONNECTING -> finish(this, Failure.REMOTE_CLOSE, true);
                        case CLOSING -> {
                            peerCloseCode = statusCode;
                            checkClosed(this);
                        }
                        // The JDK replies to a peer-initiated close when this callback returns.
                        case READY -> finishPeerClose(this, statusCode);
                        default -> { }
                    }
                }
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            fail(this, Failure.CONNECTION);
        }
    }
}

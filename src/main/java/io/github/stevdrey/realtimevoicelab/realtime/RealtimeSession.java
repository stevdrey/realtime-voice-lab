package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;

public final class RealtimeSession implements AutoCloseable {
    public static final String DEFAULT_MODEL = "gpt-realtime-2.1-mini";
    public static final int MAX_INPUT_CHARS = 4_000;
    private static final int MAX_HISTORY_TURNS = 60;
    static final Duration MAX_SESSION_TIME = Duration.ofMinutes(5);
    static final Duration ITEM_ACK_TIMEOUT = Duration.ofSeconds(15);
    static final Duration RESPONSE_CORRELATION_TIMEOUT = Duration.ofSeconds(15);
    static final Duration CANCEL_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MAX_AUDIO_TIME = Duration.ofSeconds(30);
    static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_INPUT_AUDIO_BYTES = (int) (SessionProtocol.PCM_BYTES_PER_SECOND * MAX_AUDIO_TIME.toSeconds());
    private static final int MAX_OUTPUT_AUDIO_BYTES = MAX_INPUT_AUDIO_BYTES;
    // Ignore very short taps instead of asking the provider to commit a sub-100 ms buffer.
    static final int MIN_INPUT_AUDIO_BYTES = SessionProtocol.PCM_BYTES_PER_SECOND / 10;
    private static final int AUDIO_APPEND_BYTES = SessionProtocol.PCM_BYTES_PER_SECOND / 5;
    static final int MAX_TOOL_CALLS = 10;
    static final int MAX_AUDIO_RESPONSES = 4;

    public enum State {
        DISCONNECTED, CONNECTING, READY, CLOSING, ERROR
    }

    public enum Failure {
        MISSING_CREDENTIAL, AUTHENTICATION, CONNECTION, TIMEOUT, ITEM_ACK_TIMEOUT, RESPONSE_TIMEOUT, CANCEL_TIMEOUT,
        PROTOCOL, PROVIDER, REMOTE_CLOSE,
        SESSION_LIMIT, AUDIO_LIMIT, OUTPUT_LIMIT, TOOL_LIMIT
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
        IDLE, UNCONFIRMED, OPENING, RECORDING, LIMIT, STOPPING, SENDING, TOO_SHORT, UNSUPPORTED, UNAVAILABLE
    }

    public record MicrophoneView(MicState state, boolean canStart, double seconds, double level) {
    }

    public enum SpeakerState {
        IDLE, OPENING, SPEAKING, UNAVAILABLE
    }

    public record SpeakerView(SpeakerState state, double level) {
    }

    public record Workspace(Status status, List<TurnView> turns, boolean canSend, boolean canCancel,
                            MicrophoneView microphone, SpeakerView speaker) {
    }

    private final RealtimeTransport transport;
    private final MicrophoneCapture microphone;
    private final SpeakerPlayback speaker;
    private final Runnable statusChanged;
    private final Duration setupTimeout;
    private final Duration closeTimeout;
    private final LongSupplier nanoTime;
    // The timer completes exceptionally on expiry. Normal completion cancels the timeout.
    private final LongFunction<CompletableFuture<Void>> scheduleTimeout;
    private final SessionProtocol protocol = new SessionProtocol();

    // Commands, listener callbacks, write completions and deadlines share this monitor.
    private Status status = new Status(State.DISCONNECTED, null);
    private final ArrayDeque<ConversationTurn> history = new ArrayDeque<>();
    private long turnNumber;
    private Attempt active;
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
        this(transport, statusChanged, Duration.ofSeconds(15), Duration.ofSeconds(3),
                System::nanoTime, RealtimeSession::timeoutAfter, microphone, speaker);
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
        this.transport = transport;
        this.microphone = microphone;
        this.speaker = speaker;
        this.statusChanged = statusChanged;
        this.setupTimeout = setupTimeout;
        this.closeTimeout = closeTimeout;
        this.nanoTime = nanoTime;
        this.scheduleTimeout = scheduleTimeout;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized Workspace workspace() {
        boolean ready = status.state() == State.READY;
        var input = ready ? active.input : null;
        var micState = ready ? active.micState : MicState.IDLE;
        var output = ready ? active.output : null;
        var speakerState = ready ? active.speakerState : SpeakerState.IDLE;
        return new Workspace(status, history.stream().map(ConversationTurn::view).toList(),
                ready && canStartTurn(active),
                ready && (input != null && !input.discard
                        || active.turn != null && active.turn.state != TurnState.CANCELLING || output != null),
                new MicrophoneView(micState, ready && canStartAudio(active),
                        input == null ? 0 : (double) input.size / SessionProtocol.PCM_BYTES_PER_SECOND,
                        micState == MicState.RECORDING ? input.level : 0),
                new SpeakerView(speakerState, output == null ? 0 : output.level));
    }

    private boolean canStartTurn(Attempt attempt) {
        return attempt.turn == null && attempt.input == null && attempt.output == null && !attempt.writing;
    }

    private boolean canStartAudio(Attempt attempt) {
        // Stopped playback can still own the speaker while its worker flushes and closes the line.
        return canStartTurn(attempt) && attempt.inputConfigured && captureWorker == null && playbackWorker == null;
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
        if (history.size() == MAX_HISTORY_TURNS) {
            history.removeFirst();
        }
        history.addLast(turn);
    }

    public synchronized void cancelTurn() {
        if (status.state() != State.READY) {
            return;
        }
        if (active.input != null) {
            cancelAudio();
        } else if (active.turn == null && active.output != null && withinSessionTime(active)) {
            stopPlayback(active);
            statusChanged.run();
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
        var input = new AudioInput(++turnNumber);
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
        if (status.state() != State.READY || attempt.input == null || attempt.input.discard
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
            resetInput(attempt, MicState.UNAVAILABLE);
            statusChanged.run();
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
        if (input.pcm == null) {
            input.pcm = ByteBuffer.allocate(MAX_INPUT_AUDIO_BYTES);
        }
        int accepted = Math.min(count, input.pcm.remaining());
        input.pcm.put(pcm, 0, accepted);
        int previousSize = input.size;
        input.size += accepted;
        int peak = 0;
        for (int index = 0; index < accepted; index += Short.BYTES) {
            int sample = (short) ((pcm[index] & 0xff) | (pcm[index + 1] << 8));
            peak = Math.max(peak, Math.abs(sample));
        }
        input.level = peak / 32768.0;
        if (!input.pcm.hasRemaining()) {
            input.stop = true;
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
            if (input.discard) {
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
        input.discard = true;
        input.stop = true;
        input.pcm = null;
        attempt.micState = MicState.STOPPING;
        if (captureWorker != input) {
            advanceInput(attempt);
        }
        statusChanged.run();
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

        var attempt = new Attempt(Objects.requireNonNull(transcriptionModel));
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
            attempt.opening = transport.open(model, attempt);
            attempt.opening.whenComplete((socket, error) -> {
                if (error != null) {
                    fail(attempt, connectionFailure(error));
                }
            });
        } catch (RuntimeException error) {
            fail(attempt, connectionFailure(error));
        }
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
        attempt.sessionDeadline.complete(null);
        attempt.closeFailure = limit;
        attempt.message = null;
        resetInput(attempt, MicState.IDLE);
        stopPlayback(attempt);
        // OUTPUT_LIMIT arrives after the provider has already stopped generation.
        attempt.cancelBeforeClose = limit != Failure.OUTPUT_LIMIT
                && (limit != null || (attempt.turn != null && attempt.turn.responseRequested));
        finishTurn(attempt, TurnState.INTERRUPTED);
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
            attempt.socket.sendText(SessionProtocol.CANCEL_RESPONSE, true)
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
        if (nanoTime.getAsLong() - attempt.startedAt < MAX_SESSION_TIME.toNanos()) {
            return true;
        }
        stopAtLimit(attempt, Failure.SESSION_LIMIT);
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
        active = null;
        clearPhaseDeadline(attempt);
        attempt.sessionDeadline.complete(null);
        attempt.message = null;
        resetInput(attempt, MicState.IDLE);
        stopPlayback(attempt);
        finishTurn(attempt, TurnState.INTERRUPTED);
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
        int buffered = attempt.message == null ? 0 : attempt.message.length();
        if (fragment.length() > SessionProtocol.MAX_MESSAGE_CHARS - buffered) {
            // There may be an active response, so abort immediately if a frame exceeds the buffer.
            throw new IOException("Realtime message limit exceeded");
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
                return;
            }
            message = attempt.message.toString();
            attempt.message = null;
        }

        var event = protocol.parse(message);
        switch (event.type()) {
            case "error" -> {
                // Setup cannot complete after a rejected configuration.
                if (status.state() == State.CONNECTING) {
                    fail(attempt, Failure.PROVIDER);
                } else if (attempt.turn != null || (attempt.input != null && attempt.input.sentChunks > 0)) {
                    var error = protocol.textEvent(message).path("error");
                    if (attempt.turn != null) {
                        turnError(attempt, error);
                    } else {
                        var id = error.path("event_id").textValue();
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
                    sendConfiguration(attempt);
                }
            }
            case "session.updated" -> {
                var configuration = event.configuration();
                if (status.state() == State.CONNECTING && configuration.sessionId().equals(attempt.sessionId)) {
                    if (!configuration.outputConfirmed()
                            || !attempt.transcriptionModel.modelId().equals(configuration.transcriptionModel())) {
                        fail(attempt, Failure.PROTOCOL);
                        return;
                    }
                    attempt.inputConfigured = configuration.inputConfirmed();
                    attempt.micState = attempt.inputConfigured ? MicState.IDLE : MicState.UNCONFIRMED;
                    attempt.updateAcknowledged = true;
                    checkReady(attempt);
                }
            }
            case "input_audio_buffer.committed" -> {
                var input = attempt.input;
                if (input != null && input.committing) {
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
                if (input != null && input.cleanupSent && input.committedItemId == null) {
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
                // Replays and rejected requests also consume budget. Dispatch remains a separate check.
                if (status.state() == State.READY && ++attempt.toolCalls > MAX_TOOL_CALLS) {
                    stopAtLimit(attempt, Failure.TOOL_LIMIT);
                }
            }
            case "response.output_audio.delta" -> {
                if (status.state() == State.READY && !attempt.finishedResponses.contains(event.responseId())) {
                    receiveOutputAudio(attempt, event);
                }
            }
            case "response.output_audio.done", "response.output_audio_transcript.delta",
                 "response.output_audio_transcript.done" -> {
                if (status.state() == State.READY && isVoiceResponse(attempt.turn, event.responseId())) {
                    receiveVoiceEvent(attempt.turn, event);
                }
            }
            case "response.done" -> {
                if (status.state() == State.READY) {
                    responseDone(attempt, protocol.textEvent(message).path("response"));
                }
            }
            case "conversation.item.added", "response.created" -> {
                if (status.state() == State.READY && attempt.turn != null) {
                    receiveTurnEvent(attempt, event.type(), protocol.textEvent(message));
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

    private void receiveOutputAudio(Attempt attempt, SessionProtocol.Event event) throws IOException {
        var pcm = protocol.decodeAudio(event.encodedAudio());
        accountForAudio(attempt, event.responseId(), pcm.length);
        var turn = attempt.turn;
        if (status.state() == State.READY && pcm.length != 0 && isVoiceResponse(turn, event.responseId())
                && turn.canPlayPart(event.part()) && attempt.speakerState != SpeakerState.UNAVAILABLE) {
            turn.markResponding();
            queuePlayback(attempt, pcm);
        }
    }

    private void queuePlayback(Attempt attempt, byte[] pcm) {
        if (attempt.output != null) {
            if (!attempt.output.stream.offer(pcm)) {
                playbackUnavailable(attempt);
            }
            return;
        }
        var output = new AudioOutput();
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
                        level -> playbackLevel(attempt, output, level));
            } finally {
                playbackEnded(attempt, output, result);
            }
        });
    }

    private synchronized void playbackOpenExpired(Attempt attempt, AudioOutput output) {
        if (active == attempt && attempt.output == output && withinSessionTime(attempt)) {
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

    private synchronized void playbackEnded(Attempt attempt, AudioOutput output, SpeakerPlayback.Result result) {
        playbackWorker = null;
        if (active == attempt && attempt.output == output) {
            if (result == SpeakerPlayback.Result.UNAVAILABLE) {
                playbackUnavailable(attempt);
            } else {
                stopPlayback(attempt);
                statusChanged.run();
            }
        } else if (status.state() == State.READY && canStartAudio(active)) {
            statusChanged.run();
        }
        startPlaybackIfAvailable();
    }

    private void playbackUnavailable(Attempt attempt) {
        stopPlayback(attempt);
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
        if (!attempt.finishedResponses.add(id)) {
            return;
        }
        if (attempt.finishedResponses.size() > MAX_HISTORY_TURNS) {
            attempt.finishedResponses.removeFirst();
        }
        attempt.audioBytes.remove(id);
        var outcome = response.path("status").asText();
        var turn = attempt.turn;
        boolean matchesTurn = turn != null && (id.equals(turn.responseId) || matchesUnboundResponse(turn, response));
        if (matchesTurn) {
            // The terminal output is what the provider keeps, including text generated before a cancel applied.
            if (turn.isVoice()) {
                protocol.reconcileVoiceTranscript(turn, response);
                if (attempt.output != null) {
                    if ("completed".equals(outcome)) {
                        attempt.output.stream.complete();
                    } else {
                        stopPlayback(attempt);
                    }
                }
            } else {
                turn.reconcile(protocol.terminalText(response));
            }
            finishTurn(attempt, switch (outcome) {
                case "completed" -> TurnState.COMPLETED;
                case "cancelled" -> TurnState.CANCELLED;
                case "incomplete" -> TurnState.INCOMPLETE;
                default -> TurnState.FAILED;
            });
        }
        if ("incomplete".equals(outcome)
                && "max_output_tokens".equals(response.path("status_details").path("reason").textValue())) {
            stopAtLimit(attempt, Failure.OUTPUT_LIMIT);
        } else if (matchesTurn) {
            statusChanged.run();
        }
    }

    private void receiveTurnEvent(Attempt attempt, String type, JsonNode event) throws IOException {
        var turn = attempt.turn;
        switch (type) {
            case "conversation.item.added" -> {
                var item = event.path("item");
                if (turn.id.equals(item.path("id").textValue()) && "message".equals(item.path("type").textValue())
                        && "user".equals(item.path("role").textValue())) {
                    turn.itemAcknowledged = true;
                    advanceTurn(attempt);
                }
            }
            case "response.created" -> {
                var response = event.path("response");
                if (matchesUnboundResponse(turn, response)) {
                    var id = protocol.requiredId(response.path("id").textValue());
                    if (attempt.finishedResponses.contains(id)) {
                        return;
                    }
                    turn.responseId = id;
                    advanceTurn(attempt);
                }
            }
            default -> throw new IllegalArgumentException("Unexpected text conversation event");
        }
    }

    private boolean matchesUnboundResponse(ConversationTurn turn, JsonNode response) {
        return turn.responseRequested && turn.responseId == null
                && turn.id.equals(response.path("metadata").path(SessionProtocol.TURN_METADATA).textValue());
    }

    private void turnError(Attempt attempt, JsonNode error) {
        var turn = attempt.turn;
        var eventId = error.path("event_id").textValue();
        if (eventId == null) {
            // Once a response is requested, the provider may still be generating. Reconnect before sending again.
            if (turn.responseRequested) {
                fail(attempt, Failure.PROVIDER);
            }
            return;
        }
        boolean itemRejected = !turn.itemAcknowledged && turn.itemEventId().equals(eventId);
        boolean responseRejected = turn.responseRequested && turn.responseId == null
                && turn.responseEventId().equals(eventId);
        if (itemRejected || responseRejected) {
            finishTurn(attempt, TurnState.FAILED);
        } else if (turn.cancelSent && turn.cancelEventId().equals(eventId)) {
            turn.cancelSent = false;
            turn.state = TurnState.CANCEL_REJECTED;
            advanceTurn(attempt);
        } else {
            return;
        }
        statusChanged.run();
    }

    private void finishTurn(Attempt attempt, TurnState outcome) {
        if (attempt.turn != null) {
            // A terminal event can arrive before the write completes. Keep that write bounded.
            if (!attempt.writing) {
                clearPhaseDeadline(attempt);
            }
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
            clearPhaseDeadline(attempt);
            return;
        }
        if (!withinSessionTime(attempt)) {
            return;
        }
        if (!turn.responseRequested && turn.state == TurnState.CANCELLING) {
            finishTurn(attempt, TurnState.CANCELLED);
            statusChanged.run();
        } else if (turn.itemAcknowledged && !turn.responseRequested) {
            turn.responseRequested = true;
            // An unidentified response may still be running. Abort on timeout before allowing a new session.
            startDeadline(attempt, RESPONSE_CORRELATION_TIMEOUT, Failure.RESPONSE_TIMEOUT);
            if (active == attempt) {
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

    private void writeTurnEvent(Attempt attempt, String event) {
        attempt.writing = true;
        try {
            attempt.socket.sendText(event, true).whenComplete((socket, error) -> turnEventWritten(attempt, error));
        } catch (RuntimeException error) {
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
        } else if (attempt.input != null) {
            advanceInput(attempt);
        } else {
            advanceTurn(attempt);
            statusChanged.run();
        }
    }

    private void sendConfiguration(Attempt attempt) {
        if (!withinSessionTime(attempt)) {
            return;
        }
        try {
            attempt.socket.sendText(protocol.configuration(attempt.transcriptionModel), true).whenComplete((socket, error) -> {
                synchronized (RealtimeSession.this) {
                    if (active != attempt) {
                        return;
                    }
                    if (error != null) {
                        fail(attempt, Failure.CONNECTION);
                        return;
                    }
                    attempt.updateSent = true;
                    checkReady(attempt);
                }
            });
        } catch (RuntimeException error) {
            fail(attempt, Failure.CONNECTION);
        }
    }

    private void checkReady(Attempt attempt) {
        if (attempt.updateSent && attempt.updateAcknowledged && withinSessionTime(attempt)
                && attempt.phaseDeadline.complete(null)) {
            publish(State.READY, null);
        }
    }

    private static final class AudioInput {
        private final long number;
        private final String eventPrefix;
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

        AudioInput(long number) {
            this.number = number;
            this.eventPrefix = "rvl-" + number + "-audio-";
        }
    }

    private static final class AudioOutput {
        private final SpeakerPlayback.Stream stream = new SpeakerPlayback.Stream(MAX_OUTPUT_AUDIO_BYTES);
        private CompletableFuture<Void> openDeadline;
        private double level;
    }

    private final class Attempt implements WebSocket.Listener {
        private final long startedAt = nanoTime.getAsLong();
        private final TranscriptionModel transcriptionModel;
        private CompletableFuture<WebSocket> opening;
        private CompletableFuture<Void> phaseDeadline;
        private CompletableFuture<Void> sessionDeadline;
        private WebSocket socket;
        private StringBuilder message;
        private String sessionId;
        private ConversationTurn turn;
        private AudioInput input;
        private AudioOutput output;
        private MicState micState = MicState.IDLE;
        private SpeakerState speakerState = SpeakerState.IDLE;
        private boolean inputConfigured;
        private boolean pumpingAudio;
        private boolean writing;
        private boolean cancelBeforeClose;
        private final Map<String, Long> audioBytes = new HashMap<>();
        private final LinkedHashSet<String> finishedResponses = new LinkedHashSet<>();
        private int toolCalls;
        private Failure closeFailure;
        private boolean updateSent;
        private boolean updateAcknowledged;
        private boolean closeSent;
        private Integer peerCloseCode;

        Attempt(TranscriptionModel transcriptionModel) {
            this.transcriptionModel = transcriptionModel;
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
                if (active == this && status.state() != State.CLOSING && withinSessionTime(this)) {
                    try {
                        receive(this, fragment, last);
                    } catch (IOException error) {
                        fail(this, Failure.PROTOCOL);
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

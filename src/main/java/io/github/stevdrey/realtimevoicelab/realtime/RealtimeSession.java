package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;

public final class RealtimeSession implements AutoCloseable {
    public static final String DEFAULT_MODEL = "gpt-realtime-2.1-mini";
    public static final int MAX_INPUT_CHARS = 4_000;
    private static final int MAX_HISTORY_TURNS = 60;
    static final Duration MAX_SESSION_TIME = Duration.ofMinutes(5);
    static final Duration RESPONSE_CORRELATION_TIMEOUT = Duration.ofSeconds(15);
    static final Duration MAX_AUDIO_TIME = Duration.ofSeconds(30);
    static final int MAX_TOOL_CALLS = 10;
    static final int MAX_AUDIO_RESPONSES = 4;

    public enum State {
        DISCONNECTED, CONNECTING, READY, CLOSING, ERROR
    }

    public enum Failure {
        MISSING_CREDENTIAL, AUTHENTICATION, CONNECTION, TIMEOUT, RESPONSE_TIMEOUT, PROTOCOL, PROVIDER, REMOTE_CLOSE,
        SESSION_LIMIT, AUDIO_LIMIT, OUTPUT_LIMIT, TOOL_LIMIT
    }

    public record Status(State state, Failure failure) {
    }

    public enum TurnState {
        WAITING, RESPONDING, CANCELLING, COMPLETED, CANCELLED, FAILED, INCOMPLETE, INTERRUPTED, CANCEL_REJECTED
    }

    public record TurnView(long number, String input, String output, TurnState state) {
    }

    public record Workspace(Status status, List<TurnView> turns, boolean canSend, boolean canCancel) {
    }

    private final RealtimeTransport transport;
    private final Runnable statusChanged;
    private final Duration setupTimeout;
    private final Duration closeTimeout;
    private final LongSupplier nanoTime;
    // The timer completes exceptionally on expiry. Normal completion cancels the timeout.
    private final LongFunction<CompletableFuture<Void>> scheduleTimeout;
    private final SessionProtocol protocol = new SessionProtocol();

    // Commands, listener callbacks, write completions and deadlines share this monitor.
    private Status status = new Status(State.DISCONNECTED, null);
    private final ArrayDeque<TextTurn> history = new ArrayDeque<>();
    private long turnNumber;
    private Attempt active;
    private boolean disposed;

    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged) {
        this(transport, statusChanged, Duration.ofSeconds(15), Duration.ofSeconds(3));
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout, Duration closeTimeout) {
        this(transport, statusChanged, setupTimeout, closeTimeout, System::nanoTime, RealtimeSession::timeoutAfter);
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout, Duration closeTimeout,
                    LongSupplier nanoTime, LongFunction<CompletableFuture<Void>> scheduleTimeout) {
        this.transport = transport;
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
        return new Workspace(status, history.stream().map(TextTurn::view).toList(),
                ready && active.turn == null && !active.writing,
                ready && active.turn != null && active.turn.state != TurnState.CANCELLING);
    }

    public synchronized boolean sendText(String input) {
        var attempt = active;
        if (status.state() != State.READY || attempt.turn != null || attempt.writing
                || input == null || input.isBlank() || input.length() > MAX_INPUT_CHARS || !withinSessionTime(attempt)) {
            return false;
        }
        var turn = new TextTurn(++turnNumber, input);
        attempt.turn = turn;
        if (history.size() == MAX_HISTORY_TURNS) {
            history.removeFirst();
        }
        history.addLast(turn);
        writeTurnEvent(attempt, protocol.createItem(turn));
        statusChanged.run();
        return true;
    }

    public synchronized void cancelText() {
        var attempt = active;
        if (status.state() != State.READY || attempt.turn == null || attempt.turn.state == TurnState.CANCELLING
                || !withinSessionTime(attempt)) {
            return;
        }
        attempt.turn.state = TurnState.CANCELLING;
        advanceTurn(attempt);
        statusChanged.run();
    }

    public synchronized void connect(String model) {
        if (disposed || active != null) {
            return;
        }

        var attempt = new Attempt();
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
        finishTurn(attempt, TurnState.INTERRUPTED);
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
                } else if (attempt.turn != null) {
                    turnError(attempt, protocol.textEvent(message).path("error"));
                }
            }
            case "session.created" -> {
                if (status.state() == State.CONNECTING && attempt.sessionId == null) {
                    attempt.sessionId = event.sessionId();
                    sendConfiguration(attempt);
                }
            }
            case "session.updated" -> {
                if (status.state() == State.CONNECTING && event.sessionId().equals(attempt.sessionId)) {
                    if (!event.outputConfigurationConfirmed()) {
                        fail(attempt, Failure.PROTOCOL);
                        return;
                    }
                    attempt.updateAcknowledged = true;
                    checkReady(attempt);
                }
            }
            case "response.function_call_arguments.done" -> {
                // Replays and rejected requests also consume budget. Dispatch remains a separate check.
                if (status.state() == State.READY && ++attempt.toolCalls > MAX_TOOL_CALLS) {
                    stopAtLimit(attempt, Failure.TOOL_LIMIT);
                }
            }
            case "response.output_audio.delta" -> {
                if (status.state() == State.READY) {
                    accountForAudio(attempt, event.responseId(), event.audioBytes());
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
                if (status.state() == State.READY && turn != null && event.responseId().equals(turn.responseId)) {
                    turn.append(event.textDelta());
                    statusChanged.run();
                }
            }
            default -> {
                // Future GA events do not participate in this lifecycle.
            }
        }
    }

    private void accountForAudio(Attempt attempt, String responseId, int bytes) throws IOException {
        // Each response has its own budget. The number tracked at once stays bounded.
        if (!attempt.audioBytes.containsKey(responseId) && attempt.audioBytes.size() >= MAX_AUDIO_RESPONSES) {
            throw new IOException("Too many concurrent audio responses");
        }
        long total = attempt.audioBytes.merge(responseId, (long) bytes, Long::sum);
        if (total > SessionProtocol.PCM_BYTES_PER_SECOND * MAX_AUDIO_TIME.toSeconds()) {
            stopAtLimit(attempt, Failure.AUDIO_LIMIT);
        }
    }

    private void responseDone(Attempt attempt, JsonNode response) throws IOException {
        var id = response.path("id").textValue();
        if (id == null || id.isBlank()) {
            throw new IOException("Missing completed Realtime response ID");
        }
        attempt.audioBytes.remove(id);
        var outcome = response.path("status").asText();
        var turn = attempt.turn;
        boolean matchesTurn = turn != null && (id.equals(turn.responseId) || matchesUnboundResponse(turn, response));
        if (matchesTurn) {
            // The terminal output is what the provider keeps, including text generated before a cancel applied.
            turn.reconcile(protocol.terminalText(response));
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
                    var id = response.path("id").textValue();
                    if (id == null || id.isBlank()) {
                        throw new IOException("Missing Realtime response ID");
                    }
                    turn.responseId = id;
                    clearPhaseDeadline(attempt);
                    advanceTurn(attempt);
                }
            }
            default -> throw new IllegalArgumentException("Unexpected text conversation event");
        }
    }

    private boolean matchesUnboundResponse(TextTurn turn, JsonNode response) {
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
        } else {
            return;
        }
        statusChanged.run();
    }

    private void finishTurn(Attempt attempt, TurnState outcome) {
        if (attempt.turn != null) {
            clearPhaseDeadline(attempt);
            attempt.turn.finish(outcome);
            attempt.turn = null;
        }
    }

    private void advanceTurn(Attempt attempt) {
        var turn = attempt.turn;
        if (turn == null || attempt.writing || !withinSessionTime(attempt)) {
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
            attempt.socket.sendText(protocol.configuration(), true).whenComplete((socket, error) -> {
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

    private final class Attempt implements WebSocket.Listener {
        private final long startedAt = nanoTime.getAsLong();
        private CompletableFuture<WebSocket> opening;
        private CompletableFuture<Void> phaseDeadline;
        private CompletableFuture<Void> sessionDeadline;
        private WebSocket socket;
        private StringBuilder message;
        private String sessionId;
        private TextTurn turn;
        private boolean writing;
        private boolean cancelBeforeClose;
        private final Map<String, Long> audioBytes = new HashMap<>();
        private int toolCalls;
        private Failure closeFailure;
        private boolean updateSent;
        private boolean updateAcknowledged;
        private boolean closeSent;
        private Integer peerCloseCode;

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

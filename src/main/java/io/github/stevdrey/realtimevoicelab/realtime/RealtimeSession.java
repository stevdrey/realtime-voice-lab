package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

public final class RealtimeSession implements AutoCloseable {
    public static final String DEFAULT_MODEL = "gpt-realtime-2.1-mini";

    public enum State {
        DISCONNECTED, CONNECTING, READY, CLOSING, ERROR
    }

    public enum Failure {
        MISSING_CREDENTIAL, AUTHENTICATION, CONNECTION, TIMEOUT, PROTOCOL, PROVIDER, REMOTE_CLOSE
    }

    public record Status(State state, Failure failure) {
    }

    private final RealtimeTransport transport;
    private final Runnable statusChanged;
    private final Duration setupTimeout;
    private final Duration closeTimeout;
    private final SessionProtocol protocol = new SessionProtocol();

    // Commands, listener callbacks, write completions and deadlines share this monitor.
    private Status status = new Status(State.DISCONNECTED, null);
    private Attempt active;
    private boolean disposed;

    public RealtimeSession(RealtimeTransport transport, Runnable statusChanged) {
        this(transport, statusChanged, Duration.ofSeconds(15), Duration.ofSeconds(3));
    }

    RealtimeSession(RealtimeTransport transport, Runnable statusChanged, Duration setupTimeout, Duration closeTimeout) {
        this.transport = transport;
        this.statusChanged = statusChanged;
        this.setupTimeout = setupTimeout;
        this.closeTimeout = closeTimeout;
    }

    public synchronized Status status() {
        return status;
    }

    public synchronized void connect(String model) {
        if (disposed || active != null) {
            return;
        }

        var attempt = new Attempt();
        active = attempt;
        publish(State.CONNECTING, null);
        startDeadline(attempt, setupTimeout);
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
        if (attempt == null || status.state() == State.CLOSING) {
            return;
        }

        if (status.state() == State.CONNECTING) {
            finish(attempt, null, true);
            return;
        }

        // READY requires both the session.update write and its acknowledgement.
        // As a result, this close frame cannot overtake a text write.
        publish(State.CLOSING, null);
        startDeadline(attempt, closeTimeout);
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
        if (attempt.closeSent && attempt.peerCloseCode != null) {
            finishPeerClose(attempt, attempt.peerCloseCode);
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
        if (active != null) {
            finish(active, null, true);
        }
        transport.close();
    }

    private void publish(State state, Failure failure) {
        status = new Status(state, failure);
        statusChanged.run();
    }

    private void startDeadline(Attempt attempt, Duration timeout) {
        attempt.deadline = new CompletableFuture<>();
        attempt.deadline.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        fail(attempt, Failure.TIMEOUT);
                    }
                });
    }

    private synchronized void fail(Attempt attempt, Failure failure) {
        if (active == attempt) {
            finish(attempt, failure, true);
        }
    }

    private void finish(Attempt attempt, Failure failure, boolean abort) {
        active = null;
        attempt.deadline.complete(null);
        attempt.message = null;
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
        if (attempt.message == null) {
            attempt.message = new StringBuilder();
        }
        if (fragment.length() > SessionProtocol.MAX_MESSAGE_CHARS - attempt.message.length()) {
            throw new IOException("Realtime message limit exceeded");
        }
        attempt.message.append(fragment);
        if (!last) {
            return;
        }

        var message = attempt.message.toString();
        attempt.message = null;
        var event = protocol.parse(message);
        switch (event.type()) {
            case "error" -> fail(attempt, Failure.PROVIDER);
            case "session.created" -> {
                if (status.state() == State.CONNECTING && attempt.sessionId == null) {
                    attempt.sessionId = event.sessionId();
                    sendConfiguration(attempt);
                }
            }
            case "session.updated" -> {
                if (status.state() == State.CONNECTING && event.sessionId().equals(attempt.sessionId)) {
                    attempt.updateAcknowledged = true;
                    checkReady(attempt);
                }
            }
            default -> {
                // Future GA events do not participate in this lifecycle.
            }
        }
    }

    private void sendConfiguration(Attempt attempt) {
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
        if (attempt.updateSent && attempt.updateAcknowledged && attempt.deadline.complete(null)) {
            publish(State.READY, null);
        }
    }

    private final class Attempt implements WebSocket.Listener {
        private CompletableFuture<WebSocket> opening;
        private CompletableFuture<Void> deadline;
        private WebSocket socket;
        private StringBuilder message;
        private String sessionId;
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
                socket.request(1);
            }
        }

        @Override
        public CompletableFuture<Void> onText(WebSocket webSocket, CharSequence fragment, boolean last) {
            synchronized (RealtimeSession.this) {
                if (active == this && status.state() != State.CLOSING) {
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

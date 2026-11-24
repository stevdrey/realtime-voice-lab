package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

public interface RealtimeTransport extends AutoCloseable {
    /**
     * Starts an attempt without blocking the caller. Cancelling the returned future cancels
     * setup and the handshake if either is still pending.
     */
    CompletableFuture<WebSocket> open(String model, WebSocket.Listener listener);

    @Override
    default void close() {
    }

    final class MissingCredential extends RuntimeException {
    }
}

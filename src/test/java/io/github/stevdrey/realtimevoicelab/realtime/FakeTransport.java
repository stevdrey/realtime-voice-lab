package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class FakeTransport implements RealtimeTransport {
    public final List<Socket> attempts = new ArrayList<>();
    public boolean closed;

    @Override
    public CompletableFuture<WebSocket> open(String model, WebSocket.Listener listener) {
        var socket = new Socket(model, listener);
        attempts.add(socket);
        return socket.opening;
    }

    @Override
    public void close() {
        closed = true;
    }

    public static final class Socket implements WebSocket {
        public final String model;
        public final CompletableFuture<WebSocket> opening = new CompletableFuture<>();
        public final CompletableFuture<WebSocket> textWrite = new CompletableFuture<>();
        CompletableFuture<WebSocket> cancelWrite = CompletableFuture.completedFuture(this);
        CompletableFuture<WebSocket> closeWrite = CompletableFuture.completedFuture(this);
        public final List<String> writes = new ArrayList<>();
        public int demand;
        public int closeWrites;
        public boolean aborted;
        private final Listener listener;

        private Socket(String model, Listener listener) {
            this.model = model;
            this.listener = listener;
        }

        public void opened() {
            listener.onOpen(this);
            opening.complete(this);
        }

        public void text(String fragment, boolean last) {
            listener.onText(this, fragment, last);
        }

        public void created() {
            text("""
                    {"type":"session.created","session":{"type":"realtime","id":"test-session"}}
                    """, true);
        }

        public void updated() {
            text("""
                    {"type":"session.updated","session":{"type":"realtime","id":"test-session","max_output_tokens":512}}
                    """, true);
        }

        public void ready() {
            opened();
            created();
            textWrite.complete(this);
            updated();
        }

        public void peerClose(int code, String detail) {
            listener.onClose(this, code, detail);
        }

        public void fail(Throwable error) {
            listener.onError(this, error);
        }

        public void binary() {
            listener.onBinary(this, ByteBuffer.allocate(1), true);
        }

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (last && writes.isEmpty()) {
                writes.add(data.toString());
                return textWrite;
            }
            if (!last || writes.size() != 1 || !textWrite.isDone()
                    || !SessionProtocol.CANCEL_RESPONSE.contentEquals(data)) {
                throw new AssertionError("Unexpected text write");
            }
            writes.add(data.toString());
            return cancelWrite;
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            if (!textWrite.isDone() || (writes.size() > 1 && !cancelWrite.isDone())
                    || statusCode != NORMAL_CLOSURE || !reason.isEmpty()) {
                throw new AssertionError("Unexpected or overlapping close write");
            }
            closeWrites++;
            return closeWrite;
        }

        @Override
        public void request(long count) {
            demand += Math.toIntExact(count);
        }

        @Override
        public void abort() {
            aborted = true;
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return aborted || closeWrites > 0;
        }

        @Override
        public boolean isInputClosed() {
            return aborted;
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }
    }
}

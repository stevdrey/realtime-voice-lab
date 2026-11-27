package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class FakeTransport implements RealtimeTransport {
    public final List<Socket> attempts = new ArrayList<>();
    public boolean closed;

    static String withToolConfiguration(String json) {
        try {
            var event = new ObjectMapper().readTree(json);
            new LocalTools().declare((ObjectNode) event.path("session"), SessionSettings.DEFAULT.toolPreset());
            return event.toString();
        } catch (JsonProcessingException error) {
            throw new AssertionError("Invalid session fixture", error);
        }
    }

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
        CompletableFuture<WebSocket> nextTurnWrite = CompletableFuture.completedFuture(this);
        private CompletableFuture<WebSocket> pendingWrite = textWrite;
        private boolean closeCancelWritten;
        public final List<String> writes = new CopyOnWriteArrayList<>();
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
            updated(null);
        }

        public void updated(String transcriptionModel) {
            updated(SessionSettings.DEFAULT, transcriptionModel);
        }

        public void updated(SessionSettings settings, String transcriptionModel) {
            updated(settings, transcriptionModel, "audio/pcm");
        }

        void updated(SessionSettings settings, String transcriptionModel, String outputFormat) {
            var event = new ObjectMapper().createObjectNode().put("type", "session.updated");
            var session = event.putObject("session").put("type", "realtime").put("id", "test-session")
                    .put("max_output_tokens", 512).put("model", settings.model().id())
                    .put("instructions", settings.instructions());
            new LocalTools().declare(session, settings.toolPreset());
            session.putArray("output_modalities").add(settings.mode().modality());
            var audio = session.putObject("audio");
            var input = audio.putObject("input");
            var detection = settings.effectiveTurnDetection();
            if (detection.automatic()) {
                input.putObject("turn_detection").put("type", detection.type())
                        .put("create_response", false).put("interrupt_response", false);
            } else {
                input.putNull("turn_detection");
            }
            input.putObject("format").put("type", "audio/pcm").put("rate", 24000);
            if (transcriptionModel == null) {
                input.putNull("transcription");
            } else {
                input.putObject("transcription").put("model", transcriptionModel);
            }
            var output = audio.putObject("output").put("voice", settings.voice().id())
                    .putObject("format").put("type", outputFormat);
            if ("audio/pcm".equals(outputFormat)) {
                output.put("rate", 24000);
            }
            text(event.toString(), true);
        }

        public void ready() {
            ready(null);
        }

        public void ready(String transcriptionModel) {
            opened();
            created();
            textWrite.complete(this);
            updated(transcriptionModel);
        }

        public void truncated(String itemId, int audioEndMs) {
            text("""
                    {"type":"conversation.item.truncated","item_id":"%s","content_index":0,"audio_end_ms":%d}
                    """.formatted(itemId, audioEndMs), true);
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
            if (!last || !pendingWrite.isDone()) {
                throw new AssertionError("Unexpected or overlapping text write");
            }
            if (closeCancelWritten) {
                // The generic cancel precedes close, so nothing else may follow it.
                throw new AssertionError("Text write after the close cancellation");
            }
            writes.add(data.toString());
            if (SessionProtocol.CANCEL_RESPONSE.message().contentEquals(data)) {
                closeCancelWritten = true;
                pendingWrite = cancelWrite;
            } else {
                pendingWrite = nextTurnWrite;
                nextTurnWrite = CompletableFuture.completedFuture(this);
            }
            return pendingWrite;
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            if (!pendingWrite.isDone()
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

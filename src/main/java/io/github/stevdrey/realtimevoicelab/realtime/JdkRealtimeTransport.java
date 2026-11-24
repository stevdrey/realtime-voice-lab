package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class JdkRealtimeTransport implements RealtimeTransport {
    private static final String ENDPOINT = "wss://api.openai.com/v1/realtime?model=";
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Supplier<String> apiKey;
    private final Supplier<WebSocket.Builder> socketBuilder;
    private final ThreadPoolExecutor setupWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofVirtual().name("realtime-websocket-setup").factory());
    private HttpClient client;

    public JdkRealtimeTransport() {
        apiKey = () -> System.getenv("OPENAI_API_KEY");
        socketBuilder = this::newSocketBuilder;
    }

    JdkRealtimeTransport(Supplier<String> apiKey, Supplier<WebSocket.Builder> socketBuilder) {
        this.apiKey = apiKey;
        this.socketBuilder = socketBuilder;
    }

    @Override
    public CompletableFuture<WebSocket> open(String model, WebSocket.Listener listener) {
        var result = new CompletableFuture<WebSocket>();
        var setup = new FutureTask<Void>(() -> {
            try {
                startHandshake(model, listener, result);
            } catch (RuntimeException error) {
                result.completeExceptionally(error);
            }
            return null;
        });
        result.whenComplete((socket, error) -> {
            if (result.isCancelled()) {
                setup.cancel(true);
                setupWorker.remove(setup);
            }
        });
        try {
            setupWorker.execute(setup);
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    // JDK client creation and buildAsync can perform synchronous TLS and proxy setup.
    private void startHandshake(String model, WebSocket.Listener listener, CompletableFuture<WebSocket> result) {
        if (model == null || !MODEL_ID.matcher(model).matches()) {
            throw new IllegalArgumentException("Invalid model ID");
        }
        var key = apiKey.get();
        if (key == null || key.isBlank()) {
            throw new MissingCredential();
        }

        var builder = socketBuilder.get()
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key);
        if (result.isCancelled()) {
            return;
        }
        var handshake = builder.buildAsync(URI.create(ENDPOINT + model), listener);
        result.whenComplete((socket, error) -> {
            if (result.isCancelled()) {
                handshake.cancel(true);
            }
        });
        handshake.whenComplete((socket, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else if (!result.complete(socket)) {
                socket.abort();
            }
        });
    }

    private WebSocket.Builder newSocketBuilder() {
        synchronized (this) {
            if (client != null) {
                return client.newWebSocketBuilder();
            }
        }
        var created = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        synchronized (this) {
            if (!setupWorker.isShutdown()) {
                client = created;
                return created.newWebSocketBuilder();
            }
        }
        created.shutdownNow();
        throw new CancellationException();
    }

    @Override
    public void close() {
        setupWorker.shutdownNow();
        HttpClient closing;
        synchronized (this) {
            closing = client;
            client = null;
        }
        if (closing != null) {
            closing.shutdownNow();
        }
    }
}

package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class JdkRealtimeTransport implements RealtimeTransport {
    private static final String ENDPOINT = "wss://api.openai.com/v1/realtime?model=";
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Supplier<String> apiKey;
    private final Supplier<WebSocket.Builder> socketBuilder;
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
        if (model == null || !MODEL_ID.matcher(model).matches()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid model ID"));
        }

        var key = apiKey.get();
        if (key == null || key.isBlank()) {
            return CompletableFuture.failedFuture(new MissingCredential());
        }

        return socketBuilder.get()
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key)
                .buildAsync(URI.create(ENDPOINT + model), listener);
    }

    private WebSocket.Builder newSocketBuilder() {
        if (client == null) {
            client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        }
        return client.newWebSocketBuilder();
    }

    @Override
    public void close() {
        if (client != null) {
            client.shutdownNow();
            client = null;
        }
    }
}

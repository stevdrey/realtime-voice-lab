package io.github.stevdrey.realtimevoicelab.realtime;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.DEFAULT_MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

class JdkRealtimeTransportTest {
    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void sendsOnlyBearerAuthorizationAndSanitizesAuthenticationRejection(int statusCode) {
        var builder = new FakeBuilder();
        var transport = new JdkRealtimeTransport(() -> "synthetic-token", () -> builder);
        try (var session = new RealtimeSession(transport, () -> {})) {
            assertNull(builder.uri);
            session.connect(DEFAULT_MODEL);

            assertEquals(URI.create("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1-mini"), builder.uri);
            assertEquals(Map.of("Authorization", "Bearer synthetic-token"), builder.headers);
            assertEquals(Duration.ofSeconds(10), builder.timeout);

            var response = (HttpResponse<?>) Proxy.newProxyInstance(
                    HttpResponse.class.getClassLoader(), new Class<?>[]{HttpResponse.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("statusCode")) {
                            return statusCode;
                        }
                        throw new AssertionError("Unexpected handshake response read");
                    });
            builder.handshake.completeExceptionally(new WebSocketHandshakeException(response));
            assertEquals(new RealtimeSession.Status(RealtimeSession.State.ERROR,
                    RealtimeSession.Failure.AUTHENTICATION), session.status());
        }
    }

    @Test
    void missingCredentialNeverBuildsASocket() {
        var transport = new JdkRealtimeTransport(() -> null, () -> fail("Unexpected socket builder"));
        try (var session = new RealtimeSession(transport, () -> {})) {
            session.connect(DEFAULT_MODEL);
            assertEquals(new RealtimeSession.Status(RealtimeSession.State.ERROR,
                    RealtimeSession.Failure.MISSING_CREDENTIAL), session.status());
        }
    }

    @Test
    void modelCannotChangeEndpointOrInjectQueryParameters() {
        var transport = new JdkRealtimeTransport(() -> "synthetic-token", () -> fail("Unexpected socket builder"));
        try (var session = new RealtimeSession(transport, () -> {})) {
            session.connect("mini&model=another-model");
            assertEquals(new RealtimeSession.Status(RealtimeSession.State.ERROR,
                    RealtimeSession.Failure.CONNECTION), session.status());
        }
    }

    private static final class FakeBuilder implements WebSocket.Builder {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
        private Duration timeout;
        private URI uri;

        @Override
        public WebSocket.Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        @Override
        public WebSocket.Builder connectTimeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        @Override
        public WebSocket.Builder subprotocols(String mostPreferred, String... lesserPreferred) {
            throw new AssertionError("Unexpected subprotocol");
        }

        @Override
        public CompletableFuture<WebSocket> buildAsync(URI uri, WebSocket.Listener listener) {
            this.uri = uri;
            return handshake;
        }
    }
}

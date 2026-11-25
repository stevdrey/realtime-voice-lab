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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.DEFAULT_MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class JdkRealtimeTransportTest {
    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void sendsOnlyBearerAuthorizationAndSanitizesAuthenticationRejection(int statusCode) throws Exception {
        var builder = new FakeBuilder();
        var transport = new JdkRealtimeTransport(() -> "synthetic-token", () -> builder);
        var failed = new CountDownLatch(2);
        try (var session = new RealtimeSession(transport, failed::countDown)) {
            assertNull(builder.uri);
            session.connect(DEFAULT_MODEL);
            assertTrue(builder.started.await(5, TimeUnit.SECONDS));

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
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(new RealtimeSession.Status(RealtimeSession.State.ERROR,
                    RealtimeSession.Failure.AUTHENTICATION), session.status());
        }
    }

    @Test
    void missingCredentialNeverBuildsASocket() throws Exception {
        var transport = new JdkRealtimeTransport(() -> null, () -> fail("Unexpected socket builder"));
        var failed = new CountDownLatch(2);
        try (var session = new RealtimeSession(transport, failed::countDown)) {
            session.connect(DEFAULT_MODEL);
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertEquals(new RealtimeSession.Status(RealtimeSession.State.ERROR,
                    RealtimeSession.Failure.MISSING_CREDENTIAL), session.status());
        }
    }

    @Test
    void modelCannotChangeEndpointOrInjectQueryParameters() {
        try (var transport = new JdkRealtimeTransport(() -> "synthetic-token", () -> fail("Unexpected socket builder"))) {
            var opening = transport.open("mini&model=another-model", new WebSocket.Listener() {});
            var failure = assertThrows(ExecutionException.class, () -> opening.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blockedSetupCanBeCancelledOrTimeOutAndCancelsLateHandshake(boolean cancel) throws Exception {
        var caller = Thread.currentThread();
        var setupThread = new CompletableFuture<Thread>();
        var releaseSetup = new CompletableFuture<Void>();
        var builder = new FakeBuilder(() -> {
            setupThread.complete(Thread.currentThread());
            // Simulate a proxy selector that continues running after interruption.
            releaseSetup.orTimeout(10, TimeUnit.SECONDS).join();
        });
        var transport = new JdkRealtimeTransport(() -> "synthetic-token", () -> builder);
        var ended = new CountDownLatch(2);
        try (var session = new RealtimeSession(transport, ended::countDown,
                Duration.ofSeconds(cancel ? 15 : 1), Duration.ofSeconds(3))) {
            session.connect(DEFAULT_MODEL);
            assertNotSame(caller, setupThread.get(5, TimeUnit.SECONDS));
            if (cancel) {
                session.disconnect();
            }
            assertTrue(ended.await(5, TimeUnit.SECONDS));
            var expected = cancel
                    ? new RealtimeSession.Status(RealtimeSession.State.DISCONNECTED, null)
                    : new RealtimeSession.Status(RealtimeSession.State.ERROR, RealtimeSession.Failure.TIMEOUT);
            assertEquals(expected, session.status());

            releaseSetup.complete(null);
            assertTrue(builder.handshake.handle((socket, error) -> builder.handshake.isCancelled())
                    .get(5, TimeUnit.SECONDS));
        } finally {
            releaseSetup.complete(null);
        }
    }

    private static final class FakeBuilder implements WebSocket.Builder {
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CompletableFuture<WebSocket> handshake = new CompletableFuture<>();
        private final Runnable beforeHandshake;
        private Duration timeout;
        private URI uri;

        private FakeBuilder() {
            this(() -> {});
        }

        private FakeBuilder(Runnable beforeHandshake) {
            this.beforeHandshake = beforeHandshake;
        }

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
            beforeHandshake.run();
            started.countDown();
            return handshake;
        }
    }
}

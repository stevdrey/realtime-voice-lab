package io.github.stevdrey.realtimevoicelab.realtime;

import java.math.BigDecimal;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.Failure;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.RateLimit;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.State;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnState;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeUsageTest {
    private static final String MIXED_USAGE = """
            {"input_tokens":150,"output_tokens":70,"total_tokens":220,
             "input_token_details":{"text_tokens":100,"audio_tokens":50,"cached_tokens":30,
                                    "cached_tokens_details":{"text_tokens":20,"audio_tokens":10,"image_tokens":0}},
             "output_token_details":{"text_tokens":30,"audio_tokens":40,"reasoning_tokens":12}}
            """;
    private static final String ZERO_USAGE = """
            {"input_tokens":0,"output_tokens":0}
            """;
    private final FakeTransport transport = new FakeTransport();
    private final AtomicLong nanos = new AtomicLong();
    private RealtimeSession session;

    @AfterEach
    void cleanup() {
        session.close();
    }

    @Test
    void sumsEachPricedBucketAndCountsReasoningWithinOutput() {
        var socket = connect(null, null);
        var first = responseDone("first", "completed", MIXED_USAGE);
        socket.text(first.substring(0, 80), false);
        assertNull(usage().estimatedUsd());
        socket.text(first.substring(80), true);
        assertUsd("0.00132420");
        assertEquals(150, usage().reportedInputTokens());
        assertEquals(70, usage().reportedOutputTokens());
        assertEquals(30, usage().reportedCachedTokens());
        assertEquals(12L, usage().reportedReasoningTokens());

        // The total may include reasoning that was broken out from the text detail.
        socket.text(responseDone("second", "incomplete", MIXED_USAGE.replace("\"text_tokens\":30", "\"text_tokens\":18")), true);
        assertUsd("0.00264840");
        assertEquals(140, usage().reportedOutputTokens());
        assertEquals(24L, usage().reportedReasoningTokens());
    }

    @Test
    void deduplicatesCancelledAndFailedResponsesEvenAfterSixtyMoreResponses() {
        var socket = connect(null, null);
        socket.text(responseDone("cancelled", "cancelled", MIXED_USAGE), true);
        socket.text(responseDone("failed", "failed", MIXED_USAGE), true);
        for (int i = 0; i < 65; i++) {
            socket.text(responseDone("later-" + i, "completed", ZERO_USAGE), true);
        }
        socket.text(responseDone("cancelled", "cancelled", "null"), true);
        socket.text(responseDone("failed", "completed", MIXED_USAGE), true);
        assertUsd("0.00264840");
        assertEquals(300, usage().reportedInputTokens());
    }

    @Test
    void canPriceExplicitZeroAndCacheThatBelongsEntirelyToText() {
        var socket = connect(null, null);
        assertNull(usage().estimatedUsd());
        socket.text(responseDone("zero", "completed", ZERO_USAGE), true);
        assertUsd("0");
        socket.text(responseDone("text", "completed", """
                {"input_tokens":50,"output_tokens":5,"input_token_details":{"text_tokens":50,"cached_tokens":10},
                 "output_token_details":{"text_tokens":5}}
                """), true);
        assertUsd("0.00003660");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "{\"input_tokens\":150,\"output_tokens\":70}",
            "{\"input_tokens\":10,\"output_tokens\":0,\"input_token_details\":{\"text_tokens\":5,\"audio_tokens\":5,\"cached_tokens\":3}}",
            "{\"input_tokens\":4,\"output_tokens\":0,\"input_token_details\":{\"text_tokens\":4,\"cached_tokens\":5}}",
            "{\"input_tokens\":0,\"output_tokens\":1,\"output_token_details\":{\"text_tokens\":\"1\"}}",
            "{\"input_tokens\":0,\"output_tokens\":0,\"total_tokens\":1}",
            "{\"input_tokens\":0,\"output_tokens\":0,\"input_token_details\":{\"audio_tokens\":-1}}",
            "{\"input_tokens\":1,\"output_tokens\":0,\"input_token_details\":{\"image_tokens\":1,\"cached_tokens\":0}}"
    })
    void missingOrAmbiguousUsageMakesTheSessionEstimateUnavailable(String data) {
        var socket = connect(null, null);
        socket.text(responseDone("known", "completed", MIXED_USAGE), true);
        assertUsd("0.00132420");
        socket.text(responseDone("unknown", "completed", data), true);
        assertTrue(usage().missingUsage());
        assertUsd("0.00132420");
        assertEquals(State.READY, session.status().state());
        socket.text(responseDone("new-known", "completed", MIXED_USAGE), true);
        assertTrue(usage().missingUsage());
        assertUsd("0.00264840");
        assertEquals(300, usage().reportedInputTokens());
    }

    @Test
    void readsRateLimitsSeparatelyAndIgnoresUnsupportedOrMalformedQuotas() {
        var socket = connect(null, null);
        var initial = """
                {"type":"rate_limits.updated","event_id":"quota-1","rate_limits":[
                 {"name":"requests","limit":10,"remaining":9,"reset_seconds":5.25},
                 {"name":"tokens","limit":1000,"remaining":500,"reset_seconds":0},
                 {"name":"unknown","limit":1,"remaining":0,"reset_seconds":2}]}
                """;
        socket.text(initial, true);
        assertEquals(List.of(new RateLimit("requests", 10, 9, 5.25), new RateLimit("tokens", 1000, 500, 0)),
                usage().rateLimits());
        assertNull(usage().estimatedUsd());
        socket.text(responseDone("r", "completed", MIXED_USAGE), true);
        socket.text("""
                {"type":"rate_limits.updated","event_id":"quota-2","rate_limits":[
                 {"name":"tokens","limit":1000,"remaining":850,"reset_seconds":0.5},
                 {"name":"requests","limit":10,"remaining":11,"reset_seconds":-1}]}
                """, true);
        socket.text(initial, true);
        assertEquals(List.of(new RateLimit("tokens", 1000, 850, 0.5)), usage().rateLimits());
        assertUsd("0.00132420");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            ",\"rate_limits\":null",
            ",\"rate_limits\":[]",
            ",\"rate_limits\":[{\"name\":\"tokens\",\"limit\":5,\"remaining\":6,\"reset_seconds\":1}]",
            """
            ,"rate_limits":[
              {"name":"tokens","limit":10,"remaining":7,"reset_seconds":1},
              {"name":"tokens","limit":10,"remaining":6,"reset_seconds":1}]
            """
    })
    void keepsTheLatestValidQuotaSnapshotWhenAnUpdateHasNoUsableSnapshot(String malformedFields) {
        var socket = connect(null, null);
        socket.text("""
                {"type":"rate_limits.updated","event_id":"valid","rate_limits":[
                  {"name":"tokens","limit":1000,"remaining":800,"reset_seconds":15}]}
                """, true);
        socket.text("""
                {"type":"rate_limits.updated","event_id":"malformed"%s}
                """.formatted(malformedFields), true);
        assertEquals(List.of(new RateLimit("tokens", 1000, 800, 15)), usage().rateLimits());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsDuplicateQuotaNamesRegardlessOfWhichEntryIsInvalid(boolean invalidFirst) {
        var socket = connect(null, null);
        socket.text("""
                {"type":"rate_limits.updated","event_id":"initial","rate_limits":[
                  {"name":"requests","limit":10,"remaining":9,"reset_seconds":15},
                  {"name":"tokens","limit":1000,"remaining":800,"reset_seconds":15}]}
                """, true);
        socket.text("""
                {"type":"rate_limits.updated","event_id":"duplicate","rate_limits":[
                  {"name":"tokens","limit":1000,"remaining":%d,"reset_seconds":1},
                  {"name":"tokens","limit":1000,"remaining":%d,"reset_seconds":1}]}
                """.formatted(invalidFirst ? 1100 : 600, invalidFirst ? 600 : 1100), true);
        assertEquals(List.of(new RateLimit("requests", 10, 9, 15), new RateLimit("tokens", 1000, 800, 15)),
                usage().rateLimits());

        socket.text("""
                {"type":"rate_limits.updated","event_id":"distinct","rate_limits":[
                  {"name":"requests","limit":10,"remaining":8,"reset_seconds":2},
                  {"name":"tokens","limit":1000,"remaining":600,"reset_seconds":2}]}
                """, true);
        assertEquals(List.of(new RateLimit("requests", 10, 8, 2), new RateLimit("tokens", 1000, 600, 2)),
                usage().rateLimits());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keepsCompletedTerminalTextWhenTheCutoffEndsTheSession(boolean usageAvailable) {
        var socket = connect(null, "0.00165525");
        assertTrue(session.sendText("Synthetic question"));
        acknowledgeTurn(socket, 1);
        socket.text("""
                {"type":"response.done","response":{"id":"terminal","status":"completed",
                 "metadata":{"rvl_turn":"rvl-1"},"usage":%s,
                 "output":[{"type":"message","role":"assistant",
                            "content":[{"type":"output_text","text":"Synthetic terminal answer."}]}]}}
                """.formatted(usageAvailable ? MIXED_USAGE : "null"), true);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");

        assertEquals(usageAvailable ? Failure.SPEND_LIMIT : Failure.SPEND_ESTIMATE_UNAVAILABLE,
                session.status().failure());
        var turn = session.workspace().turns().getLast();
        assertEquals("Synthetic terminal answer.", turn.output());
        assertEquals(TurnState.COMPLETED, turn.state());
    }

    @Test
    void pendingRequestSurvivesBindingAndBecomesUnavailableIfClosedBeforeTerminalUsage() {
        var socket = connect(null, null);
        nanos.set(Duration.ofSeconds(1).toNanos());
        assertTrue(session.sendText("Synthetic first turn"));
        assertFalse(usage().pending());
        acknowledgeTurn(socket, 1);
        assertTrue(usage().pending());
        socket.text("""
                {"type":"response.created","response":{"id":"one","metadata":{"rvl_turn":"rvl-1"}}}
                """, true);
        assertTrue(usage().pending());
        socket.text(responseDone("one", "completed", MIXED_USAGE), true);
        assertFalse(usage().pending());
        assertUsd("0.00132420");

        assertTrue(session.sendText("Synthetic second turn"));
        acknowledgeTurn(socket, 2);
        assertTrue(usage().pending());
        assertUsd("0.00132420");
        nanos.set(Duration.ofSeconds(3).toNanos());
        session.disconnect();
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertUsd("0.00132420");
        assertTrue(usage().missingUsage());
        assertFalse(usage().pending());
        nanos.set(Duration.ofSeconds(5).toNanos());
        assertEquals(Duration.ofSeconds(3), session.elapsedTime());

        session.connect(Model.MINI.id());
        transport.attempts.getLast().ready();
        socket.text(responseDone("late", "completed", MIXED_USAGE), true);
        assertNull(usage().estimatedUsd());
        assertFalse(usage().missingUsage());
        assertEquals(0, usage().reportedInputTokens());
        assertEquals(Duration.ZERO, session.elapsedTime());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ready", "closing", "terminal"})
    void accountsForCorrelatedTerminalUsageDuringDisconnect(String bindAt) {
        var socket = connect(null, null);
        assertTrue(session.sendText("Synthetic question"));
        acknowledgeTurn(socket, 1);
        var created = """
                {"type":"response.created","response":{"id":"pending","metadata":{"rvl_turn":"rvl-1"}}}
                """;
        if ("ready".equals(bindAt)) {
            socket.text(created, true);
        }
        session.disconnect();
        assertEquals(State.CLOSING, session.status().state());
        assertTrue(usage().pending());
        int writes = socket.writes.size();
        socket.text(responseDone("unrelated", "completed", MIXED_USAGE), true);
        assertTrue(usage().pending());
        assertEquals(0, usage().reportedInputTokens());
        if ("closing".equals(bindAt)) {
            socket.text(created, true);
        }
        var terminal = """
                {"type":"response.done","response":{"id":"pending","status":"%s",%s
                 "usage":%s,"output":[{"type":"message","role":"assistant",
                  "content":[{"type":"output_text","text":"Synthetic late reply."}]},
                  {"type":"function_call","name":"calculate_workspace_counter","call_id":"late_call",
                   "arguments":"{\\"current\\":40,\\"delta\\":2}"}]}}
                """.formatted("closing".equals(bindAt) ? "completed" : "cancelled",
                "terminal".equals(bindAt) ? "\"metadata\":{\"rvl_turn\":\"rvl-1\"}," : "", MIXED_USAGE);
        int split = terminal.length() / 2;
        socket.text(terminal.substring(0, split), false);
        socket.text(terminal.substring(split), true);
        socket.text(terminal, true);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");

        assertEquals(State.DISCONNECTED, session.status().state());
        assertFalse(usage().pending());
        assertFalse(usage().missingUsage());
        assertUsd("0.00132420");
        assertEquals(writes, socket.writes.size());
        var turn = session.workspace().turns().getLast();
        assertEquals("", turn.output());
        assertEquals(TurnState.INTERRUPTED, turn.state());
    }

    @Test
    void matchesTerminalWithoutCreationAndClearsExplicitlyRejectedRequests() {
        var socket = connect(null, null);
        assertTrue(session.sendText("Synthetic first turn"));
        acknowledgeTurn(socket, 1);
        socket.text("""
                {"type":"error","error":{"event_id":"rvl-1-response","code":"synthetic_rejection"}}
                """, true);
        assertFalse(usage().pending());
        assertFalse(usage().missingUsage());
        assertTrue(session.sendText("Synthetic second turn"));
        acknowledgeTurn(socket, 2);
        socket.text("""
                {"type":"response.done","response":{"id":"two","status":"completed","output":[],
                  "metadata":{"rvl_turn":"rvl-2"},"usage":%s}}
                """.formatted(MIXED_USAGE), true);
        assertFalse(usage().pending());
        assertUsd("0.00132420");
    }

    @Test
    void cutoffUsesTwentyPercentMarginAndStopsOtherPendingGeneration() {
        var socket = connect(null, "0.002");
        socket.text(responseDone("first", "completed", MIXED_USAGE), true);
        socket.text(responseDone("first", "completed", MIXED_USAGE), true);
        assertUsd("0.00132420");
        assertEquals(State.READY, session.status().state());
        socket.text("""
                {"type":"response.created","response":{"id":"still-running"}}
                """, true);
        socket.text(responseDone("second", "completed", MIXED_USAGE), true);
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(SessionProtocol.CANCEL_RESPONSE.message(), socket.writes.getLast());
        assertEquals(1, socket.closeWrites);
        socket.text(responseDone("still-running", "cancelled", MIXED_USAGE), true);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(Failure.SPEND_LIMIT, session.status().failure());
        assertFalse(usage().missingUsage());
        assertUsd("0.00397260");
    }

    @Test
    void cutoffAtThresholdBlocksToolContinuationAndKeepsMeasuredTotal() {
        var socket = connect(null, "0.00165525");
        assertTrue(session.sendText("Synthetic tool request"));
        acknowledgeTurn(socket, 1);
        int writes = socket.writes.size();
        socket.text("""
                {"type":"response.done","response":{"id":"tool","status":"completed","metadata":{"rvl_turn":"rvl-1"},
                 "output":[{"type":"function_call","id":"function_1","name":"calculate_workspace_counter",
                            "call_id":"call_first","arguments":"{\\"current\\":40,\\"delta\\":2}"}],
                 "usage":%s}}
                """.formatted(MIXED_USAGE), true);
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(writes, socket.writes.size());
        assertEquals(1, socket.closeWrites);
        assertUsd("0.00132420");
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(Failure.SPEND_LIMIT, session.status().failure());
    }

    @Test
    void optedInCutoffStopsWhenUsageBecomesUnavailable() {
        var socket = connect(null, "0.01");
        socket.text(responseDone("unknown", "failed", "null"), true);
        assertEquals(State.CLOSING, session.status().state());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(Failure.SPEND_ESTIMATE_UNAVAILABLE, session.status().failure());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void blankSpendLimitLeavesTheCutoffDisabled(String limit) {
        var pricing = SessionPricing.load(null, limit);
        assertNull(pricing.cutoff());
        assertFalse(pricing.invalidCutoff());
        createSession(null, limit);
        session.connect(Model.MINI.id());
        assertEquals(State.CONNECTING, session.status().state());
    }

    @Test
    void missingPricesWithCutoffPreventOpeningTheSocket(@TempDir Path local) {
        createSession(local.resolve("missing.properties").toString(), "0.01");
        session.connect(Model.MINI.id());
        assertEquals(Failure.SPEND_CONFIGURATION, session.status().failure());
        assertTrue(transport.attempts.isEmpty());
    }

    @Test
    void loadsDatedConfiguredPricesByModelAndDoesNotFallBackForAnOmittedModel(@TempDir Path local) throws Exception {
        var override = local.resolve("rates.properties");
        Files.writeString(override, """
                gpt-realtime-2.1.date=2026-10-01
                gpt-realtime-2.1.text-input=1
                gpt-realtime-2.1.cached-text-input=2
                gpt-realtime-2.1.text-output=3
                gpt-realtime-2.1.audio-input=4
                gpt-realtime-2.1.cached-audio-input=5
                gpt-realtime-2.1.audio-output=6
                """);
        var socket = connect(override.toString(), null);
        socket.text(responseDone("unpriced", "completed", MIXED_USAGE), true);
        assertNull(usage().pricingDate());
        assertNull(usage().estimatedUsd());
        assertFalse(usage().missingUsage());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        session.connect(Model.STANDARD.id());
        socket = transport.attempts.getLast();
        socket.opened();
        socket.created();
        socket.textWrite.complete(socket);
        var defaults = SessionSettings.DEFAULT;
        socket.updated(new SessionSettings(Model.STANDARD, defaults.voice(), defaults.mode(), defaults.instructions()), null);
        socket.text(responseDone("priced", "completed", MIXED_USAGE), true);
        assertEquals(LocalDate.parse("2026-10-01"), usage().pricingDate());
        assertUsd("0.000660");
    }

    @Test
    void transcriptionIsTrackedSeparatelyFromResponsePricing() {
        createSession(null, null);
        session.connect(Model.MINI.id(), TranscriptionModel.TRANSCRIBE);
        var socket = transport.attempts.getLast();
        socket.ready(TranscriptionModel.TRANSCRIBE.modelId());
        assertTrue(usage().transcriptionEnabled());
        assertFalse(usage().transcriptionUsed());
        socket.text("""
                {"type":"input_audio_buffer.committed","item_id":"synthetic-audio"}
                """, true);
        socket.text("""
                {"type":"conversation.item.input_audio_transcription.completed","item_id":"synthetic-audio",
                 "content_index":0,"usage":{"type":"duration","seconds":8}}
                """, true);
        assertTrue(usage().transcriptionUsed());
        assertNull(usage().estimatedUsd());
        socket.text(responseDone("realtime", "completed", MIXED_USAGE), true);
        assertUsd("0.00132420");
    }

    private FakeTransport.Socket connect(String pricingFile, String cutoff) {
        createSession(pricingFile, cutoff);
        session.connect(Model.MINI.id());
        var socket = transport.attempts.getLast();
        socket.ready();
        return socket;
    }

    private void createSession(String pricingFile, String cutoff) {
        session = new RealtimeSession(transport, () -> {}, Duration.ofSeconds(15), Duration.ofSeconds(3),
                nanos::get, ignored -> new CompletableFuture<>(), new MicrophoneCapture(), new SpeakerPlayback(),
                () -> {}, Runnable::run, SessionPricing.load(pricingFile, cutoff));
    }

    private RealtimeSession.UsageView usage() {
        return session.workspace().usage();
    }

    private void assertUsd(String expected) {
        assertEquals(new BigDecimal(expected), usage().estimatedUsd().setScale(new BigDecimal(expected).scale()));
    }

    private String responseDone(String id, String status, String data) {
        return """
                {"type":"response.done","response":{"id":"%s","status":"%s","output":[],"usage":%s}}
                """.formatted(id, status, data);
    }

    private void acknowledgeTurn(FakeTransport.Socket socket, int turn) {
        socket.text("""
                {"type":"conversation.item.added","item":{"id":"rvl-%d","type":"message","role":"user"}}
                """.formatted(turn), true);
    }
}

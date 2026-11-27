package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.audio.SpeakerPlayback;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.ToolPreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.Failure;
import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.State;
import static io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnState;
import static org.junit.jupiter.api.Assertions.*;

class RealtimeToolCallingTest {
    private static final String COUNTER = "calculate_workspace_counter";
    private static final String ARGUMENTS = "{\"current\":40,\"delta\":2}";
    private final ObjectMapper json = new ObjectMapper();
    private final FakeTransport transport = new FakeTransport();
    private final ArrayDeque<Runnable> workspaceTasks = new ArrayDeque<>();
    private final AtomicLong now = new AtomicLong(Long.MAX_VALUE - Duration.ofSeconds(1).toNanos());
    private LongSupplier clock = now::get;
    private CompletableFuture<Void> latestDeadline;
    private final RealtimeSession session = new RealtimeSession(transport, () -> {},
            Duration.ofSeconds(15), Duration.ofSeconds(3), () -> clock.getAsLong(),
            nanos -> latestDeadline = new CompletableFuture<>(), new MicrophoneCapture(),
            new SpeakerPlayback(), () -> {}, workspaceTasks::addLast);
    private FakeTransport.Socket socket;

    @BeforeEach
    void connect() {
        session.connect(RealtimeSession.DEFAULT_MODEL);
        socket = transport.attempts.getLast();
        socket.ready();
        assertEquals(State.READY, session.status().state());
    }

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    void declaresTheExactLocalFunctionAndChecksTheEffectiveConfiguration() throws Exception {
        var configured = json.readTree(socket.writes.getFirst()).path("session");
        assertEquals("auto", configured.path("tool_choice").textValue());
        assertEquals(1, configured.path("tools").size());
        var tool = configured.path("tools").get(0);
        assertEquals("function", tool.path("type").textValue());
        assertEquals(COUNTER, tool.path("name").textValue());
        assertEquals(json.readTree("""
                {"type":"object","additionalProperties":false,"required":["current","delta"],
                 "properties":{"current":{"type":"integer","minimum":0,"maximum":100},
                               "delta":{"type":"integer","minimum":-10,"maximum":10}}}
                """), tool.path("parameters"));

        var settings = new SessionSettings(SessionSettings.Model.MINI, SessionSettings.Voice.MARIN,
                SessionSettings.Mode.AUDIO, "New instructions");
        assertTrue(session.configure(settings, State.READY));
        socket.text("""
                {"type":"session.updated","session":{"type":"realtime","id":"test-session","max_output_tokens":512,
                 "tool_choice":"auto","tools":[{"type":"mcp","server_label":"unexpected"}],
                 "model":"gpt-realtime-2.1-mini","instructions":"New instructions","output_modalities":["audio"],
                 "audio":{"input":{"turn_detection":null},
                          "output":{"voice":"marin","format":{"type":"audio/pcm","rate":24000}}}}}
                """, true);
        assertFailure(Failure.SETTINGS_MISMATCH);
    }

    @Test
    void acceptsANormalizedEchoButRejectsChangedBounds() throws Exception {
        var tools = new LocalTools();
        var normalized = json.readTree("""
                {"tool_choice":"auto","tools":[{"type":"function","name":"calculate_workspace_counter",
                 "parameters":{"type":"object","strict":true,"required":["delta","current"],
                  "properties":{"current":{"type":"integer","minimum":0.0,"maximum":100},
                                "delta":{"type":"integer","minimum":-10,"maximum":10.0}}}}]}
                """);
        assertEquals(ToolPreset.DEMO_COUNTER, tools.confirmedPreset(normalized));
        ObjectNode changed = normalized.deepCopy();
        ((ObjectNode) changed.at("/tools/0/parameters/properties/current")).put("maximum", 50);
        assertNull(tools.confirmedPreset(changed));
        ObjectNode open = normalized.deepCopy();
        ((ObjectNode) open.at("/tools/0/parameters")).put("additionalProperties", true);
        assertNull(tools.confirmedPreset(open));
    }

    @Test
    void requiresExplicitWorkspacePresetAndChecksTheEcho() throws Exception {
        assertFalse(session.configure(workspaceSettings(), State.READY));
        assertCallError(call("disabled", "resetWorkspace", "{}"), "disabled", "unknown_tool");
        assertTrue(workspaceTasks.isEmpty());
        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());

        openWorkspaceSession();
        var configured = json.readTree(socket.writes.getFirst()).path("session");
        assertEquals("auto", configured.path("tool_choice").textValue());
        assertEquals(List.of("changeColor", "setTheme", "incrementCounter", "resetWorkspace"),
                configured.path("tools").valueStream().map(tool -> tool.path("name").textValue()).toList());
        assertEquals(List.of("indigo", "blue", "green", "amber"),
                configured.at("/tools/0/parameters/properties/color/enum").valueStream()
                        .map(JsonNode::textValue).toList());
        socket.updated(); // The provider echoed the demo preset instead of the requested workspace.
        assertFailure(Failure.SETTINGS_MISMATCH);
        assertTrue(workspaceTasks.isEmpty());
    }

    @Test
    void appliesCompositeWorkspaceCallsInOrderAndIgnoresDuplicateIds() throws Exception {
        connectWorkspace();
        var key = startTurn("composite");
        int initialWrites = socket.writes.size();
        signal("composite", "count");
        assertTrue(workspaceTasks.isEmpty());
        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());
        var terminal = done("composite", "completed", key,
                call("color", "changeColor", "{\"color\":\"blue\"}"),
                call("theme", "setTheme", "{\"theme\":\"midnight\"}"),
                call("count", "incrementCounter", "{\"delta\":7}"),
                call("count", "incrementCounter", "{\"delta\":3}"));
        socket.text(terminal, true);
        socket.text(terminal, true);
        assertEquals(initialWrites, socket.writes.size());
        assertEquals(1, workspaceTasks.size());
        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());
        workspaceTasks.removeFirst().run();

        assertEquals(new ToyWorkspace(ToyWorkspace.Color.BLUE, ToyWorkspace.Theme.MIDNIGHT, 7),
                session.workspace().toy());
        assertOutput(lastWrite(), "color", """
                {"ok":true,"color":"blue","theme":"slate","counter":0}
                """);
        acknowledge(lastWrite());
        assertOutput(lastWrite(), "theme", """
                {"ok":true,"color":"blue","theme":"midnight","counter":0}
                """);
        acknowledge(lastWrite());
        assertOutput(lastWrite(), "count", """
                {"ok":true,"color":"blue","theme":"midnight","counter":7}
                """);
        acknowledge(lastWrite());
        assertEquals("none", lastWrite().at("/response/tool_choice").textValue());
        assertEquals(initialWrites + 4, socket.writes.size());
        socket.text(done("composite_follow_up", "completed", requestKey(lastWrite())), true);

        key = startTurn("reset");
        socket.text(done("reset", "completed", key, call("count", "incrementCounter", "{\"delta\":10}"),
                call("reset", "resetWorkspace", "{}")), true);
        workspaceTasks.removeFirst().run();
        assertOutput(lastWrite(), "reset", """
                {"ok":true,"color":"indigo","theme":"slate","counter":0}
                """);
        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());
        acknowledge(lastWrite());
        assertEquals("none", lastWrite().at("/response/tool_choice").textValue());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            changeColor                 | {"color":"chartreuse"}                   | invalid_arguments
            changeColor                 | {"color":"blue","path":"elsewhere"}      | invalid_arguments
            changeColor                 | {"color":"blue","color":"green"}         | invalid_arguments
            setTheme                    | {"theme":"system"}                       | invalid_arguments
            setTheme                    | {"theme":null}                           | invalid_arguments
            incrementCounter            | {"delta":11}                             | invalid_arguments
            incrementCounter            | {"delta":1.0}                            | invalid_arguments
            incrementCounter            | {"delta":-1}                             | counter_out_of_range
            resetWorkspace              | {"extra":true}                           | invalid_arguments
            calculate_workspace_counter | {"current":40,"delta":2}                 | unknown_tool
            """)
    void invalidWorkspaceCallsReturnFixedErrorsAndKeepState(String name, String arguments, String error)
            throws Exception {
        connectWorkspace();
        var key = startTurn("invalid");
        socket.text(done("invalid", "completed", key, call("invalid", name, arguments)), true);
        workspaceTasks.removeFirst().run();
        assertOutput(lastWrite(), "invalid", "{\"ok\":false,\"error\":\"" + error + "\"}");
        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "reconnect", "timeout"})
    void queuedWorkspaceBatchCannotMutateAfterItLosesItsTurn(String ending) throws Exception {
        connectWorkspace();
        var key = startTurn("old");
        socket.text(done("old", "completed", key, call("queued", "incrementCounter", "{\"delta\":5}")), true);
        assertEquals(1, workspaceTasks.size());
        switch (ending) {
            case "cancel" -> session.cancelTurn();
            case "reconnect" -> connectWorkspace();
            case "timeout" -> now.addAndGet(RealtimeSession.TOOL_OUTPUT_TIMEOUT.toNanos());
            default -> throw new AssertionError(ending);
        }
        int writes = socket.writes.size();
        workspaceTasks.removeFirst().run();
        assertEquals(writes, socket.writes.size());
        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());
        if ("timeout".equals(ending)) {
            assertFailure(Failure.ITEM_ACK_TIMEOUT);
        } else {
            assertTrue(session.workspace().canSend());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Failure.class, names = {"ITEM_ACK_TIMEOUT", "SESSION_LIMIT"})
    void discardsWorkspaceBatchIfADeadlineExpiresDuringExecution(Failure failure) throws Exception {
        connectWorkspace();
        if (failure == Failure.SESSION_LIMIT) {
            now.addAndGet(RealtimeSession.MAX_SESSION_TIME.toNanos() - 1);
        }
        var key = startTurn("expiring");
        socket.text(done("expiring", "completed", key, call("count", "incrementCounter", "{\"delta\":5}")), true);
        if (failure == Failure.ITEM_ACK_TIMEOUT) {
            now.addAndGet(RealtimeSession.TOOL_OUTPUT_TIMEOUT.toNanos() - 1);
        }

        // Both entry checks see a valid sample. The clock expires during execution, before any timer fires.
        var entryChecks = new AtomicInteger(2);
        clock = () -> {
            long sample = now.get();
            if (entryChecks.decrementAndGet() == 0) {
                now.incrementAndGet();
            }
            return sample;
        };
        int writes = socket.writes.size();
        workspaceTasks.removeFirst().run();

        assertEquals(ToyWorkspace.DEFAULT, session.workspace().toy());
        assertFalse(socket.writes.stream().skip(writes).anyMatch(write -> write.contains("\"function_call_output\"")));
        if (failure == Failure.SESSION_LIMIT) {
            assertEquals(State.CLOSING, session.status().state());
            socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
            assertEquals(new RealtimeSession.Status(State.ERROR, failure), session.status());
        } else {
            assertFailure(failure);
        }
    }

    private SessionSettings workspaceSettings() {
        var defaults = SessionSettings.DEFAULT;
        return new SessionSettings(defaults.model(), defaults.voice(), defaults.mode(), defaults.instructions(),
                defaults.turnDetection(), ToolPreset.TOY_WORKSPACE);
    }

    private void openWorkspaceSession() {
        session.disconnect();
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertTrue(session.configure(workspaceSettings(), State.DISCONNECTED));
        session.connect(RealtimeSession.DEFAULT_MODEL);
        socket = transport.attempts.getLast();
        socket.opened();
        socket.created();
        socket.textWrite.complete(socket);
    }

    private void connectWorkspace() {
        openWorkspaceSession();
        socket.updated(workspaceSettings(), null);
        assertEquals(State.READY, session.status().state());
    }

    @Test
    void sendsOrderedOutputsThenOneTextFollowUpAfterBothWriteAndAcknowledgment() throws Exception {
        var initialKey = startTurn("primary");
        int beforeTools = socket.writes.size();
        socket.text("""
                {"type":"response.function_call_arguments.delta","response_id":"primary",
                 "call_id":"call_a","delta":"ignored fragment"}
                """, true);
        signal("primary", "call_a");
        assertEquals(beforeTools, socket.writes.size());

        var blockedWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = blockedWrite;
        var first = call("call_a", COUNTER, ARGUMENTS);
        var second = call("call_b", COUNTER, "{\"current\":100,\"delta\":-10}");
        var prefix = message("Result follows.");
        var terminal = done("primary", "completed", initialKey, prefix, first, second, first);
        socket.text(terminal.substring(0, terminal.length() / 2), false);
        assertEquals(beforeTools, socket.writes.size());
        socket.text(terminal.substring(terminal.length() / 2), true);
        socket.text(terminal, true);
        var firstOutput = lastWrite();
        assertOutput(firstOutput, "call_a", "{\"ok\":true,\"value\":42}");
        ObjectNode wrongAck = firstOutput.path("item").deepCopy();
        wrongAck.put("call_id", "call_b");
        socket.text(added(wrongAck), true);
        acknowledge(firstOutput);
        acknowledge(firstOutput);
        assertEquals(beforeTools + 1, socket.writes.size());
        assertFalse(session.workspace().canSend());
        blockedWrite.complete(socket);

        var secondOutput = lastWrite();
        assertOutput(secondOutput, "call_b", "{\"ok\":true,\"value\":90}");
        assertEquals(beforeTools + 2, socket.writes.size());
        acknowledge(firstOutput);
        assertEquals(beforeTools + 2, socket.writes.size());
        acknowledge(secondOutput);
        var continuation = lastWrite();
        assertEquals("response.create", continuation.path("type").textValue());
        assertEquals("text", continuation.at("/response/output_modalities/0").textValue());
        assertEquals("none", continuation.at("/response/tool_choice").textValue());
        var followUpKey = requestKey(continuation);
        assertNotEquals(initialKey, followUpKey);
        acknowledge(secondOutput);
        assertEquals(beforeTools + 3, socket.writes.size());
        responseCreated("stale", initialKey);
        socket.text(done("stale", "completed", initialKey, message("Stale reply")), true);
        assertFalse(session.workspace().canSend());
        responseCreated("continuation", followUpKey);
        socket.text(done("continuation", "completed", followUpKey, message("42 and 90.")), true);
        assertEquals(1, session.workspace().turns().size());
        assertEquals("Result follows.\n42 and 90.", session.workspace().turns().getFirst().output());
        assertEquals(TurnState.COMPLETED, session.workspace().turns().getFirst().state());
        assertTrue(session.workspace().canSend());
        assertFalse(session.inspectedEvents().stream().anyMatch(entry ->
                entry.jsonDetail().contains("\"call_a\"") || entry.jsonDetail().contains(COUNTER)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{\"current\":5}", "{\"current\":\"5\",\"delta\":1}",
            "{\"current\":5.0,\"delta\":1}", "{\"current\":101,\"delta\":1}", "{\"current\":5,\"delta\":11}",
            "{\"current\":5,\"delta\":1,\"extra\":\"synthetic-private\"}",
            "{\"current\":5,\"current\":6,\"delta\":1}", "{\"current\":5,\"delta\":1} {}",
            "{\"current\":99999999999999999999999,\"delta\":1}"})
    void malformedArgumentsReturnOnlyAFixedError(String raw) throws Exception {
        assertCallError(call("bad_args", COUNTER, raw), "bad_args", "invalid_arguments");
    }

    @Test
    void rejectsOversizedArgumentsAndUnknownToolsAndReturnsSafeCalculationErrors() throws Exception {
        assertCallError(call("oversized", COUNTER, ARGUMENTS + " ".repeat(1024)), "oversized", "invalid_arguments");
        assertCallError(call("unknown", "synthetic_private_name", ARGUMENTS), "unknown", "unknown_tool");
        assertCallError(call("range", COUNTER, "{\"current\":100,\"delta\":1}"), "range", "counter_out_of_range");
        assertCallError(call("partial", COUNTER, ARGUMENTS).put("status", "incomplete"),
                "partial", "incomplete_call");
    }

    @Test
    void refusesInvalidCallIdsAndPreflightsTheWholeBatchBeforeWriting() throws Exception {
        var key = startTurn("reply");
        int writes = socket.writes.size();
        socket.text(done("reply", "completed", key, call("valid", COUNTER, ARGUMENTS),
                call("contains whitespace", COUNTER, ARGUMENTS)), true);
        assertEquals(writes, socket.writes.size());
        assertFailure(Failure.PROTOCOL);
    }

    @Test
    void deduplicatesCallIdsAcrossResponsesAndDoesNotContinueOnAReplay() throws Exception {
        var firstKey = startTurn("first");
        var call = call("replayed", COUNTER, ARGUMENTS);
        socket.text(done("first", "completed", firstKey, call), true);
        acknowledge(lastWrite());
        socket.text(done("follow_up", "completed", requestKey(lastWrite())), true);

        var secondKey = startTurn("second");
        int writes = socket.writes.size();
        socket.text(done("first", "completed", firstKey, call), true);
        socket.text(done("second", "completed", secondKey, call), true);
        assertEquals(writes, socket.writes.size());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void refusesCallsInTheToolDisabledFollowUp() throws Exception {
        var key = startTurn("reply");
        socket.text(done("reply", "completed", key, call("valid", COUNTER, ARGUMENTS)), true);
        acknowledge(lastWrite());
        var followUpKey = requestKey(lastWrite());
        int writes = socket.writes.size();
        socket.text(done("follow_up", "completed", followUpKey, call("unexpected", COUNTER, ARGUMENTS)), true);
        assertEquals(writes, socket.writes.size());
        assertFailure(Failure.PROTOCOL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "failed", "incomplete"})
    void ignoresFunctionItemsFromUnsuccessfulResponses(String status) throws Exception {
        var key = startTurn("reply");
        signal("reply", "unfinished");
        int writes = socket.writes.size();
        socket.text(done("reply", status, key, call("unfinished", COUNTER, ARGUMENTS)), true);
        assertEquals(writes, socket.writes.size());
        assertTrue(session.workspace().canSend());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "failed", "incomplete", "completed"})
    void cancelledTurnNeverRunsCallsEvenIfTheProviderCompletesBeforeCancel(String status) throws Exception {
        var key = startTurn("reply");
        var call = call("cancel_me", COUNTER, ARGUMENTS);
        socket.text(done("unrelated", "completed", null, call), true);
        signal("reply", "cancel_me");
        session.cancelText();
        assertEquals("response.cancel", lastWrite().path("type").textValue());
        int writes = socket.writes.size();
        socket.text(done("reply", status, key, call), true);
        assertEquals(writes, socket.writes.size());
        assertTrue(session.workspace().canSend());
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, false", "false, true"})
    void waitsForInFlightToolOutputAfterCancellation(boolean writeBeforeCancel, boolean acknowledgeBeforeWrite)
            throws Exception {
        var key = startTurn("reply");
        var blockedWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = blockedWrite;
        socket.text(done("reply", "completed", key, call("a", COUNTER, ARGUMENTS), call("b", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        int writes = socket.writes.size();
        if (writeBeforeCancel) {
            blockedWrite.complete(socket);
        }
        session.cancelTurn();
        assertFalse(session.workspace().canSend());
        ObjectNode unrelated = output.path("item").deepCopy();
        unrelated.put("call_id", "b");
        socket.text(added(unrelated), true);
        assertFalse(session.sendText("Another turn before settlement"));
        if (acknowledgeBeforeWrite) {
            acknowledge(output);
            assertFalse(session.workspace().canSend());
            blockedWrite.complete(socket);
        } else {
            blockedWrite.complete(socket);
            assertFalse(session.workspace().canSend());
            acknowledge(output);
        }
        acknowledge(output);
        assertEquals(writes, socket.writes.size());
        assertEquals(TurnState.CANCELLED, session.workspace().turns().getFirst().state());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void rejectsLateToolOutputErrorAfterCancellation() throws Exception {
        var key = startTurn("reply");
        socket.text(done("reply", "completed", key, call("rejected", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        int writes = socket.writes.size();
        session.cancelTurn();
        rejectOutput(output);
        assertFailure(Failure.PROVIDER);
        acknowledge(output);
        assertEquals(writes, socket.writes.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timesOutUnacknowledgedToolOutputAfterCancellation(boolean deliverTimer) throws Exception {
        var key = startTurn("reply");
        socket.text(done("reply", "completed", key, call("timeout", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        var deadline = latestDeadline;
        int writes = socket.writes.size();
        session.cancelTurn();
        assertSame(deadline, latestDeadline);
        if (deliverTimer) {
            deadline.completeExceptionally(new TimeoutException());
        } else {
            now.addAndGet(RealtimeSession.TOOL_OUTPUT_TIMEOUT.toNanos());
            acknowledge(output);
        }
        assertFailure(Failure.ITEM_ACK_TIMEOUT);
        assertEquals(writes, socket.writes.size());
    }

    @Test
    void failsACancelledTurnIfTheAcknowledgedToolOutputWriteFails() throws Exception {
        var key = startTurn("reply");
        var blockedWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = blockedWrite;
        socket.text(done("reply", "completed", key, call("failed", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        int writes = socket.writes.size();
        session.cancelTurn();
        acknowledge(output);
        assertFalse(session.workspace().canSend());
        blockedWrite.completeExceptionally(new IOException("synthetic private detail"));
        assertFailure(Failure.CONNECTION);
        assertEquals(writes, socket.writes.size());
    }

    @Test
    void disconnectAndReconnectIgnoreOldOutputCallbacks() throws Exception {
        var key = startTurn("reply");
        socket.text(done("reply", "completed", key, call("a", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        int writes = socket.writes.size();
        session.disconnect();
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        acknowledge(output);
        assertEquals(writes, socket.writes.size());
        connect();
        startTurn("fresh");
        socket.text(done("fresh", "completed", null, call("a", COUNTER, ARGUMENTS)), true);
        assertOutput(lastWrite(), "a", "{\"ok\":true,\"value\":42}");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresOutputsToBeAcknowledgedWithinTheDeadline(boolean deliverTimer) throws Exception {
        var key = startTurn("reply");
        socket.text(done("reply", "completed", key, call("timeout", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        int writes = socket.writes.size();
        if (deliverTimer) {
            latestDeadline.completeExceptionally(new TimeoutException());
        } else {
            now.addAndGet(RealtimeSession.TOOL_OUTPUT_TIMEOUT.toNanos());
            acknowledge(output);
        }
        assertEquals(writes, socket.writes.size());
        assertFailure(Failure.ITEM_ACK_TIMEOUT);
    }

    @Test
    void stopsAfterACorrelatedProviderRejectionOrFailedSocketWrite() throws Exception {
        var key = startTurn("reply");
        socket.text(done("reply", "completed", key, call("rejected", COUNTER, ARGUMENTS)), true);
        var output = lastWrite();
        rejectOutput(output);
        assertFailure(Failure.PROVIDER);
        int writes = socket.writes.size();
        acknowledge(output);
        assertEquals(writes, socket.writes.size());

        connect();
        key = startTurn("fresh");
        socket.nextTurnWrite = CompletableFuture.failedFuture(new IOException("synthetic private detail"));
        socket.text(done("fresh", "completed", key, call("failed", COUNTER, ARGUMENTS)), true);
        assertFailure(Failure.CONNECTION);
    }

    @Test
    void countsSignalsAndTerminalOnlyCallsTogetherAndNeverWritesAnOverBudgetBatch() throws Exception {
        var key = startTurn("reply");
        for (int index = 1; index < RealtimeSession.MAX_TOOL_CALLS; index++) {
            signal("other_response", "call_" + index);
        }
        signal("reply", "credited");
        socket.text(done("reply", "completed", key, call("credited", COUNTER, ARGUMENTS)), true);
        assertOutput(lastWrite(), "credited", "{\"ok\":true,\"value\":42}");
        acknowledge(lastWrite());
        socket.text(done("follow_up", "completed", requestKey(lastWrite())), true);
        var nextKey = startTurn("next");
        int writes = socket.writes.size();
        socket.text(done("next", "completed", nextKey, call("uncounted", COUNTER, ARGUMENTS)), true);
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(List.of(SessionProtocol.CANCEL_RESPONSE.message()), socket.writes.subList(writes, socket.writes.size()));
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(Failure.TOOL_LIMIT, session.status().failure());
    }

    @Test
    void doesNotSubmitAnyOutputsWhenOnlyPartOfTheBatchFitsTheSessionBudget() throws Exception {
        var key = startTurn("reply");
        for (int index = 1; index < RealtimeSession.MAX_TOOL_CALLS; index++) {
            signal("other_response", "call_" + index);
        }
        int writes = socket.writes.size();
        socket.text(done("reply", "completed", key, call("fits", COUNTER, ARGUMENTS),
                call("exceeds", COUNTER, ARGUMENTS)), true);
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(List.of(SessionProtocol.CANCEL_RESPONSE.message()), socket.writes.subList(writes, socket.writes.size()));
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(Failure.TOOL_LIMIT, session.status().failure());
    }

    private void assertCallError(JsonNode call, String callId, String code) throws Exception {
        var key = startTurn("first_" + callId);
        socket.text(done("first_" + callId, "completed", key, call), true);
        var output = lastWrite();
        assertOutput(output, callId, "{\"ok\":false,\"error\":\"" + code + "\"}");
        acknowledge(output);
        socket.text(done("follow_" + callId, "completed", requestKey(lastWrite())), true);
        assertTrue(session.workspace().canSend());
    }

    private void assertOutput(JsonNode event, String callId, String result) throws Exception {
        assertEquals("conversation.item.create", event.path("type").textValue());
        assertEquals("function_call_output", event.at("/item/type").textValue());
        assertEquals(callId, event.at("/item/call_id").textValue());
        assertTrue(event.at("/item/output").isTextual());
        assertEquals(json.readTree(result), json.readTree(event.at("/item/output").textValue()));
    }

    private String startTurn(String responseId) throws Exception {
        assertTrue(session.sendText("Use the demo counter."));
        acknowledge(lastWrite());
        var key = requestKey(lastWrite());
        responseCreated(responseId, key);
        return key;
    }

    private void responseCreated(String responseId, String key) {
        socket.text("""
                {"type":"response.created","response":{"id":"%s","metadata":{"rvl_turn":"%s"}}}
                """.formatted(responseId, key), true);
    }

    private void signal(String responseId, String callId) {
        socket.text("""
                {"type":"response.function_call_arguments.done","response_id":"%s","call_id":"%s",
                 "name":"untrusted_early_name","arguments":"untrusted early arguments"}
                """.formatted(responseId, callId), true);
    }

    private String done(String responseId, String status, String key, JsonNode... output) {
        var event = json.createObjectNode().put("type", "response.done");
        var response = event.putObject("response").put("id", responseId).put("status", status);
        if (key != null) {
            response.putObject("metadata").put("rvl_turn", key);
        }
        response.putArray("output").addAll(List.of(output));
        return event.toString();
    }

    private ObjectNode call(String callId, String name, String arguments) {
        return json.createObjectNode().put("type", "function_call").put("status", "completed")
                .put("call_id", callId).put("name", name).put("arguments", arguments);
    }

    private JsonNode message(String text) {
        var item = json.createObjectNode().put("type", "message").put("role", "assistant");
        item.putArray("content").addObject().put("type", "output_text").put("text", text);
        return item;
    }

    private void acknowledge(JsonNode command) {
        socket.text(added(command.path("item")), true);
    }

    private void rejectOutput(JsonNode command) {
        var error = json.createObjectNode().put("type", "error");
        error.putObject("error").put("event_id", command.path("event_id").textValue())
                .put("message", "synthetic private detail");
        socket.text(error.toString(), true);
    }

    private String added(JsonNode item) {
        var event = json.createObjectNode().put("type", "conversation.item.added");
        event.set("item", item);
        return event.toString();
    }

    private String requestKey(JsonNode command) {
        return command.at("/response/metadata/rvl_turn").textValue();
    }

    private JsonNode lastWrite() throws Exception {
        return json.readTree(socket.writes.getLast());
    }

    private void assertFailure(Failure failure) {
        assertEquals(new RealtimeSession.Status(State.ERROR, failure), session.status());
        assertTrue(socket.aborted);
        assertFalse(session.workspace().canSend());
    }
}

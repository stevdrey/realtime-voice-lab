package io.github.stevdrey.realtimevoicelab.realtime;

import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.Failure;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.State;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeTextConversationTest {
    private final FakeTransport transport = new FakeTransport();
    private final RealtimeSession session = new RealtimeSession(transport, () -> { });
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void close() {
        session.close();
    }

    @Test
    void streamsAndReconcilesTwoTurnsOnTheSameConversation() throws Exception {
        assertFalse(session.sendText("Before connecting"));
        var socket = ready();
        assertFalse(session.sendText("   "));
        assertFalse(session.sendText("x".repeat(RealtimeSession.MAX_INPUT_CHARS + 1)));

        var itemWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = itemWrite;
        var first = "First \"synthetic\" input";
        assertTrue(session.sendText(first));
        assertFalse(session.sendText("Overlap"));
        var event = mapper.readTree(socket.writes.getLast());
        var item = event.path("item");
        assertEquals("conversation.item.create", event.path("type").asText());
        assertEquals("message", item.path("type").asText());
        assertEquals("user", item.path("role").asText());
        assertEquals("input_text", item.path("content").get(0).path("type").asText());
        assertEquals(first, item.path("content").get(0).path("text").asText());
        itemAdded(socket, "different-item");
        itemAdded(socket, "rvl-1");
        assertEquals(2, socket.writes.size());
        itemWrite.complete(socket);

        var create = mapper.readTree(socket.writes.getLast());
        var response = create.path("response");
        assertEquals("response.create", create.path("type").asText());
        assertEquals("[\"text\"]", response.path("output_modalities").toString());
        assertEquals(512, response.path("max_output_tokens").asInt());
        assertTrue(response.path("conversation").isMissingNode()); // GA defaults to the existing conversation.
        assertEquals("rvl-1", response.path("metadata").path("rvl_turn").asText());

        responseCreated(socket, "rvl-1", "first");
        socket.text("{\"type\":\"response.output_text.delta\",\"response_id\":\"first\",", false);
        socket.text("\"output_index\":0,\"content_index\":0,\"delta\":\"Streaming\"}", true);
        assertEquals("Streaming", last().output());
        textDelta(socket, "first", 1, "more");
        textDelta(socket, "first", 0, " text");
        assertEquals("Streaming text\nmore", last().output());
        socket.text("""
                {"type":"response.output_text.done","response_id":"first","item_id":"reply",
                 "output_index":0,"content_index":0,"text":"Final text"}
                """, true);
        doneWithText(socket, "first", "completed", "Final text");
        doneWithText(socket, "first", "completed", "Duplicate terminal");
        assertEquals("Final text", last().output());
        assertEquals(TurnState.COMPLETED, last().state());
        assertTrue(session.workspace().canSend());

        assertTrue(session.sendText("Follow-up"));
        itemAdded(socket, "rvl-2");
        responseCreated(socket, "rvl-1", "first");
        textDelta(socket, "first", 0, "Stale text");
        assertEquals("", last().output());
        responseCreated(socket, "rvl-2", "second");
        textDelta(socket, "second", 0, "Second answer");
        socket.text("""
                {"type":"response.done","response":{"id":"second","status":"completed","output":[]}}
                """, true);
        assertEquals(2, session.workspace().turns().size());
        assertEquals("Second answer", last().output());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void finishesFromMatchingTerminalMetadataWhenCreationDidNotIdentifyTheResponse() {
        var socket = ready();
        session.sendText("Synthetic input");
        itemAdded(socket, "rvl-1");
        socket.text("""
                {"type":"response.created","response":{"id":"reply","metadata":null}}
                """, true);
        textDelta(socket, "reply", 0, "Unidentified text");
        socket.text("""
                {"type":"response.done","response":{"id":"unrelated","status":"completed",
                 "metadata":{"rvl_turn":"another-turn"}}}
                """, true);
        assertEquals("", last().output());
        assertFalse(session.workspace().canSend());
        session.cancelText();
        assertEquals(TurnState.CANCELLING, last().state());
        assertEquals(3, socket.writes.size());

        socket.text("""
                {"type":"response.done","response":{"id":"reply","status":"completed",
                 "metadata":{"rvl_turn":"rvl-1"},"output":[
                 {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Final reply"}]}]}}
                """, true);
        assertEquals(TurnState.COMPLETED, last().state());
        assertEquals("Final reply", last().output());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void replacesStreamedTextWithAnExplicitlyEmptyTerminalPart() {
        var socket = ready();
        session.sendText("Synthetic input");
        itemAdded(socket, "rvl-1");
        responseCreated(socket, "rvl-1", "reply");
        textDelta(socket, "reply", 0, "Partial reply");
        socket.text("""
                {"type":"response.done","response":{"id":"unrelated","status":"completed",
                 "metadata":{"rvl_turn":"rvl-1"}}}
                """, true);
        assertEquals(TurnState.RESPONDING, last().state());
        assertEquals("Partial reply", last().output());

        doneWithText(socket, "reply", "completed", "");
        assertEquals(TurnState.COMPLETED, last().state());
        assertEquals("", last().output());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void cancelsBeforeGenerationWhileTheItemWriteIsPending() {
        var socket = ready();
        var itemWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = itemWrite;
        session.sendText("Queued input");
        session.cancelText();
        itemAdded(socket, "rvl-1");
        assertFalse(session.workspace().canSend());
        itemWrite.complete(socket);
        assertEquals(TurnState.CANCELLED, last().state());
        assertEquals(2, socket.writes.size());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void targetsCancellationAndWaitsForTerminalAndThePendingWrite() throws Exception {
        var socket = ready();
        session.sendText("Cancel this");
        itemAdded(socket, "rvl-1");
        session.cancelText();
        assertFalse(session.workspace().canCancel());
        assertEquals(3, socket.writes.size());

        var cancelWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = cancelWrite;
        responseCreated(socket, "rvl-1", "target");
        var cancel = mapper.readTree(socket.writes.getLast());
        assertEquals("response.cancel", cancel.path("type").asText());
        assertEquals("target", cancel.path("response_id").asText());
        textDelta(socket, "target", 0, "Late text");
        assertEquals("Late text", last().output());
        assertEquals(TurnState.CANCELLING, last().state());
        doneWithText(socket, "target", "cancelled", "Late terminal text");
        assertEquals("Late terminal text", last().output());
        assertEquals(TurnState.CANCELLED, last().state());
        assertFalse(session.workspace().canSend());
        cancelWrite.complete(socket);
        assertTrue(session.workspace().canSend());
    }

    @Test
    void recoversFromCorrelatedProviderErrorsAndCanRetryCancellation() {
        var socket = ready();
        session.sendText("Reject the item");
        error(socket, "rvl-1-item");
        itemAdded(socket, "rvl-1");
        assertEquals(2, socket.writes.size());
        assertEquals(TurnState.FAILED, last().state());
        assertTrue(session.workspace().canSend());

        session.sendText("Keep the response active");
        itemAdded(socket, "rvl-2");
        error(socket, "rvl-1-response");
        responseCreated(socket, "rvl-2", "active-response");
        error(socket, "rvl-2-item");
        error(socket, "rvl-2-response");
        assertFalse(session.workspace().canSend());
        textDelta(socket, "active-response", 0, "Partial text");
        session.cancelText();
        error(socket, "rvl-2-cancel-1");
        assertEquals(TurnState.CANCEL_REJECTED, last().state());
        assertTrue(session.workspace().canCancel());
        session.cancelText();
        error(socket, "rvl-2-cancel-1");
        assertEquals(TurnState.CANCELLING, last().state());
        doneWithText(socket, "active-response", "cancelled", "Kept after cancel");
        assertEquals("Kept after cancel", last().output());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void ignoresAnUncorrelatedErrorBeforeTheResponseIsRequested() throws Exception {
        var socket = ready();
        session.sendText("Synthetic input");
        socket.text("""
                {"type":"error","error":{"type":"server_error","message":"private detail"}}
                """, true);
        assertEquals(State.READY, session.status().state());
        assertEquals(TurnState.WAITING, last().state());
        assertFalse(socket.aborted);

        itemAdded(socket, "rvl-1");
        assertEquals("response.create", mapper.readTree(socket.writes.getLast()).path("type").asText());
    }

    @Test
    void disconnectCancelsAnActiveResponseBeforeClosing() {
        var socket = ready();
        session.sendText("Count for a while");
        itemAdded(socket, "rvl-1");
        responseCreated(socket, "rvl-1", "active");
        textDelta(socket, "active", 0, "One");
        session.disconnect();
        assertEquals(State.CLOSING, session.status().state());
        assertEquals(4, socket.writes.size());
        assertEquals(SessionProtocol.CANCEL_RESPONSE, socket.writes.getLast());
        assertEquals(1, socket.closeWrites);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(new RealtimeSession.Status(State.DISCONNECTED, null), session.status());
        assertEquals(TurnState.INTERRUPTED, last().state());
        assertEquals("One", last().output());
    }

    @Test
    void closesOnAnUncorrelatedErrorDuringGeneration() {
        var socket = ready();
        session.sendText("Synthetic input");
        itemAdded(socket, "rvl-1");
        socket.text("""
                {"type":"error","error":{"type":"server_error","message":"private detail"}}
                """, true);
        assertEquals(Failure.PROVIDER, session.status().failure());
        assertEquals(TurnState.INTERRUPTED, last().state());
        assertTrue(socket.aborted);
    }

    @Test
    void disconnectWaitsForTheWriteAndIsolatesLateCallbacksAcrossReconnect() {
        var socket = ready();
        var itemWrite = new CompletableFuture<WebSocket>();
        socket.nextTurnWrite = itemWrite;
        session.sendText("Disconnect during send");
        session.disconnect();
        assertEquals(State.CLOSING, session.status().state());
        assertFalse(session.workspace().canSend());
        assertEquals(0, socket.closeWrites);
        itemWrite.complete(socket);
        assertEquals(1, socket.closeWrites);
        assertEquals(2, socket.writes.size());
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(TurnState.INTERRUPTED, last().state());
        ready();
        itemAdded(socket, "rvl-1");
        assertTrue(session.workspace().turns().isEmpty());
        assertTrue(session.workspace().canSend());
    }

    @Test
    void keepsThePartialTextAndClosesAtTheExistingOutputLimit() {
        var socket = ready();
        session.sendText("Reach the output limit");
        itemAdded(socket, "rvl-1");
        responseCreated(socket, "rvl-1", "limited");
        textDelta(socket, "limited", 0, "Partial");
        socket.text("""
                {"type":"response.done","response":{"id":"limited","status":"incomplete",
                 "status_details":{"type":"incomplete","reason":"max_output_tokens"},"output":[]}}
                """, true);
        assertEquals(3, socket.writes.size());
        assertEquals(1, socket.closeWrites);
        socket.peerClose(WebSocket.NORMAL_CLOSURE, "");
        assertEquals(Failure.OUTPUT_LIMIT, session.status().failure());
        assertEquals(TurnState.INCOMPLETE, last().state());
        assertEquals("Partial", last().output());
    }

    @Test
    void rejectsAnOversizedMatchingDeltaAndRetainsTheBoundedPartialText() {
        var socket = ready();
        session.sendText("Exercise the transcript buffer");
        itemAdded(socket, "rvl-1");
        responseCreated(socket, "rvl-1", "bounded");
        textDelta(socket, "bounded", 0, "Partial");
        textDelta(socket, "bounded", 0, "x".repeat(32 * 1024));
        assertEquals(Failure.PROTOCOL, session.status().failure());
        assertEquals(TurnState.INTERRUPTED, last().state());
        assertEquals("Partial", last().output());
        assertTrue(socket.aborted);
    }

    private FakeTransport.Socket ready() {
        session.connect(RealtimeSession.DEFAULT_MODEL);
        var socket = transport.attempts.getLast();
        socket.ready();
        return socket;
    }

    private RealtimeSession.TurnView last() {
        return session.workspace().turns().getLast();
    }

    private void itemAdded(FakeTransport.Socket socket, String turn) {
        socket.text("""
                {"type":"conversation.item.added","item":{"id":"%s","type":"message","role":"user"}}
                """.formatted(turn), true);
    }

    private void responseCreated(FakeTransport.Socket socket, String turn, String response) {
        socket.text("""
                {"type":"response.created","response":{"id":"%s","metadata":{"rvl_turn":"%s"}}}
                """.formatted(response, turn), true);
    }

    private void textDelta(FakeTransport.Socket socket, String response, int part, String text) {
        socket.text("""
                {"type":"response.output_text.delta","response_id":"%s","item_id":"reply",
                 "output_index":0,"content_index":%d,"delta":"%s"}
                """.formatted(response, part, text), true);
    }

    private void doneWithText(FakeTransport.Socket socket, String response, String status, String text) {
        socket.text("""
                {"type":"response.done","response":{"id":"%s","status":"%s","output":[
                 {"type":"message","role":"assistant","content":[{"type":"output_text","text":"%s"}]}]}}
                """.formatted(response, status, text), true);
    }

    private void error(FakeTransport.Socket socket, String eventId) {
        socket.text("""
                {"type":"error","error":{"event_id":"%s","message":"private synthetic detail"}}
                """.formatted(eventId), true);
    }
}

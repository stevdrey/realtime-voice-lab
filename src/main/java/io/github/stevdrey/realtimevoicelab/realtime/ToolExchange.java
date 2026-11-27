package io.github.stevdrey.realtimevoicelab.realtime;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** Tracks the ordered output writes and their server acknowledgments for one response. */
final class ToolExchange {
    private List<Output> outputs;
    private final String turnId;
    private final long startedAt;
    private int sent;
    private int acknowledged;

    ToolExchange(String turnId, long startedAt) {
        this.turnId = turnId;
        this.startedAt = startedAt;
    }

    void completeExecution(List<Output> results) {
        outputs = List.copyOf(results);
    }

    boolean complete() {
        return outputs != null && acknowledged == outputs.size();
    }

    boolean awaitingAcknowledgment() {
        return sent != acknowledged;
    }

    boolean expired(long now, long limit) {
        return now - startedAt >= limit;
    }

    SessionProtocol.Command nextWrite(SessionProtocol protocol) {
        if (outputs == null || awaitingAcknowledgment() || complete()) {
            return null;
        }
        var output = outputs.get(sent++);
        return protocol.functionOutput(eventId(sent), itemId(sent), output.callId(), output.result());
    }

    boolean acknowledge(JsonNode item) {
        if (!awaitingAcknowledgment() || !itemId(sent).equals(item.path("id").textValue())
                || !"function_call_output".equals(item.path("type").textValue())
                || !outputs.get(sent - 1).callId().equals(item.path("call_id").textValue())) {
            return false;
        }
        acknowledged++;
        return true;
    }

    boolean rejectsPendingOutput(String eventId) {
        return awaitingAcknowledgment() && eventId(sent).equals(eventId);
    }

    private String eventId(int index) {
        return itemId(index) + "-create";
    }

    private String itemId(int index) {
        return turnId + "-tool-" + index;
    }

    record Output(String callId, String result) {
    }
}

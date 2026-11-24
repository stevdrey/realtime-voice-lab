package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.util.TreeMap;

import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnState;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnView;
import io.github.stevdrey.realtimevoicelab.realtime.SessionProtocol.TextDelta;

final class TextTurn {
    private static final int MAX_TEXT_CHARS = 32 * 1024;
    private static final int MAX_PART_INDEX = 127;

    final long number;
    final String id;
    final String input;
    TurnState state = TurnState.WAITING;
    boolean itemAcknowledged;
    boolean responseRequested;
    int cancelRequests;
    boolean cancelSent;
    String responseId;

    private final TreeMap<Integer, StringBuilder> parts = new TreeMap<>();
    private int streamedChars;
    private String finishedText;

    TextTurn(long number, String input) {
        this.number = number;
        this.id = "rvl-" + number;
        this.input = input;
    }

    String itemEventId() {
        return id + "-item";
    }

    String responseEventId() {
        return id + "-response";
    }

    String cancelEventId() {
        return id + "-cancel-" + cancelRequests;
    }

    void append(TextDelta delta) throws IOException {
        if (delta.text() == null || invalidIndex(delta.outputIndex()) || invalidIndex(delta.contentIndex())) {
            throw new IOException("Invalid Realtime text delta");
        }
        var text = delta.text();
        if (!text.isEmpty()) {
            int key = delta.outputIndex() * (MAX_PART_INDEX + 1) + delta.contentIndex();
            int separator = !parts.isEmpty() && !parts.containsKey(key) ? 1 : 0;
            checkLength(streamedChars + text.length() + separator);
            streamedChars += text.length() + separator;
            parts.computeIfAbsent(key, ignored -> new StringBuilder()).append(text);
            if (state == TurnState.WAITING) {
                state = TurnState.RESPONDING;
            }
        }
    }

    void reconcile(String terminalText) throws IOException {
        if (terminalText != null) {
            checkLength(terminalText.length());
            finishedText = terminalText;
        }
    }

    void finish(TurnState outcome) {
        if (finishedText == null) {
            finishedText = streamedText();
        }
        parts.clear();
        state = outcome;
    }

    TurnView view() {
        return new TurnView(number, input, finishedText == null ? streamedText() : finishedText, state);
    }

    private String streamedText() {
        return String.join("\n", parts.values());
    }

    private void checkLength(int length) throws IOException {
        if (length > MAX_TEXT_CHARS) {
            throw new IOException("Realtime text limit exceeded");
        }
    }

    private boolean invalidIndex(int index) {
        return index < 0 || index > MAX_PART_INDEX;
    }
}

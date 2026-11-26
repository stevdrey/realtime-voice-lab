package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.InputTranscriptState;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnState;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TurnView;
import io.github.stevdrey.realtimevoicelab.realtime.SessionProtocol.OutputPart;

final class ConversationTurn {
    private static final int MAX_TEXT_CHARS = 32 * 1024;
    private static final int MAX_PART_INDEX = 127;

    final long number;
    final String id;
    final String input;
    final String audioItemId;
    TurnState state = TurnState.WAITING;
    boolean itemAcknowledged;
    boolean responseRequested;
    int cancelRequests;
    boolean cancelSent;
    String responseId;
    boolean playbackStopped;
    AssistantAudio assistantAudio;
    long playedFrames;

    private final TreeMap<Integer, StringBuilder> parts = new TreeMap<>();
    private final Set<Integer> finalTranscripts = new HashSet<>();
    private final Set<Integer> finalAudio = new HashSet<>();
    private int streamedChars;
    private String finishedText;
    private InputTranscriptState inputTranscriptState;
    private String inputTranscript;

    ConversationTurn(long number, String input) {
        this(number, input, null, false);
    }

    ConversationTurn(long number, String audioItemId, boolean transcribe) {
        this(number, "Voice input", audioItemId, transcribe);
    }

    private ConversationTurn(long number, String input, String audioItemId, boolean transcribe) {
        this.number = number;
        this.id = "rvl-" + number;
        this.input = input;
        this.audioItemId = audioItemId;
        inputTranscriptState = transcribe ? InputTranscriptState.PENDING : InputTranscriptState.OFF;
    }

    boolean isVoice() {
        return audioItemId != null;
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

    void append(OutputPart delta) throws IOException {
        int key = partKey(delta);
        if (finalTranscripts.contains(key)) {
            return;
        }
        if (delta.text() == null) {
            throw new IOException("Invalid Realtime text delta");
        }
        var text = delta.text();
        if (!text.isEmpty()) {
            int separator = !parts.isEmpty() && !parts.containsKey(key) ? 1 : 0;
            checkLength(streamedChars + text.length() + separator);
            streamedChars += text.length() + separator;
            parts.computeIfAbsent(key, ignored -> new StringBuilder()).append(text);
            markResponding();
        }
    }

    void finishTranscriptPart(OutputPart part) throws IOException {
        int key = partKey(part);
        if (finalTranscripts.contains(key)) {
            return;
        }
        if (part.text() == null) {
            throw new IOException("Missing Realtime output transcript");
        }
        var previous = parts.get(key);
        int existing = previous == null ? 0 : previous.length();
        int separator = previous == null && !parts.isEmpty() ? 1 : 0;
        int updatedLength = streamedChars - existing + part.text().length() + separator;
        checkLength(updatedLength);
        streamedChars = updatedLength;
        parts.put(key, new StringBuilder(part.text()));
        finalTranscripts.add(key);
        markResponding();
    }

    void finishAudioPart(OutputPart part) throws IOException {
        finalAudio.add(partKey(part));
    }

    boolean canPlayPart(OutputPart part) throws IOException {
        return !playbackStopped && !finalAudio.contains(partKey(part));
    }

    void markResponding() {
        if (state == TurnState.WAITING) {
            state = TurnState.RESPONDING;
        }
    }

    boolean finishInputTranscript(String itemId, String transcript) {
        if (inputTranscriptState != InputTranscriptState.PENDING || !audioItemId.equals(itemId)) {
            return false;
        }
        if (transcript == null || transcript.isBlank() || transcript.length() > MAX_TEXT_CHARS) {
            inputTranscriptState = InputTranscriptState.FAILED;
        } else {
            inputTranscript = transcript;
            inputTranscriptState = InputTranscriptState.COMPLETED;
        }
        return true;
    }

    void abandonInputTranscript() {
        if (inputTranscriptState == InputTranscriptState.PENDING) {
            inputTranscriptState = InputTranscriptState.FAILED;
        }
    }

    void discardAudioTranscript() {
        parts.clear();
        finalTranscripts.clear();
        streamedChars = 0;
        finishedText = "";
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
        finalTranscripts.clear();
        finalAudio.clear();
        state = outcome;
    }

    TurnView view() {
        return new TurnView(number, inputTranscript == null ? input : inputTranscript,
                finishedText == null ? streamedText() : finishedText, state, isVoice(), inputTranscriptState);
    }

    private String streamedText() {
        return String.join("\n", parts.values());
    }

    private void checkLength(int length) throws IOException {
        if (length > MAX_TEXT_CHARS) {
            throw new IOException("Realtime text limit exceeded");
        }
    }

    private int partKey(OutputPart part) throws IOException {
        if (invalidIndex(part.outputIndex()) || invalidIndex(part.contentIndex())) {
            throw new IOException("Invalid Realtime output part index");
        }
        return part.outputIndex() * (MAX_PART_INDEX + 1) + part.contentIndex();
    }

    private boolean invalidIndex(int index) {
        return index < 0 || index > MAX_PART_INDEX;
    }
}

package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.util.StringJoiner;

import io.github.stevdrey.realtimevoicelab.audio.MicrophoneCapture;
import io.github.stevdrey.realtimevoicelab.realtime.RealtimeSession.TranscriptionModel;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.Mode;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.TurnDetection;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class SessionProtocol {
    static final int MAX_MESSAGE_CHARS = 256 * 1024;
    static final int MAX_OUTPUT_TOKENS = 512;
    static final int MAX_ID_CHARS = 512;
    private static final String PCM_AUDIO_TYPE = "audio/pcm";
    private static final int PCM_SAMPLE_RATE = MicrophoneCapture.SAMPLE_RATE;
    static final int PCM_BYTES_PER_SECOND = MicrophoneCapture.BYTES_PER_SECOND;
    static final Command CANCEL_RESPONSE = new Command("response.cancel", null,
            "{\"type\":\"response.cancel\"}", null);
    static final String TURN_METADATA = "rvl_turn";

    private final JsonFactory factory = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    private final ObjectMapper mapper = new ObjectMapper(factory);
    private final LocalTools tools;

    SessionProtocol(LocalTools tools) {
        this.tools = tools;
    }

    Command configuration(String eventId, SessionSettings requested, SessionSettings applied,
                          TranscriptionModel transcription) {
        var update = command("session.update", eventId);
        var session = update.putObject("session").put("type", "realtime");
        session.put("instructions", requested.instructions());
        session.putArray("output_modalities").add(requested.mode().modality());
        boolean initial = applied == null;
        boolean updateVoice = initial || requested.voice() != applied.voice();
        boolean setPcmOutput = initial || applied.mode() == Mode.TEXT && requested.mode() == Mode.AUDIO;
        if (initial) {
            session.put("max_output_tokens", MAX_OUTPUT_TOKENS);
            tools.declare(session);
        }
        var detection = requested.effectiveTurnDetection();
        if (initial || detection != applied.effectiveTurnDetection()) {
            var input = session.withObjectProperty("audio").putObject("input");
            if (detection.automatic()) {
                var vad = input.putObject("turn_detection").put("type", detection.type())
                        .put("create_response", false).put("interrupt_response", false);
                if (detection == TurnDetection.SERVER_VAD) {
                    vad.putNull("idle_timeout_ms");
                }
            } else {
                input.putNull("turn_detection");
            }
            putPcmFormat(input);
            if (initial && transcription == TranscriptionModel.OFF) {
                input.putNull("transcription");
            } else if (initial) {
                input.putObject("transcription").put("model", transcription.modelId());
            }
        }
        if (updateVoice || setPcmOutput) {
            var output = session.withObjectProperty("audio").putObject("output");
            if (updateVoice) {
                output.put("voice", requested.voice().id());
            }
            if (setPcmOutput) {
                putPcmFormat(output);
            }
        }
        return command(update, null);
    }

    private void putPcmFormat(ObjectNode audio) {
        audio.putObject("format").put("type", PCM_AUDIO_TYPE).put("rate", PCM_SAMPLE_RATE);
    }

    Command createItem(ConversationTurn turn) {
        var event = command("conversation.item.create", turn.itemEventId());
        event.putObject("item").put("id", turn.id).put("type", "message").put("role", "user")
                .putArray("content").addObject().put("type", "input_text").put("text", turn.input);
        return command(event, null);
    }

    Command appendAudio(String id, byte[] pcm) {
        return command(command("input_audio_buffer.append", id).put("audio", pcm), pcm.length);
    }

    Command commitAudio(String id) {
        return command(command("input_audio_buffer.commit", id), null);
    }

    Command clearAudio(String id) {
        return command(command("input_audio_buffer.clear", id), null);
    }

    Command deleteItem(String eventId, String itemId) {
        return command(command("conversation.item.delete", eventId).put("item_id", itemId), null);
    }

    Command createResponse(ConversationTurn turn) {
        var event = command("response.create", turn.responseEventId());
        var response = event.putObject("response").put("max_output_tokens", MAX_OUTPUT_TOKENS);
        response.putArray("output_modalities").add(turn.isVoice() ? "audio" : "text");
        response.putObject("metadata").put(TURN_METADATA, turn.responseRequestKey());
        if (turn.followingTools) {
            response.put("tool_choice", "none");
        }
        return command(event, null);
    }

    Command functionOutput(String eventId, String itemId, String callId, String output) {
        var event = command("conversation.item.create", eventId);
        event.putObject("item").put("type", "function_call_output").put("id", itemId)
                .put("call_id", callId).put("output", output);
        return command(event, null);
    }

    Command cancelResponse(ConversationTurn turn) {
        var event = command("response.cancel", turn.cancelEventId()).put("response_id", turn.responseId);
        return command(event, null);
    }

    Command truncateAudio(String eventId, String itemId, int milliseconds) {
        return command(command("conversation.item.truncate", eventId).put("item_id", itemId)
                .put("content_index", 0).put("audio_end_ms", milliseconds), null);
    }

    private ObjectNode command(String type, String id) {
        return mapper.createObjectNode().put("type", type).put("event_id", id);
    }

    private Command command(ObjectNode event, Integer audioBytes) {
        return new Command(event.path("type").textValue(), event.path("event_id").textValue(),
                event.toString(), audioBytes);
    }

    JsonNode textEvent(String message) throws IOException {
        return mapper.readTree(message);
    }

    String terminalText(JsonNode response) {
        var text = new StringJoiner("\n");
        boolean hasText = false;
        for (var item : response.path("output")) {
            if ("message".equals(item.path("type").textValue()) && "assistant".equals(item.path("role").textValue())) {
                for (var part : item.path("content")) {
                    if ("output_text".equals(part.path("type").textValue()) && part.path("text").isTextual()) {
                        text.add(part.path("text").textValue());
                        hasText = true;
                    }
                }
            }
        }
        return hasText ? text.toString() : null;
    }

    void reconcileVoiceTranscript(ConversationTurn turn, JsonNode response) throws IOException {
        int outputIndex = 0;
        for (var item : response.path("output")) {
            if ("message".equals(item.path("type").textValue()) && "assistant".equals(item.path("role").textValue())) {
                int contentIndex = 0;
                for (var part : item.path("content")) {
                    var type = part.path("type").textValue();
                    var transcript = part.path("transcript").textValue();
                    if ("output_audio".equals(type) && transcript != null) {
                        turn.finishTranscriptPart(new OutputPart(outputIndex, contentIndex, transcript));
                    }
                    contentIndex++;
                }
            }
            outputIndex++;
        }
    }

    // Read lifecycle fields and stream payloads without building a tree for every event.
    Event parse(String message) throws IOException {
        String type = null;
        String eventId = null;
        JsonNode session = null;
        String responseId = null;
        String itemId = null;
        String delta = null;
        String transcript = null;
        int outputIndex = -1;
        int contentIndex = -1;
        try (var parser = factory.createParser(message)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Invalid Realtime event");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var field = parser.currentName();
                var value = parser.nextToken();
                if ("type".equals(field)) {
                    type = textValue(parser, value);
                } else if ("event_id".equals(field)) {
                    eventId = textValue(parser, value);
                } else if ("response_id".equals(field)) {
                    responseId = textValue(parser, value);
                } else if ("item_id".equals(field)) {
                    itemId = textValue(parser, value);
                } else if ("delta".equals(field) && value == JsonToken.VALUE_STRING) {
                    // The type may follow the delta, so decoding waits until the event is known.
                    delta = parser.getText();
                } else if ("transcript".equals(field)) {
                    transcript = textValue(parser, value);
                } else if ("output_index".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                    outputIndex = partIndex(parser);
                } else if ("content_index".equals(field) && value == JsonToken.VALUE_NUMBER_INT) {
                    contentIndex = partIndex(parser);
                } else if ("session".equals(field) && value == JsonToken.START_OBJECT) {
                    session = mapper.readTree(parser);
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                throw new IOException("Invalid Realtime event");
            }
        }

        type = requiredText(type);
        return switch (type) {
            case "session.created", "session.updated" ->
                    new Event(type, eventId, readConfiguration(session), null, null, null, null);
            case "response.output_audio.delta" ->
                    new Event(type, eventId, null, requiredId(responseId), itemId, delta,
                            new OutputPart(outputIndex, contentIndex, null));
            case "response.output_text.delta", "response.output_audio_transcript.delta" ->
                    new Event(type, eventId, null, requiredId(responseId), itemId, null,
                            new OutputPart(outputIndex, contentIndex, delta));
            case "response.output_audio.done", "response.output_audio_transcript.done" ->
                    new Event(type, eventId, null, requiredId(responseId), itemId, null,
                            new OutputPart(outputIndex, contentIndex, transcript));
            case "conversation.item.input_audio_transcription.completed",
                 "conversation.item.input_audio_transcription.failed" ->
                    new Event(type, eventId, null, null, itemId, null, new OutputPart(-1, contentIndex, transcript));
            case "input_audio_buffer.committed", "input_audio_buffer.speech_started",
                 "input_audio_buffer.speech_stopped" -> new Event(type, eventId, null, null, itemId, null, null);
            default -> new Event(type, eventId, null, null, null, null, null);
        };
    }

    private Configuration readConfiguration(JsonNode session) throws IOException {
        if (session == null || !"realtime".equals(session.path("type").textValue())) {
            throw new IOException("Invalid session type");
        }
        var audio = session.path("audio");
        var input = audio.path("input");
        var output = audio.path("output");
        var transcription = input.path("transcription");
        var transcriptionModel = transcription.isNull() || transcription.isMissingNode()
                ? "" : transcription.path("model").textValue();
        if ("".equals(transcriptionModel) && transcription.isObject()) {
            transcriptionModel = null;
        }
        var cap = session.path("max_output_tokens");
        boolean bounded = cap.isIntegralNumber() && cap.canConvertToInt()
                && cap.intValue() > 0 && cap.intValue() <= MAX_OUTPUT_TOKENS;
        var modalities = session.path("output_modalities");
        var modality = modalities.isArray() && modalities.size() == 1 ? modalities.get(0).textValue() : null;
        return new Configuration(requiredText(session.path("id").textValue()), bounded,
                isPcmFormat(output.path("format")),
                isPcmFormat(input.path("format")), readTurnDetection(input.path("turn_detection")), transcriptionModel,
                session.path("model").textValue(), output.path("voice").textValue(),
                modality, session.path("instructions").textValue(), tools.confirmed(session));
    }

    private TurnDetection readTurnDetection(JsonNode node) {
        if (node.isNull()) {
            return TurnDetection.MANUAL;
        }
        if (!node.path("create_response").isBoolean() || node.path("create_response").booleanValue()
                || !node.path("interrupt_response").isBoolean() || node.path("interrupt_response").booleanValue()) {
            return null;
        }
        return switch (node.path("type").asText()) {
            case "server_vad" -> node.hasNonNull("idle_timeout_ms") ? null : TurnDetection.SERVER_VAD;
            case "semantic_vad" -> TurnDetection.SEMANTIC_VAD;
            default -> null;
        };
    }

    private int partIndex(JsonParser parser) throws IOException {
        return parser.getNumberType() == JsonParser.NumberType.INT ? parser.getIntValue() : -1;
    }

    private boolean isPcmFormat(JsonNode format) {
        if (!PCM_AUDIO_TYPE.equals(format.path("type").textValue())) {
            return false;
        }
        var rate = format.path("rate");
        // GA permits omitting the PCM rate. 24 kHz is the only supported PCM rate.
        return rate.isMissingNode()
                || (rate.isIntegralNumber() && rate.canConvertToInt() && rate.intValue() == PCM_SAMPLE_RATE);
    }

    byte[] decodeAudio(String delta) throws IOException {
        if (delta == null) {
            throw new IOException("Invalid Realtime audio delta");
        }
        try {
            // Frame alignment is checked only for PCM that enters the playback queue.
            return Base64Variants.getDefaultVariant().decode(delta);
        } catch (IllegalArgumentException invalidBase64) {
            throw new IOException("Invalid Realtime audio delta");
        }
    }

    private String textValue(JsonParser parser, JsonToken token) throws IOException {
        if (token == JsonToken.VALUE_STRING) {
            return parser.getText();
        }
        parser.skipChildren();
        return null;
    }

    private String requiredText(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Invalid Realtime event");
        }
        return value;
    }

    String requiredId(String value) throws IOException {
        if (value != null && value.length() > MAX_ID_CHARS) {
            throw new IOException("Realtime identifier limit exceeded");
        }
        return requiredText(value);
    }

    record Configuration(String sessionId, boolean boundedOutput, boolean pcmOutput, boolean pcmInput,
                         TurnDetection turnDetection, String transcriptionModel, String model, String voice,
                         String modality, String instructions, boolean toolsConfirmed) {
        boolean matches(SessionSettings settings) {
            return modelMatches(settings.model().id()) && settings.voice().id().equals(voice)
                    && settings.mode().modality().equals(modality) && settings.instructions().equals(instructions);
        }

        // The provider may report the dated snapshot behind the requested alias.
        private boolean modelMatches(String alias) {
            return model != null && (model.equals(alias)
                    || model.startsWith(alias + "-") && model.substring(alias.length() + 1).matches("\\d{4}-\\d{2}-\\d{2}"));
        }

        @Override
        public String toString() {
            return "Configuration[sessionId=" + sessionId + "]";
        }
    }

    record Command(String type, String eventId, String message, Integer audioBytes) {
        // The message can hold user text or audio, so it never appears in diagnostics.
        @Override
        public String toString() {
            return "Command[type=" + type + ", eventId=" + eventId + ", audioBytes=" + audioBytes + "]";
        }
    }

    record Event(String type, String eventId, Configuration configuration, String responseId, String itemId,
                 String encodedAudio, OutputPart part) {
    }

    record OutputPart(int outputIndex, int contentIndex, String text) {
    }
}

package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SessionProtocol {
    static final int MAX_MESSAGE_CHARS = 256 * 1024;
    static final int MAX_OUTPUT_TOKENS = 512;
    private static final String PCM_AUDIO_TYPE = "audio/pcm";
    private static final int PCM_SAMPLE_RATE = 24_000;
    static final int PCM_BYTES_PER_SECOND = PCM_SAMPLE_RATE * Short.BYTES;
    static final String CANCEL_RESPONSE = "{\"type\":\"response.cancel\"}";

    private final JsonFactory factory = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    private final ObjectMapper mapper = new ObjectMapper(factory);

    String configuration() {
        var update = mapper.createObjectNode().put("type", "session.update");
        var session = update.putObject("session").put("type", "realtime");
        session.put("instructions", "Respond briefly.");
        session.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        session.putArray("output_modalities").add("audio");
        var audio = session.putObject("audio");
        audio.putObject("input").putNull("turn_detection")
                .putObject("format").put("type", PCM_AUDIO_TYPE).put("rate", PCM_SAMPLE_RATE);
        audio.putObject("output").put("voice", "marin")
                .putObject("format").put("type", PCM_AUDIO_TYPE).put("rate", PCM_SAMPLE_RATE);
        return update.toString();
    }

    // Streams the event so large payloads such as audio deltas are skipped rather than materialized.
    Event parse(String message) throws IOException {
        String type = null;
        String sessionType = null;
        String sessionId = null;
        int outputCap = 0;
        boolean pcmOutput = false;
        String responseId = null;
        String delta = null;
        try (var parser = factory.createParser(message)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Invalid Realtime event");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var field = parser.currentName();
                var value = parser.nextToken();
                if ("type".equals(field)) {
                    type = textValue(parser, value);
                } else if ("response_id".equals(field)) {
                    responseId = textValue(parser, value);
                } else if ("delta".equals(field) && value == JsonToken.VALUE_STRING) {
                    // The type may follow the delta, so decoding waits until the event is known.
                    delta = parser.getText();
                } else if ("session".equals(field) && value == JsonToken.START_OBJECT) {
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        var sessionField = parser.currentName();
                        var sessionValue = parser.nextToken();
                        if ("type".equals(sessionField)) {
                            sessionType = textValue(parser, sessionValue);
                        } else if ("id".equals(sessionField)) {
                            sessionId = textValue(parser, sessionValue);
                        } else if ("max_output_tokens".equals(sessionField) && sessionValue == JsonToken.VALUE_NUMBER_INT) {
                            outputCap = parser.getIntValue();
                        } else if ("audio".equals(sessionField) && sessionValue == JsonToken.START_OBJECT) {
                            pcmOutput = readSupportedOutputFormat(parser);
                        } else {
                            parser.skipChildren();
                        }
                    }
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
            case "session.created", "session.updated" -> {
                if (!"realtime".equals(sessionType)) {
                    throw new IOException("Invalid session type");
                }
                yield new Event(type, requiredText(sessionId),
                        pcmOutput && outputCap > 0 && outputCap <= MAX_OUTPUT_TOKENS, null, -1);
            }
            case "response.output_audio.delta" ->
                    new Event(type, null, false, requiredText(responseId), decodedLength(delta));
            default -> new Event(type, null, false, null, -1);
        };
    }

    private boolean readSupportedOutputFormat(JsonParser parser) throws IOException {
        JsonNode audio = mapper.readTree(parser);
        var format = audio.path("output").path("format");
        if (!PCM_AUDIO_TYPE.equals(format.path("type").textValue())) {
            return false;
        }
        var rate = format.path("rate");
        // GA permits omitting the PCM rate. 24 kHz is the only supported PCM rate.
        return rate.isMissingNode()
                || (rate.isIntegralNumber() && rate.canConvertToInt() && rate.intValue() == PCM_SAMPLE_RATE);
    }

    private int decodedLength(String delta) throws IOException {
        if (delta == null) {
            throw new IOException("Invalid Realtime audio delta");
        }
        try {
            // Only the length is used. The decoded PCM is discarded immediately.
            return Base64Variants.getDefaultVariant().decode(delta).length;
        } catch (IllegalArgumentException invalidBase64) {
            throw new IOException("Invalid Realtime audio delta");
        }
    }

    ResponseDone responseDone(String message) throws IOException {
        try (var parser = factory.createParser(message)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var field = parser.currentName();
                var value = parser.nextToken();
                if ("response".equals(field) && value == JsonToken.START_OBJECT) {
                    return readCompletedResponse(parser);
                }
                parser.skipChildren();
            }
        }
        throw new IOException("Missing completed Realtime response");
    }

    private ResponseDone readCompletedResponse(JsonParser parser) throws IOException {
        String id = null;
        String status = null;
        String reason = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            var field = parser.currentName();
            var value = parser.nextToken();
            switch (field) {
                case "id" -> id = textValue(parser, value);
                case "status" -> status = textValue(parser, value);
                case "status_details" -> {
                    if (value == JsonToken.START_OBJECT) {
                        while (parser.nextToken() == JsonToken.FIELD_NAME) {
                            var detail = parser.currentName();
                            var detailValue = parser.nextToken();
                            if ("reason".equals(detail)) {
                                reason = textValue(parser, detailValue);
                            } else {
                                parser.skipChildren();
                            }
                        }
                    } else {
                        parser.skipChildren();
                    }
                }
                default -> parser.skipChildren();
            }
        }
        return new ResponseDone(requiredText(id), "incomplete".equals(status) && "max_output_tokens".equals(reason));
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

    record Event(String type, String sessionId, boolean outputConfigurationConfirmed, String responseId,
                 int audioBytes) {
    }

    record ResponseDone(String id, boolean outputLimitReached) {
    }
}

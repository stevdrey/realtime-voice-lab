package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SessionProtocol {
    static final int MAX_MESSAGE_CHARS = 256 * 1024;

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
        session.putArray("output_modalities").add("audio");
        var audio = session.putObject("audio");
        audio.putObject("input").putNull("turn_detection")
                .putObject("format").put("type", "audio/pcm").put("rate", 24000);
        audio.putObject("output").put("voice", "marin")
                .putObject("format").put("type", "audio/pcm").put("rate", 24000);
        return update.toString();
    }

    // Streams the event so large payloads such as audio deltas are skipped rather than materialized.
    Event parse(String message) throws IOException {
        String type = null;
        String sessionType = null;
        String sessionId = null;
        try (var parser = factory.createParser(message)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Invalid Realtime event");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                var field = parser.currentName();
                var value = parser.nextToken();
                if ("type".equals(field)) {
                    type = textValue(parser, value);
                } else if ("session".equals(field) && value == JsonToken.START_OBJECT) {
                    while (parser.nextToken() == JsonToken.FIELD_NAME) {
                        var sessionField = parser.currentName();
                        var sessionValue = parser.nextToken();
                        if ("type".equals(sessionField)) {
                            sessionType = textValue(parser, sessionValue);
                        } else if ("id".equals(sessionField)) {
                            sessionId = textValue(parser, sessionValue);
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
                yield new Event(type, requiredText(sessionId));
            }
            default -> new Event(type, null);
        };
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

    record Event(String type, String sessionId) {
    }
}

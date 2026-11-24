package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class SessionProtocol {
    static final int MAX_MESSAGE_CHARS = 256 * 1024;

    private final ObjectMapper mapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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

    Event parse(String message) throws IOException {
        var event = mapper.readTree(message);
        if (event == null || !event.isObject()) {
            throw new IOException("Invalid Realtime event");
        }
        var type = requiredText(event.path("type"));
        return switch (type) {
            case "session.created", "session.updated" -> {
                var session = event.path("session");
                if (!"realtime".equals(session.path("type").textValue())) {
                    throw new IOException("Invalid session type");
                }
                yield new Event(type, requiredText(session.path("id")));
            }
            default -> new Event(type, null);
        };
    }

    private String requiredText(JsonNode node) throws IOException {
        var value = node.textValue();
        if (value == null || value.isBlank()) {
            throw new IOException("Invalid Realtime event");
        }
        return value;
    }

    record Event(String type, String sessionId) {
    }
}

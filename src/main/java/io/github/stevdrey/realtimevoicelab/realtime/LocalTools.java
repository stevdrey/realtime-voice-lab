package io.github.stevdrey.realtimevoicelab.realtime;

import java.io.IOException;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The only callable code is a bounded calculation with no access to the desktop or network. */
final class LocalTools {
    private static final String COUNTER = "calculate_workspace_counter";
    private static final int MAX_COUNTER = 100;
    private static final int MAX_DELTA = 10;
    private static final int MAX_ARGUMENT_CHARS = 1024;
    private static final Pattern CALL_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final ObjectMapper arguments = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxNumberLength(16).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    void declare(ObjectNode session) {
        session.set("tools", declarations());
        session.put("tool_choice", "auto");
    }

    boolean confirmed(JsonNode session) {
        var tools = session.path("tools");
        if (!"auto".equals(session.path("tool_choice").textValue()) || !tools.isArray() || tools.size() != 1) {
            return false;
        }
        var tool = tools.get(0);
        return "function".equals(tool.path("type").textValue()) && COUNTER.equals(tool.path("name").textValue())
                && parametersConfirmed(tool.path("parameters"));
    }

    // The provider may normalize the echo. Compare the constraints, while execute() still validates every call.
    private boolean parametersConfirmed(JsonNode schema) {
        var additional = schema.path("additionalProperties");
        var required = schema.path("required");
        var properties = schema.path("properties");
        return "object".equals(schema.path("type").textValue())
                && (additional.isMissingNode() || additional.isBoolean() && !additional.booleanValue())
                && required.isArray() && required.size() == 2
                && (requiredPair(required, "current", "delta") || requiredPair(required, "delta", "current"))
                && properties.isObject() && properties.size() == 2
                && integerProperty(properties.path("current"), 0, MAX_COUNTER)
                && integerProperty(properties.path("delta"), -MAX_DELTA, MAX_DELTA);
    }

    private boolean requiredPair(JsonNode required, String first, String second) {
        return first.equals(required.get(0).textValue()) && second.equals(required.get(1).textValue());
    }

    private boolean integerProperty(JsonNode property, int minimum, int maximum) {
        return "integer".equals(property.path("type").textValue())
                && bound(property.path("minimum"), minimum) && bound(property.path("maximum"), maximum);
    }

    private boolean bound(JsonNode value, int expected) {
        return value.isNumber() && value.doubleValue() == expected;
    }

    private ArrayNode declarations() {
        var tools = JsonNodeFactory.instance.arrayNode();
        tools.addObject().put("type", "function").put("name", COUNTER)
                .put("description", "Calculate a demo counter's next value without changing the workspace.")
                .set("parameters", parameters());
        return tools;
    }

    private ObjectNode parameters() {
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("current").add("delta");
        var properties = schema.putObject("properties");
        properties.putObject("current").put("type", "integer").put("minimum", 0).put("maximum", MAX_COUNTER);
        properties.putObject("delta").put("type", "integer").put("minimum", -MAX_DELTA).put("maximum", MAX_DELTA);
        return schema;
    }

    boolean validCallId(String value) {
        return value != null && value.length() <= 32 && CALL_ID.matcher(value).matches();
    }

    ObjectNode execute(JsonNode call) {
        if (call.has("status") && !"completed".equals(call.path("status").textValue())) {
            return error("incomplete_call");
        }
        if (!COUNTER.equals(call.path("name").textValue())) {
            return error("unknown_tool");
        }
        var raw = call.path("arguments").textValue();
        if (raw == null || raw.length() > MAX_ARGUMENT_CHARS) {
            return error("invalid_arguments");
        }
        try {
            var values = arguments.readTree(raw);
            if (values == null || !values.isObject() || values.size() != 2) {
                return error("invalid_arguments");
            }
            var current = values.path("current");
            var delta = values.path("delta");
            if (!inRange(current, 0, MAX_COUNTER) || !inRange(delta, -MAX_DELTA, MAX_DELTA)) {
                return error("invalid_arguments");
            }
            int result = current.intValue() + delta.intValue();
            if (result < 0 || result > MAX_COUNTER) {
                return error("counter_out_of_range");
            }
            return JsonNodeFactory.instance.objectNode().put("ok", true).put("value", result);
        } catch (IOException invalidJson) {
            return error("invalid_arguments");
        }
    }

    private boolean inRange(JsonNode value, int minimum, int maximum) {
        return value.isIntegralNumber() && value.canConvertToInt()
                && value.intValue() >= minimum && value.intValue() <= maximum;
    }

    private ObjectNode error(String code) {
        return JsonNodeFactory.instance.objectNode().put("ok", false).put("error", code);
    }
}

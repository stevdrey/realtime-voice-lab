package io.github.stevdrey.realtimevoicelab.realtime;

import static io.github.stevdrey.realtimevoicelab.realtime.ToyWorkspace.MAX_COUNTER;
import static io.github.stevdrey.realtimevoicelab.realtime.ToyWorkspace.MAX_DELTA;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.stevdrey.realtimevoicelab.realtime.SessionSettings.ToolPreset;

/** Declares and executes only the functions in the operator's selected preset. */
final class LocalTools {
    private static final int MAX_ARGUMENT_CHARS = 1024;
    private static final Pattern CALL_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final Parameter CURRENT = new Parameter("current", 0, MAX_COUNTER);
    private static final Parameter DELTA = new Parameter("delta", -MAX_DELTA, MAX_DELTA);
    private static final List<Tool> DEMO_TOOLS = List.of(new Tool("calculate_workspace_counter",
            "Calculate a demo counter's next value without changing the workspace.", List.of(CURRENT, DELTA)));
    private static final List<Tool> WORKSPACE_TOOLS = List.of(
            new Tool("changeColor", "Set the color swatch in the toy workspace.",
                    List.of(new Parameter("color", Arrays.stream(ToyWorkspace.Color.values())
                            .map(ToyWorkspace.Color::id).toList()))),
            new Tool("setTheme", "Set the theme of the toy workspace panel.",
                    List.of(new Parameter("theme", Arrays.stream(ToyWorkspace.Theme.values())
                            .map(ToyWorkspace.Theme::id).toList()))),
            new Tool("incrementCounter", "Change the toy workspace counter by delta. The result must be 0 to 100.",
                    List.of(DELTA)),
            new Tool("resetWorkspace", "Restore the toy workspace's default color, theme, and counter.", List.of()));

    private final ObjectMapper arguments = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxNumberLength(16).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    void declare(ObjectNode session, ToolPreset preset) {
        var tools = session.putArray("tools");
        for (var tool : toolsFor(preset)) {
            var schema = tools.addObject().put("type", "function").put("name", tool.name())
                    .put("description", tool.description()).putObject("parameters")
                    .put("type", "object").put("additionalProperties", false);
            var required = schema.putArray("required");
            var properties = schema.putObject("properties");
            for (var parameter : tool.parameters()) {
                required.add(parameter.name());
                parameter.declare(properties.putObject(parameter.name()));
            }
        }
        session.put("tool_choice", "auto");
    }

    ToolPreset confirmedPreset(JsonNode session) {
        var tools = session.path("tools");
        if (!"auto".equals(session.path("tool_choice").textValue()) || !tools.isArray()) {
            return null;
        }
        for (var preset : ToolPreset.values()) {
            var expected = toolsFor(preset);
            if (tools.size() == expected.size()
                    && expected.stream().allMatch(tool -> tool.confirmedIn(tools))) {
                return preset;
            }
        }
        return null;
    }

    private List<Tool> toolsFor(ToolPreset preset) {
        return switch (preset) {
            case DEMO_COUNTER -> DEMO_TOOLS;
            case TOY_WORKSPACE -> WORKSPACE_TOOLS;
        };
    }

    boolean validCallId(String value) {
        return value != null && value.length() <= 32 && CALL_ID.matcher(value).matches();
    }

    Result execute(ToolPreset preset, JsonNode call, ToyWorkspace workspace) {
        if (call.has("status") && !"completed".equals(call.path("status").textValue())) {
            return error("incomplete_call", workspace);
        }
        var name = call.path("name").textValue();
        var tool = toolsFor(preset).stream().filter(spec -> spec.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return error("unknown_tool", workspace);
        }
        var raw = call.path("arguments").textValue();
        if (raw == null || raw.length() > MAX_ARGUMENT_CHARS) {
            return error("invalid_arguments", workspace);
        }
        try {
            var values = arguments.readTree(raw);
            if (values == null || !values.isObject() || values.size() != tool.parameters().size()
                    || !tool.parameters().stream().allMatch(parameter -> parameter.accepts(values.path(parameter.name())))) {
                return error("invalid_arguments", workspace);
            }
            int delta = values.path("delta").intValue();
            int nextCounter = (preset == ToolPreset.DEMO_COUNTER ? values.path("current").intValue() : workspace.counter())
                    + delta;
            if (nextCounter < 0 || nextCounter > MAX_COUNTER) {
                return error("counter_out_of_range", workspace);
            }
            if (preset == ToolPreset.DEMO_COUNTER) {
                return new Result(success().put("value", nextCounter), workspace);
            }
            var next = switch (name) {
                case "changeColor" -> new ToyWorkspace(
                        ToyWorkspace.Color.valueOf(values.path("color").textValue().toUpperCase(Locale.ROOT)),
                        workspace.theme(), workspace.counter());
                case "setTheme" -> new ToyWorkspace(workspace.color(),
                        ToyWorkspace.Theme.valueOf(values.path("theme").textValue().toUpperCase(Locale.ROOT)),
                        workspace.counter());
                case "incrementCounter" -> new ToyWorkspace(workspace.color(), workspace.theme(), nextCounter);
                case "resetWorkspace" -> ToyWorkspace.DEFAULT;
                default -> throw new IllegalStateException("Missing workspace tool implementation");
            };
            return new Result(success().put("color", next.color().id()).put("theme", next.theme().id())
                    .put("counter", next.counter()), next);
        } catch (IOException invalidJson) {
            return error("invalid_arguments", workspace);
        }
    }

    private ObjectNode success() {
        return JsonNodeFactory.instance.objectNode().put("ok", true);
    }

    private Result error(String code, ToyWorkspace workspace) {
        return new Result(JsonNodeFactory.instance.objectNode().put("ok", false).put("error", code), workspace);
    }

    record Result(ObjectNode output, ToyWorkspace workspace) {
    }

    private record Tool(String name, String description, List<Parameter> parameters) {
        boolean confirmedIn(JsonNode tools) {
            for (var tool : tools) {
                if ("function".equals(tool.path("type").textValue()) && name.equals(tool.path("name").textValue())) {
                    return confirmsParameters(tool.path("parameters"));
                }
            }
            return false;
        }

        // The provider can reorder fields and omit some default schema metadata in its echo.
        private boolean confirmsParameters(JsonNode schema) {
            var additional = schema.path("additionalProperties");
            var required = schema.path("required");
            var properties = schema.path("properties");
            return "object".equals(schema.path("type").textValue())
                    && (additional.isMissingNode() || additional.isBoolean() && !additional.booleanValue())
                    && (required.isArray() || parameters.isEmpty() && required.isMissingNode())
                    && (properties.isObject() || parameters.isEmpty() && properties.isMissingNode())
                    && required.size() == parameters.size() && properties.size() == parameters.size()
                    && parameters.stream().allMatch(parameter -> required.valueStream()
                            .anyMatch(field -> parameter.name().equals(field.textValue()))
                            && parameter.confirmed(properties.path(parameter.name())));
        }
    }

    private record Parameter(String name, int minimum, int maximum, List<String> choices) {
        Parameter(String name, int minimum, int maximum) {
            this(name, minimum, maximum, List.of());
        }

        Parameter(String name, List<String> choices) {
            this(name, 0, 0, choices);
        }

        void declare(ObjectNode property) {
            if (choices.isEmpty()) {
                property.put("type", "integer").put("minimum", minimum).put("maximum", maximum);
            } else {
                var allowed = property.put("type", "string").putArray("enum");
                choices.forEach(allowed::add);
            }
        }

        boolean confirmed(JsonNode property) {
            if (choices.isEmpty()) {
                return "integer".equals(property.path("type").textValue())
                        && bound(property.path("minimum"), minimum) && bound(property.path("maximum"), maximum);
            }
            var values = property.path("enum");
            return "string".equals(property.path("type").textValue()) && values.isArray()
                    && values.size() == choices.size() && choices.stream()
                    .allMatch(choice -> values.valueStream().anyMatch(value -> choice.equals(value.textValue())));
        }

        boolean accepts(JsonNode value) {
            if (choices.isEmpty()) {
                return value.isIntegralNumber() && value.canConvertToInt()
                        && value.intValue() >= minimum && value.intValue() <= maximum;
            }
            return value.isTextual() && choices.contains(value.textValue());
        }

        private boolean bound(JsonNode value, int expected) {
            return value.isNumber() && value.doubleValue() == expected;
        }
    }
}

package com.relay.application.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free JSON Schema check — enough for tool parameters:
 * {@code type}, {@code required}, {@code properties}, {@code enum}, {@code items},
 * {@code minimum}/{@code maximum}, {@code minLength}, {@code minItems}.
 *
 * <p>The point is a hard gate in front of every tool call, so a hallucinated parameter
 * set never reaches Jira or Slack.
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    public record Result(boolean valid, List<String> errors) {

        public static Result ok() {
            return new Result(true, List.of());
        }

        public String message() {
            return String.join("; ", errors);
        }
    }

    public static Result validate(JsonNode schema, JsonNode value) {
        List<String> errors = new ArrayList<>();
        check(schema, value, "$", errors);
        return errors.isEmpty() ? Result.ok() : new Result(false, errors);
    }

    private static void check(JsonNode schema, JsonNode value, String path, List<String> errors) {
        if (schema == null || schema.isNull() || !schema.isObject()) {
            return;
        }
        if (value == null || value.isMissingNode()) {
            errors.add(path + " is missing");
            return;
        }

        String type = schema.path("type").asText(null);
        if (type != null && !typeMatches(type, value)) {
            errors.add(path + " must be " + type + " but was " + kindOf(value));
            return;
        }

        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean found = false;
            for (JsonNode allowed : enumNode) {
                if (allowed.asText().equals(value.asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(path + " must be one of " + enumNode);
            }
        }

        if ("object".equals(type) || value.isObject()) {
            JsonNode required = schema.get("required");
            if (required != null && required.isArray()) {
                for (JsonNode req : required) {
                    JsonNode child = value.get(req.asText());
                    if (child == null || child.isNull()
                            || (child.isTextual() && child.asText().isBlank())) {
                        errors.add(path + "." + req.asText() + " is required");
                    }
                }
            }
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = properties.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode child = value.get(entry.getKey());
                    if (child != null && !child.isNull()) {
                        check(entry.getValue(), child, path + "." + entry.getKey(), errors);
                    }
                }
            }
        }

        if (value.isArray()) {
            // An empty array satisfies "required" — it is neither null nor a blank string —
            // so without this a row with no cells in it reaches the provider as a write that
            // writes nothing. minItems is the only place the gate can say so.
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) {
                errors.add(path + " must have at least " + schema.get("minItems").asInt() + " items");
            }
            JsonNode items = schema.get("items");
            if (items != null) {
                for (int i = 0; i < value.size(); i++) {
                    check(items, value.get(i), path + "[" + i + "]", errors);
                }
            }
        }

        if (value.isNumber()) {
            if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
                errors.add(path + " must be >= " + schema.get("minimum").asText());
            }
            if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
                errors.add(path + " must be <= " + schema.get("maximum").asText());
            }
        }

        if (value.isTextual() && schema.has("minLength")
                && value.asText().length() < schema.get("minLength").asInt()) {
            errors.add(path + " must be at least " + schema.get("minLength").asInt() + " characters");
        }
        /*
          The other bound, learned live (#175): Jira caps a summary at 255 and answers
          HTTP 400 past it. Without maxLength here the gate showed a human a title the
          provider was guaranteed to refuse — approving it bought a provider error, a
          re-derivation and a second identical question. The gate's whole contract is
          that what it shows can actually be sent.
        */
        if (value.isTextual() && schema.has("maxLength")
                && value.asText().length() > schema.get("maxLength").asInt()) {
            errors.add(path + " must be at most " + schema.get("maxLength").asInt() + " characters");
        }
    }

    private static boolean typeMatches(String type, JsonNode value) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber() || (value.isTextual() && value.asText().matches("-?\\d+"));
            case "number" -> value.isNumber() || (value.isTextual() && value.asText().matches("-?\\d+(\\.\\d+)?"));
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private static String kindOf(JsonNode node) {
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNumber()) {
            return "number";
        }
        return "null";
    }
}

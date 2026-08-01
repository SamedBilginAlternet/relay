package com.relay.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import com.relay.application.json.SchemaValidator;
import com.relay.application.port.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a human changed at the approval gate, checked before it becomes the step's
 * parameters.
 *
 * <p>The gate is the one place where a person can hand Relay a value directly, so it is
 * also the one place where a typo — or a paste of somebody else's JSON — could reach a
 * provider. Nothing is written until every edited field has passed the tool's own schema,
 * which means a rejected edit leaves the step exactly where it was: still waiting.
 *
 * <p>The sentences are Turkish and per field on purpose. "$.channel must be one of [...]"
 * next to a text box tells the person who mistyped a channel nothing they can act on.
 */
public final class ParamEdit {

    private ParamEdit() {
    }

    /** One field the user rewrote, as the audit trail will read it. */
    public record Change(Object before, Object after) {
    }

    /**
     * @param params  what the step should carry if the edit is accepted
     * @param errors  field name → why it was refused; empty means accepted
     * @param changes only the fields whose value actually differs from what was on screen
     */
    public record Result(Map<String, Object> params, Map<String, String> errors,
                         Map<String, Change> changes) {

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** Rules the validator understands, in the order a person would notice them. */
    private static final List<String> RULES = List.of("type", "enum", "minLength", "minimum", "maximum");

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "string", "metin",
            "integer", "tam sayı",
            "number", "sayı",
            "boolean", "doğru/yanlış",
            "object", "nesne",
            "array", "liste");

    /**
     * Merges the edit onto the parameters that were shown and validates the result.
     *
     * <p>Merged rather than replaced: the screen edits the short, human fields and must not
     * be able to drop a parameter it never displayed.
     */
    public static Result of(Tool tool, Map<String, Object> current, Map<String, Object> edited) {
        Map<String, Object> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Change> changes = new LinkedHashMap<>();

        JsonNode schema = tool.schema();
        JsonNode properties = schema == null ? null : schema.get("properties");

        for (Map.Entry<String, Object> entry : edited.entrySet()) {
            String field = entry.getKey();
            JsonNode fieldSchema = properties == null || !properties.isObject() ? null : properties.get(field);
            if (properties != null && properties.isObject() && fieldSchema == null) {
                // A field the tool does not have is never a typo worth guessing at: it is
                // either a stale screen or something the caller made up.
                errors.put(field, "Bu araçta böyle bir parametre yok.");
                continue;
            }
            JsonNode value = Json.toNode(entry.getValue());
            String problem = fieldSchema == null ? null : problem(fieldSchema, value);
            if (problem != null) {
                errors.put(field, problem);
                continue;
            }
            Object before = merged.get(field);
            merged.put(field, entry.getValue());
            if (!Json.toNode(before).equals(value)) {
                changes.put(field, new Change(before, entry.getValue()));
            }
        }

        requiredFields(schema).forEach(field -> {
            if (errors.containsKey(field) || !blank(merged.get(field))) {
                return;
            }
            errors.put(field, "Bu alan zorunlu — boş bırakılamaz.");
        });

        if (errors.isEmpty()) {
            // Last word to the same validator that guards every tool call, so an edit can
            // never take a path the model's own parameters would not survive.
            SchemaValidator.Result check = SchemaValidator.validate(schema, Json.toNode(merged));
            if (!check.valid()) {
                errors.put("params", "Değerler aracın şemasına uymuyor: " + check.message());
            }
        }

        return new Result(merged, errors, errors.isEmpty() ? changes : Map.of());
    }

    private static List<String> requiredFields(JsonNode schema) {
        JsonNode required = schema == null ? null : schema.get("required");
        if (required == null || !required.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(required.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    /**
     * Which rule the value broke, said in Turkish — or {@code null} when it broke none.
     *
     * <p>Each rule is re-tested on its own through {@link SchemaValidator} rather than
     * re-implemented here: the answer to "is this the right type" has to be the same one
     * the tool call itself will get, or the gate would accept values the call then refuses.
     */
    private static String problem(JsonNode fieldSchema, JsonNode value) {
        if (value == null || value.isNull()) {
            return null; // absence is the required check's business, not the type check's
        }
        if (SchemaValidator.validate(fieldSchema, value).valid()) {
            return null;
        }
        for (String rule : RULES) {
            JsonNode expectation = fieldSchema.get(rule);
            if (expectation == null) {
                continue;
            }
            ObjectNode only = Json.object();
            only.set(rule, expectation);
            if (SchemaValidator.validate(only, value).valid()) {
                continue;
            }
            return sentence(rule, expectation);
        }
        return "Değer bu alanın şemasına uymuyor.";
    }

    private static String sentence(String rule, JsonNode expectation) {
        return switch (rule) {
            case "type" -> "Bu alan " + TYPE_NAMES.getOrDefault(expectation.asText(), expectation.asText())
                    + " olmalı.";
            case "enum" -> "Şu değerlerden biri olmalı: " + join(expectation) + ".";
            case "minLength" -> "En az " + expectation.asInt() + " karakter olmalı.";
            case "minimum" -> "En az " + expectation.asText() + " olmalı.";
            case "maximum" -> "En fazla " + expectation.asText() + " olmalı.";
            default -> "Değer bu alanın şemasına uymuyor.";
        };
    }

    private static String join(JsonNode values) {
        StringBuilder sb = new StringBuilder();
        values.forEach(value -> sb.append(sb.length() == 0 ? "" : ", ").append(value.asText()));
        return sb.toString();
    }
}

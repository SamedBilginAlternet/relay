package com.relay.application.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thin Jackson helpers shared by the orchestrator. */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static JsonNode parse(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Models like to wrap JSON in prose or fences. Pull the first balanced object/array out.
     * Returns null when there is nothing parseable.
     */
    public static JsonNode extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = indexOfFirst(text);
        if (start < 0) {
            return null;
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    try {
                        return MAPPER.readTree(text.substring(start, i + 1));
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int indexOfFirst(String text) {
        int obj = text.indexOf('{');
        int arr = text.indexOf('[');
        if (obj < 0) {
            return arr;
        }
        if (arr < 0) {
            return obj;
        }
        return Math.min(obj, arr);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, LinkedHashMap.class);
    }

    public static JsonNode toNode(Object value) {
        if (value == null) {
            return MAPPER.nullNode();
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        return MAPPER.valueToTree(value);
    }

    public static Object toPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return MAPPER.convertValue(node, Object.class);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** Truncated JSON for prompts — keeps token spend sane. */
    public static String preview(Object value, int max) {
        String raw = write(value);
        return raw.length() <= max ? raw : raw.substring(0, max) + "…";
    }
}

package com.relay.infrastructure.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.application.json.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Demo-day insurance: recorded JSON answers for every tool, on the classpath at
 * {@code fixtures/<tool>.json}. {@code {{param}}} placeholders are substituted from
 * the actual call parameters (as JSON values, so escaping is never wrong), which makes
 * the replayed answer echo what the agent asked for.
 */
@Component
public class FixtureStore {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*}}");

    public JsonNode load(String toolName, JsonNode params) {
        String path = "fixtures/" + toolName + ".json";
        try (InputStream in = FixtureStore.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("no replay fixture for " + toolName + " (expected " + path + ")");
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return substitute(Json.parse(raw), params);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + path + " could not be read: " + e.getMessage(), e);
        }
    }

    public boolean has(String toolName) {
        return FixtureStore.class.getClassLoader()
                .getResource("fixtures/" + toolName + ".json") != null;
    }

    JsonNode substitute(JsonNode node, JsonNode params) {
        if (node == null || params == null) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode out = Json.object();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                out.set(entry.getKey(), substitute(entry.getValue(), params));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = Json.mapper().createArrayNode();
            for (JsonNode child : node) {
                out.add(substitute(child, params));
            }
            return out;
        }
        if (node.isTextual()) {
            String text = node.asText();
            Matcher matcher = PLACEHOLDER.matcher(text);
            if (!matcher.find()) {
                return node;
            }
            matcher.reset();
            // A whole-string placeholder keeps the parameter's own type.
            Matcher whole = PLACEHOLDER.matcher(text);
            if (whole.matches()) {
                JsonNode value = resolve(params, whole.group(1));
                return value == null ? node : value;
            }
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                JsonNode value = resolve(params, matcher.group(1));
                String replacement = value == null ? matcher.group(0)
                        : (value.isTextual() ? value.asText() : value.toString());
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            return Json.mapper().getNodeFactory().textNode(sb.toString());
        }
        return node;
    }

    private JsonNode resolve(JsonNode params, String path) {
        JsonNode current = params;
        for (String part : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.get(part);
        }
        return current == null || current.isNull() ? null : current;
    }
}

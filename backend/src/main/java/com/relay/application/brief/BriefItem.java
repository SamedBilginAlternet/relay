package com.relay.application.brief;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing waiting for the user, normalised across providers.
 *
 * <p>Two projections come out of it and they are deliberately different:
 *
 * <ul>
 *   <li>{@link #row()} — the flat line a section list renders:
 *       {@code {id, title, subtitle, meta, url, tone}}. No {@code source}, no {@code ref}:
 *       a list row needs text, not provider internals.</li>
 *   <li>{@link #view()} — the rich shape the insight layer reasons over, and the base of a
 *       priority card. {@code ref} lives here and reaches the UI only inside
 *       {@code suggestedActions[].params}.</li>
 * </ul>
 *
 * @param prefix key shown before the title in a row ({@code KAN-42}, {@code acme/pay#12}), may be empty
 * @param meta   right-aligned small text — relative time, or the clock for an event
 * @param from   who it is from / who it is about, for the priority card
 * @param tone   colour hint only: {@code default | warn | danger | success}. Never the sole carrier
 *               of meaning — the text says the same thing.
 */
public record BriefItem(String id, String source, String kind, String prefix, String title,
                        String subtitle, String meta, String from, String url, String at,
                        String tone, Map<String, Object> ref) {

    public static final String DEFAULT = "default";
    public static final String WARN = "warn";
    public static final String DANGER = "danger";
    public static final String SUCCESS = "success";

    public BriefItem {
        ref = ref == null ? Map.of() : Map.copyOf(ref);
        tone = tone == null || tone.isBlank() ? DEFAULT : tone;
        prefix = prefix == null ? "" : prefix;
    }

    /** {@code KAN-42 Ödeme servisi 500 dönüyor} */
    public String rowTitle() {
        return prefix.isBlank() ? title : prefix + " " + title;
    }

    /** The flat line the frontend renders inside a section. */
    public Map<String, Object> row() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("title", rowTitle());
        map.put("subtitle", subtitle);
        map.put("meta", meta);
        map.put("url", url);
        map.put("tone", tone);
        return map;
    }

    /** Everything about the item — insight input and priority-card base. */
    public Map<String, Object> view() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("source", source);
        map.put("kind", kind);
        map.put("title", title);
        map.put("subtitle", subtitle);
        map.put("from", from);
        map.put("url", url);
        map.put("at", at);
        map.put("ref", ref);
        return map;
    }

    /** Everything the classifier should read, in one line. */
    public String text() {
        return (title == null ? "" : title) + " " + (subtitle == null ? "" : subtitle);
    }
}

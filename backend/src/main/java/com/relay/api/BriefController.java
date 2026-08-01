package com.relay.api;

import com.relay.application.brief.BriefService;
import com.relay.application.cost.CostMeter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The Bugün screen in one call. Partial success is the contract, not an accident. */
@RestController
@RequestMapping("/api/brief")
public class BriefController {

    private final BriefService brief;

    public BriefController(BriefService brief) {
        this.brief = brief;
    }

    @GetMapping
    public Map<String, Object> get() {
        return priced(brief.brief(false));
    }

    /** Same payload, cache bypassed. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return priced(brief.brief(true));
    }

    /**
     * {@code llm.costUsd} is two model turns added together, and adding two doubles is how
     * {@code 0.0036615500000000004} reached the screen while the same money on
     * {@code /api/runs} read {@code 0.003662}. The sum belongs to the assembly; the shape it
     * is reported in belongs to the endpoint, so the rounding happens on the way out — one
     * rule, {@link CostMeter#usd(Double)}, for every cost Relay answers with (#90).
     *
     * <p>Copies rather than edits: the map it is handed is the cached brief, shared by every
     * reader until the cache expires.
     */
    static Map<String, Object> priced(Map<String, Object> body) {
        if (!(body.get("llm") instanceof Map<?, ?> info)
                || !(info.get("costUsd") instanceof Number cost)) {
            return body;
        }
        Map<String, Object> rounded = new LinkedHashMap<>();
        info.forEach((key, value) -> rounded.put(String.valueOf(key), value));
        rounded.put("costUsd", CostMeter.usd(cost.doubleValue()));
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("llm", rounded);
        return out;
    }
}

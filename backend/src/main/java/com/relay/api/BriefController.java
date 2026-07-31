package com.relay.api;

import com.relay.application.brief.BriefService;
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
        return brief.brief(false);
    }

    /** Same payload, cache bypassed. */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return brief.brief(true);
    }
}

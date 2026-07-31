package com.relay.api;

import com.relay.application.assistant.AskService;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Kargolarım gelmiş mi?" — a question about the mailbox, answered with sources.
 *
 * <p>Read-only by design: it searches Gmail and returns text. Nothing here can write
 * anywhere, and it deliberately does not start a run — a run needs the approval gate,
 * a question does not.
 */
@RestController
@RequestMapping("/api/ask")
public class AskController {

    private final AskService ask;

    public AskController(AskService ask) {
        this.ask = ask;
    }

    public record AskRequest(@NotBlank String question) {
    }

    @PostMapping
    public Map<String, Object> ask(@RequestBody AskRequest request) {
        return ask.ask(request == null ? null : request.question());
    }
}

package com.relay.api;

import com.relay.application.orchestrator.RunService;
import com.relay.application.view.Views;
import com.relay.domain.Run;
import com.relay.infrastructure.sse.SseEventPublisher;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** REST + SSE surface for runs. No business logic lives here. */
@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final SseEventPublisher sse;

    public RunController(RunService runService, SseEventPublisher sse) {
        this.runService = runService;
        this.sse = sse;
    }

    public record CreateRunRequest(@NotBlank String goal, Double budgetUsd) {
    }

    public record RejectRequest(String reason) {
    }

    /** {@code cardId} is the brief card the action came from — echoed back, never trusted. */
    public record FromSuggestionRequest(String cardId, @NotBlank String tool, Map<String, Object> params,
                                        String label, Double budgetUsd) {
    }

    /**
     * A suggested action from the Bugün screen becomes an ordinary run — same coordinator,
     * same policy engine, same approval gate. Nothing is executed here.
     */
    @PostMapping("/from-suggestion")
    public ResponseEntity<Map<String, Object>> fromSuggestion(@RequestBody FromSuggestionRequest request) {
        Run run = runService.startFromSuggestion(request.tool(), request.params(), request.label(),
                request.budgetUsd());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id().toString());
        body.put("id", run.id().toString());
        body.put("status", run.status().wire());
        body.put("goal", run.goal());
        body.put("tool", request.tool());
        body.put("cardId", request.cardId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** Returns immediately with the runId; planning and execution continue in the background. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRunRequest request) {
        Run run = runService.start(request.goal(), request.budgetUsd());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id().toString());
        body.put("status", run.status().wire());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return Views.run(runService.get(id));
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        List<Map<String, Object>> items = new ArrayList<>();
        runService.list(page, size).forEach(run -> items.add(Views.runSummary(run)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("total", runService.count());
        return body;
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        runService.get(id); // 404 before opening a stream to nothing
        return sse.subscribe(id);
    }

    @PostMapping("/{id}/steps/{stepId}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @PathVariable UUID stepId,
                                       jakarta.servlet.http.HttpServletRequest request) {
        return Views.run(runService.approve(id, stepId, actor(request)));
    }

    /** The signed-in e-mail, so the audit trail can answer "who approved this". */
    private static String actor(jakarta.servlet.http.HttpServletRequest request) {
        return com.relay.infrastructure.auth.AuthFilter.current(request)
                .map(com.relay.domain.User::email)
                .orElse(null);
    }

    @PostMapping("/{id}/steps/{stepId}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @PathVariable UUID stepId,
                                      @RequestBody(required = false) RejectRequest request,
                                      jakarta.servlet.http.HttpServletRequest httpRequest) {
        return Views.run(runService.reject(id, stepId, request == null ? null : request.reason(),
                actor(httpRequest)));
    }

    @PostMapping("/{id}/rerun")
    public ResponseEntity<Map<String, Object>> rerun(@PathVariable UUID id) {
        Run run = runService.rerun(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id().toString());
        body.put("status", run.status().wire());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}

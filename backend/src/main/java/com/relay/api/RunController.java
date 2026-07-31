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
    public Map<String, Object> approve(@PathVariable UUID id, @PathVariable UUID stepId) {
        return Views.run(runService.approve(id, stepId));
    }

    @PostMapping("/{id}/steps/{stepId}/reject")
    public Map<String, Object> reject(@PathVariable UUID id, @PathVariable UUID stepId,
                                      @RequestBody(required = false) RejectRequest request) {
        return Views.run(runService.reject(id, stepId, request == null ? null : request.reason()));
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

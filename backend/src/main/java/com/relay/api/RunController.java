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

    /**
     * {@code params} carries only what the human changed on the approval screen; an empty
     * or absent body is the old "approve what you see" and behaves identically.
     */
    public record ApproveRequest(Map<String, Object> params) {
    }

    /**
     * {@code cardId} is the brief card the action came from — echoed back, never trusted.
     *
     * <p>{@code context} is what that card was about: title, sender, one-line summary, link.
     * Optional, and deliberately so — a client that omits it gets the run it got before,
     * built from the label alone.
     */
    public record FromSuggestionRequest(String cardId, @NotBlank String tool, Map<String, Object> params,
                                        String label, RunService.SuggestionContext context,
                                        Double budgetUsd) {
    }

    /**
     * A suggested action from the Bugün screen becomes an ordinary run — same coordinator,
     * same policy engine, same approval gate. Nothing is executed here.
     */
    @PostMapping("/from-suggestion")
    public ResponseEntity<Map<String, Object>> fromSuggestion(@RequestBody FromSuggestionRequest request) {
        Run run = runService.startFromSuggestion(request.tool(), request.params(), request.label(),
                request.context(), request.budgetUsd());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", run.id().toString());
        body.put("id", run.id().toString());
        body.put("status", run.status().wire());
        body.put("goal", run.goal());
        body.put("tool", request.tool());
        body.put("cardId", request.cardId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * As long as a paragraph of context, no longer. Mirrors the guard in {@link RunService},
     * which stays where it is for callers that never come through HTTP.
     */
    static final int MAX_GOAL_CHARS = 2000;

    /**
     * The two sentences a person actually meets when the goal box is wrong.
     *
     * <p>{@code RunService} refuses the same two cases, in English, because its messages are
     * read in a log by whoever is debugging a caller. These are read on the Sohbet screen by
     * somebody who typed a goal — "goal is too long: 2500 characters, limit is 2000" in the
     * middle of an entirely Turkish product (#81).
     */
    private static void checkGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("Bir hedef yaz.");
        }
        if (goal.length() > MAX_GOAL_CHARS) {
            throw new IllegalArgumentException("Hedef çok uzun — " + goal.length()
                    + " karakter, sınır " + MAX_GOAL_CHARS + ".");
        }
    }

    /** Returns immediately with the runId; planning and execution continue in the background. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRunRequest request) {
        checkGoal(request == null ? null : request.goal());
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

    /** As many runs as one request will hand out, however many were asked for. */
    static final int MAX_PAGE_SIZE = 100;

    /**
     * History, a page at a time — and the page it answers with is the page it describes.
     *
     * <p>{@code size} was passed through untouched while the store quietly capped it at a
     * hundred, so {@code ?size=99999} returned 100 rows and called them 99999. A client that
     * divides {@code total} by {@code size} to count pages concluded there was one page and
     * it had all of it; with 114 runs recorded, fourteen were unreachable and nothing said
     * so. Out-of-range sizes are pulled to the limit and the limit is what comes back, so
     * {@code items.length <= size} holds on every response.
     *
     * <p>A negative {@code page} is refused rather than corrected: it is a negative offset,
     * which no caller can mean, and answering 200 to it hides the caller's bug in ours.
     */
    @GetMapping
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @RequestParam(required = false) String status) {
        if (page < 0) {
            throw new IllegalArgumentException("Sayfa numarası 0 ya da daha büyük olmalı.");
        }
        // `total` is the total for what was asked for, not for the table: a filtered page
        // whose total counted every run would tell the caller there are more pages of
        // waiting runs than exist.
        com.relay.domain.RunStatus wanted = runStatus(status);
        int applied = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        List<Map<String, Object>> items = new ArrayList<>();
        runService.list(wanted, page, applied).forEach(run -> items.add(Views.runSummary(run)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", applied);
        body.put("status", wanted == null ? null : wanted.wire());
        body.put("total", runService.count(wanted));
        return body;
    }

    /** An unknown status is the caller's mistake, and answering with every run hides it. */
    private static com.relay.domain.RunStatus runStatus(String wire) {
        if (wire == null || wire.isBlank()) {
            return null;
        }
        for (com.relay.domain.RunStatus candidate : com.relay.domain.RunStatus.values()) {
            if (candidate.wire().equalsIgnoreCase(wire.trim())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Bilinmeyen akış durumu: " + wire
                + ". Beklenen: planning, awaiting_approval, running, done, failed, cancelled.");
    }

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id) {
        runService.get(id); // 404 before opening a stream to nothing
        return sse.subscribe(id);
    }

    /**
     * Approve — with the corrected parameters, when the human fixed something on screen.
     * An edit that fails the tool's schema answers 400 with a message per field and leaves
     * the step waiting exactly where it was.
     */
    @PostMapping("/{id}/steps/{stepId}/approve")
    public Map<String, Object> approve(@PathVariable UUID id, @PathVariable UUID stepId,
                                       @RequestBody(required = false) ApproveRequest body,
                                       jakarta.servlet.http.HttpServletRequest request) {
        return Views.run(runService.approve(id, stepId, body == null ? null : body.params(), actor(request)));
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

    /**
     * Stops the flow. Answers with the run as it stands: {@code cancelled} when it was
     * waiting on a human, still {@code running} when a tool call is in flight — that one is
     * allowed to finish, and the {@code run.finished} event lands when it does.
     */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID id,
                                      jakarta.servlet.http.HttpServletRequest request) {
        return Views.run(runService.cancel(id, actor(request)));
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

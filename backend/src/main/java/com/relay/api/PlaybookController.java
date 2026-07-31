package com.relay.api;

import com.relay.application.playbook.PlaybookService;
import com.relay.application.view.Views;
import com.relay.domain.Run;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The written-down flows: what can run right now, and starting one. */
@RestController
@RequestMapping("/api/playbooks")
public class PlaybookController {

    private final PlaybookService playbooks;

    public PlaybookController(PlaybookService playbooks) {
        this.playbooks = playbooks;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return playbooks.describeAll();
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<Map<String, Object>> run(@PathVariable String id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Double budget = body == null ? null : asDouble(body.get("budgetUsd"));
        Run run = playbooks.start(id, budget);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Views.run(run));
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

package com.relay.api;

import com.relay.application.connection.ConnectionService;
import com.relay.application.connection.Masking;
import com.relay.domain.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Jira/Slack credentials. Values go in plain, come back masked. */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    public record ConnectionUpdate(String provider, Map<String, String> config) {
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return connections.describeAll();
    }

    @PutMapping
    public Map<String, Object> save(@RequestBody ConnectionUpdate update) {
        Connection saved = connections.save(update.provider(), update.config());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", saved.provider());
        body.put("configured", !saved.config().isEmpty());
        body.put("config", Masking.maskConfig(saved.config()));
        return body;
    }

    @PostMapping("/{provider}/test")
    public Map<String, Object> test(@PathVariable String provider) {
        return connections.test(provider);
    }
}

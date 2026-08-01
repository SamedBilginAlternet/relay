package com.relay.api;

import com.relay.application.stats.PanelReport;
import com.relay.application.stats.PanelService;
import com.relay.application.stats.PanelStatsRepository;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/panel?from=…&to=…} — the flow panel behind one request.
 *
 * <p>Read-only, session-guarded like the rest of {@code /api/**}, and it never touches
 * the model: the whole answer is five aggregate statements. That is the property worth
 * protecting — the number that says what a run costs must not itself cost a run.
 *
 * <p>Both parameters are optional; leaving them out means the last seven days.
 */
@RestController
@RequestMapping("/api/panel")
public class PanelController {

    private final PanelService panel;

    public PanelController(PanelService panel) {
        this.panel = panel;
    }

    @GetMapping
    public Map<String, Object> panel(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        PanelReport report = panel.report(from, to);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", iso(report.from()));
        body.put("to", iso(report.to()));

        Map<String, Object> runs = new LinkedHashMap<>();
        runs.put("total", report.runs().total());
        runs.put("byStatus", report.runs().byStatus());
        body.put("runs", runs);

        PanelReport.Approvals approvals = report.approvals();
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("steps", approvals.steps());
        gate.put("gated", approvals.gated());
        gate.put("gatedRatio", approvals.gatedRatio());
        gate.put("approved", approvals.approved());
        gate.put("rejected", approvals.rejected());
        gate.put("pending", approvals.pending());
        gate.put("approvalRate", approvals.approvalRate());
        body.put("approvals", gate);

        List<Map<String, Object>> rejections = new ArrayList<>();
        for (PanelStatsRepository.Rejection rejection : report.rejections()) {
            Map<String, Object> item = new LinkedHashMap<>();
            // runId first: the point of the list is that every line is a door back to the record.
            item.put("runId", rejection.runId() == null ? null : rejection.runId().toString());
            item.put("stepId", rejection.stepId() == null ? null : rejection.stepId().toString());
            item.put("runGoal", rejection.runGoal());
            // A cancelled run writes its unfinished steps off as rejected too; the reader
            // needs to see that from the line itself, not infer it from the wording.
            item.put("runStatus", rejection.runStatus());
            item.put("stepTitle", rejection.stepTitle());
            item.put("toolName", rejection.toolName());
            item.put("reason", rejection.reason());
            item.put("at", iso(rejection.at()));
            rejections.add(item);
        }
        body.put("rejections", rejections);

        List<Map<String, Object>> tools = new ArrayList<>();
        for (PanelStatsRepository.ToolUsage usage : report.tools()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolName", usage.toolName());
            item.put("calls", usage.calls());
            item.put("tokens", usage.tokens());
            item.put("costUsd", usage.usd());
            tools.add(item);
        }
        body.put("tools", tools);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tokens", report.totals().tokens());
        totals.put("costUsd", report.totals().usd());
        body.put("totals", totals);

        return body;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}

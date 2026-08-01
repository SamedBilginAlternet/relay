package com.relay.api;

import com.relay.application.cost.CostMeter;
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
 * the model: the whole answer is a handful of aggregate statements. That is the property
 * worth protecting — the number that says what a run costs must not itself cost a run.
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
        // Sent as three numbers rather than one rate and a hint. "Onaylandı / düzeltilip
        // onaylandı / reddedildi" is a different claim from "%89 onaylandı", and the screen
        // must not have to reconstruct it by subtraction.
        gate.put("approvedAsIs", approvals.approvedAsIs());
        gate.put("approvedWithEdit", approvals.approvedWithEdit());
        gate.put("rejected", approvals.rejected());
        gate.put("cancelled", approvals.cancelled());
        gate.put("pending", approvals.pending());
        gate.put("approvalRate", approvals.approvalRate());
        gate.put("editRate", approvals.editRate());
        body.put("approvals", gate);

        // Two lists, not one filtered on the client. Stopping a run closes its unfinished
        // steps as rejected, and those write-offs used to outnumber the real refusals on
        // the one list that has to show the gate earning its friction (#54).
        body.put("rejections", lines(report.rejections()));
        body.put("cancellations", lines(report.cancellations()));

        List<Map<String, Object>> tools = new ArrayList<>();
        for (PanelStatsRepository.ToolUsage usage : report.tools()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolName", usage.toolName());
            item.put("calls", usage.calls());
            item.put("tokens", usage.tokens());
            item.put("costUsd", CostMeter.usd(usage.usd()));
            tools.add(item);
        }
        body.put("tools", tools);

        // Which model answered, and what it cost. Empty until the column that records it
        // is deployed — the screen says so in words rather than drawing a chart of zeros.
        List<Map<String, Object>> models = new ArrayList<>();
        for (PanelStatsRepository.ModelUsage usage : report.models()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", usage.model());
            item.put("calls", usage.calls());
            item.put("tokens", usage.tokens());
            item.put("costUsd", CostMeter.usd(usage.usd()));
            // Same money helper, same six decimals. A counterfactual that printed in a
            // second format would read as a second kind of number, and the whole point of
            // the line below is that the two are directly comparable.
            item.put("premiumCostUsd", CostMeter.usd(usage.premiumUsd()));
            models.add(item);
        }
        body.put("models", models);

        PanelReport.Routing routing = report.routing();
        // null, not a block of zeros. "We did not record the counterfactual for this
        // window" and "the counterfactual cost nothing" are different sentences, and only
        // one of them is true.
        if (routing == null) {
            body.put("routing", null);
        } else {
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put("calls", routing.calls());
            saved.put("tokens", routing.tokens());
            saved.put("costUsd", CostMeter.usd(routing.usd()));
            saved.put("premiumCostUsd", CostMeter.usd(routing.premiumUsd()));
            saved.put("differenceUsd", CostMeter.usd(routing.differenceUsd()));
            body.put("routing", saved);
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("tokens", report.totals().tokens());
        totals.put("costUsd", CostMeter.usd(report.totals().usd()));
        body.put("totals", totals);

        return body;
    }

    /** Both lists carry the same shape — a reader comparing them must not have to re-learn it. */
    private static List<Map<String, Object>> lines(List<PanelStatsRepository.Rejection> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PanelStatsRepository.Rejection row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            // runId first: the point of the list is that every line is a door back to the record.
            item.put("runId", row.runId() == null ? null : row.runId().toString());
            item.put("stepId", row.stepId() == null ? null : row.stepId().toString());
            item.put("runGoal", row.runGoal());
            // A refusal on a run that was cancelled later is still a refusal, and it stays
            // in the refusal list — the run's status says so on the line instead.
            item.put("runStatus", row.runStatus());
            item.put("stepTitle", row.stepTitle());
            item.put("toolName", row.toolName());
            item.put("reason", row.reason());
            item.put("at", iso(row.at()));
            out.add(item);
        }
        return out;
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}

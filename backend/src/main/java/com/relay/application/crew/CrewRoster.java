package com.relay.application.crew;

import com.relay.application.policy.PolicyEngine;
import com.relay.application.port.ConnectionRepository;
import com.relay.application.port.LlmPurpose;
import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.AgentRole;
import com.relay.domain.Connection;
import com.relay.domain.PolicyMode;
import com.relay.domain.RiskLevel;
import com.relay.domain.ToolPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The crew, computed. Nothing here is authored and nothing here is stored.
 *
 * <p>A member is not a name: it is an authority, a model tier and the tools it holds
 * (docs/EKIP.md §3). All three already exist elsewhere in the system, which is the whole
 * reason this class may exist at all — it reads {@link ToolRegistry} for who is on the crew,
 * {@link PolicyEngine} for what each one may do, and {@link ConnectionRepository} for whether
 * anything is actually behind it. It writes to none of them.
 *
 * <p>Two rules it enforces by construction, both from docs/EKIP.md §5.5 and §7.5:
 *
 * <ul>
 *   <li><b>A member exists only if a registered tool produced it.</b> There is no list of
 *       members to add a name to, so the only way to put a specialist on this screen is to
 *       write the {@code Tool} class it derives from. A persona with nothing behind it is a
 *       costume, and this is the structure that makes one impossible rather than discouraged.
 *   <li><b>Authority is computed, never kept.</b> The counts below are the effective policies
 *       of the tools a member holds, grouped. A second table of per-agent permissions would be
 *       a second source that can disagree with the first, and on the day they disagreed nobody
 *       would know which one the engine obeyed.
 * </ul>
 */
public class CrewRoster {

    /**
     * The six names in {@link AgentRole}, minus the user, in the order a run visits them.
     *
     * <p>They are hard-coded because they genuinely are: {@code Planner.CREW} keeps the same
     * whitelist, and adding an integration has never added one of these. The purpose beside
     * each is the call it actually makes — {@code Planner} asks for {@code plan},
     * {@code Verifier} for {@code verify}, and the other three never reach a model at all,
     * which is why their tier is absent instead of guessed.
     */
    private static final List<CoreDuty> CORE = List.of(
            new CoreDuty(AgentRole.PLANNER, LlmPurpose.PLAN),
            new CoreDuty(AgentRole.COORDINATOR, null),
            new CoreDuty(AgentRole.VERIFIER, LlmPurpose.VERIFY),
            new CoreDuty(AgentRole.POLICY, null),
            new CoreDuty(AgentRole.COST, null));

    /** What a specialist is called for: the parameters that go to its provider. */
    private static final String SPECIALIST_PURPOSE = LlmPurpose.TOOL_PARAMS;

    private final ToolRegistry tools;
    private final PolicyEngine policies;
    private final ConnectionRepository connections;
    private final Set<String> smallPurposes;

    /**
     * @param smallPurposes {@code app.llm.small-purposes}, the same property
     *                      {@code GroqLlmClient} routes on. Passed in rather than read here
     *                      so the screen cannot claim a tier the client would not use; blank
     *                      means the shipped default, exactly as it does in {@code LlmConfig}.
     */
    public CrewRoster(ToolRegistry tools, PolicyEngine policies, ConnectionRepository connections,
                      Collection<String> smallPurposes) {
        this.tools = tools;
        this.policies = policies;
        this.connections = connections;
        this.smallPurposes = normalize(smallPurposes);
    }

    private static Set<String> normalize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return LlmPurpose.DEFAULT_SMALL;
        }
        return raw.stream()
                .filter(purpose -> purpose != null && !purpose.isBlank())
                .map(purpose -> purpose.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Crew crew() {
        return new Crew(core(), specialists());
    }

    private List<CoreMember> core() {
        List<CoreMember> out = new ArrayList<>();
        for (CoreDuty duty : CORE) {
            out.add(new CoreMember(duty.id(), duty.purpose(), tier(duty.purpose())));
        }
        return out;
    }

    /**
     * One member per tool namespace, and no member without a tool.
     *
     * <p>Grouped by {@link AgentRole#toolAgent} rather than by {@link Tool#provider()}, because
     * those two differ where it matters: Gmail and Calendar share one {@code google} connection
     * but are two members with two authorities, and the orchestrator already addresses them as
     * {@code gmail-agent} and {@code calendar-agent}. Grouping by connection would merge two
     * members the run itself keeps apart.
     */
    private List<Specialist> specialists() {
        Map<String, PolicyEngine.EffectivePolicy> effective = new LinkedHashMap<>();
        policies.effectivePolicies().forEach(policy -> effective.put(policy.toolName(), policy));

        Map<String, List<Held>> held = new LinkedHashMap<>();
        Map<String, Tool> firstTool = new LinkedHashMap<>();
        for (Tool tool : tools.all()) {
            PolicyEngine.EffectivePolicy policy = effective.get(tool.name());
            if (policy == null) {
                continue;
            }
            String id = AgentRole.toolAgent(tool.name());
            held.computeIfAbsent(id, key -> new ArrayList<>())
                    .add(new Held(tool.name(), policy.risk(), policy.mode(), policy.overridden()));
            firstTool.putIfAbsent(id, tool);
        }

        List<Specialist> out = new ArrayList<>();
        held.forEach((id, tools) -> {
            Tool sample = firstTool.get(id);
            String connectionProvider = sample.provider();
            out.add(new Specialist(
                    id,
                    ToolPolicy.providerOf(sample.name()),
                    connectionProvider,
                    connected(connectionProvider),
                    SPECIALIST_PURPOSE,
                    tier(SPECIALIST_PURPOSE),
                    List.copyOf(tools),
                    count(tools)));
        });
        // Connected first, then alphabetical. An idle member is still a member — it goes to the
        // bottom of the list, never off it, because "Notion is registered and unreachable" is a
        // truer answer than a screen that quietly leaves Notion out.
        out.sort(Comparator.comparing((Specialist s) -> !s.connected()).thenComparing(Specialist::id));
        return out;
    }

    /**
     * A provider counts as connected when credentials are stored for it, which is the same
     * test {@code ConnectionService.describeAll} reports as {@code configured}. An empty row
     * is not a connection: {@code AbstractTool} falls back to fixtures for exactly that case.
     */
    private boolean connected(String provider) {
        Optional<Connection> connection = connections.findByProvider(provider);
        return connection.isPresent() && !connection.get().config().isEmpty();
    }

    private static Map<PolicyMode, Integer> count(List<Held> tools) {
        Map<PolicyMode, Integer> counts = new LinkedHashMap<>();
        for (PolicyMode mode : PolicyMode.values()) {
            counts.put(mode, 0);
        }
        for (Held tool : tools) {
            counts.merge(tool.mode(), 1, Integer::sum);
        }
        return counts;
    }

    /** {@code null} for a member that never calls a model — an absent tier, not a cheap one. */
    private String tier(String purpose) {
        if (purpose == null) {
            return null;
        }
        return smallPurposes.contains(purpose.toLowerCase(Locale.ROOT)) ? "small" : "large";
    }

    private record CoreDuty(String id, String purpose) {
    }

    public record Crew(List<CoreMember> core, List<Specialist> members) {
    }

    public record CoreMember(String id, String purpose, String tier) {
    }

    /**
     * @param provider           the tool namespace ({@code gmail}), which is what the member is
     *                           named after and what the interface draws a mark for
     * @param connectionProvider where its credentials live ({@code google}) — the same name for
     *                           most providers, deliberately not for the Google-backed pair
     * @param authority          effective modes, counted; the union described in EKIP.md §3.1
     */
    public record Specialist(String id, String provider, String connectionProvider, boolean connected,
                             String purpose, String tier, List<Held> tools,
                             Map<PolicyMode, Integer> authority) {
    }

    public record Held(String toolName, RiskLevel risk, PolicyMode mode, boolean overridden) {
    }
}

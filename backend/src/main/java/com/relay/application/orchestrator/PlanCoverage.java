package com.relay.application.orchestrator;

import com.relay.application.port.Tool;
import com.relay.application.port.ToolRegistry;
import com.relay.domain.RiskLevel;
import com.relay.domain.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Does the plan cover the goal, and only the goal? Pure text against the tool registry —
 * no model call, same answer every time.
 *
 * <p>Two live incidents on 2026-08-01 are the whole reason this exists, and they are
 * mirror images. Run {@code 85f1b3be}: the goal named the Notion decision log and quoted a
 * note to append; the note happened to contain "KAN-32" and "tamamlandı", the planner
 * keyword-matched the payload, and the one-step plan it produced closed KAN-32 in Jira —
 * a write to a surface the goal never named, approved at the gate, reverted by hand
 * (run {@code 790f5d65}, issue #175). Same day, #145's finding: a goal naming the mailbox
 * and Jira got a plan covering a quarter of it, and the run closed green. The model can do
 * better — the same goal with an explicit tool name planned perfectly (run {@code d47e276b})
 * — but nothing deterministic was checking the result. This is that check.
 *
 * <p>Both directions are one rule: <b>surfaces the goal names are binding</b>. A provider
 * the goal names must appear in the plan ({@link Assessment#missing}), and the plan must
 * not <em>write</em> to a provider the goal never names
 * ({@link Assessment#unrequestedWrites}). Reads are free in both directions — an extra
 * lookup changes nothing outside Relay, and a named surface is covered by a read of it.
 *
 * <p>Deliberate blind spots, chosen over crying wolf:
 * <ul>
 *   <li><b>Quoted payload is stripped before matching.</b> Text the goal carries as
 *       content — the note between quotes in run {@code 85f1b3be} — is what the planner
 *       wrongly executed; a checker that read providers out of it would repeat the
 *       planner's mistake in reverse and call the good plan drifted.</li>
 *   <li><b>Ambiguous words map to nothing.</b> "kayıt" is a Jira issue, a Sheets row or a
 *       generic record; "sayfa" is Notion, Confluence or the web. A warning that fires on
 *       every second goal teaches people to approve past it, which un-writes the one line
 *       this check exists to make them read.</li>
 *   <li><b>A goal that names no surface at all gets no warnings.</b> "ekibe haber ver"
 *       leaves surface choice to the planner on purpose; flagging every write under such a
 *       goal would be the cry-wolf case again.</li>
 * </ul>
 */
public final class PlanCoverage {

    /**
     * What the plan misses and what it invents.
     *
     * @param missing           providers the goal names that no step touches
     * @param unrequestedWrites providers of WRITE steps that the goal never names
     */
    public record Assessment(List<String> missing, List<String> unrequestedWrites) {

        public boolean clean() {
            return missing.isEmpty() && unrequestedWrites.isEmpty();
        }
    }

    /**
     * A quoted span whose content is payload, not command. The opening quote must follow
     * start-of-text, whitespace or a colon and the closing one must be followed by
     * whitespace, punctuation or end-of-text — so the Turkish suffix apostrophe
     * ("Notion'daki", "KAN-32'yle") never opens or closes a span and cannot swallow the
     * words between two suffixed names.
     */
    private static final Pattern QUOTED = Pattern.compile(
            "(?<=^|[\\s:(\\[])[\"'‘’“”«](.*?)[\"'‘’“”»]"
                    + "(?=$|[\\s.,;:!?)\\]])",
            Pattern.DOTALL);

    /** {@code KAN-32} — the shape of a Jira record key. */
    private static final Pattern RECORD_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b");

    /** {@code owner/repo#42} — how a GitHub issue or PR is named across repos. */
    private static final Pattern REPO_REF =
            Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+#\\d+");

    /** {@code #genel} — a Slack channel. Requires a letter, so {@code #42} stays an issue. */
    private static final Pattern CHANNEL_REF =
            Pattern.compile("(?<![\\w/])#[\\p{L}][\\p{L}\\p{N}_-]*");

    /**
     * The alias table: provider → stems matched as word prefixes, so Turkish suffixes
     * ("takvime", "kütüğüne", "maillerime") match without a morphology engine. Stems are
     * already Turkish-folded lowercase. Only genuinely unambiguous nouns are here — see the
     * class comment for what was left out and why.
     */
    private static final Map<String, List<String>> STEMS = stems();

    /** Words matched exactly, never as a prefix — "pr" as a prefix would match "proje". */
    private static final Map<String, List<String>> EXACT_WORDS = Map.of(
            "github", List.of("pr"),
            "docs", List.of("docs"));

    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");

    private static Map<String, List<String>> stems() {
        Map<String, List<String>> stems = new LinkedHashMap<>();
        stems.put("jira", List.of("jira"));
        stems.put("notion", List.of("notion", "kütük", "kütüğ"));
        stems.put("slack", List.of("slack", "kanal"));
        stems.put("gmail", List.of("gmail", "mail", "posta"));
        stems.put("github", List.of("github"));
        stems.put("calendar", List.of("calendar", "takvim"));
        stems.put("sheets", List.of("sheets", "tablo"));
        stems.put("confluence", List.of("confluence", "wiki"));
        stems.put("docs", List.of("doküman", "döküman"));
        return stems;
    }

    private final ToolRegistry tools;

    public PlanCoverage(ToolRegistry tools) {
        this.tools = tools;
    }

    /**
     * The providers this goal names — by product name, by an unambiguous everyday noun, or
     * by an identifier whose shape belongs to one provider. Quoted payload does not count:
     * a record key inside the note being carried is content, not a request.
     *
     * <p>Folding is {@code toLowerCase} in the Turkish locale, for the same reason the
     * frontend's {@code words()} uses {@code toLocaleLowerCase('tr')}: the default locale
     * turns {@code I} into {@code i} and {@code İ} into {@code i̇} (dotted, two code
     * points), so "JİRA" would stop matching "jira" and "TAKVIM" would never match
     * "takvim". Half the goals typed into this product carry a dotless ı.
     */
    public static Set<String> mentionedProviders(String goal) {
        Set<String> out = new LinkedHashSet<>();
        String visible = QUOTED.matcher(goal == null ? "" : goal).replaceAll(" ");
        if (RECORD_KEY.matcher(visible).find()) {
            out.add("jira");
        }
        if (REPO_REF.matcher(visible).find()) {
            out.add("github");
        }
        if (CHANNEL_REF.matcher(visible).find()) {
            out.add("slack");
        }
        List<String> words = words(visible);
        STEMS.forEach((provider, stems) -> {
            if (words.stream().anyMatch(word -> stems.stream().anyMatch(word::startsWith))) {
                out.add(provider);
            }
        });
        EXACT_WORDS.forEach((provider, exact) -> {
            if (words.stream().anyMatch(exact::contains)) {
                out.add(provider);
            }
        });
        return out;
    }

    /**
     * The two lists the coordinator surfaces after planning. Empty twice over when the goal
     * names no provider at all — a goal that leaves the surface open flags nothing.
     */
    public Assessment assess(String goal, List<Step> steps) {
        Set<String> mentioned = mentionedProviders(goal);
        if (mentioned.isEmpty()) {
            return new Assessment(List.of(), List.of());
        }
        Set<String> planned = new LinkedHashSet<>();
        Set<String> written = new LinkedHashSet<>();
        for (Step step : steps) {
            Tool tool = registered(step);
            if (tool == null) {
                continue;
            }
            planned.add(tool.provider());
            if (tool.risk() != RiskLevel.READ) {
                written.add(tool.provider());
            }
        }
        List<String> missing = new ArrayList<>();
        mentioned.stream().filter(p -> !planned.contains(p)).forEach(missing::add);
        List<String> unrequested = new ArrayList<>();
        written.stream().filter(p -> !mentioned.contains(p)).forEach(unrequested::add);
        return new Assessment(List.copyOf(missing), List.copyOf(unrequested));
    }

    /** The provider this step writes to, or {@code null} for reads and reasoning steps. */
    public String writeProvider(Step step) {
        Tool tool = registered(step);
        return tool == null || tool.risk() == RiskLevel.READ ? null : tool.provider();
    }

    /** How the warning names a provider — the product name a reader knows, not the id. */
    public static String label(String provider) {
        return switch (provider) {
            case "jira" -> "Jira";
            case "notion" -> "Notion";
            case "slack" -> "Slack";
            case "gmail" -> "Gmail";
            case "github" -> "GitHub";
            case "calendar" -> "Takvim";
            case "sheets" -> "E-tablolar";
            case "confluence" -> "Confluence";
            case "docs" -> "Docs";
            case "hr" -> "İK";
            default -> provider;
        };
    }

    private Tool registered(Step step) {
        return step.toolName() == null ? null : tools.find(step.toolName()).orElse(null);
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.toLowerCase(TURKISH).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isBlank()) {
                out.add(word);
            }
        }
        return out;
    }
}

package com.relay.application.text;

import java.util.List;
import java.util.Locale;

/**
 * Recognises text that says work happened without saying what happened.
 *
 * <p>Live, Relay posted this to a Slack channel:
 * <em>"Relay özeti — channel engineering değil #all-samed olsun. Adımlar Relay tarafından
 * yürütüldü; ayrıntılar zaman çizelgesinde."</em> The Groq key was rate limited, the
 * fallback model wrote its template, the approval gate showed it, and out it went. The
 * reader learned nothing and had to go dig through a timeline — the exact work Relay
 * exists to remove.
 *
 * <p>So filler is treated as a defect, not as content: a write carrying it is refused
 * before it reaches the provider.
 */
public final class Filler {

    /** Lower-cased fragments that only ever appear in placeholder text. */
    private static final List<String> MARKERS = List.of(
            "ayrıntılar zaman çizelgesinde",
            "adımlar relay tarafından yürütüldü",
            "relay özeti —",
            "detaylar aşağıda",
            "lorem ipsum",
            "todo:",
            // The fallback writes this when the steps found nothing. Honest, but a channel
            // post that only says "nothing found" is noise — the run should stop and say so
            // to the operator instead of telling the team.
            "sonuç bulunamadı:",
            "<...>",
            "[buraya");

    private Filler() {
    }

    public static boolean looksLikeFiller(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}

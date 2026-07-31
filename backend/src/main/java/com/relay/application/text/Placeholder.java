package com.relay.application.text;

import java.util.List;

/**
 * Spots a parameter that was never actually filled in.
 *
 * <p>Live, Slack was asked to post to the channel {@code {{steps[3].channel}}} and answered
 * {@code channel_not_found}. Relay has no template engine — values move between steps
 * because the specialist reads the earlier results and writes the real value — so a
 * {@code {{…}}} in a parameter means the model deferred the work to a substitution that is
 * never going to happen.
 *
 * <p>The markers are deliberately narrow. Angle brackets are not among them: Slack's own
 * message syntax uses {@code <@U123>} and {@code <https://…|label>}, and flagging those
 * would refuse perfectly good messages.
 */
public final class Placeholder {

    private static final List<String> MARKERS = List.of("{{", "}}", "${", "steps[");

    private Placeholder() {
    }

    public static boolean unresolved(String value) {
        if (value == null) {
            return false;
        }
        for (String marker : MARKERS) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}

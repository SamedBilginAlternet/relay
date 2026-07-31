package com.relay.application.policy;

import com.relay.domain.PolicyMode;

/**
 * Verdict for one step.
 *
 * @param mode     effective policy
 * @param reason   why — shown on the timeline
 * @param explicit true when an operator override decided it, false when it came from the risk default
 */
public record PolicyDecision(PolicyMode mode, String reason, boolean explicit) {

    public boolean auto() {
        return mode == PolicyMode.AUTO;
    }

    public boolean ask() {
        return mode == PolicyMode.ASK;
    }

    public boolean forbidden() {
        return mode == PolicyMode.FORBIDDEN;
    }
}

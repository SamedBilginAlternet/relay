package com.relay.application.orchestrator;

import com.relay.application.port.Clock;
import com.relay.application.port.EventPublisher;
import com.relay.application.port.RunEvent;
import com.relay.application.view.Views;
import com.relay.domain.AgentMessage;
import com.relay.domain.Run;
import java.util.UUID;

/**
 * Agent-to-agent chatter. Every line is stored on the run AND pushed as an
 * {@code agent.message} event, so the timeline shows who told whom what.
 */
public class AgentJournal {

    private final EventPublisher events;
    private final Clock clock;

    public AgentJournal(EventPublisher events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    public AgentMessage say(Run run, UUID stepId, String from, String to, String content) {
        AgentMessage message = AgentMessage.of(run.id(), stepId, from, to, content, clock.now());
        run.addMessage(message);
        events.publish(run.id(), RunEvent.of(RunEvent.AGENT_MESSAGE, Views.message(message)));
        return message;
    }
}

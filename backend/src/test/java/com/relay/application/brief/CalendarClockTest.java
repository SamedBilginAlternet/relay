package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.ToolRegistry;
import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.infrastructure.tools.ToolRegistryImpl;
import com.relay.support.TestDoubles;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A 05:00 meeting in Istanbul was displayed as "02:00": the label was the characters after
 * the T in the ISO string, which is the instant's UTC clock. Google returns whatever offset
 * the event carries, so that string can never be read as a local time.
 */
class CalendarClockTest {

    private BriefService service(String timezone) {
        ToolRegistry tools = new ToolRegistryImpl(List.of());
        TestDoubles.FixedClock clock = new TestDoubles.FixedClock();
        StubLlmClient llm = new StubLlmClient(tools);
        return new BriefService(tools, new TestDoubles.InMemoryConnectionRepository(),
                new InsightService(llm, tools), new DigestService(llm), llm, clock, Runnable::run,
                Duration.ofSeconds(8), Duration.ofSeconds(60), timezone, "RELAY");
    }

    @Test
    void a_utc_instant_is_shown_on_the_user_s_clock() {
        assertThat(service("Europe/Istanbul").clockLabel("2026-08-01T02:00:00Z")).isEqualTo("05:00");
    }

    @Test
    void an_offset_the_provider_sent_is_converted_not_copied() {
        // Same instant, written with a +02:00 offset by the provider.
        assertThat(service("Europe/Istanbul").clockLabel("2026-08-01T04:00:00+02:00")).isEqualTo("05:00");
    }

    @Test
    void another_zone_reads_the_same_instant_differently() {
        assertThat(service("Europe/London").clockLabel("2026-08-01T02:00:00Z")).isEqualTo("03:00");
    }

    /** An all-day event has no clock; showing a made-up one would be worse than showing none. */
    @Test
    void an_all_day_event_has_no_clock() {
        assertThat(service("Europe/Istanbul").clockLabel("2026-08-01")).isEmpty();
        assertThat(service("Europe/Istanbul").clockLabel("")).isEmpty();
    }
}

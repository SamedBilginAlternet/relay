package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmPurpose;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Live, the Bugün screen printed two ellipses where the day's summary belongs: the prompt
 * ends with the shape to fill in — {@code {"summary":"…","advice":"…"}} — and a small model
 * under pressure copied it instead of answering. A digest that says nothing must not exist.
 */
class DigestTemplateTest {

    private static final BriefItem ITEM = new BriefItem("jira:KAN-4", "jira", "issue", "KAN-4",
            "Profil sayfası yeniden tasarımı", "Devam Ediyor", "25dk önce", "Samed",
            "https://jira.example/KAN-4", "2026-07-31T08:30:00Z", BriefItem.DEFAULT,
            Map.of("issueKey", "KAN-4"));

    private java.util.Optional<DigestService.Digest> digestFrom(String json) {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(
                Map.of(LlmPurpose.DIGEST, json));
        return new DigestService(llm).digest(List.of(ITEM), List.of());
    }

    @Test
    void an_echoed_template_is_not_a_summary() {
        assertThat(digestFrom("{\"summary\":\"…\",\"priorities\":[],\"advice\":\"…\"}")).isEmpty();
    }

    @Test
    void a_dotted_placeholder_counts_too() {
        assertThat(digestFrom("{\"summary\":\"...\",\"priorities\":[],\"advice\":\"...\"}")).isEmpty();
    }

    @Test
    void a_real_summary_survives_and_drops_only_the_empty_advice() {
        var digest = digestFrom("{\"summary\":\"KAN-4 bugün bitmeli.\","
                + "\"priorities\":[{\"itemId\":\"jira:KAN-4\",\"why\":\"…\"}],\"advice\":\"…\"}");

        assertThat(digest).isPresent();
        assertThat(digest.orElseThrow().summary()).isEqualTo("KAN-4 bugün bitmeli.");
        assertThat(digest.orElseThrow().advice()).isEmpty();
        assertThat(digest.orElseThrow().priorities()).as("a placeholder reason is not a reason").isEmpty();
    }
}

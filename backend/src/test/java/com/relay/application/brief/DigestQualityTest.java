package com.relay.application.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.relay.application.port.LlmPurpose;
import com.relay.support.TestDoubles;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The paragraph the jury reads in the first twelve seconds, twice broken on a healthy model.
 *
 * <p>Live on 1 August, with {@code degraded:false} and five keys available, the Bugün screen
 * printed <em>"…birlikte beberapaNeeds_reply mailleri bekliyor. id=gmail:19fbb199a0786906,
 * id=github-pr:…#43…"</em>; the next generation ended a sentence with <em>"önemli bir
 * vấn"</em>. An Indonesian word, a Vietnamese one, the raw {@code kind} enum and the internal
 * item ids the prompt uses to name a row — none of it produced by a failure the code could
 * see. A 70B model asked for Turkish drifts, so the answer is checked rather than trusted.
 *
 * <p>These tests hold the line in both directions. Broken text must not reach the screen, and
 * a perfectly ordinary Turkish sentence — {@code çğıöşü} and all — must not be thrown away by
 * the checker that removes it. The second half is the one that would quietly cost the demo
 * its summary every time.
 */
class DigestQualityTest {

    private static final BriefItem MAIL = new BriefItem("gmail:19fbb199a0786906", "gmail", "mail",
            "", "Ödeme adımında hata alıyoruz", "Ayşe Yıldız", "2sa önce", "Ayşe Yıldız",
            "https://mail.example/1", "2026-08-01T05:41:00Z", BriefItem.WARN,
            Map.of("messageId", "19fbb199a0786906"));

    private Optional<DigestService.Digest> digestFrom(String json) {
        TestDoubles.ScriptedLlmClient llm = new TestDoubles.ScriptedLlmClient(
                Map.of(LlmPurpose.DIGEST, json));
        return new DigestService(llm).digest(List.of(MAIL), List.of());
    }

    private Optional<DigestService.Digest> withSummary(String summary) {
        return digestFrom("{\"summary\":\"" + summary + "\",\"priorities\":[],\"advice\":\"\"}");
    }

    @Test
    void a_digest_that_leaks_an_item_id_is_not_shown() {
        assertThat(withSummary("Bugün id=gmail:19fbb199a0786906 ve "
                + "id=github-pr:SamedBilginAlternet/issue-to-notion-demo#43 seni bekliyor."))
                .as("the user never sees the handle the prompt uses to name a row")
                .isEmpty();
    }

    @Test
    void a_digest_that_leaks_a_raw_enum_is_not_shown() {
        // Glued to the word in front of it, exactly as it arrived live: a word boundary
        // would have let this through.
        assertThat(withSummary("Ödeme hatası ile birlikte beberapaNeeds_reply mailleri bekliyor."))
                .isEmpty();
        assertThat(withSummary("Bugün birkaç needs_reply maili ve bir bug_report var.")).isEmpty();
    }

    @Test
    void a_digest_that_slipped_into_another_language_is_not_shown() {
        // Vietnamese, the second live generation.
        assertThat(withSummary("Giriş sonrası yönlendirme kaybı önemli bir vấn")).isEmpty();
        // Indonesian is written with our own alphabet, so it is named rather than detected.
        assertThat(withSummary("Bugün beberapa mail seni bekliyor.")).isEmpty();
        // And anything in a script neither language uses.
        assertThat(withSummary("Bugün üç iş var, включая один срочный.")).isEmpty();
        assertThat(withSummary("Bugün üç iş var, 一つは緊急です。")).isEmpty();
    }

    @Test
    void a_turkish_summary_keeps_its_turkish_letters() {
        Optional<DigestService.Digest> digest = withSummary("Ödeme adımında hata alıyoruz "
                + "konulu mail bugünün en acil işi; ardından iki pull request inceleme bekliyor.");

        assertThat(digest).as("çğıöşü is Turkish, not a foreign script").isPresent();
        assertThat(digest.orElseThrow().summary()).contains("Ödeme adımında hata alıyoruz");
    }

    /** A bad reason costs its own row, not the whole paragraph — the summary was fine. */
    @Test
    void only_the_reason_that_leaks_is_dropped() {
        Optional<DigestService.Digest> digest = digestFrom(
                "{\"summary\":\"Ödeme hatası bugünün en acil işi.\",\"priorities\":["
                + "{\"itemId\":\"gmail:19fbb199a0786906\",\"why\":\"needs_reply olarak işaretli\"}],"
                + "\"advice\":\"Sabahı ödeme hatasına ayır.\"}");

        assertThat(digest).isPresent();
        assertThat(digest.orElseThrow().summary()).isEqualTo("Ödeme hatası bugünün en acil işi.");
        assertThat(digest.orElseThrow().priorities()).isEmpty();
        assertThat(digest.orElseThrow().advice()).isEqualTo("Sabahı ödeme hatasına ayır.");
    }

    /** "Jira: bugün üç kayıt var" is prose; {@code jira:KAN-4} is a handle. */
    @Test
    void a_colon_in_a_sentence_is_not_an_item_id() {
        assertThat(withSummary("Jira: bugün üç kayıt açık, ikisi seni bekliyor.")).isPresent();
        // "pull request" is how people write about GitHub in Turkish — it is not an enum.
        assertThat(withSummary("İki pull request incelemeni bekliyor.")).isPresent();
    }
}

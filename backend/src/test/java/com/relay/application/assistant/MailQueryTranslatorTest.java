package com.relay.application.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.relay.infrastructure.llm.StubLlmClient;
import com.relay.support.TestDoubles;
import org.junit.jupiter.api.Test;

/**
 * The question → query seam. The model writes the query; this test holds the gate that
 * decides whether it may run, because everything rejected here has come back from a model
 * at least once.
 */
class MailQueryTranslatorTest {

    private static final String CARGO = "Kargolarım gelmiş mi?";

    @Test
    void theModelsQueryIsUsedAndShownBackToTheUser() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("""
                {"query":"(from:(trendyol OR aras OR yurtici) OR subject:(kargo OR teslimat)) newer_than:30d",
                 "explanation":"Son 30 günde kargo maillerini aradım."}""");

        MailQueryTranslator.Translation translation = new MailQueryTranslator(llm).translate(CARGO);

        assertThat(translation.source()).isEqualTo(MailQueryTranslator.SOURCE_LLM);
        assertThat(translation.query()).contains("trendyol").contains("newer_than:30d");
        assertThat(translation.explanation()).contains("kargo");
        assertThat(translation.tokens()).isPositive();
        // The question itself reaches the model — nothing else could answer "Ahmet'ten geldi mi".
        assertThat(llm.requests.get(0).user()).contains(CARGO);
        assertThat(llm.requests.get(0).schema()).isNotNull();
    }

    @Test
    void aTurkishQuestionStillFindsCargoSendersWhenTheModelIsOnTheStub() {
        // The stub answers every purpose with its own summary text; the fallback rules run.
        MailQueryTranslator.Translation translation =
                new MailQueryTranslator(new StubLlmClient(null)).translate(CARGO);

        assertThat(translation.source()).isEqualTo(MailQueryTranslator.SOURCE_HEURISTIC);
        assertThat(translation.query())
                .contains("trendyol").contains("aras").contains("yurtici")
                .contains("kargo").contains("newer_than:");
        assertThat(translation.explanation()).isNotBlank();
        assertThat(translation.tokens()).isZero();
    }

    /** {@code "KARGO"} and {@code "İADE"} must fold the same way an ASCII keyword list expects. */
    @Test
    void turkishDottedAndDotlessCapitalsStillMatchTheRules() {
        MailQueryTranslator translator = new MailQueryTranslator(new StubLlmClient(null));

        assertThat(translator.translate("KARGOLARIM GELMİŞ Mİ").query()).contains("kargo");
        assertThat(translator.translate("SİPARİŞİM NEREDE").query()).contains("kargo");
        assertThat(translator.translate("Faturalarımı göster").query()).contains("fatura");
        assertThat(translator.translate("FATURA GELDİ Mİ").query()).contains("fatura");
    }

    @Test
    void proseAndPackagingFromTheModelAreRejectedInFavourOfTheRules() {
        // A sentence about the search instead of the search.
        assertThat(MailQueryTranslator.sanitize(
                "Kullanıcının kargo maillerini son otuz gün içinde arayıp sonuçları listeleyeceğim"))
                .isNull();
        // Fenced blocks, unbalanced parentheses and stray quotes never reach Gmail.
        assertThat(MailQueryTranslator.sanitize("```\nfrom:trendyol\n```")).isNull();
        assertThat(MailQueryTranslator.sanitize("from:(trendyol OR aras newer_than:30d")).isNull();
        assertThat(MailQueryTranslator.sanitize("subject:\"kargo newer_than:30d")).isNull();
        assertThat(MailQueryTranslator.sanitize("x".repeat(500))).isNull();
        assertThat(MailQueryTranslator.sanitize("")).isNull();

        // Packaging that is safe to strip, rather than reject.
        assertThat(MailQueryTranslator.sanitize("q=from:trendyol newer_than:7d"))
                .isEqualTo("from:trendyol newer_than:7d");
        assertThat(MailQueryTranslator.sanitize("\"from:trendyol newer_than:7d\""))
                .isEqualTo("from:trendyol newer_than:7d");
        assertThat(MailQueryTranslator.sanitize("from:trendyol\n  newer_than:7d"))
                .isEqualTo("from:trendyol newer_than:7d");
        // A bare keyword search is a legitimate query.
        assertThat(MailQueryTranslator.sanitize("kargo teslimat")).isEqualTo("kargo teslimat");
    }

    @Test
    void aRejectedModelQueryFallsBackToTheRulesWithoutFailingTheRequest() {
        MailQueryTranslator.Translation translation = new MailQueryTranslator(
                new TestDoubles.StaticLlmClient("Tabii, kargo maillerine bakayım!")).translate(CARGO);

        assertThat(translation.source()).isEqualTo(MailQueryTranslator.SOURCE_HEURISTIC);
        assertThat(translation.query()).contains("kargo");
        // The tokens the rejected call cost are still reported.
        assertThat(translation.tokens()).isPositive();
    }

    @Test
    void anAddressInTheQuestionBecomesAFromFilter() {
        MailQueryTranslator.Translation translation = new MailQueryTranslator(new StubLlmClient(null))
                .translate("ayse@alterteam.dev adresinden bir şey geldi mi?");

        assertThat(translation.query()).startsWith("from:ayse@alterteam.dev");
    }

    /**
     * "Şundan mail gelmiş mi" is the question this endpoint exists for. Live, the fallback
     * searched for the string "Atlassiandan" and reported nothing found while an Atlassian
     * mail was sitting in the inbox.
     */
    @Test
    void theTurkishAblativeSuffixNamesTheSenderRatherThanTheSearchTerm() {
        MailQueryTranslator translator = new MailQueryTranslator(new StubLlmClient(null));

        assertThat(translator.translate("Atlassian'dan mail gelmiş mi?").query())
                .isEqualTo("from:Atlassian newer_than:90d");
        assertThat(translator.translate("Ayşe'den bir şey var mı?").query())
                .startsWith("from:Ayşe");
        assertThat(translator.translate("Migros'tan kampanya maili geldi mi?").query())
                .startsWith("from:Migros");
        // A sender the question names beats the topic rules — it is the narrower search.
        assertThat(translator.translate("Trendyol'dan kargo maili geldi mi?").query())
                .startsWith("from:Trendyol");
    }

    @Test
    void anUnknownTopicIsSearchedWithTheQuestionsOwnWords() {
        MailQueryTranslator.Translation translation = new MailQueryTranslator(new StubLlmClient(null))
                .translate("Vergi levhası ile ilgili mail gelmiş mi?");

        assertThat(translation.query()).contains("Vergi").contains("levhası").contains("newer_than:");
        // Scaffolding words are not searched for: live, "ilgili" went into the query and
        // no mail about a tax certificate contains it.
        assertThat(translation.query()).doesNotContain("gelmiş").doesNotContain("ilgili");
    }

    @Test
    void anEmptyQuestionIsRejectedBeforeAnyCall() {
        TestDoubles.StaticLlmClient llm = new TestDoubles.StaticLlmClient("{}");

        assertThatThrownBy(() -> new MailQueryTranslator(llm).translate("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(llm.requests).isEmpty();
    }
}

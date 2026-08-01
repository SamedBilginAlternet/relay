package com.relay.application.playbook;

import com.relay.application.playbook.Playbook.Move;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The written-down flows. Deliberately few: each one is a job a knowledge worker does
 * every single day, and each ends by telling someone what changed.
 */
public final class Playbooks {

    public static final Playbook MORNING = new Playbook(
            "gunun-ozeti",
            "Günün özeti",
            "Bugün üstümde ne var? Jira kayıtlarımı, review bekleyen PR'ları ve bugünkü "
                    + "toplantılarımı topla, sonra ekibe kısa bir durum mesajı yaz.",
            "Jira · GitHub · Takvim okunur, ardından Slack mesajı onayına gelir",
            List.of(
                    Move.required("Üstümdeki Jira kayıtlarını getir", "jira.listMyIssues", Map.of()),
                    Move.optional("Review bekleyen PR'ları getir", "github.listMyPullRequests", Map.of()),
                    Move.optional("Bugünün toplantılarını getir", "calendar.listToday", Map.of()),
                    Move.optional("Ekibe durum mesajı gönder", "slack.postMessage", Map.of())));

    public static final Playbook MAIL_TO_TICKET = new Playbook(
            "maili-tickete-cevir",
            "Maili işe çevir",
            "Bugünkü maillerime bak; iş talebi ya da hata bildirimi olanlar için Jira kaydı aç "
                    + "ve kaydı açtığımı ilgili kanaldan bildir.",
            "Gmail okunur; Jira kaydı, Notion notu ve Slack bildirimi ayrı ayrı onayına gelir",
            List.of(
                    Move.required("Bugünün maillerini oku", "gmail.listToday", Map.of()),
                    Move.required("Jira kaydını aç", "jira.createIssue", Map.of()),
                    // Optional, so a workspace with no Notion connection runs the flow
                    // unchanged: PlaybookService drops an optional step whose provider is
                    // not connected. The note is where the work is written down for the
                    // people who never open the issue tracker.
                    Move.optional("Notion'a not sayfası aç", "notion.createPage", Map.of()),
                    Move.optional("Kanala bildir", "slack.postMessage", Map.of())));

    public static final Playbook BLOCKERS = new Playbook(
            "blocker-taramasi",
            "Takılan işler",
            "Blocker etiketli açık kayıtları bul, hangileri kimde bekliyor çıkar ve ekibe "
                    + "kayıt anahtarlarıyla birlikte özet geç.",
            "Jira taranır, Slack özeti onayına gelir",
            List.of(
                    Move.required("Blocker kayıtlarını bul", "jira.searchIssues",
                            Map.of("jql", "labels = blocker AND statusCategory != Done ORDER BY updated DESC")),
                    Move.optional("Ekibe özet gönder", "slack.postMessage", Map.of())));

    public static final Playbook PR_REVIEW = new Playbook(
            "pr-durumu",
            "Bekleyen incelemeler",
            "Review bekleyen pull request'lerimi listele ve iki günden uzun bekleyenleri "
                    + "ekibe hatırlat.",
            "GitHub okunur, Slack hatırlatması onayına gelir",
            List.of(
                    Move.required("Review bekleyen PR'ları getir", "github.listMyPullRequests", Map.of()),
                    Move.optional("Bekleyenleri ekibe hatırlat", "slack.postMessage", Map.of())));

    /**
     * "Toplantıya katılmadan önce şuna bak."
     *
     * <p>The reason it is written down rather than typed as a goal: the shape never changes.
     * Read today's calendar, take the clue the meeting itself carries — its title, the people
     * invited — and go looking for it in the records and in the mail.
     *
     * <p>The reading steps are optional on purpose: a workspace with Jira but no mail still
     * gets the half it can have. The calendar step is not, because without the meeting there
     * is nothing to prepare for.
     *
     * <p>It ends where the preparation ends: a meeting that needs a follow-up gets one
     * proposed. That step is a WRITE, so it stops at the approval gate — the reading half
     * still runs to the end on its own, and a workspace whose Google grant is one scope short
     * simply never gets there.
     */
    public static final Playbook MEETING_PREP = new Playbook(
            "toplanti-hazirligi",
            "Toplantı öncesi hazırlık",
            "Bugünkü toplantıma girmeden önce hazırlan: takvimdeki ilk toplantıyı al, "
                    + "başlığındaki ve katılımcılarındaki ipuçlarıyla ilgili Jira kayıtlarını ve "
                    + "mailleri bul, bulduklarını kaynak (kayıt anahtarı, mail konusu) göstererek "
                    + "özetle. İlgili bir şey çıkmazsa uydurma — 'bulunamadı' de. Konuşulacaklar "
                    + "bir toplantıya sığmayacaksa takvime bir takip toplantısı öner.",
            "Takvim · Jira · Gmail okunur; takip toplantısı önerilirse onayına gelir",
            List.of(
                    Move.required("Bugünün toplantılarını getir", "calendar.listToday", Map.of()),
                    Move.optional("Toplantının konusuyla ilgili kayıtları ara", "jira.searchIssues", Map.of()),
                    Move.optional("Toplantının konusuyla ilgili mailleri ara", "gmail.search", Map.of()),
                    Move.optional("Takip toplantısı öner", "calendar.createEvent", Map.of())));

    /**
     * Order is the shelf's argument. The mail flow leads because it is the one job every
     * desk has; the engineering-shaped ones follow. A shelf that opens with "Takılan işler"
     * tells a first-time reader this is a tool for a sprint board.
     */
    public static final List<Playbook> ALL =
            List.of(MAIL_TO_TICKET, MORNING, MEETING_PREP, BLOCKERS, PR_REVIEW);

    private Playbooks() {
    }

    public static Optional<Playbook> byId(String id) {
        return ALL.stream().filter(playbook -> playbook.id().equals(id)).findFirst();
    }
}

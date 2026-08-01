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

    /**
     * "Neye takıldık, kimde bekliyor."
     *
     * <p>It ends twice on purpose. The Slack message is what the team reads today; the
     * spreadsheet row is what somebody reads in a month, when the question is whether the
     * same thing keeps happening. Both are optional, so a workspace with only Jira still
     * gets the scan, and both stop at the approval gate.
     */
    public static final Playbook BLOCKERS = new Playbook(
            "blocker-taramasi",
            "Takılan işler",
            "Blocker etiketli açık kayıtları bul, hangileri kimde bekliyor çıkar ve ekibe "
                    + "kayıt anahtarlarıyla birlikte özet geç. Sonra takip tablosuna bugünün "
                    + "tarihi, kayıt sayısı ve en uzun bekleyen kayıtla bir satır ekle.",
            "Jira taranır; Slack özeti ve tablo satırı ayrı ayrı onayına gelir",
            List.of(
                    Move.required("Blocker kayıtlarını bul", "jira.searchIssues",
                            Map.of("jql", "labels = blocker AND statusCategory != Done ORDER BY updated DESC")),
                    Move.optional("Ekibe özet gönder", "slack.postMessage", Map.of()),
                    Move.optional("Takip tablosuna satır ekle", "sheets.appendRow", Map.of())));

    /**
     * The chain {@code sheets.appendRow} could not close on its own: the blocker scan has
     * been writing a row a week into the tracking sheet, and nothing could read them back.
     * "Is this the fourth week running" lives in those rows, and until {@code
     * sheets.readRange} the only way to answer it was to open the sheet by hand — which is
     * the exact work Relay claims to remove.
     *
     * <p>The seeded range is deliberately tab-less: {@code ReadRange.withDefaults} prefixes
     * the connection's {@code defaultSheetName}, so the same playbook reads {@code Takip} on
     * the workspace that configured it and the first tab on the one that did not.
     */
    public static final Playbook SHEET_DIGEST = new Playbook(
            "tablo-ozeti",
            "Tablo özeti",
            "Takip tablosunun son satırlarını oku; tekrarlayan ve en uzun süredir bekleyen "
                    + "kalemleri kayıt anahtarlarıyla çıkar ve ekibe kısa bir özet gönder.",
            "Tablo okunur, Slack özeti onayına gelir",
            List.of(
                    Move.required("Takip tablosunu oku", "sheets.readRange",
                            Map.of("range", "A1:F50")),
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
     * <p>It ends where the preparation ends: the summary that was assembled gets a document
     * to live in ("Toplantı notu taslağı aç"), and a meeting that needs a follow-up gets one
     * proposed. Both are WRITEs, so each stops at its own approval gate — the reading half
     * still runs to the end on its own, and a workspace whose Google grant is a scope short
     * simply never gets there. The note step is optional like every write here: preparation
     * read aloud in chat is still preparation.
     */
    public static final Playbook MEETING_PREP = new Playbook(
            "toplanti-hazirligi",
            "Toplantı öncesi hazırlık",
            "Bugünkü toplantıma girmeden önce hazırlan: takvimdeki ilk toplantıyı al, "
                    + "başlığındaki ve katılımcılarındaki ipuçlarıyla ilgili Jira kayıtlarını ve "
                    + "mailleri bul, bulduklarını kaynak (kayıt anahtarı, mail konusu) göstererek "
                    + "özetle. İlgili bir şey çıkmazsa uydurma — 'bulunamadı' de. Hazırlığı "
                    + "toplantı notu taslağı olarak bir dokümana aç. Konuşulacaklar bir "
                    + "toplantıya sığmayacaksa takvime bir takip toplantısı öner.",
            "Takvim · Jira · Gmail okunur; not taslağı ve takip toplantısı onayına gelir",
            List.of(
                    Move.required("Bugünün toplantılarını getir", "calendar.listToday", Map.of()),
                    Move.optional("Toplantının konusuyla ilgili kayıtları ara", "jira.searchIssues", Map.of()),
                    Move.optional("Toplantının konusuyla ilgili mailleri ara", "gmail.search", Map.of()),
                    Move.optional("Toplantı notu taslağı aç", "docs.createDocument", Map.of()),
                    Move.optional("Takip toplantısı öner", "calendar.createEvent", Map.of())));

    /**
     * The HR story, and deliberately not an "HR integration" (#169). There is no HR
     * provider here to connect: Workday and its kin sell tenants, not API keys, and a
     * tool wired to nothing would be exactly the theatre this product refuses. What a
     * small company's HR actually runs on is the three things already connected — the
     * mailbox the requests arrive in, the calendar the absence lives on, the sheet the
     * balance is tracked in. So leave management is a flow over real tools, and every
     * write in it stops at its own gate.
     *
     * <p>The reading step is required: without the requests there is nothing to process,
     * and a goal that says "gelenler için" with nothing arrived is the empty-precondition
     * case — the writing steps skip with their reason instead of inventing an absence
     * (#168), and the run closes honestly.
     */
    public static final Playbook LEAVE_REQUESTS = new Playbook(
            "izin-talepleri",
            "İzin talepleri",
            "Son bir haftanın maillerinde ekipten gelen izin taleplerini bul. Gelenler için: "
                    + "izin günlerini takvimime blok olarak işle, izin takip tablosuna kişi, "
                    + "tarih aralığı ve izin türüyle bir satır ekle, ve talebi yanıtlayan kısa "
                    + "bir onay maili taslağı hazırla. Talep yoksa uydurma — bulunmadığını "
                    + "söyle ve hiçbir şey yazma.",
            "Gmail okunur; takvim bloğu, tablo satırı ve cevap taslağı ayrı ayrı onayına gelir",
            List.of(
                    Move.required("İzin taleplerini maillerde ara", "gmail.search",
                            Map.of("query", "subject:(izin OR \"annual leave\" OR rapor) newer_than:7d",
                                    "maxResults", 15)),
                    Move.optional("İzin günlerini takvime işle", "calendar.createEvent", Map.of()),
                    Move.optional("İzin tablosuna satır ekle", "sheets.appendRow", Map.of()),
                    Move.optional("Onay cevabını taslakla", "gmail.createDraft", Map.of())));

    /**
     * Order is the shelf's argument. The mail flow leads because it is the one job every
     * desk has; the engineering-shaped ones follow. A shelf that opens with "Takılan işler"
     * tells a first-time reader this is a tool for a sprint board.
     */
    public static final List<Playbook> ALL =
            List.of(MAIL_TO_TICKET, MORNING, LEAVE_REQUESTS, MEETING_PREP, BLOCKERS,
                    SHEET_DIGEST, PR_REVIEW);

    private Playbooks() {
    }

    public static Optional<Playbook> byId(String id) {
        return ALL.stream().filter(playbook -> playbook.id().equals(id)).findFirst();
    }
}

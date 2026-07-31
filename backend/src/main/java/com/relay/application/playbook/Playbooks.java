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
            "Maili tickete çevir",
            "Bugünkü maillerime bak; iş talebi ya da hata bildirimi olanlar için Jira kaydı aç "
                    + "ve kaydı açtığımı ilgili kanaldan bildir.",
            "Gmail okunur, Jira kaydı ve Slack bildirimi ayrı ayrı onayına gelir",
            List.of(
                    Move.required("Bugünün maillerini oku", "gmail.listToday", Map.of()),
                    Move.required("Jira kaydını aç", "jira.createIssue", Map.of()),
                    Move.optional("Kanala bildir", "slack.postMessage", Map.of())));

    public static final Playbook BLOCKERS = new Playbook(
            "blocker-taramasi",
            "Blocker taraması",
            "Blocker etiketli açık kayıtları bul, hangileri kimde bekliyor çıkar ve ekibe "
                    + "kayıt anahtarlarıyla birlikte özet geç.",
            "Jira taranır, Slack özeti onayına gelir",
            List.of(
                    Move.required("Blocker kayıtlarını bul", "jira.searchIssues",
                            Map.of("jql", "labels = blocker AND statusCategory != Done ORDER BY updated DESC")),
                    Move.optional("Ekibe özet gönder", "slack.postMessage", Map.of())));

    public static final Playbook PR_REVIEW = new Playbook(
            "pr-durumu",
            "PR durumu",
            "Review bekleyen pull request'lerimi listele ve iki günden uzun bekleyenleri "
                    + "ekibe hatırlat.",
            "GitHub okunur, Slack hatırlatması onayına gelir",
            List.of(
                    Move.required("Review bekleyen PR'ları getir", "github.listMyPullRequests", Map.of()),
                    Move.optional("Bekleyenleri ekibe hatırlat", "slack.postMessage", Map.of())));

    public static final List<Playbook> ALL = List.of(MORNING, BLOCKERS, PR_REVIEW, MAIL_TO_TICKET);

    private Playbooks() {
    }

    public static Optional<Playbook> byId(String id) {
        return ALL.stream().filter(playbook -> playbook.id().equals(id)).findFirst();
    }
}

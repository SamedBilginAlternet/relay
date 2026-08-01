/**
 * What every action button in Bugün is called.
 *
 * The model picks WHICH tool moves a job on; it does not get to name the button.
 * It used to: the brief carried a `label` written fresh on every generation, so
 * the same urgent mail offered "Jira issue oluştur", then "Jira'ya aç", then
 * "Jira kaydı aç" across three consecutive loads of the same screen, and the
 * same pull request was "İncele ve yorumla" one minute and "Review iste" the
 * next. A button name is the product's vocabulary: people learn it and get
 * faster, they screenshot it, they write it in a support note, they say it to
 * each other. A name that is rewritten every render can do none of that — and
 * "Review iste" was English in a Turkish interface besides.
 *
 * So the choice stays with the model and the wording comes from here, keyed by
 * the tool's registered name. Every label is a specific Turkish verb phrase
 * (DESIGN.md §3): what the click does, not what the tool is.
 *
 * An unknown tool keeps the model's own words. The registry can grow before
 * this file does, and a button labelled with a raw tool id — or, worse, a
 * guessed Turkish phrase for something we do not recognise — would be a worse
 * answer than the sentence the model wrote for it.
 */
const LABELS: Record<string, string> = {
  // Jira
  'jira.createIssue': 'Jira kaydı aç',
  'jira.updateIssue': 'Kaydı güncelle',
  'jira.addComment': 'Kayda yorum ekle',
  'jira.getComments': 'Yorumları getir',
  'jira.getIssue': 'Kaydı oku',
  'jira.searchIssues': 'Kayıtlarda ara',
  'jira.listMyIssues': 'Üstümdeki kayıtları getir',

  // Gmail
  'gmail.createDraft': 'Cevap yaz',
  'gmail.getMessage': 'Maili oku',
  'gmail.search': 'Postada ara',
  'gmail.listToday': 'Bugünün maillerini getir',

  // GitHub
  'github.addComment': 'İncele ve yorumla',
  'github.listMyPullRequests': 'Bekleyen değişiklikleri getir',
  'github.listMyIssues': 'Bana atanan kayıtları getir',

  // Slack
  'slack.postMessage': 'Slack’e özet at',
  'slack.listChannels': 'Kanalları getir',

  // Notion
  'notion.createPage': 'Notion’a not aç',

  // Calendar
  'calendar.listToday': 'Bugünün toplantılarını getir',
  'calendar.listUpcoming': 'Yaklaşan toplantıları getir',
};

/** Every button name the interface owns — the list the test checks. */
export const ACTION_LABELS: Readonly<Record<string, string>> = LABELS;

/**
 * The button's words: ours when we know the tool, the model's when we do not.
 *
 * @param tool     registered tool name, e.g. `jira.createIssue`
 * @param proposed what the model called it
 */
export function actionLabel(tool: string, proposed: string): string {
  return LABELS[tool] ?? proposed;
}

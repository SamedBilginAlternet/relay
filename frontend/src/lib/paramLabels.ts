/**
 * Human names for the tool parameters shown on the approval gate.
 *
 * The gate is the demo's centrepiece: a person reads what is about to be sent
 * and says yes. It was labelling those boxes with the raw API field name, so a
 * team lead was asked to approve `PROJECTKEY`, `SUMMARY` and — because the page
 * is `lang="tr"` and the label was uppercased in CSS — `İSSUETYPE`, dotted
 * capital and all. Turkish maps `i` to `İ`, which is right for Turkish words and
 * wrong for an English identifier; every field with an `i` in it came out looking
 * like a typo on the most closely watched frame of the pitch.
 *
 * Two rules follow, and they are why this file exists rather than an inline map:
 *
 *  1. A known field gets a Turkish noun a non-engineer reads without stopping.
 *  2. An unknown field falls back to the raw key **unchanged** — no uppercasing,
 *     no prettifying. The model can invent a parameter name; guessing a Turkish
 *     word for something we do not recognise would put a label on the screen
 *     that does not match what gets sent, which is exactly the trust the gate
 *     is there to hold.
 *
 * The keys come from the registered tools' `schema()` (Jira, Slack, Gmail,
 * Calendar, GitHub, Notion). Matching is case-insensitive because the schemas are not
 * consistent themselves — Jira's create step spells it `issueType` in one place
 * and `issuetype` in another.
 */
const LABELS: Record<string, string> = {
  // Jira
  projectkey: 'Proje',
  project: 'Proje',
  summary: 'Başlık',
  issuetype: 'Konu türü',
  issuekey: 'Kayıt',
  description: 'Açıklama',
  status: 'Durum',
  transition: 'Durum geçişi',
  assignee: 'Sorumlu',
  priority: 'Öncelik',
  comment: 'Yorum',
  jql: 'Arama sorgusu',

  // Slack
  channel: 'Kanal',
  text: 'Mesaj',
  threadts: 'Yanıtlanan mesaj',

  // Notion
  parentdatabaseid: 'Veritabanı',
  content: 'İçerik',

  // Gmail
  to: 'Kime',
  cc: 'Bilgi',
  subject: 'Konu',
  body: 'İçerik',
  inreplyto: 'Yanıtlanan mesaj',

  // Calendar
  title: 'Başlık',
  location: 'Yer',
  timezone: 'Saat dilimi',
  days: 'Gün sayısı',

  // GitHub
  repo: 'Depo',
  number: 'Numara',

  // Shared
  query: 'Arama',
  limit: 'En fazla kayıt',
  maxresults: 'En fazla kayıt',
};

/** A label a person can read, or the raw key exactly as the model wrote it. */
export function paramLabel(key: string): string {
  return LABELS[key.toLowerCase()] ?? key;
}

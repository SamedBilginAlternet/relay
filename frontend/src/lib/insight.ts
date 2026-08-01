import { AlertTriangle, CircleDot, GitPullRequest, Mail, SquareKanban } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { InsightSource, InsightUrgency } from '../types/brief';

/**
 * How a source and an urgency look, in one place.
 *
 * These used to live inside the big insight card, which meant every other
 * component that wanted the Jira icon had to import a card it did not render.
 * The feed made that untenable — a source is a *badge* now, worn by rows that
 * have nothing else in common with each other.
 */
export const SOURCE_META: Record<InsightSource, { Icon: LucideIcon; label: string }> = {
  gmail: { Icon: Mail, label: 'E-posta' },
  github: { Icon: GitPullRequest, label: 'GitHub' },
  jira: { Icon: SquareKanban, label: 'Jira' },
};

/** Colour never carries the meaning alone — icon + word always ride along. */
export const URGENCY_META: Record<
  InsightUrgency,
  { label: string; className: string; Icon: LucideIcon }
> = {
  high: { label: 'Acil', className: 'urgency--high', Icon: AlertTriangle },
  normal: { label: 'Normal', className: 'urgency--normal', Icon: CircleDot },
  low: { label: 'Düşük', className: 'urgency--low', Icon: CircleDot },
};

const KIND_LABEL: Record<string, string> = {
  bug_report: 'hata bildirimi',
  request: 'istek',
  fyi: 'bilgilendirme',
  needs_reply: 'yanıt bekliyor',
  scheduling: 'takvim',
};

export function kindLabel(kind: string): string {
  return KIND_LABEL[kind] ?? kind.replace(/_/g, ' ');
}

/**
 * Grammar, not facts. Removed from both sides before anything is compared, so
 * "ve bu önemli bir sorun" cannot pass as four new things to say.
 */
const FUNCTION_WORDS = new Set([
  'ama',
  'ancak',
  'bir',
  'bu',
  'bunu',
  'çok',
  'çünkü',
  'da',
  'daha',
  'de',
  'en',
  'fakat',
  'gibi',
  'hem',
  'her',
  'için',
  'ile',
  'ise',
  'kadar',
  'ki',
  'mı',
  'mi',
  'mu',
  'mü',
  'o',
  'olan',
  'olarak',
  'onu',
  'şu',
  'şunu',
  'tüm',
  'veya',
  'ya',
  've',
]);

/**
 * Turkish-folded content words.
 *
 * `toLocaleLowerCase('tr')` is not optional here: the default locale turns
 * `I` into `i`, so "Iptal"/"iptal" match but "IPTAL"/"ıptal" do not, and half
 * the titles on this screen carry a dotless ı. Apostrophes are glued shut
 * first — "README'ye" is one word, not "readme" plus a stray "ye".
 */
function words(text: string): string[] {
  return text
    .toLocaleLowerCase('tr')
    .replace(/['’‘`´]/g, '')
    .split(/[^\p{L}\p{N}]+/u)
    .filter((word) => word.length >= 2 && !FUNCTION_WORDS.has(word));
}

/**
 * Same word, allowing for Turkish suffixes.
 *
 * Turkish glues its grammar onto the end of a stem, so a model rewriting a
 * title produces "notunun" for "notunu" and "eklenmesi" for "ekle". Comparing
 * whole words would call those four different words and let the restatement
 * through, which is exactly how the tautology survived this long. Four shared
 * leading letters is the cheapest stemmer that catches all of them without
 * merging "hata" into "haber".
 */
function sameWord(a: string, b: string): boolean {
  if (a === b) return true;
  const shortest = Math.min(a.length, b.length);
  if (shortest < 4) return false;
  let shared = 0;
  while (shared < shortest && a[shared] === b[shared]) shared += 1;
  return shared >= 4;
}

/** How much of the title has to come back before the line counts as an echo. */
const ECHO_RATIO = 0.6;
/** New words below this is a turn of phrase; at or above it is a clause. */
const NEW_WORDS_NEEDED = 4;

/**
 * Does this sentence say anything the row's own title does not?
 *
 * The "Neden şimdi" line is the screen's answer to the only question the list
 * cannot answer by ordering itself, and most of the time it was answering with
 * the title conjugated differently: "Kurulum notunu README'ye ekle" earned
 * "Kurulum notunun README'ye eklenmesi gerekiyor." Read out loud, the row said
 * the same thing twice and the second time carried no fact — no date, no wait,
 * no person, no blocker. Deleting the line loses nothing; printing it costs a
 * line on every row and teaches the reader to skip the one place a real reason
 * would appear.
 *
 * So a line has to earn its place. It keeps it when the title is mostly *not*
 * in it, when what it adds carries a number (a clock, a date, a count, "262
 * gündür" — the facts issue #67 asks for by name), or when what it adds is long
 * enough to be a clause rather than a rephrasing.
 *
 * The cost, deliberately paid: a reason that quotes the title back and then
 * adds two or three genuinely new words — "…, Ayşe bekliyor" — is dropped with
 * the filler. Two rows of padding on every screen is the worse trade, and the
 * dropped words are still one click down in the row's own body.
 */
export function reasonEarnsItsLine(title: string, reason: string | null | undefined): boolean {
  const text = (reason ?? '').trim();
  if (!text) return false;

  const titleWords = words(title);
  const reasonWords = words(text);
  if (titleWords.length === 0 || reasonWords.length === 0) return true;

  const echoed = titleWords.filter((word) => reasonWords.some((other) => sameWord(word, other)));
  if (echoed.length / titleWords.length < ECHO_RATIO) return true;

  const fresh = reasonWords.filter((word) => !titleWords.some((other) => sameWord(word, other)));
  if (fresh.some((word) => /\p{N}/u.test(word))) return true;
  return fresh.length >= NEW_WORDS_NEEDED;
}

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

/* ------------------------------------------------------------------ */
/* The fact strip (issue #141)                                        */
/*                                                                     */
/* Three rows of the live brief carried, word for word:               */
/*                                                                     */
/*   "…deposuna ait bir pull request var ve senin PR'ın —              */
/*    incelemeye başlanabilir."                                        */
/*                                                                     */
/* Three sentences saying the same thing is a model repeating itself,  */
/* and the reader learns in one visit that the second line of a row is */
/* not worth reading. What differs between those three rows is the     */
/* repository, the number and the age — facts the payload already      */
/* carries and the screen was throwing away.                           */
/*                                                                     */
/* So the sentence is replaced by at most three machine tokens in the  */
/* mono layer, with the same grammar on every row and different values */
/* in it. Same shape, different numbers: that is what makes a column   */
/* scannable, and it is the opposite of what prose does.               */
/* ------------------------------------------------------------------ */

/** One row's tokens, keyed so the caller can put them back on their row. */
export type FactStrip = { id: string; tokens: string[] };

/** At most three, because a fourth stops being scannable and starts being a sentence. */
export const MAX_TOKENS = 3;

/**
 * Make every visible strip different from every other one.
 *
 * <p>WHY THIS IS A FUNCTION AND NOT A CONVENTION. Mono type is a truth claim. Three rows
 * reading `github · pull request · açık` are *worse* than the repeated sentence they
 * replaced: repeated prose reads as a tired model, but identical mono reads as measured
 * data, and measured data is believed. Sameness in a fixed-width column is invisible
 * until it has already destroyed the scanning it exists to enable.
 *
 * <p>Collisions are resolved by taking tokens away, never by adding them. For each group
 * of identical strips the highest-ranked row keeps everything; each lower one drops
 * trailing tokens until it differs, and a row left with nothing renders no strip at all.
 * Silence is honest. A synthesised disambiguator — an index, a dash, the word "diğer" —
 * would be a fact the payload never contained.
 *
 * <p>Every PREFIX of a kept strip is spoken for, not only the whole of it. Trimming the
 * age off `github · pull request · açık` leaves `github · pull request`, which is not a
 * different fact — it is the same claim with less of it, and two rows carrying it are as
 * indistinguishable as before. A row whose every prefix is already on screen has nothing
 * of its own to say and says nothing.
 *
 * @param rows ordered by rank; earlier rows win a collision
 */
export function dedupeStrips(rows: FactStrip[]): FactStrip[] {
  const taken = new Set<string>();
  return rows.map((row) => {
    let tokens = row.tokens.slice(0, MAX_TOKENS);
    while (tokens.length > 0 && taken.has(tokens.join(' '))) {
      tokens = tokens.slice(0, -1);
    }
    for (let end = 1; end <= tokens.length; end += 1) {
      taken.add(tokens.slice(0, end).join(' '));
    }
    return { id: row.id, tokens };
  });
}

/**
 * Which rows keep their sentence, and which give it up to the row above.
 *
 * <p>`reasonEarnsItsLine` already drops a reason that only rephrases its own title. This
 * is the other half: a reason that is a rephrasing of *another row's* reason. Neither
 * sentence is filler on its own — the tautology guard passes all three — and together
 * they are three quarters of the screen's prose saying one thing.
 *
 * <p>The scarcity is the point. Once at most one or two rows a day carry a sentence, a
 * sentence means "this one is different", which is what a reason was always supposed to
 * mean. The rows that lose it lose nothing: it is in the body, one press away.
 *
 * @param rows ordered by rank; earlier rows keep their sentence
 * @returns the reason to print on each row's line, or null to leave the line off
 */
export function rationReasons(
  rows: { id: string; title: string; why: string | null | undefined }[],
): Map<string, string | null> {
  const kept: string[][] = [];
  const out = new Map<string, string | null>();
  for (const row of rows) {
    const why = (row.why ?? '').trim();
    if (!why || !reasonEarnsItsLine(row.title, why)) {
      out.set(row.id, null);
      continue;
    }
    const mine = words(why);
    const echoesOne = kept.some((earlier) => overlap(mine, earlier) >= ECHO_RATIO);
    out.set(row.id, echoesOne ? null : why);
    if (!echoesOne) kept.push(mine);
  }
  return out;
}

/** How much of `a` is already in `b`, allowing for Turkish suffixes. */
function overlap(a: string[], b: string[]): number {
  if (a.length === 0) return 0;
  const shared = a.filter((word) => b.some((other) => sameWord(word, other)));
  return shared.length / a.length;
}

/**
 * The item's own name in its own system: `KAN-42`, `acme/pay#128`, or the person.
 *
 * <p>Parsed off the id, which is where the backend already puts it — `jira:KAN-42`,
 * `github-pr:acme/pay#128`, `gmail:18f2…`. Gmail's id is an opaque handle and never goes
 * on screen, so a mail is named by whoever sent it.
 */
export function itemHandle(card: {
  id: string;
  source: InsightSource;
  from?: string;
}): string | null {
  const colon = card.id.indexOf(':');
  const rest = colon >= 0 ? card.id.slice(colon + 1) : card.id;
  if (card.source === 'gmail') return (card.from ?? '').trim() || null;
  return rest.trim() || (card.from ?? '').trim() || null;
}

/**
 * One row's machine facts: what it is, where it stands, how old it is.
 *
 * <p>Three tokens at most, the same three kinds on every row, so the column reads down
 * rather than across. Nothing is invented to reach three: a row with one fact prints one
 * fact. `ACİL` is not in here — it is drawn separately, in the danger colour, because it
 * is the one token that is a warning rather than a description.
 *
 * @param age the section item's own `meta` string, joined by the caller; the server wrote
 *            it and it is never reformatted here
 */
export function factStrip(
  card: { id: string; source: InsightSource; from?: string; subtitle?: string; kind: string },
  age: string | null | undefined,
): string[] {
  const tokens: string[] = [];
  const handle = itemHandle(card);
  if (handle) tokens.push(handle);

  const state = (card.subtitle ?? '').trim();
  // Gmail's subtitle is the sender, which is already the handle. Printing it twice is
  // the sameness this strip exists to remove, so the mail says what kind of mail it is.
  const secondary = state && state !== handle ? state : kindLabel(card.kind);
  if (secondary && secondary !== handle) tokens.push(secondary);

  const when = (age ?? '').trim();
  if (when) tokens.push(when);
  return tokens.slice(0, MAX_TOKENS);
}

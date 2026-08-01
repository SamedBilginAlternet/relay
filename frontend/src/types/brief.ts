/**
 * Bugün (daily brief) contract — mirrors docs/BRIEF.md §2/§3/§5.
 * `GET /api/brief` returns PARTIAL SUCCESS: every section carries its own
 * status, so one missing integration never blanks the screen.
 */

export type BriefSectionStatus = 'ok' | 'unavailable' | 'error';

/** One row inside a compact section (inbox / work / code / calendar). */
export type BriefItem = {
  id: string;
  title: string;
  /** Second line — sender, issue key, repo, time… */
  subtitle?: string | null;
  /** Right-aligned meta — time, state, count. */
  meta?: string | null;
  /** Optional deep link out to the source system. */
  url?: string | null;
  /** Colour hint; always paired with text, never colour alone. */
  tone?: 'default' | 'warn' | 'danger' | 'success';
};

export type BriefSection = {
  status: BriefSectionStatus;
  /** Why it is unavailable/errored — shown verbatim to the user. */
  reason?: string;
  items: BriefItem[];
};

export type InsightSource = 'gmail' | 'github' | 'jira';
export type InsightUrgency = 'high' | 'normal' | 'low';

/** One LLM-proposed action. Suggestion ≠ execution: nothing runs unclicked. */
export type SuggestedAction = {
  tool: string;
  label: string;
  params: Record<string, unknown>;
};

export type InsightCard = {
  id: string;
  source: InsightSource;
  title: string;
  from?: string;
  /** bug_report | request | fyi | needs_reply | scheduling | … */
  kind: string;
  urgency: InsightUrgency;
  summary: string;
  suggestedActions: SuggestedAction[];
};

/**
 * The day in a sentence, written after every section came back. Optional on purpose:
 * when the model is unavailable the backend omits it rather than shipping filler.
 */
export type BriefDigest = {
  summary: string;
  /** Ordered, with the reason each item earned its place. */
  priorities: { itemId: string; why: string }[];
  advice?: string;
};

/** The countable half of the day, from `today.counts`. */
export type BriefTodayCounts = {
  inbox: number;
  /** Mail from a person — a mailing list is not work. */
  inboxPersonal: number;
  inboxBulk: number;
  work: number;
  code: number;
  calendar: number;
  /** Insight cards that came back `high` urgency. */
  urgent: number;
};

/** Where a named item came from. Wider than `InsightSource`: a meeting can be named. */
export type BriefHighlightSource = 'gmail' | 'jira' | 'github' | 'calendar';

/**
 * One item the day summary names instead of only counting.
 *
 * "1 mail bir kişiden geldi" says something is waiting without saying what, so
 * the reader still has to go and look. Mailings are never named — they are not
 * work — which is also why this list can be empty on a busy-looking inbox.
 */
export type BriefHighlight = {
  /** The brief item this points at, so the screen can go to that row. */
  itemId: string;
  source: BriefHighlightSource;
  /** What it is, in one phrase — a subject, an issue title, a meeting name. */
  label: string;
  /** Who it is from / what state it is in. May be empty. */
  detail: string;
};

/**
 * The day in counted facts: arithmetic over what the providers already returned,
 * with no model involved.
 *
 * This is the opposite half of {@link BriefDigest}. The digest says what the day
 * *means* and is the first thing to vanish when the token budget is spent; this
 * one is always there on a current backend. Still optional in the type, because
 * a backend from before it existed simply does not send the field.
 */
export type BriefToday = {
  /** One sentence. On an empty day it says so instead of dressing up zeros. */
  headline: string;
  /** Short counted phrases; empty on a quiet day. */
  lines: string[];
  /** A few of the day's items by name. Empty when only mailings arrived. */
  highlights: BriefHighlight[];
  counts: BriefTodayCounts;
};

export type Brief = {
  date: string;
  today?: BriefToday | null;
  digest?: BriefDigest | null;
  priority: InsightCard[];
  inbox: BriefSection;
  work: BriefSection;
  code: BriefSection;
  calendar: BriefSection;
};

export type BriefSectionKey = 'inbox' | 'work' | 'code' | 'calendar';

export const EMPTY_SECTION: BriefSection = { status: 'ok', items: [] };

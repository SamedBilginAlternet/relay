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

export type Brief = {
  date: string;
  priority: InsightCard[];
  inbox: BriefSection;
  work: BriefSection;
  code: BriefSection;
  calendar: BriefSection;
};

export type BriefSectionKey = 'inbox' | 'work' | 'code' | 'calendar';

export const EMPTY_SECTION: BriefSection = { status: 'ok', items: [] };

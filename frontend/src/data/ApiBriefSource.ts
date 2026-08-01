import type {
  Brief,
  BriefDigest,
  BriefItem,
  BriefSection,
  BriefSectionStatus,
  BriefHighlight,
  BriefHighlightSource,
  BriefToday,
  InsightCard,
  InsightSource,
  InsightUrgency,
  SuggestedAction,
} from '../types/brief';
import { ApiError } from './ApiRunSource';
import type { BriefSource } from './BriefSource';

const SOURCES: InsightSource[] = ['gmail', 'github', 'jira'];
const URGENCIES: InsightUrgency[] = ['high', 'normal', 'low'];
const STATUSES: BriefSectionStatus[] = ['ok', 'unavailable', 'error'];
const HIGHLIGHT_SOURCES: BriefHighlightSource[] = ['gmail', 'jira', 'github', 'calendar'];

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function normalizeItem(raw: unknown, index: number): BriefItem {
  const r = asRecord(raw);
  return {
    id: asString(r.id) || `item-${index}`,
    title: asString(r.title) || asString(r.summary) || '(başlıksız)',
    subtitle: asString(r.subtitle) || null,
    meta: asString(r.meta) || null,
    url: asString(r.url) || null,
    tone: (['default', 'warn', 'danger', 'success'] as const).includes(
      r.tone as 'default',
    )
      ? (r.tone as BriefItem['tone'])
      : 'default',
  };
}

/**
 * A missing/garbled section must never crash the screen — it degrades to
 * `error` with an explicit reason so the user still sees *why*.
 */
function normalizeSection(raw: unknown, label: string): BriefSection {
  if (raw == null) {
    return { status: 'error', reason: `${label} bölümü yanıtta yok.`, items: [] };
  }
  const r = asRecord(raw);
  const status = STATUSES.includes(r.status as BriefSectionStatus)
    ? (r.status as BriefSectionStatus)
    : 'error';
  const items = Array.isArray(r.items) ? r.items.map(normalizeItem) : [];
  const reason = asString(r.reason) || undefined;
  return {
    status,
    reason:
      reason ?? (status === 'error' ? `${label} bölümü okunamadı.` : undefined),
    items,
  };
}

/** The three the policy engine knows. Anything else is not a claim we can make. */
const RISKS = ['read', 'write', 'destructive'] as const;

function normalizeAction(raw: unknown, index: number): SuggestedAction {
  const r = asRecord(raw);
  const risk = asString(r.risk);
  return {
    tool: asString(r.tool) || `tool-${index}`,
    label: asString(r.label) || asString(r.tool) || 'Eylem',
    params: asRecord(r.params),
    // Carried, not dropped. This normaliser rebuilds each action field by field,
    // so a field the server started sending is invisible until it is named here
    // — which is how the card's "you will be asked before this is sent" line
    // silently never appeared, on a payload that had said `write` all along.
    risk: (RISKS as readonly string[]).includes(risk)
      ? (risk as SuggestedAction['risk'])
      : null,
  };
}

function normalizeCard(raw: unknown, index: number): InsightCard {
  const r = asRecord(raw);
  const source = SOURCES.includes(r.source as InsightSource)
    ? (r.source as InsightSource)
    : 'gmail';
  const urgency = URGENCIES.includes(r.urgency as InsightUrgency)
    ? (r.urgency as InsightUrgency)
    : 'normal';
  return {
    id: asString(r.id) || `card-${index}`,
    source,
    title: asString(r.title) || '(başlıksız)',
    from: asString(r.from) || undefined,
    kind: asString(r.kind) || 'fyi',
    urgency,
    summary: asString(r.summary),
    suggestedActions: Array.isArray(r.suggestedActions)
      ? r.suggestedActions.map(normalizeAction)
      : [],
    url: asString(r.url) || undefined,
  };
}

/** Absent, empty or garbled all mean the same thing: show no summary at all. */
function normalizeDigest(raw: unknown): BriefDigest | null {
  const r = asRecord(raw);
  const summary = asString(r.summary).trim();
  if (!summary) return null;
  const priorities = Array.isArray(r.priorities)
    ? r.priorities
        .map((entry) => {
          const p = asRecord(entry);
          return { itemId: asString(p.itemId), why: asString(p.why) };
        })
        .filter((p) => p.itemId && p.why)
    : [];
  const advice = asString(r.advice).trim();
  return { summary, priorities, advice: advice || undefined };
}

function asCount(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0;
}

/**
 * Absent means an older backend that never counted the day — the screen then
 * renders exactly what it rendered before. A headline is the one part that
 * cannot be reconstructed, so without it there is nothing to show.
 */
/** A named item with no id could not be pointed at, and one with no label has
 *  nothing to say — either way it is dropped rather than rendered half-blank. */
function normalizeHighlight(raw: unknown): BriefHighlight | null {
  const r = asRecord(raw);
  const itemId = asString(r.itemId).trim();
  const label = asString(r.label).trim();
  if (!itemId || !label) return null;
  const source = asString(r.source) as BriefHighlightSource;
  return {
    itemId,
    label,
    source: HIGHLIGHT_SOURCES.includes(source) ? source : 'gmail',
    detail: asString(r.detail).trim(),
  };
}

function normalizeToday(raw: unknown): BriefToday | null {
  const r = asRecord(raw);
  const headline = asString(r.headline).trim();
  if (!headline) return null;
  const c = asRecord(r.counts);
  return {
    headline,
    lines: Array.isArray(r.lines)
      ? r.lines.map((line) => asString(line).trim()).filter(Boolean)
      : [],
    highlights: Array.isArray(r.highlights)
      ? r.highlights
          .map(normalizeHighlight)
          .filter((h): h is BriefHighlight => h != null)
      : [],
    counts: {
      inbox: asCount(c.inbox),
      inboxPersonal: asCount(c.inboxPersonal),
      inboxBulk: asCount(c.inboxBulk),
      work: asCount(c.work),
      code: asCount(c.code),
      calendar: asCount(c.calendar),
      urgent: asCount(c.urgent),
    },
  };
}

export function normalizeBrief(raw: unknown): Brief {
  const r = asRecord(raw);
  return {
    date: asString(r.date) || new Date().toISOString(),
    today: normalizeToday(r.today),
    digest: normalizeDigest(r.digest),
    priority: Array.isArray(r.priority) ? r.priority.map(normalizeCard) : [],
    inbox: normalizeSection(r.inbox, 'Gelen kutusu'),
    work: normalizeSection(r.work, 'Üstümdeki işler'),
    code: normalizeSection(r.code, 'Kod'),
    calendar: normalizeSection(r.calendar, 'Takvim'),
  };
}

/**
 * What the card was about, in the fields the run's goal is built from.
 *
 * The card is already on the screen — the row the user pressed carries the title,
 * the sender and the sentence explaining why it is there — and none of it used to
 * leave the browser. So a flow started from "Cevap yaz" knew only "Cevap yaz", and
 * the draft it wrote was titled after the button.
 *
 * It is a headline, not the item: the summary is one line the backend clips again,
 * and the mail's body is never in it. `itemId` is the same id the card is keyed by,
 * which is how the backend names a record (`jira:KAN-42`) and finds the message a
 * reply has to read (`gmail:18f2…`).
 */
function suggestionContext(card: InsightCard) {
  return {
    itemId: card.id,
    source: card.source,
    title: card.title,
    from: card.from ?? '',
    summary: card.summary,
    url: card.url ?? '',
  };
}

/** Real backend: REST only — the brief is a snapshot, not a stream. */
export class ApiBriefSource implements BriefSource {
  readonly kind = 'api' as const;
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await fetch(`${this.baseUrl}${path}`, {
        // /api/brief is behind the session cookie like everything else.
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
        ...init,
      });
    } catch {
      throw new ApiError('Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.', 0);
    }
    if (!res.ok) {
      let detail = '';
      try {
        const body = (await res.json()) as { message?: string; error?: string };
        detail = body.message || body.error || '';
      } catch {
        /* body was not json — ignore */
      }
      const fallback =
        res.status >= 500
          ? `Brifing alınamadı (HTTP ${res.status}). Backend ayakta mı?`
          : res.status === 404
            ? 'Brifing ucu bulunamadı (HTTP 404). Backend bu sürümü henüz sunmuyor.'
            : `İstek başarısız (HTTP ${res.status})`;
      throw new ApiError(detail || fallback, res.status);
    }
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }

  async getBrief(): Promise<Brief> {
    return normalizeBrief(await this.request<unknown>('/brief'));
  }

  async refreshBrief(): Promise<Brief> {
    return normalizeBrief(await this.request<unknown>('/brief/refresh', { method: 'POST', body: '{}' }));
  }

  async startFromSuggestion(
    card: InsightCard,
    action: SuggestedAction,
  ): Promise<{ runId: string }> {
    const body = await this.request<{ runId?: string; id?: string }>('/runs/from-suggestion', {
      method: 'POST',
      body: JSON.stringify({
        cardId: card.id,
        tool: action.tool,
        label: action.label,
        params: action.params,
        context: suggestionContext(card),
      }),
    });
    const runId = body?.runId ?? body?.id;
    if (!runId) throw new ApiError('Sunucu akış kimliği döndürmedi.', 500);
    return { runId };
  }
}

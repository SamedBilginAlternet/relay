import type {
  Brief,
  BriefItem,
  BriefSection,
  BriefSectionStatus,
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

function normalizeAction(raw: unknown, index: number): SuggestedAction {
  const r = asRecord(raw);
  return {
    tool: asString(r.tool) || `tool-${index}`,
    label: asString(r.label) || asString(r.tool) || 'Eylem',
    params: asRecord(r.params),
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
  };
}

export function normalizeBrief(raw: unknown): Brief {
  const r = asRecord(raw);
  return {
    date: asString(r.date) || new Date().toISOString(),
    priority: Array.isArray(r.priority) ? r.priority.map(normalizeCard) : [],
    inbox: normalizeSection(r.inbox, 'Gelen kutusu'),
    work: normalizeSection(r.work, 'Üstümdeki işler'),
    code: normalizeSection(r.code, 'Kod'),
    calendar: normalizeSection(r.calendar, 'Takvim'),
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
    cardId: string,
    action: SuggestedAction,
  ): Promise<{ runId: string }> {
    const body = await this.request<{ runId?: string; id?: string }>('/runs/from-suggestion', {
      method: 'POST',
      body: JSON.stringify({
        cardId,
        tool: action.tool,
        label: action.label,
        params: action.params,
      }),
    });
    const runId = body?.runId ?? body?.id;
    if (!runId) throw new ApiError('Sunucu akış kimliği döndürmedi.', 500);
    return { runId };
  }
}

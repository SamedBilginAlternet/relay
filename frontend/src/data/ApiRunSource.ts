import type {
  Connection,
  ConnectionTestResult,
  Health,
  Provider,
  Run,
  RunEvent,
  RunSummary,
} from '../types/api';
import { RUN_EVENT_TYPES } from '../types/api';
import type { RunSource, RunStreamHandlers, Unsubscribe } from './RunSource';

export class ApiError extends Error {
  readonly status: number;
  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 15000];

/** Real backend: REST + EventSource. */
export class ApiRunSource implements RunSource {
  readonly kind = 'api' as const;
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  private url(path: string): string {
    return `${this.baseUrl}${path}`;
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await fetch(this.url(path), {
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
          ? `Sunucu yanıt vermedi (HTTP ${res.status}). Backend ayakta mı? VITE_API_BASE_URL: ${this.baseUrl}`
          : res.status === 404
            ? 'Kayıt bulunamadı (HTTP 404).'
            : `İstek başarısız (HTTP ${res.status})`;
      throw new ApiError(detail || fallback, res.status);
    }
    if (res.status === 204) return undefined as T;
    const text = await res.text();
    if (!text) return undefined as T;
    return JSON.parse(text) as T;
  }

  health(): Promise<Health> {
    return this.request<Health>('/health');
  }

  createRun(goal: string, budgetUsd?: number | null): Promise<{ runId: string }> {
    return this.request<{ runId: string }>('/runs', {
      method: 'POST',
      body: JSON.stringify(budgetUsd == null ? { goal } : { goal, budgetUsd }),
    });
  }

  getRun(runId: string): Promise<Run> {
    return this.request<Run>(`/runs/${encodeURIComponent(runId)}`);
  }

  async listRuns(): Promise<RunSummary[]> {
    // The history endpoint is paginated; accept array / {items} / Spring {content}.
    const body = await this.request<unknown>('/runs');
    const rows = Array.isArray(body)
      ? body
      : ((body as { items?: unknown[]; content?: unknown[] } | null)?.items ??
        (body as { content?: unknown[] } | null)?.content ??
        []);
    return (rows as Partial<RunSummary & Run>[]).map((r) => ({
      id: String(r.id ?? ''),
      goal: r.goal ?? '',
      status: r.status ?? 'done',
      costTokens: r.costTokens ?? 0,
      costUsd: r.costUsd ?? 0,
      budgetUsd: r.budgetUsd ?? null,
      createdAt: r.createdAt ?? new Date().toISOString(),
      finishedAt: r.finishedAt ?? null,
      stepCount: r.stepCount ?? (Array.isArray(r.steps) ? r.steps.length : 0),
    }));
  }

  rerun(runId: string): Promise<{ runId: string }> {
    return this.request<{ runId: string }>(`/runs/${encodeURIComponent(runId)}/rerun`, {
      method: 'POST',
    });
  }

  async approveStep(runId: string, stepId: string): Promise<void> {
    await this.request<void>(
      `/runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/approve`,
      { method: 'POST', body: '{}' },
    );
  }

  async rejectStep(runId: string, stepId: string, reason: string): Promise<void> {
    await this.request<void>(
      `/runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/reject`,
      { method: 'POST', body: JSON.stringify({ reason }) },
    );
  }

  getConnections(): Promise<Connection[]> {
    return this.request<Connection[]>('/connections');
  }

  saveConnection(provider: Provider, config: Record<string, string>): Promise<Connection> {
    return this.request<Connection>('/connections', {
      method: 'PUT',
      body: JSON.stringify({ provider, config }),
    });
  }

  testConnection(provider: Provider): Promise<ConnectionTestResult> {
    return this.request<ConnectionTestResult>(
      `/connections/${encodeURIComponent(provider)}/test`,
      { method: 'POST', body: '{}' },
    );
  }

  streamRun(runId: string, handlers: RunStreamHandlers): Unsubscribe {
    let source: EventSource | null = null;
    let closed = false;
    let attempt = 0;
    let hadDrop = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    const resync = () => {
      this.getRun(runId)
        .then((run) => handlers.onResync?.(run))
        .catch(() => {
          /* the stream is live again; a failed gap-fill is not fatal */
        });
    };

    const connect = () => {
      if (closed) return;
      handlers.onStatus(hadDrop ? 'reconnecting' : 'connecting');
      const es = new EventSource(this.url(`/runs/${encodeURIComponent(runId)}/stream`));
      source = es;

      es.onopen = () => {
        attempt = 0;
        handlers.onStatus('live');
        // SSE has no replay — refetch the full run to fill whatever we missed.
        if (hadDrop) resync();
      };

      const dispatch = (type: RunEvent['type']) => (ev: MessageEvent<string>) => {
        try {
          const payload = ev.data ? (JSON.parse(ev.data) as Record<string, unknown>) : {};
          handlers.onEvent({ ...payload, type } as RunEvent);
        } catch {
          /* malformed frame — drop it rather than crashing the stream */
        }
      };

      for (const type of RUN_EVENT_TYPES) {
        es.addEventListener(type, dispatch(type) as EventListener);
      }
      // Fallback for servers that send unnamed events with `type` in the body.
      es.onmessage = (ev: MessageEvent<string>) => {
        try {
          const payload = JSON.parse(ev.data) as RunEvent;
          if (payload && typeof payload.type === 'string') handlers.onEvent(payload);
        } catch {
          /* ignore */
        }
      };

      es.onerror = () => {
        es.close();
        source = null;
        if (closed) return;
        hadDrop = true;
        handlers.onStatus('reconnecting');
        const delay = RECONNECT_DELAYS[Math.min(attempt, RECONNECT_DELAYS.length - 1)] ?? 15000;
        attempt += 1;
        timer = setTimeout(connect, delay);
      };
    };

    connect();

    return () => {
      closed = true;
      if (timer) clearTimeout(timer);
      source?.close();
      handlers.onStatus('closed');
    };
  }
}

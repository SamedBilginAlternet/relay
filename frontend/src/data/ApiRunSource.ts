import type {
  Connection,
  ConnectionTestResult,
  GoogleStatus,
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
  /**
   * Field name → why the server refused it. Only the approval gate fills this in today:
   * a rejected parameter edit has to be shown next to the box that caused it.
   */
  readonly fields: Record<string, string>;

  constructor(message: string, status: number, fields: Record<string, string> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fields = fields;
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
        // Every /api call is behind the session cookie now; `include` keeps it
        // attached even when the SPA and the API sit on different origins.
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
        ...init,
      });
    } catch {
      throw new ApiError('Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.', 0);
    }
    if (!res.ok) {
      let detail = '';
      let fields: Record<string, string> = {};
      try {
        const body = (await res.json()) as {
          message?: string;
          error?: string;
          fields?: Record<string, string>;
        };
        detail = body.message || body.error || '';
        if (body.fields && typeof body.fields === 'object') fields = body.fields;
      } catch {
        /* body was not json — ignore */
      }
      const fallback =
        res.status >= 500
          ? `Sunucu yanıt vermedi (HTTP ${res.status}). Backend ayakta mı? VITE_API_BASE_URL: ${this.baseUrl}`
          : res.status === 404
            ? 'Kayıt bulunamadı (HTTP 404).'
            : `İstek başarısız (HTTP ${res.status})`;
      throw new ApiError(detail || fallback, res.status, fields);
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

  async listRuns(options?: { status?: RunSummary['status']; size?: number }): Promise<RunSummary[]> {
    const query = new URLSearchParams();
    if (options?.status) query.set('status', options.status);
    if (options?.size) query.set('size', String(options.size));
    const path = query.toString() ? `/runs?${query}` : '/runs';
    // The history endpoint is paginated; accept array / {items} / Spring {content}.
    const body = await this.request<unknown>(path);
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
      // A number or nothing. `?? 0` here would turn "this server does not send progress"
      // into "nothing has run yet", and the row would report no progress on a flow that
      // is nearly finished — a wrong answer that looks like a measured one.
      doneStepCount: typeof r.doneStepCount === 'number' ? r.doneStepCount : null,
    }));
  }

  cancelRun(runId: string): Promise<Run> {
    return this.request<Run>(`/runs/${encodeURIComponent(runId)}/cancel`, {
      method: 'POST',
      body: '{}',
    });
  }

  rerun(runId: string): Promise<{ runId: string }> {
    return this.request<{ runId: string }>(`/runs/${encodeURIComponent(runId)}/rerun`, {
      method: 'POST',
    });
  }

  async approveStep(
    runId: string,
    stepId: string,
    params?: Record<string, unknown>,
  ): Promise<void> {
    await this.request<void>(
      `/runs/${encodeURIComponent(runId)}/steps/${encodeURIComponent(stepId)}/approve`,
      { method: 'POST', body: params ? JSON.stringify({ params }) : '{}' },
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

  async getGoogleStatus(): Promise<GoogleStatus> {
    // 503 here is a normal answer, not a fault: it means the server was started without
    // GOOGLE_CLIENT_ID/SECRET. The screen shows what to set instead of an error banner.
    try {
      return await this.request<GoogleStatus>('/oauth/google/status');
    } catch {
      return {
        configured: false,
        connected: false,
        scopes: '',
        redirectUri: '',
        startUrl: '/api/oauth/google/start',
      };
    }
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
      // EventSource cannot send headers — the session HAS to travel as a cookie, and
      // cross-origin it only does so with withCredentials.
      const es = new EventSource(this.url(`/runs/${encodeURIComponent(runId)}/stream`), {
        withCredentials: true,
      });
      source = es;

      es.onopen = () => {
        attempt = 0;
        handlers.onStatus('live');
        // Every open, not only the ones after a drop. The server's replay comes out of a
        // buffer that lives in the API process, so a deploy while a run waits on a person
        // leaves a stream with nothing to say — and the screen sat there with an approval
        // badge over an empty timeline, twice, on ordinary restarts. The run itself is on
        // disk; fetching it is what guarantees the screen is never blank. It also closes
        // the hole a long outage leaves, since only the last 400 frames are replayed. The
        // reducer is what makes the overlap harmless.
        resync();
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
        const retry = () => {
          if (!closed) timer = setTimeout(connect, delay);
        };
        // A stream the server hung up on because the session was signed out looks exactly
        // like a dropped line from in here, and EventSource answers both the same way:
        // reconnect, for ever. So ask once before retrying. A 401 ends the loop and leaves
        // the app's own session guard to take the person to the sign-in screen, instead of
        // a browser knocking every fifteen seconds on a door that is locked.
        this.getRun(runId)
          .then(() => retry())
          .catch((error: unknown) => {
            if (error instanceof ApiError && error.status === 401) {
              closed = true;
              handlers.onStatus('closed');
              return;
            }
            retry();
          });
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

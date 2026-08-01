import type {
  Connection,
  GoogleStatus,
  ConnectionTestResult,
  Health,
  Provider,
  Run,
  RunEvent,
  RunSummary,
} from '../types/api';
import { applyEvent } from './applyEvent';
import type { RunSource, RunStreamHandlers, Unsubscribe } from './RunSource';
import { AGENTS, DEMO_SCRIPT, mockId, seededHistory, stepsFromScript } from './mockScript';
import type { ScriptStep } from './mockScript';

type Decision =
  | { kind: 'approve'; params?: Record<string, unknown> }
  | { kind: 'reject'; reason: string };

type Playback = {
  run: Run;
  started: boolean;
  cancelled: boolean;
  pending: Map<string, (d: Decision) => void>;
  subscribers: Set<RunStreamHandlers>;
};

const TERMINAL = new Set(['done', 'failed', 'cancelled']);
const TERMINAL_STEP = new Set(['done', 'failed', 'rejected']);

const MASK = (value: string): string => {
  const v = value.trim();
  if (!v) return '';
  if (v.length <= 6) return '••••';
  return `${v.slice(0, 4)}••••${v.slice(-4)}`;
};

/**
 * Fully scripted, backend-free source.
 * Plays a realistic run on a timer — including two approval gates and
 * agent-to-agent traffic — so the whole UI can be built and demoed offline.
 */
export class MockRunSource implements RunSource {
  readonly kind = 'mock' as const;

  private readonly runs = new Map<string, Playback>();
  private readonly history: Run[] = seededHistory();
  private readonly connections: Record<Provider, { config: Record<string, string>; updatedAt: string | null }> = {
    jira: { config: {}, updatedAt: null },
    slack: { config: {}, updatedAt: null },
    github: { config: {}, updatedAt: null },
    google: { config: {}, updatedAt: null },
  };

  async health(): Promise<Health> {
    await delay(120);
    return { status: 'up', version: '0.1.0-mock', llm: 'stub (mock veri kaynağı)' };
  }

  async createRun(goal: string, budgetUsd: number | null = 0.5): Promise<{ runId: string }> {
    await delay(250);
    const id = mockId('run');
    const run: Run = {
      id,
      goal,
      status: 'planning',
      costTokens: 0,
      costUsd: 0,
      budgetUsd,
      steps: [],
      messages: [],
      createdAt: new Date().toISOString(),
      finishedAt: null,
    };
    this.runs.set(id, {
      run,
      started: false,
      cancelled: false,
      pending: new Map(),
      subscribers: new Set(),
    });
    return { runId: id };
  }

  async getRun(runId: string): Promise<Run> {
    await delay(120);
    const live = this.runs.get(runId);
    if (live) return clone(live.run);
    const past = this.history.find((r) => r.id === runId);
    if (past) return clone(past);
    throw new Error(`Akış bulunamadı: ${runId}`);
  }

  async listRuns(): Promise<RunSummary[]> {
    await delay(180);
    const live = [...this.runs.values()].map((p) => p.run);
    return [...live, ...this.history]
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((r) => ({
        id: r.id,
        goal: r.goal,
        status: r.status,
        costTokens: r.costTokens,
        costUsd: r.costUsd,
        budgetUsd: r.budgetUsd,
        createdAt: r.createdAt,
        finishedAt: r.finishedAt,
        stepCount: r.steps.length,
      }));
  }

  /**
   * The scripted twin of `POST /api/runs/{id}/cancel`: the playback stops, everything
   * unfinished is written off as rejected and the run closes as `cancelled`.
   */
  async cancelRun(runId: string): Promise<Run> {
    await delay(150);
    const playback = this.runs.get(runId);
    if (!playback) throw new Error(`Çalışan akış bulunamadı: ${runId}`);
    if (TERMINAL.has(playback.run.status)) throw new Error('Akış zaten bitti.');

    playback.cancelled = true;
    // A gate waiting on a human never settles on its own — release it, the playback
    // checks `cancelled` the moment it wakes up.
    for (const resolve of [...playback.pending.values()]) resolve({ kind: 'approve' });
    playback.pending.clear();

    this.emit(playback, {
      type: 'agent.message',
      from: AGENTS.user,
      to: AGENTS.coordinator,
      content: 'Akış iptal edildi — kalan adımlar çalıştırılmadı.',
    });
    for (const step of playback.run.steps) {
      if (TERMINAL_STEP.has(step.status)) continue;
      this.emit(playback, {
        type: 'step.finished',
        stepId: step.id,
        status: 'rejected',
        decision: 'rejected',
        rejectReason: 'akış iptal edildi',
        result: null,
        error: null,
        tokens: 0,
        costUsd: 0,
      });
    }
    this.emit(playback, { type: 'run.finished', status: 'cancelled' });
    return clone(playback.run);
  }

  async rerun(runId: string): Promise<{ runId: string }> {
    const source = this.runs.get(runId)?.run ?? this.history.find((r) => r.id === runId);
    return this.createRun(source?.goal ?? 'Aynı akışı tekrar çalıştır');
  }

  streamRun(runId: string, handlers: RunStreamHandlers): Unsubscribe {
    const playback = this.runs.get(runId);
    if (!playback) {
      // Finished/seeded run: nothing to stream, but never leave the UI hanging.
      handlers.onStatus('connecting');
      const t = setTimeout(() => handlers.onStatus('closed'), 150);
      return () => clearTimeout(t);
    }

    playback.subscribers.add(handlers);
    handlers.onStatus('connecting');
    const openTimer = setTimeout(() => {
      handlers.onStatus('live');
      if (!playback.started) {
        playback.started = true;
        void this.play(playback);
      }
    }, 300);

    return () => {
      clearTimeout(openTimer);
      playback.subscribers.delete(handlers);
      handlers.onStatus('closed');
    };
  }

  async approveStep(
    runId: string,
    stepId: string,
    params?: Record<string, unknown>,
  ): Promise<void> {
    await delay(150);
    this.runs.get(runId)?.pending.get(stepId)?.({ kind: 'approve', params });
  }

  async rejectStep(runId: string, stepId: string, reason: string): Promise<void> {
    await delay(150);
    this.runs.get(runId)?.pending.get(stepId)?.({ kind: 'reject', reason });
  }

  async getConnections(): Promise<Connection[]> {
    await delay(160);
    return (['jira', 'slack', 'github', 'google'] as Provider[]).map((provider) =>
      this.maskConnection(provider),
    );
  }

  async saveConnection(provider: Provider, config: Record<string, string>): Promise<Connection> {
    await delay(400);
    const merged = { ...this.connections[provider].config };
    for (const [k, v] of Object.entries(config)) {
      if (v.trim()) merged[k] = v.trim();
    }
    this.connections[provider] = { config: merged, updatedAt: new Date().toISOString() };
    return this.maskConnection(provider);
  }

  async testConnection(provider: Provider): Promise<ConnectionTestResult> {
    await delay(900);
    const cfg = this.connections[provider].config;
    const checkedAt = new Date().toISOString();
    if (provider === 'jira') {
      if (!cfg.baseUrl || !cfg.email || !cfg.apiToken) {
        return { ok: false, message: 'Jira için site adresi, e-posta ve API token gerekli.', checkedAt };
      }
      return { ok: true, message: `Bağlandı — ${cfg.email} · 3 proje görünür (mock).`, checkedAt };
    }
    if (provider === 'github') {
      if (!cfg.token) {
        return { ok: false, message: 'GitHub için fine-grained token (github_pat_…) gerekli.', checkedAt };
      }
      return {
        ok: true,
        message: `Bağlandı — ${cfg.login || '@me'} · 2 PR, 1 issue görünür (mock).`,
        checkedAt,
      };
    }
    if (provider === 'google') {
      return {
        ok: false,
        message: 'Google mock veri kaynağında bağlanamaz — canlı API gerekiyor.',
        checkedAt,
      };
    }
    if (!cfg.botToken) {
      return { ok: false, message: 'Slack bot token (xoxb-…) girilmemiş.', checkedAt };
    }
    if (!cfg.botToken.startsWith('xoxb-')) {
      return { ok: false, message: 'Token `xoxb-` ile başlamalı (bot token).', checkedAt };
    }
    return { ok: true, message: 'Bağlandı — relay-bot · 2 kanal görünür (mock).', checkedAt };
  }

  async getGoogleStatus(): Promise<GoogleStatus> {
    await delay(140);
    return {
      configured: false,
      connected: false,
      scopes: 'gmail.readonly calendar.readonly',
      redirectUri: '',
      startUrl: '/api/oauth/google/start',
    };
  }

  /* ---------------------------------------------------------------- */

  private maskConnection(provider: Provider): Connection {
    const entry = this.connections[provider];
    const secretKeys = new Set(['apiToken', 'botToken', 'token', 'refreshToken']);
    const config: Record<string, string> = {};
    for (const [k, v] of Object.entries(entry.config)) {
      config[k] = secretKeys.has(k) ? MASK(v) : v;
    }
    return {
      provider,
      configured: Object.keys(entry.config).length > 0,
      config,
      updatedAt: entry.updatedAt,
    };
  }

  private emit(p: Playback, event: RunEvent): void {
    p.run = applyEvent(p.run, event);
    for (const sub of [...p.subscribers]) sub.onEvent(event);
  }

  private async wait(p: Playback, ms: number): Promise<void> {
    await delay(ms);
    if (p.cancelled) throw new PlaybackCancelled();
  }

  private waitForDecision(p: Playback, stepId: string): Promise<Decision> {
    return new Promise<Decision>((resolve) => {
      p.pending.set(stepId, (d) => {
        p.pending.delete(stepId);
        resolve(d);
      });
    });
  }

  private async play(p: Playback): Promise<void> {
    try {
      const script = DEMO_SCRIPT;
      const goal = p.run.goal;

      await this.wait(p, 600);
      this.emit(p, {
        type: 'agent.message',
        from: AGENTS.planner,
        to: AGENTS.user,
        content: `Hedefi okudum: “${truncate(goal, 90)}”. Kadroyu kurdum — Koordinatör, Jira uzmanı, Slack uzmanı ve Doğrulayıcı. ${script.length} adımlık bir akış çıkarıyorum.`,
      });

      await this.wait(p, 900);
      this.emit(p, { type: 'run.planned', steps: stepsFromScript(script) });

      let tokens = 0;
      let costUsd = 0;
      const rejected: string[] = [];

      for (let i = 0; i < script.length; i += 1) {
        const def = script[i] as ScriptStep;
        const step = p.run.steps[i];
        if (!step) break;
        const stepId = step.id;

        await this.wait(p, 450);
        for (const msg of def.before ?? []) {
          this.emit(p, { type: 'agent.message', from: msg.from, to: msg.to, content: msg.content, stepId });
          await this.wait(p, 500);
        }

        if (def.gate) {
          this.emit(p, { type: 'step.awaiting', stepId });
          const decision = await this.waitForDecision(p, stepId);
          if (p.cancelled) throw new PlaybackCancelled();
          if (decision.kind === 'reject') {
            this.emit(p, {
              type: 'step.finished',
              stepId,
              status: 'rejected',
              decision: 'rejected',
              rejectReason: decision.reason,
              result: null,
              error: null,
              tokens: 0,
              costUsd: 0,
            });
            rejected.push(def.title);
            await this.wait(p, 400);
            this.emit(p, {
              type: 'agent.message',
              from: AGENTS.coordinator,
              to: def.role,
              content: `Kullanıcı bu adımı reddetti — gerekçe: “${decision.reason}”. Adımı atlıyorum, sonraki adımlarda bu bilgiyi dikkate al.`,
              stepId,
            });
            await this.wait(p, 500);
            continue;
          }
          // The real backend writes one trail line per corrected field, with both values
          // and who typed them. The demo says the same thing — an edit nobody can see
          // afterwards is not governance, it is just a text box.
          for (const [field, value] of Object.entries(decision.params ?? {})) {
            const before = step.params[field];
            this.emit(p, {
              type: 'agent.message',
              from: AGENTS.user,
              to: def.role,
              content: `Parametre kullanıcı tarafından düzenlendi — ${field}: “${String(before ?? '')}” → “${String(value)}”`,
              stepId,
            });
            step.params = { ...step.params, [field]: value };
            await this.wait(p, 350);
          }
        }

        this.emit(p, { type: 'step.started', stepId });
        await this.wait(p, def.workMs);

        this.emit(p, {
          type: 'step.finished',
          stepId,
          status: 'done',
          decision: def.gate ? 'approved' : 'auto',
          result: def.result,
          error: null,
          tokens: def.tokens,
          costUsd: def.costUsd,
        });

        tokens += def.tokens;
        costUsd = Number((costUsd + def.costUsd).toFixed(4));
        this.emit(p, { type: 'run.cost', tokens, costUsd });

        for (const msg of def.after ?? []) {
          await this.wait(p, 450);
          this.emit(p, { type: 'agent.message', from: msg.from, to: msg.to, content: msg.content, stepId });
        }
      }

      await this.wait(p, 600);
      this.emit(p, {
        type: 'agent.message',
        from: AGENTS.verifier,
        to: AGENTS.coordinator,
        content:
          rejected.length === 0
            ? 'Üç kriter de karşılandı: blocker’lar tespit edildi, ticket’lar güncellendi, ekip bilgilendirildi.'
            : `Kısmi teslim: ${rejected.length} adım kullanıcı tarafından reddedildi (${rejected.join(', ')}). Kalan adımlar hedefe uygun.`,
      });

      await this.wait(p, 700);
      this.emit(p, {
        type: 'agent.message',
        from: AGENTS.coordinator,
        to: AGENTS.user,
        content:
          rejected.length === 0
            ? 'Bitti. 3 blocker (RUN-42, RUN-51, RUN-63) Blocked/Highest olarak işaretlendi ve #dev-sprint kanalına özet gönderildi. Toplam maliyet: $' +
              costUsd.toFixed(4) +
              ` · ${tokens.toLocaleString('tr-TR')} token.`
            : `Bitti — ${rejected.length} adım senin talebinle atlandı. Yapılanların dökümü akış panelinde satır satır duruyor.`,
      });

      await this.wait(p, 300);
      this.emit(p, { type: 'run.finished', status: 'done' });
      // The finished run stays in `this.runs` — listRuns() merges live + seeded,
      // so it shows up in History immediately without being duplicated.
    } catch (err) {
      if (!(err instanceof PlaybackCancelled)) throw err;
    }
  }
}

class PlaybackCancelled extends Error {}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

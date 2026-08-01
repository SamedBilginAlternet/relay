import { API_BASE_URL, RUN_SOURCE_KIND } from './index';
import type { PanelRange, PanelReport, PanelRejection, PanelToolUsage } from '../types/panel';

export interface PanelSource {
  report(range: PanelRange): Promise<PanelReport>;
}

const RUN_STATUSES = ['planning', 'awaiting_approval', 'running', 'done', 'failed', 'cancelled'];

function num(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}

function text(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const s = String(value).trim();
  return s.length > 0 ? s : null;
}

/**
 * The server already sends every bucket, but the screen must not depend on that: a
 * chart built from `Object.entries` of a partial map silently loses a bar, and a lost
 * bar is a wrong picture rather than a visible failure.
 */
function normalizeStatuses(raw: unknown): Record<string, number> {
  const source = (raw ?? {}) as Record<string, unknown>;
  const out: Record<string, number> = {};
  for (const status of RUN_STATUSES) out[status] = num(source[status]);
  for (const [key, value] of Object.entries(source)) {
    if (!(key in out)) out[key] = num(value);
  }
  return out;
}

function normalizeRejection(raw: unknown): PanelRejection {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    runId: String(r.runId ?? ''),
    stepId: String(r.stepId ?? ''),
    runGoal: text(r.runGoal),
    runStatus: text(r.runStatus),
    stepTitle: text(r.stepTitle),
    toolName: text(r.toolName),
    reason: text(r.reason),
    at: text(r.at),
  };
}

function normalizeTool(raw: unknown): PanelToolUsage {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    toolName: String(r.toolName ?? '—'),
    calls: num(r.calls),
    tokens: num(r.tokens),
    costUsd: num(r.costUsd),
  };
}

function normalize(raw: unknown): PanelReport {
  const r = (raw ?? {}) as Record<string, unknown>;
  const runs = (r.runs ?? {}) as Record<string, unknown>;
  const approvals = (r.approvals ?? {}) as Record<string, unknown>;
  const totals = (r.totals ?? {}) as Record<string, unknown>;
  return {
    from: String(r.from ?? ''),
    to: String(r.to ?? ''),
    runs: { total: num(runs.total), byStatus: normalizeStatuses(runs.byStatus) },
    approvals: {
      steps: num(approvals.steps),
      gated: num(approvals.gated),
      gatedRatio: num(approvals.gatedRatio),
      approved: num(approvals.approved),
      rejected: num(approvals.rejected),
      cancelled: num(approvals.cancelled),
      pending: num(approvals.pending),
      approvalRate: num(approvals.approvalRate),
    },
    rejections: Array.isArray(r.rejections) ? r.rejections.map(normalizeRejection) : [],
    cancellations: Array.isArray(r.cancellations) ? r.cancellations.map(normalizeRejection) : [],
    tools: Array.isArray(r.tools) ? r.tools.map(normalizeTool) : [],
    totals: { tokens: num(totals.tokens), costUsd: num(totals.costUsd) },
  };
}

class ApiPanelSource implements PanelSource {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  async report(range: PanelRange): Promise<PanelReport> {
    const query = new URLSearchParams();
    if (range.from) query.set('from', range.from);
    if (range.to) query.set('to', range.to);
    const suffix = query.toString() ? `?${query}` : '';

    const res = await fetch(`${this.baseUrl}/panel${suffix}`, {
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
    });
    const body = await res.text();
    const parsed = body ? (JSON.parse(body) as unknown) : null;
    if (!res.ok) {
      const detail = parsed as { message?: string } | null;
      throw new Error(detail?.message || `Panel okunamadı (HTTP ${res.status})`);
    }
    return normalize(parsed);
  }
}

/**
 * Offline twin. The demo can be shown without a backend, and a panel that is blank
 * there would undercut the exact claim it exists to make. These are plausible numbers
 * for a small week, not zeros dressed up as a chart — and they are only ever reachable
 * when `VITE_RUN_SOURCE` is not `api`.
 */
class MockPanelSource implements PanelSource {
  async report(range: PanelRange): Promise<PanelReport> {
    const to = range.to ? new Date(range.to) : new Date();
    const from = range.from ? new Date(range.from) : new Date(to.getTime() - 7 * 86_400_000);
    const day = (back: number) => new Date(to.getTime() - back * 86_400_000).toISOString();
    return {
      from: from.toISOString(),
      to: to.toISOString(),
      runs: {
        total: 14,
        byStatus: { planning: 0, awaiting_approval: 2, running: 0, done: 9, failed: 2, cancelled: 1 },
      },
      approvals: {
        steps: 46,
        gated: 11,
        gatedRatio: 11 / 46,
        approved: 6,
        rejected: 2,
        cancelled: 1,
        pending: 2,
        // 6 / (6 + 2). The cancelled step is nobody's decision, so it is in neither half.
        approvalRate: 6 / 8,
      },
      rejections: [
        {
          runId: '00000000-0000-0000-0000-0000000000a1',
          stepId: '00000000-0000-0000-0000-0000000000b1',
          runGoal: 'Sprint blocker’larını Slack’e özetle',
          runStatus: 'failed',
          stepTitle: '#genel kanalına mesaj gönder',
          toolName: 'slack.postMessage',
          reason: 'Yanlış kanal — bu özet #dev kanalına gitmeli.',
          at: day(1),
        },
        {
          runId: '00000000-0000-0000-0000-0000000000a2',
          stepId: '00000000-0000-0000-0000-0000000000b2',
          runGoal: 'RELAY-42 için yorum bırak',
          runStatus: 'done',
          stepTitle: 'Jira issue’suna yorum ekle',
          toolName: 'jira.addComment',
          reason: 'Metin müşteriye kapalı bilgi içeriyor.',
          at: day(3),
        },
      ],
      cancellations: [
        {
          runId: '00000000-0000-0000-0000-0000000000a3',
          stepId: '00000000-0000-0000-0000-0000000000b3',
          runGoal: 'Haftalık durum notunu hazırla',
          runStatus: 'cancelled',
          stepTitle: 'GitHub issue’suna yorum ekle',
          toolName: 'github.addComment',
          reason: 'akış iptal edildi (demo@relay)',
          at: day(5),
        },
      ],
      tools: [
        { toolName: 'jira.searchIssues', calls: 12, tokens: 8400, costUsd: 0.0061 },
        { toolName: 'gmail.listToday', calls: 9, tokens: 6100, costUsd: 0.0044 },
        { toolName: 'slack.postMessage', calls: 5, tokens: 2200, costUsd: 0.0016 },
        { toolName: 'jira.addComment', calls: 3, tokens: 1500, costUsd: 0.0011 },
        { toolName: 'calendar.listToday', calls: 2, tokens: 900, costUsd: 0.0006 },
      ],
      totals: { tokens: 24_800, costUsd: 0.0192 },
    };
  }
}

let instance: PanelSource | null = null;

export function getPanelSource(): PanelSource {
  if (!instance) {
    instance = RUN_SOURCE_KIND === 'api' ? new ApiPanelSource(API_BASE_URL) : new MockPanelSource();
  }
  return instance;
}

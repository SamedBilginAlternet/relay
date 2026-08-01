import { API_BASE_URL, RUN_SOURCE_KIND } from './index';

/** Governance mode for a single tool — mirrors `PolicyMode` on the server. */
export type PolicyMode = 'auto' | 'ask' | 'forbidden';

/** Risk of a tool — mirrors `RiskLevel` on the server. */
export type RiskLevel = 'read' | 'write' | 'destructive';

/** One row of `GET /api/policies`. */
export type ToolPolicy = {
  provider: string;
  toolName: string;
  risk: RiskLevel;
  mode: PolicyMode;
  /** True when an operator record is stored for this tool. */
  overridden: boolean;
};

/**
 * The default comes from the risk level, not from the row — this is the same
 * table as `RiskLevel.defaultMode()` on the server, and it is what "varsayılana
 * dön" puts back. Keeping it here (instead of reading `overridden`) is
 * deliberate: `PUT /api/policies` can only write a mode, never delete a stored
 * record, so a tool that has been set back to its default still comes back with
 * `overridden: true`. What the operator needs to see is whether the effective
 * mode differs from the default, and that is computable from `risk` alone.
 */
export const DEFAULT_MODE: Record<RiskLevel, PolicyMode> = {
  read: 'auto',
  write: 'ask',
  destructive: 'forbidden',
};

export function defaultModeFor(risk: RiskLevel): PolicyMode {
  return DEFAULT_MODE[risk] ?? 'forbidden';
}

export interface PolicySource {
  list(): Promise<ToolPolicy[]>;
  /** `PUT /api/policies` takes a batch and answers with the whole table. */
  setMode(toolName: string, mode: PolicyMode): Promise<ToolPolicy[]>;
}

const RISKS: RiskLevel[] = ['read', 'write', 'destructive'];
const MODES: PolicyMode[] = ['auto', 'ask', 'forbidden'];

function normalize(raw: unknown): ToolPolicy {
  const r = (raw ?? {}) as Record<string, unknown>;
  const risk = String(r.risk ?? '') as RiskLevel;
  const mode = String(r.mode ?? '') as PolicyMode;
  const safeRisk = RISKS.includes(risk) ? risk : 'destructive';
  return {
    provider: String(r.provider ?? 'diğer'),
    toolName: String(r.toolName ?? ''),
    risk: safeRisk,
    // An unreadable mode must not be shown as the loosest one.
    mode: MODES.includes(mode) ? mode : defaultModeFor(safeRisk),
    overridden: r.overridden === true,
  };
}

class ApiPolicySource implements PolicySource {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
      ...init,
    });
    const text = await res.text();
    const body = text ? (JSON.parse(text) as unknown) : null;
    if (!res.ok) {
      const detail = body as { message?: string } | null;
      throw new Error(detail?.message || `Politikalar okunamadı (HTTP ${res.status})`);
    }
    return body as T;
  }

  async list(): Promise<ToolPolicy[]> {
    const rows = await this.request<unknown[]>('/policies');
    return Array.isArray(rows) ? rows.map(normalize) : [];
  }

  async setMode(toolName: string, mode: PolicyMode): Promise<ToolPolicy[]> {
    // The endpoint accepts a batch; one row is a batch of one.
    const rows = await this.request<unknown[]>('/policies', {
      method: 'PUT',
      body: JSON.stringify([{ toolName, mode }]),
    });
    return Array.isArray(rows) ? rows.map(normalize) : [];
  }
}

/**
 * Offline twin. The demo runs without a backend, and a policy screen that shows
 * nothing there would break the one sentence the demo is built on. Same tools,
 * same defaults, overrides held in memory for the length of the session.
 *
 * <p>"Same tools" is load-bearing and had drifted: the registry grew
 * `jira.getComments`, `gmail.createDraft` and `calendar.listUpcoming` and this
 * list did not, so the demo answered "what is this agent allowed to do" with
 * fifteen of the eighteen rules the server enforces. It is also the only place
 * the screen's height can be measured before it ships.
 */
class MockPolicySource implements PolicySource {
  private rows: ToolPolicy[] = (
    [
      ['google', 'calendar.createEvent', 'write'],
      ['google', 'calendar.listToday', 'read'],
      ['google', 'calendar.listUpcoming', 'read'],
      ['github', 'github.addComment', 'write'],
      ['github', 'github.listMyIssues', 'read'],
      ['github', 'github.listMyPullRequests', 'read'],
      ['google', 'gmail.createDraft', 'write'],
      ['google', 'gmail.getMessage', 'read'],
      ['google', 'gmail.listToday', 'read'],
      ['google', 'gmail.search', 'read'],
      ['jira', 'jira.addComment', 'write'],
      ['jira', 'jira.createIssue', 'write'],
      ['jira', 'jira.getComments', 'read'],
      ['jira', 'jira.getIssue', 'read'],
      ['jira', 'jira.listMyIssues', 'read'],
      ['jira', 'jira.searchIssues', 'read'],
      ['jira', 'jira.updateIssue', 'write'],
      ['notion', 'notion.createPage', 'write'],
      ['google', 'sheets.appendRow', 'write'],
      ['slack', 'slack.listChannels', 'read'],
      ['slack', 'slack.postMessage', 'write'],
    ] as [string, string, RiskLevel][]
  ).map(([provider, toolName, risk]) => ({
    provider,
    toolName,
    risk,
    mode: defaultModeFor(risk),
    overridden: false,
  }));

  async list(): Promise<ToolPolicy[]> {
    return this.rows.map((row) => ({ ...row }));
  }

  async setMode(toolName: string, mode: PolicyMode): Promise<ToolPolicy[]> {
    this.rows = this.rows.map((row) =>
      row.toolName === toolName ? { ...row, mode, overridden: true } : row,
    );
    return this.list();
  }
}

let instance: PolicySource | null = null;

export function getPolicySource(): PolicySource {
  if (!instance) {
    instance = RUN_SOURCE_KIND === 'api' ? new ApiPolicySource(API_BASE_URL) : new MockPolicySource();
  }
  return instance;
}

import { API_BASE_URL, RUN_SOURCE_KIND } from './index';
import { defaultModeFor } from './PolicySource';
import type { PolicyMode, RiskLevel } from './PolicySource';

/**
 * `GET /api/crew` — the crew, computed on the server from the tool registry and
 * the policy engine.
 *
 * The types below carry no name and no description for a member, and that is
 * the point: the payload holds `jira-agent`, not "Jira Uzmanı", and the screen
 * asks `lib/agents.ts` for the word. An id this build has never seen therefore
 * reaches the screen intact instead of being translated into something someone
 * invented (docs/EKIP.md §7.1).
 *
 * Nothing here writes. Authority is changed on `#/politikalar`, which is a
 * human endpoint; this file only ever reads, and it keeps no copy — a second
 * store of permissions is exactly what EKIP.md §7.5 forbids.
 */

/** One tool a member holds, with the mode that tool is actually running under. */
export type HeldTool = {
  name: string;
  risk: RiskLevel;
  mode: PolicyMode;
  /** True when an operator has a record stored for it. */
  overridden: boolean;
};

/** The tier a member's calls start on: the strong model or the small one. */
export type ModelTier = 'large' | 'small' | null;

export type CrewMember = {
  /** `jira-agent` — built by `AgentRole.toolAgent` from the tool name. */
  id: string;
  /** The tool namespace (`gmail`), which is what the brand mark is drawn for. */
  provider: string;
  /** Where the credentials live (`google` for Gmail and Takvim). */
  connectionProvider: string;
  connected: boolean;
  toolCount: number;
  auto: number;
  ask: number;
  forbidden: number;
  tier: ModelTier;
  tools: HeldTool[];
};

/** `planner`, `coordinator`, `verifier`, `policy`, `cost` — six minus the user. */
export type CoreMember = {
  id: string;
  /** The LLM job it makes, or null for the three that never call a model. */
  purpose: string | null;
  tier: ModelTier;
};

export type Crew = {
  core: CoreMember[];
  members: CrewMember[];
};

export interface CrewSource {
  crew(): Promise<Crew>;
}

const RISKS: RiskLevel[] = ['read', 'write', 'destructive'];
const MODES: PolicyMode[] = ['auto', 'ask', 'forbidden'];

function count(raw: Record<string, unknown>, key: string): number {
  const value = Number(raw[key]);
  return Number.isFinite(value) && value >= 0 ? value : 0;
}

function normalizeTool(raw: unknown): HeldTool {
  const r = (raw ?? {}) as Record<string, unknown>;
  const risk = String(r.risk ?? '') as RiskLevel;
  const mode = String(r.mode ?? '') as PolicyMode;
  const safeRisk = RISKS.includes(risk) ? risk : 'destructive';
  return {
    name: String(r.name ?? ''),
    risk: safeRisk,
    // Same rule as Politikalar: an unreadable mode is never shown as the loosest one.
    mode: MODES.includes(mode) ? mode : defaultModeFor(safeRisk),
    overridden: r.overridden === true,
  };
}

function normalizeTier(raw: unknown): ModelTier {
  const tier = String(raw ?? '');
  return tier === 'large' || tier === 'small' ? tier : null;
}

function normalizeMember(raw: unknown): CrewMember {
  const r = (raw ?? {}) as Record<string, unknown>;
  const tools = Array.isArray(r.tools) ? r.tools.map(normalizeTool) : [];
  const id = String(r.id ?? '');
  return {
    id,
    provider: String(r.provider ?? id.replace(/-agent$/, '')),
    connectionProvider: String(r.connectionProvider ?? r.provider ?? ''),
    // Absent means "we do not know it is connected", and the honest reading of
    // that is idle. A member drawn as ready on a missing field would be the one
    // lie this screen cannot afford.
    connected: r.connected === true,
    toolCount: count(r, 'toolCount') || tools.length,
    auto: count(r, 'auto'),
    ask: count(r, 'ask'),
    forbidden: count(r, 'forbidden'),
    tier: normalizeTier(r.tier),
    tools,
  };
}

function normalizeCore(raw: unknown): CoreMember {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: String(r.id ?? ''),
    purpose: r.purpose == null ? null : String(r.purpose),
    tier: normalizeTier(r.tier),
  };
}

class ApiCrewSource implements CrewSource {
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  async crew(): Promise<Crew> {
    const res = await fetch(`${this.baseUrl}/crew`, { credentials: 'include' });
    const text = await res.text();
    const body = text ? (JSON.parse(text) as unknown) : null;
    if (!res.ok) {
      const detail = body as { message?: string } | null;
      throw new Error(detail?.message || `Ekip okunamadı (HTTP ${res.status})`);
    }
    const raw = (body ?? {}) as Record<string, unknown>;
    return {
      core: Array.isArray(raw.core) ? raw.core.map(normalizeCore) : [],
      members: Array.isArray(raw.members) ? raw.members.map(normalizeMember) : [],
    };
  }
}

/**
 * Offline twin, built the same way the server builds the real one: from a tool
 * list, by grouping. It is written as tools rather than as members on purpose —
 * a mock that listed members directly would be the hand-written crew this whole
 * screen exists to prove we do not have.
 */
const MOCK_TOOLS: [string, RiskLevel][] = [
  ['calendar.listToday', 'read'],
  ['calendar.listUpcoming', 'read'],
  ['github.addComment', 'write'],
  ['github.listMyIssues', 'read'],
  ['github.listMyPullRequests', 'read'],
  ['gmail.createDraft', 'write'],
  ['gmail.getMessage', 'read'],
  ['gmail.listToday', 'read'],
  ['gmail.search', 'read'],
  ['jira.addComment', 'write'],
  ['jira.createIssue', 'write'],
  ['jira.getComments', 'read'],
  ['jira.getIssue', 'read'],
  ['jira.listMyIssues', 'read'],
  ['jira.searchIssues', 'read'],
  ['jira.updateIssue', 'write'],
  ['notion.createPage', 'write'],
  ['slack.listChannels', 'read'],
  ['slack.postMessage', 'write'],
];

/** Which providers the offline demo pretends to have credentials for. */
const MOCK_CONNECTED = new Set(['jira', 'slack']);

/** Gmail and Takvim share one Google connection, exactly as they do live. */
const MOCK_CONNECTION_OF: Record<string, string> = {
  gmail: 'google',
  calendar: 'google',
};

class MockCrewSource implements CrewSource {
  async crew(): Promise<Crew> {
    const byProvider = new Map<string, HeldTool[]>();
    for (const [name, risk] of MOCK_TOOLS) {
      const provider = name.split('.')[0] ?? name;
      const list = byProvider.get(provider) ?? [];
      list.push({ name, risk, mode: defaultModeFor(risk), overridden: false });
      byProvider.set(provider, list);
    }

    const members: CrewMember[] = [...byProvider.entries()].map(([provider, tools]) => {
      const connectionProvider = MOCK_CONNECTION_OF[provider] ?? provider;
      return {
        id: `${provider}-agent`,
        provider,
        connectionProvider,
        connected: MOCK_CONNECTED.has(connectionProvider),
        toolCount: tools.length,
        auto: tools.filter((t) => t.mode === 'auto').length,
        ask: tools.filter((t) => t.mode === 'ask').length,
        forbidden: tools.filter((t) => t.mode === 'forbidden').length,
        tier: 'large',
        tools,
      };
    });
    members.sort(
      (a, b) => Number(b.connected) - Number(a.connected) || a.id.localeCompare(b.id, 'tr'),
    );

    return {
      core: [
        { id: 'planner', purpose: 'plan', tier: 'large' },
        { id: 'coordinator', purpose: null, tier: null },
        { id: 'verifier', purpose: 'verify', tier: 'small' },
        { id: 'policy', purpose: null, tier: null },
        { id: 'cost', purpose: null, tier: null },
      ],
      members,
    };
  }
}

let instance: CrewSource | null = null;

export function getCrewSource(): CrewSource {
  if (!instance) {
    instance = RUN_SOURCE_KIND === 'api' ? new ApiCrewSource(API_BASE_URL) : new MockCrewSource();
  }
  return instance;
}

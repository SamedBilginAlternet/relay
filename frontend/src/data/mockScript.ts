import type { Run, Step, StepStatus } from '../types/api';

export const AGENTS = {
  planner: 'Planlayıcı',
  coordinator: 'Koordinatör',
  jira: 'Jira Uzmanı',
  slack: 'Slack Uzmanı',
  verifier: 'Doğrulayıcı',
  user: 'Kullanıcı',
} as const;

export type ScriptMessage = { from: string; to: string; content: string };

export type ScriptStep = {
  title: string;
  role: string;
  toolName: string | null;
  params: Record<string, unknown>;
  /** WRITE-risk step: policy `ask` -> waits for human approval. */
  gate?: boolean;
  workMs: number;
  tokens: number;
  costUsd: number;
  result: unknown;
  before?: ScriptMessage[];
  after?: ScriptMessage[];
};

/**
 * The demo run. Mirrors the PRD headline scenario:
 * "Sprint blocker'larını topla, Jira'da 3 ticket güncelle, ekibe Slack'ten özet at."
 */
export const DEMO_SCRIPT: ScriptStep[] = [
  {
    title: 'Açık sprintteki issue’ları ara',
    role: AGENTS.jira,
    toolName: 'jira.searchIssues',
    params: {
      jql: 'project = RUN AND sprint in openSprints() AND statusCategory != Done',
      fields: ['summary', 'status', 'priority', 'assignee', 'labels'],
      maxResults: 50,
    },
    workMs: 1500,
    tokens: 412,
    costUsd: 0.0021,
    result: {
      total: 18,
      issues: [
        { key: 'RUN-42', summary: 'Ödeme webhook’u 502 dönüyor', status: 'In Progress', labels: ['blocker'] },
        { key: 'RUN-51', summary: 'Staging’de migration kilitlendi', status: 'In Progress', labels: ['blocker'] },
        { key: 'RUN-63', summary: 'SSO redirect döngüsü', status: 'To Do', labels: ['blocker', 'auth'] },
        { key: 'RUN-70', summary: 'Rapor sayfası yavaş', status: 'In Progress', labels: [] },
      ],
    },
    before: [
      {
        from: AGENTS.coordinator,
        to: AGENTS.jira,
        content:
          'RUN projesinin açık sprintindeki tamamlanmamış issue’ları getir. blocker etiketlilere öncelik ver.',
      },
    ],
    after: [
      {
        from: AGENTS.jira,
        to: AGENTS.coordinator,
        content: '18 issue döndü. 3 tanesi blocker etiketli: RUN-42, RUN-51, RUN-63.',
      },
    ],
  },
  {
    title: 'RUN-42 detayını oku',
    role: AGENTS.jira,
    toolName: 'jira.getIssue',
    params: { key: 'RUN-42', expand: ['changelog', 'comments'] },
    workMs: 900,
    tokens: 268,
    costUsd: 0.0013,
    result: {
      key: 'RUN-42',
      status: 'In Progress',
      assignee: 'deniz.k',
      blockedBy: ['RUN-51'],
      lastComment: 'Provider tarafında rate limit var, sabah tekrar denenecek.',
    },
  },
  {
    title: 'Blocker’ları süz ve özet çıkar',
    role: AGENTS.coordinator,
    toolName: null,
    params: {
      strategy: 'label:blocker OR status:Blocked',
      candidates: ['RUN-42', 'RUN-51', 'RUN-63'],
    },
    workMs: 1100,
    tokens: 690,
    costUsd: 0.0034,
    result: {
      blockers: 3,
      summary:
        'RUN-51 kök neden; RUN-42 ona bağlı. RUN-63 bağımsız ve auth ekibinde bekliyor.',
    },
  },
  {
    title: '3 ticket’ın durumunu “Blocked” yap',
    role: AGENTS.jira,
    toolName: 'jira.updateIssue',
    gate: true,
    params: {
      issues: ['RUN-42', 'RUN-51', 'RUN-63'],
      fields: { status: 'Blocked', priority: 'Highest' },
      notifyWatchers: true,
    },
    workMs: 1700,
    tokens: 358,
    costUsd: 0.0019,
    result: { updated: ['RUN-42', 'RUN-51', 'RUN-63'], failed: [] },
    before: [
      {
        from: AGENTS.coordinator,
        to: AGENTS.user,
        content:
          'Bu bir yazma adımı (jira.updateIssue → politika: onay iste). 3 ticket’ı Blocked’a çekeceğim, onayını bekliyorum.',
      },
    ],
    after: [
      {
        from: AGENTS.jira,
        to: AGENTS.coordinator,
        content: '3 ticket güncellendi, hata yok. Watcher’lara bildirim gitti.',
      },
    ],
  },
  {
    title: 'Slack kanallarını listele',
    role: AGENTS.slack,
    toolName: 'slack.listChannels',
    params: { types: 'public_channel', limit: 100 },
    workMs: 800,
    tokens: 180,
    costUsd: 0.0008,
    result: {
      channels: [
        { id: 'C02SPRINT', name: 'dev-sprint', members: 24 },
        { id: 'C02GENERAL', name: 'genel', members: 61 },
      ],
    },
    before: [
      {
        from: AGENTS.coordinator,
        to: AGENTS.slack,
        content: 'Özeti hangi kanala atacağımızı doğrula — sprint kanalı hedefimiz.',
      },
    ],
  },
  {
    title: '#dev-sprint kanalına özet gönder',
    role: AGENTS.slack,
    toolName: 'slack.postMessage',
    gate: true,
    params: {
      channel: '#dev-sprint',
      text:
        '*Sprint blocker özeti*\n• RUN-51 — Staging migration kilidi (kök neden)\n• RUN-42 — Ödeme webhook 502 (RUN-51’e bağlı)\n• RUN-63 — SSO redirect döngüsü (auth ekibi)\nÜçü de Blocked / Highest olarak işaretlendi.',
      unfurl_links: false,
    },
    workMs: 1400,
    tokens: 524,
    costUsd: 0.0027,
    result: { ok: true, channel: 'C02SPRINT', ts: '1738332841.004500', permalink: 'https://slack.com/archives/C02SPRINT/p1738332841' },
    before: [
      {
        from: AGENTS.coordinator,
        to: AGENTS.user,
        content:
          'Mesaj taslağı hazır (slack.postMessage → politika: onay iste). Göndermeden önce metni kontrol edebilirsin.',
      },
    ],
  },
  {
    title: 'Sonucu hedefe karşı doğrula',
    role: AGENTS.verifier,
    toolName: null,
    params: { goalCheck: ['blockers_identified', 'tickets_updated', 'team_notified'] },
    workMs: 950,
    tokens: 296,
    costUsd: 0.0015,
    result: { passed: true, notes: 'Üç kriter de karşılandı.' },
  },
];

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

let idSeq = 0;
export function mockId(prefix: string): string {
  idSeq += 1;
  return `${prefix}-${Date.now().toString(36)}-${idSeq.toString(36)}`;
}

export function stepsFromScript(script: ScriptStep[]): Step[] {
  return script.map((def, i) => ({
    id: mockId('step'),
    ordinal: i + 1,
    title: def.title,
    role: def.role,
    toolName: def.toolName,
    params: def.params,
    status: 'pending' as StepStatus,
    decision: null,
    rejectReason: null,
    result: null,
    error: null,
    tokens: 0,
    costUsd: 0,
    startedAt: null,
    finishedAt: null,
  }));
}

/* ------------------------------------------------------------------ */
/* Seeded history — so the History screen is never empty on a fresh load */
/* ------------------------------------------------------------------ */

type HistoryStepSeed = {
  title: string;
  role: string;
  toolName: string | null;
  params: Record<string, unknown>;
  status: StepStatus;
  decision: Step['decision'];
  rejectReason?: string;
  result?: unknown;
  error?: string;
  tokens: number;
  costUsd: number;
  durationMs: number;
};

function buildHistoryRun(
  id: string,
  goal: string,
  status: string,
  startedMinutesAgo: number,
  seeds: HistoryStepSeed[],
  messages: ScriptMessage[],
): Run {
  const start = Date.now() - startedMinutesAgo * 60_000;
  let cursor = start;
  const steps: Step[] = seeds.map((seed, i) => {
    const startedAt = new Date(cursor).toISOString();
    cursor += seed.durationMs;
    const finishedAt = new Date(cursor).toISOString();
    cursor += 400;
    return {
      id: `${id}-step-${i + 1}`,
      ordinal: i + 1,
      title: seed.title,
      role: seed.role,
      toolName: seed.toolName,
      params: seed.params,
      status: seed.status,
      decision: seed.decision,
      rejectReason: seed.rejectReason ?? null,
      result: seed.result ?? null,
      error: seed.error ?? null,
      tokens: seed.tokens,
      costUsd: seed.costUsd,
      startedAt: seed.status === 'pending' ? null : startedAt,
      finishedAt: seed.status === 'pending' ? null : finishedAt,
    };
  });

  return {
    id,
    goal,
    status,
    costTokens: steps.reduce((a, s) => a + s.tokens, 0),
    costUsd: Number(steps.reduce((a, s) => a + s.costUsd, 0).toFixed(4)),
    budgetUsd: 0.5,
    steps,
    messages: messages.map((m, i) => ({
      id: `${id}-msg-${i + 1}`,
      stepId: null,
      fromAgent: m.from,
      toAgent: m.to,
      content: m.content,
      createdAt: new Date(start + i * 1500).toISOString(),
    })),
    createdAt: new Date(start).toISOString(),
    finishedAt: new Date(cursor).toISOString(),
  };
}

export function seededHistory(): Run[] {
  return [
    buildHistoryRun(
      'run-seed-1',
      'Dünkü standup notlarını Jira’ya görev olarak aç ve ekibe Slack’ten hatırlat',
      'done',
      55,
      [
        {
          title: 'Standup notlarından görev çıkar',
          role: AGENTS.coordinator,
          toolName: null,
          params: { source: 'chat', extract: 'action_items' },
          status: 'done',
          decision: 'auto',
          result: { items: 4 },
          tokens: 720,
          costUsd: 0.0036,
          durationMs: 1400,
        },
        {
          title: '4 issue oluştur',
          role: AGENTS.jira,
          toolName: 'jira.updateIssue',
          params: { project: 'RUN', issues: 4, type: 'Task' },
          status: 'done',
          decision: 'approved',
          result: { created: ['RUN-88', 'RUN-89', 'RUN-90', 'RUN-91'] },
          tokens: 410,
          costUsd: 0.002,
          durationMs: 2100,
        },
        {
          title: '#genel kanalına hatırlatma at',
          role: AGENTS.slack,
          toolName: 'slack.postMessage',
          params: { channel: '#genel', text: 'Standup görevleri açıldı: RUN-88…91' },
          status: 'done',
          decision: 'approved',
          result: { ok: true },
          tokens: 260,
          costUsd: 0.0012,
          durationMs: 900,
        },
      ],
      [
        { from: AGENTS.planner, to: AGENTS.user, content: 'Hedefi 3 adıma böldüm.' },
        { from: AGENTS.verifier, to: AGENTS.coordinator, content: 'Dört görev de açıldı, hedef karşılandı.' },
      ],
    ),
    buildHistoryRun(
      'run-seed-2',
      'Geçen haftanın kapanan ticket’larını özetle ve müşteriye durum mesajı hazırla',
      'done',
      190,
      [
        {
          title: 'Kapanan issue’ları ara',
          role: AGENTS.jira,
          toolName: 'jira.searchIssues',
          params: { jql: 'project = RUN AND status changed to Done during (-7d, now())' },
          status: 'done',
          decision: 'auto',
          result: { total: 12 },
          tokens: 380,
          costUsd: 0.0018,
          durationMs: 1200,
        },
        {
          title: 'Müşteri mesajını gönder',
          role: AGENTS.slack,
          toolName: 'slack.postMessage',
          params: { channel: '#musteri-acme', text: 'Haftalık durum özeti…' },
          status: 'rejected',
          decision: 'rejected',
          rejectReason: 'Mesajı önce hesap yöneticisi görsün, kanal yanlış.',
          tokens: 0,
          costUsd: 0,
          durationMs: 0,
        },
        {
          title: 'Özeti taslak olarak sakla',
          role: AGENTS.coordinator,
          toolName: null,
          params: { store: 'draft' },
          status: 'done',
          decision: 'auto',
          result: { saved: true },
          tokens: 190,
          costUsd: 0.0009,
          durationMs: 700,
        },
      ],
      [
        {
          from: AGENTS.coordinator,
          to: AGENTS.slack,
          content: 'Kullanıcı reddetti: “Mesajı önce hesap yöneticisi görsün”. Gönderme, taslağa al.',
        },
      ],
    ),
    buildHistoryRun(
      'run-seed-3',
      'Prod hata alarmlarını Jira’ya bug olarak aç',
      'failed',
      420,
      [
        {
          title: 'Alarm kaynağını oku',
          role: AGENTS.coordinator,
          toolName: null,
          params: { source: 'alerts' },
          status: 'done',
          decision: 'auto',
          result: { alerts: 6 },
          tokens: 240,
          costUsd: 0.0011,
          durationMs: 800,
        },
        {
          title: 'Bug issue’ları oluştur',
          role: AGENTS.jira,
          toolName: 'jira.updateIssue',
          params: { project: 'RUN', type: 'Bug', count: 6 },
          status: 'failed',
          decision: 'approved',
          error: 'Jira 401: API token süresi dolmuş. Bağlantılar ekranından yenile.',
          tokens: 120,
          costUsd: 0.0006,
          durationMs: 1500,
        },
      ],
      [
        {
          from: AGENTS.jira,
          to: AGENTS.coordinator,
          content: 'Jira kimlik doğrulaması reddedildi (401). Token yenilenmeli, adımı durduruyorum.',
        },
      ],
    ),
  ];
}

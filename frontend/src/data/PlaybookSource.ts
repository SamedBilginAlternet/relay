import { API_BASE_URL, RUN_SOURCE_KIND, getRunSource } from './index';

/** `GET /api/playbooks` — a written-down flow and whether it can run right now. */
export type Playbook = {
  id: string;
  title: string;
  goal: string;
  subtitle: string;
  steps: { title: string; tool: string; optional: boolean }[];
  /** False when a required provider is not connected. */
  runnable: boolean;
  /** Providers that are missing — shown so the user knows what to connect. */
  missing: string[];
};

export interface PlaybookSource {
  list(): Promise<Playbook[]>;
  run(id: string): Promise<{ runId: string }>;
}

function normalize(raw: unknown): Playbook {
  const r = (raw ?? {}) as Record<string, unknown>;
  const steps = Array.isArray(r.steps) ? r.steps : [];
  return {
    id: String(r.id ?? ''),
    title: String(r.title ?? 'Akış'),
    goal: String(r.goal ?? ''),
    subtitle: String(r.subtitle ?? ''),
    steps: steps.map((s) => {
      const step = (s ?? {}) as Record<string, unknown>;
      return {
        title: String(step.title ?? ''),
        tool: String(step.tool ?? ''),
        optional: Boolean(step.optional),
      };
    }),
    runnable: r.runnable !== false,
    missing: Array.isArray(r.missing) ? r.missing.map(String) : [],
  };
}

class ApiPlaybookSource implements PlaybookSource {
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
      throw new Error(detail?.message || `Akış listesi alınamadı (HTTP ${res.status})`);
    }
    return body as T;
  }

  async list(): Promise<Playbook[]> {
    const rows = await this.request<unknown[]>('/playbooks');
    return Array.isArray(rows) ? rows.map(normalize) : [];
  }

  async run(id: string): Promise<{ runId: string }> {
    const run = await this.request<{ id?: string; runId?: string }>(
      `/playbooks/${encodeURIComponent(id)}/run`,
      { method: 'POST', body: '{}' },
    );
    return { runId: String(run.runId ?? run.id ?? '') };
  }
}

/** Offline twin: the same flows the scripted demo can actually play. */
class MockPlaybookSource implements PlaybookSource {
  private static readonly ROWS: Playbook[] = [
    {
      id: 'gunun-ozeti',
      title: 'Günün özeti',
      goal: 'Bugün üstümde ne var? Jira kayıtlarımı ve review bekleyen PR’ları topla, sonra ekibe kısa bir durum mesajı yaz.',
      subtitle: 'Jira · GitHub okunur, ardından Slack mesajı onayına gelir',
      steps: [
        { title: 'Üstümdeki Jira kayıtlarını getir', tool: 'jira.listMyIssues', optional: false },
        { title: 'Review bekleyen PR’ları getir', tool: 'github.listMyPullRequests', optional: true },
        { title: 'Ekibe durum mesajı gönder', tool: 'slack.postMessage', optional: false },
      ],
      runnable: true,
      missing: [],
    },
    {
      id: 'takilan-isler',
      title: 'Takılan işler',
      goal: 'Üç günden uzun süredir ilerlemeyen işleri bul ve sahiplerine hatırlatma yaz.',
      subtitle: 'Jira okunur, hatırlatma mesajı onayına gelir',
      steps: [
        { title: 'Bekleyen kayıtları ara', tool: 'jira.searchIssues', optional: false },
        { title: 'Hatırlatma gönder', tool: 'slack.postMessage', optional: false },
      ],
      runnable: true,
      missing: [],
    },
    /*
      Notion is here rather than only live because the shelf is the first screen
      anybody sees, and a shelf whose every flow ends in a chat message says the
      product is for engineers. This one ends in a written page, and its last
      step is optional for the same reason the live playbook's is: a workspace
      with no Notion connection still runs the flow, minus the note.
    */
    /*
      The stage flow leads the shelf: one mail carried across every surface the
      product writes to, four gates, each one the moment the pitch is about (#172).
    */
    {
      id: 'gunu-kapat',
      title: 'Günü kapat',
      goal: 'Bugünkü maillerdeki müşteri şikayetini bul; onun için Jira kaydı aç, kanala bildir, kararı Notion kütüğüne yaz ve günlük rapora satır ekle.',
      subtitle: 'Gmail okunur; Jira, Slack, Notion ve rapor satırı dört ayrı kapıda onayına gelir',
      steps: [
        { title: 'Şikayet mailini bul', tool: 'gmail.search', optional: false },
        { title: 'Maili Jira kaydına çevir', tool: 'jira.createIssue', optional: false },
        { title: 'Kanala kayıt anahtarıyla bildir', tool: 'slack.postMessage', optional: true },
        { title: 'Kararı Notion kütüğüne yaz', tool: 'notion.createPage', optional: true },
        { title: 'Günlük rapora satır ekle', tool: 'sheets.appendRow', optional: true },
      ],
      runnable: true,
      missing: [],
    },
    /*
      The HR flow rides here for the same reason Notion's does: the shelf argues
      who the product is for, and a flow that files leave requests says it is
      not only for engineers. Its writes are the three places a small company's
      HR already lives — calendar, sheet, mailbox — because there is no HR
      provider to connect and a tool wired to nothing would be theatre (#169).
    */
    {
      id: 'izin-talepleri',
      title: 'İzin talepleri',
      goal: 'Son bir haftanın maillerinde ekipten gelen izin taleplerini bul; gelenler için izin günlerini takvime işle, izin tablosuna satır ekle ve onay cevabını taslakla.',
      subtitle: 'Gmail okunur; takvim bloğu, tablo satırı ve cevap taslağı ayrı ayrı onayına gelir',
      steps: [
        { title: 'İzin taleplerini maillerde ara', tool: 'gmail.search', optional: false },
        { title: 'İzin günlerini takvime işle', tool: 'calendar.createEvent', optional: true },
        // hr.logLeave since #171: same sheet, same append — but a leave record has a
        // fixed shape (kişi · tarihler · tür), which a free values[] could not promise.
        { title: 'İzin tablosuna satır ekle', tool: 'hr.logLeave', optional: true },
        { title: 'Onay cevabını taslakla', tool: 'gmail.createDraft', optional: true },
      ],
      runnable: true,
      missing: [],
    },
    {
      id: 'karar-notu',
      title: 'Gün sonu notu',
      goal: 'Bugünkü toplantıları ve mailleri özetle, alınan kararları Notion’a not sayfası olarak yaz.',
      subtitle: 'Takvim · Gmail okunur, Notion sayfası onayına gelir',
      steps: [
        { title: 'Bugünün toplantılarını getir', tool: 'calendar.listToday', optional: false },
        { title: 'Bugünün maillerini oku', tool: 'gmail.listToday', optional: true },
        { title: 'Notion’a not sayfası aç', tool: 'notion.createPage', optional: true },
      ],
      runnable: true,
      missing: [],
    },
  ];

  async list(): Promise<Playbook[]> {
    return MockPlaybookSource.ROWS;
  }

  async run(id: string): Promise<{ runId: string }> {
    const playbook = MockPlaybookSource.ROWS.find((p) => p.id === id) ?? MockPlaybookSource.ROWS[0]!;
    return getRunSource().createRun(playbook.goal);
  }
}

let instance: PlaybookSource | null = null;

export function getPlaybookSource(): PlaybookSource {
  if (!instance) {
    instance = RUN_SOURCE_KIND === 'api' ? new ApiPlaybookSource(API_BASE_URL) : new MockPlaybookSource();
  }
  return instance;
}

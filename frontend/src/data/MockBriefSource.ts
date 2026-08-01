import type { Brief, InsightCard, InsightSource, SuggestedAction } from '../types/brief';
import type { BriefSource } from './BriefSource';
import type { RunSource } from './RunSource';

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** "14:00" for today, so the calendar/inbox rows never look stale in a demo. */
function at(hour: number, minute = 0): string {
  const d = new Date();
  d.setHours(hour, minute, 0, 0);
  return d.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
}

function buildBrief(): Brief {
  return {
    date: new Date().toISOString(),
    /* Counted, not written — and the numbers below are the ones the sections
       further down actually contain, because a summary that disagrees with the
       evidence under it is worse than no summary. Three of the four mails are
       from a person (the cost report is automatic), four records, three code
       items, calendar unavailable so it contributes nothing, and one of the
       three insight cards came back `high`. */
    today: {
      headline: 'Bugün 10 iş seni bekliyor, 1 tanesi acil.',
      /* Each line names the list it was counted from, exactly as the server
         does — the screen draws that provider's mark beside it and must never
         read the source back off the Turkish. Calendar is `unavailable` below,
         so it contributes no line and therefore no mark. */
      lines: [
        { source: 'gmail', text: '3 mail bir kişiden geldi (1 bülten ayrıldı)' },
        { source: 'jira', text: '4 kayıt üstünde' },
        { source: 'github', text: '3 PR ve issue sende' },
      ],
      /* The same rule the server follows: the first two mails from a person,
         then the top record and the top pull request. `mail-4` is the automatic
         cost report and never appears — a mailing is not work. Calendar is the
         deliberately `unavailable` section below, so it contributes nothing,
         which is exactly what a missing provider should look like here.
         Every `itemId` is a row id from the sections further down: clicking one
         has to land somewhere real. */
      highlights: [
        {
          itemId: 'mail-1',
          source: 'gmail',
          label: 'Ödeme servisi staging’de patlıyor',
          detail: `Ayşe Demir · ${at(9, 12)}`,
        },
        {
          itemId: 'mail-2',
          source: 'gmail',
          label: 'Sprint demo kaydı ve notlar',
          detail: `Deniz Aksoy · ${at(8, 40)}`,
        },
        {
          itemId: 'KAN-42',
          source: 'jira',
          label: 'Ödeme retry politikası',
          detail: 'KAN-42 · Blocked',
        },
        {
          itemId: 'pr-128',
          source: 'github',
          label: 'retry/backoff for payment client',
          detail: 'acme/payment-svc #128 · review istendi',
        },
      ],
      counts: {
        inbox: 4,
        inboxPersonal: 3,
        inboxBulk: 1,
        work: 4,
        code: 3,
        calendar: 0,
        urgent: 1,
      },
    },
    /* `priorities[].itemId` is the *card* id — the backend keys both off the same
       BriefItem, so the mock has to as well or the reasons match nothing. The third
       card is deliberately left out: the model writes at most five reasons and skips
       what it cannot justify, and that row has to stay readable without one. */
    digest: {
      summary:
        'Bugün iki şey seni bekliyor: ödeme servisindeki staging hatası ve iki gündür review bekleyen PR. Gerisi bilgilendirme.',
      priorities: [
        { itemId: 'gmail:1', why: 'Release bloke; en eski bekleyen konu bu.' },
        { itemId: 'github-pr:acme/payment-svc#128', why: 'İki gündür sende, CI yeşil.' },
      ],
      advice: 'Önce hata kaydını aç, sonra PR review — ikisi de aynı servisi ilgilendiriyor.',
    },

    priority: [
      {
        id: 'gmail:1',
        source: 'gmail',
        title: 'Ödeme servisi staging’de patlıyor',
        from: 'Ayşe Demir',
        kind: 'bug_report',
        urgency: 'high',
        summary:
          'Staging’de ödeme çağrılarının %40’ı 500 dönüyor; Ayşe kayıt açılmasını ve ekibin haberdar edilmesini istiyor.',
        suggestedActions: [
          {
            tool: 'jira.createIssue',
            label: 'Jira ticket aç',
            params: {
              projectKey: 'KAN',
              issueType: 'Bug',
              summary: 'Ödeme servisi staging’de 500 dönüyor',
              priority: 'High',
              description:
                'Ayşe Demir (e-posta, bugün 09:12): staging ortamında ödeme çağrılarının yaklaşık %40’ı HTTP 500 dönüyor. Son deploy: payment-svc 1.8.3.',
            },
          },
          {
            tool: 'slack.postMessage',
            label: 'Slack’e bildir',
            params: {
              channel: '#dev-payments',
              text: 'Staging’de ödeme servisi %40 oranında 500 dönüyor (Ayşe Demir bildirdi). Kayıt açılıyor.',
            },
          },
        ],
      },
      {
        id: 'github-pr:acme/payment-svc#128',
        source: 'github',
        title: 'PR #128 — “retry/backoff for payment client”',
        from: 'Mert Kaya',
        kind: 'request',
        urgency: 'normal',
        summary:
          'İki gündür senin review’unu bekliyor; CI yeşil, 3 dosya değişmiş ve staging hatasıyla doğrudan ilgili.',
        suggestedActions: [
          {
            tool: 'github.addComment',
            label: 'İncele ve özet yorum bırak',
            params: {
              repo: 'acme/payment-svc',
              pullNumber: 128,
              body: 'Diff’i inceledim — retry politikası mantıklı. Jitter eklenmesi dışında blocker görmüyorum.',
            },
          },
          {
            tool: 'slack.postMessage',
            label: 'Mert’e Slack’ten haber ver',
            params: { channel: '@mert.kaya', text: 'PR #128’e bugün bakıyorum.' },
          },
        ],
      },
      {
        id: 'jira:KAN-42',
        source: 'jira',
        title: 'KAN-42 iki gündür “Blocked”',
        from: 'Jira · sana atanmış',
        kind: 'needs_reply',
        urgency: 'normal',
        summary:
          'Kayıt 48 saattir Blocked; engelin ne olduğu yazılmamış. Sprint bitişine 2 gün kaldı.',
        suggestedActions: [
          {
            tool: 'jira.addComment',
            label: 'Engeli sor',
            params: {
              issueKey: 'KAN-42',
              body: 'Bu kayıt 2 gündür Blocked — engeli netleştirebilir miyiz? Sprint bitişine 2 gün kaldı.',
            },
          },
          {
            tool: 'jira.updateIssue',
            label: 'In Progress’e al',
            params: { issueKey: 'KAN-42', fields: { status: 'In Progress' } },
          },
        ],
      },
    ],

    inbox: {
      status: 'ok',
      items: [
        {
          id: 'mail-1',
          title: 'Ödeme servisi staging’de patlıyor',
          subtitle: 'Ayşe Demir · yanıt bekliyor',
          meta: at(9, 12),
          tone: 'danger',
        },
        {
          id: 'mail-2',
          title: 'Sprint demo kaydı ve notlar',
          subtitle: 'Deniz Aksoy · bilgilendirme',
          meta: at(8, 40),
        },
        {
          id: 'mail-3',
          title: 'Vendor sözleşmesi — imza gerekiyor',
          subtitle: 'Finans · yanıt bekliyor',
          meta: 'dün 17:55',
          tone: 'warn',
        },
        {
          id: 'mail-4',
          title: 'Haftalık altyapı maliyet raporu',
          subtitle: 'Otomatik · bilgilendirme',
          meta: at(7, 5),
        },
      ],
    },

    work: {
      status: 'ok',
      items: [
        { id: 'KAN-42', title: 'Ödeme retry politikası', subtitle: 'KAN-42', meta: 'Blocked', tone: 'danger' },
        { id: 'KAN-51', title: 'Webhook imza doğrulama', subtitle: 'KAN-51', meta: 'In Progress' },
        { id: 'KAN-58', title: 'Fatura PDF şablonu', subtitle: 'KAN-58', meta: 'In Review', tone: 'warn' },
        { id: 'KAN-63', title: 'Rate limit metrikleri', subtitle: 'KAN-63', meta: 'To Do' },
      ],
    },

    code: {
      status: 'ok',
      items: [
        {
          id: 'pr-128',
          title: 'retry/backoff for payment client',
          subtitle: 'acme/payment-svc #128 · review istendi',
          meta: '2 gün',
          tone: 'warn',
        },
        {
          id: 'pr-131',
          title: 'chore: bump spring boot 3.4',
          subtitle: 'acme/payment-svc #131 · review istendi',
          meta: '4 sa',
        },
        {
          id: 'issue-77',
          title: 'Webhook retry sonsuz döngüye giriyor',
          subtitle: 'acme/payment-svc #77 · sana atandı',
          meta: 'dün',
        },
      ],
    },

    // PARTIAL SUCCESS demo: Calendar is not connected, everything else still arrives.
    calendar: {
      status: 'unavailable',
      reason:
        'Google Calendar bağlı değil. Bağlantılar ekranından Google hesabını bağladığında bugünün toplantıları burada listelenir.',
      items: [],
    },
  };
}

/**
 * Backend-free brief. Rich enough to demo the whole screen — including one
 * deliberately `unavailable` section, because partial success is the point.
 */
export class MockBriefSource implements BriefSource {
  readonly kind = 'mock' as const;

  constructor(private readonly runSource: RunSource) {}

  async getBrief(): Promise<Brief> {
    await delay(420);
    return buildBrief();
  }

  async refreshBrief(): Promise<Brief> {
    await delay(700);
    return buildBrief();
  }

  async startFromSuggestion(
    card: InsightCard,
    action: SuggestedAction,
  ): Promise<{ runId: string }> {
    await delay(200);
    // A suggestion is just a pre-filled goal — the engine underneath is unchanged.
    // The goal used to be the label and the tool name, which is what the server was
    // given too; the demo would have gone on showing the bug after the fix.
    return this.runSource.createRun(mockGoal(card, action));
  }
}

/** What the item is called on this screen. */
const KIND: Record<InsightSource, string> = {
  gmail: 'Gmail maili',
  jira: 'Jira kaydı',
  github: 'GitHub kaydı',
};

/** `jira:KAN-42` → `KAN-42`. A mail's id is opaque and names nothing. */
function handle(card: InsightCard): string {
  if (card.source === 'gmail') return '';
  const at = card.id.indexOf(':');
  return at < 0 ? '' : card.id.slice(at + 1);
}

/** The sentence the backend builds, mirrored so the demo reads like the real thing. */
function mockGoal(card: InsightCard, action: SuggestedAction): string {
  const named = [KIND[card.source], handle(card), `"${card.title}"`, card.from ? `(${card.from})` : '']
    .filter(Boolean)
    .join(' ');
  return `${action.label} — ${named}. Özet: ${card.summary}`;
}

import type { Brief, SuggestedAction } from '../types/brief';
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
    _cardId: string,
    action: SuggestedAction,
  ): Promise<{ runId: string }> {
    await delay(200);
    // A suggestion is just a pre-filled goal — the engine underneath is unchanged.
    return this.runSource.createRun(`${action.label} · ${action.tool}`);
  }
}

import type {
  AskAnswer,
  AskAnswerSource,
  AskQuerySource,
  AskSourceItem,
  AskStatus,
} from '../types/ask';
import { ApiError } from './ApiRunSource';

/**
 * Everything the Sor screen is allowed to know about the backend.
 * Same contract shape as RunSource/BriefSource — components never call `fetch`.
 */
export interface AskSource {
  readonly kind: 'api' | 'mock';

  /** `POST /api/ask` — read-only search over the user's own mailbox. */
  ask(question: string): Promise<AskAnswer>;
}

// ---- normalisation ---------------------------------------------------------

const STATUSES: AskStatus[] = ['ok', 'empty', 'unavailable', 'error'];
const ANSWER_SOURCES: AskAnswerSource[] = ['llm', 'listing', 'none'];
const QUERY_SOURCES: AskQuerySource[] = ['llm', 'heuristic'];

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function asString(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function normalizeSourceItem(raw: unknown, index: number): AskSourceItem {
  const r = asRecord(raw);
  return {
    id: asString(r.id) || `source-${index}`,
    subject: asString(r.subject) || '(konusuz)',
    from: asString(r.from),
    at: asString(r.at),
    url: asString(r.url),
  };
}

/**
 * A garbled answer must never become a confident one. Anything we cannot read
 * degrades to `error` with an empty source list, which the screen renders as
 * "I could not answer" — never as an answer with no evidence behind it.
 */
export function normalizeAnswer(raw: unknown, question: string): AskAnswer {
  const r = asRecord(raw);
  const status = STATUSES.includes(r.status as AskStatus) ? (r.status as AskStatus) : 'error';
  const sources = Array.isArray(r.sources) ? r.sources.map(normalizeSourceItem) : [];
  const answer = asString(r.answer).trim();
  return {
    question: asString(r.question) || question,
    query: asString(r.query),
    queryExplanation: asString(r.queryExplanation),
    querySource: QUERY_SOURCES.includes(r.querySource as AskQuerySource)
      ? (r.querySource as AskQuerySource)
      : 'heuristic',
    status,
    answer: answer || 'Sunucu bir yanıt metni döndürmedi.',
    answerSource: ANSWER_SOURCES.includes(r.answerSource as AskAnswerSource)
      ? (r.answerSource as AskAnswerSource)
      : 'none',
    // `ok` without a single mail behind it is not an answer we are willing to show.
    sources: status === 'ok' ? sources : [],
    resultCount: typeof r.resultCount === 'number' ? r.resultCount : sources.length,
    mode: asString(r.mode) || null,
    tokens: typeof r.tokens === 'number' ? r.tokens : 0,
    costUsd: typeof r.costUsd === 'number' ? r.costUsd : 0,
  };
}

// ---- api -------------------------------------------------------------------

export class ApiAskSource implements AskSource {
  readonly kind = 'api' as const;
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  async ask(question: string): Promise<AskAnswer> {
    let res: Response;
    try {
      res = await fetch(`${this.baseUrl}/ask`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
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
        res.status === 401
          ? 'Oturumun düşmüş görünüyor. Tekrar giriş yapman gerekiyor.'
          : res.status === 404
            ? 'Soru ucu bulunamadı (HTTP 404). Backend bu sürümü henüz sunmuyor.'
            : res.status >= 500
              ? `Soru yanıtlanamadı (HTTP ${res.status}). Backend ayakta mı?`
              : `İstek başarısız (HTTP ${res.status})`;
      throw new ApiError(detail || fallback, res.status);
    }
    return normalizeAnswer(await res.json(), question);
  }
}

// ---- mock ------------------------------------------------------------------

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isoDaysAgo(days: number, hour: number): string {
  const d = new Date();
  d.setDate(d.getDate() - days);
  d.setHours(hour, 12, 0, 0);
  return d.toISOString();
}

const CARGO = /kargo|sipariş|siparis|teslimat|gönderi|gonderi|paket/i;

/**
 * Backend-free answers, so the whole screen — trace, citations, sources, and the
 * honest empty case — can be demoed without Google credentials.
 *
 * Two scripted outcomes on purpose: a cargo question finds mail, anything else
 * finds nothing. "Nothing found" is not an error state here, it is the answer,
 * and it is the one a mailbox assistant gets wrong most often.
 */
export class MockAskSource implements AskSource {
  readonly kind = 'mock' as const;

  async ask(question: string): Promise<AskAnswer> {
    await delay(900);
    const query = CARGO.test(question)
      ? '(from:(trendyol OR hepsiburada OR aras OR yurtici) OR subject:(kargo OR teslimat OR sipariş)) newer_than:30d'
      : `${question.trim().split(/\s+/).slice(0, 4).join(' ')} newer_than:90d`;

    if (!CARGO.test(question)) {
      return {
        question,
        query,
        queryExplanation: 'Sorudaki kelimeleri son 90 günün mailleri içinde aradım.',
        querySource: 'heuristic',
        status: 'empty',
        answer: `Bu sorguyla eşleşen mail bulamadım: ${query}. Sorunu biraz farklı sorarsan tekrar deneyebilirim.`,
        answerSource: 'none',
        sources: [],
        resultCount: 0,
        mode: 'mock',
        tokens: 180,
        costUsd: 0.000041,
      };
    }

    return {
      question,
      query,
      queryExplanation: 'Son 30 günde kargo ve teslimat maillerini aradım.',
      querySource: 'llm',
      status: 'ok',
      answer:
        'İki kargodan biri teslim edilmiş: Aras Kargo dün 14:20’de kapıya bıraktığını yazıyor [1]. ' +
        'Trendyol siparişin ise hâlâ yolda, tahmini teslim yarın [2]. Üçüncü bir gönderi görünmüyor.',
      answerSource: 'llm',
      sources: [
        {
          id: 'mock-mail-aras',
          subject: 'Gönderiniz teslim edilmiştir',
          from: 'Aras Kargo <bilgi@araskargo.com.tr>',
          at: isoDaysAgo(1, 14),
          url: 'https://mail.google.com/mail/u/0/#all/mock-mail-aras',
        },
        {
          id: 'mock-mail-trendyol',
          subject: 'Siparişin kargoya verildi',
          from: 'Trendyol <bilgi@trendyol.com>',
          at: isoDaysAgo(2, 9),
          url: 'https://mail.google.com/mail/u/0/#all/mock-mail-trendyol',
        },
      ],
      resultCount: 2,
      mode: 'mock',
      tokens: 940,
      costUsd: 0.000262,
    };
  }
}

import { motion, useReducedMotion } from 'motion/react';
import {
  ExternalLink,
  Loader,
  Mail,
  Search,
  SearchX,
  Send,
  TriangleAlert,
} from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { describeLoadError, LoadError } from '../components/LoadError';
import { getAskSource } from '../data';
import { formatDateTime, formatTokens, formatUsd } from '../lib/format';
import { enterProps } from '../lib/motion';
import type { AskAnswer, AskSourceItem } from '../types/ask';

const EXAMPLES = ['kargolarım gelmiş mi', 'bu hafta fatura geldi mi'];

const CITATION = /\[(\d{1,2})]/g;

/** `"Aras Kargo <bilgi@aras.com.tr>"` → `Aras Kargo`. */
function person(raw: string): string {
  if (!raw) return '';
  const bracket = raw.indexOf('<');
  const name = bracket > 0 ? raw.slice(0, bracket) : raw;
  return name.trim().replace(/^"|"$/g, '');
}

/**
 * The trace below prints the query in full, in mono, once. The server's own
 * message often ends with it too — printing a 120-character Gmail expression
 * twice on one screen buries the sentence that matters.
 */
function withoutQueryEcho(message: string, query: string): string {
  if (!query || !message.includes(query)) return message;
  return message
    .split(query)
    .join('')
    .replace(/\s*:\s*\./g, '.')
    .replace(/\s*:\s*$/g, '.')
    .replace(/\s{2,}/g, ' ')
    .trim();
}

/**
 * `[1]` in the answer is `sources[0]` — the model was handed that numbering and
 * the backend deletes any citation pointing past the end of the list. Rendering
 * them as plain text would waste the one thing that makes the answer checkable.
 */
function withCitations(
  answer: string,
  sources: AskSourceItem[],
  onCite: (index: number) => void,
): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let match: RegExpExecArray | null;
  CITATION.lastIndex = 0;
  while ((match = CITATION.exec(answer)) !== null) {
    const n = Number(match[1]);
    const source = sources[n - 1];
    if (match.index > last) out.push(answer.slice(last, match.index));
    if (source) {
      out.push(
        <button
          key={`cite-${match.index}`}
          type="button"
          className="cite"
          onClick={() => onCite(n)}
          aria-label={`Kaynak ${n}: ${source.subject}`}
        >
          [{n}]
        </button>,
      );
    } else {
      // No mail behind it: show the text, link nothing. A dead citation that
      // looks live is worse than one that reads as a stray bracket.
      out.push(match[0]);
    }
    last = match.index + match[0].length;
  }
  if (last < answer.length) out.push(answer.slice(last));
  return out;
}

export function AskScreen() {
  const [text, setText] = useState('');
  const [asked, setAsked] = useState<string | null>(null);
  const [result, setResult] = useState<AskAnswer | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [active, setActive] = useState<number | null>(null);
  const sourceRefs = useRef<Map<number, HTMLLIElement>>(new Map());
  const reduce = useReducedMotion();

  // A citation is only useful if it takes you to the mail it points at.
  useEffect(() => {
    if (active == null) return;
    const el = sourceRefs.current.get(active);
    if (!el) return;
    el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'nearest' });
    el.querySelector('a')?.focus({ preventScroll: true });
  }, [active, reduce]);

  const submit = async (question: string) => {
    const trimmed = question.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    setActive(null);
    setAsked(trimmed);
    try {
      setResult(await getAskSource().ask(trimmed));
    } catch (err) {
      setResult(null);
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  const liveMessage = busy
    ? 'Postan aranıyor.'
    : error != null
      ? describeLoadError(error)
      : result
        ? result.status === 'ok'
          ? `Yanıt hazır. ${result.sources.length} kaynak.`
          : 'Yanıt yok — ekranda gerekçesi yazıyor.'
        : '';

  return (
    <div className="page">
      <div className="page__inner">
        <div className="page__head">
          <div className="page__head-text">
            <h1 className="t-title">Postana sor</h1>
            <p className="t-caption">
              Gelen kutunda arar ve yalnızca bulduğu maillere dayanarak yanıtlar. Okur, yazmaz:
              buradan hiçbir mail gitmez, hiçbir akış başlamaz.
            </p>
          </div>
        </div>

        <form
          className="composer ask__form"
          onSubmit={(e) => {
            e.preventDefault();
            void submit(text);
          }}
        >
          <input
            className="ask__input"
            type="text"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Örn: kargolarım gelmiş mi?"
            aria-label="Posta kutuna sorman"
            maxLength={500}
            disabled={busy}
          />
          <button type="submit" className="btn" disabled={busy || text.trim().length === 0}>
            {busy ? <Loader size={15} aria-hidden className="spin" /> : <Send size={15} aria-hidden />}
            <span>{busy ? 'Aranıyor…' : 'Sor'}</span>
          </button>
        </form>

        {/* Only before the first question: once there is an answer these are noise. */}
        {!result && !busy && error == null && (
          <p className="ask__examples">
            <span className="t-caption">Örnek:</span>
            {EXAMPLES.map((example) => (
              <button
                key={example}
                type="button"
                className="btn btn--outline btn--sm"
                onClick={() => {
                  setText(example);
                  void submit(example);
                }}
              >
                {example}
              </button>
            ))}
          </p>
        )}

        <p className="sr-only" role="status" aria-live="polite">
          {liveMessage}
        </p>

        {error != null && (
          <LoadError error={error} onRetry={asked ? () => void submit(asked) : undefined} />
        )}

        {busy && (
          <div className="ask__skeleton">
            <div className="skeleton" style={{ height: 64 }} />
            <div className="skeleton" style={{ height: 96, opacity: 0.6 }} />
          </div>
        )}

        {!busy && result && <AnswerView result={result} active={active} onCite={setActive} refs={sourceRefs} />}
      </div>
    </div>
  );
}

function AnswerView({
  result,
  active,
  onCite,
  refs,
}: {
  result: AskAnswer;
  active: number | null;
  onCite: (index: number) => void;
  refs: React.MutableRefObject<Map<number, HTMLLIElement>>;
}) {
  const reduce = useReducedMotion();
  const ok = result.status === 'ok';
  const message = withoutQueryEcho(result.answer, result.query);

  return (
    <motion.section className="ans" aria-labelledby="ans-h" {...enterProps(0, reduce)}>
      <h2 className="sr-only" id="ans-h">
        Yanıt
      </h2>

      {/*
        The query first, then the answer. An answer whose search you cannot see is
        a claim; this is the receipt, and it is shown whether the search found
        anything or not — especially when it found nothing.
      */}
      <div className="ans__trace">
        <p className="ans__trace-line">
          <Search size={14} aria-hidden />
          {result.queryExplanation || 'Postanda şu sorguyla aradım.'}
        </p>
        <code className="t-mono ans__query">{result.query || '(sorgu yok)'}</code>
        <p className="ans__meta t-caption">
          <span>
            {result.querySource === 'llm' ? 'Sorguyu model yazdı' : 'Sorguyu kural çevirici yazdı'}
          </span>
          <span>{result.resultCount} sonuç</span>
          {result.mode && result.mode !== 'live' ? <span>{result.mode}</span> : null}
          <span>{formatTokens(result.tokens)} token</span>
          <span>{formatUsd(result.costUsd)}</span>
        </p>
      </div>

      {ok ? (
        <>
          {/* Honesty about the byline, before the text and not after it: whether a
              model wrote this changes how it should be read. */}
          {result.answerSource === 'listing' && (
            <p className="notice">
              <TriangleAlert size={15} aria-hidden />
              <span>
                Model şu an yanıt yazamadı; bulduğum mailleri olduğu gibi sıraladım. Aşağıdaki
                metin bir yorum değil, arama sonucu.
              </span>
            </p>
          )}

          <p className="ans__text">{withCitations(result.answer, result.sources, onCite)}</p>

          <div className="ans__srcs-head">
            <h3 className="t-label">Kaynaklar</h3>
            <span className="t-caption">Numaralar cevaptaki atıflarla aynı</span>
          </div>
          <ol className="ans__srcs">
            {result.sources.map((source, i) => {
              const n = i + 1;
              return (
                <li
                  key={source.id}
                  className={`ans__src ${active === n ? 'ans__src--active' : ''}`}
                  ref={(el) => {
                    if (el) refs.current.set(n, el);
                    else refs.current.delete(n);
                  }}
                >
                  <span className="ans__src-n" aria-hidden>
                    {n}
                  </span>
                  <span className="ans__src-main">
                    <a
                      className="ans__src-link"
                      href={source.url}
                      target="_blank"
                      rel="noreferrer"
                    >
                      <span className="sr-only">{n}. kaynak: </span>
                      {source.subject}
                      <ExternalLink size={13} aria-hidden />
                    </a>
                    <span className="ans__src-meta">
                      <Mail size={12} aria-hidden />
                      {person(source.from) || source.from || 'bilinmeyen gönderen'}
                      {source.at ? ` · ${formatDateTime(source.at)}` : ''}
                    </span>
                  </span>
                </li>
              );
            })}
          </ol>
        </>
      ) : (
        /*
          empty / unavailable / error: the server already wrote the honest
          sentence and the query is above it. Nothing is invented to fill
          the space, and no empty source list is drawn.
        */
        <p className={`notice ${result.status === 'error' ? 'notice--danger' : 'notice--warn'}`}>
          {result.status === 'empty' ? (
            <SearchX size={16} aria-hidden />
          ) : (
            <TriangleAlert size={16} aria-hidden />
          )}
          <span>{message}</span>
        </p>
      )}
    </motion.section>
  );
}

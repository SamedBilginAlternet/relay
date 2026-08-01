import { motion, useReducedMotion } from 'motion/react';
import { Bot, MessageSquare, TriangleAlert, User } from 'lucide-react';
import { Fragment, useEffect, useMemo, useRef } from 'react';
import { formatTime } from '../lib/format';
import type { RunPhase } from '../store/runStore';
import type { AgentMessage, Run } from '../types/api';
import { Composer } from './Composer';
import { EmptyState } from './EmptyState';
import { agentLabel } from '../lib/agents';
import { BrandMark, providerOf } from './BrandMark';
import '../styles/worklog.css';

type Props = {
  run: Run | null;
  phase: RunPhase;
  error: string | null;
  onSubmit?: (goal: string) => void;
  onRetry?: () => void;
  /** History/audit view: no composer, nothing to type. */
  readOnly?: boolean;
};

/*
  Naming is done here rather than at each screen's edge: Sohbet and Geçmiş print
  the same traffic, and one of them used to print it in Turkish while the other
  showed the backend's ids. The ids themselves stay untouched — `isToUser` below
  still matches on them, and the store keeps what the wire sent.
*/
const USER_TARGETS = new Set(['kullanıcı', 'user', 'sen', 'you']);

function isToUser(message: AgentMessage): boolean {
  return USER_TARGETS.has((message.toAgent ?? '').trim().toLowerCase());
}

/**
 * The machine half of a sentence, so the type layer can split it off.
 *
 * <p>DESIGN.md's first v3 rule — prose sans, machine fact mono — was applied
 * everywhere a fact is printed on its own (the tool chip, the parameter list,
 * the price). It could not reach the transcript, because there the two live in
 * the same line: "gmail.search tamam (156 ms), sonuç doğrulamaya gidiyor." is a
 * tool id, a duration and a Turkish clause. Set in one face it reads as one
 * thing, which is why an audit trail looked like chat.
 *
 * <p>The list below is deliberately closed, and every entry is a shape the
 * backend actually emits — checked against a live six-step run: a fenced span,
 * a parameter object, a quoted value, a link, the address that approved a step,
 * a dotted tool id, an issue key, a channel or issue reference, a duration, an
 * amount, a uuid, a `key=value` pair. Everything else stays prose. A false
 * positive sets a Turkish word in mono and is worse than a miss, so no rule
 * here guesses at a bare word.
 *
 * <p>Single quotes are deliberately absent from that list. Turkish attaches
 * suffixes with an apostrophe — `Jira'da`, `KAN-20'yi` — so a rule that opens a
 * span at one apostrophe closes it at the next word's, and "Jira'da 'RELAY'
 * anahtarlı" came out with `'da '` set in mono on the live trail.
 */
const MACHINE = new RegExp(
  [
    '`[^`]+`', //                                          fenced span (the fence is dropped)
    '\\{[^{}]{0,400}\\}', //                               a parameter object as one span
    '\\{"[^{}]{8,400}$', //                                …and one Json.preview cut before its brace
    'https?://\\S+', //                                    a link is never a sentence
    '[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+', //                 the address that approved a step
    '"[^"\\n]{1,80}"', //                                  a straight-quoted value
    '\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b',
    '\\b[a-z][a-z0-9_]+(?:\\.[a-z][a-zA-Z0-9_]*)+\\b', //  jira.createIssue
    '\\b[A-Z][A-Z0-9]{1,9}-\\d+\\b', //                    KAN-20
    '#[A-Za-z0-9][\\w.-]*', //                             #all-samed, #43
    '\\b\\d+(?:[.,]\\d+)?\\s?(?:ms|sn)\\b', //             156 ms, 7.2 sn
    '\\$\\d[\\d.,]*', //                                   $0.0078
    '\\b[a-z][a-zA-Z0-9_]*[:=][A-Za-z0-9_.\\-/]+', //      ok:true, id=42
  ].join('|'),
  'g',
);

export type Fragment = { text: string; machine: boolean };

/** A line cut into the two type layers, in order, with nothing dropped. */
export function splitMachine(line: string): Fragment[] {
  const out: Fragment[] = [];
  let last = 0;

  for (const hit of line.matchAll(MACHINE)) {
    const at = hit.index ?? 0;
    if (at > last) out.push({ text: line.slice(last, at), machine: false });
    const raw = hit[0];
    // A fence is punctuation for the reader, not part of the fact.
    out.push({ text: raw.startsWith('`') ? raw.slice(1, -1) : raw, machine: true });
    last = at + raw.length;
  }

  if (last < line.length) out.push({ text: line.slice(last), machine: false });
  return out;
}

/**
 * A parameter object long enough to be read as a document, not as a word.
 *
 * <p>`slack.postMessage çağrılıyor: {"text":"Günaydın! …"}` put six lines of
 * payload in the middle of a Turkish clause on the live trail — the sentence
 * and its cargo interleaved until neither could be skimmed. Past this length
 * the object leaves the line and takes a ground of its own; `{}` and small
 * id objects stay inline, where pulling them out would cost more than it buys.
 */
const BLOCK_PAYLOAD = 48;

/** The body of one row: sans by default, mono where the machine speaks. */
function Line({ text }: { text: string }) {
  return (
    <>
      {splitMachine(text).map((part, i) =>
        part.machine ? (
          <code
            key={i}
            className={
              part.text.startsWith('{') && part.text.length > BLOCK_PAYLOAD
                ? 'worklog__fact worklog__fact--block'
                : 'worklog__fact'
            }
          >
            {part.text}
          </code>
        ) : (
          <span key={i}>{part.text}</span>
        ),
      )}
    </>
  );
}

/** The provider's own mark where there is one, the crew's glyph where there is not. */
function Mark({ agent }: { agent: string }) {
  const provider = providerOf(agent.replace(/-agent$/, ''));
  return provider ? <BrandMark provider={provider} size={12} /> : <Bot size={12} aria-hidden />;
}

export function ChatPanel({ run, phase, error, onSubmit, onRetry, readOnly = false }: Props) {
  const reduce = useReducedMotion();
  const scrollRef = useRef<HTMLDivElement>(null);

  const messages = useMemo(
    () => [...(run?.messages ?? [])].sort((a, b) => a.createdAt.localeCompare(b.createdAt)),
    [run?.messages],
  );

  /*
    The step a message belongs to, for the boundary rows below. Every step-scoped
    message arrives with the step's id (AgentJournal.say), so the grouping key is
    the wire's own — no Turkish sentence is ever parsed to find where a step starts.
  */
  const stepsById = useMemo(() => new Map((run?.steps ?? []).map((s) => [s.id, s])), [run?.steps]);

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: reduce ? 'auto' : 'smooth' });
  }, [messages.length, phase, reduce]);

  const thinking =
    phase === 'creating' ||
    (run != null && (run.status === 'planning' || run.status === 'running'));

  /* One wave, capped: a live run keeps appending rows, and a row that waits a
     third of a second before it appears reads as a stall, not as a stagger. */
  const enter = (i: number) => ({
    initial: reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(6px)' },
    animate: { opacity: 1, transform: 'translateY(0px)' },
    transition: {
      duration: reduce ? 0.15 : 0.28,
      delay: reduce ? 0 : Math.min(i, 8) * 0.02,
      ease: [0.16, 1, 0.3, 1] as [number, number, number, number],
    },
  });

  return (
    <section className="chat-col" aria-label="Konuşma">
      <div className="chat-scroll" ref={scrollRef}>
        {phase === 'error' && (
          <div className="notice notice--danger">
            <TriangleAlert size={16} aria-hidden />
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: 8 }}>
              <span>{error ?? 'Bir şeyler ters gitti.'}</span>
              {onRetry && (
                <button type="button" className="btn btn--outline btn--sm" onClick={onRetry}>
                  Tekrar dene
                </button>
              )}
            </div>
          </div>
        )}

        {!run && phase !== 'error' && (
          <EmptyState
            Icon={MessageSquare}
            title="Konuşma boş"
            description="Aşağıya işini yaz — Relay bir akış kurar ve her adımı akış panelinde gösterir."
          />
        )}

        {run && (
          <ol className="worklog">
            {/*
              The ask is the first entry in the record, and it belongs to the
              human layer with the answers: those are what a person reads, the
              rows between them are what the machine did about it.
            */}
            <motion.li className="worklog__row worklog__row--goal" {...enter(0)}>
              <span className="worklog__who">
                <User size={12} aria-hidden />
                {agentLabel('user')}
              </span>
              <p className="worklog__line">{run.goal}</p>
              <time className="worklog__time" dateTime={run.createdAt}>
                {formatTime(run.createdAt)}
              </time>
            </motion.li>

            {messages.map((m, i) => {
              const toUser = isToUser(m);
              const prev = messages[i - 1];

              /*
                A step's first message opens a boundary row: "Adım 2 · Review
                bekleyen PR'ları getir". 20 rows for 4 steps read as one grey
                column until the reader re-derived the structure from the
                sentences; the boundary is that structure, drawn once. Global
                traffic (the plan, the closing summary) carries no stepId and
                gets no boundary — it belongs to the run, not to a step.
              */
              const boundary =
                m.stepId && m.stepId !== (prev?.stepId ?? null) ? stepsById.get(m.stepId) : undefined;

              /*
                Same minute as the row above → the stamp text yields (Slack's
                rule). The machine-readable time stays on every row: the visual
                column is deduplicated, the record is not.
              */
              const stamp = formatTime(m.createdAt);
              const shown = stamp !== formatTime(prev?.createdAt ?? run.createdAt);

              return (
                <Fragment key={m.id}>
                  {boundary && (
                    <motion.li className="worklog__step" {...enter(i + 1)}>
                      <span className="worklog__step-n">Adım {boundary.ordinal}</span>
                      <span className="worklog__step-title">{boundary.title}</span>
                    </motion.li>
                  )}
                  <motion.li
                    className={`worklog__row ${toUser ? 'worklog__row--tome' : 'worklog__row--a2a'}`}
                    aria-label={
                      toUser
                        ? `${agentLabel(m.fromAgent)} sana yazdı`
                        : `Ajan mesajı: ${agentLabel(m.fromAgent)} → ${agentLabel(m.toAgent)}`
                    }
                    {...enter(i + 1)}
                  >
                    <span className="worklog__who">
                      <Mark agent={m.fromAgent} />
                      {agentLabel(m.fromAgent)}
                    </span>
                    <p className="worklog__line">
                      {/* The route is machine routing and is set as such — and only
                          where there is one: a message to the person is addressed
                          to them, not forwarded to them. */}
                      {!toUser && (
                        <>
                          <span className="worklog__arrow" aria-hidden>
                            →
                          </span>
                          <span className="worklog__to">{agentLabel(m.toAgent)}</span>
                        </>
                      )}
                      <Line text={m.content} />
                    </p>
                    <time className="worklog__time" dateTime={m.createdAt} title={stamp}>
                      {shown ? stamp : null}
                    </time>
                  </motion.li>
                </Fragment>
              );
            })}
          </ol>
        )}

        {thinking && (
          <div className="chat-system" role="status">
            <span className="typing" aria-hidden>
              <i />
              <i />
              <i />
            </span>
            {phase === 'creating' || run?.status === 'planning'
              ? 'Kadro kuruluyor ve akış planlanıyor…'
              : 'Ajanlar çalışıyor — adımlar akış panelinde canlı akıyor'}
          </div>
        )}

        {run && run.status === 'awaiting_approval' && (
          <div className="chat-system" style={{ color: 'var(--warn)', borderColor: 'rgba(180,83,9,0.35)' }}>
            Onayın bekleniyor — akış panelinde “Onayla” veya “Reddet”.
          </div>
        )}

        {run && (run.status === 'done' || run.status === 'failed' || run.status === 'cancelled') && (
          <div className="chat-system">
            Akış bitti · {run.steps.length} adım · denetim izi akış panelinde duruyor
          </div>
        )}
      </div>

      {!readOnly && onSubmit && (
        <div className="chat-foot">
          <Composer onSubmit={onSubmit} busy={phase === 'creating'} />
        </div>
      )}
    </section>
  );
}

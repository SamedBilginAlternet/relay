import { motion, useReducedMotion } from 'motion/react';
import { ArrowRight, Bot, MessageSquare, Sparkles, TriangleAlert, User } from 'lucide-react';
import { useEffect, useMemo, useRef } from 'react';
import { formatTime } from '../lib/format';
import type { RunPhase } from '../store/runStore';
import type { AgentMessage, Run } from '../types/api';
import { Composer } from './Composer';
import { EmptyState } from './EmptyState';

type Props = {
  run: Run | null;
  phase: RunPhase;
  error: string | null;
  onSubmit?: (goal: string) => void;
  onRetry?: () => void;
  /** History/audit view: no composer, nothing to type. */
  readOnly?: boolean;
};

const USER_TARGETS = new Set(['kullanıcı', 'user', 'sen', 'you']);

function isToUser(message: AgentMessage): boolean {
  return USER_TARGETS.has((message.toAgent ?? '').trim().toLowerCase());
}

export function ChatPanel({ run, phase, error, onSubmit, onRetry, readOnly = false }: Props) {
  const reduce = useReducedMotion();
  const scrollRef = useRef<HTMLDivElement>(null);

  const messages = useMemo(
    () => [...(run?.messages ?? [])].sort((a, b) => a.createdAt.localeCompare(b.createdAt)),
    [run?.messages],
  );

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior: reduce ? 'auto' : 'smooth' });
  }, [messages.length, phase, reduce]);

  const thinking =
    phase === 'creating' ||
    (run != null && (run.status === 'planning' || run.status === 'running'));

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
          <motion.div
            className="msg-user"
            initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(6px)' }}
            animate={{ opacity: 1, transform: 'translateY(0px)' }}
            transition={{ duration: 0.25, ease: 'easeOut' }}
          >
            <div className="t-label" style={{ marginBottom: 4, display: 'flex', gap: 6, alignItems: 'center' }}>
              <User size={11} aria-hidden /> Sen
            </div>
            <p className="t-body">{run.goal}</p>
          </motion.div>
        )}

        {messages.map((m, i) =>
          isToUser(m) ? (
            <motion.div
              key={m.id}
              className="msg-agent"
              initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(8px)' }}
              animate={{ opacity: 1, transform: 'translateY(0px)' }}
              transition={{ duration: reduce ? 0.2 : 0.3, ease: [0.16, 1, 0.3, 1] }}
            >
              <div className="msg-agent__who">
                <span className="agent-badge agent-badge--accent">
                  <Bot size={12} aria-hidden />
                  {m.fromAgent}
                </span>
                <span className="t-caption">{formatTime(m.createdAt)}</span>
              </div>
              <p className="t-body">{m.content}</p>
            </motion.div>
          ) : (
            <motion.article
              key={m.id}
              className="a2a"
              aria-label={`Ajan mesajı: ${m.fromAgent} → ${m.toAgent}`}
              initial={reduce ? { opacity: 0 } : { opacity: 0, transform: 'translateY(8px)' }}
              animate={{ opacity: 1, transform: 'translateY(0px)' }}
              transition={{
                duration: reduce ? 0.2 : 0.3,
                delay: reduce ? 0 : Math.min(i, 4) * 0.02,
                ease: [0.16, 1, 0.3, 1],
              }}
            >
              <header className="a2a__route">
                <Sparkles size={12} aria-hidden />
                <span className="a2a__from">{m.fromAgent}</span>
                <ArrowRight size={12} aria-hidden />
                <span className="a2a__to">{m.toAgent}</span>
                <span style={{ marginLeft: 'auto' }}>{formatTime(m.createdAt)}</span>
              </header>
              <p className="a2a__body">{m.content}</p>
            </motion.article>
          ),
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
          <p className="t-caption" style={{ marginTop: 8 }}>
            Yeni bir iş yazdığında yeni bir akış başlar. Yazma adımları her zaman onayını bekler.
          </p>
        </div>
      )}
    </section>
  );
}

import { CornerDownLeft, Send } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';

type Props = {
  onSubmit: (goal: string) => void;
  busy?: boolean;
  placeholder?: string;
  variant?: 'landing' | 'inline';
  autoFocus?: boolean;
  value?: string;
};

/* Same sentence as the first landing example, on purpose: the empty box and the
   first suggestion are the two places a stranger learns what to ask for, and
   they used to teach two different products (KONUMLANDIRMA.md §3, A2). */
const PLACEHOLDER_LONG =
  'Ne yapmamı istersin? Örn: “Bugünkü maillerime bak, iş talebi gibi görünenler için Jira kaydı aç ve ilgili kanaldan ekibe haber ver.”';
/** A 3-line placeholder gets clipped in a 1-row textarea on small phones. */
const PLACEHOLDER_SHORT = 'Ne yapmamı istersin?';

export function Composer({
  onSubmit,
  busy = false,
  placeholder,
  variant = 'inline',
  autoFocus = false,
  value,
}: Props) {
  const [text, setText] = useState(value ?? '');
  const ref = useRef<HTMLTextAreaElement>(null);
  const [narrow, setNarrow] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(max-width: 560px)').matches,
  );

  useEffect(() => {
    const mq = window.matchMedia('(max-width: 560px)');
    const onChange = () => setNarrow(mq.matches);
    onChange();
    mq.addEventListener('change', onChange);
    return () => mq.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    if (value !== undefined) setText(value);
  }, [value]);

  useEffect(() => {
    if (autoFocus) ref.current?.focus();
  }, [autoFocus]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 160)}px`;
  }, [text]);

  const submit = () => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    onSubmit(trimmed);
    setText('');
  };

  return (
    <form
      className={`composer ${variant === 'landing' ? 'composer--landing' : ''}`}
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <textarea
        ref={ref}
        rows={1}
        value={text}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            submit();
          }
        }}
        placeholder={placeholder ?? (narrow ? PLACEHOLDER_SHORT : PLACEHOLDER_LONG)}
        aria-label="Yapılmasını istediğin iş"
        disabled={busy}
      />
      <span className="composer__hint desktop-only" aria-hidden>
        <CornerDownLeft size={12} /> Enter
      </span>
      <button type="submit" className="btn" disabled={busy || text.trim().length === 0}>
        <Send size={15} aria-hidden />
        <span>{busy ? 'Gönderiliyor…' : 'Çalıştır'}</span>
      </button>
    </form>
  );
}

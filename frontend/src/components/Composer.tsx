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

export function Composer({
  onSubmit,
  busy = false,
  placeholder = 'Ne yapmamı istersin? Örn: “Blocker etiketli Jira işlerini bul, durumlarını güncelle, ekibe Slack’ten özet at.”',
  variant = 'inline',
  autoFocus = false,
  value,
}: Props) {
  const [text, setText] = useState(value ?? '');
  const ref = useRef<HTMLTextAreaElement>(null);

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
        placeholder={placeholder}
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

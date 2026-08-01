import { LogOut, Search, Sparkles } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { Session } from '../lib/session';

/**
 * Who is signed in, and the way out.
 *
 * It is rendered into the existing header through a portal instead of editing
 * AppHeader: the header is being redesigned in parallel, and a portal keeps this
 * feature out of that file entirely. If no header is on screen (or it is replaced by
 * something without `.header`), the chip falls back to a floating button so signing
 * out is never unreachable.
 */
export function AccountMenu({ session, onNavigate }: { session: Session; onNavigate: (hash: string) => void }) {
  const [host, setHost] = useState<Element | null>(null);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    setHost(document.querySelector('header.header'));
  }, []);

  useEffect(() => {
    if (!open) return;
    const onClick = (event: MouseEvent) => {
      if (!ref.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const user = session.state.user;
  if (!user) return null;

  const initial = (user.displayName || user.email || '?').trim().charAt(0).toLocaleUpperCase('tr-TR');

  const menu = (
    <div className={host ? 'account' : 'account account--loose'} ref={ref}>
      <button
        type="button"
        className="account__chip"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        {user.avatarUrl ? (
          <img className="account__avatar" src={user.avatarUrl} alt="" />
        ) : (
          <span className="account__avatar account__avatar--letter" aria-hidden>
            {initial}
          </span>
        )}
        <span className="account__name">{user.displayName}</span>
      </button>

      {open ? (
        <div className="account__menu" role="menu">
          <div className="account__id">
            <b>{user.displayName}</b>
            <span>{user.email}</span>
            <span className="account__provider">
              {user.provider === 'google' ? 'Google ile giriş' : 'E-posta ile giriş'}
            </span>
          </div>
          {/*
            `Postana sor` used to be the second tab on the main bar. It reads the
            mailbox and answers; it starts no flow and asks for no approval, so
            it was describing a different product than the one being pitched
            (issue #59). It is not gone — this menu is where the things you reach
            on purpose live, next to the tour, and the address still works.
          */}
          <button
            type="button"
            className="account__item"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onNavigate('#/sor');
            }}
          >
            <Search size={15} aria-hidden /> Postana sor
          </button>
          <button
            type="button"
            className="account__item"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              onNavigate('#/onboarding');
            }}
          >
            <Sparkles size={15} aria-hidden /> Tanıtım turunu tekrar aç
          </button>
          <button
            type="button"
            className="account__item account__item--danger"
            role="menuitem"
            onClick={() => {
              setOpen(false);
              void session.signOut();
            }}
          >
            <LogOut size={15} aria-hidden /> Çıkış yap
          </button>
        </div>
      ) : null}
    </div>
  );

  return host ? createPortal(menu, host) : menu;
}

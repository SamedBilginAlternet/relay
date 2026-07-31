import type { ReactNode } from 'react';

/**
 * The frame every signed-out screen shares: white page, one card, the wordmark
 * above it. Styles live in styles/auth.css so this feature never edits global.css.
 */
export function AuthShell({
  title,
  lead,
  children,
  footer,
}: {
  title: string;
  lead: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <div className="auth">
      <div className="auth__inner">
        <div className="auth__brand" aria-hidden>
          <span className="auth__mark">
            <svg viewBox="0 0 64 64" width="15" height="15">
              <circle cx="17" cy="38" r="7" fill="currentColor" />
              <rect
                x="20"
                y="23.5"
                width="24"
                height="8"
                rx="4"
                fill="currentColor"
                transform="rotate(-14 32 27.5)"
              />
              <circle cx="47" cy="20" r="7" fill="currentColor" />
            </svg>
          </span>
          <span className="auth__word">
            <span className="auth__r">r</span>elay
          </span>
        </div>

        <div className="auth__card">
          <h1 className="auth__title">{title}</h1>
          <p className="auth__lead">{lead}</p>
          {children}
        </div>

        {footer ? <div className="auth__foot">{footer}</div> : null}
      </div>
    </div>
  );
}

/** Label + input + the error that belongs to this input, nothing else. */
export function AuthField({
  id,
  label,
  type,
  value,
  onChange,
  placeholder,
  autoComplete,
  error,
  hint,
  disabled,
}: {
  id: string;
  label: string;
  type: 'email' | 'password' | 'text';
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  autoComplete?: string;
  error?: string | null;
  hint?: string;
  disabled?: boolean;
}) {
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  return (
    <div className="auth__field">
      <label className="auth__label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className={error ? 'auth__input auth__input--bad' : 'auth__input'}
        type={type}
        value={value}
        placeholder={placeholder}
        autoComplete={autoComplete}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : hint ? hintId : undefined}
        onChange={(e) => onChange(e.target.value)}
      />
      {hint && !error ? (
        <p className="auth__hint" id={hintId}>
          {hint}
        </p>
      ) : null}
      {error ? (
        <p className="auth__error" id={errorId} role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** Google's own mark — a coloured G is what people look for; a generic icon is not. */
export function GoogleButton({ href, label }: { href: string; label: string }) {
  return (
    <a className="auth__btn auth__btn--google" href={href}>
      <svg width="17" height="17" viewBox="0 0 48 48" aria-hidden>
        <path
          fill="#4285F4"
          d="M45.12 24.5c0-1.56-.14-3.06-.4-4.5H24v8.51h11.84c-.51 2.75-2.06 5.08-4.39 6.64v5.52h7.11c4.16-3.83 6.56-9.47 6.56-16.17z"
        />
        <path
          fill="#34A853"
          d="M24 46c5.94 0 10.92-1.97 14.56-5.33l-7.11-5.52c-1.97 1.32-4.49 2.1-7.45 2.1-5.73 0-10.58-3.87-12.31-9.07H4.34v5.7C7.96 41.07 15.4 46 24 46z"
        />
        <path
          fill="#FBBC05"
          d="M11.69 28.18c-.44-1.32-.69-2.73-.69-4.18s.25-2.86.69-4.18v-5.7H4.34C2.85 17.09 2 20.45 2 24s.85 6.91 2.34 9.88l7.35-5.7z"
        />
        <path
          fill="#EA4335"
          d="M24 10.75c3.23 0 6.13 1.11 8.41 3.29l6.31-6.31C34.91 4.18 29.93 2 24 2 15.4 2 7.96 6.93 4.34 14.12l7.35 5.7c1.73-5.2 6.58-9.07 12.31-9.07z"
        />
      </svg>
      {label}
    </a>
  );
}

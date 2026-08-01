import { RefreshCw, TriangleAlert } from 'lucide-react';
import '../styles/screens.css';

/**
 * The one way a screen says "I could not read this".
 *
 * <p>Five screens used to fail in two different languages. Bugün, Geçmiş and
 * Bağlantılar printed a Turkish sentence with a retry button; Panel and
 * Politikalar printed whatever `Error.message` happened to hold, which for a
 * blocked request is the browser's own `Failed to fetch` — English, in the
 * middle of a Turkish product, with nothing to press.
 *
 * <p>So the exception stops here. `describeLoadError` maps a failure to a
 * sentence a person can act on, and anything that smells of the machine
 * (English exception text, an environment variable name, a base URL) is
 * replaced rather than shown: the reader cannot set `VITE_API_BASE_URL`, and
 * telling them to is worse than saying nothing.
 */

/** Same sentence the data layer already uses, so the screens read as one product. */
const NETWORK_TEXT = 'Sunucuya ulaşılamadı. Bağlantını ve API adresini kontrol et.';
const UNREADABLE_TEXT = 'Sunucudan beklenmeyen bir yanıt geldi. Tekrar dene.';
const GENERIC_TEXT = 'Bu ekranın verisi yüklenemedi. Tekrar dene.';

/** `fetch` rejects with these when the request never reached a server. */
const NETWORK = /failed to fetch|networkerror|network request failed|load failed|fetch failed|err_/i;
/** A 200 that was not JSON — usually an HTML error page from a proxy. */
const UNREADABLE = /unexpected token|not valid json|json\.parse|syntaxerror|unexpected end of/i;
/** Machine detail: env var names and URLs are never part of a user-facing sentence. */
const MACHINE = /VITE_[A-Z_]+|https?:\/\/|\blocalhost\b/;
/**
 * English is the machine talking. The product is Turkish end to end, so a
 * message carrying any of these is a proxy, a framework or a stack — never a
 * sentence written for the person reading it.
 */
const ENGLISH =
  /\b(error|failed|failure|invalid|unexpected|unauthorized|forbidden|not found|internal|timeout|refused|denied|bad request|exception|null|undefined)\b/i;

function statusOf(error: unknown): number | undefined {
  const status = (error as { status?: unknown } | null)?.status;
  return typeof status === 'number' ? status : undefined;
}

function messageOf(error: unknown): string {
  if (typeof error === 'string') return error.trim();
  if (error instanceof Error) return error.message.trim();
  return '';
}

function byStatus(status: number | undefined): string | null {
  if (!status) return null;
  if (status === 401) return 'Oturumun düşmüş görünüyor. Tekrar giriş yapman gerekiyor.';
  if (status === 403) return 'Bu bilgiyi görme yetkin yok.';
  if (status === 404) return 'Aradığın kayıt sunucuda bulunamadı.';
  if (status === 429) return 'Çok fazla istek gönderildi. Biraz bekleyip tekrar dene.';
  if (status >= 500) return 'Sunucu bu isteği yanıtlayamadı. Birazdan tekrar dene.';
  return 'İstek sunucuya ulaştı ama kabul edilmedi. Tekrar dene.';
}

/**
 * A failure, as a sentence in Turkish. Never returns the raw exception text of
 * a network or parse error, and never returns a string carrying machine detail.
 */
export function describeLoadError(error: unknown): string {
  const status = statusOf(error);
  const raw = messageOf(error);

  if (status === 0 || NETWORK.test(raw)) return NETWORK_TEXT;
  if (UNREADABLE.test(raw)) return byStatus(status) ?? UNREADABLE_TEXT;
  // The server writes its own Turkish sentences (validation, policy, quota) and
  // those are better than anything guessed from a status code — but only when
  // they are free of machine detail.
  if (raw && !MACHINE.test(raw) && !ENGLISH.test(raw)) return raw;
  return byStatus(status) ?? GENERIC_TEXT;
}

type Props = {
  error: unknown;
  /** Omitted only where there is genuinely nothing to retry. */
  onRetry?: () => void;
  /** Overrides the default "Tekrar dene" when the action is more specific. */
  retryLabel?: string;
};

export function LoadError({ error, onRetry, retryLabel }: Props) {
  return (
    <div className="notice notice--danger load-error" role="alert">
      <TriangleAlert size={16} aria-hidden />
      <div className="load-error__body">
        <span className="load-error__text">{describeLoadError(error)}</span>
        {onRetry && (
          <button type="button" className="btn btn--outline btn--sm load-error__retry" onClick={onRetry}>
            <RefreshCw size={14} aria-hidden />
            {retryLabel ?? 'Tekrar dene'}
          </button>
        )}
      </div>
    </div>
  );
}

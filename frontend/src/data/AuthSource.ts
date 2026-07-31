import { API_BASE_URL, RUN_SOURCE_KIND } from './index';
import type { SessionState, SessionUser } from '../types/auth';
import { ANONYMOUS } from '../types/auth';

/**
 * A refusal the form can act on: which input was wrong, in Turkish.
 * `field` matches the input name ('email' | 'password'), or null when the whole
 * attempt failed.
 */
export class AuthError extends Error {
  readonly field: string | null;
  readonly status: number;

  constructor(message: string, field: string | null = null, status = 400) {
    super(message);
    this.name = 'AuthError';
    this.field = field;
    this.status = status;
  }
}

/** Everything the login / onboarding screens are allowed to know about auth. */
export interface AuthSource {
  readonly kind: 'api' | 'mock';
  me(): Promise<SessionState>;
  login(email: string, password: string): Promise<SessionState>;
  register(email: string, password: string, displayName: string): Promise<SessionState>;
  logout(): Promise<void>;
  completeOnboarding(): Promise<SessionState>;
  /** Where "Google ile devam et" sends the browser, or null when unavailable. */
  googleStartUrl(): string | null;
}

type ServerSession = {
  authenticated?: boolean;
  user?: SessionUser | null;
  googleLogin?: boolean;
};

function normalize(body: ServerSession | null): SessionState {
  if (!body || !body.authenticated || !body.user) {
    return { authenticated: false, user: null, googleLogin: Boolean(body?.googleLogin) };
  }
  return {
    authenticated: true,
    user: {
      id: String(body.user.id ?? ''),
      email: String(body.user.email ?? ''),
      displayName: String(body.user.displayName ?? body.user.email ?? ''),
      avatarUrl: body.user.avatarUrl ?? null,
      provider: String(body.user.provider ?? 'password'),
      onboarded: Boolean(body.user.onboarded),
      createdAt: String(body.user.createdAt ?? ''),
    },
    googleLogin: Boolean(body.googleLogin),
  };
}

/** Real backend. The session is an HttpOnly cookie, so nothing is kept in JS. */
export class ApiAuthSource implements AuthSource {
  readonly kind = 'api' as const;
  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await fetch(`${this.baseUrl}${path}`, {
        // The session cookie is the whole point — it must ride along even when the
        // SPA is served from a different origin than the API.
        credentials: 'include',
        headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
        ...init,
      });
    } catch {
      throw new AuthError('Sunucuya ulaşılamadı. Bağlantını kontrol et.', null, 0);
    }
    let body: unknown = null;
    const text = await res.text();
    if (text) {
      try {
        body = JSON.parse(text);
      } catch {
        body = null;
      }
    }
    if (!res.ok) {
      const detail = body as { message?: string; field?: string | null } | null;
      throw new AuthError(
        detail?.message || `İstek başarısız (HTTP ${res.status})`,
        detail?.field ?? null,
        res.status,
      );
    }
    return body as T;
  }

  async me(): Promise<SessionState> {
    try {
      return normalize(await this.request<ServerSession>('/auth/me'));
    } catch (err) {
      // A backend that is down must not look like "you are signed out" forever —
      // but it also must not block the screen. Anonymous is the safe answer.
      if (err instanceof AuthError && err.status === 0) throw err;
      return ANONYMOUS;
    }
  }

  async login(email: string, password: string): Promise<SessionState> {
    return normalize(
      await this.request<ServerSession>('/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      }),
    );
  }

  async register(email: string, password: string, displayName: string): Promise<SessionState> {
    return normalize(
      await this.request<ServerSession>('/auth/register', {
        method: 'POST',
        body: JSON.stringify({ email, password, displayName }),
      }),
    );
  }

  async logout(): Promise<void> {
    await this.request<unknown>('/auth/logout', { method: 'POST', body: '{}' });
  }

  async completeOnboarding(): Promise<SessionState> {
    return normalize(
      await this.request<ServerSession>('/auth/onboarding/complete', {
        method: 'POST',
        body: '{}',
      }),
    );
  }

  googleStartUrl(): string {
    return `${this.baseUrl}/auth/google/start`;
  }
}

/**
 * Offline twin. `VITE_RUN_SOURCE=mock` is the demo-safe mode with no backend at all,
 * and the login/onboarding flow has to stay clickable there — so the "session" is a
 * localStorage row with the same validation messages as the server.
 */
export class MockAuthSource implements AuthSource {
  readonly kind = 'mock' as const;
  private static readonly KEY = 'relay.mock.session';

  private read(): SessionUser | null {
    try {
      const raw = localStorage.getItem(MockAuthSource.KEY);
      return raw ? (JSON.parse(raw) as SessionUser) : null;
    } catch {
      return null;
    }
  }

  private write(user: SessionUser | null): SessionState {
    try {
      if (user) localStorage.setItem(MockAuthSource.KEY, JSON.stringify(user));
      else localStorage.removeItem(MockAuthSource.KEY);
    } catch {
      /* private mode — the session just does not survive a reload */
    }
    return { authenticated: Boolean(user), user, googleLogin: false };
  }

  async me(): Promise<SessionState> {
    return this.write(this.read());
  }

  async login(email: string, password: string): Promise<SessionState> {
    const clean = email.trim().toLowerCase();
    if (!/^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/.test(clean)) {
      throw new AuthError('Geçerli bir e-posta adresi gir.', 'email', 400);
    }
    if (password.length < 8) {
      throw new AuthError('Parola en az 8 karakter olmalı.', 'password', 400);
    }
    const existing = this.read();
    return this.write(
      existing && existing.email === clean ? existing : this.newUser(clean, clean.split('@')[0] ?? clean),
    );
  }

  async register(email: string, password: string, displayName: string): Promise<SessionState> {
    const clean = email.trim().toLowerCase();
    if (!/^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/.test(clean)) {
      throw new AuthError('Geçerli bir e-posta adresi gir.', 'email', 400);
    }
    if (password.length < 8) {
      throw new AuthError('Parola en az 8 karakter olmalı.', 'password', 400);
    }
    return this.write(this.newUser(clean, displayName.trim() || (clean.split('@')[0] ?? clean)));
  }

  async logout(): Promise<void> {
    this.write(null);
  }

  async completeOnboarding(): Promise<SessionState> {
    const user = this.read();
    return this.write(user ? { ...user, onboarded: true } : null);
  }

  googleStartUrl(): null {
    return null;
  }

  private newUser(email: string, displayName: string): SessionUser {
    return {
      id: 'mock-user',
      email,
      displayName,
      avatarUrl: null,
      provider: 'password',
      onboarded: false,
      createdAt: new Date().toISOString(),
    };
  }
}

let instance: AuthSource | null = null;

export function getAuthSource(): AuthSource {
  if (!instance) {
    instance = RUN_SOURCE_KIND === 'api' ? new ApiAuthSource(API_BASE_URL) : new MockAuthSource();
  }
  return instance;
}

/**
 * Sign-in types — mirrors `GET /api/auth/me` on the backend.
 *
 * Scope note (deliberate): Relay is ONE shared workspace. A session says who is at
 * the keyboard; it does not scope the data. Every signed-in user sees the same
 * connections, runs and playbooks.
 */

export type SessionUser = {
  id: string;
  email: string;
  displayName: string;
  avatarUrl: string | null;
  /** 'password' | 'google' — how the account was first created. */
  provider: string;
  /** True once the onboarding tour has been finished (or skipped) on the server. */
  onboarded: boolean;
  createdAt: string;
};

export type SessionState = {
  authenticated: boolean;
  user: SessionUser | null;
  /** False when the server has no Google client id — the button is then hidden. */
  googleLogin: boolean;
};

export const ANONYMOUS: SessionState = { authenticated: false, user: null, googleLogin: false };

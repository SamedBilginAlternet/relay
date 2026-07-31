-- Relay — accounts and sessions.
--
-- Scope decision (deliberate, see docs/ARCHITECTURE.md §4): Relay is a SINGLE
-- SHARED WORKSPACE. A user row proves who is at the keyboard; it does not own
-- data. Connections, runs and policies stay global — everyone who signs in sees
-- the same ones. There is no user_id on runs/connections on purpose.

create table users (
    id            uuid primary key,
    email         varchar(320) not null unique,
    -- null for accounts that only ever signed in with Google
    password_hash text,
    display_name  varchar(160) not null,
    avatar_url    text,
    -- 'password' | 'google' — how the account was first created
    provider      varchar(32)  not null default 'password',
    onboarded_at  timestamptz,
    created_at    timestamptz  not null
);

-- Opaque session tokens. Only the SHA-256 of the cookie value is stored, so a
-- database dump cannot be replayed as a login.
create table sessions (
    id         uuid primary key,
    user_id    uuid        not null references users (id) on delete cascade,
    token_hash varchar(64) not null unique,
    created_at timestamptz not null,
    expires_at timestamptz not null
);

create index idx_sessions_user on sessions (user_id);
create index idx_sessions_expires on sessions (expires_at);

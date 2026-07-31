-- Relay — initial schema (ARCHITECTURE §4)

create table runs (
    id                uuid primary key,
    goal              text        not null,
    status            varchar(32) not null,
    created_at        timestamptz not null,
    finished_at       timestamptz,
    cost_tokens       bigint      not null default 0,
    cost_usd          double precision not null default 0,
    budget_usd        double precision,
    budget_overridden boolean     not null default false
);

create index idx_runs_created_at on runs (created_at desc);

create table steps (
    id            uuid primary key,
    run_id        uuid        not null references runs (id) on delete cascade,
    ordinal       integer     not null,
    title         text        not null,
    agent_role    varchar(64),
    tool_name     varchar(128),
    params        jsonb,
    status        varchar(32) not null,
    decision      varchar(16),
    reject_reason text,
    result        jsonb,
    error         text,
    started_at    timestamptz,
    finished_at   timestamptz,
    tokens        bigint      not null default 0,
    cost_usd      double precision not null default 0,
    attempts      integer     not null default 0
);

create index idx_steps_run on steps (run_id, ordinal);

create table agent_messages (
    id         uuid primary key,
    run_id     uuid        not null references runs (id) on delete cascade,
    step_id    uuid,
    from_agent varchar(64) not null,
    to_agent   varchar(64) not null,
    content    text        not null,
    created_at timestamptz not null
);

create index idx_messages_run on agent_messages (run_id, created_at);

-- config holds AES-GCM ciphertext (base64). Plaintext tokens never touch this table.
create table connections (
    id         uuid primary key,
    provider   varchar(32) not null unique,
    config     text        not null,
    created_at timestamptz not null
);

create table tool_policies (
    tool_name varchar(128) primary key,
    provider  varchar(32)  not null,
    mode      varchar(16)  not null
);

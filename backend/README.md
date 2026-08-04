# Relay — Backend

Java 21 · Spring Boot 3.4 · PostgreSQL + Flyway · SSE · Gradle wrapper

The backend plans a workflow from a goal, walks it with an agent crew (planner →
coordinator → tool agents → verifier), enforces a per-tool policy, meters cost and
streams every transition over SSE.

> **It always runs.** With no Groq keys it uses the deterministic `StubLlmClient`, and with
> provider tools answer from recorded fixtures. Live provider access is code-locked off
> while connections are workspace-global.
> No accounts, no network, no excuses on demo day.

---

## 1. Run it

```bash
export JAVA_HOME=~/jdk21
export PATH=$JAVA_HOME/bin:$PATH

# a Postgres has to exist somewhere
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/relay
export SPRING_DATASOURCE_USERNAME=relay
export SPRING_DATASOURCE_PASSWORD=relay
export APP_ENCRYPTION_KEY=$(openssl rand -base64 32)

./gradlew bootRun            # http://localhost:8080
```

Build and test:

```bash
./gradlew build              # compile + tests + jar
./gradlew test               # tests only (no database needed)
java -jar build/libs/relay-backend-0.1.0.jar
```

Flyway applies `src/main/resources/db/migration/V1__init.sql` at startup; Hibernate is on
`ddl-auto: validate`, so schema drift fails loudly instead of silently.

---

## 2. Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/relay` | In Docker the service is `db`: `jdbc:postgresql://db:5432/relay` |
| `SPRING_DATASOURCE_USERNAME` | `relay` | |
| `SPRING_DATASOURCE_PASSWORD` | `relay` | |
| `APP_ENCRYPTION_KEY` | dev fallback | AES-GCM key for `Connection.config`. **Set it everywhere real.** Any string or a 32-byte base64 blob |
| `GROQ_API_KEYS` | *(empty)* | Comma separated. Empty ⇒ stub LLM |
| `GROQ_MODEL` | `llama-3.3-70b-versatile` | |
| `GROQ_BASE_URL` | `https://api.groq.com/openai/v1` | |
| `GROQ_PRICE_INPUT` / `GROQ_PRICE_OUTPUT` | `0.59` / `0.79` | USD per million tokens, used by the cost meter |
| Tool mode | `replay` | Code-locked until connections have per-user ownership |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:4173` | Comma separated |
| `DEFAULT_BUDGET_USD` | `0.50` | Used when `POST /api/runs` omits `budgetUsd` |
| `GOOGLE_CLIENT_ID` | *(empty)* | Gmail + Calendar OAuth. Empty ⇒ those tools report `unavailable`, everything else still runs |
| `GOOGLE_CLIENT_SECRET` | *(empty)* | |
| `GOOGLE_REDIRECT_URI` | *(empty)* | Must match the console exactly, e.g. `http://localhost:8080/api/oauth/google/callback` |
| `GOOGLE_SUCCESS_REDIRECT` | *(empty)* | Where to send the browser after consent. Empty ⇒ the callback answers JSON |
| `BRIEF_TOOL_TIMEOUT_SECONDS` | `8` | Per-tool ceiling in the parallel fan-out of `/api/brief` |
| `BRIEF_CACHE_SECONDS` | `60` | `POST /api/brief/refresh` bypasses it |
| `BRIEF_TIMEZONE` | `Europe/Istanbul` | What "today" means |
| `BRIEF_DEFAULT_PROJECT_KEY` | `RELAY` | Jira project a `jira.createIssue` suggestion targets when the brief has no Jira issue to copy it from |

Server port is `8080`. No secret is committed anywhere in this repo.

---

## 3. Switching stub ↔ groq

```bash
# stub (default): deterministic, offline, $0.00 cost reported because nothing was billed
unset GROQ_API_KEYS

# groq: keys are used round-robin
export GROQ_API_KEYS=gsk_aaa...,gsk_bbb...,gsk_ccc...
```

A `429`/quota answer parks that key for 60s and rotates to the next one. When every key is
cooling down the call falls back to the stub and `GET /api/health` says so:

```json
{ "llm": { "provider": "stub", "degraded": true, "keysTotal": 3, "keysAvailable": 0,
           "keys": ["gsk_****aaaa"], "lastError": "all groq keys exhausted (groq HTTP 429)" } }
```

Keys are only ever printed masked.

## 4. Provider safety mode

Provider tools are code-locked to replay. The current schema stores one connection per
provider for the whole workspace; exposing live mode on a public deployment would let a
visitor use the account connected by somebody else. Live access must not return until
connections, runs and brief caches have per-user ownership.

Fixtures live in `src/main/resources/fixtures/<tool>.json`. `{{param}}` placeholders are
substituted from the actual call, so a replayed `slack.postMessage` echoes the real text the
agent composed. Recording a new one is just dropping a JSON file next to the others.

Credentials (entered through `PUT /api/connections`, stored AES-GCM encrypted):

| Provider | Config keys |
|---|---|
| `jira` | `baseUrl` (`https://x.atlassian.net`), `email`, `apiToken` |
| `slack` | `botToken` (`xoxb-…`) |
| `github` | `token` (fine-grained PAT), `login` *(optional — falls back to `@me` in search qualifiers)* |
| `google` | `refreshToken`, `accessToken`, `expiresAt` — **not typed by hand**, written by `GET /api/oauth/google/callback` |

Tokens are never logged and always masked in responses (`xoxb-****4d21`). Re-saving a masked
value keeps the stored secret.

---

## 5. API

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/health` | `{status, version, llm, tools}` |
| `POST` | `/api/runs` | `{goal, budgetUsd?}` → `202 {runId, status}`; work continues off-thread |
| `GET` | `/api/runs/{id}` | Full state: run + steps + messages |
| `GET` | `/api/runs/{id}/stream` | **SSE**, replays the backlog on subscribe |
| `POST` | `/api/runs/{id}/steps/{stepId}/approve` | |
| `POST` | `/api/runs/{id}/steps/{stepId}/reject` | `{reason}` — the reason is delivered to the agent |
| `POST` | `/api/runs/{id}/rerun` | Same goal, new run |
| `GET` | `/api/runs?page=0&size=20` | History, newest first |
| `GET`/`PUT` | `/api/connections` | Masked on the way out |
| `POST` | `/api/connections/{provider}/test` | Calls that provider's cheapest READ tool |
| `GET`/`PUT` | `/api/policies` | `PUT` takes a list of `{toolName, mode}` |
| `GET` | `/api/tools` | Registry + JSON schemas |
| `GET` | `/api/brief` | The Bugün screen, all sections in one call. Cached ~60s |
| `POST` | `/api/brief/refresh` | Same body, cache bypassed |
| `POST` | `/api/runs/from-suggestion` | `{cardId?, tool, params, label, context?, budgetUsd?}` → `202 {runId, id, status}` |
| `GET` | `/api/oauth/google/status` | `{configured, connected, scopes, …}` |
| `GET` | `/api/oauth/google/start` | `302` to Google consent; `503 google_not_configured` when the env vars are absent |
| `GET` | `/api/oauth/google/callback` | Code → tokens, stored on the encrypted `google` connection |

SSE event names: `run.planned`, `step.started`, `step.awaiting`, `step.finished`,
`agent.message`, `run.cost`, `run.finished`. All ids are UUID strings, all fields camelCase.

### `GET /api/brief` — shape

```jsonc
{
  "date": "2026-07-31T08:00:00Z",
  "priority": [                                  // AI cards, highest urgency first, max 5
    { "id": "gmail:18f2…", "source": "gmail|github|jira", "title": "…", "from": "Ayşe Yıldız",
      "kind": "bug_report|request|fyi|needs_reply|scheduling",
      "urgency": "high|normal|low", "summary": "Tek cümle — ne isteniyor",
      "suggestedActions": [ { "tool": "jira.createIssue", "label": "Jira ticket aç", "params": {} } ] }
  ],
  "inbox":    { "status": "ok", "reason": null, "items": [ /* rows */ ] },   // Gmail
  "work":     { "status": "ok", "reason": null, "items": [] },               // Jira
  "code":     { "status": "error", "reason": "GitHub kimlik bilgilerini kabul etmedi (HTTP 401) — …", "items": [] },
  "calendar": { "status": "unavailable", "reason": "Google Takvim bağlı değil — …", "items": [] }
}
```

A row is flat and display-only: `{id, title, subtitle, meta, url, tone}`, where `tone` is
`default | warn | danger | success` — a colour hint that never carries meaning alone, the text
says the same thing. Provider handles (`ref`) never appear on a row; they reach the UI only
inside `suggestedActions[].params`.

`status` is exactly one of `ok` (fetched), `unavailable` (integration not connected — the user
can fix it in settings) or `error` (connected but the call failed). `reason` is shown to the
user verbatim, so it is a single Turkish sentence built from the HTTP status — never a raw
provider message, which could echo back a URL or a token.

**Partial success is the contract.** Every READ tool runs in parallel on a virtual thread with
its own 8s timeout; one dead integration greys out one card and the other three still arrive.

Clicking a suggestion calls `POST /api/runs/from-suggestion`, which seeds a normal run with
that single step. It goes through the same coordinator, the same policy engine and the same
approval gate — a suggested WRITE still parks on the human. Suggestion ≠ action.

The optional `context` is what the card was about:

```jsonc
"context": { "itemId": "jira:KAN-42", "source": "jira", "title": "Ödeme retry politikası",
             "from": "Ayşe Demir", "summary": "İki gündür Blocked.", "url": "https://…" }
```

The run's goal is built from it — *"İlerlemeyi kayda yaz — Jira kaydı KAN-42 «Ödeme retry
politikası» (Ayşe Demir). Özet: …"* — because everything downstream reads that one sentence:
the specialist writes the parameters from it and the grounding check looks for the record key
in it. Omit it and the goal is the label alone, exactly as before. Each field is clipped
server-side: the goal is prompt text on every model call of the run, so it stays a headline,
never the item's full text.

A mail reply is seeded as **two** steps — `gmail.getMessage` then `gmail.createDraft` — and
the pre-written `subject`/`body` are dropped from the second one, so the answer is written
from the message that was just read instead of from a template. Everything else stays a
single step: a Jira comment does not need the issue's full text to say what it is going to
say. The approval gate is unmoved either way — the read runs, the write waits.

---

## 6. Layout

```
domain/            Run, Step, AgentMessage, Connection, ToolPolicy + enums — plain Java, zero framework imports
application/
  port/            LlmClient, Tool, ToolRegistry, RunRepository, ConnectionRepository,
                   PolicyRepository, EventPublisher, Clock
  orchestrator/    Planner, Coordinator, ToolAgent, Verifier, RunService, AgentJournal
  brief/           BriefService (parallel fan-out + partial success), InsightService (AI layer), BriefItem
  policy/          PolicyEngine (auto | ask | forbidden)
  cost/            CostMeter
  connection/      ConnectionService, Masking
  json/            Json, SchemaValidator
  view/            Views — the single source of the wire shape (REST and SSE share it)
infrastructure/
  llm/             GroqLlmClient (key rotation), StubLlmClient, RoutingLlmClient, ApiKeyPool
  tools/           JiraTool, SlackTool, GitHubTool, GmailTool, CalendarTool, GoogleTool,
                   ToolRegistryImpl, FixtureStore, AbstractTool
  google/          GoogleOAuth — code exchange, refresh-token storage, access-token renewal
  persistence/     JPA entities + repository adapters
  crypto/          AesGcmCipher
  sse/             SseEventPublisher
  config/          Spring wiring (the application layer carries no Spring annotations)
api/               REST controllers + SSE. No business logic.
```

Dependencies point inwards: `api → application → domain`, with `infrastructure` plugged into
the ports from `infrastructure/config`. Adding an integration means adding one `Tool`
`@Component` — nothing in the orchestrator changes.

## 7. Policy and cost

Defaults come from the tool's risk: `READ → auto`, `WRITE → ask`, `DESTRUCTIVE → forbidden`.
An override in `/api/policies` wins. A forbidden attempt is rejected, written to the
timeline as a `policy` agent message and recorded on the step (`rejectReason`).

Every LLM call is metered on the step and on the run. When the run total passes `budgetUsd`
the coordinator parks the next step, emits a `cost` agent message and waits; approving that
step raises the ceiling for the rest of the run.

The verifier checks each result against the goal and can send a step back at most twice
(`Step.MAX_RETRIES`); after that the step fails.

## 8. Tests

```bash
./gradlew test    # 74 tests, no database and no network required
```

Covers the policy decisions, cost accumulation and budget stop, Groq key rotation
(mock 429 → next key → cooldown → stub fallback), tool schema validation and replay, and a
full orchestrator run on the stub LLM with replayed tools including approval, rejection,
forbidden policy and the budget pause.

For the Bugün screen specifically: the insight layer dropping a suggestion that names an
unregistered tool, the brief keeping three sections alive while a fourth is `unavailable` or
`error`, failure reasons staying Turkish and leaking neither token nor stack trace, the row
shape the frontend renders, the 60s cache and its bypass, and a suggested WRITE still stopping
at the approval gate.

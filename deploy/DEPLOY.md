# Relay — deployment runbook

> Written for 3am. Every command is copy-pasteable. Nothing is implied.
> If you only have two minutes, read **§0 The one rule** and **§4B Coolify**.

Stack being deployed: **Java 21 / Spring Boot 3.4** API (REST + SSE) ·
**PostgreSQL 16** (Flyway migrates on API startup) · **React + Vite** SPA served
by nginx. Everything the operator can set is in `.env.example`.

**Two ways to deploy, same compose file:**

| Path | Use when | Section |
|---|---|---|
| **Coolify** (default) | normal deploys, git-push driven | §1, §3, **§4B**, §5, §6, §7, §8 |
| Manual `docker compose` | Coolify is down, or you are debugging | §1, §2, §3, §4, §5, §6, §7, §8 |

Both paths produce identical containers on identical ports. §6–§8 (Caddy and
verification) are shared and are **not optional for either path** — Coolify does
not touch the edge.

---

## 0. The one rule

**The VPS has exactly one process on ports 80 and 443: another project's Caddy.**

- Relay must **never** bind 80 or 443.
- The Coolify proxy stays **OFF**. If you turn it on, it grabs 80/443 and takes
  every site on the box down, not just this one. This is a **server-level**
  setting in Coolify (Servers → the server → Proxy), not a per-app one.
- Never set a **Domain / FQDN** on the Relay resource in Coolify, and never
  define `SERVICE_FQDN_*` / `SERVICE_URL_*`. Coolify reacts to those by
  generating proxy labels and expecting to serve the traffic itself. Leave the
  field empty; the domain lives in the Caddy block instead.
- Relay publishes on **all interfaces** on its own high ports, and Caddy
  reverse-proxies the domain to them.

**Why not `127.0.0.1:`** — the intuitive, "safer" binding is the one that
breaks: n11's Caddy runs *inside a container* and reaches the host through
`host.docker.internal`, i.e. the **host gateway**, not loopback. A loopback
binding is invisible from there and every request answers **502**. Accepted
trade-off: the ports are also reachable directly at
`http://187.124.7.138:<PORT>` with **no TLS**. Nothing goes on those ports that
must not be seen in the clear.

| Component | Container port | Host port | Public route |
|---|---|---|---|
| `web` (nginx + SPA) | 80 | `0.0.0.0:8086` | `https://APP_DOMAIN/` |
| `api` (Spring Boot) | 8080 | `0.0.0.0:8087` | `https://APP_DOMAIN/api/*` (incl. SSE) |
| `db` (postgres 16) | 5432 | **not published** | none |

Both host ports are `WEB_PORT` / `API_PORT` in `.env`. Change them if another
project already holds them — and change the Caddy block to match.

Ports already claimed on this box: **8081** Faruk, **8082** KPSS Atlas,
**8083** olaylar-arsivi, **8084 + 8085** Rung. Relay takes the next free pair.

---

## 1. Prerequisites

Run these on the server before anything else.

```bash
# Docker Engine + compose v2 plugin
docker --version
docker compose version          # must print v2.x or newer

# The two ports Relay wants must be free — LOOK, do not assume
for p in $(seq 8086 8099); do ss -tln | grep -q ":$p " || echo "BOS: $p"; done
# expected: "BOS: 8086" and "BOS: 8087" appear in the list

# Caddy must already be running and owning 80/443
sudo ss -tlnp | grep -E ':(80|443)\b'

# Where is Caddy? (you need this in §6)
docker ps --format '{{.Names}}\t{{.Image}}' | grep -i caddy

# DNS must already point at this box, or Caddy cannot issue a certificate
dig +short relay.samedbilgin.com
curl -s ifconfig.me; echo
```

Also needed: ~3 GB free disk for the JDK build layers and the Gradle
dependency cache (`df -h /var/lib/docker`). The runtime image itself is small —
`eclipse-temurin:21-jre-alpine` plus one fat jar.

---

## 2. Clone — *manual path only*

Coolify clones the repo itself; skip to §3 if you are deploying via Coolify.

```bash
sudo mkdir -p /opt/relay && sudo chown "$USER" /opt/relay
git clone <REPO_URL> /opt/relay
cd /opt/relay
```

Everything in §4 assumes `cd /opt/relay`.

---

## 3. Configuration values

The same variables are used by both paths — only *where you type them* differs:

- **Coolify:** paste them into the resource's *Environment Variables* tab
  (there is a **Bulk Edit** box that accepts `.env` syntax verbatim). Coolify
  writes the `.env` file next to the compose file at deploy time.
- **Manual:** create the file on disk.

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 32 | tr -d '/+=' | cut -c1-32     # POSTGRES_PASSWORD
openssl rand -base64 32                                # APP_ENCRYPTION_KEY
${EDITOR:-nano} .env
```

Minimum edits:

| Variable | Set it to |
|---|---|
| `APP_DOMAIN` | the real domain, e.g. `relay.samedbilgin.com` (no scheme, no slash) |
| `POSTGRES_PASSWORD` | the first string you just generated — **required** |
| `APP_ENCRYPTION_KEY` | the second one, ≥ 32 chars — **required** |
| `CORS_ALLOWED_ORIGINS` | `https://<APP_DOMAIN>` |
| `GROQ_API_KEYS` | comma-separated Groq keys. Empty = the API runs on `StubLlmClient` |
| `GROQ_MODEL` | e.g. `llama-3.3-70b-versatile` |
| `TOOLS_MODE` | `stub` for a side-effect-free demo, `live` for real Jira/Slack calls |
| `WEB_PORT` / `API_PORT` | only if 8086/8087 were taken in §1 |
| `VITE_RUN_SOURCE` | `api` — `mock` ships the fixture demo and looks deceptively fine |
| `VITE_API_BASE_URL` | leave **empty** — production is same-origin behind Caddy |

**The two required variables are enforced in the container, not in the compose
file.** `${VAR:?message}` is banned here: Coolify parses it like
`${VAR:-default}` and pastes the *error message in as the value*, so a missing
password would silently become a password that is written in this repository.
Instead `deploy/backend-entrypoint.sh` refuses to start and prints, e.g.:

```
[entrypoint] FATAL: APP_ENCRYPTION_KEY is empty. Generate one: openssl rand -base64 32
```

Sanity check — this renders the fully-resolved config:

```bash
docker compose -f docker-compose.prod.yml config | head -n 40
```

Then confirm the published ports are **not** loopback-bound:

```bash
docker compose -f docker-compose.prod.yml config | grep -A3 published
# NO entry may show  host_ip: 127.0.0.1  — that binding 502s behind Caddy
```

---

## 4. Build and start — *manual path*

Deploying through Coolify? Skip to **§4B**.

```bash
cd /opt/relay
docker compose -f docker-compose.prod.yml build          # 3–8 min cold (Gradle)
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
```

Wait until `db`, `api` and `web` all show `(healthy)`. `api` will not start
until `db` is healthy — that is intentional. `web` starts regardless of the API,
so the SPA shell still renders during an API outage instead of 502-ing.

```bash
watch -n2 'docker compose -f docker-compose.prod.yml ps'
```

The api healthcheck has a **60 s** start period: JVM boot plus the Flyway
migration on a cold database. It is not stuck until it stays `starting` past
that.

Note: the build needs **BuildKit** (default in compose v2+ and in Coolify). The
build-context filters live in `deploy/*.Dockerfile.dockerignore`, which only
BuildKit reads. If you ever build with the legacy builder, set
`DOCKER_BUILDKIT=1` first.

### What the backend image does

`deploy/backend.Dockerfile` is two stages:

1. **build** — `eclipse-temurin:21-jdk-alpine`. Runs `./gradlew --no-daemon
   bootJar -x test` (or `./mvnw package` if the backend ever switches wrappers),
   then normalises the artifact to `/out/app.jar`, rejecting the Gradle
   `*-plain.jar` / Maven `original-*.jar` decoys and asserting the jar really
   contains `BOOT-INF/`. Dependencies are cached across builds through BuildKit
   cache mounts on `/root/.gradle` and `/root/.m2`.
2. **runtime** — `eclipse-temurin:21-jre-alpine`, non-root (`relay`, uid 10001),
   `HEALTHCHECK` on a plain **GET** `/api/health`.

The healthcheck is a plain GET on purpose. busybox `wget --spider` sends a
**HEAD**, and a GET-only `@GetMapping("/api/health")` answers HEAD with **405**
→ the container is permanently unhealthy while the app is perfectly fine. And
busybox `wget` has **no** `--tries`/`-t` flag. Both were learned the hard way;
do not "improve" that line.

---

## 4B. Deploy via Coolify

### First-time setup

1. **Server → Proxy must be `None` / stopped.** Check this *before* creating the
   resource: Coolify → Servers → *your server* → Proxy. If Traefik or Coolify's
   own Caddy is running there, it owns :80/:443 and the other project's Caddy is
   already broken. Stop it.
2. Coolify → *your project* → **+ New Resource** → **Docker Compose** →
   *Private Repository (with GitHub App)* or *Deploy Key*.
3. Fill in:

   | Field | Value |
   |---|---|
   | Repository | the Relay repo |
   | Branch | `main` |
   | Base Directory | `/` |
   | Docker Compose Location | `/docker-compose.prod.yml` |

4. **Domains / FQDN: leave EMPTY.** This is the single most important field on
   the page. An FQDN here makes Coolify generate proxy labels and try to serve
   the site itself, which collides with the shared Caddy. The domain is
   configured in the Caddy block (§6) and nowhere else.
5. **Environment Variables** tab → *Bulk Edit* → paste your filled-in `.env`
   contents (§3). Toggle *Is Secret* on `POSTGRES_PASSWORD`,
   `APP_ENCRYPTION_KEY` and `GROQ_API_KEYS`. Coolify pre-fills the variable
   names it detected in the compose file; make sure every one has a value.
   **Coolify will NOT fail loudly if you forget one** — the compose file
   deliberately avoids `${VAR:?message}` (see §3). The api container is what
   fails, with a `[entrypoint] FATAL:` line in its log.
6. Coolify may warn that the compose file publishes ports. That is correct and
   intended: published on all interfaces so the Caddy CONTAINER can reach them
   via `host.docker.internal`. A `127.0.0.1:` binding here would 502.
7. **Deploy**.

### What Coolify does and does not manage

| | |
|---|---|
| Manages | clone, build (BuildKit), `compose up`, restarts, env injection, logs, redeploy history |
| Does **not** manage | the Caddy site block (§6–§7), TLS, DNS |

Container names are left to Coolify on purpose — `docker-compose.prod.yml`
declares no `container_name`. Always address services by *service name*
(`api`, `web`, `db`), never by a guessed container name.

The volume is pinned as `${COMPOSE_PROJECT_NAME:-relay}-db-data` so the database
survives the resource being deleted and recreated in Coolify. Deleting the
resource in Coolify with *"delete volumes"* checked still destroys it.

### Where Coolify put everything (you need this for logs and §5)

```bash
# from any Relay container, ask Docker where its compose project lives
CID=$(docker ps --filter "label=com.docker.compose.service=api" \
                --filter "label=com.docker.compose.project=relay" -q | head -n1)
APPDIR=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' "$CID")
echo "$APPDIR"          # e.g. /data/coolify/applications/<uuid>
cd "$APPDIR"
ls -la                  # docker-compose.prod.yml + the .env Coolify generated
```

From that directory every `docker compose -f docker-compose.prod.yml …` command
in this runbook works exactly as written.

### Redeploying

Push to `main` (if auto-deploy is on) or press **Redeploy** in Coolify.
Changing a `VITE_*` value needs a **rebuild**, not a restart (§9).

---

## 5. Database migrations

**There is no migrator container.** Flyway runs inside the API at startup: the
api waits for `db` to be healthy (`depends_on: service_healthy`), applies any
pending migration from `backend/src/main/resources/db/migration`, and only then
starts serving. That is why the api healthcheck's `start_period` is 60 s.

Consequences worth knowing at 3am:

- A **failed migration is a failed boot.** The api container exits or restarts
  in a loop and `/api/health` never turns green. The cause is in the api log,
  not in the web log.
- A migration is applied by *whichever* instance boots first. There is one
  instance here, so no locking surprises.
- Rolling back means restoring a dump (§10c) or shipping a compensating
  migration. Flyway does not undo anything on the community edition.

Inspect what the database thinks it has:

```bash
docker compose -f docker-compose.prod.yml exec db \
    psql -U relay -d relay -c 'SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;'
```

Take a backup before a release that touches existing data:

```bash
mkdir -p backups
docker compose -f docker-compose.prod.yml --profile backup run --rm backup
```

Verify locally, before involving Caddy:

```bash
curl -sS http://127.0.0.1:8087/api/health ; echo
# -> {"status":"ok","version":"0.1.0","llm":"groq"}   (llm: "stub" if no keys)
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8086/
# -> 200
```

**If both of those pass, the containers are fine and any remaining problem is
in Caddy.**

---

## 6 + 7. Add the Caddy site block — *in the n11 repo, not on the server*

**Do not edit the Caddyfile on the droplet.** It is CI-managed: the n11 deploy
copies it to `/opt/n11/Caddyfile`, so any hand edit is wiped on the next deploy.

The file you edit lives in the **n11 repository**:

```
infra/digitalocean/Caddyfile
```

1. Open `deploy/Caddyfile.snippet` in this repo and copy the block between the
   `BURADAN KOPYALA` / `BURAYA KADAR` markers.
2. Paste it next to the other project blocks at the end of that Caddyfile
   (`faruktekstil` / `kpssatlas` / `olay` / `rung` live there — same section,
   same style: `encode zstd gzip` plus a `log` block).
3. Adjust the domain and the two ports only if yours differ from
   `relay.samedbilgin.com` / 8086 / 8087.
4. Commit, push, and **deploy n11**. Caddy obtains the TLS certificate itself.

The block is three `handle`s, and the order is load-bearing:

```
handle /api/runs/*/stream   -> api, NO encode, flush_interval -1   (SSE)
handle /api/*               -> api, encode zstd gzip
handle                      -> web, encode zstd gzip
```

Four things that will bite you:

* **The SSE `handle` must come first.** Caddy uses the first matching `handle`;
  put it after `/api/*` and the stream silently takes the compressed route.
* **`encode` must NOT be inside the SSE block.** Compressing an event stream
  buffers frames: the run appears frozen and then every event lands at once.
  `flush_interval -1` pushes each write straight through.
* `reverse_proxy host.docker.internal:PORT` — **never** `127.0.0.1`. Caddy runs
  in a container; loopback there is Caddy's own, not the host's. Result: 502.
  It resolves through `extra_hosts: host-gateway` in n11's compose.
* The `log {` brace must be on **its own line**. Written as
  `log { output stdout` on one line, Caddy fails with *Unexpected next token*.

Validate locally before pushing:

```bash
caddy validate --config infra/digitalocean/Caddyfile      # in the n11 repo
```

---

## 8. Verify

```bash
D=relay.samedbilgin.com   # your APP_DOMAIN

# 1. API through the edge
curl -sS https://$D/api/health ; echo
# -> {"status":"ok","version":"...","llm":"groq"|"stub"}

# 2. SPA shell, and its cache policy
curl -sSI https://$D/ | grep -iE 'HTTP/|cache-control'
# -> HTTP/2 200 ... cache-control: no-cache, must-revalidate

# 3. Hashed asset cached forever
ASSET=$(curl -sS https://$D/ | grep -oE '/assets/[^"]+\.js' | head -n1)
curl -sSI "https://$D$ASSET" | grep -i cache-control
# -> cache-control: public, max-age=31536000, immutable

# 4. Deep link falls back to the SPA (history fallback)
curl -sS -o /dev/null -w '%{http_code}\n' https://$D/runs/anything
# -> 200

# 5. SSE headers — the route that silently breaks
curl -sSI -H 'Accept: text/event-stream' \
     https://$D/api/runs/00000000-0000-0000-0000-000000000000/stream | head -n5
# -> content-type: text/event-stream
# -> there must be NO `content-encoding:` header. If there is one, `encode`
#    leaked into the SSE handle (or the request fell through to /api/*).

# 6. SSE live stream — start a run in the UI, then watch its events land
RUN_ID=<paste a real run id>
curl -N -H 'Accept: text/event-stream' https://$D/api/runs/$RUN_ID/stream
# events must arrive ONE BY ONE as the run progresses. If nothing appears for
# ~30s and then everything lands at once, the stream is being buffered.

# 7. Relay's ports are published on all interfaces, deliberately
sudo ss -tlnp | grep -E ':(8086|8087)\b'
# -> 0.0.0.0:8086 / 0.0.0.0:8087. If you see 127.0.0.1 here, Caddy will 502.
```

Then open `https://$D`, give the agent a goal, and watch the step timeline fill
in live.

---

## 9. Updating an existing deployment

**Coolify:** push to `main` (auto-deploy) or press **Redeploy**. Flyway applies
any new migration during the api's startup — nothing else to run. Same ports,
same Caddy block, no edge work.

Take a backup first if the release migrates existing data, and remember that
changing a `VITE_*` variable in Coolify requires a **rebuild**, not a restart —
use *Redeploy*, and untick *Use build cache* if the value appears not to have
taken.

**Manual:**

```bash
cd /opt/relay
git pull
docker compose -f docker-compose.prod.yml --profile backup run --rm backup   # if the release migrates data
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs --tail=50 api   # watch Flyway
```

Changing `VITE_*` values requires a rebuild of `web`, not a restart — Vite bakes
them in at build time:

```bash
docker compose -f docker-compose.prod.yml build web && \
docker compose -f docker-compose.prod.yml up -d web
```

---

## 10. Rollback

### 10a. Roll back the app (fastest, no data loss)

**Coolify:** resource → **Deployments** → find the last green deployment →
**Redeploy** that commit. This is the fastest rollback available; use it first.
If the bad release also migrated the database, do §10b as well.

**Manual:**

```bash
cd /opt/relay
git log --oneline -n 10               # pick the last known-good commit
git checkout <GOOD_SHA>
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
curl -sS http://127.0.0.1:8087/api/health ; echo
```

If the previous images are still on the box you can skip the rebuild entirely:

```bash
docker images | grep relay-           # find the previous APP_VERSION tag
APP_VERSION=0.1.0 docker compose -f docker-compose.prod.yml up -d --no-build
```

### 10b. Roll back the database schema

Flyway (community) has no `undo`. An older app image against a newer schema
usually still runs — the extra column is simply unused. If it genuinely cannot,
your options are, in order of preference:

1. ship a forward "compensating" migration and redeploy,
2. restore the pre-release dump (§10c).

### 10c. Restore from a dump (last resort — destroys current data)

```bash
docker compose -f docker-compose.prod.yml stop api web
docker compose -f docker-compose.prod.yml exec -T db \
    pg_restore -U relay -d relay --clean --if-exists < backups/relay-YYYYMMDD-HHMMSS.dump
docker compose -f docker-compose.prod.yml start api web
```

### 10d. Roll back the Caddy change

The Caddyfile is CI-managed from the **n11 repo** — roll back there, not on the
droplet (a hand edit is overwritten by the next n11 deploy anyway).

```bash
# in the n11 repository
git revert <commit-that-added-the-relay-block>
git push
# then deploy n11
```

Removing the `relay.samedbilgin.com` block affects only that domain; every other
site block keeps serving.

### 10e. Full stop (Relay only — other projects untouched)

**Coolify:** resource → **Stop**. This leaves the named volume alone.

**Manual:**

```bash
docker compose -f docker-compose.prod.yml down          # keeps the data volume
docker compose -f docker-compose.prod.yml down -v       # DELETES the database
```

`down -v` is unrecoverable without a dump. Read the command twice.
The same applies to Coolify's *Delete resource* dialog: leave the
"delete volumes" checkbox **unticked** unless you mean it.

---

## 11. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| api log: `[entrypoint] FATAL: SPRING_DATASOURCE_PASSWORD is empty` | `POSTGRES_PASSWORD` never got a value in Coolify's env tab | Set it (§3). The compose file cannot enforce it — `${VAR:?}` is banned because Coolify turns the message into the value |
| api log: `[entrypoint] FATAL: APP_ENCRYPTION_KEY is ... chars, needs >= 32` | placeholder or short key | `openssl rand -base64 32` |
| api log: `[entrypoint] WARN: GROQ_API_KEYS is empty` and the UI says "stub" | no Groq keys configured | Paste a comma-separated list into `GROQ_API_KEYS`, redeploy. Rotation and cooldown are automatic (ARCHITECTURE.md §7) |
| `Error starting userland proxy: bind: address already in use` on `up` | Another project already holds 8086/8087 | `for p in $(seq 8086 8099); do ss -tln \| grep -q ":$p " \|\| echo "BOS: $p"; done` → pick free ports, edit `WEB_PORT`/`API_PORT` in `.env`, update the Caddy block, redeploy n11 |
| `up` fails with `port is already allocated` but `ss` shows nothing | A stale container from a previous run | `docker compose -f docker-compose.prod.yml down && docker container prune -f` |
| Caddy **502** on `/` | `web` container down, or wrong port in the site block | `docker compose -f docker-compose.prod.yml ps` → then `curl -I http://127.0.0.1:8086/`. If that works, the Caddy block points at the wrong port |
| Caddy **502** on everything, `curl http://127.0.0.1:8086` works on the host | Ports were bound to `127.0.0.1` in the compose file | Publish on all interfaces. Caddy is a container; `host.docker.internal` is the host gateway, and loopback bindings are unreachable from it |
| Caddy **502** on `/api/*` only | `api` unhealthy (usually the DB or a failed migration) | `docker compose -f docker-compose.prod.yml logs --tail=100 api` |
| **SSE stream hangs**, then all events arrive at once when the run finishes | `encode` is applied to the SSE route, or the request fell through to the `/api/*` handle | The `handle /api/runs/*/stream` block must come **before** `handle /api/*`, must have **no** `encode`, and must keep `flush_interval -1` (§6) |
| SSE reconnects every ~30–60 s | idle/read timeout somewhere in the chain | Keep `flush_interval -1`; make sure the backend emits a heartbeat/comment frame; do not add a `transport http { read_timeout }` to that route |
| SSE responds `text/html` instead of `text/event-stream` | the request reached the **web** container | nginx returns 404 for `/api/` with `api is served by the edge` — the Caddy route is missing or misordered |
| Certificate never issues, Caddy log says `no such host` / challenge failed | DNS not pointing here yet, or something else on 80 | `dig +short $APP_DOMAIN` vs `curl ifconfig.me`; `sudo ss -tlnp \| grep :80` must show only Caddy |
| Two processes fighting over 80/443 | Coolify proxy got switched on | Coolify → Servers → *server* → Proxy → stop it / set `None`. Check with `sudo ss -tlnp \| grep -E ':(80\|443)'` — only the shared Caddy may appear |
| Every other site on the box went down right after a Coolify deploy | An FQDN was set on the Relay resource (or `SERVICE_FQDN_*` was defined), so Coolify started its proxy | Clear the Domains/FQDN field, remove the variable, stop the Coolify proxy, redeploy. The domain belongs in the Caddy block only |
| Coolify deploy is green but the site is unreachable | The Caddy block was never added, or n11 was not redeployed | §6–§7. Coolify never touches the edge |
| Changed a `VITE_*` variable in Coolify, app behaves the same | Vite bakes those in at build time and the build cache was reused | Redeploy with *Use build cache* unticked |
| SPA shows fake runs / fixture data in production | `VITE_RUN_SOURCE=mock` was baked into the image | Set it to `api` and **rebuild** `web` |
| Cannot find the compose file / `.env` on the server under Coolify | Coolify deploys into `/data/coolify/applications/<uuid>` | Derive it from the container label — see §4B "Where Coolify put everything" |
| Redeploy fails with `container name ... is already in use` | A `container_name:` was reintroduced into the compose file | Remove it. `docker-compose.prod.yml` deliberately declares none; Coolify names containers itself |
| api log: `Connection to db:5432 refused` / `UnknownHostException: db` | `SPRING_DATASOURCE_URL` overridden with `localhost` | Host must be `db` (the compose service name). The entrypoint rejects localhost outright — inside the api container it is the api itself |
| api log: `FATAL: password authentication failed for user "relay"` | `.env` password changed after the volume was created — postgres only reads `POSTGRES_PASSWORD` on **first** init | Set `.env` back, or `ALTER USER relay WITH PASSWORD '...'` via `docker compose -f docker-compose.prod.yml exec db psql -U relay -d relay`, or (data loss) `down -v` and start over |
| `db` reports healthy but the api cannot authenticate | someone replaced the healthcheck with `pg_isready` or `psql -h 127.0.0.1` | Restore `psql -h db`: the image's `pg_hba.conf` trusts `127.0.0.1/32` before it checks any password, so loopback checks are always false positives |
| api restarts in a loop, healthcheck never green | Flyway migration failure, or still booting | `logs --tail=100 api`; the JVM + migration needs up to 60 s on a cold DB |
| api container healthy in `docker ps` terms but unhealthy flag never clears | someone "fixed" the healthcheck to use `wget --spider` | `--spider` sends HEAD; a GET-only `/api/health` answers 405. Use a plain GET. busybox wget also has no `--tries` |
| `depends_on ... service_healthy` hangs forever | `db` healthcheck failing | `docker inspect --format '{{json .State.Health}}' "$(docker compose -f docker-compose.prod.yml ps -q db)" \| python3 -m json.tool` |
| Build fails `FATAL: no ./gradlew and no ./mvnw under backend/` | the wrapper was not committed | Commit `backend/gradlew`, `backend/gradle/wrapper/*` (including the jar) — `.gitignore` must not swallow `gradle-wrapper.jar` |
| Build fails `FATAL: build produced no runnable jar` | the Gradle task produced only the plain jar | The backend needs the Spring Boot Gradle plugin so `bootJar` exists |
| Build fails `COPY failed: no source files were specified` | Building from the wrong directory | The build context is the **repository root**. Run compose from `/opt/relay` (manual) or make sure Coolify's *Base Directory* is `/` |
| Build fails on `npm ci` with `lock file missing` | Frontend lockfile not committed | The Dockerfile falls back to `npm install` and warns; commit `frontend/package-lock.json` to make builds reproducible |
| Disk full mid-build | JDK + Gradle cache layers | `docker builder prune -f && docker image prune -f` (does not touch running containers or volumes) |

### Log commands worth memorising

Under Coolify, `cd` into the deploy directory first (§4B) — or just use the
resource's **Logs** tab, which shows the same streams.

```bash
docker compose -f docker-compose.prod.yml logs -f --tail=100 api
docker compose -f docker-compose.prod.yml logs -f --tail=100 web
docker compose -f docker-compose.prod.yml logs -f --tail=100 db
docker compose -f docker-compose.prod.yml exec db psql -U relay -d relay -c '\dt'
```

Service names (`api`, `web`, `db`) are stable in both paths; container names are
not — never hardcode them.

---

## 12. File map

```
docker-compose.yml            development stack (publishes db; `--profile app`
                              also builds api+web locally)
docker-compose.prod.yml       production stack (all interfaces, no db port)
.env.example                  every variable, commented
deploy/
  backend.Dockerfile          gradle/maven wrapper -> fat jar -> temurin 21-jre-alpine, non-root
  backend.Dockerfile.dockerignore
  backend-entrypoint.sh       required-env validation, then `exec java -jar`
  frontend.Dockerfile         node build (VITE_* baked in) -> nginx:alpine
  frontend.Dockerfile.dockerignore
  nginx.conf                  SPA history fallback + cache policy
  Caddyfile.snippet           the site block to add in the n11 repo
  DEPLOY.md                   this file
```

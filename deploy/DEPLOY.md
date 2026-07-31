# Rung — deployment runbook

> Written for 3am. Every command is copy-pasteable. Nothing is implied.
> If you only have two minutes, read **§0 The one rule** and **§4B Coolify**.

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

- Rung must **never** bind 80 or 443.
- The Coolify proxy stays **OFF**. If you turn it on, it grabs 80/443 and takes
  every site on the box down, not just this one. This is a **server-level**
  setting in Coolify (Servers → the server → Proxy), not a per-app one.
- Never set a **Domain / FQDN** on the Rung resource in Coolify. Coolify reacts
  to an FQDN by generating proxy labels and expecting to serve the traffic
  itself. Leave it empty; the domain lives in the Caddy block instead.
- Rung publishes to **127.0.0.1 only**, on its own high ports, and Caddy
  reverse-proxies the domain to them.

| Component | Container port | Host port (loopback only) | Public route |
|---|---|---|---|
| `web` (nginx + SPA) | 80 | `0.0.0.0:8084` (host gateway) | `https://APP_DOMAIN/` |
| `api` (ASP.NET Core) | 8080 | `0.0.0.0:8085` (host gateway) | `https://APP_DOMAIN/api/*`, `/hub/*` |
| `db` (postgres 16) | 5432 | **not published** | none |

Both host ports are `WEB_PORT` / `API_PORT` in `.env`. Change them if another
project already holds them — and change the Caddy block to match.

---

## 1. Prerequisites

Run these on the server before anything else.

```bash
# Docker Engine + compose v2 plugin
docker --version
docker compose version          # must print v2.x or newer

# The two ports Rung wants must be free
ss -tlnp | grep -E ':(8084|8085)\b' || echo "8084/8085 free — good"

# Caddy must already be running and owning 80/443
sudo ss -tlnp | grep -E ':(80|443)\b'

# Where is Caddy? (you need this in step 6)
systemctl status caddy --no-pager | head -n 3
docker ps --format '{{.Names}}\t{{.Image}}' | grep -i caddy

# DNS must already point at this box, or Caddy cannot issue a certificate
dig +short rung.samedbilgin.com
curl -s ifconfig.me; echo
```

Also needed: ~4 GB free disk for the .NET SDK build layers
(`df -h /var/lib/docker`).

---

## 2. Clone — *manual path only*

Coolify clones the repo itself; skip to §3 if you are deploying via Coolify.

```bash
sudo mkdir -p /opt/rung && sudo chown "$USER" /opt/rung
git clone <REPO_URL> /opt/rung
cd /opt/rung
```

Everything in §4 assumes `cd /opt/rung`.

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
openssl rand -base64 32 | tr -d '/+=' | cut -c1-32     # copy this
${EDITOR:-nano} .env
```

Minimum edits:

| Variable | Set it to |
|---|---|
| `APP_DOMAIN` | the real domain, e.g. `rung.samedbilgin.com` (no scheme, no slash) |
| `POSTGRES_PASSWORD` | the string you just generated |
| `Cors__AllowedOrigins` | `https://<APP_DOMAIN>` |
| `WEB_PORT` / `API_PORT` | only if 8084/8085 were taken in §1 |
| `VITE_API_BASE_URL` | leave **empty** — production is same-origin |
| `GENERATION_PROVIDER` | `stub` for a safe demo, `llm` + `ANTHROPIC_API_KEY` for live generation |

Sanity check — this renders the fully-resolved config and fails loudly on any
missing required variable:

```bash
docker compose -f docker-compose.prod.yml config | head -n 40
```

Then confirm nothing is exposed publicly:

```bash
docker compose -f docker-compose.prod.yml config | grep -A2 published
# every entry must show  host_ip: 127.0.0.1
```

---

## 4. Build and start — *manual path*

Deploying through Coolify? Skip to **§4B**.

```bash
cd /opt/rung
docker compose -f docker-compose.prod.yml build          # 3–8 min cold
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
```

Wait until `db`, `api` and `web` all show `(healthy)`. `api` will not start
until `db` is healthy — that is intentional. `web` starts regardless of the API,
so the SPA shell still renders during an API outage instead of 502-ing.

```bash
watch -n2 'docker compose -f docker-compose.prod.yml ps'
```

Note: the build needs **BuildKit** (default in compose v2+ and in Coolify). The
build-context filters live in `deploy/*.Dockerfile.dockerignore`, which only
BuildKit reads. If you ever build with the legacy builder, set
`DOCKER_BUILDKIT=1` first.

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
   | Repository | the Rung repo |
   | Branch | `main` |
   | Base Directory | `/` |
   | Docker Compose Location | `/docker-compose.prod.yml` |

4. **Domains / FQDN: leave EMPTY.** This is the single most important field on
   the page. An FQDN here makes Coolify generate proxy labels and try to serve
   the site itself, which collides with the shared Caddy. The domain is
   configured in the Caddy block (§6) and nowhere else.
5. **Environment Variables** tab → *Bulk Edit* → paste your filled-in `.env`
   contents (§3). Toggle *Is Secret* on `POSTGRES_PASSWORD` and
   `ANTHROPIC_API_KEY`. Coolify pre-fills the variable names it detected in the
   compose file; make sure every one has a value, especially
   `POSTGRES_PASSWORD`. **It will NOT fail loudly if you forget it** — the
   compose file deliberately avoids `${VAR:?message}` because Coolify parses
   that syntax as a default and pastes the error text in as the value. Check it
   by hand.
6. Coolify may warn that the compose file publishes ports. That is correct and
   intended: published on all interfaces so the Caddy CONTAINER can reach them
   via `host.docker.internal`. A `127.0.0.1:` binding here would 502.
7. **Deploy**.

### What Coolify does and does not manage

| | |
|---|---|
| Manages | clone, build (BuildKit), `compose up`, restarts, env injection, logs, redeploy history |
| Does **not** manage | the Caddy site block (§6–§7), TLS, DNS, EF migrations (§5) |

Container names are left to Coolify on purpose — `docker-compose.prod.yml`
declares no `container_name`. Always address services by *service name*
(`api`, `web`, `db`), never by a guessed container name.

The volume is pinned as `${COMPOSE_PROJECT_NAME:-rung}-db-data` so the database
survives the resource being deleted and recreated in Coolify. Deleting the
resource in Coolify with *"delete volumes"* checked still destroys it.

### Where Coolify put everything (you need this for §5 and for logs)

```bash
# from any Rung container, ask Docker where its compose project lives
CID=$(docker ps --filter "label=com.docker.compose.service=api" \
                --filter "label=com.docker.compose.project=rung" -q | head -n1)
APPDIR=$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' "$CID")
echo "$APPDIR"          # e.g. /data/coolify/applications/<uuid>
cd "$APPDIR"
ls -la                  # docker-compose.prod.yml + the .env Coolify generated
```

From that directory every `docker compose -f docker-compose.prod.yml …` command
in this runbook works exactly as written.

### Redeploying

Push to `main` (if auto-deploy is on) or press **Redeploy** in Coolify.
Then re-run §5 if the release contains a migration.

---

## 5. Apply database migrations

The API image is runtime-only and does not carry the EF tooling. Migrations are
applied by a one-shot container built from the `migrator` stage. It sits behind
a compose **profile**, so neither `up` nor a Coolify deploy ever starts it by
accident — you run it deliberately.

```bash
# Coolify: first hop into the directory Coolify deployed into (see §4B)
# cd /data/coolify/applications/<uuid>

docker compose -f docker-compose.prod.yml --profile migrate run --rm migrator
```

SSH-free alternative for Coolify: resource → **Scheduled Tasks / Execute
Command**, or set the same line as a *Post-deployment Command* so every deploy
migrates itself. Do that only once you trust the migrations — an auto-applied
bad migration on a live database is worse than a failed deploy.

Expected tail: `Done.` (or `No migrations were applied. The database is already up to date.`)

Useful variants:

```bash
# list what the database thinks it has
docker compose -f docker-compose.prod.yml --profile migrate run --rm migrator \
    migrations list --project Rung.Infrastructure --startup-project Rung.Api

# roll the schema back to a specific migration
docker compose -f docker-compose.prod.yml --profile migrate run --rm migrator \
    database update 20260731_InitialCreate \
    --project Rung.Infrastructure --startup-project Rung.Api
```

If the backend instead applies migrations itself on startup, this step is a
harmless no-op — run it anyway, it is idempotent.

Restart the API afterwards so it picks up the new schema cleanly:

```bash
docker compose -f docker-compose.prod.yml restart api
```

Verify locally, before involving Caddy:

```bash
curl -sS http://127.0.0.1:8085/api/health ; echo
# -> {"status":"ok","version":"0.1.0"}
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8084/
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
   (`faruktekstil` / `kpssatlas` / `olay` live there — same section, same style).
3. Adjust the domain and the two ports only if yours differ from
   `rung.samedbilgin.com` / 8084 / 8085.
4. Commit, push, and **deploy n11**. Caddy obtains the TLS certificate itself.

Two things that will bite you:

* `reverse_proxy host.docker.internal:PORT` — **never** `127.0.0.1`. Caddy runs
  in a container; loopback there is Caddy's own, not the host's. Result: 502.
  It resolves through `extra_hosts: host-gateway` in n11's compose.
* The `log {` brace must be on **its own line**. Written as
  `log { output stdout` on one line, Caddy fails with *Unexpected next token*.

If you want to validate locally before pushing:

```bash
caddy validate --config infra/digitalocean/Caddyfile      # in the n11 repo
```

---

## 8. Verify

```bash
D=rung.samedbilgin.com   # your APP_DOMAIN

# 1. API through the edge
curl -sS https://$D/api/health ; echo
# -> {"status":"ok","version":"..."}

# 2. SPA shell, and its cache policy
curl -sSI https://$D/ | grep -iE 'HTTP/|cache-control'
# -> HTTP/2 200 ... cache-control: no-cache, must-revalidate

# 3. Hashed asset cached forever
ASSET=$(curl -sS https://$D/ | grep -oE '/assets/[^"]+\.js' | head -n1)
curl -sSI "https://$D$ASSET" | grep -i cache-control
# -> cache-control: public, max-age=31536000, immutable

# 4. Service worker never cached
curl -sSI https://$D/sw.js | grep -i cache-control
# -> cache-control: no-cache, no-store, must-revalidate

# 5. Deep link falls back to the SPA (history fallback)
curl -sS -o /dev/null -w '%{http_code}\n' https://$D/topic/anything
# -> 200

# 6. SignalR WebSocket upgrade — the one that silently breaks
curl -sSI -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
     -H 'Sec-WebSocket-Version: 13' \
     -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
     https://$D/hub/generation | head -n1
# -> 101 Switching Protocols (ideal) or a 4xx from SignalR's negotiate check.
#    A 502 or 404 means the edge is wrong, not the app.

# 7. Nothing of Rung is listening on a public interface
sudo ss -tlnp | grep -E ':(8084|8085)\b'
# -> both must show 127.0.0.1:8084 / 127.0.0.1:8085, NEVER 0.0.0.0 or *
```

Then open `https://$D` on a real phone and scroll one topic end-to-end.

---

## 9. Updating an existing deployment

**Coolify:** push to `main` (auto-deploy) or press **Redeploy**. Then, if the
release contains a migration, run §5. Nothing else changes — same ports, same
Caddy block, no edge work.

Take a backup first if the migration touches existing data (see below), and
remember that changing a `VITE_*` variable in Coolify requires a **rebuild**,
not a restart — use *Redeploy*, and untick *Use build cache* if the value
appears not to have taken.

**Manual:**

```bash
cd /opt/rung
git pull
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml --profile migrate run --rm migrator
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
```

Take a backup first if the migration touches existing data:

```bash
mkdir -p backups
docker compose -f docker-compose.prod.yml --profile backup run --rm backup
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
cd /opt/rung
git log --oneline -n 10               # pick the last known-good commit
git checkout <GOOD_SHA>
docker compose -f docker-compose.prod.yml build
docker compose -f docker-compose.prod.yml up -d
curl -sS http://127.0.0.1:8085/api/health ; echo
```

If the previous images are still on the box you can skip the rebuild entirely:

```bash
docker images | grep rung-            # find the previous APP_VERSION tag
APP_VERSION=0.1.0 docker compose -f docker-compose.prod.yml up -d --no-build
```

### 10b. Roll back the database schema

```bash
docker compose -f docker-compose.prod.yml --profile migrate run --rm migrator \
    database update <PREVIOUS_MIGRATION_NAME> \
    --project Rung.Infrastructure --startup-project Rung.Api
```

### 10c. Restore from a dump (last resort — destroys current data)

```bash
docker compose -f docker-compose.prod.yml stop api web
docker compose -f docker-compose.prod.yml exec -T db \
    pg_restore -U rung -d rung --clean --if-exists < backups/rung-YYYYMMDD-HHMMSS.dump
docker compose -f docker-compose.prod.yml start api web
```

### 10d. Roll back the Caddy change

The Caddyfile is CI-managed from the **n11 repo** — roll back there, not on the
droplet (a hand edit is overwritten by the next n11 deploy anyway).

```bash
# in the n11 repository
git revert <commit-that-added-the-rung-block>
git push
# then deploy n11
```

Removing the `rung.samedbilgin.com` block affects only that domain; every other
site block keeps serving.

### 10e. Full stop (Rung only — other projects untouched)

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
| `Error starting userland proxy: bind: address already in use` on `up` | Another project already holds 8084/8085 | `sudo ss -tlnp \| grep -E ':(8084\|8085)'` → pick free ports, edit `WEB_PORT`/`API_PORT` in `.env`, update the Caddy block, `up -d` again, reload Caddy |
| `up` fails with `port is already allocated` but `ss` shows nothing | A stale container from a previous run | `docker compose -f docker-compose.prod.yml down && docker container prune -f` |
| Caddy **502 Bad Gateway** on `/` | `web` container down, or wrong port in the site block | `docker compose -f docker-compose.prod.yml ps` → then `curl -I http://127.0.0.1:8084/`. If that works, the Caddy block points at the wrong port |
| Caddy **502** on `/api/*` only | `api` unhealthy (usually the DB) | `docker compose -f docker-compose.prod.yml logs --tail=100 api` |
| Caddy 502 on everything, and Caddy runs **in a container** | Inside that container `127.0.0.1` is the container, not the host | `docker inspect -f '{{.HostConfig.NetworkMode}}' <caddy>` — it must be `host`. Otherwise use the host gateway IP (`172.17.0.1`) in the site block and publish Rung's ports on that interface |
| Certificate never issues, Caddy log says `no such host` / challenge failed | DNS not pointing here yet, or something else on 80 | `dig +short $APP_DOMAIN` vs `curl ifconfig.me`; `sudo ss -tlnp \| grep :80` must show only Caddy |
| Two processes fighting over 80/443 | Coolify proxy got switched on | Coolify → Servers → *server* → Proxy → stop it / set `None`, then `sudo systemctl reload caddy`. Check with `sudo ss -tlnp \| grep -E ':(80\|443)'` — only the shared Caddy may appear |
| Every other site on the box went down right after a Coolify deploy | An FQDN was set on the Rung resource, so Coolify started its proxy to serve it | Clear the Domains/FQDN field on the resource, stop the Coolify proxy, redeploy, reload Caddy. The domain belongs in the Caddy block only |
| Coolify deploy fails at `docker compose config` / "required variable ... missing" | Env var exists in the compose file but has no value in Coolify's UI | Resource → Environment Variables → Bulk Edit → paste `.env` (§3). Coolify does **not** read a `.env` committed to the repo |
| Coolify deploy is green but the site is unreachable | The Caddy block was never added, or Caddy was not reloaded | §6–§7. Coolify never touches the edge |
| Changed a `VITE_*` variable in Coolify, app behaves the same | Vite bakes those in at build time and the build cache was reused | Redeploy with *Use build cache* unticked |
| Cannot find the compose file / `.env` on the server under Coolify | Coolify deploys into `/data/coolify/applications/<uuid>` | Derive it from the container label — see §4B "Where Coolify put everything" |
| Redeploy fails with `container name ... is already in use` | A `container_name:` was reintroduced into the compose file | Remove it. `docker-compose.prod.yml` deliberately declares none; Coolify names containers itself |
| API logs `Npgsql.NpgsqlException: Connection refused` / `No such host is known` | `ConnectionStrings__Default` uses `localhost` | Host must be `db` (the compose service name). Inside the api container, `localhost` is the api itself |
| API logs `password authentication failed for user "rung"` | `.env` password changed after the volume was created — postgres only reads `POSTGRES_PASSWORD` on **first** init | Either set `.env` back to the original password, or `ALTER USER rung PASSWORD '...'` via `docker compose -f docker-compose.prod.yml exec db psql -U rung -d rung`, or (data loss) `down -v` and start over |
| API restarts in a loop, healthcheck never green | Migrations not applied, or the DB is still initialising | `logs --tail=100 api`; run the migrator (§5); `db` needs ~20s on first boot |
| `depends_on ... service_healthy` hangs forever | `db` healthcheck failing | `docker inspect --format '{{json .State.Health}}' "$(docker compose -f docker-compose.prod.yml ps -q db)" \| python3 -m json.tool` |
| SignalR: browser console `WebSocket failed to connect`, falls back to long-polling (slow but works) | Caddy route for `/hub/*` missing, ordered after the catch-all, or `encode` applied to it | The `/hub/*` `handle` must come **before** the root `handle`, must **not** be inside an `encode` block, and must keep `transport http { versions 1.1 }`. Never add manual `header_up Connection/Upgrade` — Caddy manages those and overriding them breaks the handshake |
| SignalR: connection opens then drops every ~60s | Idle timeout on the proxy | Keep `flush_interval -1` on the `/hub/*` route; raise the hub keep-alive interval on the client |
| SignalR: `Error: Failed to complete negotiation` with a 404 | `/hub/*` is hitting the **web** container | nginx deliberately returns 404 for `/hub/` with the message `hub is served by the edge` — the Caddy route is missing or misordered |
| App loads an old version after a deploy | Service worker cached the shell | `curl -sSI https://$D/sw.js \| grep -i cache-control` must be `no-store`. Then hard-reload / clear site data once |
| `docker compose config` errors `required variable POSTGRES_PASSWORD is missing` | No `.env`, or the line is blank | `cp .env.example .env` and set it |
| Build fails `COPY failed: no source files were specified` | Building from the wrong directory | The build context is the **repository root**. Run compose from `/opt/rung` (manual) or make sure Coolify's *Base Directory* is `/` |
| Build fails on `npm ci` with `lock file missing` | Frontend lockfile not committed | The Dockerfile falls back to `npm install` and warns; commit `frontend/package-lock.json` to make builds reproducible |
| Disk full mid-build | .NET SDK layers | `docker builder prune -f && docker image prune -f` (does not touch running containers or volumes) |

### Log commands worth memorising

Under Coolify, `cd` into the deploy directory first (§4B) — or just use the
resource's **Logs** tab, which shows the same streams.

```bash
docker compose -f docker-compose.prod.yml logs -f --tail=100 api
docker compose -f docker-compose.prod.yml logs -f --tail=100 web
docker compose -f docker-compose.prod.yml logs -f --tail=100 db
docker compose -f docker-compose.prod.yml exec db psql -U rung -d rung -c '\dt'
sudo journalctl -u caddy -f
```

Service names (`api`, `web`, `db`) are stable in both paths; container names are
not — never hardcode them.

---

## 12. File map

```
docker-compose.yml            development stack (publishes db for psql)
docker-compose.prod.yml       production stack (loopback only, no db port)
.env.example                  every variable, commented
deploy/
  frontend.Dockerfile         node build -> nginx:alpine
  frontend.Dockerfile.dockerignore
  backend.Dockerfile          .NET restore/publish -> aspnet:alpine, non-root
  backend.Dockerfile.dockerignore
  nginx.conf                  SPA history fallback + cache policy
  Caddyfile.snippet           the site block to append on the server
  DEPLOY.md                   this file
```

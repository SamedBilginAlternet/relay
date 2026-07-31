#!/bin/sh
# ---------------------------------------------------------------------------
# Relay — api container entrypoint.
#
# WHY THIS FILE EXISTS
# --------------------
# The compose file cannot validate required variables. `${VAR:?message}` is
# forbidden on this box: Coolify parses that syntax like `${VAR:-default}` and
# substitutes the ERROR MESSAGE as the value — a missing POSTGRES_PASSWORD
# would silently become the literal password "POSTGRES_PASSWORD is required".
# So the check happens here, at container start, where a failure is a real
# failure: non-zero exit, visible in `docker compose logs api` and in Coolify.
#
# Nothing secret is echoed. Values are masked before printing.
# ---------------------------------------------------------------------------
set -eu

log()  { echo "[entrypoint] $*"; }
warn() { echo "[entrypoint] WARN: $*" >&2; }
fail() { echo "[entrypoint] FATAL: $*" >&2; exit 1; }

mask() {
    # keep the last 4 characters, hide the rest
    v="${1:-}"
    len=${#v}
    if [ "$len" -le 4 ]; then printf '****'; else printf '****%s' "$(printf '%s' "$v" | tail -c 4)"; fi
}

# --- database --------------------------------------------------------------
[ -n "${SPRING_DATASOURCE_URL:-}" ] \
    || fail "SPRING_DATASOURCE_URL is empty. Expected jdbc:postgresql://db:5432/relay"

case "${SPRING_DATASOURCE_URL}" in
    jdbc:postgresql://*) : ;;
    *) fail "SPRING_DATASOURCE_URL must start with jdbc:postgresql:// (got: ${SPRING_DATASOURCE_URL%%:*}...)" ;;
esac

case "${SPRING_DATASOURCE_URL}" in
    *//localhost*|*//127.0.0.1*)
        fail "SPRING_DATASOURCE_URL points at localhost. Inside this container localhost is the API itself — use the compose service name: jdbc:postgresql://db:5432/<db>" ;;
esac

[ -n "${SPRING_DATASOURCE_USERNAME:-}" ] || fail "SPRING_DATASOURCE_USERNAME is empty."

[ -n "${SPRING_DATASOURCE_PASSWORD:-}" ] \
    || fail "SPRING_DATASOURCE_PASSWORD is empty. Set POSTGRES_PASSWORD in the Coolify env tab (or .env) — the compose file cannot enforce it, see the header of docker-compose.prod.yml."

case "${SPRING_DATASOURCE_PASSWORD}" in
    change-me*|changeme*)
        fail "SPRING_DATASOURCE_PASSWORD is still the .env.example placeholder. Generate one: openssl rand -base64 32 | tr -d '/+=' | cut -c1-32" ;;
esac

# --- encryption ------------------------------------------------------------
# Connection.config (Jira/Slack tokens) is stored AES-GCM encrypted with this
# key. A weak or missing key means either a boot failure or plaintext tokens in
# the database; both are worse to discover later.
[ -n "${APP_ENCRYPTION_KEY:-}" ] \
    || fail "APP_ENCRYPTION_KEY is empty. Generate one: openssl rand -base64 32"

if [ "${#APP_ENCRYPTION_KEY}" -lt 32 ]; then
    fail "APP_ENCRYPTION_KEY is ${#APP_ENCRYPTION_KEY} chars, needs >= 32. Generate one: openssl rand -base64 32"
fi

case "${APP_ENCRYPTION_KEY}" in
    change-me*|changeme*)
        fail "APP_ENCRYPTION_KEY is still the .env.example placeholder." ;;
esac

# --- llm -------------------------------------------------------------------
if [ -z "${GROQ_API_KEYS:-}" ]; then
    warn "GROQ_API_KEYS is empty — the backend will fall back to StubLlmClient (deterministic, offline). Fine for a demo, NOT a working product."
else
    # GROQ_API_KEYS is a comma-separated rotation list (ARCHITECTURE.md §7).
    key_count=$(printf '%s' "${GROQ_API_KEYS}" | tr ',' '\n' | grep -c '[^[:space:]]' || true)
    log "GROQ_API_KEYS: ${key_count} key(s) in rotation, last one ends $(mask "${GROQ_API_KEYS##*,}")"
fi

[ -n "${GROQ_MODEL:-}" ] || warn "GROQ_MODEL is empty — the application default will be used."

# --- tools / cors ----------------------------------------------------------
case "${TOOLS_MODE:-}" in
    live|stub) log "TOOLS_MODE=${TOOLS_MODE}" ;;
    "")        warn "TOOLS_MODE is empty — the application default will be used." ;;
    *)         warn "TOOLS_MODE='${TOOLS_MODE}' is not one of live|stub; passing it through anyway." ;;
esac

[ -n "${CORS_ALLOWED_ORIGINS:-}" ] \
    || warn "CORS_ALLOWED_ORIGINS is empty. In production the SPA is same-origin behind Caddy so this is usually harmless, but a browser calling the API from another host will be blocked."

# --- summary ---------------------------------------------------------------
log "java:      $(java -version 2>&1 | head -n 1)"
log "profile:   ${SPRING_PROFILES_ACTIVE:-<default>}"
log "datasource:${SPRING_DATASOURCE_URL} (user=${SPRING_DATASOURCE_USERNAME}, pass=$(mask "${SPRING_DATASOURCE_PASSWORD}"))"
log "cors:      ${CORS_ALLOWED_ORIGINS:-<unset>}"
log "starting Spring Boot on :${SERVER_PORT:-8080}"

# JAVA_OPTS is intentionally unquoted: it is a list of flags, not one argument.
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"

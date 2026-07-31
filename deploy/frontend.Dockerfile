# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Relay — frontend image (React + Vite + TypeScript SPA)
#
# Build context: the REPOSITORY ROOT (so that this Dockerfile and the sibling
# deploy/frontend.Dockerfile.dockerignore are both honoured by BuildKit).
#
#   docker build -f deploy/frontend.Dockerfile -t relay-web .
#
# Stage 1: node — install deps (cached on the lockfile) and run `vite build`.
# Stage 2: nginx:alpine — serve the static `dist/` with SPA history fallback.
#          The API is NOT proxied here; the edge (Caddy) routes /api/*,
#          including the SSE stream /api/runs/{id}/stream.
# ---------------------------------------------------------------------------

ARG NODE_VERSION=22
ARG NGINX_VERSION=1.27

# --------------------------------------------------------------------- build
FROM node:${NODE_VERSION}-alpine AS build
WORKDIR /app

ENV CI=true \
    NODE_ENV=development

# Dependency manifests first — this layer is reused on every source-only change.
COPY frontend/package.json frontend/package-lock.json* frontend/npm-shrinkwrap.json* ./
RUN --mount=type=cache,target=/root/.npm \
    if [ -f package-lock.json ] || [ -f npm-shrinkwrap.json ]; then \
        npm ci --no-audit --no-fund; \
    else \
        echo "WARN: no lockfile found, falling back to 'npm install'" >&2; \
        npm install --no-audit --no-fund; \
    fi

# Now the source.
COPY frontend/ ./

# Vite bakes these in at build time — they cannot be changed at runtime.
# Changing one of them needs `docker compose build web`; a restart does nothing.
#   VITE_API_BASE_URL=""  -> same-origin (production behind Caddy)
#   VITE_API_BASE_URL="http://127.0.0.1:8087/api" -> local docker-compose dev
ARG VITE_API_BASE_URL=""
# Run kaynagi. `api` = gercek backend (PROD'DA BU OLMALI),
# `mock` = repodaki fixture ile calisan demo modu.
ARG VITE_RUN_SOURCE="api"
ARG VITE_APP_VERSION="dev"
ENV VITE_RUN_SOURCE=${VITE_RUN_SOURCE} \
    VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_APP_VERSION=${VITE_APP_VERSION} \
    NODE_ENV=production

RUN npm run build

# ------------------------------------------------------------------- runtime
FROM nginx:${NGINX_VERSION}-alpine AS runtime

# Custom config: SPA history fallback + cache policy. Replaces the stock file.
COPY deploy/nginx.conf /etc/nginx/nginx.conf

# Stock nginx image ships a default site; remove it so only our server{} exists.
RUN rm -f /etc/nginx/conf.d/default.conf

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# busybox wget: no --tries option, and --spider sends HEAD. Use a plain GET.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1

STOPSIGNAL SIGQUIT
CMD ["nginx", "-g", "daemon off;"]

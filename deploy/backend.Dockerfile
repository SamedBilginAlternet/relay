# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Rung — backend image (ASP.NET Core Minimal API + SignalR + EF Core/Npgsql)
#
# Build context: the REPOSITORY ROOT.
#
#   docker build -f deploy/backend.Dockerfile -t rung-api .
#
# Stages
#   manifests - prunes the source tree down to *.csproj/*.sln/props so that the
#               restore layer only invalidates when dependencies change
#   restore   - `dotnet restore` (the expensive, network-bound step)
#   source    - full source on top of the warm restore
#   publish   - `dotnet publish` -> /app/publish
#   migrator  - optional target: adds dotnet-ef, used by the one-shot
#               `migrator` compose service to apply EF Core migrations
#   runtime   - final image: aspnet alpine, non-root, HEALTHCHECK /api/health
#
# The .NET version is a build ARG so this file needs no edit when the backend
# retargets. Keep DOTNET_VERSION in sync with backend/global.json.
# ---------------------------------------------------------------------------

ARG DOTNET_VERSION=10.0

# ------------------------------------------------------------------ manifests
# Same base image as `restore` on purpose: no extra image to pull.
FROM mcr.microsoft.com/dotnet/sdk:${DOTNET_VERSION} AS manifests
WORKDIR /m
COPY backend/ ./src/
# Keep only what `dotnet restore` reads. Everything else is deleted, so this
# stage's output is byte-identical across pure source edits => restore cache hit.
RUN find ./src -type f \
        ! -name '*.csproj'  ! -name '*.sln'    ! -name '*.slnx' \
        ! -name '*.props'   ! -name '*.targets' \
        ! -iname 'nuget.config' ! -name 'global.json' ! -name 'packages.lock.json' \
        -delete \
 && find ./src -type d -empty -delete

# -------------------------------------------------------------------- restore
FROM mcr.microsoft.com/dotnet/sdk:${DOTNET_VERSION} AS restore
WORKDIR /src

ENV DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1 \
    DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 \
    NUGET_XMLDOC_MODE=skip

COPY --from=manifests /m/src/ ./
RUN --mount=type=cache,target=/root/.nuget/packages,sharing=locked \
    dotnet restore Rung.Api/Rung.Api.csproj

# --------------------------------------------------------------------- source
FROM restore AS source
WORKDIR /src
COPY backend/ ./

# -------------------------------------------------------------------- publish
FROM source AS publish
ARG APP_VERSION=0.1.0
RUN --mount=type=cache,target=/root/.nuget/packages,sharing=locked \
    dotnet publish Rung.Api/Rung.Api.csproj \
        -c Release \
        --no-restore \
        -o /app/publish \
        /p:UseAppHost=false \
        /p:Version=${APP_VERSION}

# ------------------------------------------------------------------- migrator
# Optional target, used by the `migrator` profile in docker-compose.prod.yml:
#   docker build -f deploy/backend.Dockerfile --target migrator -t rung-migrator .
#   docker run --rm -e ConnectionStrings__Default=... rung-migrator
FROM source AS migrator
WORKDIR /src
ENV PATH="${PATH}:/root/.dotnet/tools" \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1
RUN --mount=type=cache,target=/root/.nuget/packages,sharing=locked \
    dotnet tool install --global dotnet-ef
ENTRYPOINT ["dotnet", "ef"]
CMD ["database", "update", \
     "--project", "Rung.Infrastructure", \
     "--startup-project", "Rung.Api"]

# -------------------------------------------------------------------- runtime
FROM mcr.microsoft.com/dotnet/aspnet:${DOTNET_VERSION}-alpine AS runtime

# icu-libs: Alpine defaults to invariant globalization, which breaks Turkish
#           culture handling. tzdata: timestamptz conversions.
RUN apk add --no-cache icu-libs tzdata

ENV DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=false \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1 \
    DOTNET_EnableDiagnostics=0 \
    ASPNETCORE_URLS=http://+:8080 \
    ASPNETCORE_ENVIRONMENT=Production \
    TZ=Etc/UTC

# Non-root, fixed uid/gid so any mounted path has predictable ownership.
RUN addgroup -g 10001 -S rung \
 && adduser  -u 10001 -S rung -G rung -h /app -s /sbin/nologin

WORKDIR /app
COPY --from=publish --chown=rung:rung /app/publish ./

USER rung:rung

EXPOSE 8080

# busybox wget ships with the alpine base image — no extra layer needed.
# NOTE: this must be a real GET. busybox `--spider` issues a HEAD, and a
# Minimal-API MapGet("/api/health") answers HEAD with 405 -> permanently
# unhealthy. Also: busybox wget has no --tries/-t option.
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["dotnet", "Rung.Api.dll"]

# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Relay — backend image (Java 21 + Spring Boot 3.4, PostgreSQL/Flyway, SSE)
#
# Build context: the REPOSITORY ROOT.
#
#   docker build -f deploy/backend.Dockerfile -t relay-api .
#
# Stages
#   build   - JDK 21. Runs the project's own wrapper (./gradlew, or ./mvnw if
#             the backend ever switches) and produces ONE executable fat jar,
#             normalised to /out/app.jar so the runtime stage never has to know
#             the artifact's name or version.
#   runtime - JRE 21 alpine, non-root, HEALTHCHECK on GET /api/health.
#
# Why the whole `backend/` tree is copied in one go instead of the usual
# "manifests first, then sources" split: both wrappers resolve dependencies
# into a cache directory, and BuildKit `--mount=type=cache` already keeps that
# directory warm ACROSS builds. The manifest split would buy a layer cache we
# do not need and would hardcode Gradle-only filenames into this file.
#
# Migrations: Flyway runs INSIDE the application at startup (see DEPLOY.md §5).
# There is deliberately no separate migrator image.
# ---------------------------------------------------------------------------

ARG JAVA_VERSION=21

# ----------------------------------------------------------------------- build
FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS build

# bash: some wrapper/toolchain scripts assume it. git: build plugins that stamp
# a commit id fail hard when it is absent. Build stage only — not shipped.
RUN apk add --no-cache bash git

WORKDIR /src

ENV GRADLE_USER_HOME=/root/.gradle \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

COPY backend/ ./

ARG APP_VERSION=0.1.0

# `-x test` / `-DskipTests`: the image build is not the test gate. CI runs the
# suite; a hackathon deploy must not fail on a flaky test at 3am.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    --mount=type=cache,target=/root/.m2,sharing=locked \
    set -eux; \
    if [ -f ./gradlew ]; then \
        chmod +x ./gradlew; \
        ./gradlew --no-daemon --stacktrace -Pversion="${APP_VERSION}" bootJar -x test; \
    elif [ -f ./mvnw ]; then \
        chmod +x ./mvnw; \
        ./mvnw -B -ntp -DskipTests -Drevision="${APP_VERSION}" package; \
    else \
        echo "FATAL: no ./gradlew and no ./mvnw under backend/ — nothing to build." >&2; \
        exit 1; \
    fi

# Normalise the artifact name. Gradle's bootJar also emits `*-plain.jar` (the
# thin jar) and Maven leaves `original-*.jar` behind; picking either one gives a
# container that starts and then dies with "no main manifest attribute".
RUN set -eux; \
    jar="$(find . -type f -name '*.jar' \
              \( -path '*/build/libs/*' -o -path '*/target/*' \) \
              ! -name '*-plain.jar' \
              ! -name '*-sources.jar' \
              ! -name '*-javadoc.jar' \
              ! -name 'original-*.jar' \
              ! -path '*/target/maven-*' \
            2>/dev/null | sort | head -n1 || true)"; \
    if [ -z "$jar" ]; then \
        echo "FATAL: build produced no runnable jar." >&2; \
        find . -name '*.jar' -maxdepth 4 >&2 || true; \
        exit 1; \
    fi; \
    echo "using $jar"; \
    unzip -l "$jar" | grep -q 'BOOT-INF/' \
        || { echo "FATAL: $jar is not a Spring Boot fat jar." >&2; exit 1; }; \
    mkdir -p /out; \
    cp "$jar" /out/app.jar

# --------------------------------------------------------------------- runtime
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime

# tzdata: Postgres timestamptz <-> java.time conversions and Europe/Istanbul.
RUN apk add --no-cache tzdata

ENV TZ=Etc/UTC \
    LANG=C.UTF-8 \
    SERVER_PORT=8080 \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8"

# Non-root, fixed uid/gid so any mounted path has predictable ownership.
RUN addgroup -g 10001 -S relay \
 && adduser  -u 10001 -S relay -G relay -h /app -s /sbin/nologin

WORKDIR /app
COPY --from=build --chown=relay:relay /out/app.jar /app/app.jar

# Env validation lives HERE, not in the compose file: Coolify parses
# `${VAR:?message}` as if it were `${VAR:-default}` and would substitute the
# error text as the value. See docker-compose.prod.yml header.
COPY --chmod=0755 deploy/backend-entrypoint.sh /usr/local/bin/entrypoint.sh

USER relay:relay

EXPOSE 8080

# busybox wget ships with the alpine base image — no extra layer needed.
# NOTE: this must be a real GET. busybox `--spider` issues a HEAD, and a
# GET-only @GetMapping("/api/health") answers HEAD with 405 -> permanently
# unhealthy. Also: busybox wget has no --tries/-t option.
# start-period is generous: JVM boot + Flyway migration on a cold database.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/api/health || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# CORTEX :: log-monitoring-service runtime image (P10.0 / ADR-0050).
#
# Multi-stage, build-from-source: a JDK17 builder produces the Spring Boot
# fat jar, a slim JRE17 stage runs it as a non-root user. Hand-rolled (no
# jib / no spring-boot:build-image) => ZERO backend pom touches. The image
# build skips the quality gates (checkstyle / spotbugs / owasp / jacoco /
# sbom / enforcer) -- those are CI's job (P14); the image only needs a jar.
#
# Build context = repo ROOT:
#   docker build -f infra/docker/log-monitoring-service.Dockerfile -t cortex/log-monitoring-service:0.1.0 .
# ---------------------------------------------------------------------------

# ---- stage 1: build the fat jar from source -------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /build
COPY . .
# Two-phase, pom-free build of a runnable fat jar:
#  (1) `-am install` compiles + installs the app and its reactor deps (e.g.
#      log-agent-lib) into the local repo. No repackage goal here, so the
#      library modules (which have no main class) are never repackaged.
#  (2) app-ONLY `package spring-boot:repackage` in ONE lifecycle produces the
#      executable fat jar (the service poms don't declare the plugin -- they
#      run via spring-boot:run in dev -- and a standalone repackage fails with
#      "Source file is not available", so it must share the package phase).
# Tests + quality gates are skipped (CI owns those, P14); the image only needs
# a jar. NOTE: build with `--network=host` so BuildKit can reach Maven Central.
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    ./mvnw -q -B -ntp -pl log-monitoring-service -am -Dmaven.test.skip=true \
        -Dcheckstyle.skip=true -Dspotbugs.skip=true \
        -Ddependency-check.skip=true -Dcyclonedx.skip=true \
        -Djacoco.skip=true -Denforcer.skip=true clean install && \
    ./mvnw -q -B -ntp -pl log-monitoring-service -Dmaven.test.skip=true \
        -Dcheckstyle.skip=true -Dspotbugs.skip=true \
        -Ddependency-check.skip=true -Dcyclonedx.skip=true \
        -Djacoco.skip=true -Denforcer.skip=true \
        package spring-boot:repackage

# ---- stage 2: slim runtime ------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system cortex \
 && useradd --system --gid cortex --home-dir /app --shell /usr/sbin/nologin cortex
COPY --from=builder /build/log-monitoring-service/target/log-monitoring-service-0.1.0-SNAPSHOT.jar /app/app.jar
RUN chown -R cortex:cortex /app
USER cortex
EXPOSE 8098
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
  CMD curl -fsS http://localhost:8098/actuator/health || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]

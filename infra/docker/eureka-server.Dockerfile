# syntax=docker/dockerfile:1
# ---------------------------------------------------------------------------
# CORTEX :: eureka-server runtime image (P10.0 / ADR-0050).
#
# Standalone Eureka registry (ADR-0016): its pom is DELIBERATELY outside
# the cortex-parent reactor, so it is built by cd-ing into the project and
# invoking the repo-root Maven wrapper (the documented local-dev flow).
#
# Multi-stage, build-from-source: a JDK17 builder produces the Spring Boot
# fat jar, a slim JRE17 stage runs it as a non-root user. Hand-rolled (no
# jib / no spring-boot:build-image) => ZERO pom touches.
#
# Build context = repo ROOT:
#   docker build -f infra/docker/eureka-server.Dockerfile -t cortex/eureka-server:0.1.0 .
# ---------------------------------------------------------------------------

# ---- stage 1: build the fat jar from source -------------------------------
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    sed -i 's/\r$//' mvnw && chmod +x mvnw && \
    cd infra/eureka/eureka-server && \
    ../../../mvnw -q -B -ntp -DskipTests clean package

# ---- stage 2: slim runtime ------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system cortex \
 && useradd --system --gid cortex --home-dir /app --shell /usr/sbin/nologin cortex
COPY --from=builder \
    /build/infra/eureka/eureka-server/target/cortex-eureka-server-0.1.0-SNAPSHOT.jar \
    /app/app.jar
RUN chown -R cortex:cortex /app
USER cortex
EXPOSE 8761
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=10 \
  CMD curl -fsS http://localhost:8761/actuator/health || exit 1
ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]

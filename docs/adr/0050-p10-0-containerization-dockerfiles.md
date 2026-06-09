# ADR-0050: P10.0 containerization — multi-stage from-source Dockerfiles

- **Status**: Accepted
- **Date**: 2026-06-09
- **Deciders**: @varadharajaan (operator)
- **Tags**: infra, docker, containerization, P10, build, release, ADR-0016

## Context

P10 ships the `infra/docker/` compose stack that runs all seven CORTEX
services plus the standalone Eureka registry as containers on top of the
existing smoke datastores. P10 was **hard-gated**: there were no
container images anywhere in the repo — no `**/Dockerfile`, and no pom
used `spring-boot:build-image` or jib — so there was nothing to compose.

P10.0 (this ADR) builds that missing layer: one container image per
runnable service. The services are already ~95% container-ready (each
exposes `/actuator/health`, is configured entirely through environment
variables, and selects behaviour through Spring profiles), so the only
gap is a reproducible image build.

Constraints that shaped the decision:

1. **No backend pom edits.** The image build must not modify any service
   `pom.xml`. This keeps the change directory-isolated under
   `infra/docker/` and — critically — avoids touching
   `log-remediation-service`, whose pom is being actively edited by a
   parallel workstream.
2. **Hermetic and self-contained.** A clone + `docker build` must produce
   a runnable image with no host-side Maven build step first.
3. **The quality gates are CI's job.** Checkstyle, SpotBugs, OWASP
   dependency-check, JaCoCo, CycloneDX SBOM, and the enforcer run in
   `mvn verify` locally and in CI (and will be formalized in P14). The
   image build only needs a jar; re-running those gates inside every
   image build would be slow and redundant.

## Decision

**D1 — One hand-rolled multi-stage Dockerfile per service under
`infra/docker/<service>.Dockerfile`.** Stage 1 (`eclipse-temurin:17-jdk-jammy`)
builds the fat jar from source; stage 2 (`eclipse-temurin:17-jre-jammy`)
runs it. Hand-rolled Dockerfiles — not jib and not
`spring-boot:build-image` — because both of those bind into Maven and
would require pom edits (D-constraint 1). Eight images total: the seven
services and the standalone eureka-server.

**D2 — Build the runnable jar without declaring the Spring Boot plugin in
the service poms.** The service modules do **not** declare
`spring-boot-maven-plugin` (they run via `spring-boot:run` in dev), so a
plain `mvn package` yields a *thin* jar with no `BOOT-INF` and no
`Main-Class` (≈12 KB) that `java -jar` cannot launch. The build therefore
runs two phases in the builder stage:

1. `./mvnw -pl <svc> -am … clean install` — compiles and installs the app
   **and its reactor dependencies** (e.g. `log-agent-lib`) into the
   in-container local repo. No repackage goal here, so library modules
   (which have no main class) are never repackaged.
2. `./mvnw -pl <svc> … package spring-boot:repackage` — app module only
   (dependencies resolved from the local repo), with `repackage` appended
   to the **same** `package` lifecycle, producing the ≈50–78 MB executable
   fat jar.

This is the minimal pom-free recipe. Appending `repackage` to a single
`-am package` invocation does **not** work — the goal then runs against
every reactor module in the `-am` set, including `log-agent-lib`, and
fails with "Unable to find main class". A standalone `spring-boot:repackage`
invocation also fails ("Source file is not available… run 'package' as part
of the same lifecycle"). The two-phase split is the resolution. The
eureka-server pom **does** declare the plugin and is standalone (ADR-0016),
so its image builds with a single `package`.

**D3 — Skip tests and quality gates in the image build.** The builder
runs with `-Dmaven.test.skip=true` (so test sources are not even compiled,
dropping the entire test-scoped dependency closure) and explicitly skips
checkstyle, spotbugs, dependency-check, cyclonedx, jacoco, and the
enforcer. The image is an *artifact carrier*, not a verification surface;
`mvn verify` (local triangle Leg A) and CI (Leg C, formalized in P14) own
the gates.

**D4 — `--network=host` for the build.** On the Docker Desktop / Windows
host used here, the default BuildKit bridge network cannot reach Maven
Central ("Network is unreachable"); host networking routes the build
through the working VM network. Builds are invoked with
`docker build --network=host`. A BuildKit cache mount
(`--mount=type=cache,target=/root/.m2`) shares the Maven cache across the
eight image builds.

**D5 — Hardened runtime.** The runtime stage runs as a non-root `cortex`
user, sets a container-aware heap (`-XX:MaxRAMPercentage=75`), `EXPOSE`s
the service port, declares a `HEALTHCHECK` that curls
`/actuator/health`, and uses an exec-form entrypoint so the JVM is PID 1
and receives signals directly.

**D6 — Lean build context.** A reconciled root `.dockerignore` drops
`**/target`, VCS, IDE, docs, the local tracking files, run-logs, and the
non-build infra subtrees — but deliberately keeps `infra/eureka/eureka-server`
(the eureka image builds from there).

## Consequences

- The repo can now build eight runnable images from a clean clone with no
  host-side Maven step, unblocking the P10.1 compose stack.
- Zero service poms changed; `log-remediation-service`'s pom is untouched,
  so the parallel workstream is unaffected.
- Images carry no test/quality tooling and stay relatively small (JRE base
  + one fat jar).
- Standalone boot only guarantees liveness; services with external
  dependencies report `503 DOWN` until wired to their datastores. Full
  wired readiness over the compose network is proven in P10.1.
- `log-gateway` requires `SPRING_PROFILES_ACTIVE=dev` (or a prod profile)
  to boot — the base profile leaves the JWT secret empty by design.

## Verification

- **Leg A** — n/a in the Maven reactor sense (no Java changed); the build
  *is* the artifact. Each `docker build --network=host` succeeds and
  yields a fat jar (eureka 8761, gateway 8090, ingest 8092, echo 8093,
  processor 8095, remediation 8096, indexer 8097, monitoring 8098).
- **Leg B** — `scripts/live-e2e/smoke-p10-0.ps1` builds all eight images
  and boots each container: eureka + echo reach `health UP`; the
  dependency-bound services boot their web context and answer
  `/actuator/health` (DOWN-without-infra is expected and accepted at this
  tier). The build recipe and its three traps are recorded in
  `/memories/repo/docker-image-build-recipe.md`.

## Rejected alternatives

- **`spring-boot:build-image` (Cloud Native Buildpacks).** Produces an
  OCI image with no Dockerfile, but binds into the Maven build and would
  require adding the plugin execution to every service pom — violating the
  no-pom-edit constraint and touching `log-remediation-service`.
- **Jib (`jib-maven-plugin`).** Same objection: it is a Maven plugin and
  needs per-module pom configuration.
- **Single jar-copy Dockerfile expecting a host-built jar.** Not
  self-contained; requires a host `mvn package` before every build and
  would not work from a clean clone or in CI without an extra stage.
- **One shared parameterized Dockerfile + `--build-arg MODULE`.** Tempting
  for DRY, but the eureka-server build differs (standalone project, single
  `package`, different jar path), and per-service `HEALTHCHECK` ports and
  start-periods read more clearly as explicit files. Eight small explicit
  Dockerfiles beat one branchy templated one.

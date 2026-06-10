# ADR-0060: P16 E2E and load testing with Gatling

- **Status**: Accepted
- **Date**: 2026-06-10
- **Deciders**: @varadharajaan
- **Tags**: testing, performance, gatling, slo, P16

## Context

The platform contract (Part 12.7) requires committed performance baselines that
fail CI on regression, and `plan.md` section 6 sets per-tier latency SLOs for
the read surfaces (structured p95 < 100 ms, label p95 < 500 ms, full-text
p95 < 1 s). P0-P15 shipped functional + integration + Newman coverage, but no
load or SLO-assertion layer existed: there was no `**/performance/` tree, no
Gatling or k6 wiring, and no throughput gate.

P16 adds that layer. PHASES.md scopes it as "k6 + Gatling, SLO-driven".

## Decision

**D1 -- Gatling, not k6, as the load tool.** Gatling is JVM-native and runs
under Maven via `gatling-maven-plugin`, so the simulations live in the same
build, language (Java DSL), and toolchain as the rest of the platform and
satisfy Part 12.7's "simulations under `src/test/.../performance/`" intent
without adding a JavaScript runtime. k6 was rejected to avoid a second language
+ runtime for one phase; the SLO assertions Gatling expresses natively cover the
requirement.

**D2 -- Standalone `log-load-tests` module, outside the reactor.** Like
`infra/eureka`, the module is deliberately NOT a `cortex-parent` child:
simulations run against a LIVE booted stack, not in the unit/IT build, and must
not inherit the parent Checkstyle/SpotBugs/JaCoCo gates (those target production
code, not throughput probes). This keeps `mvn verify` on the reactor fast and
unaffected.

**D3 -- One bootstrap login, then SLO assertions on the read paths.** A single
login runs once in the simulation constructor (before injection) and mints an
access token every virtual user replays, so the measured load stays on the two
read surfaces -- `POST /api/v1/query/nl` (NL-to-LogQL) and
`GET /api/v1/logs/search?index=&q=&maxHits=` (searchLogs). Authenticating once
mirrors a real client session and keeps the login throttle (a security control,
covered functionally by the Newman suite) out of the read-path SLO measurement.
`GatewayLoadSimulation` asserts `global().responseTime().percentile(95).lt(p95)`
plus `global().successfulRequests().percent().gte(successPercent)`; a latency or
error-rate regression fails `gatling:test` (non-zero exit), which fails CI.

**D4 -- Everything is a `-D` tunable.** `baseUrl`, `users`, `rampSeconds`,
`username`, `password`, `tenant`, `nlPrompt`, `searchIndex`, `searchQuery`,
`searchMaxHits`, `p95Millis`, `successPercent` are all system properties with
safe defaults, so the same simulation runs as a fast CI smoke (default 20 users
/ p95 1 s) or a heavier local soak without code changes.

**D5 -- CI runs load against the live compose stack, gated.** A `load-test` job
in `.github/workflows/ci.yml` boots the P10 compose stack, waits for gateway
health, runs `gatling:test` against it, and uploads the Gatling HTML report as
an artifact. The read paths accept `200`, a feature-gated or empty-index `404`,
and -- under concurrent load -- a per-user sub-bucket `429` (the rate limiter
working as designed); only a `5xx`, a timeout, or a p95 regression fails the
gate. So the job measures the gateway round-trip budget regardless of which
optional read features a deploy enables or whether load trips the limiter.

## Consequences

- The platform now has a committed, SLO-asserting load baseline; a p95 or
  error-rate regression on the gateway read path fails CI (Part 12.7 satisfied).
- The standalone module keeps the reactor build fast and the production quality
  gates unpolluted by load-probe code.
- The read-path simulation is rate-limiter-aware: a fast `429` under load counts
  as a healthy, well-formed gateway response (not a failure), so the SLO gate
  measures latency + availability rather than raw throughput past the limiter.
- Gatling HTML reports are retained per run for trend inspection.
- The load tool is JVM-native, so no second language/runtime is introduced.

## Alternatives Considered

- **k6 (Grafana).** Rejected. Strong tool, but it adds a JavaScript runtime and
  a separate scripting language for a single phase; Gatling keeps everything in
  the existing Java/Maven toolchain.
- **Put simulations in an existing service module under
  `src/test/.../performance/`.** Rejected. That drags load-probe code through the
  module's Checkstyle/SpotBugs/JaCoCo gates and couples a live-stack concern to a
  unit/IT build. A standalone module (D2) is cleaner and mirrors `infra/eureka`.
- **Run load only locally, no CI job.** Rejected. Part 12.7 requires the
  baseline to be CI-enforced so regressions block merges, not just be runnable
  on a developer box.
- **Log in per virtual user.** Rejected. With a single seeded account every VU
  drains the same login throttle (anon bucket) plus the same per-user NL/search
  sub-buckets, so per-VU login floods the throttle with `429`s and measures the
  limiter, not the read path. One bootstrap login reused by all VUs models a
  real client session and isolates the read-path SLO.

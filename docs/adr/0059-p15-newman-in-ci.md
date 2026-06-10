# ADR-0059: P15 Postman + Newman in CI

- **Status**: Accepted
- **Date**: 2026-06-10
- **Deciders**: @varadharajaan
- **Tags**: ci, testing, postman, newman, P15

## Context

Part 25.10 requires every REST service to run its Postman collection through
Newman in CI, and the platform already ships six committed collections plus
local environment files (`postman/log-*.postman_collection.json`). Until P14
there was no CI pipeline to host them; the only Newman execution was the local
`scripts/live-e2e/e2e-all.ps1` harness, which is gitignored and cannot run on a
GitHub runner. P14 landed the CI foundation (compose-built images, root
`mvn verify`). P15 adds the Newman job on top.

## Decision

**D1 -- A dedicated `newman` job boots the real compose stack.** The job first
builds the eight service images with `docker build --network=host` (the
multi-stage Dockerfiles fetch from Maven Central, which the default bridge
network cannot reach during `compose up --build`), then runs
`docker compose -f infra/docker/docker-compose.yml up -d` and waits for the
published gateway health endpoint (`http://localhost:8090/actuator/health`)
before executing the six collections. This exercises the actual P10 images and
container-to-container wiring, not a mocked surface, satisfying the Part 26
"live booted server" intent in CI.

**D2 -- Collections run in-network with per-collection URL overrides.** Each
collection runs inside a `postman/newman:6-alpine` container attached to
`cortex-net`, with `--env-var base_url=http://cortex-<service>:<port>` pointing
at the in-network DNS name (gateway 8090, ingest 8092, processor 8095,
remediation 8096, indexer 8097, monitoring 8098). Four collections also reach a
second service, so the job overrides those cross-service URLs too -- processor
`gateway_base_url`/`ingest_base_url`, remediation `wiremock_base_url`, indexer
`quickwit_base_url`, monitoring `prometheus_base_url` -- since the committed
local env files default them to `localhost`, which resolves to the Newman
container itself on `cortex-net` (a cross-service login or pipeline POST against
`localhost` simply errors). The committed local env files keep `localhost`
defaults for developer use; the CI overrides swap only the host. The gateway
local env `tenant_id` is `cortex-dev` (the provisioned dev tenant the compose
stack seeds, matching the other five collections) so the logs/batch route
resolves a real tenant instead of a 400. `--bail` stops a collection at the
first failure (the Auth folder chains state into later requests).

**D3 -- The local `e2e-all.ps1` harness is NOT the CI implementation.** That
script is gitignored (`/scripts/`) and absent on a fresh checkout, so the job
replicates its boot-and-run logic inline in `ci.yml`. The two stay behavior
equivalent (same collections, same in-network targets).

**D4 -- A Newman failure fails the pipeline.** Any non-zero Newman exit fails
the job (Part 25.10.1 / 25.10.2). Compose logs are dumped on failure and the
stack is always torn down with `down -v`.

## Consequences

- Every merge to `main` (and every PR) now proves the six service APIs answer
  correctly against the composed stack, catching contract drift that
  `mvn verify` (in-process MockMvc) cannot.
- The Newman job is heavier than a unit run (it builds and boots the full
  stack), but it is the executable API contract the platform already committed
  to in Part 25.
- The job is independent of the image-publish path, so it gates review without
  needing registry credentials.

## Alternatives Considered

- **Call `scripts/live-e2e/e2e-all.ps1` from CI.** Rejected. `/scripts/` is
  gitignored, so the script does not exist on a CI checkout; the boot-and-run
  logic is inlined instead.
- **Run Newman against `localhost` published ports.** Rejected. Only the gateway
  publishes a host port; the other five services are reachable only on
  `cortex-net`, so the Newman container joins that network and targets the DNS
  names. Cross-service URLs in the collections (a processor pipeline POST that
  loops back through the gateway, etc.) are overridden to their `cortex-net` DNS
  names for the same reason.
- **Mock the services instead of booting compose.** Rejected. Part 26 requires
  a live booted server; a mock would re-introduce the exact gap Newman exists to
  close.

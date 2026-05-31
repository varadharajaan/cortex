# ADR-0019: Transport-protocol policy (gRPC > HTTP/2 > HTTP/1.1; HTTP/3 deferred)

- Status: Accepted
- Date: 2026-05-31
- Phase: cross-cutting (applies P3.x onward)
- Deciders: @varadharajaan
- Tags: transport, grpc, http2, http3, performance, future-stories
- Supersedes: none
- Superseded by: none
- Related: ADR-0002 (monorepo modules), ADR-0004 (REST and GraphQL), ADR-0006
  (AI provider abstraction), ADR-0014 (Spring Cloud Gateway MVC), ADR-0018
  (NL->LogQL Spring AI / Ollama)

## Context

The repo is a Java 17 microservices mesh (Spring Boot 3.3.6 + Spring Cloud
2023.0.4). Today every inter-service hop is **HTTP/1.1 JSON over plain TCP**.
That worked for P0..P3.2 because traffic is single-host localhost and the
choice was driven by what each upstream natively spoke (Eureka REST, Ollama
REST, Spring Cloud Gateway MVC). The user asked an explicit question:

> "can't we use gRPC or HTTPS/3? HTTPS/1.1 REST is old, right? not just
> Ollama -- in general for the repo."

We need a written policy so every future service starts with the right
default and existing services have a documented backlog item to upgrade.

## Decision drivers

- D1. **Reuse over rewrite.** Where the upstream protocol is fixed by a
  third party (Eureka, Ollama, Redis RESP, Quickwit, Loki), the transport
  choice is not ours to make.
- D2. **Local-dev friction stays low.** No QUIC / native libs / cert
  toolchains required to `mvn verify` and run smoke.
- D3. **Internal hops should multiplex.** Anything fan-out heavy (agent ->
  gateway log batches; gateway -> N backends) benefits from h2/h3
  multiplexing over a single TCP/QUIC connection.
- D4. **Schema-first contracts beat hand-rolled JSON** for high-volume
  internal RPCs (ingest, processor, indexer). gRPC's `.proto` is the
  cheapest way to enforce that.
- D5. **Browser traffic must stay HTTP-family.** Browsers don't speak gRPC
  natively; gRPC-Web is a real option but adds a translation hop.
- D6. **Operations cost is real.** h2 needs TLS (in practice), h3 needs a
  QUIC stack, gRPC needs codegen + protobuf review tooling.

## Decision

**Policy: gRPC > HTTP/2 > HTTP/1.1 -- with documented exceptions.**

### Rule R1: New service-to-service RPC defaults to gRPC

Any **new** server-to-server call introduced in P4 or later MUST default to
gRPC (Spring `grpc-server-spring-boot-starter` + `grpc-client-spring-boot-
starter`, or the Spring 6.2+ first-party gRPC support when we move to 6.2).
The contract MUST live in `proto/` at the repo root, owned by the producer
module.

Exceptions are allowed only if at least one of:

- **R1.a** The upstream is a fixed external system that does not speak gRPC
  (Ollama, Azure OpenAI, Loki, Quickwit, Postgres, Redis, Eureka, Slack,
  PagerDuty, Jira). Use whatever protocol that system mandates.
- **R1.b** The downstream is reached by a browser. Use REST or GraphQL
  (already covered by ADR-0004).
- **R1.c** The call is one-shot, low-frequency, and adding protobuf would
  be net negative for the volume (e.g. an admin operation called once at
  boot). Justify in the service's package-info or a focused mini-ADR.

Every exception MUST be recorded in
`docs/transport-deferred-stories.md` so we can revisit it later.

### Rule R2: Where gRPC is not used, HTTP/2 is the floor

If R1 falls through to HTTP, the hop MUST run HTTP/2 in any of these
configurations:

- **HTTPS over h2** (production, TLS terminated at the service).
- **h2c (HTTP/2 cleartext)** for intra-cluster localhost-only dev.

HTTP/1.1 is only allowed where the upstream explicitly does not support
HTTP/2 (Eureka 1.x, Ollama, Redis RESP). Same rule: every HTTP/1.1 hop is
listed in `docs/transport-deferred-stories.md`.

### Rule R3: HTTP/3 (QUIC) is opt-in, edge-only

HTTP/3 lights up only on the **public edge** (browser <-> gateway over a
TLS-terminating LB such as Cloud Front / Azure Front Door / nginx-quic).
Internal hops stay h2 / gRPC. Reason: the JDK 17 `HttpClient` has no h3
support, Spring `RestClient` has no h3 support, and Reactor Netty's h3
support requires native QUIC libs that are painful to bundle in our image
matrix. Revisit when Reactor Netty h3 is GA AND we have a real
mobile/lossy-network use case.

### Rule R4: Browser-facing RPC stays REST + GraphQL

ADR-0004 already governs the public API surface. h2 is added on top
purely as a transport upgrade; the wire format (REST JSON / GraphQL) does
not change.

### Rule R5: Streaming use cases must consider Kafka first

Long-lived bidirectional streams (alert fan-out, log tailing) should go
through the message bus (ADR-0005) before they consider gRPC streaming.
gRPC streaming is only the right answer for short-lived
request/response-with-progress patterns (agent ingest batches with
backpressure, indexer-side push for re-index).

### Rule R6: Same-process, in-VM calls stay JVM calls

Don't introduce gRPC over loopback for two beans in the same JVM. Use
Spring beans / events.

## Decision outcome

**Chosen policy: gRPC > HTTP/2 > HTTP/1.1, with R1.a / R1.b / R1.c
exceptions documented in `docs/transport-deferred-stories.md`. HTTP/3 is
edge-only and deferred until we have a public mobile / WAN client.**

### Positive consequences

- Every new service author has a one-line rule: "is this server-to-server
  and the other end is also our code? Then gRPC."
- All HTTP/1.1 debt is visible in one file and can be planned as future
  user stories without re-discovering them.
- We don't accidentally rewrite stable HTTP/1.1 paths that talk to fixed
  externals (Eureka, Ollama).

### Negative consequences

- gRPC adds protobuf codegen, schema-review discipline, and a separate
  HTTP/2 server thread pool per service.
- Postman / curl debugging is harder for gRPC; we MUST ship grpcurl
  examples in each service's README when a gRPC endpoint lands.
- Service-mesh introspection (e.g. Spring Cloud Gateway logging request
  bodies) doesn't work for gRPC frames. Need OpenTelemetry spans instead.

## Considered options

- **Option A (chosen): tiered policy gRPC > h2 > h1, with documented
  exceptions and an HTTP/3 deferral.** Pragmatic, lets new services adopt
  the modern stack without forcing a rewrite of stable HTTP/1.1 paths to
  fixed externals.
- **Option B: gRPC everywhere immediately.** Rewrites every existing
  controller, requires gRPC-Web in the SPA, breaks compatibility with the
  Postman collection that backs Newman in CI. Massive scope creep with no
  measurable win at our current scale.
- **Option C: stay on HTTP/1.1 everywhere until a perf incident forces a
  change.** Cheapest today, but bakes in technical debt and means every
  future service has to re-litigate this choice. Also wastes the
  multiplexing wins on agent -> gateway ingest where they will matter.
- **Option D: HTTP/3 everywhere internal.** Native QUIC stack on the JVM
  is not production-grade in 2026; Reactor Netty h3 is still incubating.
  Plus zero benefit on localhost / single-AZ.

## Current-state inventory (snapshot at 2026-05-31)

| Hop | Today | Target | Driver |
|---|---|---|---|
| Browser -> `log-gateway` (REST + future GraphQL) | HTTP/1.1 | HTTP/2 (with TLS at edge) | R4 |
| Browser -> `log-gateway` over public WAN | n/a (no edge yet) | HTTP/3 at edge LB only | R3 |
| `log-agent-lib` -> `log-gateway` (ingest, P4+) | HTTP/1.1 (JDK HttpClient) | **gRPC streaming** | R1 + R5 |
| `log-gateway` -> `log-echo-service` (P3.4 stub) | HTTP/1.1 via Spring Cloud Gateway MVC | HTTP/2 (h2c intra-cluster) | R1.b lite (gateway is REST) |
| `log-ingest-service` -> `log-processor-service` (P4 -> P5) | new in P4 | **gRPC** | R1 |
| `log-processor-service` -> `log-indexer-service` (P5 -> P7) | new in P5 | **gRPC** | R1 |
| `log-processor-service` -> `log-remediation-service` (P5 -> P6) | new in P5 | **gRPC** | R1 |
| `log-monitoring-service` -> all (P8) | new in P8 | **gRPC** (pull) + Kafka (push events) | R1 + R5 |
| `log-gateway` -> Ollama / Azure OpenAI | HTTP/1.1 (just pinned in P3.3) | HTTP/1.1 forever | R1.a (upstream) |
| `log-gateway` -> Redis | RESP / TCP via Lettuce | RESP forever | R1.a (Lettuce binary) |
| Service <-> Eureka | HTTP/1.1 REST | HTTP/1.1 forever | R1.a (Eureka 1.x) |
| Service <-> Quickwit / Loki | HTTP/1.1 REST | HTTP/2 if Quickwit / Loki supports | R1.a today; revisit per version |
| Service <-> Postgres | TCP libpq | TCP forever | R1.a |
| Inter-service async (P5+ alerts, fan-out) | n/a | Kafka | R5 (ADR-0005) |

## Implementation policy

1. New services added P4 onward: scaffold step MUST include
   `<artifactId>grpc-spring-boot-starter</artifactId>` for the producer
   and `grpc-client-spring-boot-starter` for the consumer, plus a
   `proto/<service>.proto` contract.
2. Existing HTTP/1.1 hops continue working; no rewrite until the
   corresponding deferred story is picked up.
3. Every new sub-phase's plan.md row MUST explicitly state the chosen
   transport ("h2c intra-cluster", "gRPC", "HTTP/1.1 because Eureka").
4. `agent-strict-rules-prompts.md` will get a new rule B22 in the next
   amendment pointing here (out of P3.3 scope -- captured as a
   deferred-story).
5. Smoke scripts MUST cover the chosen transport (e.g. grpcurl for gRPC
   endpoints) so we don't get "fine in tests, broken on wire" surprises.

## Links

- ADR-0002 monorepo modules
- ADR-0004 REST + GraphQL
- ADR-0005 message bus
- ADR-0006 AI provider abstraction
- ADR-0014 Spring Cloud Gateway MVC
- ADR-0018 NL->LogQL via Spring AI
- `docs/transport-deferred-stories.md` (the backlog this ADR feeds)
- Spring gRPC: https://github.com/spring-projects/spring-grpc
- Reactor Netty HTTP/3: https://github.com/reactor/reactor-netty

# 0016 - CORTEX uses Eureka for local-dev service discovery (K8s-native in prod)

Status: Accepted
Date: 2026-05-30
Deciders: CORTEX core engineering
Tags: P3, gateway, discovery, eureka, local-dev, infra

## Context

P3.1 closed `log-gateway` auth (login + refresh + bearer parsing). The
next P3 sub-phases need a real downstream service so Part 21 Level 4
(downstream consumer verification) is honestly satisfiable:

- **P3.2** Bucket4j+Redis rate limiting must demonstrate throttling on
  PROXIED traffic, not just on `GET /api/v1/health` (which is a local
  gateway endpoint).
- **P3.3** NL->LogQL routing via Spring AI must show end-to-end calls
  out of the gateway to a service that returns a LogQL-shaped body.
- **P3.4** RouteLocator `/logs/**` and `/search/**` must resolve via
  `lb://<service-id>` to a live downstream registered in a discovery
  registry.

LD23 (DONE = Part 21 Levels 1-6 proven on terminal) makes silent skips
of Level 4 a contract break. LD24 (recorded 2026-05-30) approved
**Option 2**: insert sub-phase P3.0b before P3.2 to stand up the
minimum infrastructure required.

This ADR records the discovery-registry choice for that sub-phase.

## Decision drivers

- **Stack consistency**: rest of CORTEX is Spring Boot 3.3.5 + Spring
  Cloud 2023.0.4. Spring Cloud Netflix Eureka 4.1.x is the in-BOM
  discovery client/server for that BOM line.
- **Gateway integration**: `spring-cloud-starter-gateway-mvc`
  consumes the `DiscoveryClient` bean directly to resolve
  `lb://<service-id>` predicates. Mocking that contract is more work
  than running a 50MB Eureka server locally.
- **Local-dev parity (Part 22)**: every external prod dependency must
  have a local equivalent. The Day-1 prod target is AKS, where the
  native discovery mechanism is Kubernetes `Service` DNS, NOT a
  registry server. We need a Day-1 LOCAL equivalent of K8s service
  resolution; Eureka is the simplest one Spring Cloud ships.
- **Throwaway scope**: the registry is needed only until the full
  `infra/docker/` compose stack lands in P10 (which standardises on
  Kubernetes for prod). It must NOT become a long-term operational
  surface.
- **Zero-secret bootstrap**: a developer must be able to `mvnw
  spring-boot:run` the gateway and have it find `log-echo-service`
  with zero credentials.

## Considered options

### Option A - Eureka standalone (Spring Cloud Netflix)
- Pros: one annotation (`@EnableEurekaServer`), zero ops, well-
  documented Spring Cloud feature; matches the gateway's `lb://`
  predicates with no glue code; runs in 50MB heap.
- Cons: Netflix OSS is in maintenance mode upstream; we are deliberately
  scoping it to LOCAL only.

### Option B - Spring Cloud Consul / Zookeeper
- Pros: actively maintained upstream.
- Cons: adds an operational dependency (Consul agent or ZK ensemble);
  bootstrap is non-trivial for a developer's first run; configuration
  surface is much larger than we need.

### Option C - WireMock-stubbed `DiscoveryClient`
- Pros: zero new process.
- Cons: stubs the `DiscoveryClient` interface but does NOT load-balance
  real HTTP calls; would force every downstream test to also stub the
  HTTP server; this is twice the work of running real Eureka.

### Option D - Kubernetes-native discovery in dev (kind / minikube)
- Pros: closest to prod.
- Cons: forces a kind/minikube cluster as a hard prerequisite for
  `mvnw spring-boot:run`; massive friction for a sub-phase whose
  purpose is the simplest possible Level 4 fixture.

### Option E - Hard-code downstream URLs in dev (no discovery)
- Pros: simplest possible.
- Cons: breaks the `lb://` predicate contract that P3.4 needs; means
  P3.4 cannot honestly satisfy Part 21 Level 4 without ALSO standing
  up discovery; defers the same work with extra rework cost.

## Decision outcome

**Chosen: Option A - Spring Cloud Netflix Eureka standalone.**

Concrete shape for v0.1.0:

- A 1-class standalone Spring Boot app under
  `infra/eureka/eureka-server/` (NOT a Cortex Maven module; it is
  NEVER published to any registry):
  - `@SpringBootApplication`
  - `@EnableEurekaServer`
  - port 8761
  - `eureka.client.register-with-eureka=false`
  - `eureka.client.fetch-registry=false`
  - actuator `/actuator/health` exposed for the smoke runbook
- Run via its own `infra/eureka/eureka-server/mvnw spring-boot:run`
  OR via `docker compose -f infra/local/docker-compose.smoke.yml up
  eureka`.
- `log-echo-service` (added in the SAME sub-phase as a published
  Cortex Maven module) registers with the standalone Eureka via
  `spring-cloud-starter-netflix-eureka-client` -> `eureka.client.
  serviceUrl.defaultZone=http://localhost:8761/eureka/`.
- `log-gateway` adds the same client starter and resolves
  `log-echo-service` via the live `DiscoveryClient` bean.

## Consequences

Positive:

- Smallest possible Day-1 infra surface (one extra Java process).
- `spring-cloud-starter-gateway-mvc` `lb://` predicates work as
  documented with zero glue.
- Eureka self-registration explicitly turned OFF so the registry
  never advertises itself.
- A developer can run gateway + log-echo + Eureka with three
  `mvnw spring-boot:run` invocations.

Negative / Open:

- Netflix OSS upstream is maintenance-only. Acceptable because the
  registry is LOCAL ONLY and is replaced by Kubernetes `Service`
  DNS in prod.
- Adds two ports to the local-dev surface (8761 Eureka, the
  echo-service's 8090).

Re-evaluation triggers:

- When P10 lands the full compose stack: fold
  `infra/local/docker-compose.smoke.yml` into `infra/docker/` and
  re-confirm Eureka is still the cheapest option.
- When `log-ingest-service` (P4) and `log-query-service` (P7-ish)
  land: delete `log-echo-service` and re-point gateway routes at
  the real services.
- When Kubernetes-native discovery is wired (P11/P12): delete the
  Eureka server, switch the gateway client to `spring-cloud-
  starter-kubernetes-discoveryclient` (or remove discovery entirely
  in favor of K8s DNS). The `lb://` predicate stays the same.

## Related

- ADR-0014 - log-gateway uses Spring Cloud Gateway MVC
- LD22 (memory.md) - live HTTP smoke is mandatory
- LD23 (memory.md) - DONE means Part 21 Levels 1-6 on the terminal
- LD24 (memory.md) - P3.0b approved as the Level 4 prerequisite
- LD25 (memory.md) - Windows live-smoke gateway lifecycle
- Part 22 (strict rules) - local environment parity
- Part 23 (strict rules) - service discovery
- Part 26 (strict rules) - Postman/Newman live execution gate

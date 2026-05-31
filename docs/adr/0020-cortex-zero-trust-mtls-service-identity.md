# ADR-0020: Zero-trust service-to-service trust model (mTLS + service identity)

- Status: **Accepted -- MANDATORY**. Policy + scaffold standard in force
  from this commit; full enforcement in `-Pstaging` / `-Pprod` lands in
  P5.1..P5.5; **every new service from P4 onward MUST scaffold mTLS-
  ready from day one** (see "Scaffold standard" below).
- Date: 2026-05-31 (amended 2026-05-31: escalated from "deferred until
  P5" to "mandatory scaffold readiness from P4+" per user directive
  "mTLS is must for all communication").
- Phase: cross-cutting (scaffold MUST land in every P4+ service; full
  enforcement rollout P5.1..P5.5)
- Deciders: @varadharajaan
- Tags: security, zero-trust, mtls, spiffe, service-identity,
  mandatory-scaffold
- Supersedes: none
- Superseded by: none
- Related: ADR-0015 (JWT resource server), ADR-0009 (tenant isolation),
  ADR-0019 (transport protocols)

## Context

Today the repo enforces auth **at the edge only**: the SPA presents a JWT
to `log-gateway`; everything behind the gateway is on a flat, plaintext
HTTP/1.1 LAN that trusts the network. That is fine for single-host dev,
but it is **not** zero-trust:

- Gateway -> echo (P3.4) will be unauthenticated HTTP.
- Gateway -> Ollama / Azure OpenAI is plaintext.
- Service -> Eureka and service -> Redis are unauthenticated.
- A compromised pod in the same VPC can read or impersonate any internal
  call.
- We have no audit trail of *which* service made a given downstream call;
  we only have the user JWT.

The user asked explicitly:

> "are we using mTLS for service-to-service even within network for
> zero-trust?"

The honest answer was "no, not today." This ADR captures the target
posture so we can budget the work as P5.1..P5.5 user stories without
re-deciding from scratch later.

## Decision drivers

- D1. **One identity per workload.** Every Spring Boot service ships with
  its own keypair + certificate, not a shared truststore secret.
- D2. **No code changes per service** for the common case. Use Spring Boot
  `SslBundle` so each module reads its identity from a uniform config
  block.
- D3. **Cheap rotation.** Certs MUST be rotatable without a service
  restart in steady state (P5 acceptance criterion).
- D4. **Service-mesh-friendly.** If we eventually move to Linkerd / Istio
  on AKS (ADR pending), mTLS becomes free; our in-app implementation MUST
  be removable without touching business code.
- D5. **Defence in depth.** mTLS proves *identity*, not *permission*. We
  still need an authorisation layer on top (which service is allowed to
  call which endpoint).
- D6. **Don't block dev.** A developer running `mvn verify` MUST NOT need
  to install a CA by hand. Dev profile uses an **auto-provisioned local
  CA** (step-ca container booted by the same `docker-compose.smoke.yml`
  that boots Redis/WireMock) -- plaintext is forbidden in any committed
  profile from P4 onward. `mvn verify` works because Testcontainers
  spins step-ca on demand.
- D7. **mTLS is the only acceptable transport for inter-service IPC.**
  No new service may ship a plaintext inbound listener or outbound
  client targeting another in-repo service. Hops to fixed externals
  (Ollama, Eureka 1.x, Loki, Quickwit, Postgres, Redis) MUST route
  through a TLS-terminating sidecar in prod when the external itself
  cannot speak mTLS -- the in-repo side of the call ALWAYS speaks mTLS.

## Decision

**Policy is MANDATORY effective immediately. The 5-phase rollout under
P5 stays as the IMPLEMENTATION schedule for enforcement in
`-Pstaging` / `-Pprod`, but every NEW service from P4 onward MUST
scaffold mTLS-ready code on day one (see "Scaffold standard" below).
Existing P3.x services (`log-gateway`, `log-echo-service`,
`log-agent-lib`) are retrofitted under deferred-stories rows S-10..S-13.**

### Scaffold standard (mandatory for every new Spring Boot service P4+)

Every new service module MUST ship the following on its first commit.
CI will add an ArchUnit / static-config check for these in P5.1; until
then, code review enforces.

1. **`application.yml`** carries a `spring.ssl.bundle.jks.cortex-service`
   block pointing at a `classpath:/cert/<service>-keystore.p12` (a dummy
   self-signed cert checked into the module for dev; replaced by
   `step-ca`-issued cert in `-Pstaging` / `-Pprod`).

   **Local-mode coverage**: this classpath bundle is the answer for
   BOTH local modes in the repo today (per LD24): (a) services started
   manually by a developer via `.\mvnw.cmd spring-boot:run` on the
   Windows host, and (b) services started detached by
   `scripts/smoke-p3-*.ps1`. Neither runs inside the
   `docker-compose.smoke.yml` stack -- that stack only hosts Redis +
   WireMock (+ step-ca from row S-13 onward). The classpath cert means
   the Spring app boots with a working SslBundle **before** step-ca is
   reachable, so `mvn verify` does not need Docker. Once step-ca is up
   (P5.1), a Windows-host script (`scripts/issue-local-cert.ps1`, row
   S-15) overwrites the classpath placeholder with a real step-ca-
   issued cert at the same path, no service restart logic required
   beyond the existing Spring `SslBundle` reload.
2. **`application-prod.yml` / `application-staging.yml`** override that
   block to `enabled: true` + `server.ssl.enabled=true` +
   `server.ssl.client-auth=need` + `server.ssl.bundle=cortex-service`.
   These profiles MUST refuse to start if the bundle is missing.
3. **Every outbound client** (Spring `RestClient`, `WebClient`,
   `OllamaApi` HTTP client, Lettuce `RedisClient`, gRPC `ManagedChannel`)
   is constructed through an `SslBundles` lookup, NOT a hardcoded
   `HttpClient`. Wire via the shared `cortex-service-clients`
   auto-config in `log-agent-lib` (to be added in P4).
4. **Service identity** is encoded as cert SAN
   `spiffe://cortex/<service-name>` (placeholder local cert in dev, real
   step-ca-issued cert in staging/prod). The service exposes
   `GET /actuator/info` with `cortex.identity.spiffe` so a probe can
   verify the running identity.
5. **Server-side service-JWT filter** is wired in the security chain
   even in dev (no-op when service JWT is absent). This avoids "works in
   dev, breaks in prod" surprises when P5.4 lands.
6. **A `*-mtls-ready.smoke.ps1` script** under `scripts/` boots the
   service with mTLS enabled and proves: (a) handshake succeeds with a
   client cert, (b) handshake is refused without one. Required before
   the sub-phase's plan.md row can be marked `[x]`.
7. **Module README** has an "mTLS posture" section linking back to this
   ADR and listing any deferred-story rows the service owns.

Non-compliance with items 1..7 is treated as a blocking review comment,
not a nit. The cost of scaffolding mTLS-ready from day one is roughly
one morning per service; retrofitting later is days per service plus
the migration risk.

### P5.1 - Local CA + per-service cert issuance

- Use `step-ca` (CNCF, MIT-licensed, zero-dep Go binary) as the dev/test
  CA. In prod, swap to `cert-manager` issuing from an Azure Key Vault
  CA.
- Each service gets a cert with SAN
  `spiffe://cortex/<service-name>` (e.g.
  `spiffe://cortex/log-gateway`). SPIFFE IDs even without SPIRE today,
  so we are forward-compatible.
- Cert validity: 24h in dev, 7d in prod with auto-renewal at 50% TTL.
- Bundle format: PKCS#12, loaded via Spring Boot `SslBundle` so the
  application.yml block is identical across modules.

### P5.2 - mTLS for `log-gateway` <-> backend (smallest blast radius)

- Enable `server.ssl.enabled=true` + `server.ssl.client-auth=need` on
  every backend (echo, ingest, processor, indexer, remediation,
  monitoring).
- Wire the gateway's `RestClient` / Reactor Netty `HttpClient` to the
  same `SslBundle` so it presents its identity.
- Eureka registration switches from `nonSecurePort` to `securePort` with
  `secureVipAddress`. Gateway's `RouteLocator` (P3.4) resolves over
  `lb://` and the gateway side handles TLS internally.

### P5.3 - mTLS to Redis + Eureka

- Redis: enable TLS on 6379 (Redis >= 6 ships built-in TLS); add ACL per
  service (`user cortex-gateway >dev-pw on ~cortex:rl:* +@read +@write`).
- Eureka: enable HTTPS on the Eureka server, switch clients to
  `securePortEnabled: true`. Eureka 1.x mTLS is partial -- if it does
  not satisfy us, treat as a deferred story to move to Consul/Nacos
  (Option D below).

### P5.4 - Service-identity JWT claim + downstream authorisation

mTLS proves "you are `log-gateway`"; it does not prove "you are allowed
to call `log-ingest-service:/internal/replay`". Layer on top:

- Each service mints a short-lived (60s) service JWT signed with its
  identity cert (or with a shared signer in prod via Vault transit
  engine). Claims include `iss` (caller service id), `aud` (callee), and
  `act.sub` (acting-as user, copied from the inbound user JWT).
- Spring Security on the callee validates the service JWT against its
  trust list. Refusal -> 403 with `errorCode=SERVICE_FORBIDDEN`.
- This is the same `act` claim shape OAuth 2.0 Token Exchange (RFC 8693)
  uses, so we stay standards-aligned.

### P5.5 - Secrets backend

- Move JWT signing key, Redis password, keystore passphrases, Argon2
  pepper, Spring AI provider keys out of env vars and YAML.
- Dev: SOPS-encrypted `.env` files committed to the repo.
- Prod: Azure Key Vault accessed via workload-identity (CSI driver on
  AKS). Per Part 24, no plaintext secrets in any pipeline log.

### Observability

- Every mTLS handshake failure emits a structured log line
  (`event=mtls_handshake_failed peer_san=... reason=...`) and a
  Micrometer counter `cortex.security.mtls.failures{reason}`. Alert if
  rate > 0 for 5m in prod.
- Service-JWT validation failures emit
  `event=service_jwt_rejected caller=... callee=... reason=...`.

## Considered options

- **Option A (chosen): in-app mTLS now (P5.x), service-mesh later if we
  go to k8s.** Self-contained, no new infra, works on bare VMs and in
  k8s alike. Future move to Linkerd/Istio can remove the in-app SSL
  config without touching business code.
- **Option B: skip mTLS, rely on network-layer isolation (VPC + NACLs +
  k8s NetworkPolicy).** Cheaper to ship; fails zero-trust on day one
  (any pod compromise gives unrestricted lateral movement). Rejected.
- **Option C: adopt a service mesh (Linkerd / Istio) for transparent
  mTLS.** The right answer once we are on k8s. Premature today: we have
  no k8s, no cluster, and Linkerd's bare-VM story is poor. Treat as a
  P11+ ADR (after `infra/helm/`).
- **Option D: replace Eureka with Consul/Nacos (which speak mTLS
  natively).** Sensible only if Eureka 1.x mTLS proves a blocker in
  P5.3. Tracked as a deferred story.
- **Option E: SPIFFE + SPIRE end-to-end.** The "right" zero-trust
  primitive, but a multi-week setup. Encode the IDs now
  (`spiffe://cortex/...`) so we can adopt SPIRE later without renaming.

## Decision outcome

**Chosen: Option A (in-app mTLS now-mandatory scaffold + P5.1..P5.5
enforcement, SPIFFE-shaped service IDs, service JWT for authorisation,
Azure Key Vault for secrets). Service-mesh (Option C) is the explicit
future migration path; SPIRE (Option E) is encoded but not adopted.**

### Positive consequences

- Real zero-trust posture in prod: every internal hop is mutually
  authenticated and authorised, no exceptions inside our codebase.
- New services are born mTLS-ready -- no "phase N migration" risk.
- Dev does not regress: step-ca container makes `mvn verify` a
  zero-touch experience for the developer (no CA install, no manual
  cert generation).
- Service-mesh migration is unblocked (the in-app SSL block becomes
  redundant, not contradictory).
- Compliance posture (SOC 2 CC6.6, ISO 27001 A.13.1.1) gets evidence:
  every internal call is mutually authenticated.

### Negative consequences

- Operational cost: a CA to run, certs to rotate, alerts to tune.
- Cold-start CPU cost: handshake on every new TCP connection. Mitigate
  with connection pooling (already in Lettuce + RestClient).
- Adds keystore passphrases to the secrets surface (P5.5 must land
  alongside P5.1; until then dev keystores use a fixed dev-only passphrase
  `cortex-dev-only` documented in module README).
- Every new service costs ~half a day of scaffold work up front. Trade-
  off accepted because retrofitting is days per service.
- Fixed-external hops (Ollama, Eureka 1.x, Loki, Quickwit, Postgres,
  Redis) need TLS-terminating sidecars in prod when the external cannot
  speak mTLS natively. Captured as a permanent operational requirement,
  not a code requirement.

## Acceptance criteria

### Per new service (P4+ scaffold, enforced at code review)

- SC-1: items 1..7 of "Scaffold standard" above are present in the
  first commit that introduces the service.
- SC-2: `*-mtls-ready.smoke.ps1` exists and exits 0 with mTLS on, exit
  non-zero without a client cert.
- SC-3: module README contains an "mTLS posture" section.
- SC-4: any plaintext outbound to a fixed external is recorded as a row
  in `docs/transport-deferred-stories.md` section B (S-10 onward).

### Per rollout sub-phase

- **P5.1**: `step-ca` container is part of `docker-compose.smoke.yml`;
  scripted issuance of a cert for any service name; `mvn verify` passes
  with `-Pdev` against step-ca-issued certs (no longer dummy classpath
  certs).
- **P5.2**: gateway -> echo over h2 + mTLS in `-Pstaging`; plaintext
  call refused with TLS alert; smoke covers both happy and refused
  cases.
- **P5.3**: Redis CONNECT fails without a client cert; Eureka register
  fails without a client cert (or T-13 escalation triggers).
- **P5.4**: callee returns 403 `SERVICE_FORBIDDEN` when service JWT is
  missing/expired/wrong-audience; happy path includes both user JWT and
  service JWT, both validated.
- **P5.5**: zero secrets in env vars in `-Pprod`; pipeline reads from
  Key Vault via workload identity.

## Links

- ADR-0009 tenant isolation (per-tenant data plane)
- ADR-0015 log-gateway JWT resource server (this is the user-JWT side)
- ADR-0019 transport protocols (h2/gRPC require TLS in practice)
- `docs/transport-deferred-stories.md` (P5.x stories tracked here)
- SPIFFE: https://spiffe.io
- Spring Boot SSL Bundles:
  https://docs.spring.io/spring-boot/reference/features/ssl.html
- Azure Key Vault CSI driver:
  https://learn.microsoft.com/azure/aks/csi-secrets-store-driver

# Transport + zero-trust deferred stories

> Companion file to **ADR-0019** (transport protocols) and **ADR-0020**
> (zero-trust mTLS). Every entry here is a backlog user story scheduled
> for P5+ unless tagged otherwise. New entries land here whenever a
> sub-phase chooses HTTP/1.1 plaintext over the ADR-0019 / ADR-0020
> target.

Legend: `[ ]` not started | `[-]` in progress | `[x]` done | `[!]` blocked

---

## Section A - Transport upgrades (ADR-0019)

| Id | Hop | Today | Target | Phase | Notes |
|----|-----|-------|--------|-------|-------|
| T-01 | Browser -> `log-gateway` | HTTP/1.1 plaintext | HTTP/2 over TLS at edge | P5.2 | Needs TLS terminator (nginx / Azure Front Door); h2 lights up automatically. |
| T-02 | Public edge LB | n/a (no edge yet) | HTTP/3 (QUIC) at edge only | P12 | Only when we have a public domain + Azure Front Door / CloudFront. Not for internal hops. |
| T-03 | `log-agent-lib` -> `log-gateway` (ingest, P4+) | n/a today | **gRPC streaming** (`Logs.Ingest` bidi stream with backpressure) | P4 | Schema lives in `proto/cortex/agent/v1/ingest.proto`. Fall back to HTTP/2 if gRPC client matrix (Logback embedder JVM 8 callers) forces it; record deviation here. |
| T-04 | `log-gateway` -> `log-echo-service` (P3.4) | will ship as HTTP/1.1 via Spring Cloud Gateway MVC | h2c intra-cluster | P5.2 | Stub service; not worth a gRPC contract since the hop is replaced by `log-ingest-service` in P4. |
| T-05 | `log-ingest-service` -> `log-processor-service` | n/a (new in P4 -> P5) | **gRPC** unary + server-streaming for batch ack | P5 | Producer owns `proto/cortex/ingest/v1/processor.proto`. |
| T-06 | `log-processor-service` -> `log-indexer-service` | n/a (new in P5 -> P7) | **gRPC** | P7 | Producer owns `proto/cortex/processor/v1/indexer.proto`. |
| T-07 | `log-processor-service` -> `log-remediation-service` | n/a (new in P5 -> P6) | **gRPC** + Kafka fan-out (ADR-0005) | P6 | gRPC for synchronous "did we fire?"; Kafka for async event log. |
| T-08 | `log-monitoring-service` -> all | n/a (new in P8) | **gRPC** pull (`/health/v1`) + Kafka for events | P8 | Avoid Prometheus HTTP/1.1 scrape pattern internally; expose Micrometer over gRPC. |
| T-09 | Service -> Quickwit | will ship as HTTP/1.1 | HTTP/2 if/when Quickwit version supports h2 server-side | P7 | Re-check Quickwit release notes at P7 kick-off. |
| T-10 | Service -> Loki | will ship as HTTP/1.1 | HTTP/2 (Loki 2.9+ supports h2c) | P7 | Flip a single client property; not blocked by Loki. |
| T-11 | Service -> Ollama (NL->LogQL) | HTTP/1.1 pinned (ADR-0018, P3.3) | HTTP/1.1 forever | n/a | R1.a exception: Ollama server is HTTP/1.1 only. Documented permanent deviation. |
| T-12 | Service -> Eureka | HTTP/1.1 REST | HTTP/1.1 forever (or replace Eureka in T-13) | n/a | R1.a exception: Eureka 1.x is HTTP/1.1. |
| T-13 | Replace Eureka with Consul/Nacos | Eureka 1.x | Consul or Nacos (gRPC + mTLS native) | P5.3 | Trigger only if mTLS on Eureka 1.x (ADR-0020 P5.3) proves blocking. |
| T-14 | Service -> Redis | RESP binary (Lettuce) | RESP forever | n/a | R1.a exception: not HTTP-family. TLS handled separately (S-03). |
| T-15 | Service -> Postgres | libpq TCP | libpq forever | n/a | R1.a exception: not HTTP-family. TLS handled separately (S-04). |
| T-16 | Inter-service async (alerts, fan-out) | n/a | Kafka (ADR-0005) | P5..P8 | R5 rule: streaming use cases consider message bus first. |
| T-17 | Codify in `agent-strict-rules-prompts.md` (B22 transport rule) | not in rulebook | rule B22 references ADR-0019 | next strict-rules amendment | Out of P3.3 scope. |

---

## Section B - Zero-trust + secrets (ADR-0020)

| Id | Scope | Today | Target | Phase | Notes |
|----|-------|-------|--------|-------|-------|
| S-01 | Local CA + per-service certs | none | `step-ca` (dev), `cert-manager` + Azure KV (prod); SAN = `spiffe://cortex/<service>` | P5.1 | PKCS#12 bundle, loaded via Spring Boot `SslBundle`. |
| S-02 | mTLS gateway <-> backends | plaintext HTTP/1.1 | mTLS over h2 (`client-auth=need`) | P5.2 | Smallest blast radius; pairs with T-04. |
| S-03 | Redis TLS + ACL | plaintext, no AUTH | TLS on 6379, ACL per service | P5.3 | Redis >= 6 native TLS. |
| S-04 | Eureka mTLS | plaintext | TLS + client-cert; OR replace per T-13 | P5.3 | If Eureka 1.x mTLS is partial, escalate T-13. |
| S-05 | Service-identity JWT (RFC 8693 token-exchange shape) | none | short-lived (60s) service JWT with `iss` / `aud` / `act.sub` | P5.4 | Authorisation on top of mTLS identity. |
| S-06 | Secrets backend | env vars + YAML | SOPS for dev, Azure Key Vault + CSI driver for prod | P5.5 | Removes plaintext signing keys from process env. |
| S-07 | mTLS observability | none | structured log + `cortex.security.mtls.failures{reason}` counter + alert | P5.x | Alert if rate > 0 for 5m in prod. |
| S-08 | Service-mesh migration (Linkerd / Istio) | none | transparent mTLS via sidecar | P11+ | Once we are on k8s with Helm. In-app SSL block becomes redundant (not contradictory). |
| S-09 | SPIFFE / SPIRE | SPIFFE-shaped SANs only | SPIRE attestation + JWT-SVID | post-P11 | IDs are forward-compatible today. |
| S-10 | `log-gateway` mTLS-ready scaffold retrofit | plaintext, no SslBundle | SslBundle block + outbound clients via SslBundles lookup + SPIFFE SAN + service-JWT filter wired (no-op in dev) | P4 prep (must land before P5.2 enforcement) | Existing service predates LD39. Retrofit work isolated from feature work. |
| S-11 | `log-echo-service` mTLS-ready scaffold retrofit | plaintext, no SslBundle | same checklist as S-10 | P4 prep | Stub service; cheap to retrofit. |
| S-12 | `log-agent-lib` shared `cortex-service-clients` autoconfig (SslBundles-aware RestClient / WebClient / gRPC factory) | none | new auto-config module + `META-INF/spring/...AutoConfiguration.imports` | P4 (lands with `log-ingest-service`) | Pre-req for SC-1 item 3 (outbound clients via SslBundles lookup). New services import the autoconfig, get mTLS-ready clients for free. |
| S-13 | `step-ca` container in `infra/local/docker-compose.smoke.yml` + bootstrap script | none | step-ca service exposing ACME on port 9000; init container issues per-service certs on `mvn verify` | P5.1 | Replaces dummy classpath keystores from the scaffold standard. Local-only; prod uses cert-manager + Azure Key Vault. |
| S-14 | WireMock HTTPS for local smoke | HTTP/1.1 plaintext on host 8094 | HTTPS on host 8443 with a step-ca-issued cert; mappings unchanged | P5.1 (pairs with S-13) | Even stub upstreams should be encrypted in local-smoke so the gateway exercises its TLS path. Ollama itself still HTTP/1.1 plaintext in prod (R1.a exception per T-11) -- prod adds a TLS-terminating sidecar. |
| S-15 | `scripts/issue-local-cert.ps1` (Windows host helper) | n/a | PowerShell script: detects step-ca container, calls `step ca certificate <svc>.<dev.local> <svc>-keystore.p12 <svc>-key.p12`, writes to each module's `target/cert/` so Spring `SslBundle` picks it up over the classpath placeholder | P5.1 (pairs with S-13) | Covers the local mode where services run from `.\mvnw.cmd spring-boot:run` on the Windows host (not inside compose). Without this row, devs would have to hand-extract certs from the step-ca container -- friction tax violates D6. |

---

## Conventions

- **Owner**: producer module of the hop. The producer's package-info or
  service README MUST link back to the row id above.
- **Adding a row**: any sub-phase that chooses a lower-tier transport
  (HTTP/1.1 over h2; h2 over gRPC; plaintext over mTLS) MUST add a row
  here in the SAME commit as the implementation. CI does not enforce
  this yet -- treat it as a code-review checklist item.
- **Closing a row**: mark `[x]` with the commit range, CI run id, and
  the ADR (if any) that captured the rollout. Do NOT delete completed
  rows; they are the audit trail.
- **Adding a new exception class** (beyond R1.a / R1.b / R1.c in
  ADR-0019): amend ADR-0019, do not just add a row here.

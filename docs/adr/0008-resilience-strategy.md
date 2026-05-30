# 0008. Resilience4j wraps every egress call

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: resilience, fault-tolerance, dependencies

## Context and problem statement

Every CORTEX service calls at least one external dependency: another
service, a database, the message bus, an AI provider, or a remediation
target. Failures in one dependency must not cascade. Slow dependencies
must not exhaust thread pools. What is the standard pattern?

## Decision drivers

- Every egress call must have timeouts.
- Failing dependencies must be tripped open after N consecutive failures.
- Retries must be bounded and use jitter.
- The pattern must be uniform across all services.
- Observability: every protector must emit metrics scraped by Prometheus.

## Considered options

- **Resilience4j 2.2.0** with annotation-based wrappers.
- **Spring Retry + Hystrix** (Hystrix is in maintenance mode).
- **Hand-rolled `CompletableFuture` plumbing**.
- **Service mesh (Istio / Linkerd)** for cross-service resilience only.

## Decision outcome

Chosen option: **Resilience4j 2.2.0** with the full stack of annotations
applied to every egress method:

```java
@CircuitBreaker(name = "azureOpenAi", fallbackMethod = "rulesOnlyFallback")
@Retry(name = "azureOpenAi")
@RateLimiter(name = "azureOpenAi")
@TimeLimiter(name = "azureOpenAi")
public CompletableFuture<EnrichmentResult> enrich(LogEntry entry) { ... }
```

### Standard configuration

```yaml
resilience4j:
  circuitbreaker:
    instances:
      default:
        sliding-window-size: 50
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 5
  retry:
    instances:
      default:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
  timelimiter:
    instances:
      default:
        timeout-duration: 5s
        cancel-running-future: true
  ratelimiter:
    instances:
      default:
        limit-for-period: 200
        limit-refresh-period: 1s
        timeout-duration: 0
```

Each named instance (`azureOpenAi`, `postgres`, `loki`, etc.) overrides
only the fields that differ from `default`.

### Service-mesh complement

Resilience4j handles the in-process side. A future service mesh (Istio)
would add network-side resilience (retries on connection refused,
locality-aware load balancing). The two are complementary, not
substitutes.

### Positive consequences

- Uniform pattern across all seven services.
- Metrics out of the box (Micrometer); dashboards auto-derive panels.
- Fallbacks make degraded operation explicit and easy to test.
- No code in `try/catch` ladders for retry / timeout / fallback.

### Negative consequences

- Annotation discovery requires Spring AOP, which the project already
  uses for transactions; no extra config burden.
- Misconfigured fallbacks can mask real failures. Mitigated by a unit
  test on every fallback verifying it emits the `degraded` metric.

## Pros and cons of the options

### Resilience4j 2.2.0

- **Good**, modular, annotation-driven, first-class Micrometer integration.
- **Bad**, learning curve for AOP-style configuration.

### Spring Retry + Hystrix

- **Good**, familiar.
- **Bad**, Hystrix is unmaintained; rate limiter and time limiter are missing.

### Hand-rolled CompletableFuture plumbing

- **Good**, no dependency.
- **Bad**, every team rolls a slightly different pattern; no metrics by default.

### Service mesh only

- **Good**, language-agnostic.
- **Bad**, can't express AI-provider fallbacks (in-process choice).

## Links

- [Resilience4j docs](https://resilience4j.readme.io/docs).
- [ADR-0006](./0006-ai-provider-abstraction.md).

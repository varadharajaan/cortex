# 0011. Observability: OpenTelemetry + Micrometer + Loki appender

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: observability, otel, micrometer, grafana

## Context and problem statement

CORTEX is a log management system; it must dog-food its own
observability story. We need traces, metrics, and self-logs flowing to
a single observability stack with zero per-service custom code beyond
adding a single dependency.

## Decision drivers

- One SDK per signal type (no per-vendor lock-in).
- Auto-instrumentation must cover Spring Web, Spring Data JPA, the
  message bus, Resilience4j, and outbound HTTP.
- Self-logs must land in the same Loki cluster the system serves.
- Tenant context (`tenant.id`) must flow through every signal.
- Local dev must work without a cloud APM.

## Considered options

- **OpenTelemetry SDK (traces) + Micrometer (metrics) + loki4j
  appender (logs)** -> Tempo + Prometheus + Loki via OTLP/native.
- **Spring Cloud Sleuth** (now deprecated) + Micrometer + custom
  Loki sender.
- **Vendor APM (Datadog / New Relic)** agents only.
- **Elastic APM agent** + Logback ECS encoder.

## Decision outcome

Chosen option: **OpenTelemetry SDK + Micrometer + loki4j Logback
appender**, exporting to Tempo (traces), Prometheus (metrics), and
Loki (logs). All three are provisioned in local Docker Compose and in
the Helm chart for cluster deployment. Grafana is the single pane.

### Per-service dependencies

```xml
<dependency>
  <groupId>io.opentelemetry.instrumentation</groupId>
  <artifactId>opentelemetry-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>com.github.loki4j</groupId>
  <artifactId>loki-logback-appender</artifactId>
</dependency>
```

### Signal flow

```
+-------------+     OTLP/gRPC     +--------+
|  service    | ----------------> | Tempo  |  (traces)
|             |     scrape        +--------+
|             | <---------------- | Prom   |  (metrics)
|             |     loki4j push   +--------+
|             | ----------------> |  Loki  |  (logs)
+-------------+                   +--------+
                                       ^
                                       |
                                +-------+--------+
                                |    Grafana     |  (dashboards, alerts, SLO)
                                +----------------+
```

### Tenant context

Every signal carries `tenant.id`:

- Traces: OTel baggage `tenant.id` -> span attribute.
- Metrics: Micrometer `Tags.of("tenant_id", id)`.
- Logs: Logback MDC `tenant_id` -> Loki label.

This makes per-tenant dashboards and SLOs a single Grafana variable.

### Positive consequences

- One SDK per signal, all vendor-neutral.
- Auto-instrumentation covers everything we use.
- Loki4j writes self-logs to the same store users see; dog-fooding
  exposes performance issues early.
- Per-tenant dashboards drop out for free.

### Negative consequences

- OTel java agent overhead (~5% throughput, measured). Acceptable for
  the visibility gained. Configurable to off via env flag for a
  controlled experiment.
- Cardinality risk in Loki labels (`tenant_id` is high cardinality).
  Mitigated by Loki's stream-sharding configuration and a label-budget
  alert that fires before chunks explode.

## Pros and cons of the options

### OTel + Micrometer + loki4j (chosen)

- **Good**, vendor-neutral; auto-instrumented; OSS stack everywhere.
- **Bad**, three SDKs to keep up-to-date.

### Spring Cloud Sleuth + custom Loki

- **Good**, integrated with Spring.
- **Bad**, Sleuth is deprecated in favor of Micrometer Tracing / OTel.

### Vendor APM agents

- **Good**, polished UX.
- **Bad**, per-host licensing; lock-in; no local-dev story.

### Elastic APM + ECS

- **Good**, mature.
- **Bad**, Elastic licensing complexity; we already have Grafana.

## Links

- [OpenTelemetry Java](https://opentelemetry.io/docs/zero-code/java/).
- [Micrometer](https://micrometer.io/).
- [loki4j](https://loki4j.github.io/loki-logback-appender/).
- [ADR-0009](./0009-tenant-isolation.md).

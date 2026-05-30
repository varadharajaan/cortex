# 0005. Message bus: RabbitMQ locally, Azure Service Bus in production

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: messaging, async, ingest, processor

## Context and problem statement

CORTEX decouples ingestion from enrichment, indexing, and remediation
using an asynchronous message bus. Local development must be free,
fast, and offline. Production needs managed durability, dead-letter
queues, and per-message TTL. Which broker do we standardize on?

## Decision drivers

- Spring Boot 3 must have a first-class starter.
- Local dev: zero-config, single Docker container.
- Production: managed, multi-region, durable, dead-letter support.
- Same client API in code regardless of environment (swap by config).
- No cost for local dev or CI runs.

## Considered options

- **RabbitMQ** (local) + **Azure Service Bus** (prod), bridged by Spring
  Cloud Stream's binder abstraction.
- **Apache Kafka** everywhere.
- **NATS JetStream** everywhere.
- **Azure Event Hubs** (Kafka-compatible) everywhere, with Kafka local.

## Decision outcome

Chosen option: **RabbitMQ local, Azure Service Bus production**, both
behind Spring Cloud Stream's binder abstraction. Application code calls
`StreamBridge.send("logs.ingest", payload)`; the binder routes to the
configured broker per environment profile.

### Positive consequences

- Local dev = one `docker run rabbitmq:3.13-management` container.
- Production gets a fully managed broker with regional failover,
  per-message TTL, and dead-letter handling baked in.
- Same Java code in both environments; the binder swap is config-only.
- RabbitMQ's management UI on port 15672 is excellent for local debugging.

### Negative consequences

- Two brokers means two integration-test paths. Mitigated by an
  abstraction-level Testcontainers test for the binder contract plus
  a production-binder smoke test in CI.
- AMQP 1.0 (Service Bus) and AMQP 0.9.1 (RabbitMQ) have subtle behavior
  differences (e.g., dead-lettering semantics). Documented in the
  binder configuration files.

## Topic layout

```
logs.ingest          - raw ingested log events
logs.enriched        - AI-enriched events from log-processor
logs.anomalies       - anomalies above the alert threshold
remediation.actions  - playbook execution commands
remediation.audit    - playbook execution results
```

Each topic has a dead-letter counterpart: `logs.ingest.dlq`, etc.

## Pros and cons of the options

### RabbitMQ + Service Bus (via Spring Cloud Stream)

- **Good**, best local DX; best prod durability; config-only swap.
- **Bad**, two systems' quirks to know.

### Kafka everywhere

- **Good**, single broker, very high throughput.
- **Bad**, heavier local footprint (Zookeeper or KRaft), more ops in prod.

### NATS JetStream

- **Good**, lightweight, modern, fast.
- **Bad**, less mature managed offering on Azure; smaller ecosystem.

### Event Hubs (Kafka API) everywhere with local Kafka

- **Good**, single client.
- **Bad**, no managed dead-letter; Event Hubs partitions are not Kafka
  partitions semantically; debugging is harder.

## Links

- [Spring Cloud Stream binders](https://spring.io/projects/spring-cloud-stream).
- [ADR-0008](./0008-resilience-strategy.md) (every send is wrapped in
  Resilience4j retry + circuit breaker).

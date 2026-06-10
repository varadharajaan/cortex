# ADR-0051: log-remediation auto-remediation pipeline

- **Status**: Accepted
- **Date**: 2026-06-09
- **Deciders**: @varadharajaan (operator), Codex
- **Tags**: log-remediation-service, auto-remediation, kafka, redis,
  resilience4j, dlq, audit, P19, P20, P21

## Context

P6.0..P6.3 made `log-remediation-service` a reliable operator
notification relay: it consumed valid anomaly CloudEvents from
`cortex.anomalies.v1`, parsed them into `AnomalyEvent`, and dispatched
Slack, PagerDuty, or Jira notifications through one active
`RemediationDispatcher`.

P19/P20/P21 turn that relay into a fix-first auto-remediation stage.
The upstream `log-processor-service` still owns AI classification and
anomaly publication. Remediation owns deterministic policy, playbook
execution, human escalation, and the audit trail for the decision.

The design question was whether auto-remediation needs a second generic
DLQ. It does not. Invalid input and valid-but-unfixed decisions are
different facts:

- malformed anomaly envelopes are poison input and belong on
  `cortex.anomalies.v1.dlq`;
- valid anomalies that are skipped or fail remediation are successful
  decisions and belong on `cortex.remediation.outcomes.v1`, followed by
  human fallback dispatch;
- fixed anomalies are silent to humans and are visible through the same
  outcome audit topic.

## Decision

Adopt a fix-first remediation pipeline with these rules.

### D1. Processor keeps AI classification

`log-processor-service` continues to run Spring AI anomaly
classification. It is the semantic enrichment stage for raw log events
and publishes anomaly CloudEvents to `cortex.anomalies.v1`.

`log-remediation-service` does not call an LLM in P21. It receives the
classifier's deterministic fields (`severity`, `confidence`,
`anomalyType`, `remediationKey`) and makes policy/playbook decisions from
that typed envelope. Moving AI into remediation would duplicate
classification, make replay non-deterministic, and blur the boundary
between "what happened?" and "what should we do?".

### D2. Redis SETNX dedupe protects remediation side effects

Before policy or playbook execution, `RemediationDedupeService` claims
`cortex:remediation:dedupe:{tenantId}:{eventId}` with `SETNX` and a
bounded TTL (`cortex.remediation.dedupe.ttl`, default `PT24H`).

Duplicate claims are skipped without publishing a second outcome or
dispatching a second human notification. Redis failure is fail-open:
remediation continues so an unavailable dedupe cache cannot suppress
incident handling.

### D3. Malformed anomalies go to one anomaly DLQ

The consumer publishes empty payloads and parse failures to
`cortex.anomalies.v1.dlq` with failure-reason headers, then acknowledges
the Kafka offset. This DLQ is for malformed anomaly envelopes only.

There is no generic DLQ-2 for valid remediation failures. A valid anomaly
that cannot be auto-fixed remains a first-class remediation outcome, not
a poison message.

### D4. Policy and playbook are deterministic and tenant-aware by seam

`RemediationPolicyService` returns a `RemediationPolicy` snapshot for
the anomaly. The first implementation is property-backed, but the seam is
tenant-scoped by shape and can later read from Postgres, config service,
or another policy store without changing `RemediationEngine`.

Policy gates are:

- `enabled`
- `dryRunOnly`
- `autoApply`
- `minSeverity`
- `allowedRemediationKeys`

`RemediationPlaybook` is the deterministic auto-fix SPI:

- `key()`
- `dryRun(AnomalyEvent)`
- `apply(AnomalyEvent)`

The engine always dry-runs before apply. Apply runs only when policy
allows it.

### D5. Every valid decision emits an outcome audit event

For each non-duplicate valid anomaly, `RemediationEngine` emits a
CloudEvents 1.0 structured JSON envelope to
`cortex.remediation.outcomes.v1` with event type
`io.cortex.remediation.outcome.v1`.

The outcome data is `RemediationOutcome`:

- `eventId`
- `tenantId`
- `severity`
- `anomalyType`
- `remediationKey`
- `outcome` (`fixed`, `skipped`, `failed`)
- `reason`
- `playbookKey`
- `dryRun`
- `ts`

This topic is the durable audit trail for auto-remediation decisions and
is intentionally separate from human dispatch counters.

### D6. Fixed means silent success

When a playbook returns `fixed`, the engine publishes the outcome event
and stops. Slack, PagerDuty, and Jira are not called. This prevents
operator fatigue and keeps human channels reserved for cases that need
attention.

### D7. Skipped and failed outcomes escalate to guarded dispatchers

When the outcome is `skipped` or `failed`, the engine calls the active
`RemediationDispatcher` through `RemediationDispatcherGuard`.

The guard wraps dispatch in programmatic Resilience4j retry and circuit
breaker policies per channel. Transient dispatcher verdicts are retried;
an open circuit returns a bounded `transient_failure` dispatch result
instead of blocking the Kafka consumer thread.

### D8. Kafka offset remains the durability boundary

The service uses direct Spring Kafka with manual offset commit. After the
engine finishes, or after a malformed envelope has been sent to the DLQ,
the consumer acknowledges the offset.

No relational outbox is introduced for remediation outcomes. This module
is a Kafka consumer -> Kafka producer / outbound HTTP relay, and the
Kafka offset remains the replay boundary.

## Consequences

- Auto-remediation is deterministic and replayable because LLM calls stay
  in the processor.
- Human notification volume drops because successful auto-fixes are audit
  only.
- Operators still see every valid decision through
  `cortex.remediation.outcomes.v1`.
- Malformed input has a single, clear DLQ. Valid remediation misses do not
  create DLQ noise.
- The policy seam can become tenant-backed later without changing the
  consumer or dispatcher contracts.
- Dispatcher degradation is contained by Resilience4j so a bad Slack,
  PagerDuty, or Jira dependency does not monopolize the consumer thread.

## Alternatives

1. **Move AI classification into remediation.** Rejected. The processor is
   already the enrichment/classification stage; duplicating AI in
   remediation would create two authorities and make replay dependent on a
   changing model.
2. **Create a generic DLQ-2 for auto-remediation failures.** Rejected.
   Skipped/failed remediation for a valid anomaly is an expected decision,
   not poison input. The outcome topic plus human fallback carries that
   fact more clearly.
3. **Dispatch humans before attempting auto-fix.** Rejected. It defeats the
   silent-success goal and turns self-healing into alert enrichment only.
4. **Fail closed when Redis dedupe is unavailable.** Rejected. Dedupe is a
   side-effect guard, not the incident handling source of truth.
5. **Write outcomes through a relational outbox.** Rejected for P21. Kafka
   offset replay is the durability boundary for this relay-style service.

## Verification

- `.\mvnw.cmd -pl log-remediation-service clean verify -B`
- `scripts\live-e2e\smoke-p6-1a.ps1 -SkipInfra -KeepInfra -Provider all`
- `npx --yes newman run postman\log-remediation.postman_collection.json
  -e postman\log-remediation.postman_environment_local.json --bail`

The verified run on 2026-06-09 covered module tests, live JVM boot,
Slack/PagerDuty/Jira smoke paths, outcome-topic advancement, and Newman
against a live remediation process.

## References

- ADR-0031 -- processor anomaly publisher to `cortex.anomalies.v1`.
- ADR-0032 -- remediation dispatcher SPI and consumer contract.
- ADR-0033 / ADR-0034 / ADR-0035 -- Slack, PagerDuty, Jira adapters.
- ADR-0037 -- remediation cross-phase live smoke and Postman closer.
- ADR-0008 -- Resilience4j strategy.
- `docs/auto-remedy-discussion/codex-proposal.md`
- `docs/auto-remedy-discussion/p19-p21-codex.md`

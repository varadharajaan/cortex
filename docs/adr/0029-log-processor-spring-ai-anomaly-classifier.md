# 0029. log-processor-service Spring AI 1.0 anomaly classifier (Ollama dev / Azure OpenAI prod)

- Status: accepted
- Date: 2026-06-03
- Deciders: @varadharajaan
- Tags: ai, spring-ai, processor, anomaly, ollama, azure-openai

## Context and problem statement

`log-processor-service` consumes parsed log events off
`cortex.logs.events.v1` (P5.1 / ADR-0028) and decides whether each event
is an anomaly worth handing to the P6 remediation queue. P5.0 shipped
the SPI (`AnomalyClassifier`) plus a no-op default
(`NoopAnomalyClassifier`) so the scaffold ran end-to-end without an LLM
dependency. P5.2 replaces the no-op with a real Spring AI 1.0.0 chat
classifier behind the same SPI, gated by the existing
`cortex.processor.classifier` property.

ADR-0006 established the provider abstraction: Ollama for local dev
(zero-cost, offline), Azure OpenAI for production (HIPAA / SOC 2 /
ISO 27001 compliant, hosted). P5.2 is the first concrete consumer of
that abstraction inside the processor. P3.3 / ADR-0018 shipped the
mirror pattern for the gateway NL-to-LogQL endpoint, so this ADR
inherits the WireMock-stubbed test harness + HTTP/1.1-pinned
`OllamaApi` bean override (memory.md LD42) without re-litigating
either decision.

## Decision drivers

- D1 — must reuse the existing `cortex.processor.classifier` binder
  gate so swapping implementations stays config-only (no code churn).
- D2 — must reuse the existing `AnomalyClassifier` SPI return type
  (`Classification` record) so `LogEventConsumer` is unaware of which
  classifier is wired.
- D3 — must dodge the JDK 17 HTTP/2 h2c upgrade collision with
  WireMock 3.x documented in LD42 so the smoke harness keeps working
  unchanged.
- D4 — must NOT crash the consumer when the upstream LLM is down;
  classifier failures degrade gracefully to `Classification.none()`
  so Kafka offsets keep advancing.
- D5 — must publish a Micrometer counter
  (`cortex.processor.events.classified_total{outcome}`) with a tight,
  finite tag-key allowlist so the metric surface honours Part 17
  cardinality rules.
- D6 — must keep the dev classpath lean: ship only the Ollama starter
  in P5.2; Azure OpenAI swap deferred to a follow-up alongside the
  prod Helm chart (the swap itself is config-only per ADR-0006).
- D7 — must keep the gateway P3.3 testing pattern: a `@TestConfiguration`
  bean overrides `ChatModel` with a Mockito mock, gated by
  `spring.ai.model.chat=none` on the test classpath so the Ollama
  autoconfig stays dormant during unit tests.

## Considered options

1. **Spring AI 1.0 `ChatClient` with provider-agnostic call site,
   Ollama dev (this PR) + Azure OpenAI prod (config-only swap per
   ADR-0006).** -- accepted.
2. **Rule-based heuristic classifier (level=ERROR + service allowlist).**
   -- rejected: rules drift, no semantic reasoning over message text,
   no per-tenant tuning without a YAML edit cycle.
3. **Bayesian classifier (`weka.classifiers.bayes.NaiveBayes`).** --
   rejected: requires labelled training data which we do not have
   in P5; classroom-quality results vs LLM zero-shot performance.
4. **Dedicated ML service (Python FastAPI + scikit-learn / PyTorch)
   over gRPC.** -- rejected: would split the deployment surface,
   add a Polyglot toolchain, and force a new ADR for the transport
   contract; LLM-first is faster to ship and ADR-0006 already
   committed to Spring AI.
5. **Direct REST call to Ollama (`/api/generate`) via `RestClient`.**
   -- rejected: re-implements prompt rendering + structured output
   parsing + provider abstraction; loses the Spring AI evolution
   path (advisors, streaming, function calling).
6. **Direct REST call to Azure OpenAI (`OpenAIClient` from the
   `azure-ai-openai` SDK).** -- rejected: provider-specific call site
   defeats D1; ADR-0006 explicitly chose the Spring AI abstraction.

## Decision outcome

Chosen option: **option 1, Spring AI 1.0 `ChatClient` with
provider-agnostic call site, Ollama dev (this PR) + Azure OpenAI prod
(config-only follow-up).**

### Implementation shape

- `SpringAiAnomalyClassifier` is gated by
  `@ConditionalOnProperty(prefix="cortex.processor.classifier",
  name="provider", havingValue="spring-ai")`. The existing
  `NoopAnomalyClassifier` keeps its `havingValue="noop"` +
  `matchIfMissing=true` gate (also under the same `provider`
  sub-property), so the default stays no-op until a caller
  explicitly opts in. P5.0's flat scalar
  `cortex.processor.classifier` is restructured into a typed sub-block
  `cortex.processor.classifier.provider` + sibling tuning keys so
  `ClassifierProperties` can bind to the prefix without colliding
  with the binder gate's scalar value (Spring Boot 3.x rejects a
  property being both a leaf and a parent in one tree).

- The classifier wraps a `ChatClient` built from the auto-configured
  `ChatClient.Builder`. The call site is provider-agnostic:

  ```java
  chatClient.prompt()
      .user(renderedPrompt)
      .call()
      .entity(ModelClassification.class);
  ```

  Switching providers means swapping `spring-ai-starter-model-ollama`
  for `spring-ai-starter-model-azure-openai` plus a `spring.ai.*`
  yml block; the classifier code is unchanged. This honours D1 + D2.

- An `OllamaHttpConfig` `@Configuration` declares the `OllamaApi`
  bean explicitly, pinning the underlying JDK `HttpClient` to
  HTTP/1.1 (D3). The gateway P3.3 / ADR-0018 NL feature ships the
  same override; the rationale is identical (WireMock 3.x is
  HTTP/1.1-only and the JDK 17 client otherwise negotiates an h2c
  upgrade per LD42). Gated by the same
  `cortex.processor.classifier=spring-ai` key so dev runs with
  classifier=noop never load the Ollama starter beans.

- `ClassifierProperties` is a typed `@ConfigurationProperties`
  record bound to prefix `cortex.processor.classifier`. Fields:
  `provider` (enum noop / spring-ai), `model` (default `mistral`),
  `temperature` (default `0.2`), `maxTokens` (default `256`),
  `confidenceThreshold` (default `0.7`), `requestTimeout` (default
  `PT10S`). The threshold gate is enforced inside the classifier:
  verdicts with confidence below the threshold downgrade to
  `Classification.none()` so `LogEventConsumer` never publishes a
  low-confidence "anomaly" to the P5.4 outbox.

- The prompt template
  (`src/main/resources/prompts/anomaly-classifier.st`) is rendered
  by literal substring replacement, NOT Spring AI's ST4
  `PromptTemplate`, to dodge the ST4-vs-JSON-braces collision (LD42).
  Placeholders: `{message}`, `{level}`, `{service}`, `{labels}`,
  `{ts}`, `{tenant_id}`. Hard constraints in the prompt forbid
  surrounding markdown and pin the output JSON schema to the
  `ModelClassification` record fields (`anomaly`, `severity`,
  `reason`, `confidence`).

- `ProcessorMetrics` adds
  `cortex.processor.events.classified_total{outcome}` with the
  outcome tag-key allowlisted to four values: `anomaly` (verdict
  above threshold), `normal` (verdict not anomalous OR downgraded
  by the threshold gate), `error` (classifier threw or returned
  `null`), `low_confidence` (RESERVED -- counter pre-registered so
  the Grafana label set is stable but the value stays 0 in P5.2;
  P5.3+ may have the classifier itself publish to this counter to
  separate "model said normal" from "model said anomaly but was
  downgraded"). Tag-key allowlist satisfies D5 + Part 17. The
  consumer ticks exactly once per record from the three active
  outcomes.

- `LogEventConsumer` wraps the `classifier.classify(event)` call in
  a `try`-`catch(RuntimeException)` that records
  `outcome=error`, falls back to `Classification.none()`, and acks
  the offset (D4). The pre-existing catch-all at method scope stays
  as a defence in depth for non-classifier defects (parse +
  validate complete before this point so they cannot trip the
  classify-side catch).

### Positive consequences

- Provider swap is config-only per ADR-0006 (D1).
- `LogEventConsumer` keeps its 5-step pipeline shape; the only
  observable change is the new counter (D2).
- LLM outages do not stop Kafka consumption; verdicts degrade to
  `Classification.none()` and `outcome=error` ticks (D4).
- Test classpath uses the gateway P3.3 pattern (`spring.ai.model.chat=none`
  + `@TestConfiguration` Mockito `ChatModel`); zero new test infra (D7).

### Negative consequences

- Adds one runtime dependency (`spring-ai-starter-model-ollama`)
  even on prod profiles. The JAR is ~700 KB and the autoconfig is
  inert when `spring.ai.ollama.*` is unset, so the runtime cost is
  effectively zero, but the SBOM grows by one entry.
- The Azure OpenAI prod swap is documented in ADR-0006 but not
  executed in this PR (D6). A separate follow-up issue will add
  `spring-ai-starter-model-azure-openai`, an `application-prod.yml`
  block, and a CI matrix dimension that exercises the Azure path
  against a mocked Azure endpoint.
- The classifier issues one synchronous LLM call per event on the
  consumer thread. Under P5.3 fan-out concurrency this caps per-pod
  throughput at roughly `consumer-threads * (1 / latency)`. The
  P5.3 ADR-on-throughput will decide whether to add async batching
  or per-tenant rate limiting; P5.2 stays synchronous for clarity.

### References

- ADR-0006: AI provider abstraction (Ollama local, Azure OpenAI prod).
- ADR-0018: log-gateway NL-to-LogQL via Spring AI 1.0 + Ollama + WireMock.
- ADR-0026: log-ingest-service CloudEvents 1.0 producer envelope.
- ADR-0027: log-ingest-service DLQ counters binder.
- ADR-0028: log-processor-service consumer (direct spring-kafka per LD79).
- memory.md LD42: HTTP/2 h2c upgrade collision with WireMock 3.x.
- memory.md LD89 / LD90: ship-claim discipline (verbatim `gh pr view`).
- GitHub issue #72.

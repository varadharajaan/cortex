# 0006. AI provider abstraction (Ollama local, Azure OpenAI prod)

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: ai, spring-ai, processor, nl-to-logql

## Context and problem statement

CORTEX uses LLMs in two places:

1. **Log enrichment** (`log-processor-service`) - per event, classify
   intent, score anomaly likelihood, suggest a remediation tag.
2. **Natural-language query** (`nlToLogQL` endpoint) - translate user
   prompts into LogQL, SQL, or Quickwit DSL.

Local dev must work offline at zero cost. Production must use a
hosted, compliant, accountable provider.

## Decision drivers

- Local dev: offline, free, no API key required.
- Production: enterprise-grade compliance (Azure OpenAI is HIPAA, SOC 2,
  ISO 27001).
- Cost ceiling per tenant must be enforceable.
- Provider swap must be config-only; no code branches per provider.
- Streaming responses must work in both providers.

## Considered options

- **Ollama (local) + Azure OpenAI (prod)** behind a Spring AI abstraction.
- **OpenAI direct** in both environments (paid in local too).
- **Self-hosted vLLM** in both environments.
- **HuggingFace Inference Endpoints** + local transformers.

## Decision outcome

Chosen option: **Ollama locally, Azure OpenAI in production**, both
accessed through Spring AI 1.0.0's `ChatClient` interface. Selection
is per-tenant via configuration; the application code knows nothing
about the provider.

### Local profile (Ollama)

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.1:8b
          temperature: 0.2
```

### Production profile (Azure OpenAI)

```yaml
spring:
  ai:
    azure:
      openai:
        endpoint: ${AZURE_OPENAI_ENDPOINT}
        api-key: ${AZURE_OPENAI_API_KEY}
        chat:
          options:
            deployment-name: gpt-4o-mini   # enrichment
            temperature: 0.2
```

The `nlToLogQL` endpoint uses a separate deployment (`gpt-4o`) because
the schema-translation task benefits from the larger model.

### Positive consequences

- Local dev runs entirely offline; no API key needed; no per-token cost.
- Spring AI abstracts the provider; switching is config-only.
- Streaming works in both providers via `ChatClient.stream()`.
- Per-tenant configuration enables future routing (e.g., GDPR tenants
  pinned to EU region).

### Negative consequences

- Two providers means two integration-test paths. Mitigated by mocking
  `ChatClient` for unit tests; reserving live calls for CI smoke tests
  gated on a secret being available.
- Local model output is noisier than gpt-4o. Mitigated by tighter
  prompts and a structured-output validator on the response.
- Per-tenant cost guardrails require a token-budget interceptor (planned
  in P5).

## Resilience

Every call is wrapped in Resilience4j (`@CircuitBreaker`, `@Retry`,
`@TimeLimiter`, `@RateLimiter`). On open-circuit, the processor falls
back to a rules-only classifier and tags the event `enrichment=degraded`.

## Pros and cons of the options

### Ollama + Azure OpenAI (via Spring AI)

- **Good**, free local; compliant prod; config-only swap; streaming everywhere.
- **Bad**, two providers' quirks; local model accuracy below cloud.

### OpenAI direct everywhere

- **Good**, single provider; best model quality.
- **Bad**, paid local dev; not in Microsoft compliance envelope.

### Self-hosted vLLM everywhere

- **Good**, full control; no per-token cost in prod.
- **Bad**, GPU ops burden; high baseline cost; slower iteration.

### HuggingFace Inference + local transformers

- **Good**, model variety.
- **Bad**, two completely different code paths; cold-start latency on
  HF Inference for small tenants.

## Links

- [Spring AI 1.0.0](https://docs.spring.io/spring-ai/reference/).
- [ADR-0008](./0008-resilience-strategy.md).
- [ARCHITECTURE.md §5](../ARCHITECTURE.md).

# ADR-0018: NL->LogQL via Spring AI ChatClient (Ollama dev/test, Azure OpenAI prod)

- Status: Accepted
- Date: 2026-05-31
- Phase: P3.3
- Supersedes: none
- Superseded by: none
- Related: ADR-0006 (AI provider abstraction), ADR-0017 (rate limit), B20 (Spring AI)

## Context

P3.3 introduces a natural-language to LogQL translation endpoint on the gateway:
`POST /api/v1/query/nl` takes a free-form prompt like `errors in payment service
last 1h` and returns a structured response `{ logql, confidence, explanation }`
that the eventual `log-query-service` (P7) will execute against the indexer
(Quickwit/Loki). The endpoint is JWT-protected and stacks the P3.2 global
rate-limit with a per-feature sub-bucket so an LLM-token storm against one
caller does not exhaust the global per-principal quota.

Three independent decisions are locked here:

1. **LLM client library**: Spring AI 1.0.0 `ChatClient` over a `ChatModel`
   bean.
2. **Provider topology**: Ollama for dev/test (stubbed by WireMock in
   automated tests + the smoke script), Azure OpenAI in prod via a profile
   switch (ADR-0006).
3. **Composed rate-limit**: a per-principal sub-bucket keyed
   `cortex:rl:nlq:user:<sub>` enforced inside `NlQueryServiceImpl`, distinct
   from the global `cortex:rl:user:<sub>` bucket the P3.2 filter consumes.

## Decision

### 1. Spring AI ChatClient + structured output

We use `org.springframework.ai.chat.client.ChatClient` (fluent API) on top of
the auto-configured `OllamaChatModel` (or `AzureOpenAiChatModel` in prod). The
prompt template lives in `classpath:/prompts/nl-to-logql.st` so prompt edits
are not Java source changes (B20.1). Structured output uses
`BeanOutputConverter<NlQueryModelResponse>` so the model is asked to emit JSON
that maps to a strongly-typed record; we wrap that record into our public
`NlQueryResponse` DTO after a validation pass.

The artifact name is `spring-ai-starter-model-ollama` (GA naming; the
pre-1.0.0 `spring-ai-ollama-spring-boot-starter` was renamed). The BOM is
already imported by the parent POM (P0 wiring), so we only need a single
`<dependency>` line in `log-gateway/pom.xml`.

### 2. Ollama for dev/test, Azure OpenAI for prod

Dev developers run `ollama` locally (or skip the endpoint -- the controller
returns 503 `UPSTREAM_UNAVAILABLE` when the Ollama server is unreachable, not
a 500). Automated tests use WireMock to stub the Ollama `/api/chat` endpoint
so neither CI nor `mvn verify` requires a real model. Prod will swap to Azure
OpenAI via `spring.profiles.active=prod` and a different starter artifact in
a later sub-phase (out of scope for P3.3 -- locked here as the intended path).

The smoke compose at `infra/local/docker-compose.smoke.yml` already runs
WireMock on host port `8081`; we add a stub mapping
`infra/local/wiremock/mappings/ollama-chat-happy.json` (and two more for the
schema-miss and refusal paths) so the smoke script gets deterministic
responses.

### 3. Composed rate-limit (per-feature sub-bucket)

The P3.2 `RateLimitFilter` runs in the servlet chain and keys on
`cortex:rl:user:<sub>` for authenticated callers. NL->LogQL adds a second,
SMALLER bucket keyed on `cortex:rl:nlq:user:<sub>` (defaults: capacity=10
tokens, refill=PT1M) enforced inside `NlQueryServiceImpl` BEFORE the
ChatClient call. This means:

- A caller with 90 of 100 global tokens remaining can still be rejected with
  429 on the NL endpoint if their NL sub-bucket is empty.
- The 429 carries `errorCode=NL_QUERY_RATE_LIMITED` and a `Retry-After`
  derived from the sub-bucket's refill window.
- The global X-RateLimit-* headers are unchanged (they reflect the global
  bucket; the sub-bucket is not surfaced in headers because doing so would
  leak per-feature quotas to clients without a corresponding RFC).
- LLM tokens are NOT consumed when the sub-bucket rejects: we check
  consumption before the ChatClient.call().

Sub-bucket enforcement gracefully no-ops when
`cortex.gateway.rate-limit.enabled=false` (the proxy manager bean is absent
in that case; we autowire `Optional<ProxyManager<String>>`).

## Validation contract

Even when the model returns syntactically-valid JSON conforming to the schema,
we still apply a small validator:

| Check | On fail |
|-------|---------|
| `logql` non-blank, length <= 1024, ASCII printable + LogQL whitespace | 422 NL_QUERY_INVALID |
| `confidence` in `[0.0, 1.0]` | 422 NL_QUERY_INVALID |
| `explanation` length <= 2048 | 422 NL_QUERY_INVALID |
| `logql` starts with `{` (LogQL stream-selector) OR `count_over_time` / `rate` / `sum` | 422 NL_QUERY_INVALID |
| Model refusal marker in `explanation` (`"refuse"`, `"cannot answer"`, `"I'm sorry"`) | 422 NL_QUERY_REFUSED |

Upstream failures (ChatClient throws, Ollama unreachable, schema parse
failure) become 503 NL_QUERY_UPSTREAM_FAILED so the caller can distinguish
"the model said no" from "the model is down".

## Alternatives considered

| Option | Reason rejected |
|--------|-----------------|
| Roll our own `RestClient` against Ollama's `/api/chat` | Violates B20.1 ("use Spring AI ChatClient with PromptTemplate"). Also re-implements structured-output conversion. |
| Skip dev provider, prod-only Azure OpenAI | Would force every developer to provision Azure quota for local runs; defeats LD18 (fast inner loop). |
| Real Ollama instance in CI/smoke | Pulling a 4GB model into `cortex-smoke-mistral` would push smoke runtime from ~15s to ~5min and require a GPU runner for any real reasoning. WireMock is deterministic, fast, and version-pinned. |
| Surface the sub-bucket in X-RateLimit-* headers | Headers are already defined to reflect the global bucket; adding per-feature triples would break Postman assertions and confuse callers. The 429 body's `errorCode` is unambiguous. |
| Enforce sub-bucket as a second `OncePerRequestFilter` matched on path | Would require pulling the principal out of `SecurityContextHolder` a second time, would not have access to the prompt (so no logging context), and would split the LLM-call orchestration across a filter + controller. Controller-side enforcement keeps the whole NL flow in one place. |

## Consequences

### Positive
- Single library, single auto-config, single fluent API for chat.
- Smoke + unit tests run with zero external network calls.
- Switching provider in P12 is a starter-swap + yaml change; the
  `NlQueryService` interface is unchanged.
- Composed rate-limit cleanly defends against feature-targeted floods.

### Negative
- Spring AI 1.0.0 GA is recent; we pin to 1.0.0 (already in parent POM) and
  accept upstream churn risk.
- `BeanOutputConverter` injects schema instructions into the user prompt at
  request time; the prompt is no longer purely template-driven. Acceptable
  tradeoff for type safety.
- The sub-bucket is silent: clients learn about it only on 429. Documented
  in OpenAPI per A12.8.

### Risks
- **Prompt injection via `prompt` field**: mitigated by (a) `@Size(max=2048)`
  on the request DTO, (b) prompt template wraps user input in clearly-marked
  delimiters and instructs the model to treat it as data, (c) output validator
  rejects logql that contains shell metacharacters or backticks.
- **LLM cost**: each ChatClient call is one HTTP round-trip; the sub-bucket
  caps it. We are NOT caching identical prompts in P3.3 -- a future
  ADR may add a per-tenant prompt cache.

## Open questions
- Streaming: P3.3 returns a single JSON; streaming is out of scope. P7 may
  revisit when the indexer is online.
- Embeddings / vector store: out of scope for P3.3; tracked for ADR-0019.

## References
- Spring AI 1.0.0 docs: https://docs.spring.io/spring-ai/reference/1.0/
- Ollama API: https://github.com/ollama/ollama/blob/main/docs/api.md
- WireMock standalone: https://wiremock.org/docs/standalone/
- ADR-0006 (AI provider abstraction)
- ADR-0017 (rate limit)
- B20 (Spring AI strict rules)

---

## Amendment 2026-05-31 (LD42) -- Hand-wire `OllamaApi` bean to pin HTTP/1.1

### Context (post-implementation)

The first end-to-end smoke run of P3.3 failed: 5 of 10 tests returned
HTTP 502 `errorCode=NL_QUERY_UPSTREAM_FAILED`. The live WireMock journal
showed every outbound POST to `/api/chat` as `wasMatched=false` with an
EMPTY recorded request body. Outbound headers from the gateway carried
`Upgrade: h2c`, `Connection: Upgrade, HTTP2-Settings`, and
`Transfer-Encoding: chunked` -- JDK 17 `HttpClient` was negotiating an
HTTP/2 cleartext upgrade on every outbound POST. WireMock 3.10's default
listener is HTTP/1.1-only, so it could not honour the upgrade and dropped
the chunked request body. The stub matcher never saw the prompt, WireMock
returned 404, and `NlQueryServiceImpl` surfaced the failure as
`NL_QUERY_UPSTREAM_FAILED`.

### Decision

Hand-wire `OllamaApi` in `NlQueryConfig` with an explicit
`@Bean @ConditionalOnMissingBean public OllamaApi ollamaApi(@Value(...)
String baseUrl)` that builds its `RestClient.Builder` from a
`JdkClientHttpRequestFactory` whose underlying `java.net.http.HttpClient`
is pinned to `HttpClient.Version.HTTP_1_1`.

```java
@Bean
@ConditionalOnMissingBean
public OllamaApi ollamaApi(
        @Value("${spring.ai.ollama.base-url:http://localhost:11434}") final String baseUrl) {
    final RestClient.Builder restClientBuilder = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                            .version(HttpClient.Version.HTTP_1_1)
                            .build()));
    return OllamaApi.builder()
            .baseUrl(baseUrl)
            .restClientBuilder(restClientBuilder)
            .build();
}
```

### Why a `RestClientCustomizer` does not work

Spring AI 1.0.0's `OllamaApiAutoConfiguration#ollamaApi(...)` resolves
its `RestClient.Builder` via
`restClientBuilderProvider.getIfAvailable(RestClient::builder)`. When no
`RestClient.Builder` bean is published, the `RestClient::builder`
fallback factory runs OUTSIDE the `RestClientCustomizer` chain -- so
publishing a customizer that pins HTTP/1.1 has no effect on the
auto-config's `RestClient`. Bytecode inspection of
`OllamaApiAutoConfiguration` and `javap` of `OllamaApi$Builder`
confirmed the only reliable override hook is the `OllamaApi` bean
itself, which is `@ConditionalOnMissingBean` in the auto-config.

### Why `@Value` and not `OllamaConnectionDetails`

The integration-test classpath
(`log-gateway/src/test/resources/application.yml`) sets
`spring.ai.model.chat=none`, which disables
`OllamaApiAutoConfiguration` entirely. As a side effect, the
`OllamaConnectionDetails` bean is ABSENT in tests. A first attempt that
injected `OllamaConnectionDetails` into the override method crashed the
test context with `NoSuchBeanDefinitionException`. `@Value` resolves
directly against `Environment` regardless of which auto-config classes
are active, so the override wires in BOTH the production context and
the test context. The default value matches Spring AI's own
(`http://localhost:11434`).

### Consequences

#### Positive
- Production Ollama is HTTP/1.1 too, so pinning the version is safe
  across all environments.
- WireMock-stubbed dev / smoke now sees the full request body and
  matches stubs correctly.
- Cross-cutting rule for Spring AI starters captured in LD42:
  whenever transport-level control is needed (HTTP version, timeouts,
  request factory, error handler), hand-wire the `*Api` bean rather
  than relying on `RestClientCustomizer`.

#### Negative
- One extra `@Bean` method (10 lines) lives in `NlQueryConfig`. Worth
  the cost: the alternative was either upgrading WireMock to a build
  that supports HTTP/2 (no stable Java client at the time) or
  patching `java.net.http.HttpClient` to disable upgrade negotiation
  per-instance (no public API).

#### Risks
- If a future Spring AI release switches the auto-config to take a
  `RestClient.Builder` as a constructor arg (so customizers DO
  apply), this `@Bean` becomes redundant but harmless --
  `@ConditionalOnMissingBean` still makes our bean win, no behaviour
  change.

### Verification (live, 2026-05-31)

- `mvn -pl log-gateway -am verify -B`: BUILD SUCCESS, Tests run: 94,
  Failures: 0, Errors: 0, Skipped: 0; Checkstyle 0, SpotBugs 0;
  total 05:47.
- Live NL probe against the relaunched JVM: HTTP 200 with body
  `{"logql":"{service=\"payment\"} |~ \"(?i)error\" | json","confidence":0.92,...}`.
  WireMock journal showed exactly one entry: `wasMatched=true
  status=200 method=POST url=/api/chat`, body-len=2266, headers
  contained only `Host`, `Transfer-Encoding: chunked`,
  `User-Agent: Java-http-client/17.0.19`, `Accept`, `Content-Type` --
  **no `Upgrade: h2c`, no `Connection: Upgrade`**.
- `powershell -File scripts\smoke-p3-3.ps1`: EXIT 0, Passed: 10 /
  Failed: 0.
- `npx newman run postman\log-gateway.postman_collection.json -e
  postman\log-gateway.postman_environment_local.json --reporters
  cli`: EXIT 0, 136 requests / 103 assertions / 0 failures.

### References (amendment)
- LD42 (memory.md): the reusable cross-cutting rule.
- LD45 (memory.md): why `spring-boot:run` must use `fork=false` on
  Windows for log capture to work.

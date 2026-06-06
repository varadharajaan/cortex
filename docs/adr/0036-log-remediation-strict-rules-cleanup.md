# 0036. log-remediation-service P6.0a strict-rules cleanup (`@Validated` + composition `RestDispatchTemplate` + OCP `RemediationMetrics` + Lombok constructors + private-Javadoc supersede + constants package)

- Status: accepted
- Date: 2026-06-06
- Deciders: @varadharajaan
- Tags: remediation, refactor, solid, lombok, validation, ocp, javadoc, constants

## Context and problem statement

P6.0..P6.3 shipped the `log-remediation-service` consumer pipeline
(ADR-0032) and the three production channel adapters: Slack
(ADR-0033), PagerDuty (ADR-0034), Jira (ADR-0035). The closer PR
P6.1a (issue #93) was supposed to follow immediately and ship the
cross-phase IT + smoke script + Postman collection.

Before opening P6.1a, an honest SOLID-and-proper-patterns audit of
the `log-remediation-service` production code against
`agent-strict-rules-prompts.md` parts A2 / A3 / A4 / A6 / A7
(triggered by the user's question "are u using SOLID principle and
also design patterns and principal level coding expectation?")
found seven concrete violations that needed to be cleaned up BEFORE
the closer landed — closing P6 with a known-defective design would
ossify the violations under LD104 closer-pattern semantics:

1. **A2.3 (Javadoc placement)** — every private helper across
   `JiraRemediationDispatcher`, `PagerDutyRemediationDispatcher`,
   `SlackRemediationDispatcher`, `AnomalyConsumer`,
   `AnomalyEnvelopeParser`, and `RemediationMetrics` carried a
   full Javadoc block. A2.3 explicitly forbids private-method
   Javadoc: *"Javadoc only on public APIs ... Never on private
   methods."* The repository's universal-Javadoc enforcer
   (`checkstyle.xml`, recorded as **LD5** in `memory.md`) was
   itself the root cause.
2. **A4.2 (constructor injection via Lombok)** — every Spring
   component used a hand-rolled public constructor + `this.x = x`
   field assignments even though Lombok was already on the
   classpath (`log-remediation-service/pom.xml:129` +
   parent `:130`); A4.2 says to use `@RequiredArgsConstructor` +
   `@Slf4j` for any non-trivial Spring bean.
3. **A6.1 (validation at the system boundary)** — the three
   `@ConfigurationProperties` records (`SlackProperties`,
   `PagerDutyProperties`, `JiraProperties`) were missing
   `@Validated`; A6.1 requires `@Validated` on every
   configuration boundary so Spring can wire JSR-380 validators
   on demand even if the records currently carry no field
   constraints.
4. **A2.10 (file length soft limit)** —
   `JiraRemediationDispatcher` was 365 lines and
   `PagerDutyRemediationDispatcher` ~340 lines; both above the
   250-line `FileLength` Checkstyle soft limit and unrecorded.
5. **A3.2 (Open/Closed Principle)** — `RemediationMetrics`
   hand-maintained a 9-call bootstrap block
   (`bootstrap("slack",...)`, `bootstrap("pagerduty",...)`,
   `bootstrap("jira",...)`). Adding a fourth channel adapter
   meant editing this class, which is an OCP violation.
6. **A3.1 (Single Responsibility) + DRY** — each of the three
   real-channel adapters carried ~80 lines of duplicated
   try/catch + `classifyHttp` + `classifyTransport` logic. The
   only per-channel concerns were the configured-check, the
   endpoint, the body shape, and (for Jira) the auth header;
   every adapter re-implemented the outer error-handling shell.
7. **A7 (no magic numbers; centralise constants)** —
   `HTTP_TOO_MANY_REQUESTS = 429` was redeclared inside every
   real-channel adapter; the Checkstyle `MagicNumber` warnings
   on `429` showed up three times across the production tree.

The closer PR (P6.1a) must NOT carry these in. Cut a separate
"P6.0a strict-rules cleanup" PR first (issue #95) — no
behavioural change, no new feature — that pays the technical debt
and resets the LD5 universal-Javadoc enforcer.

## Decision drivers

- D1 — A2.3 is the authoritative rule on Javadoc placement; the
  earlier `memory.md` LD5 ("comprehensive Javadoc on every method
  in the module") was an artifact of the universal Checkstyle
  enforcer and predates the strict-rules contract; it must be
  superseded by a pointer to A2.3, NOT preserved in parallel.
- D2 — the shared error-handling shell across the three REST
  adapters must be eliminated via the strongest design that keeps
  each adapter independently testable and lets future channels
  plug in without editing existing code; this means composition
  over inheritance (Effective Java item 18), not an abstract base
  class.
- D3 — the `RemediationMetrics` bootstrap must be flipped to an
  OCP-compliant loop over an injected `List<RemediationDispatcher>`
  so that adding the future P6.4 retry/DLQ channel (or any
  fan-out channel) requires zero edits here.
- D4 — `@Validated` must land on every `@ConfigurationProperties`
  record even when there are no field-level constraints yet, so
  Spring's validation hook is wired and a future field-level
  `@NotBlank` lands without a second config-change PR.
- D5 — Lombok adoption must be uniform across the module
  (`@RequiredArgsConstructor` + `@Slf4j`) so the consumer +
  parser + dispatcher tree all read the same way; the existing
  `LogEventConsumer` precedent from log-processor-service
  already follows this shape.
- D6 — the new constants package must follow Part 9.5 owner /
  package-info template so its purpose is auditable; it must
  carry only cross-cutting constants (not channel-specific
  ones — those stay inside each adapter as their own static
  finals).
- D7 — the refactor must be behaviour-preserving: every HTTP
  outcome row from ADR-0033 / ADR-0034 / ADR-0035 must remain
  bit-for-bit identical (same DispatchResult shape, same reason
  strings, same channel tags) so dashboards never see drift.
- D8 — the strict-rules cleanup PR must land BEFORE the P6.1a
  closer (LD104) so the closer can run its cross-phase IT
  against the cleaned-up dispatcher surface.

## Considered options

### For the shared error-handling shell (D2)

1. **Abstract base class `AbstractRestRemediationDispatcher` with
   protected template-method hooks (`channelId()`,
   `isConfigured()`, `executePost(event)`).** — rejected:
   `@RequiredArgsConstructor` cannot generate the `super(...)`
   call a subclass needs to forward the channelId into the base
   class's constructor; either we lose Lombok at the most
   important constructors in the module, or we hand-roll the
   `super(...)` and lose the rule-A4.2 win. Also blocks `final`
   on the concrete adapter classes (Effective Java item 17 wants
   `final` by default).
2. **Stateless composition helper `RestDispatchTemplate` injected
   into each adapter as a field; adapter calls
   `template.dispatch(event, this::isConfigured, this::executePost)`.** —
   accepted. The template owns the outer try / catch + 429 / 5xx /
   timeout / IO classification; the adapter owns only the
   channel-specific configured-check + endpoint + body builder.
   Every adapter stays `final` + `@RequiredArgsConstructor`. The
   template is independently testable and the design follows
   Effective Java item 18 (composition over inheritance).
3. **A utility class with static `dispatch(...)` methods.** —
   rejected: per A2.10 + A4 + general OO, stateless utility-style
   classes are a code smell when the helper carries a
   channel-specific configuration value (the `channelId` string).
   The template instance encapsulates that state cleanly.
4. **Spring AOP `@Around` aspect that wraps every
   `RemediationDispatcher.dispatch(...)` call with the
   try/catch.** — rejected: introducing AOP for a 3-class refactor
   adds proxy semantics + cglib that the rest of the module does
   not use; the cost is higher than the benefit and the resulting
   indirection is much harder to debug than a one-line delegated
   call.

### For the OCP fix on `RemediationMetrics` (D3)

A. **Inject `List<RemediationDispatcher>` + iterate at
   `@PostConstruct`; call
   `bootstrap(d.channelId(), OUTCOME_*)` for each adapter +
   each failable outcome.** — accepted. Spring autowires the
   list to exactly the active adapters (one per profile under
   `@ConditionalOnProperty`); the all-`UNKNOWN` placeholder
   series is always registered so empty-list test fixtures still
   see the counter family.
B. **Spring `BeanPostProcessor` that watches every
   `RemediationDispatcher` bean and lazy-registers its outcome
   series.** — rejected: heavier than required, harder to read,
   and runs at a Spring lifecycle phase that's confusing to
   reason about when a test instantiates the class directly.
C. **Keep the hand-coded list and just add a comment saying
   "remember to update when P6.4 lands".** — rejected on
   principle: OCP is not satisfied by a TODO comment.

### For the LD5 supersede (D1)

- **Keep LD5 alongside A2.3 ("comprehensive Javadoc is a
  superset of A2.3, so still safe to comply").** — rejected:
  LD5's universal scope contradicts A2.3 directly. Any rule that
  says "more documentation never hurts" actually does hurt: it
  trains reviewers + future agents to add private-method Javadoc,
  which A2.3 explicitly bans. Cleaner to write the supersede now
  than to leave a contradiction in `memory.md`.
- **Delete LD5 from `memory.md` outright.** — accepted-with-
  modification: mark it SUPERSEDED with a pointer to this ADR +
  A2.3 (don't delete; the supersede record is itself useful
  context for future agents).

## Decision outcome

Chosen options (combined): **option 2 (composition via
`RestDispatchTemplate`) + option A (OCP loop over
`List<RemediationDispatcher>`) + LD5 SUPERSEDED by A2.3.**

### Implementation shape

- New stateless helper
  `io.cortex.remediation.dispatch.RestDispatchTemplate` (package-
  private, `final`, `@Slf4j + @RequiredArgsConstructor`):
  ```java
  DispatchResult dispatch(AnomalyEvent event,
                          BooleanSupplier configCheck,
                          Consumer<AnomalyEvent> executor)
  ```
  Owns the outer try / catch, the no-throw-on-transient contract
  (ADR-0032 D6), and the HTTP / transport classification table
  shared by every REST adapter. Parameterised by `channelId`
  string so log lines and `DispatchResult` reasons stay
  channel-attributed. Uses
  `RemediationHttp.TOO_MANY_REQUESTS` from the new constants
  package (no magic numbers).

- New SPI method on
  `io.cortex.remediation.dispatch.RemediationDispatcher`:
  ```java
  String channelId();
  ```
  Every concrete adapter returns its `DispatchResult.CHANNEL_*`
  constant. Used by `RemediationMetrics.bootstrapMeters()` to
  loop without hard-coding the channel list.

- Rewritten adapters
  (`Slack/PagerDuty/Jira RemediationDispatcher`): each is now
  `@Component @RequiredArgsConstructor` `final class`,
  ~95–185 lines, with the body of `dispatch(event)` reduced to a
  single line:
  ```java
  return template.dispatch(event, this::isConfigured, this::executePost);
  ```
  Per-channel concerns (body shape, endpoint, auth header) stay
  local; classification + try/catch + outcome mapping no longer
  duplicated.

- `RemediationMetrics` refactor:
  ```java
  @Component
  @RequiredArgsConstructor
  public class RemediationMetrics {
      private final MeterRegistry registry;
      private final List<RemediationDispatcher> dispatchers;

      @PostConstruct
      void bootstrapMeters() {
          bootstrap(UNKNOWN, UNKNOWN);
          for (var d : dispatchers) {
              bootstrap(d.channelId(), OUTCOME_DISPATCHED);
              bootstrap(d.channelId(), OUTCOME_TRANSIENT_FAILURE);
              bootstrap(d.channelId(), OUTCOME_PERMANENT_FAILURE);
          }
      }
  }
  ```

- New constants package `io.cortex.remediation.constants`:
  - `RemediationHttp.java` — final utility class with private
    `UnsupportedOperationException` ctor; single constant
    `TOO_MANY_REQUESTS = 429`. Other HTTP status codes used by
    the adapters (200/201/202/400/401/429/500) appear ONLY in
    test fixtures or are classified via
    `HttpStatusCode.is5xxServerError()` so they don't need
    constants.
  - `package-info.java` — Part 9.5 "Owns / Never imports /
    Owner" template.

- `@Validated` annotation added to all three
  `@ConfigurationProperties` records (no field-level constraints
  yet — the compact-ctor null-coerce + blank-coerce stays the
  source of truth for default values per ADR-0033 / 0034 / 0035).

- Lombok adoption: `AnomalyConsumer` / `AnomalyEnvelopeParser` /
  `RemediationMetrics` flipped to
  `@RequiredArgsConstructor` (and `@Slf4j` where they had a
  hand-rolled `LoggerFactory.getLogger(...)` field).

- Javadoc stripped from every private method across the module
  per A2.3.

- `checkstyle.xml` (root) updated to enforce A2.3 — scope on the
  `MissingJavadocMethod`/`MissingJavadocType`/`JavadocType`
  modules raised from `private` to `public`/`protected`, and
  `JavadocMethod.accessModifiers` reduced to `"public,protected"`.
  Header comment cites A2.3 + the LD5 supersede.

- ArchUnit `ArchitectureTest` extended with a `Constants` layer
  that may only be accessed by `Dispatch` (the only place
  importing `RemediationHttp` is `RestDispatchTemplate`).

### Tracking artifacts

- New issue **#95** "P6.0a strict-rules cleanup …".
- New branch `feat/95-p6-0a-strict-rules-cleanup` off `main`
  (`2411191`).
- LD5 in `memory.md` flipped to **SUPERSEDED by A2.3 per
  ADR-0036**.
- `docs/adr/INDEX.md` row + count bump 35 -> 36 + refreshed-on
  date.
- `CHANGELOG.md` entry under `[Unreleased] > ### Changed`
  (refactor, not new feature).

### Positive consequences

- A2.3 honoured: zero private-method Javadoc in the module; the
  Checkstyle enforcer can no longer regress to the LD5 shape.
- A3.2 (OCP) honoured at the `RemediationMetrics` boundary:
  adding the future P6.4 retry/DLQ channel requires `+1 class +1
  @ConditionalOnProperty` with zero edits to `RemediationMetrics`.
- A3.1 (SRP) + DRY honoured at the dispatcher boundary: the
  shared error-handling shell lives in exactly one place
  (`RestDispatchTemplate`), and the per-channel adapters carry
  only the per-channel concerns.
- A4.2 honoured: every Spring component uses
  `@RequiredArgsConstructor` (+ `@Slf4j` where the logger field
  was hand-rolled).
- A6.1 honoured: every `@ConfigurationProperties` record carries
  `@Validated`.
- A7 honoured: `429` no longer appears as a magic number in
  production code.
- File-length warnings on the three real-channel adapters
  collapsed (`Jira` 365 -> 184; `PagerDuty` 340 -> 142;
  `Slack` 230 -> 113).
- Behaviour preserved bit-for-bit: every WireMock IT in
  `Slack/PagerDuty/JiraRemediationDispatcherWireMockIT` passes
  unchanged; every outcome row from ADR-0033 / 0034 / 0035
  produces the same `DispatchResult` reason string.

### Negative consequences and trade-offs

- The composition pattern introduces one indirection at the
  dispatcher boundary (`template.dispatch(...)`); the stack
  trace on a transient failure now goes through
  `RestDispatchTemplate` rather than directly through the
  channel adapter. Mitigation: every log line on the template
  is `{channelId} ...`, so the line itself carries the channel
  context.
- The `RemediationMetrics` test class
  (`RemediationMetricsTest`) had to grow from 159 to 254 lines
  to cover the new `bootstrapIteratesOverMultipleDispatchers`
  path; this exceeds the 250-line `FileLength` soft limit by 4
  lines (warning, not error). Accepted: the test surface is
  more important than the soft cap.
- A future channel adapter still has to remember to call
  `template.dispatch(...)` in its body; this is enforced only
  by review, not by the type system. Mitigation: ADR-0036 is
  the contract; any new dispatcher PR that re-implements the
  try/catch instead of delegating must be rejected at review.

## Links / references

- Supersedes (decision portion only): **memory.md LD5**
  ("comprehensive Javadoc on every method in the module") —
  pointer to A2.3 retained in the LD5 row of `memory.md`.
- Reinforces: A2.3 (Javadoc placement), A3.1 (SRP), A3.2 (OCP),
  A4.2 (constructor injection via Lombok), A6.1 (validation at
  the system boundary), A7 (no magic numbers).
- Builds on: ADR-0032 (RemediationDispatcher SPI), ADR-0033 (Slack
  adapter), ADR-0034 (PagerDuty adapter), ADR-0035 (Jira adapter).
- Predates: P6.1a closer (issue #93) — LD104 closer-pattern.
- Tracking issue: #95
  (`feat/95-p6-0a-strict-rules-cleanup`).

## How to validate

```bash
./mvnw verify -pl log-remediation-service -am
# BUILD SUCCESS expected, 0 Checkstyle violations, 0 SpotBugs,
# JaCoCo all gates met (BUNDLE 0.80/0.80), 16 ITs + every unit
# test green (no behavioural regression vs P6.3).
```

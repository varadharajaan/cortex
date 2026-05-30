# 0001. Use Java 17 LTS as the runtime

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: runtime, language, build

## Context and problem statement

CORTEX is a long-lived microservices project that needs an LTS JVM with
broad library support, modern language features (records, sealed types,
pattern matching, text blocks), and a clear upgrade path. Java 21 adds
virtual threads, but the user has explicitly excluded them ("vritual
treads 21 not supported"). Which runtime do we standardize on?

## Decision drivers

- Must be an LTS release (multi-year vendor support).
- Must support records, sealed types, pattern matching, text blocks.
- Must NOT depend on virtual threads (`Thread.ofVirtual`).
- Spring Boot 3.3.x supports Java 17 as its minimum baseline.
- Must be installable via Temurin on Windows, macOS, Linux, and CI.

## Considered options

- Java 17 LTS (Temurin)
- Java 21 LTS (Temurin) with virtual threads disabled
- Java 21 LTS (Temurin) using virtual threads
- Java 11 LTS

## Decision outcome

Chosen option: **Java 17 LTS (Temurin 17.0.19)**, because it satisfies
every driver, is the minimum baseline for Spring Boot 3.3.x, and
explicitly avoids the virtual-thread feature the user has excluded.

### Positive consequences

- All modern syntactic features we need are available (records, sealed,
  switch patterns, text blocks).
- Spring Boot 3.3.5 is fully supported and well-tested on Java 17.
- Long support window: Temurin 17 is maintained through 2029.
- CI matrix stays simple: a single `actions/setup-java@v4` invocation.

### Negative consequences

- We forgo virtual threads, structured concurrency previews, and the
  scoped-values preview. Throughput-sensitive call sites must use the
  classic `Executor` + thread-pool model.
- A future Java 21 (or 25 LTS) migration will require a dedicated ADR
  (likely 0013+) plus a Spring Boot major-version bump.

## Pros and cons of the options

### Java 17 LTS

- **Good**, supported until 2029; Spring Boot 3.3.x baseline; widely deployed.
- **Bad**, no virtual threads, no string templates.

### Java 21 LTS (virtual threads disabled)

- **Good**, longer support window (2031).
- **Bad**, user has explicitly excluded the virtual-thread feature; we'd
  have a flagship Java 21 dependency we cannot use.

### Java 21 LTS (virtual threads on)

- **Good**, throughput on synchronous blocking I/O.
- **Bad**, user constraint forbids it.

### Java 11 LTS

- **Good**, very mature.
- **Bad**, missing records, sealed types, pattern matching; Spring Boot
  3.x requires Java 17+.

## Links

- Locked decision LD1 in the internal memory log.
- Rule override O3 (defers Rule B14 virtual-threads requirement).
- [Spring Boot 3.3 system requirements](https://docs.spring.io/spring-boot/docs/3.3.5/reference/html/getting-started.html#getting-started.system-requirements)

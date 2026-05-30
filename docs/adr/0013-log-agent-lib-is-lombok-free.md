# 0013. log-agent-lib is Lombok-free

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: lib, agent, build, embedder-ergonomics

## Context and problem statement

`log-agent-lib` is a thin Java SDK that downstream services embed on their
classpath to ship structured log events to a CORTEX cluster. Every other
Java module in this monorepo uses Lombok (`@RequiredArgsConstructor`,
`@Slf4j`, `@Builder`) via the parent POM's annotation-processor wiring.

The question: should the embedder SDK also use Lombok?

A consumer that pulls `log-agent-lib` onto their classpath should not be
forced to:

- add Lombok as a transitive runtime concern,
- align their build's annotation-processor configuration with ours,
- ship the Lombok IDE plugin to every developer just to read our public API
  in their editor,
- inherit our parent POM's `-Amapstruct.*` compiler args (which fail under
  `-Werror` when the consumer has no MapStruct dependency).

## Decision drivers

- Embedder ergonomics: zero surprise for consumers on their classpath.
- Public API stability: generated constructors / getters from Lombok make
  binary-compat reviews harder, because the annotation processor decides
  the signature.
- Build hygiene: the parent POM passes `-Amapstruct.suppressGeneratorTimestamps`
  to javac; in a module that has neither Lombok nor MapStruct as a
  dependency, that argument triggers `-Werror` on "annotation processor
  not found".
- IDE friction: any consumer team that does NOT use Lombok would have to
  install the Lombok plugin just to navigate our SDK source.
- The SDK is small (5 production classes + 1 enum + 1 record + 1 exception);
  the boilerplate savings from Lombok are negligible.

## Considered options

- **Option A — Use Lombok like every other module.** Inherit the parent's
  annotation processor paths, declare `@RequiredArgsConstructor` and
  `@Slf4j` on internal classes, use `@Builder` on `LogEntry`.
- **Option B — Lombok-free SDK with hand-written constructors, plain
  `LoggerFactory.getLogger(...)`, and a fluent builder class.** Override the
  parent compiler plugin in this module to drop the MapStruct/Lombok
  processor args, and re-add only JaCoCo (because the parent's `<plugins>`
  block is replaced by the override).
- **Option C — Keep Lombok but only as `<scope>provided</scope>` and rely
  on Lombok's delombok step.** Generate a "delomboked" source jar for IDE
  navigation and a separate compiled jar.

## Decision outcome

Chosen option: **Option B — Lombok-free SDK**, because the SDK is on the
embedder's classpath and the cost of "zero AP surprises for consumers" is
worth the small amount of hand-written boilerplate (one builder class, one
SLF4J logger constant per class, five hand-written constructors / getters).

### Positive consequences

- Consumers add `log-agent-lib` as a single dependency with zero
  annotation-processor implications on their build.
- Public API surface is exactly what is written; no generated methods to
  audit for binary-compat.
- The SDK can be built with a minimal compiler plugin override
  (`<annotationProcessorPaths combine.self="override"/>` plus
  `<compilerArgs combine.self="override">`) without inheriting parent AP
  args that fail under `-Werror`.
- IDE-friendly: any consumer can navigate the SDK source without the
  Lombok plugin installed.
- Smaller dependency tree advertised by the SDK pom (no `lombok` entry
  even at `provided` scope).

### Negative consequences

- The SDK is the **only** Java module in the monorepo that does not use
  Lombok. Contributors must remember the asymmetry when moving between
  modules.
- Hand-written constructors, getters, and a fluent builder mean a few
  dozen extra lines of code in this module.
- Any rule in the strict prompt that says "use Lombok" (A4.2, A5.1) is
  explicitly overridden for this module. The override is recorded in
  `memory.md` as a rule override with this ADR as the justification.

## Pros and cons of the options

### Option A — Use Lombok like every other module

- **Good**, because it is consistent with every other module in the
  monorepo, so contributors do not have to context-switch.
- **Good**, because the parent POM already wires the Lombok annotation
  processor and IDE configuration is solved at the parent level.
- **Bad**, because consumers of the SDK inherit a Lombok-on-the-classpath
  concern they did not ask for.
- **Bad**, because the parent POM also wires the MapStruct annotation
  processor (`-Amapstruct.suppressGeneratorTimestamps` etc.), which fails
  under `-Werror` in a module that has no MapStruct dependency. Working
  around this requires overriding the compiler plugin anyway, at which
  point Lombok also has to be re-wired explicitly.

### Option B — Lombok-free SDK (chosen)

- **Good**, because the SDK has zero annotation-processor footprint for
  consumers.
- **Good**, because the public API is exactly the source code; no
  generated methods to review.
- **Good**, because the compiler plugin override is small and explicit:
  `combine.self="override"` on `<annotationProcessorPaths>` and
  `<compilerArgs>`, then re-declare only JaCoCo so the bundle gate still
  fires.
- **Bad**, because this is the only Java module that breaks the
  "every module uses Lombok" rule from the strict prompt.
- **Bad**, because contributors must remember the asymmetry when moving
  between the SDK and the rest of the monorepo.

### Option C — Lombok + delombok

- **Good**, because consumers get a delomboked source jar that needs no
  IDE plugin to read.
- **Bad**, because delombok adds a Maven plugin invocation, a second
  source attachment, and complicates the publish pipeline for one small
  module.
- **Bad**, because consumers still see Lombok on the runtime classpath
  unless we additionally engineer a shade / repackage step. Two extra
  layers of build complexity for negligible benefit over Option B.

## Links

- Related ADRs:
  - ADR-0002 (monorepo modules) — defines `log-agent-lib` as a top-level
    module sibling to the services.
  - ADR-0012 (build and quality gates) — defines the parent POM's
    Lombok / MapStruct / Checkstyle / SpotBugs / JaCoCo wiring that this
    module selectively overrides.
- External references:
  - SLF4J FAQ: "Should I use Lombok's `@Slf4j` in a library?" — guidance
    against embedding annotation processors in libraries consumed by
    third parties.
- Issues / PRs:
  - GitHub issue #3 — P2: build `log-agent-lib`.

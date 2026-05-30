# 0012. Build and quality gates: universal Javadoc + JaCoCo 80%

- Status: accepted
- Date: 2026-05-30
- Deciders: @varadharajaan
- Tags: build, maven, quality-gates, ci

## Context and problem statement

CORTEX is going to be production-operated and (eventually) externally
contributed to. We need build-time quality gates that fail loudly when
code drifts from the standard, and we need them to be uniform across
all seven modules.

The user has explicitly required Javadoc on every class and method,
including private methods ("add comments for every class and methods").
That is stricter than the original project contract (Rule A2.3, which
limits Javadoc to public API).

## Decision drivers

- Single source of truth for plugin versions and configs (parent POM).
- Every gate must fail the build, not just warn.
- Gates must run identically locally (`./mvnw verify`) and in CI.
- Universal Javadoc must be enforced (user requirement).
- Coverage threshold high enough to matter, low enough to ship.
- Security scanning at every build (OWASP DC).

## Considered options

- **All gates inline in parent POM**, run on `verify`.
- **Sonar-only quality gate**, deferring local enforcement.
- **Pre-commit hooks** (Husky / pre-commit) + lighter CI.

## Decision outcome

Chosen option: **All gates inline in the parent POM, fail the build on
`./mvnw verify`**. CI runs the same command. Sonar runs in addition
for trend analysis but does not replace local enforcement.

### Enforced gates

| Gate                   | Version  | Failure mode                               |
| ---------------------- | -------- | ------------------------------------------ |
| Maven Enforcer         | 3.5.0    | Java 17 only, Maven >= 3.9, depConvergence |
| Checkstyle             | (latest) | severity=error; universal Javadoc;
|                        |          | ASCII-only; no tabs; no `System.out`;      |
|                        |          | no field `@Autowired`; 120-char line       |
| SpotBugs + FindSecBugs | 4.8.6.5  | effort=Max, threshold=High                 |
| JaCoCo                 | 0.8.12   | line >= 80%, branch >= 80% (bundle)        |
| OWASP Dependency-Check | 11.1.0   | failBuildOnCVSS=8                          |
| CycloneDX SBOM         | 2.9.0    | aggregated on `package`                    |
| Reproducible build     | -        | `outputTimestamp=2026-01-01T00:00:00Z`     |

### Universal Javadoc (overrides A2.3)

```xml
<module name="MissingJavadocMethod">
  <property name="scope" value="private"/>
</module>
<module name="MissingJavadocType">
  <property name="scope" value="private"/>
</module>
<module name="JavadocMethod">
  <property name="validateThrows" value="true"/>
  <property name="allowMissingParamTags" value="false"/>
  <property name="allowMissingReturnTag" value="false"/>
</module>
```

Every method (public, package-private, protected, private) requires
a Javadoc block with `@param` for every parameter, `@return` for every
non-void return, and `@throws` for every checked or documented unchecked
exception.

### JaCoCo exclusions

- `**/*Application.class` (Spring Boot entry points)
- `**/dto/**` (DTOs are data holders)
- `**/entity/**` (JPA entities are data holders)
- `**/config/**` (Spring config classes)
- `**/exception/*Exception.class` (exception data carriers)
- `**/generated/**` (generated MapStruct / GraphQL code)
- `**/package-info.class`

These are excluded because their coverage is misleading: covering them
requires writing meaningless tests that don't improve safety.

### CI gate

GitHub Actions runs the same `./mvnw verify` plus:

- `commitlint` on every PR (Conventional Commits).
- Trivy on every built image.
- Cosign signature on every released image (P14+).

### Positive consequences

- One command (`./mvnw verify`) reproduces every gate locally.
- Drift between local and CI is impossible.
- Universal Javadoc forces authors to document their thinking,
  raising future-maintainer productivity.
- 80% coverage is the industry sweet spot - high enough to catch
  regressions, low enough that authors don't game it with trivial tests.

### Negative consequences

- First build of a new module is slow (OWASP DC downloads NVD cache,
  ~1 GB). Mitigated by CI cache and a daily nightly refresh.
- Universal Javadoc adds keystrokes. Mitigated by IDE templates and
  a `javadoc` snippet in `.vscode/`.

## Pros and cons of the options

### All gates in parent POM (chosen)

- **Good**, single source of truth; local = CI; reproducible builds.
- **Bad**, parent POM is large (~500 lines); first build is slow.

### Sonar-only

- **Good**, fancy UI; trend analysis.
- **Bad**, local builds don't fail; PR feedback is delayed; outage in
  Sonar wedges the team.

### Pre-commit hooks

- **Good**, fast feedback.
- **Bad**, easy to bypass with `--no-verify`; not enforced in CI.

## Links

- Locked decision LD5 (universal Javadoc).
- Rule override O1.
- [checkstyle.xml](../../checkstyle.xml).
- [pom.xml](../../pom.xml).

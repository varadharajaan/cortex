<!--
  PR title MUST follow Conventional Commits:
    feat(scope): ...
    fix(scope): ...
    chore(scope): ...
    docs(scope): ...
    refactor(scope): ...
    test(scope): ...
    build(scope): ...
    ci(scope): ...
    perf(scope): ...
-->

## Summary

<!-- One paragraph: what does this PR do? Why now? -->

## Phase / Issue

- Phase: `phase:Pxx`
- Closes: `#<issue-number>`

## Type of change

- [ ] feat (new user-visible capability)
- [ ] fix  (defect repair)
- [ ] refactor (no behavior change)
- [ ] perf (faster / lower memory)
- [ ] docs
- [ ] test
- [ ] chore / build / ci
- [ ] breaking change (describe migration below)

## Definition of Done checklist (Part 21)

- [ ] `./mvnw -B clean verify` passes locally (no skipped checks).
- [ ] Coverage >= 80% line and branch on changed packages.
- [ ] Checkstyle clean (universal Javadoc on every class and method).
- [ ] SpotBugs + FindSecBugs clean at High threshold.
- [ ] OWASP Dependency-Check shows no new findings >= CVSS 8.
- [ ] ArchUnit tests added/updated when layer surface changes.
- [ ] Testcontainers IT covers the happy path (no H2 ever).
- [ ] OpenAPI + GraphQL schema regenerated if endpoints changed.
- [ ] ADR added under `docs/adr/` for any architectural choice.
- [ ] Postman collection updated for any HTTP change (Part 25).
- [ ] `CHANGELOG.md` updated under `[Unreleased]`.
- [ ] No `--` or non-ASCII characters in source (Rule A0.1).
- [ ] No `System.out.println`, no `printStackTrace`, no field `@Autowired`.

## Security

- [ ] No secrets in source / logs / fixtures.
- [ ] Input validated at controller boundary (`@Valid`, jakarta annotations).
- [ ] Authorization rule expressed in `@PreAuthorize` or filter, not in service.

## Screenshots / Logs / Traces

<!-- For UI, API, or observability changes, attach evidence. -->

## Rollout / Rollback

<!-- How does this ship? What is the rollback procedure if it misbehaves? -->

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

## Definition of Done checklist (Part 21 + Part 26.10 + Part 26.11)

### Part 21 (build + quality gates)

- [ ] `./mvnw -B clean verify` passes locally (no skipped checks).
- [ ] Coverage >= 80% line and branch on changed packages.
- [ ] Checkstyle clean (universal Javadoc on every class and method).
- [ ] SpotBugs + FindSecBugs clean at High threshold.
- [ ] OWASP Dependency-Check shows no new findings >= CVSS 8.
- [ ] ArchUnit tests added/updated when layer surface changes.
- [ ] Testcontainers IT covers the happy path (no H2 ever).
- [ ] OpenAPI + GraphQL schema regenerated if endpoints changed.
- [ ] ADR added under `docs/adr/` for any architectural choice AND a
      row appended to `docs/adr/INDEX.md` in the SAME PR.
- [ ] Postman collection updated for any HTTP change (Part 25 + 26.9).
- [ ] `CHANGELOG.md` updated under `[Unreleased]`.
- [ ] `README.md` Status block, What's-working list, and tech-stack
      table updated to match `main` HEAD after this PR (Part 26.10.5).
- [ ] No `--` or non-ASCII characters in source (Rule A0.1).
- [ ] No `System.out.println`, no `printStackTrace`, no field `@Autowired`.

### Part 26.10 + LD73 five-leg triangle gate (mandatory BEFORE squash-merge)

- [ ] Leg A: `./mvnw -B clean verify` GREEN against the exact commit
      being merged (jar built, surefire 100% pass, failsafe 100% pass,
      JaCoCo line+branch>=0.80, Checkstyle 0, SpotBugs 0).
- [ ] Leg B1: `scripts/p<phase>/smoke-p<phase>.ps1` (or
      `scripts/smoke-p<phase>.ps1`) exit 0 against the live booted
      stack -- evidence log path cited below.
- [ ] Leg B2: `scripts/smoke-all.ps1 -Mode default` exit 0 (every
      prior phase still GREEN; SKIP_ENV allowed for rate-burst-only
      smokes when default gateway env is active).
- [ ] Leg B3 (when rate-limit code changed): `scripts/smoke-all.ps1
      -Mode rate-burst` exit 0 after rebooting gateway with
      `RATE_LIMIT_CAPACITY=5`.
- [ ] Leg C: `npx newman run postman/<collection>.postman_collection.json
      -e postman/...local.json` exit 0 (assertion count matches Part
      26.9 contract).

### Part 26.11 + LD86 (scripts-first discipline)

- [ ] Every ad-hoc verification command authored this PR lives under
      `scripts/p<phase>/` (or top-level `scripts/`) with a header
      comment block per Rule 26.10.8.3. No scratch `_foo.ps1` /
      `PR_*.md` / `pr-*.md` at repo root (now blocked by `.gitignore`).
- [ ] Run-log paths under `logs/local/` are cited verbatim below for
      every triangle-gate leg run.

### LD89 (false-ship hallucination antidote)

- [ ] PR merge claim below quotes the verbatim `gh pr view <N>` JSON
      stdout (mergeCommit.oid, mergedAt, state=MERGED), NOT a
      reconstructed summary.
- [ ] Issue close claim below quotes the verbatim `gh issue view <N>`
      JSON stdout (state=CLOSED, closedAt), NOT "Closes #N trailer
      should auto-close".
- [ ] Atomic 4-file flip (`plan.md` + `todo.md` + `checkpoint.md` +
      `memory.md`) lands in the SAME edit batch as the merge -- no
      stale "P<n-1> SHIPPED, P<n> in progress" pointers anywhere.

## Security

- [ ] No secrets in source / logs / fixtures.
- [ ] Input validated at controller boundary (`@Valid`, jakarta annotations).
- [ ] Authorization rule expressed in `@PreAuthorize` or filter, not in service.

## Screenshots / Logs / Traces

<!-- For UI, API, or observability changes, attach evidence. -->

## Rollout / Rollback

<!-- How does this ship? What is the rollback procedure if it misbehaves? -->

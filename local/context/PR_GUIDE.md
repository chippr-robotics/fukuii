# PR & Commit Guide

Every commit on `may-fields` must be ready for upstream. Think of each commit
as a potential PR to `chippr-robotics/fukuii`.

---

## Branch Naming

```
feat/snap-<topic>       New SNAP sync capability
fix/snap-<topic>        SNAP sync bug fix
fix/sync-<topic>        General sync bug fix
fix/network-<topic>     Network/P2P fix
test/snap-<topic>       Add or fix SNAP tests
chore/<topic>           Build, CI, docs, gitignore
refactor/snap-<topic>   Structural cleanup without behavior change
```

Examples: `feat/snap-storage-two-phase`, `fix/snap-stale-pivot`,
`test/snap-healing-coordinator`, `chore/gitignore-local-dir`

---

## Commit Message Format

Conventional commits. Pattern: `type(scope): description`

```
feat(snap): add two-phase storage download with flat-slot pre-pass
fix(sync): abandon storage recovery gracefully on stale pivot
fix(network): broaden sanitizer for <unresolved> DNS failures
test(snap): add StorageRangeCoordinator two-phase integration test
chore: add local/ to .gitignore
docs(snap): update SNAP_SYNC_STATUS with healing phase notes
refactor(snap): extract pivot selection into PivotBlockSelector
```

**Rules:**
- Lowercase type and scope
- No period at end
- Imperative mood ("add", "fix", "remove", not "added", "fixes")
- Subject line ≤72 chars
- Body (optional): explain WHY, not what — the diff shows what

**Types:** `feat`, `fix`, `test`, `chore`, `docs`, `refactor`, `perf`, `ci`
**Scopes:** `snap`, `sync`, `network`, `consensus`, `evm`, `rpc`, `db`, `build`

---

## One Commit = One Concern

Never mix:
- A bug fix + an unrelated refactor
- New feature + test for a different feature
- Formatting changes + logic changes (run `scalafmtAll` separately if needed)

If a formatter commit is needed before substantive work, that is its own commit:
```
style: scalafmtAll on may-fields base
```

---

## Pre-Commit Checklist

```bash
sbt compile          # Must pass — no exceptions
sbt test             # Must pass — fix broken tests before committing
sbt scalafmtAll      # Format — no diff after this = clean
git diff             # Review — nothing unexpected in the diff
```

For significant changes: `sbt pp` (full pre-PR suite, ~3 hr).

---

## Code Hygiene Before Committing

Remove from any code that goes into a commit:

- Local file paths (`/media/dev/2tb/...`) — use config injection or relative paths
- Environment-specific variables (`datadir`, port overrides) — use config
- Session/working notes in comments (`// TODO: check with Chris`, `// ATTEMPT 26`)
- Internal discussion in code comments (`// discussed with upstream: ...`)
- Debug `println` or temporary logging at WARN/ERROR level
- Commented-out code blocks left from investigation

What IS acceptable in comments:
- WHY something is done a non-obvious way
- Protocol spec references (`// EIP-4644 §4.2: proof must cover full range`)
- Known limitations with a tracked issue reference

---

## PR Format

Title matches the final squash commit message.

Body:
```markdown
## Summary
- <What changed and why — 2-4 bullets>

## Test Plan
- [ ] `sbt test` passes
- [ ] <specific test added or updated>
- [ ] <manual verification step if applicable>

## Notes
<Optional: anything reviewers should know about trade-offs or follow-up work>
```

---

## Rebase Discipline

```bash
# Before starting new work
git fetch upstream
git rebase upstream/main

# After upstream merges new commits
git fetch upstream
git rebase upstream/main
# Resolve any conflicts, then continue
```

Never merge upstream/main into may-fields — always rebase. This keeps the
commit history linear and each commit cherry-pickable.

---

## Cherry-Pick Test

Before opening a PR to upstream, verify each commit is self-contained:

```bash
# Create a test branch from upstream/main
git checkout -b test-cherry upstream/main
git cherry-pick <commit-hash>
sbt compile && sbt test
git branch -D test-cherry
```

If cherry-pick fails or tests break in isolation, the commit has a dependency
that needs to be resolved before it can go upstream.

---

## What Upstream Expects

Looking at merged PRs on `chippr-robotics/fukuii`:
- Focused scope — one issue per PR
- Tests included with the change
- Docs updated if behavior changes
- No internal comments, no local paths, no debug artifacts
- Conventional commit title on merge

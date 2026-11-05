# Worktree Protocol

Git worktrees isolate in-flight work from the main checkout. Use them when a
task touches enough files that partial edits would break `sbt compile-all` for
other concurrent work, or when a sprint has multiple sequential sub-prompts that
must all land together before merge.

Worktree dirs live at `.claude/worktrees/<id>` on branch `wt/<id>`.
Bin scripts at `.claude/agent-protocols/worktrees/bin/` automate the lifecycle.

---

## Decision table

| Scenario | Pattern | Example |
|----------|---------|---------|
| Multi-sub-prompt sprint where every sub-prompt is sequentially gated on the previous | **Sprint worktree** — one shared worktree for the whole sprint | §7c supervision hierarchy (P0→D→A→B→C→E3, all on `wt/7c-sprint`) |
| Single task with a clear gate condition on a prior merged item | **Task worktree** — one worktree per ticket, tear down after merge | §8b opaque types (H3→H8, each gets `wt/8b-h3` … `wt/8b-h8`) |
| Single-file or two-file change | **No worktree** — work directly on the branch | Typo fix, single-class rename |
| Parallel-safe but file-overlapping tasks that must land in a fixed order | **Task worktrees** running sequentially, not simultaneously | §8b H-series: H4 only starts after H3 merges to main branch |

**Rule of thumb:** if the sub-prompts share files or the sprint won't merge
until all sub-prompts are done, use a sprint worktree. If each sub-prompt is
independently mergeable and the next one is gated on the previous merge, use
task worktrees.

---

## Sprint worktree — lifecycle

One shared branch for all sub-prompts. Create before the first sub-prompt.
Merge after the final sub-prompt.

```bash
# 1. Create (run from main checkout, before first sub-prompt)
.claude/agent-protocols/worktrees/bin/wt-create.sh 7c-sprint scala3-cleanup-june
# → creates .claude/worktrees/7c-sprint on branch wt/7c-sprint

# 2. Enter worktree — all sub-prompts run from here
cd /media/dev/2tb/dev/fukuii/.claude/worktrees/7c-sprint

# 3. Sub-prompt P0 — commits freely to wt/7c-sprint
# 4. Sub-prompt D  — commits freely to wt/7c-sprint
# 5. Sub-prompt A  — commits freely to wt/7c-sprint
# ...
# N. Final sub-prompt commits last item to wt/7c-sprint

# N+1. Merge back (from /media/dev/2tb/dev/fukuii — main checkout)
git merge --no-ff wt/7c-sprint
git worktree remove .claude/worktrees/7c-sprint
git branch -d wt/7c-sprint
```

---

## Task worktree — lifecycle

One branch per ticket. Gate check runs against the main checkout before
creating the worktree (confirms the prerequisite was merged).

```bash
# 1. Gate check (in main checkout — confirm prior ticket is merged)
grep "opaque type Difficulty" \
  src/main/scala/com/chipprbots/ethereum/domain/Difficulty.scala

# 2. Create (run from main checkout)
.claude/agent-protocols/worktrees/bin/wt-create.sh 8b-h4 scala3-cleanup-june
# → creates .claude/worktrees/8b-h4 on branch wt/8b-h4

# 3. Enter worktree — all work happens here
cd /media/dev/2tb/dev/fukuii/.claude/worktrees/8b-h4

# 4. Implement + compile + test + commit within the worktree

# 5. Merge back (from /media/dev/2tb/dev/fukuii — main checkout)
git merge --no-ff wt/8b-h4
git worktree remove .claude/worktrees/8b-h4
git branch -d wt/8b-h4
```

---

## Naming convention

| Scope | Branch name | Worktree dir |
|-------|-------------|--------------|
| Sprint | `wt/<sprint-id>` | `.claude/worktrees/<sprint-id>` |
| Task | `wt/<task-id>` | `.claude/worktrees/<task-id>` |

Examples: `wt/7c-sprint`, `wt/8b-h3`, `wt/8e-s3d`, `wt/snap-spec-fix`.

No spaces. Use kebab-case. Keep IDs short but recognisable from the backlog.

---

## Bin scripts (quick reference)

```bash
# Create a worktree
.claude/agent-protocols/worktrees/bin/wt-create.sh <name> [base-branch]
# base-branch defaults to current HEAD branch if omitted

# List active worktrees (highlights .claude/worktrees/ entries)
.claude/agent-protocols/worktrees/bin/wt-list.sh

# Prune merged / stale worktrees under .claude/worktrees/
.claude/agent-protocols/worktrees/bin/wt-clean.sh
```

---

## Rules for agents operating inside a worktree

1. **Never push from a worktree.** The main session (orchestrator) merges
   and pushes — never the agent.

2. **Never merge from a worktree.** Merges always happen from the main
   checkout after all sub-prompts complete.

3. **Commit freely.** Commits in the worktree are visible to the main
   checkout on `wt/<id>`. Commit after each logical phase — don't batch.

4. **Run `sbt compile-all` from the worktree directory**, not the main
   checkout. The worktree has its own working tree; the main checkout is
   on a different branch.

5. **Gate checks use the main checkout.** `grep` for a prior ticket's
   artifact in the main checkout path, not the worktree path, before
   creating a new worktree.

6. **Continuation files go in the worktree's `.local/docs/continuations/`.**
   The path resolves to the worktree working tree, which is correct — the
   main thread reads it from there.

7. **`sbt formatAll` before committing**, same as on the main branch.

---

## When NOT to use a worktree

- Single-file or two-file changes: work directly on the branch.
- A task that must run on the same branch as other in-progress work.
- Short fix-up commits (formatting, comment tweaks, `.gitkeep` additions).
- Research / read-only analysis — no worktree needed for agents that only read.

---

## Teardown checklist

After the final merge back:

```bash
git worktree list                   # confirm the worktree entry is gone
git branch -a | grep wt/            # confirm the branch is deleted
.claude/agent-protocols/worktrees/bin/wt-clean.sh  # catches any stragglers
```

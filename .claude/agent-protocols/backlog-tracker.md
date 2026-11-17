# Backlog tracker protocol

**Rule (constitution Principle VIII):** all engineering work lives in one shared backlog, `backlog.yaml` on the
`tracker` branch, rendered on the docs site under Development → Tracker
(<https://docs.fukuii.com/development/tracker/>). Claim an item before you start it, keep its status current at each
transition, heartbeat while you hold it, and add what you find before your task ends.

**Why:** several hosts (fukuii-fc, dev-node) and cloud sessions work at the same time. Session notes, continuation
files and CHASE-QUEUE rows live on one host; the backlog is the one record every host can read and lock. Without it,
work is duplicated, dropped, or left in a file only one host can read.

The CLI is `scripts/backlog/backlog.py` (stdlib plus PyYAML; `python3-yaml` or `pip install pyyaml`).

## Setup, once per host

```bash
export FUKUII_NODE=fukuii-fc        # or dev-node, cloud-<name>: letters, digits, '.', '_', '-'
export FUKUII_AGENT=main            # optional: the default for claim --agent
alias backlog='python3 -I scripts/backlog/backlog.py'
```

Run it from anywhere inside a fukuii clone that can push to `origin` (or pass `--repo DIR`). On fukuii-fc, run it
from WSL: Windows git opens a credential window and hangs.

## The cycle

| Step | Command | Status after |
| --- | --- | --- |
| Find work | `backlog list [--priority P1] [--mine]`, `backlog show B-012` | |
| Claim before you start | `backlog claim B-012 --agent forge --branch fix/x` | claimed |
| Start | `backlog status B-012 in-progress` | in-progress |
| Open the PR | `backlog status B-012 review --pr 1750`; the PR body has `Backlog: B-012` | review |
| It merges | `backlog done B-012 --pr 1750` | done |
| Stop without finishing | `backlog release B-012 --reason "..."` | open |
| Cannot proceed | `backlog status B-012 blocked --note "waits for #1747"` | blocked |
| Not worth doing | `backlog drop B-012 --reason "..."` | dropped |
| Found more work | `backlog add --title "..." --priority P2 --area sync [--ref H4] [--issue N] [--note ...]` | open |

- **Claim first.** `claim` exits 3 when someone else holds a live claim and prints who and since when. Pick another
  item or ask the holder. Never work on an item another holder has claimed.
- **Subagents:** the main session claims for the work it delegates, with `--agent` naming the specialist (forge,
  beacon, vault, …). A subagent that runs on its own (a separate session) claims for itself.
- **Heartbeat at least daily** while you hold anything: `backlog heartbeat` refreshes all your claims. A claim with no
  heartbeat within `expiry_hours` (24) is STALE: the page flags it, and another holder may take it over with
  `claim --force`, which says so in the commit. `--force` never takes a live claim.
- **Before your task ends,** add every follow-up you found (review nits you deferred, a flaky test, a gap) and release
  or close what you hold. A continuation file names its B-ids; it does not replace them.
- **One item per logical unit,** roughly one PR. One-line title; notes at most 3 lines; details go in the PR, issue or
  spec. Use `refs` for old ids (H4, 10e, `#1654-B`, CHASE rows) and `depends_on` when an item cannot start earlier.
- **Never delete or reuse an id.** Close it as `dropped` with a reason.

Priorities: P0 blocks production or loses data; P1 production readiness or this sprint's commitment; P2 should be
done; P3 polish or opportunistic.

## Privacy

Never describe an undisclosed security issue in the backlog (constitution Principle VI): it is public. Keep it off the
tracker until the fix is public, or add a neutral placeholder with no detail. `backlog check` rejects advisory ids.

## How the lock works

A write is compare-and-swap through `git push`: fetch `tracker`, re-read and check the item, commit, push. A rejected
push means someone moved first, so the CLI fetches and checks again, at most 5 times with a short backoff. It never
force-pushes.

It never touches your checkout: it reads with `git cat-file` and builds commits with plumbing (`hash-object`,
`mktree`, `commit-tree`), so your working tree, index, HEAD, branches and worktrees are unchanged. Only the
remote-tracking ref `origin/tracker` moves.

Exit codes: 0 done; 1 error (git, network, an invalid backlog); 2 usage; 3 refused.

## People: editing by hand

Use the CLI where you can. You may also edit `backlog.yaml` in the GitHub web UI on the `tracker` branch: keep the
schema (the branch's README lists it), keep ids, keep notes short. Every push to `tracker` runs the branch's
`tracker-publish.yml`, which runs `backlog.py check` (a bad edit shows as a failed check) and rebuilds the docs site.
Comments other than the file's header are dropped the next time the CLI writes it.

## The page

`gh-pages.yml` renders the page (`backlog.py render --out docs/development/tracker`) before `mkdocs build --strict`:
on a push to main, on the dispatch that every push to `tracker` sends, and hourly. The committed
`docs/development/tracker/index.md` is a placeholder the render overwrites. To look locally, render into a scratch
directory: `backlog render --out /tmp/tracker`.

The page shows counts by status and priority, the burn-down (items not done per day, P0–P1 and P2–P3) and burn-up
(scope against done) from the branch's history, what is claimed (holder, agent, heartbeat age, STALE badge, branch,
PRs), what is in review or blocked, the open items by priority, and what was done in the last 14 days.

## Related protocols

- `migration-handoff.md` and continuation files carry detail; the backlog carries what exists, who holds it and its
  state.
- `inline-cleanup.md`: a CHASE-QUEUE row that another host must see becomes a backlog item.
- `worktree-protocol.md`: the work branch goes in `claim --branch`.

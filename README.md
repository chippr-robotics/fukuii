# fukuii shared backlog (`tracker` branch)

This branch holds one thing: `backlog.yaml`, the shared backlog of every piece of engineering work, who holds it
and its state. It shares no history with `main` or `staging`. The docs site renders it at
[docs.fukuii.com/development/tracker/](https://docs.fukuii.com/development/tracker/): counts, claims, the burn-down.
Every push here runs `.github/workflows/tracker-publish.yml`, which checks the schema and asks the docs workflow on
`main` to rebuild; an hourly run is the backstop.

The rules are constitution Principle VIII. The full protocol is `.claude/agent-protocols/backlog-tracker.md` on
`staging`; the CLI is `scripts/backlog/backlog.py` there.

## Claim protocol

1. Set your node once per host: `export FUKUII_NODE=fukuii-fc` (or `dev-node`, `cloud-<name>`).
2. Find work: `python3 -I scripts/backlog/backlog.py list`.
3. Claim before you start: `backlog.py claim B-012 --agent forge --branch fix/x`. It refuses (exit 3) and says who
   holds the item and since when, unless the item is free.
4. A claim is a commit plus a push to this branch. A rejected push means someone moved first: the CLI fetches,
   re-checks and retries, at most 5 times. It never force-pushes.
5. Keep the status current: `status B-012 in-progress`; `status B-012 review --pr N` when the PR opens;
   `done B-012 --pr N` when it merges; `release B-012 --reason ...` when you stop; `status B-012 blocked --note ...`.
6. Heartbeat at least daily while you hold a claim: `backlog.py heartbeat` (no id: all of yours).
7. A claim with no heartbeat for `expiry_hours` (24) is stale. Only then may someone else take it, with
   `claim --force`, and the commit says so.
8. Add the follow-ups you find before your task ends: `backlog.py add --title ... --priority P2 --area ...`.
9. A PR body names its item: `Backlog: B-012`.
10. Never describe an undisclosed security issue here. Keep it off, or use a neutral placeholder.

## Editing by hand

Use the CLI. People may also edit `backlog.yaml` in the GitHub web UI: keep the schema (the publish workflow runs
`backlog.py check` and fails on a bad edit), never delete or reuse an id (drop the item instead), and keep notes to
three lines. Details belong in the PR, the issue or the spec.

## Schema (version 1)

Top level: `version: 1`, `expiry_hours: 24`, `items:`. Each item:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | `B-001`…, stable, never reused |
| `title` | yes | one line |
| `priority` | yes | `P0` (blocking, data loss) … `P3` (polish) |
| `status` | yes | `open`, `claimed`, `in-progress`, `review`, `blocked`, `done`, `dropped` |
| `area` | yes | e.g. `sync`, `snap`, `storage`, `rpc`, `engine-api`, `p2p`, `evm`, `tui`, `tests`, `ops` |
| `opened` | yes | date |
| `refs` | yes | old ids: `H4`, `10e`, `#1654-B`, CHASE rows, `P-001` |
| `links` | yes | `issues: [n]`, `prs: [n]`, `spec`, `branch` (each optional) |
| `size` | no | `S`, `M`, `L` |
| `depends_on` | no | item ids |
| `notes` | no | at most 3 lines, no secrets |
| `closed` | done/dropped only | date |
| `claim` | claimed, in-progress, review (blocked may keep one) | `by`, `agent`, `since`, `heartbeat`, `branch` |

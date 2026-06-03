# Fukuii operations skills

Project-scoped [Agent Skills](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview)
that turn Fukuii's **maintenance and node-management runbooks** into repeatable,
guard-railed procedures. Each skill encodes *one* operational workflow, calls the
node's real interfaces (JSON-RPC `admin_*` / `eth_*` / `miner_*`, the in-process
MCP tools, the `fukuii cli` subcommands), and links back to the authoritative
runbook under `docs/runbooks/` for deep reference.

The goal is **consistent prompting and control**: every operator and every agent
runs the same validated steps against the same endpoints, with the same safety
gates, instead of improvising one-off commands.

## Why skills (not just docs or tools)

| Form | Fukuii has | Good for |
| :--- | :--- | :--- |
| **Tools** (atomic calls) | JSON-RPC `admin_*`/`eth_*`/`miner_*`, 15 read-only MCP tools, `fukuii cli` | One deterministic operation |
| **Runbooks** (prose) | `docs/runbooks/`, `docs/operations/` | Human reading, reference depth |
| **Skills** (this dir) | — | Multi-step *workflows* that orchestrate tools + judgement + safety gates, loaded on demand |

Skills sit on top of the existing tools. They do **not** replace the runbooks —
they reference them (Level-3 progressive disclosure), so the runbook stays the
single source of truth.

## Skill index

| Skill | Workflow | Backing runbook(s) |
| :--- | :--- | :--- |
| `fukuii-node-health-check`   | Full health verdict: status, sync, peers, height, log scan | operations/metrics-and-monitoring, MCP health prompt |
| `fukuii-sync-troubleshooting`| Diagnose stalled/slow SNAP or full sync; tuning levers | runbooks/snap-sync-*, operations/monitoring-snap-sync |
| `fukuii-peer-management`     | Inspect/add/remove/trust peers, maxPeers, static nodes | runbooks/peering, network-management; for-operators/static-nodes |
| `fukuii-backup-restore`      | Back up & restore datadir/keys; export/import chain | runbooks/backup-restore |
| `fukuii-disk-management`     | Disk-pressure triage, pruning, datadir sizing | runbooks/disk-management |
| `fukuii-log-triage`          | Set log level at runtime; pattern-triage logs | runbooks/log-triage, operations/LOGGING |
| `fukuii-mining-operations`   | Validate & control ETC mining via `miner_*`/`eth_*` | runbooks/mining-operations |
| `fukuii-key-management`      | Generate/encrypt keys & genesis allocs via `fukuii cli` | cli/CliCommands, runbooks/first-start |
| `fukuii-tls-operations`      | TLS for JSON-RPC; cert rotation | runbooks/tls-operations, security |
| `fukuii-checkpoint-service`  | Operate the checkpointing service | runbooks/checkpoint-service |
| `fukuii-node-configuration`  | Edit `fukuii.conf`, pick operating mode safely | runbooks/node-configuration, operating-modes |
| `fukuii-first-start`         | Bootstrap a brand-new node end to end | runbooks/first-start |
| `fukuii-security-hardening`  | IP block/unblock, trusted peers, RPC exposure review | runbooks/security; admin block/trusted methods |
| `fukuii-custom-networks`     | Stand up a private/consortium/custom-genesis chain | runbooks/custom-networks, enterprise-deployment |

## Validation

Every interface these skills name (RPC method, MCP tool/prompt, `fukuii cli`
subcommand, config key) is cross-checked against the node source on this branch —
see [`VALIDATION.md`](./VALIDATION.md) for the method, the last result, and the
runbook-vs-code drift the check caught. **Re-run it whenever the RPC surface,
CLI, or config schema changes.**

## Shared conventions

Every skill assumes the contract in [`CONVENTIONS.md`](./CONVENTIONS.md):
how to locate the node, call its RPC, and — most importantly — the
**guarded-write protocol** (confirm before any state-changing or irreversible
action). Read it once; skills reference it rather than repeating it.

## Authoring / extending

New operational workflow? Add `.claude/skills/<verb-noun>/SKILL.md` following the
template in [`CONVENTIONS.md`](./CONVENTIONS.md#skill-authoring-template), point it
at the relevant runbook, and add a row above. Keep `SKILL.md` bodies lean (the
heavy reference lives in `docs/`); the frontmatter `description` must say **what**
the skill does **and when** to use it.

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
| `fukuii-tls-operations`      | TLS and mTLS on every listener; identity rules; reload; outbound profiles | runbooks/tls-operations, security |
| `fukuii-checkpoint-service`  | Operate the checkpointing service | runbooks/checkpoint-service |
| `fukuii-node-configuration`  | Edit `fukuii.conf`, pick operating mode safely | runbooks/node-configuration, operating-modes |
| `fukuii-first-start`         | Bootstrap a brand-new node end to end | runbooks/first-start |
| `fukuii-security-hardening`  | IP block/unblock, trusted peers, RPC exposure review | runbooks/security; admin block/trusted methods |
| `fukuii-custom-networks`     | Stand up a private/consortium/custom-genesis chain | runbooks/custom-networks, enterprise-deployment |

## ETH/Sepolia — PoS operations

ETH/Sepolia is post-Merge Proof-of-Stake: the Execution Layer (Fukuii) is driven
by a Consensus Layer (CL) client over the Engine API. These skills cover that
pairing; they do **not** apply to ETC/Mordor (PoW, no CL).

| Skill | Workflow | Backing source |
| :--- | :--- | :--- |
| `fukuii-cl-setup`            | Pair a CL (Prysm/Lighthouse/Teku) with Fukuii; JWT, flags, startup order, 401 liveness check | `fukuii-engine-api-setup`, CL client docs |
| `fukuii-engine-api-setup`    | EL-side Engine API authrpc config: JWT secret, enable authrpc, verify handshake | base/network.conf, ops sepolia.conf |
| `fukuii-engine-api-debug`    | Troubleshoot Engine API faults: JWT 401, forkchoice timeout, newPayload INVALID, version mismatch, EL-vs-CL localization | sepolia-chain.conf, BEACON agent |
| `fukuii-sepolia-sync`        | Sepolia sync: pre/post-Merge phases (block 1735371), CL-driven import, post-Cancun blobs | base/chains/sepolia-chain.conf |
| `fukuii-pos-node-health`     | PoS-side health: CL↔EL liveness, attestation participation, blob gossip peers, Engine API latency | `fukuii-node-health-check`, Sepolia staking dashboard |

## Build & dependency

| Skill | Workflow | Backing source |
| :--- | :--- | :--- |
| `fukuii-dependency-audit`    | Audit all library versions; flag stale, CVE-affected, or non-LTS deps | `build.sbt`, endoflife.date, CVE feeds |
| `fukuii-tech-debt-inventory` | Inventory technical debt: deprecated APIs, suppressed warnings, TODO/FIXME, scalafmt violations | Source scan + scapegoat report |

## Release — agent improvement

| Skill | Workflow | Backing source |
| :--- | :--- | :--- |
| `fukuii-sprint-retro` | At each minor/patch release: review the sprint's PRs, turn them into an agent eval suite with gold answers, propose agent-file edits, and keep only the ones that win a blind A/B test against this and every earlier sprint's suite | `.claude/evals/` (suites, ledger), `.claude/agents/` |

## Communications

Run by the `crier` agent (`.claude/agents/crier.md`). Drafts are local files; posting to Discord is outward-facing and
needs the maintainer's explicit approval of the exact text and image.

| Skill | Workflow | Tools |
| :--- | :--- | :--- |
| `fukuii-release-announcement` | After a release: Discord post, release card (SVG + PNG), release blog post, then post on approval | `scripts/marketing/render_card.py`, `scripts/marketing/post_discord.py` |
| `fukuii-spec-blog-post`       | A spec closed or a track landed: blog post on the docs site, then a Discord note linking to it | same; posts under `docs/blog/posts/` |

## Spec Kit — Bug triage

Three-step structured bug triage. Artifacts land in `.specify/bugs/<slug>/`
and can be referenced in PRs. The workflow is strictly sequential.

| Skill | Step | What it produces |
| :--- | :--- | :--- |
| `speckit-bug-assess` | 1 — Assess | `.specify/bugs/<slug>/assessment.md`: root cause, reproduction steps, remediation options |
| `speckit-bug-fix`    | 2 — Fix    | `.specify/bugs/<slug>/fix.md`: changes applied, tests added, `sbt testEssential` result |
| `speckit-bug-test`   | 3 — Verify | `.specify/bugs/<slug>/test.md`: symptom reproduction verdict (`verified`/`partial`/`failed`) |

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

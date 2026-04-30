# Fukuii — Project Context

**Read this at the start of every session to orient quickly.**

---

## What Is This

Fukuii is a Scala Ethereum Classic (ETC) client, originally forked from Mantis
(IOHK), now maintained by Chippr Robotics LLC. It is being positioned as an
enterprise-grade multi-EVM client — sitting alongside go-ethereum, Nethermind,
and Besu as a production-grade implementation. ETC and Mordor are first-class
networks, but the design is expanding to support ETH testnets, rollups, and
enterprise EVM chains.

The Olympia upgrade (ECIP-1111/1112/1121) brings ETC and Mordor to Fusaka EVM
alignment with ETH mainnet. We are core ETC developers and institutional
implementors of EVMs for enterprise/TradFi clients.

**Status:** ALPHA — SNAP sync stabilization is the primary active workstream.

---

## Stack

| Component | Version |
|-----------|---------|
| Scala | 3.3.7 LTS |
| JDK | 25 LTS |
| sbt | 1.10.7 |
| Actor system | Apache Pekko 1.1.2 |
| DB | RocksDB |
| HTTP | Pekko HTTP 1.1.0 |
| IO | cats-effect 3 |

---

## Remotes

| Remote | URL | Role |
|--------|-----|------|
| `upstream` | https://github.com/chippr-robotics/fukuii.git | Canonical — rebase target |
| `origin` | https://github.com/chris-mercer/fukuii.git | Our fork |

---

## Active Branch: `may-fields`

- Tracks `upstream/main`
- Fresh start — no cherry-picks from `april-confluence`
- Every commit must be clean and cherry-pickable for upstream PRs
- Rebase frequently: `git fetch upstream && git rebase upstream/main`

---

## Commands

```bash
# Build
sbt compile          # ~27s
sbt assembly         # Fat JAR (~176MB) — required for running node

# Test
sbt test             # Unit tests (2,309 tests, ~9 min)
sbt it:test          # Integration tests (~30 min)
sbt pp               # Pre-PR: format + style + tests (~3 hr)

# Format
sbt scalafmtAll      # Format all Scala code

# Run node (always use JAR, not sbt run)
java -Xmx4g -Dfukuii.network=mordor -jar target/scala-3.3.7/fukuii-assembly-0.1.240.jar mordor
java -Xmx4g -Dfukuii.network=etc    -jar target/scala-3.3.7/fukuii-assembly-0.1.240.jar etc
```

---

## Key Source Paths

All source under `src/main/scala/com/chipprbots/ethereum/`

| Path | Purpose |
|------|---------|
| `blockchain/sync/snap/` | SNAP sync — state machine, coordinators, workers |
| `blockchain/sync/fast/` | Fast sync (SNAP fallback) |
| `blockchain/sync/regular/` | Regular block-by-block sync |
| `network/p2p/messages/SNAP.scala` | SNAP protocol messages |
| `network/snapserver/SnapServer.scala` | Serving SNAP to peers |
| `consensus/engine/` | Engine API (post-merge CL interface) |
| `nodebuilder/NodeBuilder.scala` | Node assembly, wires all components |
| `src/main/resources/conf/base/sync.conf` | SNAP/fast/regular sync config defaults |

---

## Architecture Reference

`ARCHITECTURE.md` in the repo root is the LLM-optimized codebase map — it is
committed to upstream and kept current. Read it for any component you haven't
touched recently. It covers every package, module, and key file.

`.claude/CLAUDE.md` has quick commands, boundaries, and the full Alpha Bugs list
(Bugs 1–31 already fixed and committed).

---

## Data Paths (local machine)

| Path | Contents |
|------|---------|
| `/media/dev/2tb/data/blockchain/fukuii/mordor/` | Mordor chain data |
| `/media/dev/2tb/data/blockchain/fukuii/etc/` | ETC mainnet chain data |
| `/media/dev/2tb/data/blockchain/fukuii/etc/logs/fukuii.log` | Live log |

---

## Network Ports (multi-client)

| Client | HTTP RPC | WS RPC | P2P |
|--------|----------|--------|-----|
| core-geth | 8545 | 8546 | 30303 |
| besu | 8548 | 8549 | 30304 |
| fukuii | 8553 | 8552 | 30305 |

---

## Reference Clients

| Client | Language | Path | PoW? | Role |
|--------|----------|------|------|------|
| core-geth | Go | `/media/dev/2tb/dev/core-geth` | **Yes — PoW** | THE reference for ETC/Mordor network config |
| go-ethereum | Go | `/media/dev/2tb/dev/go-ethereum` | No (PoS) | Canonical snap/1 protocol mechanics |
| besu | Java | `/media/dev/2tb/dev/besu` | ETC fork: PoW | Best current SNAP-serving peer for ETC |
| **reth** | **Rust** | `/media/dev/2tb/dev/reth` | **No (PoS)** | **High-perf reference — concurrency, peer mgmt, error handling** |
| erigon | Go | `/media/dev/2tb/dev/erigon` | No (PoS, dropped PoW) | Secondary — state storage patterns only |
| nethermind | C# | `/media/dev/2tb/dev/nethermind` | No (PoS) | Secondary — protocol edge cases |

**CRITICAL: ETC is Proof of Work. core-geth is the only production PoW client.**
All other reference clients are primarily PoS (post-merge ETH). When borrowing
patterns from geth/besu/nethermind/reth, filter out PoS-specific assumptions:
- No beacon chain pivot selection (we use PoW best-block consensus)
- No Engine API / CL callbacks in sync path
- No safe/finalized block concepts
- No withdrawal indexing during sync

**Reth-specific guidance:** reth v2.1 uses a staged pipeline sync (not actor-per-task).
Its Rust async/await model maps to Scala Futures/IO, not Pekko actors. Use reth as a
reference for: peer selection algorithms, concurrency control patterns, cancellation on
disconnect, backoff strategies, metrics. Do NOT attempt to copy its staged pipeline
architecture — Fukuii uses actors. Key paths in reth:
- `crates/net/downloaders/` — state downloading concurrency
- `crates/net/p2p/src/snap/` — SNAP protocol client trait
- `crates/stages/stages/` — staged sync pipeline
- `crates/net/eth-wire-types/src/snap.rs` — SNAP message types

Reference analysis docs live in `./local/reference/`. Read those before
consulting the source — they save significant time.

---

## Multi-EVM Design Rules

These apply to all new code on `may-fields`:

1. **No chain hardcoding** — chain identity comes from config injection, never
   from literals like `EtcChainId`, `MordorGenesisHash`, etc.
2. **Chain-scoped actors** — sync actors receive chain config at construction;
   never assume a single global chain context
3. **ETH testnet compatible** — SNAP sync must work against Sepolia and Holesky
   as well as Mordor and ETC mainnet
4. **Engine API aware** — SNAP sync must not conflict with Engine API state
   management (they touch different parts of the state machine)
5. **Checkpoint config** — block checkpoint numbers are chain-specific; inject
   from config, never hardcode

---

## Hive Testing

Integration tests run via the Hive framework at `/media/dev/2tb/dev/fukuii/hive/`.
Hive is the primary end-to-end verification for SNAP sync correctness.
See `.github/workflows/hive-sync.yml` for CI integration.

---

## Key Invariants

- All sync actors MUST use `.withDispatcher("sync-dispatcher")` to prevent RPC starvation
- `sbt run` kills the process after 1–3s — always use the assembly JAR for node operation
- Run `sbt compile && sbt test` before every commit
- Run `sbt scalafmtAll` to normalize formatting before committing

---

## Project Vision and Execution Strategy

**Goal:** The most performant client in the EVM space.

**Phase 1 — Foundation (current):** Quick iteration to a functional base. Get SNAP sync working end-to-end on ETC mainnet. Correctness over optimization. Every commit clean and cherry-pickable.

**Phase 2 — Hardening:** Known failure modes — persistence gaps, recovery paths, peer connectivity reliability. Prerequisites for production use.

**Phase 3 — Performance:** Deep optimizations from april-confluence findings and reference client analysis. Only meaningful once the base is solid.

**Known Phase 3 optimization areas** (on-radar, not blocking Phase 1/2):
- Concurrency — dynamic worker scaling, max-workers-per-peer limits
- Account range splitting — ranges returning >1MB need subdivision
- Parallelism — concurrent bytecode + storage coordinator phases
- Rate limiting — prevent per-peer request flooding
- Exponential cooldowns — per-peer backoff on timeout/error
- Resource management — memory pressure signals, backpressure to coordinators
- Peer quality filtering — prefer low-latency, complete-response peers
- Peer capacity tracking — infer chunk capacity from response sizes
- Peer responsiveness scoring — deprioritize consistently slow/empty responders

---

## Development Methodology

### Role of `april-confluence`

`april-confluence` is ~107 commits of iteration against ETC mainnet — a 20M+ account chain with large contract storage. It is a **treasury of real-world findings**, but is NOT the correctness authority. Use it as:

- **Starting point for identifying issues** — what broke? what edge cases appeared?
- **Scala/Pekko implementation patterns** — actor state structure, mutable map usage, etc.
- **Known problem checklist** — before implementing X, check if april-confluence hit a bug there

Do NOT use it as:
- A correctness reference (written against old codebase, may carry latent bugs)
- A design authority (reference clients override it for all protocol decisions)
- A reason to skip reference client review

Every april-confluence improvement must be:
1. Verified against reference clients — does go-ethereum or Besu do something similar?
2. Confirmed to exist in may-fields — is the underlying issue actually present?
3. Implemented cleanly from scratch with may-fields naming and structure

### Workflow for Every New Feature/Fix

1. **Identify the problem** — what behavior is missing or wrong on may-fields?
2. **Check reference clients first** — go-ethereum (canonical protocol), then Besu (ETC-compatible JVM)
3. **Check april-confluence** — Scala/Pekko patterns, known pitfalls at this site
4. **Synthesize** — implement using reference correctness + april-confluence patterns
5. **One commit per concern** — no mixed fix+refactor+test in a single commit
6. **Verify** — `sbt compile && sbt test` green before committing

### Trust Hierarchy

| Source | Trust | Use For |
|--------|-------|---------|
| go-ethereum source | Highest | Protocol correctness, timing values, design decisions |
| Besu source | High | ETC-specific behavior, Java/JVM analogies to Scala |
| EIP/ECIP specs | High | Protocol definitions |
| upstream/main docs | Medium | Intent, architecture decisions |
| april-confluence | Medium | Scala patterns, known bugs, starting point only |
| Mantis (original) | Low | Historical reference only |

# Fukuii — Rapid Iteration Development Guide

**Purpose:** This guide codifies the development methodology for may-fields. The goal is
fast, correct iteration: run → observe → identify → research → fix → verify → repeat.

---

## The Cycle

```
1. RUN        — Start node, let it run 5–10 minutes
2. OBSERVE    — Read the log, extract signal from noise
3. IDENTIFY   — Name each issue, classify it (noise / functional / critical)
4. RESEARCH   — Check april-confluence for prior fix, check reference clients for design
5. FIX        — Implement the fix cleanly (one concern per commit)
6. VERIFY     — sbt compile && sbt test → rebuild JAR → rerun
7. REPEAT
```

This cycle should complete in under 30 minutes for a noise/logging fix. Functional bugs
may require 1–2 hours of research and implementation. Critical bugs (sync stall, data
corruption) may require multiple sessions with reference client consultation.

---

## Step 2: Reading the Log

### Extract signal from noise

The default log is dominated by:
- `SEND_MSG: type=GetBlockHeaders` / `PEER_REQUEST_SUCCESS` / `PEER_REQUEST:` — sync churn
- `Auth handshake` / `[Stopping Connection]` — peer churn
- `BlockchainReader` errors — post-pivot noise

**What to actually watch:**

```bash
# Progress line (printed every ~13s)
grep "accounts/sec\|keyspace\|progress" fukuii.log

# Phase transitions
grep "AccountRange\|StorageRange\|ByteCode\|Healing\|pivot\|Pivot\|bootstrap\|Bootstrap\|SNAP.*Controller" fukuii.log | grep -v "PEER_REQUEST\|SEND_MSG"

# Errors that matter
grep "WARN\|ERROR" fukuii.log | grep -v "BlockchainReader\|PacketException\|DiscoveryNetwork\|HIVE-DEBUG\|mismatch"

# Shutdown / stop events
grep "stopped\|Stopping\|shutdown\|timed out\|stall" fukuii.log
```

### Progress interpretation

```
Account download progress: 442031 accounts (0.5% keyspace)
  (0/5 ranges done, 0 pending, 4 active, 5 workers/4 peers, 3449 accounts/sec)
```

- **keyspace** — how far through 0x00..0xFF account hash space we are
- **workers/peers** — active workers / available SNAP peers. `5 workers/4 peers` means
  one worker is waiting — normal. If peers < workers for >60s, peer starvation.
- **accounts/sec** — ETC mainnet has ~73.5M accounts → at 3,449/sec → ~6 hours to
  complete account download. Storage and healing add more time.

### Pivot refresh

A pivot refresh is expensive — it resets all in-flight requests and wastes work. Every
unnecessary refresh costs minutes. Watch for:
```
WARN StorageRangeCoordinator - Storage dispatch stalled ...
INFO SNAPSyncController - Refreshing pivot in-place ...
INFO SNAPSyncController - Pivot refreshed: block X -> Y, root A -> B
```
Each refresh increments the consecutive-refresh counter. At 3/3, SNAP falls back to
fast sync. Root cause is almost always one of BUG-003 (storage starvation) or the
stagnation watchdog firing.

---

## Step 4: Research Protocol

Two sources, two roles. Use both — in order.

---

### Part A: april-confluence — Identify the problem

april-confluence is ~107 commits from ~60 days of real iteration against ETC mainnet
on the old codebase. It contains battle-tested knowledge of exactly which bugs exist,
which files are involved, and what the root causes are.

**Role:** april-confluence tells you WHAT to fix and WHERE. It does not tell you
HOW to fix it correctly. The code was written under time pressure and may have
accumulated its own bugs or partial fixes. **Never treat a april-confluence fix as
the final implementation — always verify against reference clients.**

Use it to answer:
- "Has this exact symptom been seen before in Fukuii?"
- "Which files need to change?"
- "What is the root cause?"
- "Are there edge cases I should know about?"

```bash
# Find relevant commits
git log april-confluence --oneline --grep="keyword" | head -20

# Read a fix
git show <commit-hash> --stat
git show <commit-hash> -- src/main/scala/path/to/File.scala

# Compare current file vs april-confluence version
git diff may-fields..april-confluence -- src/main/scala/path/to/File.scala
```

**Trust levels:**
| Signal | Trust |
|--------|-------|
| "This bug exists and triggers at T+N" | HIGH — observed in production |
| "The root cause is X" | HIGH — usually traced carefully |
| "The fix is this code" | MEDIUM — may be incomplete or have follow-up bugs |
| Architecture / abstractions | LOW — refactor freely, code was written fast |

---

### Part B: Reference clients — Determine the correct fix

We have 5 production-grade reference clients representing the collective engineering
of hundreds of developers running on mainnet. This is a significant advantage.

**Role:** Reference clients are the implementation authority. They tell you HOW to
solve the problem correctly. When multiple independent clients implement the same
pattern, that is industry consensus — follow it. When only one does, verify that
client is authoritative for that specific domain.

**MANDATORY: Read actual source code before proposing any implementation.**
Agent summaries, commit message descriptions, and documentation are starting points
only. The source tells the truth.

#### Architectural proximity — which client to check first

Different clients have different relevance depending on the type of fix:

| Fix type | Check first | Why |
|----------|-------------|-----|
| JVM/actor concurrency, thread model | **Besu** | Java → Scala, same JVM runtime model |
| Protocol message handling, timing constants | **go-ethereum** | Canonical ETH implementation |
| High-performance patterns, cancellation, backpressure | **reth** | Rust async, most modern design |
| ETC-specific config (chainId, forks, ETChash, MESS, ETC hardfork blocks) | **core-geth** | Deprecated for general EVM — being superseded by Fukuii. Use only for ETC/Mordor chain specifics. |
| State trie, storage layout | **reth** or **erigon** | Most optimized trie implementations |
| Alternative approaches / tie-break | **nethermind** or **erigon** | Independent implementations |

#### Convergence signal

When 2+ independent clients implement the same pattern:

- **2 clients same approach** — strong signal, likely correct
- **3+ clients same approach** — industry consensus, follow it unless Fukuii has a
  specific architectural constraint that prevents it
- **Clients diverge** — understand why before choosing; the divergence often reflects
  a real tradeoff worth understanding

Example: go-ethereum, Besu, AND reth all cancel in-flight requests immediately on
peer disconnect → industry consensus → implement it in Fukuii.

#### Quick reference client source paths

**go-ethereum** `/media/dev/2tb/dev/go-ethereum`
```
eth/protocols/snap/sync.go           — account/storage/bytecode download state machine
eth/protocols/snap/handler.go        — SNAP serving side
eth/protocols/eth/handler.go         — ETH message dispatch, peer lifecycle
p2p/server.go                        — peer management, static peers, limits
p2p/dial.go                          — outbound dialing, reconnect, backoff
eth/downloader/downloader.go         — sync orchestration, pivot selection
```

**Besu** `/media/dev/2tb/dev/besu`
```
ethereum/eth/src/main/java/.../eth/sync/snapsync/
  SnapSyncConfiguration.java         — tuning parameters
  SnapWorldStateDownloader.java       — orchestration (closest to SNAPSyncController)
  AccountRangeDataRequest.java        — account range task + retry logic
  StorageRangeDataRequest.java        — storage range task + retry logic
ethereum/p2p/src/main/java/.../p2p/network/
  DefaultP2PNetwork.java              — peer lifecycle
  EthPeers.java                       — peer pool, limits, pruning
  MaintainedPeers.java                — static/maintained peer reconnect
```

**reth** `/media/dev/2tb/dev/reth`
```
crates/net/downloaders/              — state download concurrency + cancellation
crates/net/p2p/src/snap/client.rs   — SNAP protocol client trait
crates/net/eth-wire-types/src/snap.rs — SNAP message types
crates/stages/stages/src/stages/    — staged sync pipeline
crates/net/network/src/             — peer management, session lifecycle
```

**core-geth** `/media/dev/2tb/dev/core-geth`
> **Scope:** ETC/Mordor-specific topics only — ETChash PoW, MESS (Modified Exponential
> Subjective Scoring), ETC hardfork activation blocks, which EIPs are active on ETC,
> chainId/networkId. For general EVM functionality, SNAP sync, or peer lifecycle,
> core-geth is **deprecated** and being superseded by Fukuii. Do not use it as a
> reference for those areas.
```
eth/                                 — ETC-specific ETH protocol config
params/config.go                     — chain config, fork blocks, network IDs
params/config_classic.go             — ETC-specific fork block numbers, MESS config
```

**erigon** `/media/dev/2tb/dev/erigon`
```
eth/stagedsync/stages/               — staged sync (for architecture comparison)
p2p/                                 — peer management
```

**nethermind** `/media/dev/2tb/dev/nethermind`
```
src/Nethermind/Nethermind.Synchronization/SnapSync/   — snap sync implementation
src/Nethermind/Nethermind.Network/                    — peer management
```

---

### Research output

Before writing any code, write down (in conversation or scratch):
1. What april-confluence says the fix is (files + approach)
2. What each reference client consulted actually does (with file:line citations)
3. Where clients agree → that is the implementation target
4. Where Fukuii's actor architecture forces a different approach → note it explicitly

Only after completing A and B is it time to write code.

---

## Step 5: Fix Protocol

Every fix on may-fields must:

1. **One concern per commit** — no mixing logging + behavior + tests in one commit
2. **Reference client confirmed** — either matches a known pattern or explicitly notes divergence
3. **Test green** — `sbt compile && sbt "testOnly *<RelatedSpec>*"` before committing
4. **Clean message** — conventional commits: `fix(sync): ...`, `fix(snap): ...`, `fix(peers): ...`
5. **No local paths in code** — no `/media/dev/2tb/...`, no internal discussion in comments

### Commit message formula

```
fix(scope): one-line description of the behavior change

Explain WHY the old behavior was wrong and WHAT the fix does.
Reference client behavior if relevant.
April-confluence commit reference if porting: (from april-confluence fd1f8c1db)

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
```

---

## Step 6: Verify Protocol

After each fix:

```bash
# 1. Compile
sbt compile

# 2. Targeted tests (fast — run always)
sbt "testOnly *EtcPeerManager* *SNAP* *AccountRange* *StorageRange*"

# 3. Full unit suite before rebuild (when touching peer/sync code)
sbt test

# 4. Rebuild JAR
sbt assembly

# 5. Rerun node
java -Xmx4g \
  -Dfukuii.datadir=/media/dev/2tb/data/blockchain/fukuii/etc \
  -Dfukuii.network=etc \
  -Dfukuii.network.rpc.http.interface=0.0.0.0 \
  -Dfukuii.network.rpc.http.port=8553 \
  -Dfukuii.network.server-address.port=30305 \
  -Dfukuii.network.discovery.port=30305 \
  -Dfukuii.sync.do-fast-sync=true \
  -Dfukuii.sync.do-snap-sync=true \
  -jar target/scala-3.3.7/fukuii-assembly-0.1.240.jar etc
```

---

## Priority Classification

When multiple bugs are identified in one run, fix in this order:

| Priority | Class | Fix first if... |
|----------|-------|-----------------|
| 1 | **Noise** | Log is unreadable → can't observe real issues |
| 2 | **Functional-early** | Triggers within first 5 min (e.g., StorageRangeCoordinator stall at T+1:45) |
| 3 | **Functional-late** | Triggers only after hours (tail-stuck, healing stall) |
| 4 | **Performance** | Correct but slow |

Fix noise bugs first because they block observability of everything else.
Functional-early bugs are next because they prevent validating the fix for noise bugs.

---

## Session Handoff

At end of each session, update:
- `./local/snap/CURRENT_STATE.md` — current commit, what was fixed, what remains
- `./local/snap/BUG_LOG.md` — move fixed bugs to "Fixed" section, add new discoveries
- Git commit any completed work before stopping

---

## april-confluence as Bug Treasury — Usage Rules

april-confluence contains 107 commits from ~60 days of iteration against ETC mainnet
on the old codebase. Treat it as follows:

**DO use it for:**
- Confirming a bug exists and finding its root cause (saves hours of debugging)
- Finding which files need to change
- Understanding Pekko actor patterns that work in production
- Identifying known edge cases (storage tail-stuck, healing stall, OOM from unbounded buffers)

**DO NOT use it for:**
- Copying code wholesale — it was written under time pressure, may have accumulated debt
- Protocol design decisions — reference clients take precedence
- Assuming a fix is complete — april-confluence fixes were often partial or had follow-ups

**How to port a fix:**
1. Read the april-confluence commit (`git show <hash>`)
2. Understand WHY it works (not just what it does)
3. Verify the reference client does something equivalent
4. Implement cleanly from scratch in may-fields
5. If the logic is exactly right in april-confluence, cherry-pick only if the diff is clean —
   otherwise rewrite for clarity

---

## Known Bug Map (may-fields as of 2026-04-25)

| Bug | Severity | april-confluence fix commit(s) | First visible |
|-----|----------|--------------------------------|---------------|
| BUG-001: BlockchainReader ERROR spam | Noise | `e9f9d0647` | T+1:45 (post pivot refresh) |
| BUG-002: GetBlockHeaders tight loop | Noise | `bf29c0b62` (partial) | T+0s, continuous |
| BUG-003: StorageRangeCoordinator stall | Functional | `64c070176`, `9b5734251`, `c1a9c5dc4` | T+1:45 |
| BUG-004: AccountRangeWorker disconnect timeouts | Functional | `fc7e37ab7` | T+2:20 |
| BUG-005: Per-connection log noise | Noise | `2afc0da1d`, `6f2c2352d`, `8eef91ea1`, `6e3c2c423` | T+0s, continuous |

All five need to be fixed before the next run produces observable signal.
Implement in order: BUG-005 → BUG-001 → BUG-004 → BUG-003 → BUG-002.

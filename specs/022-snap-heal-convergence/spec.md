# Feature Specification: SNAP State Healing That Converges on a Live Chain

**Feature Branch**: `spec/022-snap-heal-convergence`

**Created**: 2026-10-28

**Status**: Draft

**Tracking**: issue #1671. Release 0.11.0 (#1659). The release waits on this (user decision 2026-10-28).

**Input**: SNAP state healing must converge on a live chain for both ETH (Path scheme) and ETC (Hash scheme). The node declares state complete when healing has nothing left to fetch from the current root, not after a full-trie verification walk. The full walk becomes a non-blocking background audit. The spec also evaluates retiring the Hash scheme. Full input: issue #1671 and its comments.

## Overview

After SNAP downloads accounts, storage and bytecode, **healing** fetches the trie nodes the download left missing or stale. The node must then decide that its state is complete before it hands off to block import.

Today fukuii cannot make that decision on a live chain.

**What happened on a fresh Sepolia sync (2026-10-27/28, Path scheme):**
- Healing drained its queue.
- The node then started a **full-trie verification walk**: 787 M nodes across 21 levels, about 17 hours on this host. The walk found 1.06 M missing nodes, which healing then fetched.
- The walk's result was thrown away. The sync pivot moves about every 12 minutes to stay inside what peers will serve, so the result was always out of date by the time it arrived.
- A walk counts only if the pivot did not move **and** the walk found nothing. On a live chain neither holds.
- After four stale walks the node falls back to slow on-demand repair, roughly 2.5 more days on Sepolia.
- ETC mainnet walks take 16–20 hours (measured for spec 006), so ETC has the same problem whenever its pivot moves.

**Why the walk exists.** The walk exists because fukuii's on-disk trie can contain a node whose subtree is incomplete, so seeing a node present is not proof that its subtree is complete. A code audit against staging `5a6b5b840` found three places where this happens:

| ID | Where | What breaks |
|----|-------|-------------|
| V1 | Healing (both schemes); the main cause | A healed node is written before its children are fetched. When the pivot moves, the node clears its pending child requests while the parents stay on disk. Healing from the new root sees the parents and never revisits the holes below them. |
| V2 | Account download (both schemes) | An account is committed to the account trie even when its storage download was abandoned, skipped or rejected. The trie above it looks complete. |
| V3 | Healing (Path scheme) | Requests for a node needed at more than one position ("aliases") are lost when the pivot moves. Duplicate children are dropped without being recorded. |

go-ethereum, Nethermind and Besu do not run this walk. They keep one invariant: **a node is written only after its whole subtree is present.** With that invariant:
- healing can walk down from the current root;
- healing can skip any node it already has;
- healing can declare completion when nothing is pending;
- after a pivot move, healing can restart from the new root and keep its disk progress.

This feature restores the invariant in fukuii and switches completion to the same rule, on both schemes.

This changes **when the node trusts its state as complete**, which makes it **consensus-adjacent**. A wrong "complete" decision produces a node that imports blocks against missing state. The Consensus-Critical Change Protocol applies:
- `beacon` reviews the ETH/Path changes;
- `forge` reviews the ETC/Hash changes;
- `vault` reviews the storage write-order changes.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A fresh ETH node finishes SNAP sync and follows the chain (Priority: P1)

An operator starts a new Sepolia node with a consensus client that checkpoint-syncs. fukuii downloads state, heals while the chain keeps moving, declares state complete, and starts importing blocks at the head.

**Why this priority**: This is the 0.11.0 release gate, and it is the failure we measured. Without it, a fresh ETH node does not reach the head in any useful time.

**Independent Test**: Run a fresh Sepolia sync on the reference host (4 cores, 16 GB RAM, one SSD).
- Pass: SNAP completes with no full-trie walk on the critical path.
- Pass: the state root at handoff matches the canonical header.
- Pass: the node then follows the head through the Engine API for at least 24 hours.

**Acceptance Scenarios**:

1. **Given** a fresh Path-scheme node whose pivot moves every few minutes, **When** healing has nothing left to fetch from the current pivot root, **Then** the node declares state complete and hands off to block import without waiting for a full-trie walk.
2. **Given** a pivot move in the middle of healing, **When** healing restarts from the new root, **Then** nodes already on disk with complete subtrees are not fetched again, and holes under previously healed parents are still found.
3. **Given** the node has handed off, **When** it imports the next blocks, **Then** every state read during execution finds its node locally. Any on-demand fetch is counted and logged with its cause.

---

### User Story 2 - A fresh ETC node finishes SNAP sync under the same rule (Priority: P1)

An operator starts a new Mordor node. Its SNAP sync completes on the same convergence rule as ETH, and ETC consensus behaviour does not change.

**Why this priority**: The user put ETC in scope for 0.11.0 and added a Mordor fresh sync as a release gate. ETC mainnet has the same latent non-convergence (16–20 hour walks).

**Independent Test**: Run a fresh Mordor sync.
- Pass: SNAP completes with no full-trie gate walk.
- Pass: the state root at handoff matches the canonical header.
- Pass: the node follows the head for at least 24 hours.
- Pass: the ETC regression suites stay green with no assertion changes.

**Acceptance Scenarios**:

1. **Given** a fresh Mordor node, **When** healing drains on the current root, **Then** the node declares state complete under the same rule as ETH.
2. **Given** the ETC consensus regression suites, **When** they run on the changed code, **Then** they pass with no assertion changes.

---

### User Story 3 - Healing survives a crash without leaving silent holes (Priority: P1)

The node crashes or is killed (for example by the out-of-memory daemon) in the middle of healing. When it restarts, no node that is present on disk has a missing child.

**Why this priority**: The invariant is only useful if it holds across crashes. A crash that leaves a parent on disk without its children brings back the exact failure this spec removes. Out-of-memory kills happen on this host.

**Independent Test**:
- Kill the node at fault-injection points during healing: after a parent is held, mid-chain of a commit, and during a pivot move.
- Pass: on restart, healing completes.
- Pass: an audit of the resulting trie finds no present node with an absent child.

**Acceptance Scenarios**:

1. **Given** healed nodes held while their children are outstanding, **When** the process is killed, **Then** on restart none of the held nodes is on disk unless all of its children are.

---

### User Story 4 - A node from before the fix can be repaired (Priority: P2)

An operator has a datadir written by the old code, for example the kept Sepolia datadir with about 32.8 M healed nodes. That datadir contains holes nothing recorded. The operator can run one repair pass that finds and fills them, then hand off.

**Why this priority**: Existing nodes are affected. This is a secondary test only: it exercises repair, not convergence, so it must never stand in for the fresh-sync acceptance tests.

**Independent Test**: Resume the kept Sepolia datadir (`/home/dontpanic/sepolia-data`) on the fixed build. A single audit and repair pass finds the old holes, healing fills them, and the node hands off.

**Acceptance Scenarios**:

1. **Given** a datadir written by the old healing code, **When** the fixed build starts on it, **Then** it detects that the datadir predates the invariant and runs one repair pass before declaring completion.

---

### User Story 5 - Decide whether ETC still needs the Hash scheme (Priority: P2)

The user's direction: "we do not need to keep hash if it is inefficient." The team decides, against stated criteria, whether new SNAP-synced nodes keep using the Hash scheme or move to Path, and what existing ETC nodes need.

**Why this priority**: The decision changes how much of this spec's Hash-scheme work is worth doing. It also interacts with v1 `.checkpoint` archives and spec 018. It does not block the ETH fix.

**Independent Test**: A decision record lists:
- every current use of the Hash scheme;
- the cost and benefit of each against the criteria in FR-020;
- the outcome;
- the migration or compatibility path for existing datadirs and archives.

**Acceptance Scenarios**:

1. **Given** the criteria in FR-020, **When** the evaluation is complete, **Then** the decision is recorded, and the plan either keeps the Hash-scheme changes or replaces them with a move of ETC to Path.

---

### Edge Cases

- **A pivot moves while parents are held in memory waiting for their children.** The held parents are dropped, not written. Healing from the new root finds them again.
- **The same node is needed at two positions (an alias), and the pivot moves before it arrives.** Both positions are filled, or both are re-discovered from the new root.
- **An account's storage download is abandoned, skipped, or fails its root check.** The path to that account in the account trie must not look complete. Healing must reach that account's storage.
- **An account's code is missing.** It is fetched before completion is declared.
- **The new pivot root is already fully present on disk.** Completion is immediate, with no walk.
- **Peers serving state drop to zero during healing.** Healing waits and keeps disk progress. It must not declare completion with work outstanding.
- **The background audit finds a hole after handoff.** The hole goes to on-demand repair. The node does not stop following the chain.
- **Path scheme: a shorter node replaces a deeper subtree.** Leftover nodes below it are removed or ignored, so nothing that iterates by position sees stale data.
- **Memory held for waiting parents grows past its bound.** The node pauses issuing new heal requests until the held set shrinks. It must not write parents early.

## Requirements *(mandatory)*

### Functional Requirements

**Invariant: a node on disk implies a complete subtree**

- **FR-001**: Healing MUST NOT persist a trie node until every child it references is either already present on disk with a matching hash, or persisted in the same atomic write. This holds on both schemes.
- **FR-002**: Healed nodes waiting for their children MUST be held outside durable storage. Memory for them MUST be bounded by a configured limit, sized from in-flight requests × trie depth. Reaching the limit MUST pause new heal requests, never trigger an early write.
- **FR-003**: When a held node's last outstanding child is persisted, the node and any ancestors that become complete MUST be persisted in one atomic write.
- **FR-004**: A process crash or kill at any point MUST NOT leave a persisted node with an absent child. Fault-injection tests MUST verify this.
- *Scope of FR-001 to FR-004 (slice 2 review, vault V3, 2026-10-29):* they hold under `pruning.mode = archive` and `basic`, where a healing write is a RocksDB batch when it returns. Under `pruning.mode = inmemory` the trie store is the node cache: healed nodes reach disk only as the cache evicts them, one by one, least recently used first, and its persist does nothing, so "persisted" does not mean "on disk". There the guarantee is the healing coordinator's full verification walk, not the invariant. Under `inmemory` nothing written after a heal flush is trusted as a completeness signal (slice 2 review, vault V3 and R1): no spec 005 subtree-complete record (pruned verification off), no spec 002 completeness marker or persisted-frontier resume (frontier persistence off, and a marker an earlier run left is cleared before healing starts), no spec 003 scoped verification (off). So every completion and every restart runs the full walk, unscoped and unpruned; it reads the store, and finds what a crash lost with the cache. The Hash controller's own trie walk is not a substitute: it checks that a present hash node is there without descending into it. FR-011 and FR-012's removal of the walk as a gate (slice 5, T051 and T052) must keep the coordinator's full walk as the completion gate under `inmemory`. `inmemory` is not the default and no shipped chain selects it.
- **FR-005** *(amended 2026-10-28; confirmed by the user on 2026-10-28)*: No account whose storage or code is incomplete may appear complete to healing. Every such account MUST be recorded durably before any action is taken on it.
  - Covers storage that was abandoned at force-complete, skipped after repeated empty responses, or rejected for a root mismatch, and code that is missing.
  - On Path, the positions above such an account are removed from the account trie, as go-ethereum's `genTrie.delete` does.
  - On Hash, nodes already flushed cannot be recalled, so the account's storage root and code are seeded for healing, resolved against the current root on every pivot move.
  - *Original wording:* "MUST NOT commit to the account trie an account whose storage or code is incomplete". This cannot be implemented on Hash, because the account trie is built before the storage outcome is known (research.md R-2).
- **FR-006** *(amended 2026-10-30; decided by the user)*: Every account excluded under FR-005 MUST be reachable by healing from the current root, and MUST be fetched before completion is declared.
  - If unservable bytecode is the only thing still blocking healing after 30 idle minutes, the node writes the chains waiting only on that code, verifies the rest of the trie with a walk, and takes a lazy hand-off with `bytecodeRecoveryDone` unset. Healing MUST NOT report complete in this case: no `StateHealingComplete` and no completeness marker. The node reaches the head, fetches missing code on demand, and runs the recovery scan at next start.
  - If anything else is still blocking, no such commit is made, and the existing abandon and re-peg paths apply.
- **FR-007** (Path): When one node is needed at more than one position, every position MUST be recorded, filled when the node arrives, and re-discovered if a pivot move drops the request.
- **FR-008** (Path): When a node is written at a position that replaces a deeper subtree, nodes left at deeper positions under it MUST be removed, or MUST be guaranteed never to be read as valid.

**Convergence and completion**

- **FR-009**: Healing MUST walk down from the current pivot root. It MUST skip a node already present with a matching hash (at its position, for Path), and MUST NOT descend below it.
- **FR-010**: On a pivot move, healing MUST restart from the new root. It MUST keep everything on disk and MUST drop held, unpersisted nodes. Keeping pending requests across the move (task #86) MAY be added as an optimisation, but correctness MUST NOT depend on it.
- **FR-011**: The node MUST declare state complete when healing has no pending, in-flight or held work for the current pivot root, and no account excluded under FR-005 is outstanding. This rule MUST be the same on both schemes.
- **FR-012**: A full-trie verification walk MUST NOT be a precondition for declaring completion on either scheme. This replaces:
  - the stale-discard rule and its fallback limit;
  - the requirement for a walk that finds nothing;
  - the separate controller walk on the Hash scheme.
- **FR-013**: After handoff, the node MUST be able to run the full-trie walk as a background audit that does not block block import. Any missing node it finds MUST go to on-demand repair and MUST be logged with its position.
- **FR-014**: Every on-demand state fetch during block import after handoff MUST be counted and logged, with the account or storage position, so operators can tell residual holes from expected misses.

**Existing datadirs and compatibility**

- **FR-015**: The node MUST record, in the datadir, that its state was healed under this invariant. On a datadir without that record, healing in progress or state not yet declared complete, the node MUST run one audit-and-repair pass before declaring completion. Under in-memory pruning the invariant does not hold on disk (healed nodes reach it only as the node cache evicts them), so the record MUST NOT be written or trusted there and the pass runs at every start.
- **FR-016**: The persisted heal frontier MUST stay bounded on both schemes. Entries for nodes that have been persisted MUST be removed (today the Path scheme never removes them).

**Observability**

- **FR-017**: The node MUST report:
  - held-node count and memory against the FR-002 bound;
  - pending and in-flight heal requests;
  - accounts excluded under FR-005 and still outstanding;
  - time since the current pivot root was adopted.
- **FR-018**: The completion log MUST state the pivot root, the number of nodes healed, and that no full-trie gate walk ran.

**Cleanup and evaluation**

- **FR-019**: Comments and log text that cite "spec 009" for moving-root healing MUST be corrected; spec 009 is the Amsterdam fork. Specs 003, 005 and 006, and the stale-discard logic, MUST be marked as superseded by this spec where their mechanisms are removed.
- **FR-020**: The plan MUST evaluate retiring the Hash scheme for SNAP-synced nodes and moving ETC to Path, and record the decision. The evaluation MUST cover:
  - each current use of the Hash scheme: v1 `.checkpoint` archives are hash-keyed and cannot load into Path; pruning modes; the spec 018 unified layout; existing ETC datadirs and their upgrade path;
  - criteria: sync time, disk size, import read cost, pruning behaviour, migration cost for existing nodes, and archive compatibility;
  - for the chosen outcome, the migration or compatibility path, and whether it lands in 0.11.0 or later.

### Key Entities

- **Held node**: a healed trie node whose children are not all present. It is kept in memory with a count of outstanding children, and is never on disk.
- **Heal request**: a request for a missing node at a position, under a given pivot root. On Path it carries every position the node is needed at.
- **Excluded account**: an account kept out of the account trie because its storage or code is incomplete, waiting for healing.
- **Invariant marker**: a durable record that the datadir's state was healed under the "present means complete" invariant.
- **Background audit**: the full-trie walk, run after handoff; it repairs what it finds and blocks nothing.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh Sepolia sync on the reference host goes from "state download done" to handoff in **under 12 hours**. Today it never finishes.
- **SC-002**: A fresh Mordor sync completes SNAP and hands off under the same rule. The time from download done to handoff is recorded as a baseline.
- **SC-003**: In both release-gate syncs, the state root at handoff matches the canonical block header. The node then imports and follows the head for **at least 24 hours** with no state-root mismatch.
- **SC-004**: In those 24 hours, on-demand state fetches caused by holes are **zero**, or a small count each traced to a cause recorded in the test report.
- **SC-005**: No full-trie walk runs before handoff in either sync. Each pivot move costs work proportional to what changed, not to the state size, as shown by nodes healed per pivot move in the logs.
- **SC-006**: Killing the node at each fault-injection point (FR-004) and restarting leaves no present node with an absent child, as checked by the background audit.
- **SC-007**: The ETC consensus regression suites and the Path-scheme healing tests pass with no assertion changes. New regression tests cover:
  - a heal interrupted by a pivot move before its children arrive;
  - an account over abandoned storage;
  - an alias lost across a pivot move;
  - convergence while the pivot keeps moving.
- **SC-008**: Memory held for waiting parents never exceeds its configured bound during either sync.
- **SC-009**: The Hash-scheme decision (FR-020) is recorded before the plan's Hash-scheme work starts.

## Assumptions

- The reference host is the one used for the failed sync (4 cores, 16 GB RAM, one SSD). The SC-001 time assumes peer availability like the 2026-10-27 run (1–9 state-serving peers).
- Lighthouse checkpoint sync supplies the Sepolia pivot. Mordor uses its usual pivot selection.
- No snapshot files are used. Sync time from other approaches (flat-state execution, snapshot distribution) is out of scope here; that is the separate sync-direction ADR, which amends specs 013 and 018.
- Until FR-020 decides otherwise, existing ETC nodes on the Hash scheme keep working on upgrade with no manual migration.
- The background audit (FR-013) may be disabled by configuration. It is off the critical path either way.
- The kept Sepolia datadir is used only for User Story 4. It is not written to before the fixed build exists.
- Reusable from earlier specs: the BFS engine and its backpressure (specs 001/002), the path-verified presence check, the persisted frontier, and the moving-root seed. Superseded: scoped verification (003), pruned verification (005), the skip-second-walk rule (006), the stale-discard limit, and most of the decoupled serve root (004).

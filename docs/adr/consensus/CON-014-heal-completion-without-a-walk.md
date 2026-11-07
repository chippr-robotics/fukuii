# CON-014: SNAP healing completes on "nothing outstanding for the current root", with no full-trie walk where the invariant marker stands

**Status**: Accepted

**Date**: 2026-11-02

**Spec**: `specs/022-snap-heal-convergence/` (FR-001 to FR-015, FR-020; `data-model.md` "Invariant marker")

**Supersedes**: [[CON-012]] where the marker stands (its byte-for-byte completion-parity invariant), [[CON-011]] (subtree completeness as a verification pass), [[CON-009]] (the CF `g` completeness marker, retired). **Amends** [[CON-010]]: the decoupled serve-root stays, but completion is no longer judged by the walk alone.

**Related**: issue #1671, release 0.11.0 (#1659); PRs #1682, #1684, #1685, #1688, #1690, #1691, #1693, #1695, #1697

## Context

Before spec 022, a node on disk did not mean the subtree under it was complete. Three things broke it:
- Healing persisted a parent before its children (research R-2).
- On Path, deeper nodes could be left under a shorter one (FR-008).
- Abandoned storage could sit under an account that looked whole (FR-005).

So completion rested on a full-trie verification walk that found nothing missing. CON-012 kept that decision byte-for-byte equal to the two-walk sequence it shortened.

On a live chain that walk does not finish. A walk over Sepolia's state took about 17 hours (787M nodes). The pivot moves about every 12 minutes to stay inside what peers serve, and a walk a re-peg overtakes is discarded. Specs 003, 005 and 006 each made the walk cheaper: 003 scoped it, 005 pruned it, 006 skipped the second one. None removed it, because nothing else could say the trie was complete.

## Decision

1. **Present means complete, where a healing write is a RocksDB batch.** Under `pruning.mode = archive` and `basic`:
   - Healing persists a node only when every child it references is on disk with a matching hash, or in the same atomic write.
   - Parents waiting on children are held in a bounded set in memory. Reaching the bound pauses requests; it never forces an early write.
   - A crash cannot leave a persisted node over an absent child (FR-001 to FR-004, slice 2).
   - Accounts whose storage or code is incomplete are recorded durably and reached by healing (FR-005, FR-006, slice 3).
2. **The rule.** Healing completes when nothing is pending, in flight or held for the current pivot root, the excluded-account set is clear, and the current root is present or has been fetched. The rule is the same on both schemes (FR-011).
3. **No walk as a precondition where the invariant marker stands.**
   - The marker (`snap.heal.invariant`, version 4) says everything present was written under the invariant. It does not say the state is complete. It is read at each decision.
   - Where it stands, completion takes no full-trie walk. The stale-discard rule and its fallback limit are bypassed there, and kept where the walk stays (FR-012, slice 5d).
   - On Mordor (T057, Hash), healing start to hand-off fell from 151 s with 4 walks (pre-spec-022 jar) to 0.3 s with none. That run healed one node. The one run with real healing behind a no-walk completion is P4 (Path, on a local jar with the ETC guard patched out): 160,333 nodes in 8 s across one re-peg. No Hash run yet shows the rule under load: T061a, T062 and T063 are open.
4. **Where the marker does not stand, the walk stays as the repair.** That is five cases:
   - a datadir from before the invariant;
   - a run that lost track of what it held;
   - a fresh Path account phase over existing nodes;
   - `pruning.mode = inmemory` on the Hash scheme, where healed nodes reach disk only as the node cache evicts them;
   - `deferred-merkleization = true`.

   The walk fills the holes it finds. A walk over holes that outlasts a re-peg, as at Sepolia's scale, fills what it reached and hands off lazily and unmarked, declaring no completion (T050a).
5. **The walk moves behind the head.** After hand-off a background audit walks the full trie at a 25% duty cycle. It sends holes to on-demand repair under its own fetcher and does not block import (FR-013, slice 5c). It is off under `inmemory`.
   - A pass is not a recompute of the hand-off root (decision 11). Induction holds per root, and one pass spans many roots. It covers by position, resolving each against the current best root.
   - On-demand fetches during import, and holes under `newPayload`, are counted and logged (FR-014). Such a hole is answered ACCEPTED, and import repairs it on reaching the block (#1697).
6. **One completion route on both schemes.** The Hash controller's separate trie walk is no longer a gate. Hash completes through `completeHealingWalkClean`, as Path does (T052). That walk's code is removed in spec 022 slice 6.
7. **FR-020: Hash stays for 0.11.0.** Moving ETC's SNAP to Path today costs an extra export scan and more disk during sync, for identical import reads and pruning (research R-6). Forge estimated 2-3× disk; that is not measured.
   - Retiring the scheme split is spec 018 DC-9 (user decision 2026-10-17, confirmed 2026-10-28): ETC re-syncs from a checkpoint onto the unified layout in spec 018's first release. The Hash scheme goes at F4, and `storage-scheme` is deprecated at F7.
   - R-6's condition to re-evaluate is that Mordor shows the export cost is small and Hash seeding is much larger than estimated. One half is measured: a Mordor Path export took 120.2 s for 5.54M nodes (T054). The other half is not. So the decision stands.

## Consequences

- **CON-012's parity no longer holds where the marker stands, by design.** There, completion is reached without a walk, from the invariant. Where the walk stays, spec 006's single-walk completion still runs.
- **The marker is stamped only in two places:**
  - at a SNAP start on an empty datadir;
  - at a completion whose root a clean full walk of the coordinator's covered.

  A completion with no walk keeps the marker as it found it and stamps nothing. Finalisation removes it with no clean decision behind the root, which includes every lazy hand-off.
- **What the invariant does not claim** is listed in `data-model.md`. The parts that matter most for ETC:
  - Code is never walked. Missing code is found by the audit, by the recovery scan, or at import.
  - On Hash, the walk proves presence and decoding, not `keccak(bytes) = key`. The audit checks the hash.
  - Holes an older binary's healing left, and nodes an import wrote over holes, are found when a root reaches them.
- **`inmemory` on Hash keeps a walk at every completion, with no audit after it.** At scale, a walk over holes there ends in a lazy hand-off, as before. Deferred merkleization also keeps the walk.
- **Upgrade cost, accepted:** an unfinished datadir from a jar before marker version 4 is treated as pre-invariant once. It costs one walk, the code scan owed, and an audit pass.
- **Not yet measured where it matters.** T061a (the excluded path, live) and T062 (Mordor on public peers) are open, and T063 is Sepolia's first sync under this rule. Heavy re-pegging is unit-tested (T046) until then. Forge would weigh a Mordor Hash soak before an ETC rotation, since on-demand repair is thinnest there (research R-5g).

## Alternatives rejected

- **Keep a walk and make it faster** (specs 003, 005, 006). Each removed a constant factor, and none fits inside a re-peg on Sepolia.
- **Remove the walk everywhere** (FR-012's first wording). In the five cases above the marker's claim is not true, and nothing else repairs. Removing the walk there would let a hole be declared complete.
- **Recompute the root at hand-off.** That puts back the gate this decision removes (decision 11). The hand-off takes a stratified sample instead (T054).
- **A hard stop when a run loses track of what it held.** A node that stops cannot be helped by the audit. Withdrawing the marker keeps the node running, and lets the walk and the audit cover the loss (research R-5e).

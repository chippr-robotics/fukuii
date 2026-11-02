# CON-014: SNAP healing completes on "nothing outstanding for the current root", with no full-trie walk where the invariant marker stands

**Status**: Accepted

**Date**: 2026-11-02

**Spec**: `specs/022-snap-heal-convergence/` (FR-001 to FR-015, FR-020)

**Supersedes**: [[CON-012]] (its byte-for-byte completion-parity invariant), [[CON-011]] (subtree completeness as a verification pass)

**Related**: [[CON-009]] (healing completeness marker), [[CON-010]] (decoupled heal serve-root), issue #1671, release 0.11.0 (#1659); PRs #1682, #1684, #1685, #1688, #1690, #1691, #1693, #1695

## Context

Before spec 022, a node on disk did not mean the subtree under it was complete. Healing persisted a parent before its children, Path could leave deeper nodes under a shorter one, and abandoned storage could sit under an account that looked whole (research R-2). So completion rested on a full-trie verification walk that found nothing missing, and CON-012 kept that decision byte-for-byte equal to the two-walk sequence it shortened.

On a live chain that walk does not finish. A walk over Sepolia's state takes about 17 hours, peers serve only the last 128 blocks or so (about 25 minutes), so the pivot re-pegs many times within one walk, and a walk that a re-peg overtakes is discarded and started again. The walk was the bottleneck the specs before 022 kept trimming (003 scoped it, 005 pruned it, 006 skipped the second one), never removed, because nothing else could say the trie was complete.

## Decision

1. **Present means complete.** Healing persists a node only when every child it references is on disk with a matching hash, or in the same atomic write. Parents waiting on children are held in a bounded set in memory; reaching the bound pauses requests and never forces an early write. A crash cannot leave a persisted node over an absent child (FR-001 to FR-004, slice 2). Accounts whose storage or code is incomplete are recorded durably and reached by healing (FR-005, FR-006, slice 3).
2. **The rule.** Healing completes when nothing is pending, in flight or held for the current pivot root, the excluded-account set is clear, and the current root is present or has been fetched. The rule is the same on both schemes (FR-011).
3. **No walk as a precondition where the invariant marker stands.** The datadir records that its state was healed under the invariant, and the record is read at the decision. Where it stands, completion takes no full-trie walk, and the stale-discard rule and its fallback limit go with it (FR-012, slice 5d). Measured on Mordor (T057): healing start to hand-off fell from 151 s with 4 walks (pre-spec-022 jar) to 0.3 s with none.
4. **Where it does not stand, the walk stays as the repair.** Five cases keep it: a datadir from before the invariant, a run that lost track of what it held, a fresh Path account phase over existing nodes, `pruning.mode = inmemory` (healed nodes reach disk only as the cache evicts them), and `deferred-merkleization = true`. There the walk is what fills holes, not a parity check (T050a, T051).
5. **The walk moves behind the head.** After hand-off a background audit walks the full trie at a 25% duty cycle, sends what it finds to on-demand repair under its own fetcher, and does not block import (FR-013, slice 5c). On-demand fetches during import and holes under `newPayload` are counted and logged (FR-014).
6. **One completion route on both schemes.** The Hash controller's separate trie walk is no longer a gate: Hash completes through `completeHealingWalkClean` like Path (T052).
7. **FR-020: Hash stays for 0.11.0.** Moving ETC's SNAP to Path today costs an extra export scan and 2-3× disk during sync, for identical import reads and pruning (research R-6). Retiring both the scheme split and the separate Path layer is spec 018 DC-9: ETC re-syncs from a checkpoint onto the unified layout in that release (user decision 2026-10-17). Re-evaluate only if Mordor shows the export cost is small and Hash seeding much larger than estimated.

## Consequences

- **CON-012's parity invariant no longer holds, by design.** Completion is no longer the outcome a clean walk would reach, byte for byte: it is reached without a walk, from the invariant. The marker CON-009 describes is still written on a clean completion, and finalisation removes it on any other.
- **What the invariant does not claim** is listed in `data-model.md`: holes an older binary's healing left, and nodes an import wrote over holes. The background audit is what finds those, and its first clean pass is the recompute of the hand-off root by induction (decision 11, T054).
- **`inmemory` and deferred merkleization keep a walk at every completion.** Operators on those settings see healing times as before.
- **Upgrade cost, accepted:** an unfinished datadir from a jar before marker version 4 is treated as pre-invariant once: one walk, the code scan owed, and an audit pass.
- **Not yet measured at scale.** Sepolia's first sync under this rule is release gate T063; heavy re-pegging is unit-tested (T046) until then.

## Alternatives rejected

- **Keep a walk and make it faster (specs 003, 005, 006).** Each removed a constant factor; none fits inside a re-peg on Sepolia.
- **Remove the walk everywhere (FR-012's first wording).** In the five cases above the marker's claim is not true and nothing else repairs; removing the walk there would let a hole be declared complete.
- **Recompute the root at hand-off.** By induction that is the audit's first clean pass; doing it at hand-off puts back the gate this decision removes (decision 11).
- **A hard stop when a run loses track of what it held.** A node that stops cannot be helped by the audit; withdrawing the marker keeps it running and lets the walk and the audit cover the loss (research R-5e).

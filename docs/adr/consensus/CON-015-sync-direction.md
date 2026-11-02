# CON-015: Sync direction: converging healing first, then flat execution reads, then state snapshots; execute-from-genesis only for ETC

**Status**: Accepted (the direction in spec 022's plan, research R-8)

**Date**: 2026-11-02

**Spec**: `specs/022-snap-heal-convergence/` (research R-8); `specs/018-storage-engine-consolidation/` (F6, F7); `specs/013-state-snapshots/` (Phase 0)

**Related**: [[CON-014]] (heal completion without a walk), [[CON-002]] (bootstrap checkpoints), [[CON-008]] (checkpoint distribution server), issue #1671

## Context

A node can reach a usable state in three ways:

- **SNAP**, then healing to converge on a live root. This is the default on every chain today.
- **Executing every block from genesis**, as a staged sync does. This trades the network for disk.
- **Downloading a state snapshot** taken by another node, then catching up. Spec 013 proposes this for ETH-family chains.

Execution in fukuii reads state only through the trie today (`BlockExecution`, `InMemoryWorldStateProxy.getAccount`; no flat storage on the execution path), so executing from genesis is trie-bound IO on this host's SATA SSD. On this host that is an IO model, not a measurement, with a 2-3× error band:

| Chain | Execute from genesis (estimated) |
|---|---|
| Mordor | about 2-5 days |
| ETC mainnet | about 1-3 months |
| Sepolia | about 2-4 months |

Measured on Mordor, SNAP from scratch:
- **2026-10-28, before spec 022:** 25.6 minutes. Accounts took 8 min, storage 8 min and bytecode 3.5 min. Healing then ran three full-trie walks of 4.78M nodes (48 s, 50 s and 96 s), and the hand-off came about 2.5 min after them.
- **After spec 022 slice 5d (S14, 2026-11-01):** 4 min 47 s in total, with healing at 0.3 s and no walk. The bytecode phase against the one local peer varies by more than the walk costs on a trie this small. The gain the walk's removal is for is at Sepolia's scale, measured by release gate T063.

## Decision

1. **Spec 022 (#1671) first:** healing that converges on a live chain (CON-014). Without it, SNAP on Sepolia did not reliably finish, and no other direction helps a node that cannot complete a sync.
2. **Then spec 018's flat execution reads** (F6, FR-011, on by default at F7). Reads from flat stores remove the trie walk per account and slot from execution. This decides whether executing from genesis is ever cheap enough to offer, so no execute-from-genesis mode is built before it.
3. **Then spec 013 Phase 0:** a measured Sepolia export (bytes per section, entries, wall time, peak RSS) before the snapshot format and its distribution are settled.
4. **A staged, execute-from-genesis mode only for Mordor and ETC, and only after flat reads exist.** On ETC it removes the dependence on the few snap-serving peers ETC has, at days (Mordor) to months (mainnet) by the model above. On Sepolia and Ethereum mainnet it would take months, so they stay on SNAP and, later, snapshots.

## Consequences

- **No execute-from-genesis work before spec 018 F6.** The figures above are a model. The first measurement comes with flat reads, on Mordor.
- **SNAP stays the default everywhere.** Spec 022's background audit and on-demand repair cover what a converged SNAP leaves behind.
- **ETC's thin snap-serving peer set stays the main ETC risk until then.** Forge would weigh a Mordor Hash soak before an ETC rotation (research R-5g).
- **Hash stays for ETC through 0.11.0**, and ETC moves onto spec 018's unified layout from a checkpoint (CON-014 decision 7, spec 018 DC-9).

## Alternatives rejected

- **Build the staged mode now, on trie reads.** It costs months on the ETH-family chains and days on Mordor, by an unmeasured model, and the work would be redone once flat reads land.
- **Snapshots before healing converges.** A snapshot still ends in a catch-up that heals and imports. A node that cannot converge after a snapshot is no better off.

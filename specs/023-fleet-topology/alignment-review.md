# Alignment review: specs 013 and 017–026 against the fleet end state

**Date**: 2026-10-30. **Tree**: `staging` 9e003ab (0.10.18). **Scope**: every candidate spec after 016, read against
the end goal recorded in [spec 023](./spec.md): a fleet of single-role fukuii processes per chain (`ingest`, `replica`,
`archive`, `sim`), a per-block change-record stream on Kafka, an indexer that owns analytics, Orbita as the control
plane. **Output**: per-spec verdicts, the amendments each needs (applied to 023–026, which are unimplemented drafts
from this sprint; recorded as *pending* on 013, 017 and 018; sequencing-only for 019–022), and a sequenced plan.
Decisions that are the user's are collected in §5.

## 1. Where the work actually is (verified on the tree, not from the status lines)

| Spec | Status line says | Tree says | Note |
| --- | --- | --- | --- |
| 016 SNAP controller split | Draft | **Complete** (R1 #1622; 020 OQ-4/5 record "016 complete") | `tasks.md` still shows 39 unchecked boxes: S0 pins, the per-slice checklist template, T054–T056. Housekeeping: mark done or move T056 (lighter WAL) to 018 E3, which now owns it. |
| 017 State capabilities, witnesses | Stub; starts after 020 | Not started. `StateIngest` (016 T055) exists; `StateAccess` does not. | 020's GT-0 condition for 017 (3.9 on staging) is met. |
| 018 Storage engine consolidation | Draft, ready for clarify | Phase 0 not started. Six amendment requests pending (from 023). | Track E waits on 017; Track F on Track E. |
| 019 zkVM guest | Stub | Not started; SP-1 spike not run. | Gated by 017 `StatelessExecutor` and 018 DC-8. |
| 020 Scala 3.9 LTS | Stub | **PR 3 landed**: `scala-3 = "3.9.0"`, sbt 1.13.0, `-source:3.6` with the DC-3 sites applied. PR 5 (187-file `-rewrite`) waits for 022. | Any code PR touching the 187 files must land before PR 5 is regenerated or after it, never beside it. |
| 021 Fixed-width UInt256 | Stub | Not started. | After 020 PR 5 (it retypes `vm/` callers the rewrite also touches). |
| 022 SNAP heal convergence | Draft | **Slices 1–3 merged** (#1682, #1684, #1685). Open: slice 4 (invariant marker, frontier retirement), slice 5 (completion switch, must be last), slice 6 (cleanup), two release gates (Mordor, Sepolia, 24 h each). 0.11.0 blocker. | Hash scheme kept for 0.11.0 (user, 2026-10-28). |
| 013 State snapshots | Draft, docs only | Not started. ETH-family only. | Becomes the follower seed (026). |
| 023–026 Fleet | Draft | Merged 2026-10-30 (#1686). Unimplemented. | This review amends 025 and 026 (§3). |

## 2. The one structural finding

**025 and 026 as merged depend on 018 Track F, which puts the first follower behind the engine bake-off, the engine swap
and the flat-layout migration.** That is the longest chain in the roadmap (017 → 018 Phase 0 → Track E → Track F)
and
none of it is a hard dependency of a change record.

The dependency came from a wrong choice in 025 DC-2/FR-008: the state diff was keyed by 018's storage namespaces,
**including trie nodes**. A record that carries trie nodes is a rollback log for one layout. A record that carries the
**semantic write set** (`address → account`, `(address, slot) → value`, `codeHash → code`, each with previous and
new
value) is layout-independent, is exactly what the indexer's balance tables need, and is already in memory at the end of
every block: `InMemoryWorldStateProxy` holds `accountsStateTrie` (pending account writes), `contractStorages` (per
address), `accountCodes` and `touchedAccounts` before `persistState` (`ledger/InMemoryWorldStateProxy.scala:184-196`).
Previous values are readable from the same proxies before the commit.

Consequences, all applied in §3:
- **025** records the flat diff; trie nodes never appear in a record. The exporter can be built on today's RocksDB
  layout (Hash or Path) as an observer of `persistState` plus the canonical-commit hooks, with no 018 code.
- **026** followers apply the flat diff to flat state and update their own trie from the changed keys, then compare the
  root. On today's code that is `MerklePatriciaTrie.put` over the changed accounts and slots (the same operation
  import performs), so a follower MVP exists before 018 Track F; under 018 F6/F11 it becomes the flat read path and the
  parallel root for free.
- **018 F2** keeps trie-node undo records for *local* rollback (engine-specific, never exported) and gains the flat diff
  as the shared, versioned part (amendment request 2, refined). One format for the exportable part; the node-level undo
  is an implementation detail of the hot tier.
- The **critical path to a first fleet** becomes: 025 schema → exporter on `ingest` (today's layout) → 013 seed →
  follower MVP → harness. 018 proceeds beside it and improves it; it no longer gates it.

The cost is that followers on today's layout rebuild a hash-keyed trie per block, which 018 later replaces. That work
is the `StateReplay` capability (§3, 018 request 7), and its apply/verify contract survives the layout change.

## 3. Per-spec verdicts and amendments

Legend: **Aligned** (no change), **Amend** (text below; applied where this sprint owns the spec, pending otherwise),
**Sequence** (no text change; ordering in §4).

### 013 State snapshots — Amend (pending section added to the spec)

Aligned: the flat-state v2 **format** is the follower seed (026 FR-001) and matches the flat stores 018 Track F will
make authoritative.

**Correction (2026-10-30, raised by the spec 022 session on the build host and verified here on 9e003ab): the format
has no populated source on today's layout.** 013 §5.5 exports by `scanRange` over flat state. On staging:
`FlatAccountStorage` is constructed (`db/components/Storages.scala`, `StoragesComponent.scala`) and **never written**;
`FlatSlotStorage` is written only by the SNAP download (`snap/actors/StorageRangeCoordinator.scala`), with no writer in
healing or under `ledger/`, so slots changed by healing or by any later block are stale; the Path CFs are frozen at the
pivot too (018 research.md:69); live state on ETH is the hash-keyed CF `n`; ETC has no flat state at all (018
research.md:72). An exporter built as §5.5 reads would produce snapshots that fail §5.4's root check on every import:
safe, never usable. Until 018 Track F makes flat state authoritative, the exporter MUST walk the trie at a frozen root
(the RocksDB checkpoint of §5.5 still gives the frozen view) and emit leaves in key order into the v2 format. That
removes §5.5's "no visited-set, the flat scan has no DAG" argument and the AC5 export-impact assumption, and changes
what Phase 0 measures (a trie walk, not a scan; the one figure in hand is Mordor's walk at 4,780,490 nodes in 36.6 s
on an idle SSD, from the 022 session). For 026, the **first** seed of any follower therefore comes from a walk; only a
follower that keeps flat state current from 025 records can later export by scanning. 013 §2 item 2 ("Path-scheme
reads rely on flat state") is out of date for the same reason (018 spec.md:48: never read by execution).

Misaligned with the fleet in four places, plus the correction above (013 pending §11 rows 6–9):
1. §5.5 exports from the validating node with a rate limiter. In a fleet the exporter runs on a `replica` or `archive`
   (any role holding a verified root at the block); `ingest` exports only in the single-node profile. The rate-limiter
   design stays for that profile.
2. Scope is ETH-family only. 026 needs an ETC seed, and the ETC checkpoint archive v1 is hash-keyed and host-trusted
   (013 §2) and dies with the Hash scheme under 018 DC-9. Make v2 the one seed format for both families; v1 is kept
   only until 018 DC-9 lands. ETC's anchor is the fleet's own `ingest` head (there is no CL), with the §5.4 root
   recomputation as the content check, which is unchanged.
3. §5.9 catch-up lists re-execution and healing. Add the third strategy, apply 025 records from N+1, which is the
   follower default 026 GT-1 measures.
4. §5.7 incremental snapshots lose most of their value once followers apply records; keep as Phase 4, lowest priority.
Also: Q2 (mainnet hosting budget) splits into fleet-internal artifacts (object store, no public egress) and the public
publisher flow, which is a separate product decision.
From spec 022, for 013 and the plan: Sepolia Phase 0 cannot use the kept datadir (known holes); it needs the fresh-sync
datadir from 022's Sepolia gate, so 013 Phase 0 follows that gate (phase B placement holds). Import (§5.10) should
reuse 022 slice 4's markers, `checkpoint.import.inProgress` and `snap.heal.invariant = 2` stamped only after the root
check (022 `data-model.md` rows 67–68), rather than a separate "unfinished snapshot" record. §5.9(b) cites specs
001–006 for healing; 022 replaces that mechanism. Slice 4 also retires the persisted frontier (CF `g`); nothing in
023–026 assumes it (checked).

### 016 SNAP controller split — Aligned, one housekeeping item

`tasks.md` is stale (§1). `StateIngest` is scoped to SNAP; it is **not** the follower write path (see 018 request 7).

### 017 State capabilities and witnesses — Amend (pending section added to the spec)

Aligned: `StateAccess` as the single execution writer is `ingest`; DC-16's bounded executor is the same mechanism as
018 FR-027 and 023 DC-8; witnesses as "an analysis data plane" sit beside 025 records (different artifacts: read set
with proofs vs write set; the indexer may want both).
Misaligned:
1. **Role mapping.** DC-16 bounds `debug_executionWitness` to the ~64-block state-history window because regeneration
   needs the parent state. An `archive` has every parent state, so on the fleet `archive` serves any N by regeneration
   and `replica` serves its window; `ingest` serves nothing in the fleet profile. Backfill mode (G-3, FR-008) runs on
   `archive`, never on `ingest`. Capture-during-import stays an `ingest` option for operators who want stored
   witnesses without an archive.
2. **`SyncMode` (FR-007)** gains `Follow` (or is derived from the 023 role): followers are never `Snap`, never
   `Backfill` unless `archive`, and capture is constructible in `Follow` on an `archive`.
3. **Witness store (FR-004, "slow tier #1483")**: on the fleet the store is the archive's own store, or witnesses are
   exported as an optional record type on the 025 topic (025 OQ-6, new). The node still writes no analytics store.
4. 018 DC-7 (key-set recorder under flat reads) already amends 017 DC-8/FR-003a; unchanged here.

### 018 Storage engine consolidation — Amend (pending table extended: rows 2, 7, 8, 9)

Aligned by construction (DC-16 is the fleet). Changes:
- **Row 2 refined**: the changeset's exportable part is the flat diff (prev + new, `chainKey`, version byte); trie-node
  undo stays local. See §2.
- **Row 7 (new): `StateReplay` capability.** Followers are a third writer kind: not SNAP (`StateIngest`), not EVM
  (`StateAccess`). `vault` owns a capability in `db/` that applies a flat diff forward or backward, updates the
  commitment trie from the changed keys, and returns the root; fully durable; used only by follower roles. It is the
  contract 026 builds on and the one that survives Track F.
- **Row 8 (new): Track H demoted.** With 025 DC-7 the node writes only Kafka; H3 (ClickHouse writer) is superseded. H4
  (`eth_getLogs` beyond the hot window through a `ColdStore` read adapter) is a **single-node-profile convenience**:
  on the fleet, Orbita's gateway routes long-range log queries to the indexer directly. GT-3 decides whether H4 ships
  at all; H2 (era files) stays, it serves `ingest` history expiry.
- **Row 9 (new): DC-9 resync path.** "ETC resyncs from a checkpoint archive" becomes "ETC re-seeds from a 013 v2
  snapshot" once 013 covers ETC; checkpoint v1 is retired with the Hash scheme.
- US-8 (enterprise risk engine against one node) should say the fleet carries that load; wording only, when accepted.

### 019 zkVM guest — Sequence

No process-model assumptions; the guest consumes a witness (DC-7) and nothing else. On the fleet, witnesses come from
`archive` or an `ingest` with capture on. SP-1 (the two-track feasibility spike) is off-host and can run any time the
user approves. The guest proper is strategically important (client diversity) and not on the fleet's critical path;
with one contributor it comes after 026 (§4 phase E); with a second it runs beside phase D.

### 020 Scala 3.9 — Sequence

PR 3 is on staging; PR 5's 187-file rewrite is regenerated after 022 lands. The fleet code PRs that touch launcher,
config and node wiring (023 T-002/T-003, 024 T-002/T-003) must be scheduled in the same window as 022 slices 4–6
(before PR 5) or after PR 7, and checked against `specs/020-scala-3-9-lts-migration/dc3-sites.md` and the PR 5 file
list first. Nothing else in 020 interacts with the fleet.

### 021 Fixed-width UInt256 — Aligned, Sequence

It is the fleet's serving workload (`sim` and `archive` trace and `eth_call` throughput) and 019's FR-003b. After 020
PR 5 (shared `vm/` files). GT-1 (impact analysis) can run earlier, off the hot files.

### 022 SNAP heal convergence — Aligned

SNAP is an `ingest`-only concern on the fleet (026 DC-5: followers never SNAP), so 022's complexity is confined to one
role. FR-020's decision (Hash kept for 0.11.0; retirement is 018 DC-9) is consistent with 023–026. The invariant
marker (FR-015) and background audit (FR-013) are per-datadir and stay on `ingest`. Nothing to change; finish it.

### 023 Fleet topology — Amend (applied)

Two gaps found on re-read:
1. **Transaction ingress.** "Transactional processing" was in the goal and no role accepts `eth_sendRawTransaction`
   except the single-node `ingest`. Followers and `sim` refuse writes (DC-9). Added **FR-010**: followers and `sim`
   forward raw transactions to the chain's `ingest` admission endpoint (never to peers), with a per-process rate cap
   and the ingest's txpool as the single admission point; and **OQ-5**: whether a `relay` role (txpool + admission
   checks against follower state + gossip) is needed at scale, as its own spec. The fence (FR-005) also stops
   accepting forwarded transactions.
2. **Fence and the Engine API.** A fenced ETH-family `ingest` must stop serving `engine_*` (close the listener) so the
   CL's reconnect fails loudly and Orbita repoints it; added to FR-005. ETC mining stays a single-node-profile option
   (FR-002 table).

### 024 Chain registry — Aligned

One transitional note: `storage-scheme` is a ChainSpec field only until 018 DC-9 unifies the layout; the validator
then rejects it as unknown. Already implied by FR-004; no change.

### 025 Change-record export — Amend (applied; §2)

DC-2, FR-008 and the state-diff entity now say **flat, semantic diff, never trie nodes**; GT-1 says the exportable part
is shared with 018 F2 and the node-level undo is not; a new OQ-6 asks whether witnesses (017) ride the same topic as
an optional record type; the Problem section names the shorter critical path.

### 026 Follower roles — Amend (applied; §2)

FR-002 applies the flat diff through the `StateReplay` capability (018 request 7) and updates the trie from the changed
keys; a note states the pre-018 MVP on today's layout and what 018 F6/F11 later replace; `SyncMode.Follow` (017) is
referenced; GT-1's option (b) is the prior.

## 4. Sequenced plan (proposal)

Phases are ordered by dependency, not by calendar. Items in one phase are parallel unless a note says otherwise.
Host windows (one build host, soak windows block builds) are 022's constraint and stay so through phase B.

| Phase | Release | Work | Why here |
| --- | --- | --- | --- |
| **A** (now) | 0.11.0 | 022 slices 4–6 and both release gates. `/speckit-clarify` on 023–026 (docs; no host). 024 T-001 golden capture (test-only). 025 T-001 schema + T-002 hook table with `forge`/`beacon` (docs). 018 Phase 0 off-host items that need no code: R-5 prior art, R-9 cold-tier assessment with Track H demoted. | 0.11.0 waits only on 022. Everything else here is paper or test-only and touches none of 022's files. |
| **B** | 0.12.0 | 020 PR 5–7 regenerated after 022; **then** 023 T-002 (runtime retirement), T-003 (`--chain/--role`), T-004 (role gating), T-005 (health); 024 T-002–T-005 (loader, `knownNetworks` removal, datadir check). 018 T-010 close-fence on RocksDB (independent of GT-1). 021 GT-1 impact analysis. 017 `/speckit-specify` + `/speckit-plan`. 013 Phase 0 measurement as a **trie walk at a frozen root** (013 §11 row 6), on the fresh Sepolia datadir from 022's gate and on Mordor. | Small code PRs on launcher/config/node wiring, sequenced around PR 5. The close-fence is the first thing followers and `sim` need from storage. |
| **C** (first fleet) | 0.13.0 | 025 T-003–T-007: exporter seam, file sink, reference consumer, Kafka, position + buffer, reorg fixtures, on **today's layout**. 018 request 7 `StateReplay` v1 on today's layout (vault). 026 T-001 GT-1 measurement, T-002 follower apply loop, T-003 `replica` RPC. 013 Phases 1–2 (ETH seed). 023 T-007/T-008 harness + isolation SCs. 018 Phase 0 GT-0/GT-1 with the read-fleet profile, in parallel. | Delivers `ingest` → Kafka → `replica` → indexer on one ETH-family chain (Platåberget) and ETC (Mordor) without waiting for the engine. |
| **D** | 0.14.0 | 018 Track E (engine) then Track F (flat model, changesets with the flat diff as the shared part, parallel root); 026 followers move onto the unified layout; 026 T-004/T-005 `archive` (GT-2); 017 implementation with witness serving on `archive`; 013 ETC seed replaces checkpoint v1 (018 DC-9); 021 implementation after 020 PR 5; 023 T-006 `sim` on 018 Track R executors. | The engine and layout work lands under a running fleet, measured against it. |
| **E** | later | 019 guest (SP-1 any time, off-host, with approval); 023 OQ-5 `relay` role as spec 027 if phase C shows ingest admission is the bottleneck; 013 Phase 4 incremental snapshots if still wanted. | Not on the fleet's critical path. |

Critical path to a first fleet: 022 gates → 020 PR 5 → 023 T-002/T-003 + 024 → 025 exporter → 013 ETH seed →
026 follower MVP → harness. 018 is beside it from phase B on.

## 5. Decisions for the user

| # | Decision | Recommendation | Where it binds |
| --- | --- | --- | --- |
| D-A | State diff at the semantic (flat) level, never trie nodes, so 025/026 do not wait for 018 Track F | **Yes.** Applied to 025/026; 018 row 2 refined (pending). | §2 |
| D-B | A `StateReplay` capability in `db/` (vault) as the follower write path | **Yes.** 018 row 7 (pending). | 026 FR-002 |
| D-C | 013 covers both families, exports from followers, and replaces checkpoint v1 with 018 DC-9; until 018 Track F the exporter walks the trie at a frozen root instead of scanning flat state | **Yes.** 013 pending section (rows 1–9). | 026 FR-001, 018 row 9 |
| D-D | Witness serving and backfill on `archive`; `SyncMode.Follow` | **Yes.** 017 pending section. | 017 DC-16, FR-007 |
| D-E | Track H demoted: node writes only Kafka; H4 is a single-node convenience decided at GT-3 | **Yes.** 018 row 8 (pending). | 018 Phase 4 |
| D-F | Transaction ingress: forward to `ingest` now; `relay` role as spec 027 only if measured necessary | **Forward first.** 023 FR-010, OQ-5 (applied). | 023 |
| D-G | 019 priority: after 026 with one contributor, beside phase D with two | Your call on staffing; SP-1 can run any time. | §4 phase E |
| D-H | Release mapping A–E above | As proposed; 0.11.0 unchanged. | §4 |
| D-I | 016 `tasks.md` housekeeping (mark done; move T056 to 018 E3) | Yes, docs-only. | §1 |

## 6. What this review did not do

No spec text of 019–022 was changed. 013, 017 and 018 received clearly marked **pending** sections only; nothing in
them binds until accepted. 023–026 were corrected directly because they are this sprint's unimplemented drafts. No
code was touched. Figures in §2 about what `InMemoryWorldStateProxy` tracks were read from the tree at 9e003ab; the
claim that previous values are readable before commit is a design statement to be confirmed by 025 GT-2's hook table,
not a measurement. The flat-state correction in §3 (013) was raised by the spec 022 session (`dev-node`) and verified
here by grep on the same tree; the Mordor walk figure is theirs, not re-measured.

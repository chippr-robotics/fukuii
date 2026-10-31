# Feature Specification: Fleet Topology — Process Model, Roles and the Contract to Orbita

**Feature Branch**: `023-fleet-topology`

**Created**: 2026-10-30

**Status**: Clarified (2026-10-31). OQ-1..OQ-5 resolved in the Clarifications session; ready for `/speckit-plan`. The Evidence figures from
the originating notes were re-measured on `staging` 0f1a904 (0.10.17) and corrected in [research.md](./research.md) §1;
the T-001 retirement inventory is done ([research.md](./research.md) §2). **Revised 2026-10-30**: the cross-spec
[alignment review](./alignment-review.md) added FR-010 (transaction ingress), OQ-5 (`relay` role), the Engine API
clause of FR-005, and a sequenced plan for 013 and 017–026. Nothing here is implemented.

**Input**: User direction, 2026-10-30: "the eventual goal will be to have a scalable fukuii 'cluster' running that is
capable of syncing and managing multiple EVM networks", feeding a larger pipeline for **analytics and transactional
processing** (balances, simulation) for many users. Initial sync is a one-time cost; blocks then arrive on a schedule.

**Scope boundary**: fukuii is the **execution layer** and nothing else. The fleet's control plane (placement,
failover, replica seeding, scaling, request routing, multi-tenant admission) is a **separate project, Orbita**. This
spec and its three companions define what fukuii must provide so that Orbita and the Chain Intelligence indexer can
build the cluster around it.

---

## How the originating notes were split

The conversation notes arrived as one spec. They cover four deliverables with different owners, dependencies and
start dates, so they are four specs. This one is the umbrella; it owns the process model and the contract to Orbita,
and it is where the cross-cutting decisions live.

| Spec | Owns | Can start | Blocks on |
| --- | --- | --- | --- |
| **023 fleet-topology** (this) | One process, one chain, one role; role wiring; runtime retirement; health, lag, lifecycle and fence; `sim` isolation; the fleet test harness; the Orbita contract | Now | Nothing for T-001..T-005; 018 Track R for `sim` caps |
| **[024 chain-registry](../024-chain-registry/spec.md)** | ChainSpec: a chain is a validated registry entry, no release needed; MESS default | Now | Nothing |
| **[025 change-record-export](../025-change-record-export/spec.md)** | The per-block record on Kafka: state diff, retractions, finality watermark; export never blocks import | Schema now (must land before 018 F2 freezes); code after 018 F2 | 018 F1/F2 |
| **[026 follower-roles](../026-follower-roles/spec.md)** | `replica` and `archive`: bootstrap once, follow by records, verify every root, serve block-pinned reads | After 025's schema and 013 | 013, 018 F2, 025 |

### Where every item of the notes landed

| Notes | Landed as |
| --- | --- |
| DC-1 one process/chain/role · DC-2 node is the source · DC-3 single writer · DC-7 node does not know the fleet · DC-8 sim never shares the importer's process · DC-9 block-pinned reads · DC-10 consensus-neutral | 023 DC-1 · DC-2 · DC-3 · DC-4 · DC-5 · DC-6 · DC-7 |
| DC-4 a chain is data · DC-11 MESS off by default | 024 DC-1 · 024 DC-2 |
| DC-5 change-record contract | 025 DC-1..DC-5 |
| DC-6 followers verify | 026 DC-1 |
| GT-0 (closed) | 023 §Gates |
| GT-1 change-record format with 018 | 025 GT-1 |
| GT-2 (closed) | 023 §Gates |
| GT-3 replica bootstrap measurement · GT-4 archive basis | 026 GT-1 · 026 GT-2 |
| FR-001 registry | 024 FR-001..FR-005 |
| FR-002 `--chain`/`--role` · FR-007 sim caps · FR-009 health · FR-010 harness · FR-011 retirement · FR-013 fence | 023 FR-001 · FR-003 · FR-004 · FR-007 · FR-006 · FR-005 |
| FR-003 records · FR-004 Kafka · FR-008 export never blocks import | 025 FR-001 · FR-002/FR-003 · 025 DC-6 |
| FR-005 bootstrap/follow · FR-006 replica RPC · FR-012 archive serving | 026 FR-001/FR-002 · FR-004 · FR-005 |
| US-1 add a chain | 024 US-1 |
| US-2 replica bootstraps · US-5 historical state | 026 US-1 · US-2 |
| US-3 reorg propagates | 025 US-1 |
| US-4 sim burst · US-6 one chain fails · US-7 ingest replaced | 023 US-2 · US-3 · US-4 |
| SC-001 | 024 SC-001 |
| SC-002 · SC-006 · SC-008 | 026 SC-001 · SC-002 · SC-003 |
| SC-003 · SC-005 · SC-007 | 023 SC-002 · SC-003 · SC-001 |
| SC-004 | 025 SC-001 |
| OQ-6 multi-tenant admission | 023 OQ-1 |
| OQ-8 Kafka layout · OQ-10 export durability | 025 OQ-1 · OQ-2 |
| OQ-9 archive basis | 026 OQ-1 |
| Amendment requests to spec 018 (1–5) | 023 §Amendment requests (owned here; raised in 018 spec.md as a pending section) |
| T-001 inventory · T-002 removal · T-004 CLI · T-009 health/fence · T-010 harness · T-012 isolation tests | 023 T-001 (done) · T-002 · T-003 · T-005 · T-007 · T-008 |
| T-003 ChainSpec | 024 T-001..T-004 |
| T-005 schema · T-006 exporter · T-011 reorg fixtures | 025 T-001 · T-002/T-003 · T-005 |
| T-007 follower · T-008 archive | 026 T-002/T-003 · T-004/T-005 |

---

## Clarifications

### Session 2026-10-31

- Q: How do Orbita's lifecycle signals (drain, fence, unfence, stop) reach a process? → A: An **admin HTTP surface on
  its own localhost-bound port** (health plus signals), reusing the `/healthcheck` server code; `SIGTERM` maps to
  drain then stop.
- Q: ActorSystem name and default datadir once `--role` exists? → A: **`fukuii_<chainKey>`** for the ActorSystem;
  default datadir **`~/.fukuii/<chainKey>/<role>`**, so two roles for one chain never collide on a host. The
  single-node profile uses the `ingest` path; existing `~/.fukuii/<chainKey>` datadirs get a documented migration
  note (move or set `datadir` explicitly).
- Q: How does `sim` read state? → A: **Block-pinned RPC to a follower** in the first release; a co-located read-only
  store is a later optimisation gated on 018 amendment request 1 (safe multi-process reads).
- Q: Transaction ingress at fleet scale, and who owns per-client admission? → A: **Forward to `ingest`** (FR-010 as
  written) in the first release; a `relay` role is opened as spec 027 **only if** the harness shows `ingest`
  admission is the bottleneck; per-client quotas and priority are **Orbita's**, fukuii keeps the FR-003 caps.

## Origin: the reasoning that led here (kept so the premises can be challenged)

1. **Concurrency review.** fukuii runs three concurrency models in one JVM: Pekko Typed actors for long-lived state
   (peers, sync state machines, `BlockImporter`), cats-effect `IO` for bounded computation (execution, validation,
   RPC), and hand-sized dispatchers plus `java.util.concurrent` primitives on hot paths. `conf/base/pekko.conf` is the
   real concurrency map (separate pools for sync, Engine API, validation, account-trie flush, storage writer, healing
   reader/writer). Isolation is **configured, not structural**.
2. **Why both Pekko and `IO`.** Inherited from Mantis (Akka + Monix `Task`); Monix stalled on cats-effect 3, so `Task`
   became `IO`. The split is defensible (actors own identity and state; `IO` gives laziness, cancellation, `Resource`
   safety, explicit blocking). The cost is at the seams: 31 `IORuntime.global` sites (research §1), and every
   `unsafeToFuture`/`pipeTo` hop drops cancellation.
3. **Cloud serving shape.** Do not scale the node; scale the reads around it. Block import is single-writer by design
   (018 research F1). Tiers: an ingest node that exports block, receipt and **state-diff** records; state-serving
   replicas; an autoscaled simulation pool in **separate processes**; an analytics tier that never queries a node, with
   historical balances as derived tables; a method-routing gateway with block pinning.
4. **Spec 018 review.** 018 already lands on this topology (DC-16: the node is the source, not the query engine). Five
   amendments are requested below so 018's Phase 1 artifacts carry the fleet's needs.
5. **Vision check.** The per-node work (016/017/018/013) trends toward the cluster, but nothing on the roadmap builds
   the cluster itself, and the in-process multi-instance runtime (`runtime/`, 122 lines) is not isolated from
   JVM-global state (research §1).
6. **Decisions (2026-10-30, user).** Retire the in-process runtime; one process per chain; the Orbita name moves to the
   control plane as a separate project; keep consensus scope as it is; Kafka for change records; an archive-capable
   role; ETC finality only at genesis; MESS off by default.

## Decisions (2026-10-30, user)

| ID | Question | Decision | Where it binds |
| --- | --- | --- | --- |
| D-1 | Future of the in-process multi-instance runtime | **Retire it.** One process per chain. The name **Orbita** moves to the control plane. | 023 DC-1, FR-006, T-001/T-002; GT-0 closed |
| D-2 | Consensus scope for the first fleet release | **Keep what exists** (ETC PoW; ETH family over the Engine API). No new consensus families. | 023 Non-goals; GT-2 closed; 024 FR-003 enum |
| D-3 | Change-record transport | **Kafka.** | 025 FR-002 |
| D-4 | Control plane | **A separate project (Orbita).** fukuii's scope is the EL. | 023 scope boundary, DC-4, §Interface to Orbita |
| D-5 | Historical state | **An archive-capable role is sufficient.** | 026 `archive`, FR-005 |
| D-6 | ETC finality and MESS | **Only block 0 is final. MESS is off by default.** | 025 DC-5; 024 DC-2 |

---

## Problem

The target is a fleet that syncs and serves several EVM networks with one analytics surface across them. Today:

- **Every spec 001–022 is single-node.** None defines the roles a process can play, how a process declares itself to
  an orchestrator, or how one process's load is kept away from another's import.
- **The in-process multi-instance runtime is a dead end.** `runtime/FukuiiRuntime.scala` and `ChainInstance.scala`
  (122 lines) start several chains in one JVM, but the process shares a static metrics registry (Bug 29, documented in
  `metrics/Metrics.scala:95-105`), one heap, one set of unbounded mailboxes and 31 `IORuntime.global` sites. Instances
  share more than the design intends. Process-per-chain makes the problem disappear instead of requiring an unwind.
- **Health is P2P-shaped.** `GET /healthcheck` readiness means "DB open, peers > 0, tip advancing"
  (`NodeJsonRpcHealthChecker.scala:106`). A follower or a simulation process has no peers and would never be ready.
- **There is no fencing hook.** Nothing stops a second importer from writing a chain's canonical state.

### Evidence (research §1, re-measured 2026-10-30)

`IORuntime.global`: 31 sites in 24 files. Bounded mailboxes: one (`SignedTransactionsFilterActor`), all others
unbounded. `object`s with mutable state: 65 top-level (tight grep) to 136 files (loose grep). Global `Config`
singleton imported by 35 main files. None of these figures gates a decision now that D-1 is taken; the first two stay
relevant **inside** a process (DC-8).

---

## Goals

1. Define the **roles** a fukuii process can play and enforce **one chain, one role per process**.
2. **Retire the in-process multi-instance runtime** cleanly (inventory in research §2).
3. Define fukuii's **obligations to Orbita**: role, chain, head, finalized head, lag, readiness, and lifecycle signals
   including a fence, without making the node aware of the fleet.
4. Guarantee **isolation**: one role's or one chain's load cannot degrade another's import.
5. Provide the **test harness** in which 023–026's success criteria are measured.

## Non-goals

- **The control plane.** Placement, scheduling, failover orchestration, replica seeding, autoscaling, request
  routing and multi-tenant admission belong to Orbita.
- **New consensus families** (BSC Parlia, Polygon Bor, OP Stack deposits and L1 data fees, rollup derivation). Out of
  scope by D-2; each would be its own spec.
- The Chain Intelligence pipeline, its schemas and its correlation engine (it consumes 025's records).
- Cross-chain atomicity or consistency beyond per-chain block pinning.
- Any throughput or concurrency claim (018 FR-028's rule applies).
- Changing any consensus output.
- The in-process L2 / cross-instance read model of `docs/architecture/pluggable-consensus-vision.md`. Superseded by
  D-1; the pluggable-consensus idea itself is not retired, only its process model.

---

## Key entities

- **Chain**: one EVM network, identified by a fukuii-local `chainKey` (`etc`, `mordor`, `eth`, `sepolia`,
  `plataberget`, …) and its `chainId`; described by a ChainSpec entry (spec 024).
- **Role**: what a fukuii process does for one chain.
  - `ingest`: syncs, imports, talks to the CL (ETH family) or validates PoW (ETC); the **single writer**; produces
    change records (025). In the single-node operator profile it also serves RPC.
  - `replica`: holds recent state for one chain, follows `ingest` through change records, serves point reads within a
    retention window (026).
  - `archive`: like `replica`, but retains state for **every** block (026).
  - `sim`: executes `eth_call`, `eth_simulateV1` and `debug_trace*` against `replica` or `archive` state; holds no
    state of its own beyond caches.
- **Process**: one JVM, one chain, one role (DC-1).
- **Lifecycle signal**: start, drain, fence, unfence, stop (FR-005).
- **Orbita**: the separate control-plane project that runs fukuii processes as a fleet.

---

## Gates *(mandatory; nothing past a gate proceeds until it reports)*

- **GT-0: Global-state audit.** *Closed by D-1.* Replaced by T-001, the retirement inventory (done, research §2).
- **GT-1: Retirement inventory reviewed.** Before any `git rm` in T-002, the inventory's Delete / Rewire / KEEP verdicts
  are confirmed under `.claude/agent-protocols/dead-code-review.md` by the implementing thread and `prism`. Output: the
  list in research §2 with any corrections.
- **GT-2: Consensus scope.** *Closed by D-2.*
- **GT-3: Noise baseline.** Before SC-002 or SC-003 is judged, the harness (FR-007) records run-to-run variance of
  import p99 and read p99 on the two harness chains with nothing else running. Output: the noise band each isolation SC
  is compared against.

---

## Design constraints *(mandatory)*

**DC-1: One process, one chain, one role.** A fukuii process serves exactly one chain in exactly one role. The
in-process multi-instance runtime (`runtime/`, `conf/multi-instance.conf`, the `fukuii runtime` command, the
`instanceId` plumbing and the per-instance metrics registry) is **removed** (FR-006; research §2). A production fleet
runs each `(chainKey, role)` in its own process. One documented **single-node operator profile** remains: one process
running `ingest` for one chain with RPC serving enabled, which is exactly today's `fukuii <network>`.

**DC-2: The node is the source, not the query engine.** Inherited from 018 DC-16. Analytical and cross-chain queries
are answered by the indexer over 025's records, never by a node.

**DC-3: Single writer per chain.** Exactly one `ingest` process per chain writes canonical state. `replica` and
`archive` change state only by applying canonical change records or by re-executing canonical blocks (026 GT-1
decides which). A standby `ingest` writes only after Orbita has fenced the old one; fukuii provides the fence (FR-005),
Orbita decides when to use it.

**DC-4: The node does not know about the fleet.** Each process exposes its role, `chainKey`, `chainId`, head number
and hash, finalized number and hash, lag behind its source, and readiness (FR-004). It accepts lifecycle signals
(FR-005). It holds no list of other processes, no leader election, no service discovery. All decisions about when to
send a signal belong to Orbita.

**DC-5: Simulation never shares the importer's process.** `sim` work runs in `sim` processes over `replica` or
`archive` state, read through **block-pinned RPC to a follower** in the first release. Opening a follower's store
read-only from a co-located `sim` is a later optimisation, allowed only if 018 GT-1 (amendment request 1) shows the
engine supports safe multi-process reads. In the single-node operator profile, 018 Track R's bounded executors (FR-027)
are the isolation mechanism.

**DC-6: Block-pinned reads.** Every read RPC a follower or `sim` serves is answered at an explicit block, and every
process reports its head, so a router can pin a session to a height and never mix states from two heights. Routing
itself is Orbita's. (026 DC-6 states the follower side.)

**DC-7: Consensus-neutral.** Nothing in specs 023–026 changes a block, receipt, state root or fork-choice output.
Any slice that touches `mpt/`, `ledger/`, `vm/` or `consensus/` routes through `forge` (ETC) and `beacon` (ETH).

**DC-8: Isolation inside a process is bounded resources, not hope.** Within one process, the role's heavy work
(simulation, trace, range scans, export) runs on bounded executors and bounded queues that share no pool and no lock
with block import (018 FR-027 / DC-15), and rejects with an explicit error when full. The process boundary (DC-1)
turns a fleet-wide failure into a per-chain one; DC-8 turns a per-chain failure into a rejected request.

**DC-9: Role determines what starts.** A process starts only the subsystems its role needs (FR-008). `replica`,
`archive` and `sim` open no P2P listener, no discovery, no Engine API server and no miner. A method a role does not
serve returns a defined "not served by this role" error, never a wrong answer.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — An operator runs today's single node unchanged (Priority: P1)

An operator who does not run a fleet starts `fukuii sepolia` or `fukuii etc` and gets what they get today: one
process, `ingest` role, RPC on, P2P on.

**Why this priority**: DC-1 must not cost existing operators anything.

**Independent Test**: SC-001; every existing `conf/*.conf` profile starts and the essential test tier passes after
T-002.

**Acceptance Scenarios**:
1. **Given** a datadir synced with 0.10.17, **When** the node after T-002 starts with `fukuii <network>`, **Then** it
   opens the datadir, syncs and serves RPC with no config change.
2. **Given** `fukuii runtime` or a config containing `fukuii-runtime.instances`, **When** started, **Then** the process
   exits with a named error that points to the one-process-per-chain profile.

### User Story 2 — A simulation burst does not move import latency (Priority: P1)

**Independent Test**: SC-002.

**Acceptance Scenarios**:
1. **Given** `sim` processes saturated against a `replica`, **When** blocks import on `ingest`, **Then** `ingest` p99
   import time stays within the GT-3 noise band of its unloaded figure, and `sim` rejects excess work with an explicit
   error rather than queueing without bound.

### User Story 3 — One chain fails, the others do not notice (Priority: P1)

**Independent Test**: SC-003.

**Acceptance Scenarios**:
1. **Given** processes serving two chains on shared hosts, **When** every process of one chain is killed or driven out
   of heap, **Then** the other chain's import and read latencies do not change beyond the GT-3 noise band.

### User Story 4 — The ingest node is replaced (Priority: P2)

**Independent Test**: SC-004.

**Acceptance Scenarios**:
1. **Given** a standby `ingest` with a recent seed, **When** Orbita fences the primary through FR-005 and starts the
   standby, **Then** no two processes write the chain's canonical state at once, the fenced process refuses to resume
   without an explicit unfence, and change records resume without gaps (idempotent duplicates allowed; 025 DC-3).
2. **Given** a fenced `ingest`, **When** it is restarted without an unfence, **Then** it comes up fenced (the fence is
   durable in its own store).

### User Story 5 — Orbita reads one process like any other (Priority: P1)

**Independent Test**: SC-005.

**Acceptance Scenarios**:
1. **Given** any role, **When** Orbita asks the process's health endpoint, **Then** it gets role, `chainKey`, `chainId`,
   head, finalized head, source lag and readiness in one documented shape, and readiness is defined per role (a
   `replica` with no peers is ready when it is at its source's head within a bound).

### Edge cases

- `--role sim` with a `--chain` whose ChainSpec names a consensus type the build lacks: rejected at load (024 DC-1),
  before any network or datadir is touched.
- A `drain` signal while a block is mid-import: the block finishes; no new import starts; readiness turns false;
  in-flight RPC completes within the drain timeout.
- `fence` arrives while export is behind: the export position is persisted at the last committed block (025 FR-004);
  nothing after it is exported by this process.
- Two processes started with the same `(chainKey, role=ingest)` and the same datadir: the second fails on the datadir
  lock as today; with different datadirs, both run and both write — fukuii cannot detect this (DC-4). The fence is
  Orbita's tool; the spec says so in the runbook.
- A `sim` process asked for a method that needs state it does not hold (`eth_getProof` at a block its follower has
  pruned): the follower's "outside retention window" error (026 DC-3) is passed through unchanged.

---

## Requirements *(mandatory)*

### Functional requirements

- **FR-001** fukuii MUST accept `--chain <chainKey>` and `--role <ingest|replica|archive|sim>` and start exactly one
  chain in one role per process. `fukuii <network>` (today's CLI) MUST remain valid and MUST mean
  `--chain <network> --role ingest` with RPC serving enabled (the single-node operator profile). The role and
  `chainKey` MUST be available to every component through `InstanceConfig`, not through a new global. The
  ActorSystem is named `fukuii_<chainKey>`; the default datadir is `~/.fukuii/<chainKey>/<role>` (single-node profile:
  the `ingest` path), and the operator docs carry the migration note for datadirs at today's `~/.fukuii/<chainKey>`.
- **FR-002** Role defaults MUST be documented per role: which servers listen, which RPC namespaces are served, which
  sync mode runs (017 `SyncMode`, with `Follow` for followers), what readiness means, and how transactions are admitted
  (FR-010) (table in `quickstart.md`, produced by `/speckit-plan`). ETC PoW mining is a single-node-profile option on
  `ingest` only; no fleet role mines.
- **FR-003** `sim` MUST run every request on bounded executors with per-method concurrency caps, gas caps and
  timeouts, MUST cancel work whose client has gone, and MUST reject with an explicit error when full. It MUST reuse
  018 FR-027's executor mechanism, not add a second one.
- **FR-004** Every process MUST expose, in one documented shape over HTTP: `role`, `chainKey`, `chainId`, `head`
  (number, hash), `finalized` (number, hash), `sourceLag` (blocks behind its source: peers for `ingest`, the record
  stream for followers, the follower for `sim`), `ready` (boolean) and `fenced` (boolean), plus the same as metrics.
  Readiness MUST be defined per role (FR-002) and MUST NOT require P2P peers for roles that have none (DC-9).
- **FR-005** `ingest` MUST implement lifecycle signals `drain`, `fence`, `unfence` and `stop`. On `fence` it stops
  importing and exporting, stops accepting transactions (FR-010), **closes the Engine API listener** on ETH-family
  chains so the consensus client's reconnect fails loudly and Orbita can repoint it, persists its export position,
  records the fenced state **durably in its own store**, and reports `fenced = true`; it MUST refuse to import or
  export after a restart until an explicit `unfence`. `drain` stops accepting new work and turns readiness off while
  in-flight work completes within a configured timeout. Followers and `sim` implement `drain` and `stop`.
- **FR-006** The in-process multi-instance runtime MUST be removed per research §2: `runtime/`,
  `conf/multi-instance.conf`, the `fukuii runtime` command, the `instanceId` constructor argument, the per-instance
  metrics registry (`Metrics.instances`, `forInstance`, `closeInstance`, the Bug 29 WARN) and
  `MetricsMultiInstanceSpec`. `InstanceConfig`, `InstanceConfigProvider` and the test providers stay.
- **FR-007** The repository MUST provide a **fleet test harness** (containers): two chains (a Mordor-class ETC devnet
  and a Platåberget-class ETH devnet, or the fixtures 024 SC-001 uses), each role at least once, a Kafka broker, and
  a load generator. It is used by 023 SC-002..SC-005, 025 SC-001..SC-003 and 026 SC-001..SC-004. It is a test fixture,
  not a production deployment; production orchestration is Orbita's.
- **FR-008** Role MUST gate subsystem start (DC-9): `replica`, `archive` and `sim` MUST NOT bind P2P, discovery,
  Engine API or miner ports; methods a role does not serve MUST return a defined error code.
- **FR-009** Lifecycle signals (FR-005) and the health shape (FR-004) MUST be served by an **admin HTTP surface on a
  dedicated port bound to localhost by default**, separate from the public JSON-RPC port and reusing the
  `/healthcheck` server code; `SIGTERM` MUST map to `drain` then `stop`. The admin port MUST never serve chain RPC.
- **FR-010** **Transaction ingress.** `eth_sendRawTransaction` (and `eth_sendTransaction` where enabled) is admitted
  only by the chain's `ingest` txpool. `replica`, `archive` and `sim` MUST forward raw transactions to the `ingest`
  admission endpoint configured for the chain, never to peers, with a per-process rate cap and the ingest's admission
  result returned to the caller unchanged; with no endpoint configured they MUST return the DC-9 role error. A fenced
  `ingest` MUST refuse forwarded transactions (FR-005). Whether a dedicated `relay` role is needed at scale is OQ-5.

### Success criteria

- **SC-001** After FR-006, `grep -rn "fukuii-runtime\|FukuiiRuntime\|ChainInstance\|instanceId" src/ ops/ docs/`
  returns only the KEEP rows of research §2; every `conf/*.conf` profile starts; `testEssential` is at baseline
  (3,621 / 0 at 0.10.17, re-run before and after).
- **SC-002** With `sim` saturated, `ingest` p99 import time is within the GT-3 noise band of its unloaded figure, and
  rejected `sim` requests carry the documented error.
- **SC-003** Killing all processes of one chain changes the other chain's import p99 and read p99 by no more than the
  GT-3 noise band.
- **SC-004** Under the US-4 fixture, the fenced process writes nothing after the fence, stays fenced across a restart,
  and the record stream has no gap (025 SC-003's checker).
- **SC-005** Every role answers FR-004 in the same shape; a follower reports ready only at head within its bound;
  readiness never depends on peers for a role with none.
- **SC-006** Under the harness's transaction load forwarded from two followers and one `sim`, `ingest` admission
  latency p99 and import p99 are recorded; the figures decide whether a `relay` role (spec 027) is opened.

---

## Open questions

- **OQ-1** *(resolved 2026-10-31)*: per-client quotas, priority and gas-budget admission are **Orbita's**; fukuii's
  part is the FR-003 caps.
- **OQ-2** *(resolved 2026-10-31)*: admin HTTP on a dedicated localhost port; `SIGTERM` = drain then stop (FR-009).
- **OQ-3** *(resolved 2026-10-31)*: `fukuii_<chainKey>`; datadir `~/.fukuii/<chainKey>/<role>` (FR-001).
- **OQ-4** *(resolved 2026-10-31)*: RPC to a follower first; read-only store later, gated on 018 request 1 (DC-5).
- **OQ-5** *(resolved 2026-10-31)*: forward to `ingest` (FR-010); a `relay` role becomes spec 027 only if the harness
  shows `ingest` admission is the bottleneck (SC-006 measures it).

---

## Interface to Orbita (informative; implemented in the Orbita project)

fukuii provides, Orbita uses:
- One process per `(chainKey, role)`, started with FR-001 flags and a ChainSpec (024).
- Health, role, head, finalized head, lag, readiness and fenced state (FR-004).
- Lifecycle signals: drain, fence, unfence, stop (FR-005).
- Change records and finality watermarks on Kafka (025) for followers and the indexer.
- Snapshot artifacts (013; 026 FR-001) for seeding followers.
- Block-pinned reads and head reporting (DC-6), which make height-pinned routing possible.

Orbita owns: placement and scheduling, failover and fencing decisions, follower seeding and scaling, the RPC gateway
(routing by method class and block pinning), multi-tenant admission (OQ-1), and fleet-wide observability.

---

## Amendment requests to spec 018

Raised here because 018's Phase 0 and Phase 1 decisions lock them in; recorded in 018's `spec.md` as a pending section
for `vault` and the user to accept or reject. 025 and 026 cite the ones they depend on.

1. **GT-1 bake-off: add a read-fleet profile.** N reader processes plus one writer, concurrency 8–64, on a larger
   cloud host class; record whether the engine supports safe **multi-process read-only access** (libmdbx and LMDB do;
   RocksDB's secondary instance is weaker). 018 DC-5 reader discipline must hold across processes. Decides 023 OQ-4.
2. **F2 changeset format: store the post-value as well as the previous value**, with `chainKey` and a schema version
   from the first byte. One record then serves rollback, follower and archive replay, export and historical-balance
   derivation (025 GT-1, DC-9).
3. **H3: the node does not write ClickHouse.** The `ColdStore` write side publishes to Kafka (025 FR-002); the indexer
   owns all analytics tables. H4's `eth_getLogs` routing reads the indexer's tables through the `ColdStore` read
   adapter. Removes node egress to an analytics database and avoids two writers for one schema (025 DC-7).
4. **T-010 close-fence: schedule early.** Merge-able on RocksDB, independent of the engine choice; removes the `dbLock`
   read stall that limits concurrent reads today (018 research §16). Followers and `sim` need it.
5. **Pruning must support an archive mode.** 018's pruning decisions (its OQ-3, OQ-5) must leave a mode that retains
   every changeset, so the `archive` role (026 FR-005) does not need a second storage layout (026 GT-2).
6. **FR-018: drop `multi-instance.conf` from the runbook list.** Retired by 023 FR-006.

---

## Related specs

- **013 State snapshots**: follower seeding (026 FR-001).
- **016 / 017**: the `StateIngest` / `StateAccess` gateways under which records are produced.
- **018 Storage engine consolidation**: changesets, flat state, Track R executors, DC-16; six amendment requests above.
- **019 zkVM guest**: unaffected.
- **024, 025, 026**: companions (table above). **[alignment-review.md](./alignment-review.md)** (2026-10-30): every
  spec after 016 read against this end state, with the pending amendments on 013, 017 and 018 and the sequenced plan.
- **Chain Intelligence** (separate service, Scala 3 on Kafka/Flink): the primary consumer of 025 records.
- **Orbita** (separate project): the control plane.

## Known tasks (seed for `/speckit-tasks`)

- **T-001** Retirement inventory — **DONE** (research §2). GT-1 reviews it.
- **T-002** Remove the runtime per research §2 (FR-006); SC-001. One PR; `prism` reviews; `eye` runs `testEssential`
  before and after. Includes the `pluggable-consensus-vision.md` banner and the ops compose replacements.
- **T-003** `--chain` / `--role` CLI and `InstanceConfig` fields (FR-001); the single-node profile mapping; OQ-3.
- **T-004** Role-gated subsystem start (FR-008, DC-9) and the "not served by this role" error; role table (FR-002).
- **T-005** Health shape (FR-004) on the existing `/healthcheck` server; per-role readiness; metrics twins. `conduit`
  owns.
- **T-006** Lifecycle signals and the durable fence (FR-005, FR-009); OQ-2. `vault` reviews the durable marker.
- **T-007** `sim` role on 018 FR-027 executors (FR-003); blocked on 018 Track R slice R1.
- **T-008** Fleet test harness (FR-007); GT-3 noise baseline.
- **T-009** Isolation tests: `sim` saturation (SC-002), chain kill (SC-003), fence (SC-004).
- **T-010** Docs: operator profile, role table, runbook for fencing; CLAUDE.md "ETC vs ETH" section unchanged.

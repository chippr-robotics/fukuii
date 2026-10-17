# Feature Specification: zkVM Guest Program (Stateless Validation on RISC-V)

**Feature Branch**: `019-zkvm-guest-program` (stub written on `docs/spec-017-roadmap-018-stub` as spec 018; renumbered to 019 on
2026-10-17 when spec 018, storage engine consolidation, was inserted before it)

**Created**: 2026-10-11

**Status**: **Draft (stub, revised 2026-10-17).** Not yet started. Gated by a two-track Phase 0 feasibility spike (SP-1)
and by a constitution amendment (CG-1). To be fleshed out with `/speckit-specify` and `/speckit-clarify` once the spike
has reported, then `/speckit-plan` and `/speckit-tasks`. The Gates and Design constraints sections are binding on that
later work. **Revised 2026-10-17**: renumbered from 018 to 019; spec 018 (storage engine consolidation) now precedes it;
the Approach section weighs a second route, a Scala 3 macro/TASTy compiler for a strictly bounded subset (Approach B),
against Scala Native (Approach A), and SP-1 runs both tracks; DC-7 fixes the host/guest data-tier boundary.

**Roadmap facts are unverified.** The EF L1-zkEVM roadmap's asks, Ere and the phase numbering below were read on
2026-10-11 from secondary sources and are to be re-verified at planning time; treat them as a working hypothesis.

**Depends on**: spec 017's `StatelessExecutor` (G-2) existing and passing its differential test on the JVM, and on
spec 017's DC-15 (narrow dependency boundary for the stateless core) and DC-11 (execution-specs witness format); and on
spec 018's DC-8 (the stateless closure gains no storage-engine dependency) and SC-007 (witnesses under the flat read
path are byte-identical to 017's), so that the host's data tier is settled before the guest is built against it.

**Sequence**: 016 → 017 → 018 → **019** (user decision, 2026-10-17). SP-1 may start earlier, off the node host, with the
user's explicit approval (FR-001).

**Input**: User request, 2026-10-11: "create spec-18 stub in the code base for the level 2 work." Level 2 is the second
of the two EL projects in the Ethereum Foundation's L1-zkEVM roadmap: an EL-owned **guest program** that validates a
block statelessly inside a zkVM. Level 1, witness production, is spec 017.

## Problem

Ethereum L1 is moving toward accepting zkEVM proofs as an alternative to re-execution. The pipeline is: stateful EL →
`ExecutionWitness` → **stateless guest program** → zkVM → prover → CL verifier. The roadmap expects each EL client to
supply its own guest program, so that proofs carry the same implementation diversity re-execution does today. CL
clients and block builders download the guest ELF from the EL's release page.

Today fukuii can do neither part. Spec 017 adds witnesses and a JVM `StatelessExecutor`, but a JVM program cannot run
in a zkVM. Without a guest, fukuii has no implementation in the proving pipeline at all. The existing guests come from
Rust, Go and C++ clients. A guest built from fukuii's Scala EVM would be the first from another language family,
which is the strongest form of the client-diversity claim at the layer Ethereum is moving consensus toward.

## What the roadmap asks of an EL guest

From the EF EL rollout milestones (HackMD, read 2026-10-11; re-verify at planning time):

| Phase | Deliverable |
| --- | --- |
| 0 | An empty program compiled for the standardized bare-metal target (RV64IM, no OS), built through a Dockerfile so it plugs into Ere, producing an ELF |
| 1 | A stateless validation program: input is a block and an `ExecutionWitness`; validates the state transition using MPT witnesses; tested against execution-specs tests. The API is blocked on execution-specs issue #1590 |
| 2 | The phase 1 program built for the target; the ELF runs on qemu-virt. Standard-library dependencies may break on bare metal |
| 3 | zkVM I/O: stdout first, then input through the zkVM's mechanism instead of stdin; tested on the zkVM's emulator |
| 4 | zkVM precompiles used as software accelerators, from the standard `zkvm_accelerators.h` C header |
| 5 | Distribution: reproducible builds (Dockerfiles for Ere), one ELF per zkVM, named `<EL_NAME>-<EL_VERSION>-<ZKVM_NAME>-<ZKVM_SDK_VERSION>`, signed with minisign, published on the release page |

## Approach

Two routes from Scala to a bare-metal RISC-V ELF are plausible. The JVM build of `StatelessExecutor` (spec 017) stays
the reference under either; the guest is the same Scala source compiled or translated for the zkVM target and checked
against the JVM build on the same inputs. SP-1 runs both and reports a decision matrix; the plan commits to one (or to
A-now-B-later) only after that report.

### Approach A — Scala Native (whole-runtime cross-compilation)

Scala Native compiles Scala through LLVM, with its own runtime: a GC, exception handling and a libc-shaped standard
library. The guest would be the stateless closure cross-built with `sbt-scala-native`.

Why it may fit:

- **GC.** One block's stateless validation has bounded memory, so a no-op GC (allocate, never free) may be enough
  within a zkVM's memory limit.
- **Accelerators.** The zkVM accelerators are exposed through a standard C header. Scala Native's C interop
  (`@extern`) binds C headers directly, so keccak, secp256k1, BN254 and the other precompiles map onto the zkVM's
  accelerated versions.
- **Single-threaded.** The guest needs no threads, no I/O beyond the zkVM's input/output, and no OS.

The open risks:

- **Runtime on bare metal.** Scala Native targets POSIX and ANSI C APIs. As of 2026-10 the project's CI has a riscv64
  *cross-compilation* job (Linux userland), and no evidence was found of a freestanding (no-libc, no-OS) mode or of an
  RV64IM bare-metal target. Exception handling (unwinding) and the allocator assume a libc. This is what SP-1a tests.
- **Dependency closure.** The stateless closure uses Pekko `ByteString` throughout `vm`, `mpt`, `domain`, `rlp`, `bytes`
  and `crypto`, and BouncyCastle for hex and crypto. Neither is available on Scala Native. Spec 017's DC-15 inventories
  them; this spec replaces them behind small interfaces (a bytes abstraction, a crypto interface whose JVM
  implementation stays BouncyCastle).
- **`BigInt`.** `UInt256` is implemented over `scala.math.BigInt`, which wraps `java.math.BigInteger`; `OpCode.scala`
  alone names `BigInt` 158 times. Scala Native ships a `BigInteger` port, but its cycle cost in a zkVM is unknown and
  likely dominant.
- **Performance in cycles.** Prover cost is RISC-V cycles, not wall time. A GC runtime, a `BigInteger` port and
  exception tables all cost cycles. The roadmap's benchmarking workstream relates opcode use to cycle counts, so this is
  measured, not guessed.
- **Heavy runtime in the guest.** The user's design goal for the host/guest split (spec 018) is a guest free of heavy
  runtime footprints. Scala Native's runtime is small next to the JVM but is still a GC and a libc; whether that is
  acceptable is a measured question (SC-006), not a stated one.

### Approach B — A Scala 3 macro/TASTy compiler for a strictly bounded subset

Instead of cross-compiling a general runtime, build a **small compiler for a small language**: the stateless closure is
written in (or constrained to) a bounded subset of Scala 3, and that subset is translated from the compiler's typed
trees into C (or LLVM IR) for RV64IM with no GC, no exceptions and no runtime beyond an arena allocator and the
accelerator calls. Scala 3 exposes the typed AST in two stable ways, and the choice between them is itself an SP-1
question:

- **B1, inline macros.** `inline def` entry points plus `scala.quoted` macros see the typed `Expr`/`Tree` of their
  arguments at compile time. A macro can walk the tree of the whole stateless entry point (through the functions it
  inlines) and emit C as a side artifact. Pro: no compiler plugin, ordinary sbt build. Con: everything reachable must be
  `inline`, which is intrusive on shared consensus code, and macro expansion of a 9,000-line interpreter stresses
  `-Xmax-inlines`.
- **B2, TASTy.** Every Scala 3 compilation unit's typed tree is pickled into TASTy, which is stable within the 3.3 LTS
  line and readable offline with `tasty-query` (or a compiler plugin phase after `pickler`). A separate tool reads the
  stateless closure's TASTy, **rejects anything outside the subset** (a lint that runs on the JVM build in CI, in the
  repo's ratchet style), lowers the accepted trees to a small IR, and emits C. Pro: shared code is untouched apart from
  staying inside the subset; the subset checker is a ratchet, like the 016/017 dependency checks; the output is
  reviewable C. Con: it is a compiler, and monomorphization of generics (`MerklePatriciaTrie[K, V]`, type classes,
  `given` resolution) must be implemented.

Precedent: **Scalus** compiles a restricted subset of Scala 3 to Cardano's Plutus Core through a compiler plugin and an
intermediate representation (SIR); it shows that "a bounded Scala 3 subset to a foreign target, with the subset enforced
by the compiler" is a working pattern, not a research project. Scala.js is the full-language version of the same idea.
Re-verify Scalus's mechanism (plugin vs. macro) at SP-1.

What the subset must exclude and what the closure needs instead:

| Excluded | Why | Replacement in the closure |
| --- | --- | --- |
| Exceptions across the guest boundary (ideally anywhere) | no unwinder | `Either` everywhere; `MissingRootNodeException` (017 FR-005) becomes a `Left` |
| `scala.math.BigInt` / `java.math.BigInteger` | JVM runtime, variable-size heap objects, cycle cost | a fixed-width `UInt256` over four `Long`s (or 8×`Int`), implemented once, byte-identical to the current one (consensus-change protocol; `beacon`, `forge`) |
| Reflection, `ClassTag`, structural types, `lazy val` with thread-safety, `var` captured by closures | no runtime support | none needed in the stateless closure; the checker proves it |
| Unbounded recursion without a stack bound | guest stack is fixed | the EVM's call depth is bounded (1024); MPT depth is bounded (64 nibbles + storage); the checker requires `@tailrec` or a documented bound |
| Collections beyond `Array`, `ArraySeq`, `List`, a bounded `Map` | runtime size | the closure already prefers these; `mutable.Map` in `vm`/`mpt` gets a bounded replacement |
| Pekko `ByteString`, BouncyCastle, json4s (the tracer imports in `vm/`) | JVM-only | 017 DC-15 inventory; the bytes abstraction; tracers excluded from the guest entry point |

Why B may fit the user's goal better than A: no GC and no libc in the guest at all (the "clean sandbox" property is by
construction, not by configuration); cycle cost is what the emitted C costs and nothing else; the subset checker gives
the repo a permanent, grep-verifiable guarantee that the consensus core stays portable. Why B is riskier: it is a
compiler to build and maintain, the `UInt256` rewrite touches consensus code on both chains, and the first version will
cover exactly the closure and nothing more.

### What is common to both

- The crypto goes to the zkVM accelerators through `zkvm_accelerators.h` on both routes (G-3); only the pure-Scala part
  (EVM interpreter, MPT verification, RLP, the witness model) is compiled by A or translated by B.
- The witness is the only input. The guest opens no database and has no storage engine, on either route (spec 018 DC-8
  guarantees the closure stays that way; DC-7 below states it from this side).
- The JVM `StatelessExecutor` is the oracle (DC-2) on both routes.

## Gates *(mandatory; nothing past them proceeds until they pass)*

**SP-1 — Phase 0 feasibility spike, two tracks.** Both tracks produce an RV64IM ELF that runs on qemu-virt and, where a
zkVM emulator is available through Ere, a cycle count for the same program. The spike takes days, not weeks, per track.

- **SP-1a (Approach A).** Compile an empty Scala Native program for RV64IM with no OS and run it on qemu-virt. Then repeat
  with a program that allocates, throws and catches an exception, and calls one C function. Record which Scala Native
  version and target triple were used, and whether a freestanding mode exists or a libc shim had to be written.
- **SP-1b (Approach B).** With `tasty-query` (B2) or an inline macro (B1), translate a fixed sample from the stateless
  closure to C and build it for RV64IM: the `rlp` encoder/decoder for a block header, Keccak-256 through a C call
  standing in for the accelerator, and one EVM opcode loop (`PUSH`/`ADD`/`MSTORE`/`SSTORE` over an in-memory world)
  with a fixed-width 256-bit arithmetic stub. Record: the subset the sample needed, every construct the translator
  rejected, the size of the translator, and the cycle count next to SP-1a's for the same program where both exist.
- **Decision matrix.** The report compares the two tracks on: runs on bare metal; cycles per unit of work; size of the
  change to shared consensus code (A: bytes + crypto abstractions; B: the same plus `UInt256` and the subset); build
  reproducibility; maintenance surface (a toolchain dependency vs. a compiler we own); and how each honours DC-7.

**It runs off the node host.** The Scala Native cross-build plus qemu runs on a CI runner or a separate machine, never
on the soak/node host: builds there swap the 16 GB host and break live sync.

**Sequencing and approval.** The sequence is spec 016, then 017, then 018, then this spec. The spike starts before
specs 016, 017 and 018 complete only with the user's explicit approval. It runs off the node host (below).

It reports one of three outcomes to the user:

- **Go (A)**: SP-1a runs all three programs and its cycle cost is within reach of the other ELs' guests. The plan takes
  Approach A, and the subset discipline of Approach B is adopted anyway as a ratchet on the closure (it costs nothing and
  keeps B open).
- **Go (B)**: SP-1a fails on bare metal or its cycle cost is prohibitive, and SP-1b translates the sample. The plan takes
  Approach B, scoped to the closure, with the `UInt256` rewrite as the first consensus-protocol task.
- **Go with constraints**: one track runs but something does not (typically exceptions under A, or a construct the
  translator cannot yet handle under B). The plan adopts the restriction as a design constraint (no exceptions across
  the guest boundary, `Either` everywhere; or the construct is rewritten in the closure).
- **No-go**: neither track produces a bare-metal ELF that validates the sample. Fukuii stays a level 1 participant
  (spec 017), and this spec is closed with the spike's findings recorded.

**CG-1 — Constitution amendment.** The constitution currently says "Scala 3.x LTS on JDK 25 (OpenJDK) ... No Scala 2
and no cross-build", and requires new dependencies to be justified. Under Approach A, a Scala Native build of the
stateless core is a cross-build and a new toolchain. Under Approach B, the translator is a new build tool that reads
TASTy (or a macro that emits a side artifact) and a C toolchain for RV64IM. Either way, before any guest build
configuration merges, the constitution is amended through `/speckit-constitution`, with an ADR under `docs/adr/`, to
allow exactly one scoped exception: the stateless core modules may be built for the zkVM guest target by the route SP-1
chose. The node itself stays JVM-only. The spike (SP-1) may run on a throwaway branch before the amendment, because it
merges nothing. Spec 018's GT-2 amends the same constitution section for the storage engine; the two amendments are
coordinated so the "Technology & Architecture Constraints" section is rewritten once.

## Goals

- G-1 A build of spec 017's `StatelessExecutor` that produces a bare-metal RV64IM ELF, by Approach A or B as SP-1
  decides.
- G-1a (Approach B, or A with the ratchet) A subset checker over the stateless closure's TASTy that fails CI on any
  construct outside the guest subset.
- G-2 The guest validates a block from its `ExecutionWitness` and agrees with the JVM `StatelessExecutor` on every
  input in the test set: same post-state root, same accept or reject decision.
- G-3 The guest uses zkVM accelerators through `zkvm_accelerators.h` for the cryptographic precompiles and hashing.
- G-4 Reproducible, signed, per-zkVM ELFs published on fukuii's release page under the roadmap's naming scheme, with
  Ere-compatible Dockerfiles.
- G-5 Fukuii listed among the EL guest providers in the roadmap's tooling (Ere, Ethproofs, zkevm.ethereum.org).

## Non-goals

- **NG-1 Running provers or proving infrastructure.** Fukuii ships the guest; provers run it.
- **NG-2 CL-side proof verification.** That is consensus-specs and CL client work.
- **NG-3 A proof-verifying validation mode in the node.** That is a later spec (spec 017 NG-8).
- **NG-4 Porting the node to Scala Native.** Only the stateless core is cross-built. Sync, networking, storage, RPC
  and actors stay JVM-only.
- **NG-5 ETC-specific guests in the first release.** The roadmap is ETH-only. An ETC guest (for proof-backed sync and
  bridges) is a later decision; the chain spec makes it a configuration change, not a new program, if it happens.
- **NG-6 A second EVM.** The guest is fukuii's existing EVM compiled or translated for another target, not a rewrite.
  Under Approach B the translator consumes the same source; a hand-maintained C port is excluded.
- **NG-7 A storage engine in the guest.** The guest reads a witness and nothing else (DC-7). Spec 018's host data
  tiers (the hot-tier engine, flat state, changesets, the cold tier) are invisible to it.
- **NG-8 A general-purpose Scala-to-C compiler.** Approach B's translator covers the stateless closure's subset and is
  allowed to reject everything else.

## Design constraints *(mandatory)*

**DC-1 — One EVM, two targets.** The guest and the node run the same Scala source for execution, state transition and
witness verification. Target-specific code is limited to the bytes abstraction, the crypto implementations (JVM:
BouncyCastle; guest: zkVM accelerators), the 256-bit arithmetic implementation if Approach B replaces `BigInt`, and I/O.
A divergence between the JVM and guest builds on any input is a P0 bug.

**DC-2 — Differential agreement is the acceptance test.** Every guest build runs the same input set as the JVM
`StatelessExecutor` (spec 017's differential set plus execution-specs fixtures) on the zkVM emulator, and must agree
on every post-state root and every rejection.

**DC-3 — Fail closed.** Any guest error, including a missing witness node, an out-of-memory condition or an
unsupported operation, produces an explicit rejection, never a silent acceptance and never a wrong root.

**DC-4 — Reproducible and signed.** ELF builds are bit-reproducible from a tagged commit through a pinned Docker
image, and every published ELF is minisign-signed. Signing keys are handled under the guarded-write protocol.

**DC-5 — Consensus determinism holds on both targets.** Constitution principle I applies to the guest exactly as to
the node. Any change to shared consensus code for this spec follows the consensus-change protocol (`beacon` for ETH),
and ETC execution on the JVM stays byte-for-byte unchanged (`forge` signs off).

**DC-6 — The JVM node pays nothing.** Introducing the bytes and crypto abstractions (and, under Approach B, the
fixed-width `UInt256`) must keep the node within the constitution's ~10% performance budget on the `[IMPORT-TIMING]`
baseline, and must not change any consensus output. `ByteString` replacement is measured, not assumed free; a
fixed-width `UInt256` is expected to be faster on the JVM too, and that is measured as well.

**DC-7 — The witness is the only thing that crosses the host/guest boundary.** The host (the node, with spec 018's data
tier) produces the `ExecutionWitness`; the guest consumes it over an in-memory `WitnessMptStorage` (017 FR-005) and has
no database, no file system and no storage engine of any kind. No type from `db/`, from spec 018's flat stores,
changesets, segment store or cold tier, or from `java.lang.foreign` may appear in the stateless closure; spec 018 DC-8
enforces this from the host side, and the guest build (A) or the subset checker (B) enforces it from this side. The guest's memory is the witness
plus the block plus bounded working state, and OQ-4's ceiling is stated in those terms.

**DC-8 — The subset is a ratchet, whichever route wins.** Even under Approach A, the stateless closure is held to the
Approach B subset by the checker (G-1a), because every construct it excludes (exceptions, `BigInt`, reflection,
unbounded collections) is also a cycle cost or a bare-metal risk under Scala Native. The checker lands before any guest
build configuration and only tightens, in the style of `warning-ratchet.md`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — A zkVM team runs fukuii's guest (Priority: P1)

A zkVM team, through Ere, pulls fukuii's signed ELF for their zkVM, feeds it a block and its witness, and gets the
same result as the other ELs' guests.

**Independent Test**: run the published ELF on the zkVM emulator over the differential set; compare with the JVM
`StatelessExecutor` and with another EL's guest.

**Acceptance Scenarios**:

1. **Given** a valid block and complete witness, **When** the guest runs, **Then** it accepts and outputs the
   header's post-state root.
2. **Given** a witness with one node removed, **When** the guest runs, **Then** it rejects explicitly.

### User Story 2 — The spike decides the path (Priority: P1)

A maintainer runs SP-1 and reports go, go-with-constraints or no-go before any other 018 work starts.

**Independent Test**: the SP-1 programs on qemu-virt.

### User Story 3 — A release ships guest ELFs (Priority: P2)

A fukuii release includes reproducible, signed guest ELFs per supported zkVM, and an independent rebuild from the tag
reproduces them bit for bit.

### Edge Cases

- A block that exhausts the guest's memory under the no-op GC (DC-3).
- An exception path in shared code that the guest subset forbids (SP-1, go-with-constraints); under B, the translator
  rejects it and the checker names the site.
- A `BigInt` operation whose fixed-width replacement differs on an overflow edge (FR-003b): ethereum/tests and the
  differential set must catch it.
- A generic or `given`-resolved call the translator cannot monomorphize (B): rewritten in the closure or the construct
  is excluded from the subset.
- A precompile with no zkVM accelerator: the software path must still be correct.
- Different zkVMs with different memory layouts and precompile conventions (one ELF each).

## Requirements *(mandatory)*

### Functional Requirements (stub level; refined by /speckit-specify)

- **FR-001** SP-1 MUST run and report before any other task in this spec. SP-1 MUST run on a CI runner or separate
  machine, never on the node host, and MUST NOT start before specs 016 and 017 complete without the user's explicit
  approval.
- **FR-002** The constitution MUST be amended (CG-1) before any Scala Native build configuration merges.
- **FR-003** The stateless core MUST be buildable for an RV64IM no-OS target, producing an ELF, by the route SP-1
  chose (Approach A: Scala Native cross-build; Approach B: TASTy/macro translation to C and a C toolchain).
- **FR-003a** A subset checker over the stateless closure's TASTy MUST run in CI and fail on any construct outside the
  guest subset (DC-8), landing before any guest build configuration.
- **FR-003b** Under Approach B, `UInt256` MUST be reimplemented fixed-width, byte-identical to the current
  implementation on every ethereum/tests vector and the 017 differential set, under the consensus-change protocol
  (`beacon` for ETH, `forge` for ETC) before the translator consumes it.
- **FR-003c** The guest MUST contain no storage engine, file system or database code (DC-7).
- **FR-004** The guest MUST read the block and `ExecutionWitness` through the zkVM's input mechanism and write its
  result through the zkVM's output mechanism.
- **FR-005** The guest MUST use `zkvm_accelerators.h` for cryptographic operations where an accelerator exists.
- **FR-006** The guest MUST agree with the JVM `StatelessExecutor` on every input in the differential set (DC-2).
- **FR-007** Release builds MUST be reproducible, per zkVM, named per the roadmap scheme and minisign-signed (DC-4).
- **FR-008** JVM-only dependencies in the stateless closure (spec 017 DC-15 inventory) MUST be replaced by target
  abstractions, without changing any consensus output and within the performance budget (DC-6).
- **FR-009** SP-1 MUST report the decision matrix for both tracks (bare-metal result, cycles, consensus-code change
  size, reproducibility, maintenance surface, DC-7) before the plan commits to a route.

### Key Entities

- **Guest program**: the bare-metal build of `StatelessExecutor` plus zkVM I/O glue (Scala Native under A; translated C
  under B).
- **Subset checker**: the CI tool over the stateless closure's TASTy that enforces the guest subset (DC-8).
- **Translator** (Approach B): the TASTy/macro-driven compiler from the guest subset to C for RV64IM.
- **Guest ELF**: one per zkVM and SDK version, reproducible and signed.
- **Bytes abstraction**: the target-neutral replacement for Pekko `ByteString` in the stateless closure.
- **Crypto interface**: BouncyCastle on the JVM, zkVM accelerators in the guest.

## Success Criteria *(mandatory)*

- **SC-001** SP-1 has reported both tracks with the decision matrix, and the user has decided Go (A), Go (B),
  go-with-constraints or no-go.
- **SC-001a** The subset checker runs in CI on the stateless closure and is green (DC-8, FR-003a), before any guest
  build configuration merges.
- **SC-002** On the zkVM emulator, the guest agrees with the JVM `StatelessExecutor` on 100% of the differential set
  and execution-specs fixtures.
- **SC-003** An independent rebuild from a release tag reproduces every published ELF bit for bit.
- **SC-004** At least one zkVM integrates fukuii's guest through Ere and runs it on mainnet or Sepolia blocks.
- **SC-005** The node's `[IMPORT-TIMING]` baseline and consensus outputs are unchanged within the constitution's budget
  after the bytes and crypto abstractions land.
- **SC-006** Cycle counts per block are measured against at least one other EL's guest on the same zkVM and blocks,
  and published. No target is set until the first measurement exists.

## Assumptions

- Spec 017's `StatelessExecutor`, witness format (DC-11) and dependency boundary (DC-15) exist before 018 starts
  beyond SP-1.
- The roadmap's standardized target (RV64IM, no OS), the accelerator header and the distribution conventions are
  still as read on 2026-10-11. Re-verify at planning time.
- `beacon` owns ETH consensus review of any shared-code change; `forge` signs off that ETC is unchanged; `wraith`
  handles cross-build compile failures.

## Open Questions

- **OQ-1** Does the current Scala Native release support an RV64IM bare-metal target, or only riscv64 Linux? As of
  2026-10 the project's CI gained a riscv64 cross-compilation job and no freestanding mode was found; answered by SP-1a.
- **OQ-1b** Approach B mechanism: TASTy offline (`tasty-query`), a compiler plugin phase, or inline macros? Default:
  TASTy offline, because it leaves the sbt build and shared code untouched and makes the subset checker a plain CI tool.
  Answered by SP-1b.
- **OQ-1c** Approach B target language: C (portable, every zkVM SDK ships a C toolchain and `zkvm_accelerators.h` is a
  C header) or LLVM IR? Default: C.
- **OQ-1d** Which TASTy version the translator targets. TASTy follows the compiler minor; the LTS audit of 2026-10-17
  (`docs/reports/LTS_TOOLCHAIN_AUDIT_2026-10.md`, R-1) recommends the repo move from Scala 3.3 LTS to 3.9 LTS before
  spec 017; that move is **spec 020** (`specs/020-scala-3-9-lts-migration/`), sequenced 016 → 020 → 017 → 018 → 019.
  The translator and `tasty-query` pin match the compiler spec 020 lands (3.9's TASTy); it is not built against 3.3's.
- **OQ-2** Which zkVMs to target first. Prefer the ones Ere already integrates and that the roadmap's benchmarks use.
- **OQ-3** Whether exceptions can be kept in the guest, or the shared code must adopt an exception-free subset.
  Under B the answer is "no exceptions"; under A it is SP-1a's finding.
- **OQ-3b** How large the `UInt256` rewrite is on the JVM side (158 `BigInt` references in `OpCode.scala` alone) and
  whether it ships before or after SP-1 reports. Recommended: after, since it is only required under B, but its JVM
  speed-up may justify it on its own.
- **OQ-4** The memory ceiling per zkVM, and whether the worst-case mainnet block fits under a no-op GC.
- **OQ-5** Whether the bytes abstraction should replace `ByteString` across the whole node or only inside the
  stateless closure. Default: only the closure, to keep the blast radius small.
- **OQ-6** Who holds the minisign release key, and how it is protected.

## Related specs

- **Spec 017** (state capabilities and execution witnesses): prerequisite. Supplies `StatelessExecutor`, the
  witness format and the dependency boundary.
- **Spec 018** (storage architecture for gigablock-scale execution): prerequisite. Settles the host data tiers (an
  embedded hot-tier engine chosen by bake-off, flat state, changesets, a parallel state root, an optional cold tier)
  beneath the capability gateways and guarantees (its DC-8) that the stateless closure gains no engine dependency; its
  SC-007 keeps witnesses byte-identical under the flat read path, so the guest's input does not change while the host
  changes.
- **Spec 009** (Amsterdam fork support): the guest must cover Amsterdam semantics, including BALs and the EIP-8037
  gas accounting that spec 009 calls load-bearing.

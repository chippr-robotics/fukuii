# Feature Specification: zkVM Guest Program (Stateless Validation on RISC-V)

**Feature Branch**: `018-zkvm-guest-program` (stub written on `docs/spec-017-roadmap-018-stub`)

**Created**: 2026-10-11

**Status**: **Draft (stub).** Not yet started. Gated by a Phase 0 feasibility spike (SP-1) and by a constitution
amendment (CG-1). To be fleshed out with `/speckit-specify` and `/speckit-clarify` once the spike has reported, then
`/speckit-plan` and `/speckit-tasks`. The Gates and Design constraints sections are binding on that later work.

**Depends on**: spec 017's `StatelessExecutor` (G-2) existing and passing its differential test on the JVM, and on
spec 017's DC-15 (narrow dependency boundary for the stateless core) and DC-11 (execution-specs witness format).

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

The only plausible route from Scala to a bare-metal RISC-V ELF is **Scala Native**, which compiles Scala through LLVM.
The JVM build of `StatelessExecutor` (spec 017) stays the reference. The guest is the same Scala source, compiled for
the zkVM target and checked against the JVM build on the same inputs.

Why Scala Native may fit:

- **GC.** One block's stateless validation has bounded memory, so a no-op GC (allocate, never free) may be enough
  within a zkVM's memory limit. That removes the hardest runtime component.
- **Accelerators.** The zkVM accelerators are exposed through a standard C header. Scala Native's C interop
  (`@extern`) binds C headers directly, so keccak, secp256k1, BN254 and the other precompiles map onto the zkVM's
  accelerated versions.
- **Single-threaded.** The guest needs no threads, no I/O beyond the zkVM's input/output, and no OS.

The open risks:

- **Runtime on bare metal.** Scala Native's exception handling and its libc assumptions have not been shown to work
  on an RV64IM no-OS target. This is what SP-1 tests.
- **Dependency closure.** The stateless closure uses Pekko `ByteString` throughout `vm`, `mpt`, `domain`, `rlp`,
  `bytes` and `crypto`, and BouncyCastle for hex and crypto. Neither is available on Scala Native. Spec 017's DC-15
  inventories them; this spec replaces them behind small interfaces (a bytes abstraction, a crypto interface whose
  JVM implementation stays BouncyCastle).
- **Performance in cycles.** Prover cost is RISC-V cycles, not wall time. Scala Native's output must be competitive
  enough in cycles to be usable. The roadmap's benchmarking workstream relates opcode use to cycle counts, so this is
  measured, not guessed.

## Gates *(mandatory; nothing past them proceeds until they pass)*

**SP-1 — Phase 0 feasibility spike.** Compile an empty Scala Native program for RV64IM with no OS and run it on
qemu-virt. Then repeat with a program that allocates, throws and catches an exception, and calls one C function. The
spike takes days, not weeks. It reports one of three outcomes to the user:

- **Go**: all three run. The spec proceeds.
- **Go with constraints**: the empty program runs but something else does not (typically exceptions). The plan then
  adopts a restricted Scala subset for the guest (for example no exceptions across the guest boundary, `Either`
  everywhere) and states the restriction as a design constraint.
- **No-go**: no bare-metal ELF is achievable. Fukuii stays a level 1 participant (spec 017), and this spec is closed
  with the spike's findings recorded.

**CG-1 — Constitution amendment.** The constitution currently says "Scala 3.x LTS on JDK 25 (OpenJDK) ... No Scala 2
and no cross-build", and requires new dependencies to be justified. A Scala Native build of the stateless core is a
cross-build and a new toolchain. Before any Scala Native code merges, the constitution is amended through
`/speckit-constitution`, with an ADR under `docs/adr/`, to allow exactly one scoped exception: the stateless core
modules may cross-build to Scala Native for the zkVM guest. The node itself stays JVM-only. The spike (SP-1) may run
on a throwaway branch before the amendment, because it merges nothing.

## Goals

- G-1 A Scala Native build of spec 017's `StatelessExecutor` that produces a bare-metal RV64IM ELF.
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
- **NG-6 A second EVM.** The guest is fukuii's existing EVM compiled for another target, not a rewrite.

## Design constraints *(mandatory)*

**DC-1 — One EVM, two targets.** The guest and the node run the same Scala source for execution, state transition and
witness verification. Target-specific code is limited to the bytes abstraction, the crypto implementations (JVM:
BouncyCastle; guest: zkVM accelerators) and I/O. A divergence between the JVM and guest builds on any input is a P0
bug.

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

**DC-6 — The JVM node pays nothing.** Introducing the bytes and crypto abstractions must keep the node within the
constitution's ~10% performance budget on the `[IMPORT-TIMING]` baseline, and must not change any consensus output.
`ByteString` replacement is measured, not assumed free.

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
- An exception path in shared code that the guest subset forbids (SP-1, go-with-constraints).
- A precompile with no zkVM accelerator: the software path must still be correct.
- Different zkVMs with different memory layouts and precompile conventions (one ELF each).

## Requirements *(mandatory)*

### Functional Requirements (stub level; refined by /speckit-specify)

- **FR-001** SP-1 MUST run and report before any other task in this spec.
- **FR-002** The constitution MUST be amended (CG-1) before any Scala Native build configuration merges.
- **FR-003** The stateless core MUST cross-build to Scala Native for an RV64IM no-OS target, producing an ELF.
- **FR-004** The guest MUST read the block and `ExecutionWitness` through the zkVM's input mechanism and write its
  result through the zkVM's output mechanism.
- **FR-005** The guest MUST use `zkvm_accelerators.h` for cryptographic operations where an accelerator exists.
- **FR-006** The guest MUST agree with the JVM `StatelessExecutor` on every input in the differential set (DC-2).
- **FR-007** Release builds MUST be reproducible, per zkVM, named per the roadmap scheme and minisign-signed (DC-4).
- **FR-008** JVM-only dependencies in the stateless closure (spec 017 DC-15 inventory) MUST be replaced by target
  abstractions, without changing any consensus output and within the performance budget (DC-6).

### Key Entities

- **Guest program**: the Scala Native build of `StatelessExecutor` plus zkVM I/O glue.
- **Guest ELF**: one per zkVM and SDK version, reproducible and signed.
- **Bytes abstraction**: the target-neutral replacement for Pekko `ByteString` in the stateless closure.
- **Crypto interface**: BouncyCastle on the JVM, zkVM accelerators in the guest.

## Success Criteria *(mandatory)*

- **SC-001** SP-1 has reported, and the user has decided go, go-with-constraints or no-go.
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

- **OQ-1** Does the current Scala Native release support an RV64IM bare-metal target, or only riscv64 Linux? Answered
  by SP-1.
- **OQ-2** Which zkVMs to target first. Prefer the ones Ere already integrates and that the roadmap's benchmarks use.
- **OQ-3** Whether exceptions can be kept in the guest, or the shared code must adopt an exception-free subset.
- **OQ-4** The memory ceiling per zkVM, and whether the worst-case mainnet block fits under a no-op GC.
- **OQ-5** Whether the bytes abstraction should replace `ByteString` across the whole node or only inside the
  stateless closure. Default: only the closure, to keep the blast radius small.
- **OQ-6** Who holds the minisign release key, and how it is protected.

## Related specs

- **Spec 017** (state capabilities and execution witnesses): prerequisite. Supplies `StatelessExecutor`, the
  witness format and the dependency boundary.
- **Spec 009** (Amsterdam fork support): the guest must cover Amsterdam semantics, including BALs and the EIP-8037
  gas accounting that spec 009 calls load-bearing.

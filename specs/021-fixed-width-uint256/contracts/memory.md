# Contract: the per-frame mutable `Memory` (spec 021, slice S4; G-6, FR-012)

*Spec 021, Phase 1, 2026-11-07. Scope: `vm/Memory.scala` and its callers in `vm/OpCode.scala`, `vm/ProgramState.scala`,
`vm/VM.scala`, `vm/ExecutionTracer.scala`, `vm/StructLogTracer.scala` and `vm/VmTracer.scala`. Layout and invariants
are in [data-model.md §8](../data-model.md#8-memory-the-per-frame-mutable-buffer-vmmemoryscala-slice-s4-research-r-11).
The reasons are in [research.md R-11](../research.md#r-11-mutable-memory-slice-s4-g-6-fr-012).*

## 1. What does not change (FR-012)

- **Gas.** Memory-expansion gas, its 2³¹−1 cap (`MaxMemory`), the out-of-gas sentinel and the unbounded size
  arithmetic in `calcMemCost`, `wordsForBytes` and MCOPY's gas. S4 edits none of these; S2 owns them.
- **Bytes.** Every byte read and written, the zero fill, and `size` (unrounded; MSIZE rounds it at use). Checked by
  the GT-2 differential against `vm.oracle.ImmutableMemory`.
- **The lazy zero tail.** Bytes past the highest write are never allocated, and escaped regions use zero-page views
  for them (`randomStatetest94`, `static_Call1MB1024Calldepth`; rows M07 and M08).
- **Identity.** `equals`, `hashCode` and `toString` keep their content semantics (M12).

## 2. API (all offsets and sizes are `Int`; see Rules M1–M3)

| Member | Mutates | Contract |
|---|---|---|
| `Memory.empty: Memory` | — | a new, empty buffer for one frame. No array until the first write |
| `size: Int` | no | the EVM memory size in bytes, unrounded |
| `loadWordInto(offset, stack)` | `size` | 32 bytes, big-endian, into a new top slot of `stack`; zeros past `written` |
| `storeWordFrom(offset, stack, depth)` | yes | the word at `depth` as 32 big-endian bytes |
| `storeByte(offset, b)` | yes | MSTORE8 |
| `write(offset, src: ByteString)` | yes | an empty `src` is a no-op (M01) |
| `writePadded(offset, size, src, from, len)` | yes | writes `src[from, from+len)` clipped to `size`, then **writes zeros** over the rest of `[offset, offset+size)` (M14) |
| `move(dst, src, n)` | yes | memmove semantics (EIP-5656); n = 0 is a no-op (M05) |
| `copyOut(offset, n): ByteString` | **no** | an **owned** copy of the overlap with `[0, written)`, plus zero-page views; n = 0 → empty (M02) |
| `view(offset, n): ByteString` | **no** | a **read-only view** of `buf` for the overlap with `[0, written)`, plus zero-page views; no copy. Used only for CALL-family input, and valid only for the duration of `vm.call` (Rule M7) |
| `hashRegion(offset, n): Array[Byte]` | **no** | Keccak-256 of the region, with the zero tail streamed (SHA3, M08) |
| `expand(end)` | `size` | `size = max(size, end)`. Callers skip it for a size-zero region (M04) |
| `snapshot(): Memory` | no | an independent `Memory` with a copy of `[0, written)` and the same `size` |
| `equals`, `hashCode`, `toString` | no | content semantics, as today (M12) |

## 3. Rules

- **Rule M1.** A word becomes an `Int` offset or size only after `calcGas` has charged for the region, and only with
  `toInt`'s low-31-bit semantics. Part A §3's T01–T05 invariants hold unchanged.
- **Rule M2.** Size-zero tests read the **full word** (`Stack.isZeroAt`), never the truncated `Int`.
- **Rule M3.** No memory read or write is moved ahead of the gas check.
- **Rule M4 (escape: copy only what is paid for).** An owned copy (`copyOut`) is made only where the operation's gas
  pays per byte of that escape:
  - CREATE/CREATE2 initcode;
  - RETURN and REVERT data;
  - LOG data;
  - tracer reads.

  CALL-family input is **not** copied: a CALL's gas does not depend on its input size, so a per-call copy is a DoS
  (forge R1-a; M15 (c)). It is passed as a `view` (Rule M7). RP-5's escape table states, per site, view or owned copy,
  and what pays for each owned copy.
- **Rule M5 (commit).** Region reads (`copyOut`, `view`, `hashRegion`) never change `size`. MLOAD's `loadWordInto`
  expands within its own step, as today, because it has no failure path after its gas check (M13). An opcode calls `expand` or writes at exactly the points where
  today's code attaches the expanded `Memory` to the state it returns, on each outcome:
  - CREATE: every outcome after its initcode check;
  - CALL: in and out on every outcome, with the output written only on success or revert;
  - the copy opcodes: after their checks.

  A path that fails a check before writing leaves `size` and the bytes as they were (M10, FR-012). RP-5's table lists
  every path.
- **Rule M6 (ownership).** One buffer per frame, shared only by that frame's `ProgramState` copies; never pooled.
  No frame writes another frame's buffer. A child frame receives its input as a read-only `view` (Rule M7), and
  everything else as owned copies.
- **Rule M7 (the CALL-input view).**
  - A `view` is valid for the duration of `vm.call`: the caller is suspended, and by Rule M6 nothing writes its
    buffer.
  - The callee only reads it. CALLDATACOPY copies into the callee's own buffer.
  - Where the input outlives the call, an owned copy is taken at that point:
    - `InternalTransaction.data`, under `traceInternalTransactions` (default off);
    - `onCallEnter`'s `input`, when a tracer is active.
  - **Precompile output is always owned.** The VM's precompile-call wrapper copies `returnData` before it leaves
    `vm.call`. The identity precompile returns its input, and without the copy RETURNDATA would alias the caller's
    buffer (M15 (a)). NG-6 holds: the copy is in the wrapper.
  - No consumer of a view or of any memory-derived `ByteString` writes into an array obtained with `toArrayUnsafe`.
    RP-5 includes a grep audit of `vm/`, the precompiles and their crypto calls.

## 4. `ProgramState`, `VM.exec` and the tracers

- `ProgramState.memory` is the frame's live buffer. `withMemory(m)` stays, to install a snapshot and for tests.
- `ExecutionTracer` gains `def readsPreStepMemory: Boolean = false`. StructLogTracer overrides it with its
  `enableMemory`.
- `VM.exec`, when tracing (word-stack contract §5), builds `before` with `memory = state.memory.snapshot()` if any
  active tracer returns `true`. Otherwise `before` carries the live buffer.
- `prevState.memory` is therefore the pre-step memory whenever a tracer has declared that it reads it (M11).
- `nextState.memory` is the live buffer. It is valid only during `onStep` and is read through `copyOut`; VmTracer's
  MSTORE and MSTORE8 reads comply.
- A tracer that reads `prevState.memory` without declaring `readsPreStepMemory` sees post-step memory. The trait's doc
  says so, and the S0 trace goldens catch it.

## 5. Not in this contract

- No buffer pool.
- No off-heap memory.
- No change to `crypto/`: SHA3 copies the stored prefix into the digest input as today.
- No change to precompiles (NG-6). They receive a view as input, and the VM wrapper owns their output.

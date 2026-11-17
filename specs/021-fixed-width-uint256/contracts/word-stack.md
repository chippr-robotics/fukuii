# Contract: the word stack and the frame interfaces around it (spec 021, slice S3)

*Spec 021, Phase 1, 2026-11-07. Scope: `vm/Stack.scala`, and the parts of `vm/ProgramState.scala`, `vm/VM.scala`,
`vm/OpCode.scala` and the four stack-reading tracers that depend on it. Layout and invariants are in
[data-model.md §5–§7](../data-model.md#5-the-word-stack-vmstackscala-slice-s3), and the reasons in
[research.md R-10](../research.md#r-10-the-word-stack-against-dc-8). `Memory` (slice S4) has its own contract,
[memory.md](./memory.md).*

## 1. `Stack`: read-only accessors (pre-instruction reads; never mutate)

Depth `i`: 0 is the top. Every accessor requires `0 <= i < size`; `OpCode.execute` has already checked `delta`.

| Accessor | Returns | Allocates | Replaces today's |
|---|---|---|---|
| `size: Int`, `maxSize: Int` | as today | no | — |
| `peek(i): UInt256` | the word at depth i | one `UInt256` | `stack.pop(n)._1(i)` used as a peek |
| `isZeroAt(i): Boolean` | the word is zero (full word) | no | `pop…isZero` |
| `int31At(i): Int` | `toInt` semantics: the low 31 bits | no | `pop…toInt` |
| `isValidIntAt(i): Boolean` | the word ≤ `Int.MaxValue` | no | `pos.toBigInt.isValidInt` |
| `byteSizeAt(i): Int` | `byteSize` of the word | no | EXP's `varGas` |
| `addressAt(i): Address` | the low 20 bytes | one `Address` | `Address(pop…)` |
| `toSeq: Seq[UInt256]` | top first, a fresh copy | yes | as today (tracers, tests) |
| `snapshot(): Stack` | a separate `Stack` holding the live words | yes | — (tracers, tests) |

**Rule P (peek before pop).** `calcGas`, `baseGas`, `varGas`, `stateGasDelta`, `recordReadsBeforeOutOfGas`,
`availableInContext`, `address()`, `addressAndKey`, CALL's parameter, gas and target helpers, and CREATE2's salt read
use **only** this section. Row W04 checks it for every opcode of every opcode table, over the ETH builders and the
ETC (config, block) matrix.

**CALL's gas word** is read with `peek(i).toBigInt` or an unbounded compare, never with `int31At` or a `toLong`.
Before EIP-150 (ETC below 2,500,000; ETH Frontier and Homestead), `gasCap` returns the raw 256-bit request, and a
truncation would forward 5 gas for a request of 2⁶⁴+5 (row F04, forge C13).

## 2. `Stack`: mutators (`exec` and `execAndSpendGas` only)

Every mutator returns `Unit`, so a persistent-style use (`val (a, s1) = stack.pop()`, or a `push(...)` used as a
`Stack`) does not compile.

| Mutator | Effect |
|---|---|
| `pop(): UInt256` | removes and returns the top word (one `UInt256`); on an empty stack returns `Zero` and changes nothing (as today, W10) |
| `drop(n: Int): Unit` | removes n words |
| `push(word: UInt256): Unit` | copies the limbs in; ignored at `maxSize` (as today) |
| `pushInt(n: Int): Unit` (n ≥ 0), `pushZero(): Unit`, `pushBool(b: Boolean): Unit` | allocation-free pushes for PC, CALLDATASIZE, MSIZE and the compares |
| `pushBytes(src: ByteString, from: Int, len: Int): Unit` | big-endian, right-aligned. Bytes past `src`'s end read as zero, as `Program.immediate` does today (W01). Used by PUSH1..PUSH32, SHA3's digest and MLOAD |
| `dup(i)`, `swap(i)`, `exchange(i, j)` | as today's semantics, copying limbs (W02, W03) |
| `unaryInPlace`, `binaryInPlace`, `ternaryInPlace` (inline, taking an inline kernel) | apply a `UInt256Kernels` kernel to the top words and write the result into the destination slot (data-model §5.3) |
| `divModInPlace(kind)`, `addModInPlace`, `mulModInPlace`, `expInPlace` | the array kernels on the stack's own array and scratch |
| `checkpoint(n: Int): Unit`, `rollback(): Unit` | EIP-8037 only: data-model §5.4 |

**Rule V (validate before mutate).**
- An `exec` reads its operands with §1's accessors, then runs every check whose error state carries the
  pre-instruction stack, and only then calls a mutator.
- The error paths that carry a post-pop stack today keep it, by keeping today's order of operations. These are
  RETURNDATACOPY's overflow and CREATE's initcode limit.
- RP-4's table lists the 22 `withError` sites and the stack each returns. Row W05 asserts them.

## 3. `Stack`: identity and construction

| Member | Contract |
|---|---|
| `Stack.empty(maxSize: Int = 1024)` | a new, empty stack: 16-word initial capacity |
| `Stack.of(words: UInt256*)` | bottom first, the same order as today's `push(Seq)`; for tests and fixtures |
| `equals` | value equality over the live words; ignores `maxSize` (as today) |
| `hashCode` | `toSeq.reverse.hashCode`: bottom first, the value today's `List`-backed stack returns |
| `toString` | `"Stack(" + top-first UInt256.toString (signed decimal) joined by "," + ")"` (as today) |

## 4. `ProgramState`

- `stack` is the frame's **live** stack. Every `copy` of a state shares it.
- A state value does not freeze its stack. Code that needs the pre-instruction stack after `exec` takes a `snapshot()`
  first.
- `withStack(s)` remains for installing a snapshot (the VM's tracer path) and for tests. `stepWithStack` and
  `jumpWithStack` drop their stack parameter and are renamed to say what they do: one `copy` for pc and gas.

## 5. `VM.exec` and the tracers

```text
val traced  = tracer.isDefined || state.env.tracer.isDefined
val before  = if traced then state.withStack(state.stack.snapshot()) else state
val after   = opCode.execute(state)
tracer.foreach(_.onStep(opCode, before, after))
state.env.tracer.foreach(_.onStep(opCode, before, after))
```

`ExecutionTracer.onStep`'s signature does not change. Its contract gains two sentences:
- `prevState.stack` is a snapshot the tracer may keep.
- `nextState.stack` is the live stack; it is valid only during `onStep` and must not be retained.

StructLogTracer, VmTracer, PrestateTracer and AccessListTracer already comply (research R-10 (d)). The S0 trace
goldens prove that `debug_trace*` and `trace_*` output is byte-identical (DC-8).

## 6. `OpCode.execute`

1. The static-context check, then underflow, then overflow: unchanged, no mutation.
2. `calcGas(state)`. Rule P applies. On OutOfGas, `recordReadsBeforeOutOfGas` (Rule P) and return the state with the
   pre-instruction stack. Unchanged.
3. `stateGas` (Rule P). If it is > 0, `stack.checkpoint(delta)`.
4. `execAndSpendGas`: Rule V.
5. On the EIP-8037 shortfall: `stack.rollback()`, then `state.copy(gas = 0).withError(OutOfGas)` (W07). Otherwise
   unchanged.

Fork dispatch, the opcode tables (`EtcOlympiaOpCodes`, `OsakaOpCodes`, `AmsterdamOpCodes` and their peers),
`forBlock` and `forTimestamp` are untouched (forge S5). Row F06's opcode-table golden, captured in S0, tests this
through S1 to S4.

## 7. `Memory`

S3 does not change `Memory`: its opcodes still pass `UInt256` offsets, read with `peek` or `pop`. Memory's interface
and its mutable representation change together in S4, under [memory.md](./memory.md) (Rules M1–M6).

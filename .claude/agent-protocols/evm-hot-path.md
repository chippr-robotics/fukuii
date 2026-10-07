# EVM interpreter hot path: contracts and measurement

The interpreter (`vm/VM.scala` `exec`, `vm/OpCode.scala` `OpCode.execute`) runs once per EVM instruction.
Both chains share it, ETC and ETH, so every change here is consensus-critical. hive's `legacy/loopMul_*`
vectors (0.5 to 6.2 Ggas in one transaction) are the workload that exposes its per-instruction cost. This
file records what the September 2026 throughput work relies on, so that later changes neither break it nor
undo it without knowing.

## Contracts `OpCode.execute` relies on

`execute` skips two per-instruction calls because their answers are fixed by an EIP:

| Hook | Contract | Why | Enforced by |
|---|---|---|---|
| `availableInContext` | true whenever `!state.staticCtx` | EIP-214 restricts STATICCALL frames only | `OpCodeContractSpec` |
| `stateGasDelta` | zero whenever `!state.config.amsterdamEnabled` | EIP-8037 state gas starts at Amsterdam | `OpCodeContractSpec` |

`execute` asks the first only inside a static frame, and the second only from Amsterdam on. A new override
that breaks either contract is silently skipped, and `OpCodeContractSpec` then fails. `CreateOp` and
`CallOp` have their own `execute` and still ask `availableInContext` unconditionally.

## One ProgramState copy per stack-only instruction

`ProgramState` is a 26-field case class and is copied at least once per instruction. `StackOnlyOp` (the
arithmetic, comparison, bitwise, PUSH, DUP, SWAP, POP and JUMPDEST families, plus ConstOp) derives both
`exec` and the fused `execAndSpendGas` from `nextStack` and `pcIncrement`. A new instruction whose only
effect is on the stack and the pc should mix in `StackOnlyOp` rather than write
`state.withStack(..).step()`: that is three copies, since `execute` then adds `spendGas`.

`ProgramState.stepWithStack` and `jumpWithStack` are, field for field,
`withStack(s).step(n).spendGas(g)` and `withStack(s).goto(d).spendGas(g)` (`StackOnlyTransitionSpec`).

## JIT: ProgramState's constructor must be inlined

Compiled on its own, `ProgramState.<init>` is ~6 KB of machine code: each reference store carries a G1
barrier. That is over `InlineSmallCode` (2500), so C2 calls it out of line from every copy site
(`-XX:+PrintInlining`: "already compiled into a big method"). hive's `fukuii.sh` passes
`-XX:CompileCommand=inline,com.chipprbots.ethereum.vm.ProgramState::<init>`. On Temurin 25,
loopMul_d2 ran at 25.4 CPU-s without it and 18.1 CPU-s with it. Adding fields to `ProgramState` makes the
out-of-line constructor bigger still, so keep the flag wherever EVM throughput matters.

## Measuring

`scripts/bench/blockchain-test-bench.sh <BlockchainTest.json> [caseRegex] [repeat]` runs cases through
`EthereumTestExecutor` (the `BlockExecution` path, with gasUsed, receipts, state root and postState all
checked) using hive's JVM flags. It prints wall time and thread CPU time.

- Compare `cpu_seconds`, not `seconds`: on a shared 4-core machine, wall time moved ±30% with load.
- A/B runs: snapshot `target/scala-3.*/classes` per build and pass `MAIN_CLASSES=<dir>`. Alternate the
  runs (A B A B).
- `JDK_IMAGE=chipprbots/fukuii:latest` measures on hive's JDK (Temurin 25) via Docker.
- `JFR=<file>` records a profile. JFR attributes some allocation cost to the nearest call (for example,
  `BigInt.bitLength` under `UInt256.apply(Long)`). Treat a hotspot as real only after an A/B run confirms it.
  Two such "hotspots" were fixed and then measured at noise level, so they were not committed:
  hand-written walks in `Stack` in place of List's `drop`/`apply`, and a one-step BigInteger build in
  `UInt256.apply(ByteString)`.

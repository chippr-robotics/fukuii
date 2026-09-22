# Hive failure inventory — measured, not inferred

Every count here comes from a hive run's `summaryResult.pass` tallies, read out of the
uploaded `hive-logs-*` artifact or the gate's own `PASSED`/`FAILED`/`TOTAL` step
environment. Nothing here is derived by grepping a client log for the word "error".

**Read the denominator before differencing two numerators.** The `consensus` suite runs
under `--sim.timelimit 60m` and walks its corpus until time runs out, so its total varies
run to run (1492 / 1550 / 1864 observed). Its pass *count* is not comparable across commits
and no claim below rests on it.

## Suite totals

| suite | commit | passed | failed | total |
|---|---|---:|---:|---:|
| engine | c82c89e | 258 | 145 | 403 |
| consume-engine | c82c89e | 1768 | 41 | 1809 |
| consume-engine | b91d3fd | 1434 | 8 | 1442 |
| rpc-compat | c82c89e | 207 | 40 | 247 |
| devp2p | c82c89e | 28 | 34 | 62 |
| devp2p | 4d57c9d | 28 | 34 | 62 |
| devp2p | b1f2db0 | 44 | 18 | 62 |
| graphql | c82c89e | 50 | 2 | 52 |
| consume-rlp | c82c89e | 1796 | 4 | 1800 |
| consensus | c82c89e | denominator unstable — not comparable |
| sync, smoke-genesis, smoke-network | c82c89e | green |
| rpc-compat | 8979c3f | 241 | **6** | 247 | testing_* namespace — all 9 cleared |
| rpc-compat | 3bbc861 | 232 | 15 | 247 | debug_trace error class — 4 cleared |
| graphql | 8979c3f | 50 | 2 | 52 | oracle held through the 597-line refactor |
| engine | fc713a9 | 306 | 97 | 403 |
| consume-engine | fc713a9 | — | 8 | 1442 |
| consume-rlp | fc713a9 | — | 4 | 1800 |
| rpc-compat | fc713a9 | 228 | 19 | 247 |
| graphql | fc713a9 | 50 | 2 | 52 |
| devp2p | fc713a9 | 44 | 18 | 62 |
| sync | fc713a9 | 10 | **1 real + 1 gate-excluded** | 12 | gate said 0 |
| sync | 3bbc861 | 10 | **2 real, 1 gate-excluded** | 12 | gate said 1 |
| sync | 672e410 | — | red | 12 | docs-only commit |

**The sync row was wrong in an earlier revision of this file and the error is worth naming.**
It recorded `fc713a9` as "GREEN / 0 failing / 1 total". All three numbers were wrong. The
suite has **12** tests, not 1. That run had **one real failure** — test 9,
`sync go-ethereum from fukuii`. It reported zero because `.github/workflows/hive-sync.yml:69`
sets

    gate_exclude: 'sync go-ethereum from fukuii|sync fukuii from nethermind'

and test 9 is documented there as failing since 2026-05-14. The GATE was zero; the SUITE was
11/12. Reading a gate result as a suite result is exactly the mistake this document's opening
rule — derive counts from `summaryResult.pass` — exists to prevent, and it was made anyway.

Confirmed from each run's own `hive-run.log`: fc713a9 ends `tests=18 failed=1`, 3bbc861 ends
`tests=18 failed=2` (18 = both suites; sync is suite 1 of 2).
| consensus | fc713a9 | denominator unstable — 7 failing, not comparable |
| rpc-compat | e33b3e0 | 228 | 19 | 247 |
| rpc-compat | b4cdc30 | 228 | 19 | 247 |
| graphql | e33b3e0 | 50 | 2 | 52 |
| graphql | b4cdc30 | 50 | 2 | 52 |
| devp2p | e33b3e0 | 44 | 18 | 62 |
| sync | cd7d670, e33b3e0 | — | 1 | 1 |

The two `b4cdc30` rows are the inertness oracles measured across the pre-execution validation
fix. Their **method histograms are byte-identical** to `e33b3e0`, not merely their counts:
rpc-compat 5 `testing_commitBlockV1` / 4 `testing_buildBlockV1` / 3 `debug_traceTransaction` /
3 `debug_traceBlockByNumber` / 2 `debug_traceBlockByHash` / 1 `eth_sendRawTransaction` /
1 `eth_config`; graphql `07_eth_gasPrice` and `04_eth_estimateGas_contractDeploy`. That is the
expected result for the right reason — both suites ingest via `chain.rlp` → `ChainImporter`,
which already called the same validator battery at `ChainImporter.scala:119`, so the fix
cannot reach them.

## devp2p — what the flat 34 concealed

The test-name histogram is **byte-identical** between `c82c89e` and `4d57c9d`. The failure
*causes* are not. Reading the histogram alone would have reported "no change"; reading the
client import log and the per-test detail offsets reports the opposite.

| bucket | c82c89e | 4d57c9d |
|---|---:|---:|
| `wrong head block in status, want: 600, have 35` | 27 | 0 |
| `wrong fork ID in status` | 0 | 27 |
| capability negotiation (snap/2, eth/71) | 4 | 4 |
| harness `exit status 1` | 3 | 3 |

Chain import, same two runs:

* `c82c89e` — `ValidationBeforeExecError(UNKNOWN_PARENT)` for every block from 36 to 600.
* `4d57c9d` — **zero** import failures; `SyncController` reports
  `beacon head 9d78f735… (knownHeader=600)`.

So `4d57c9d` (EIP-8282 builder predeploys) closed the block-36 state-root divergence and the
564-block cascade behind it. The 27 tests it unblocked immediately re-failed one layer up,
on the handshake.

## Root causes, each tied to its evidence

### A. ForkID omits three forks — 27 devp2p — FIXED in `b1f2db0`

**Outcome, measured on `b1f2db0`: 44 / 18 / 62, against 28 / 34 / 62 on `4d57c9d`.** Same
denominator, so the numbers are directly comparable: 16 tests fixed. The `wrong fork ID`
bucket went 27 -> **0**. Of those 27, fifteen now pass outright and twelve get past the
handshake and fail on real protocol conformance for the first time — see H below. The
`snap/2` (3) and harness `exit status 1` (3) buckets were untouched, as expected.

The diagnosis follows.

fukuii advertises `0x321a21a2`; peers demand `0x5942bfc2`. Computing EIP-6122 CRC32 over the
fixture genesis `1518c33d…52024c` discriminates exactly:

| fork list | checksum | |
|---|---|---|
| `[60, 120, 180]` | `0x321a21a2` | what fukuii sent |
| `[60, 120, 180, 240, 300, 360]` | `0x5942bfc2` | what peers wanted |

Two independent omissions compose:

1. `hive/fukuii/fukuii.sh` maps PRAGUE/OSAKA/AMSTERDAM but never BPO1/BPO2, so 240 and 300
   never reach the config. Not cosmetic — `isBpo1Timestamp`/`isBpo2Timestamp` also select the
   blob target, blob max and base-fee update fraction, so hive blob parameters were silently
   wrong past timestamp 240. fukuii's six BPO constants already match the fixture exactly;
   they were simply never activated.
2. `ForkId.gatherTimestampForks` enumerated every timestamp fork *except* Amsterdam, so 360
   was dropped even where the adapter did pass it.

Invisible to every existing spec because no shipped chain config sets `amsterdam-timestamp`
— `ForkIdSepoliaSpec`'s schedule stops at BPO2. A fork missing from an enumeration cannot
fail a test that never configures it.

### B. Block timestamps are signed — 32 of 41 consume-engine

All 32 EIP-4788 failures return `-38005 newPayloadV3 cannot be used pre-Cancun`, and every
one carries `timestamp_18446744073709551614` or `timestamp_18446744073709551615` — 2^64−2 and
2^64−1. **No other timestamp value fails.** That is a clean discriminator, not a correlation.

A block timestamp is a `uint64`. `opaque type Timestamp = Long` (`domain/Timestamp.scala`)
is signed, and so are all of its comparison operators and its `Ordering`. At 2^64−1 the
underlying Long is −1, so `isCancunTimestamp` evaluates `−1 >= cancunTime` as false and the
fork gate rejects a valid Cancun payload.

At least two sites share the cause:
* `EngineApiController.scala:118-151` — the fork-version gate.
* `BlockExecution.scala:211` — `UInt256(header.unixTimestamp.toLong)` then `mod 8191` for the
  EIP-4788 ring-buffer index. A sign-extended −1 indexes the wrong slot.

The opaque type is the right place to fix it: for any timestamp below 2^63 — every real
chain, every other fixture — unsigned and signed comparison agree, so the change is provably
inert outside these tests. Consensus-critical (fork dispatch): route through `beacon`, with
`forge` confirming ETC is untouched.

### C. Engine API promotes unexecuted blocks — dominates the 145 engine failures

`forkchoiceUpdated`'s `headOptimistic` branch calls `ForkChoiceManager.applyForkChoiceState`
for its `publishBeaconHead` side effect alone, but that method also calls
`promoteBranchToCanonical` + `saveBestKnownBlocks` with no check that the block was ever
executed. `newPayload`'s dedup branch then reads that number→hash mapping as proof of
validity and returns VALID for a block hive expects INVALID.

Consistent with the failure histogram, which is dominated by
`Invalid NewPayload, Transaction {Gas,GasPrice,GasTipCapPrice,Nonce,Signature,Value},
Syncing=True, … (Paris|Cancun)`.

### D. rpc-compat — 40, fully bucketed

The method histogram sums to exactly 40, so this breakdown is complete rather than truncated.

| cluster | n | note |
|---|---:|---|
| `eth_getBlockByNumber` / `eth_getBlockByHash` | 10 | read path — serialization, not execution |
| `testing_commitBlockV1` / `testing_buildBlockV1` | 9 | namespace absent from fukuii entirely |
| `debug_trace{Transaction,BlockByNumber,BlockByHash}` | 8 | |
| `eth_simulateV1` | 6 | implemented; behavioural |
| singletons | 7 | `sendRawTransaction`, `getTransactionCount`, `getStorageAt`, `getProof`, `getCode`, `getBalance`, `config` |

### E. Capability negotiation — 4 devp2p

`could not negotiate snap protocol (remote caps: [eth/68 eth/69 snap/1], local snap version: 2)`.
`Capability` declares ETH63–70 and SNAP1; it has no `eth/71` and no `snap/2` (EIP-8189), and
`GetBlockAccessLists` appears nowhere in `src/`. Affects `Status`, `TrieNodesRemoved` and both
`GetBlockAccessLists` tests. This is spec 009 slice C, not a quick fix.

### F. consume-engine remainder — 9 of 41, opcode gas schedule

All of these reach fukuii and are rejected by its own validator, so the client log names the
discrepancy exactly. `test_all_opcodes` executes every opcode once per fork:

| fork | header expects | fukuii computed | delta |
|---|---:|---:|---:|
| Paris | 8,298,977 | 8,313,979 | +15,002 |
| Shanghai | 8,283,975 | 8,298,977 | +15,002 |
| Cancun | 8,209,169 | 8,159,271 | −49,898 |
| Prague | 8,209,169 | 8,159,271 | −49,898 |

Two things worth noting before anyone theorises. Shanghai's *computed* value is exactly
Paris's *expected* value, which looks like a one-fork dispatch shift — but that reading does
not survive Cancun, where the shifted prediction would be 8,283,975 and the observed value is
8,159,271. And the delta is not one constant: it is +15,002 on the two pre-Cancun forks and
−49,898 on the two post-Cancun ones. So this is a per-opcode schedule difference, not a single
mispriced constant, and it is NOT established which opcodes.

Separately, `test_constant_gas[fork_Paris-BASEFEE]` expects 95,452 and fukuii computes
500,126 — a ~5× overcharge, a different shape from the above and probably a different defect.
`test_constant_gas[fork_Paris-MSIZE]`, 2 EIP-4844 blob-tx and 1 EIP-2930 access-list test
round out the nine; their client-side reasons were not captured.

### G. consume-rlp — 4 of 1800

Four unrelated failures, no shared cause:

* `test_identity_precompile_returndata[fork_Cancun-output_size_greater_than_input]`
* `test_modexp[fork_Shanghai-large-exponent-length-0x10000000-out-of-gas]`
* `test_sufficient_balance_blob_tx[fork_Cancun-max_blobs]`
* `test_tx_intrinsic_gas[fork_Berlin-tx_type_1-below_intrinsic_True-access_list_1_address_1…]`

The last two are the SAME defects that appear in consume-engine's remainder (F) under
different forks, so the two suites share 2 root causes, not 4. Fixing them moves both.

### H. devp2p protocol conformance — 12, newly visible on `b1f2db0`

These are not new defects. They are defects the handshake failure concealed: with the fork id
wrong, no peer ever got far enough to exercise them. They appeared as the fork-id bucket
drained, which is why devp2p's failure count fell by 16 rather than by 27.

| cluster | n | symptom |
|---|---:|---|
| snap/1 protocol offset is hardcoded | 4 | `AccountRange`, `GetByteCodes`, `GetTrieNodes`, `GetStorageRanges` — root-caused below |
| `GetPooledTransactions` not served | 5 | `NewPooledTxs` and `BlobViolations` time out; `Transaction`, `InvalidTxs`, `LargeTxRequest` crash the simulator with a Go panic |
| blob sidecar handling | 2 | `TestBlobTxWithoutSidecar`, `TestBlobTxWithMismatchedSidecar` — both `failed to read GetPooledTransactions message: disconnect` |
| eth/71 block access lists | 1 | `GetBlockAccessLists` — `connection reset by peer` during status exchange |

**snap/1 — root cause established.** The simulator reports `i/o timeout`, which reads like a
serving defect, but the client log says otherwise:

```
DECODE_ERROR: Peer sent unknown message type: 0x2a (42) ... Unknown snap/1 message type: 42
DECODE_ERROR: Cannot decode GetByteCodes. Expected RLPList[3] with structure
              [requestId, hashes, …] - disconnecting
```

fukuii is not failing to answer; it is **disconnecting**, and the simulator's read then times
out. The cause is `SNAP.scala:40`:

    val SnapProtocolOffset = 0x30

RLPx assigns each negotiated subprotocol a message-code offset derived from the capability set
agreed in the Hello exchange — it is not a constant. The peer is sending snap messages at
`0x2a` and `0x2c`. fukuii looks for them at `0x30`, treats `0x2a` as unknown, and when a code
does fall inside its `0x30` window it decodes the wrong message type, which is the
`RLPList[3]` structure failure above.

The comment at `SNAP.scala:29` records that this already bit once: the offset was `0x21` and
"caused SNAP's GetAccountRange to decode as BlockRangeUpdate". Moving the constant to `0x30`
treated that symptom. Hardcoding it at all is the defect, and it will break again on the next
capability-set change. Fix belongs with `herald` (P2P/RLPx), not consensus.

**tx-pool and blob — NOT established.** fukuii either disconnects or fails to answer
`GetPooledTransactions`; the blob pair reports the same message, so those 7 may be 1 root
cause rather than 7. No evidence either way yet.

## I. engine — "Invalid Missing Ancestor Syncing ReOrg", 48 failures

Half of all remaining engine failures. An implementation attempt was made and **reverted**; what
follows is the ground truth it produced, so the next attempt does not rediscover it.

### The 48 reconcile exactly

`tests.go:261-301` registers 11 surviving `InvalidField` values × 2 `ReOrgFromCanonical` modes,
plus 2 extra `EmptyTransactions` variants of `InvalidStateRoot` = **24 cases**. `suites/cancun`
re-registers every `suite_engine.Tests` entry `WithMainFork(config.Cancun)`, and hive runs both
suites: **24 + 24 = 48**. The defect is therefore fork-independent, not a Cancun gap.

`InvalidIndex` is 8 for `InvalidReceiptsRoot|GasLimit|GasUsed|Timestamp`, 9 for the rest.
`cAHeight = 5`, `n = 10`, so `altChainPayloads[i]` is block `5+i`.

### fukuii must sync over p2p in BOTH modes — there is no cheap local subset

The distribution loop is `for i := 1; i < n` — blocks 6..14 go **only to the secondary client**.
`altChainPayloads[10]` (block 15) is never sent to the secondary; it goes only to the client under
test, inside the poll loop. So fukuii never receives the ancestors over the Engine API and must
fetch them from the peer.

`ReOrgFromCanonical` does not change that, only the starting position:

* `false` — fukuii is removed from the CLMock for canonical production, so it sits at **genesis**
  and must sync 14 blocks. Peering is established by `secondaryClient.AddPeer(t.Engine)`.
* `true` — fukuii is driven through canonical blocks 1..15, so its head is **block 15** while the
  peer's head is **block 14**. Peering is established at secondary startup instead, via the
  variadic `bootClients` argument to `StartGethNode` (`node.go:69-81`, `:209-215`) — byte-identical
  to `AddPeer`. **There is no un-peered case.**

The `true` mode is the hard one: the peer's head is LOWER than fukuii's own. Any sync strategy
gated on "peer must have greater height or total difficulty" will never fire. It needs a targeted
**reverse-by-hash walk** from `altChainPayloads[10].parentHash`, which fukuii does not currently
have.

### The latestValidHash requirement is strict

```
if cAHeight != 0 || tc.InvalidIndex != 1 { lvh = altChainPayloads[tc.InvalidIndex-1].BlockHash }
```
No registered case sets `CommonAncestorHeight` or `InvalidIndex == 1`, so the condition is always
true and **the PoW zero-hash branch is dead code for all 48**. `&lvh` is a pointer to a stack
value and is never nil, so returning INVALID with `latestValidHash: null` **fails**, as does the
zero hash, as does fukuii's own canonical head. The expected block is on the side chain and was
never fukuii's head: fukuii must be able to name a validated-but-non-canonical block.

Budget: `TimeoutSeconds = 60` for the whole test, starting at setup. Setup burns ~20-30s (15 ×
`ProduceSingleBlock`, each with a 1s sleep), leaving roughly 30-40 poll iterations.

### Why the first attempt was reverted

It discovered invalidity by calling `reportInvalidBranch` for every `BlockExecutionError` except
`MPTError(MissingNodeException)`. But missing contract code does NOT surface as that exception —
`InMemoryWorldStateProxy.getCode` returns `ByteString.empty` rather than throwing, so the failure
arrives as `ValidationAfterExecError("Block has invalid gas used")`. `BlockImporter.scala:518-545`
treats that exact string as **locally recoverable**: it fetches the bytecode over SNAP
`GetByteCodes` and re-imports the same block, which becomes canonical.

The registry had already marked it invalid, and its `pruneAtOrBelow` was a structurally dead
no-op — the predicate re-reads a header every insertion path has already deleted. Net effect: the
node permanently refuses its own honest canonical chain, on any network including ETC, with no
recovery short of a process restart. Pre-change, that trigger caused only a peer-blacklist strike.

**Any future attempt must distinguish "this block is consensus-invalid" from "I could not execute
this block right now".** They are not the same and the error types do not separate them today.

### A pre-existing coverage ceiling — CLOSED by b4cdc30

*The section below is the original finding, left as written. It was correct, and it is now
fixed.* `BlockExecution.executeAndValidateBlocks` passed `alreadyValidated = true`, which at
`BlockExecution.scala:53` skipped `validateBlockBeforeExecution` — the only caller of
`blockHeaderValidator.validate` **and** of `ommersValidator.validate` on this path.

Measured before the fix, by `BlockExecutionPreValidationSpec`: a chain of three blocks that
executes cleanly, with one block's header validator returning `Left(HeaderDifficultyError)`,
imported as `executed 3 of 3 blocks, error=None`. Same for a block whose ommer validator
returned `Left(OmmersLengthError)`. After: execution stops at the offending block with a
`ValidationBeforeExecError`.

Scope, corrected from an earlier claim in this effort: the Engine API does **not** come through
here. `newPayload` has its own call site at `EngineApiService.scala:355`. The fix therefore
changes the p2p regular-sync path and the testmode RPC path only, and cannot alter any
engine-api response status or `validationError` string.

Chesterton's fence: `alreadyValidated = true` was correct in the original Mantis, where blocks
imported one at a time through a path that always validated first. `6ad1dec` introduced the
bulk `evaluateBranch` (a bare pass-through that never pre-validates) and `e168554` added the
extends-best skip; the flag outlived the guarantee it depended on. The `ConsensusAdapter`
comment the section below calls out is exactly what made it invisible — it was aspirational
when written and false after those two refactors.

### The original finding

`StdValidators.validateBlockAfterExecution` checks only `gasUsed` and `stateRoot`. The batch
import path `BlockFetcher` uses (`tryImportBlocks -> evaluateBranch -> forwardAndTranslateConsensusResult`)
never calls `doBlockPreValidation` — that happens only on the single-block `MinedBlock` /
`ImportNewBlock` path. There is no parent-relative gasLimit-bound or timestamp check anywhere on
the p2p import path, and `ConsensusAdapter` carries a comment asserting the opposite.

So a tampered `gasLimit` or `timestamp` ancestor imports **cleanly** and becomes canonical, and
`newPayload(tip)` would answer VALID — hive's instant hard-fail ("Client returned VALID on an
invalid chain"). Execution-level invalidities (stateRoot, gasUsed, receiptsRoot) are detectable;
header-relative ones are not, until that gap is closed. This bounds how much of the 48 any
discovery mechanism can reach.

## What is NOT established

* ~~Whether fixing A clears all 27 devp2p tests or merely uncovers a third layer.~~ Settled by
  the `b1f2db0` run: it cleared the fork-id bucket entirely, and a third layer was indeed
  underneath for 12 of them. Both halves of that caution turned out to be warranted.
* Whether the 7 tx-pool and blob failures in H share one root cause or are several.
* Whether B's fix needs sites beyond the two named. The 32 failures prove the fork gate is
  reached first; they do not prove it is the only broken comparison.
* Whether the 3 devp2p `exit status 1` harness failures are fukuii's at all.
* **Whether `sync` is fixed. IT IS NOT — and both commits I suspected are CLEARED.**

  Settled from both runs' artifacts. `git diff fc713a9 3bbc861 -- StdNode.scala` and
  `-- hive/fukuii/Dockerfile` are **empty**: the Await-bind and HIVE_CHECK_LIVE_PORT=8545 are
  byte-identical between the green run and the red one. `9501d25` only wraps the two
  `entity(as[...])` alternatives inside an ALREADY-BOUND route, so it cannot produce "nothing
  listening", and the failure is a TCP `connection refused` 261ms in with no 5s gap anywhere.
  `d288bfb` is cleared from the wire, not by assumption: every PEER_HANDSHAKE_SUCCESS in both
  runs reads `cap=ETH69`, ETH70 appears nowhere, so that branch is unreached.

  The real delta is ONE test, `sync fukuii from go-ethereum`, flipping with the
  pre-607d61d signature: `dial tcp :8545: connect: connection refused`, first attempt, no
  retry, against a node whose own log shows a healthy ETH69 handshake and two answered Engine
  API calls immediately before. hive-sync.yml's own comments flag this specific test as the
  suite's most fragile under CPU contention during concurrent JVM warmup on a 2-core runner.
  Whether this occurrence is that contention class or other jitter is **NOT ESTABLISHED** —
  the artifact carries no scheduler telemetry.

  So `607d61d` removed a real mechanism and did not make the suite deterministic. Both
  statements are true and neither implies the other.

  Note for future investigations: the SYNC artifacts DO contain
  `workspace/logs/fukuii/client-*.log`. The engine artifact did not. That asymmetry is why
  the 48-test "never reports vs reports too late" question stayed open, and it may be
  answerable from a suite whose artifact keeps client logs.
  `607d61d` removed a mechanism that provably produced the observed failure — that part still
  holds and the evidence for it is below. But the suite went GREEN on `fc713a9` and RED again
  on `3bbc861`, so one green run was never sufficient evidence for "fixed", least of all for a
  suite whose entire history is intermittency (the prior pattern was green, green, red, green,
  red). Either a later commit broke it — `9501d25` wrapped the JSON-RPC POST route in
  `toStrictEntity`, and the sync sim polls eth_getBlockByNumber on 8545, so it is on the path —
  or `607d61d` removed one of several mechanisms. Under investigation from the artifact.
  Treat sync as 1 failing until two consecutive greens, not one.

  The mechanism `607d61d` removed, which remains correctly diagnosed: From the run's own simulator log:
  `error getting block from fukuii (5ee8d3aa): Post "http://172.17.0.5:8545": dial tcp
  172.17.0.5:8545: connect: connection refused`, 277ms after the container started, against a
  node whose stdout shows a clean startup — ETH69 handshake with the geth peer succeeded,
  Engine API up on 8551, `newPayload #3000` accepted, CL beacon head received. Nothing was
  listening on 8545 yet. `JsonRpcHttpServer.run()` only *requested* the bind and returned
  `Unit`, while `startEngineApiServer` **awaits** its binding — so 8551 was guaranteed up on
  return and 8545 was not, and `HIVE_CHECK_LIVE_PORT=8551` declared readiness at the earliest
  possible instant. Hive hard-fails that connection refusal rather than retrying inside
  `--client.checktimelimit`, so the result was decided by a sub-second race.

  Two prior fixes each created the other's symptom: binding 8551 first (StdNode) fixed hive
  declaring readiness on 8545 while `engine_newPayloadV3` hit an unbound 8551; moving the probe
  to 8551 (Dockerfile) was the same bug from the other side, and its written premise —
  "fukuii's Engine API server binds AFTER JSON-RPC HTTP" — had by then been inverted by the
  StdNode change. `607d61d` awaits both bindings and returns the probe to 8545, which is
  correct *because* of the await: 8545 accepting a connection implies 8551 already does.

  **Two suspect commits were wrongly accused before CI history was checked.** `b91d3fd`'s own
  run has `sync` PASSING, and `e788857` has no check-runs or workflow runs at all — it was
  never built, superseded before Actions triggered. The "three consecutive reds after three
  suspect commits" pattern does not survive contact with the data.
* Whether fukuii's proposer had other inline consensus-formula copies that have drifted.
  `a058080` found one: `EngineApiService.scala:669-681` carried an EIP-1559 copy opening with
  `if parent.header.number == BlockNumber.Zero then parentBaseFee`, a genesis special case
  neither `BaseFeeCalculator.calcBaseFee` nor go-ethereum has (the London exemption is keyed on
  `olympiaBlockNumber`). On a London-at-genesis chain that made fukuii propose block 1 at
  1,000,000,000 where the rule gives 875,000,000, so every block 1 it produced was unacceptable
  to peers — invisible because nothing on any path recomputed it. `EngineApiService` carries
  further inline copies of the gas-limit bound (`:262-280`) and the blob-gas formula; whether
  those have drifted is NOT established.
* `consensus` suite health, which its unstable denominator makes unmeasurable as run today.
  That is a CI-integrity defect in its own right.

---

# Measurements on `6c8bc97` — and four root causes named

`6c8bc97` is the first commit whose hive jobs can report their own counts (it moved the
`$GITHUB_OUTPUT` emission ahead of the reporting block). Every figure below comes from the
uploaded artifact's `summaryResult.pass`, not from the gate.

| suite | passed | failed | total | gate verdict |
|---|---:|---:|---:|---|
| rpc-compat | 241 | **6** | 247 | RED |
| graphql | 50 | 2 | 52 | RED |
| devp2p | 44 | 18 | 62 | RED |
| sync | 17 | **1** | 18 | **GREEN — and the green is hiding the failure** |
| smoke-genesis, smoke-network | — | 0 | — | GREEN |

rpc-compat has now held at 6 across three consecutive samples. graphql and devp2p are
unmoved, which is what the inertness oracle is for.

## sync: still one real failure, still hidden by the gate

The gate printed `Hive sync gate passed: 17/18` with `GATE_FAILED: 0`. The artifact says
otherwise: the one failing test is **`sync go-ethereum from fukuii`** — a fukuii-matching
test suppressed by `hive-sync.yml`'s `gate_exclude`. A green check here still means a real
defect.

What did change: the *other* waived test, `sync fukuii from nethermind`, now **passes**. The
suite went from 2 failures to 1 without the gate being able to say so.

The remaining failure is fukuii-as-SERVER. From its detail log, the harness drives geth (the
sink) to block 0xbb8 = 3000 via `engine_newPayloadV3` / `engine_forkchoiceUpdatedV3`; geth
answers `SYNCING` to both and then `sync failed: timeout (1m0s elapsed, current head is 0)`.
geth never leaves head 0, i.e. it got nothing from fukuii over p2p. See root cause B below —
fukuii disconnects snap peers.

## Root cause A — `debug_trace*` returns no `result` at all

`StructLogTracer.getResult` is `JNothing` (`vm/StructLogTracer.scala:111`). Its scaladoc
claims the response "is built by DebugTracingJsonMethodsImplicits using
getSteps/gas/failed/returnValue". **That comment is false**:
`DebugTracingJsonMethodsImplicits.debug_traceTransaction.encodeJson(t) = t.result`, and
`t.result` *is* `tracer.getResult`. Nothing reads `getSteps`. `setResult` (line 98) has zero
callers anywhere in src/main or src/test, while `ExecutionTracer.onTxEnd` — which
`StxLedger.simulateTransactionWithTracer` already calls with exactly the right gas/return/error
— is not overridden. The whole result half of the tracer is unwired.

Measured: the node answers `{"jsonrpc":"2.0","id":1}` and the harness reports
`unable to parse result: unexpected end of JSON input`. Costs
`debug_traceTransaction/trace-contract-call` and `/trace-legacy-transfer`.

StructLogTracer is the default for any request with no `tracer` name, which is what all four
tracer tests send — so this breaks every default-tracer trace call, not just these two.

## Root cause A2 — `traceAllTxsInBlock` is O(n²), and A3 is hiding behind it

`DebugTracingService.traceAllTxsInBlock` calls `advanceWorldToTx(..., txIndex, parentStateRoot)`
inside `stxs.zipWithIndex.map`, and `advanceWorldToTx` does
`(0 until txIndex).foldLeft(world0)` — it re-executes the whole prefix from the parent root on
every index. Block 0x2 of this fixture carries **~62 transactions**, most of them contract
creations with gasLimit 1,628,061: that is 1,891 redundant executions. Measured result is
`context deadline exceeded (Client.Timeout exceeded while awaiting headers)` on both
`debug_traceBlockByNumber` tests.

**A3, currently masked by A2:** the hive schema
(`rpc-compat/testdata/openrpc-tracer.json`) requires every `debug_traceBlockByNumber` array
item to have BOTH `txHash` and `result`. fukuii's encoder emits bare tracer results with no
envelope. Fixing only the timeout would move these two tests from timeout to schema failure,
not to green.

## Root cause B — the devp2p snap offset mismatch is structural, not a fukuii bug

go-ethereum master `cmd/devp2p/internal/ethtest/protocol.go:34-40` **hardcodes**:

    baseProtoLen = 16
    ethProtoLen  = 22
    snapProtoLen = 10

so the harness always places snap at wire offset 38 regardless of which eth version it
negotiated. Only **eth/72** has protocolLength 22 (`eth/protocols/eth/protocol.go:49`:
`{ETH69: 18, ETH70: 18, ETH71: 20, ETH72: 22}`), so the harness effectively assumes an
eth/72 peer.

fukuii advertises eth/68 + eth/69, putting its snap base at 16+18 = 34. The harness's
GetByteCodes (snap 0x04) therefore arrives at wire 42 and GetTrieNodes (0x06) at wire 44.
fukuii's own client log says exactly that:

    Error: Unknown snap/1 message type: 42
    Error: Unknown snap/1 message type: 44
    Cannot decode GetByteCodes. Expected RLPList[3] ...   (17 occurrences)
    Cannot decode GetTrieNodes. Expected RLPList[4] ...   (5 occurrences)

each followed by `DECODE_ERROR ... - disconnecting`. fukuii's `ethWireSizeFor`
(`RLPxConnectionHandler.scala:126`) is **correct** and was verified against geth's table —
the mismatch is the harness's fixed assumption, and the only way to satisfy it is to reach
eth/72. Accounts for `AccountRange`, `GetByteCodes`, `GetStorageRanges`, `GetTrieNodes` plus
one client-launch failure.

Four more — `Status`, `TrieNodesRemoved`, and two `GetBlockAccessLists` — fail earlier, at
`could not negotiate snap protocol (remote caps: [eth/68 eth/69 snap/1], local snap version: 2)`:
fukuii offers snap/1, the harness requires snap/2 (EIP-8189, which adds
`GetBlockAccessLists=0x08` / `BlockAccessLists=0x09` and removes GetTrieNodes).

## Root cause C — fukuii sends an unsolicited BlockRangeUpdate after every eth/69 handshake

`NetworkPeerManagerActor.scala:733-743` sends `ETH69.BlockRangeUpdate` immediately after
every eth/69 handshake. fukuii's own scaladoc (`ETH69.scala:151`) says it is
"Sent when peer's available block range changes" — EIP-7642 defines a change notification,
and geth emits it only on change.

geth's harness `conn.go:185` computes `code -= baseProtoLen`, then switches on the
eth-relative code; its case list ends at `default: panic("unhandled eth msg code %d")` with
**no case for BlockRangeUpdate (17)**. fukuii sends BRU at wire 33; the harness reads 17 and
panics. Measured: `panic: unhandled eth msg code 17` in `Transaction`, `InvalidTxs`,
`LargeTxRequest`. fukuii's log shows `ETH69_BRU_POST_HANDSHAKE` firing 40× in one container.

Four further tx-flow tests (`NewPooledTxs`, `BlobViolations`, `TestBlobTxWithoutSidecar`,
`TestBlobTxWithMismatchedSidecar`) fail with "disconnect" in the same flow. Same cause is a
**hypothesis, not a measurement** — recorded as such.

## Root cause D — EIP-7594 blob sidecars are rejected

`eth_sendRawTransaction/send-blob-tx` answers `-32600`. Decoding the 137,725-byte payload the
harness actually sent gives

    0x03 || rlp([ tx_payload(14), 0x01, [1 blob 131072B], [1 commitment 48B], [128 proofs 48B] ])

— **5** wrapper elements, a version byte, and 128 *cell* proofs: the EIP-7594 (PeerDAS)
sidecar form. `ETHPackets.toSignedTransactionWithSidecar` accepts only
`outer.items.size == 4` (legacy EIP-4844); the 5-element wrapper falls through to a branch
that reads the whole wrapper as a 14-field tx body, throws, and is swallowed by
`EthTxService.scala:276`'s `case Failure(_) => InvalidRequest`. That bare `Failure(_)` is also
why a decode error presented as a meaningless JSON-RPC envelope error.

## Root cause E — `eth_config` (EIP-7910) is structurally wrong

Exact-comparison test, six deviations: `activationBlock` hex instead of `activationTime`
decimal; `blobSchedule` and `forkId` absent; precompiles keyed by geth-internal camelCase
instead of canonical UPPER_SNAKE (and `KZG_POINT_EVALUATION` missing entirely);
`systemContracts` carrying 1 of 5 entries under the wrong name; and `next`/`last` fabricating
fork objects with a `0xde0b6b3a7640000` sentinel where the spec wants `null`.

---

## engine on `6c8bc97` — 325 / 78 / 403

| suite | commit | passed | failed | total |
|---|---|---:|---:|---:|
| engine | fc713a9 | 306 | 97 | 403 |
| engine | **6c8bc97** | **325** | **78** | **403** |

The denominator is identical, so the difference is real: **19 fewer failures.** This is
also the first engine run that reported its own counts — the tabulation-ordering fix
(`6c8bc97`) worked, and the failure breakdown is reachable from the log tail.

**What this run cannot tell us.** It carries two changes at once — the `-32700` batch fix
and the invalid-chain reporting channel (`5779126`) — so the 19 cannot be split between
them from the count alone. Attributing *which* 19 cleared would need `fc713a9`'s failing-test
list to diff against, which this file does not record. The 19 is therefore a real
improvement of unknown composition, not 19 tests we can name.

### A prediction that was wrong, recorded as such

Before the run I wrote: "`CanonicalReOrg=False` variants clearing confirms the reporting
channel. If `=True` variants also clear, my model of that topology is wrong and I'll say
so."

Neither happened. **Both variants are still failing**, e.g.

    Invalid Missing Ancestor Syncing ReOrg, Timestamp,     EmptyTxs=False, CanonicalReOrg=False, Invalid P8 (Paris, Cancun)
    Invalid Missing Ancestor Syncing ReOrg, ReceiptsRoot,  EmptyTxs=False, CanonicalReOrg=False, Invalid P8 (Paris, Cancun)
    Invalid Missing Ancestor Syncing ReOrg, Incomplete Transactions, EmptyTxs=False, CanonicalReOrg=False, Invalid P9 (Paris)

So the reporting channel did not clear the `=False` family, which is what I expected it to
do. The failure mode I named as the falsifier (`=True` clearing too) is not what occurred
either — the prediction simply missed. `5779126` may still be doing something useful; this
run does not show it, and the honest reading is that the invalid-ancestor family is not
explained by the reporting gap alone.

### New signal worth acting on

Two entries not previously prominent:

    Request Blob Pooled Transactions Single   (Cancun)
    Request Blob Pooled Transactions Multiple (Cancun)

These are **pooled blob transactions**, which is exactly the surface of the still-open
four-element wrapper gate in `ETHPackets.toPooledTransactions` (lines 1066 and 1077, where
`isNetworkWrapped` requires `inner.items.size == 4`). A correctly formed EIP-7594 sidecar
from a peer is currently classified as ABSENT rather than malformed. That fix was scoped
for three devp2p tests; these two suggest it reaches the engine suite as well.

Also present and not yet diagnosed: four `Withdrawals Fork on ... Re-Org Sync (Paris)`
failures.

Note the histogram printed in the log is truncated (`head -25` / `head -30` in the
tabulate step), so the names above are the top slice of 78, not the whole set. The full
list is in the `hive-logs-engine` artifact.

---

## The 34 invalid-ancestor failures, characterised — and a correction to "zero INVALID"

### Correcting a figure recorded earlier in this effort

An earlier finding in this effort was carried forward as "the node never reports INVALID,
across all 48 tests" and repeated in summary as an established fact. On `6c8bc97` the
engine run's own detail logs say otherwise:

| status reply | count (whole engine suite, 403 tests) |
|---|---:|
| VALID | 11,116 |
| SYNCING | 2,678 |
| ACCEPTED | 2,608 |
| **INVALID** | **469** |
| INVALID_BLOCK_HASH | 0 |

fukuii answers INVALID 469 times. The original measurement was scoped to the 48
timeout-family tests, not the suite — so the two figures have **different denominators and
were never comparable**. Restating the subset result as a property of the node was the
error, and it is exactly the denominator mistake this file's opening rule exists to
prevent. The reporting path is not globally dead.

### What the 34 actually are

`Timeout waiting for main client to detect invalid chain` appears exactly **34** times,
matching the 34 invalid-ancestor failures one for one. The family is characterised by that
single message, and the measured exchange is always the same shape:

    >> engine_newPayloadV1   (payload whose ANCESTOR is invalid)
    << {"status":"ACCEPTED","latestValidHash":null,"validationError":null}
    >> engine_forkchoiceUpdatedV1  (head = that payload)
    << {"payloadStatus":{"status":"SYNCING",...},"payloadId":null}
    FAIL: Timeout waiting for main client to detect invalid chain

ACCEPTED then SYNCING is a legitimate first answer — the ancestor genuinely is missing. The
failure is that nothing INVALID ever follows.

### Why 5779126 does not cover this, stated as hypothesis

`InvalidChainReporter` classifies an import failure and reports it. It is downstream of an
import. In this family the node answers SYNCING and then, apparently, never executes the
side branch at all — so no import fails, so there is nothing to classify and nothing to
report. The reporting channel was wired up correctly; the message it exists to carry is
never generated.

That is consistent with the separately-recorded gap where `importToNewBranch` never
executes a lighter side branch, and it explains why BOTH `CanonicalReOrg=True` (24) and
`=False` (10) variants persist — the distinction does not matter if neither branch is
executed.

**This is a hypothesis fitting the evidence, not a measurement.** What is measured: the
exchange above, the 34/34 correspondence, and 469 INVALIDs elsewhere in the same run. What
is not: that the side branch is never executed. Confirming that needs the branch-import
path instrumented or a targeted unit reproduction, and it is the single largest remaining
lever in the engine suite.

---

## The remaining 14 engine failures, triaged from their own detail logs

Everything below is the harness's own FAIL line, read from the `hive-logs-engine`
artifact for `6c8bc97`. With the 34 invalid-ancestor and 30 fork-id families already
characterised, this accounts for all 78.

| n | family | harness's FAIL line | reading |
|--:|---|---|---|
| 4 | Withdrawals Fork on ... Re-Org Sync | `Timeout waiting for sync`, after `SYNCING` | **same shape as the 34** |
| 5 | Blob Transaction Ordering / On Block 1 | `Error verifying blob bundle: expected N blob, got M` | blob-bundle assembly |
| 2 | Request Blob Pooled Transactions | `invalid message code: 33` | **BlockRangeUpdate** |
| 1 | GetPayloadBodiesByRange (Sidechain) | `withdrawal 1 not equal: want=…` | withdrawal content |
| 1 | ForkchoiceUpdatedV3, Null Beacon Root | `Expected error on EngineForkchoiceUpdatedV3` | missing rejection |
| 1 | In-Order Consecutive Payload Execution | (no FAIL line in tail) | not yet characterised |

### Correcting an attribution I made two commits ago

The two `Request Blob Pooled Transactions` failures were recorded — in a commit message
and in a task brief — as evidence for the four-element wrapper gate at
`ETHPackets.toPooledTransactions`. **That was wrong.** Their FAIL line is

    Error executing step 2: error waiting for response: invalid message code: 33

and 33 = 0x21 = `baseProtoLen`(16) + eth-relative 17 = **BlockRangeUpdate**. These are the
eager post-handshake BRU send already removed in `cd56c7e`, surfacing as a raw wire code
rather than as the devp2p harness's `unhandled eth msg code 17` panic.

That correction propagates. The three devp2p blob tests (`TestBlobTxWithoutSidecar`,
`TestBlobTxWithMismatchedSidecar`, `BlobViolations`) fail with "failed to read
GetPooledTransactions message: disconnect" and "i/o timeout" — downstream symptoms of a
harness that died mid-exchange. If that death is the BRU, those three may already be green
from `cd56c7e`, and they are no longer good evidence for the wrapper fix. The wrapper fix
remains correct on its own merits (a valid EIP-7594 sidecar being classified as ABSENT is
a real defect), but it should not claim those tests until measured.

### The sync family is probably 38, not 34

The four `Withdrawals Fork ... Re-Org Sync` failures end in `Timeout waiting for sync`
after the node answers `SYNCING` — the same stuck-in-SYNCING shape as the 34
invalid-ancestor tests, which end in `Timeout waiting for main client to detect invalid
chain` from the same state. Both are re-org/side-branch scenarios where the node
acknowledges the head and then never makes progress. If the side-branch execution gap is
the cause, it is worth **38 engine tests**, not 34.

Stated as a shape match, not a proven shared cause.

### Two small, self-contained ones

- **`ForkchoiceUpdatedV3 ... Null Beacon Root`** — fukuii answers `VALID` with a payload
  id where the harness expects an *error*. FCUv3 with Shanghai payload attributes and a
  null beacon root must be rejected; fukuii accepts it. A missing validation, not a
  behavioural bug.
- **`GetPayloadBodiesByRange (Sidechain)`** — `withdrawal 1 not equal`, i.e. the returned
  payload body carries different withdrawal content than the block it names.

### Five blob-bundle count mismatches

`expected 0 blob, got 6`, `expected 5 blob, got 6`, and `expected 6 blob, got 5`. The node
returns the wrong number of blobs in the `getPayload` bundle — including returning 6 when
none were expected. This is bundle assembly, unrelated to the EIP-7594 sidecar decode
fixed in `9bc7fb4`, and the "got 5 where 6 expected" case shows it is not simply
off-by-one in one direction.

---

## A verified defect: `importToNewBranch` can never select a side branch on a post-merge chain

This is code-level and checkable by reading two files; it is recorded separately from the
question of which tests it explains.

`ConsensusImpl.importToNewBranch`:

```scala
blockchainReader.getChainWeightByHash(parentHash) match
  case Some(parentWeight) =>
    if newBranchWeight(branch, parentWeight) > currentBestBlockWeight then
      reorganise(currentBestBlockNumber, branch, parentWeight, parentHash)
    else KeptCurrentBestBranch          // no execution, no reporting
  case None => ConsensusError(...)      // no execution either
```

`newBranchWeight` folds `ChainWeight.increase`, and `ChainWeight.increase` is:

```scala
def increase(header: BlockHeader): ChainWeight =
  ChainWeight(totalDifficulty + header.difficulty)
```

**Post-merge every block has `difficulty == 0`.** So `newBranchWeight(branch, parentWeight)`
equals `parentWeight` exactly, for a branch of any length. A side branch by definition forks
at or below the current head, so `parentWeight <= currentBestBlockWeight`, and the guard
`newBranchWeight > currentBestBlockWeight` is therefore **never satisfiable on a PoS chain**.

Every side branch on an Engine-API chain takes `else KeptCurrentBestBranch` and is silently
dropped without being executed. Chain weight is a proof-of-work selection rule; after the
merge it is frozen, and post-merge head selection is the consensus layer's decision
delivered by `forkchoiceUpdated`, not something the execution layer derives from difficulty.

`reportIfProvenInvalid` is called from `importToTop` (two arms) and from `reorganise`'s
failure arm. It is **not** called on the `KeptCurrentBestBranch` path — consistent, since
nothing executed, so nothing failed to report.

ETC is unaffected and must stay that way: PoW blocks carry real difficulty, the comparison
is meaningful there, and this is the correct rule for a PoW chain.

### What this does and does not explain

**Not established:** that this is the code path the 34 invalid-ancestor and 4
Withdrawals-Re-Org-Sync failures traverse. `engine_newPayload` does not reach
`ConsensusImpl` at all — it calls `blockExecution.executeAndValidateBlockFull` directly,
gated on `parentKnown && parentValidated`, and on an unknown parent it stores the block with
`storeBlockByHashOnly` and answers ACCEPTED. That first answer is correct. What follows —
how the missing ancestors are backfilled, and whether that backfill reaches
`BlockImporter` → `ConsensusImpl.evaluateBranch` → `importToNewBranch` — was not traced.

So there are two candidate explanations for the 38 and they are not the same fix:

1. the backfill reaches `importToNewBranch` and is dropped by the weight guard above; or
2. no backfill is ever requested, and the node simply sits on the stored-by-hash block.

Distinguishing them needs the branch-import path instrumented on one of these fixtures.
Whichever it is, the weight guard is independently wrong for PoS and worth fixing on its
own merits — but it should not be credited with the 38 until measured.

---

## The fork-id defect was an ETH mainnet peering failure, independently verified

The missing Arrow Glacier / Gray Glacier fork blocks were found through hive's rpc-compat
fixture, but they are not a fixture artifact. `src/main/resources/conf/base/chains/eth-chain.conf`
declared neither, so the defect applied to **ETH mainnet**.

Verified by recomputing EIP-2124 from the canonical mainnet genesis hash
`d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3` and the canonical fork
schedule, rather than by trusting the figure:

```python
h = crc32(genesis)
for f in forks: h = crc32(uint64_be(f), h)
```

with block forks `[1150000, 1920000, 2463000, 2675000, 4370000, 7280000, 9069000, 9200000,
12244000, 12965000, (13773000, 15050000)]` and timestamp forks `[1681338455, 1710338135,
1746612311]` (Shanghai, Cancun, Prague):

| fork list | fork id at a Prague head |
|---|---|
| **with** Arrow Glacier + Gray Glacier | **`0xc376cf8b`** — the real mainnet value |
| **without** them | **`0x8e91a3e4`** — what fukuii advertised |

`0xc376cf8b` is ETH mainnet's published Prague fork id. Reproducing it from the canonical
genesis and schedule, and reproducing the wrong value from fukuii's schedule, fixes both
ends of the claim.

**Consequence:** on ETH mainnet, at any head past Arrow Glacier (block 13,773,000,
December 2021), fukuii advertised a fork id no peer agrees with, and the ETH status
exchange is rejected with `wrong fork ID in status`. That is not degraded peering; it is
no peering at all, for roughly four years of chain history.

Sepolia declares neither fork and is unaffected. ETC declares neither and is unaffected.

Worth noting how this was found. Nothing in the repository asserted the *value* of a
computed fork id for any chain — the arithmetic was exercised only indirectly, so a missing
input produced a confidently wrong answer that no test could see. The hive fixture caught it
by accident, from a different direction, while the production chain carried the same defect
silently. The regression tests added alongside the fix pin the value for the fixture chain
AND for mainnet, and pin the wrong value for the without-glaciers config so the fix is shown
to be causal.

---

## Corrections from the fork-id work, and a devp2p cause measured at last

### The mainnet fork id was wrong for TWO independent reasons, and I reported the wrong value

`a858f7c` was described in its own commit message as adding the two glacier forks. It also
flipped `include-on-fork-id-list` from `false` to `true` on `eth-chain.conf`, which that
message never mentions. That second change is a separate defect: the DAO fork block
1,920,000 was being excluded from ETH mainnet's fork list.

The flag is **correct for ETC** — ETC rejected the DAO bailout, so 1,920,000 is not a fork
transition there — and `eth-chain.conf` inherited it from the ETC config lineage.
go-ethereum's mainnet config sets `DAOForkSupport: true`. `etc-chain.conf` still reads
`false` and must keep doing so.

So the earlier claim in this file and in `9d1bf8b`/`a858f7c` — that fukuii advertised
`0x8e91a3e4` — **was wrong**. That is the value for a config missing the glaciers but
*having* DAO. Recomputed across all four states from the canonical mainnet genesis:

| config state | fork id at a Prague head |
|---|---:|
| DAO + both glaciers (correct) | `0xc376cf8b` |
| DAO, no glaciers | `0x8e91a3e4` ← what was previously reported |
| glaciers, no DAO | `0xce126fe9` |
| **neither — what fukuii actually shipped** | **`0x0f91ba49`** |

The conclusion is unchanged and if anything stronger: fukuii could not peer on ETH mainnet.
The specific number was wrong because the earlier check assumed a fork list the shipped
config did not have — verifying the *fix* without verifying the *starting state*.

Corroboration that the corrected list is right, reproduced independently here: at a
Shanghai head it yields `0xdce96c2d` and at a Cancun head `0x9f3d2254`, both published
mainnet values, and both require the DAO block **and** both glaciers.

### The 30 engine `Fork ID:` failures are NOT explained by that fix

Checked against the artifact's have/want pairs rather than assumed. Three distinct causes,
none of them the enumeration gap:

- **12 × `Genesis=0, Cancun=*`** — wall-clock substitution.
  `EthNodeStatus68ExchangeState.scala:73` and `:177`, and
  `EthNodeStatus69ExchangeState.scala:151`, all do
  `if storedTimestamp == Timestamp.Zero then Timestamp(System.currentTimeMillis()/1000)`.
  On a chain whose genesis timestamp is legitimately 0, the head timestamp *is* 0, so
  fukuii substitutes wall-clock and every timestamp fork looks passed. Measured:
  `have=0x237d1525 next=0` vs `want=0xc8014e7d next=1` — `next=0` is the tell.
- **12 × `Genesis=1, Cancun=*`** — wrong filter rule. `ForkId.scala:105` ends
  `.flatten.filterNot(_ == 0).distinct.sorted`; geth filters `<= genesisTimestamp`. With
  genesis ts 1 and Cancun at 1, geth skips Cancun and fukuii accumulates it.
  `ForkId.create` takes no genesis timestamp, so this needs an API change threaded to the
  handshake call sites.
- **6 × `Paris=*`** — `invalid message code: 33`, i.e. the BlockRangeUpdate bug, already
  fixed in `cd56c7e`.

The glaciers are irrelevant to all 30: the engine simulator's genesis puts every block fork
at 0, so they filter out as genesis ruleset.

### The sync `peercount=0` fork-id hypothesis is ruled out

`hive/simulators/ethereum/sync/chain/genesis.json` puts every block fork at 0 — glaciers and
mergeNetsplit included — and both `shanghaiTime` and `cancunTime` at 0, with genesis
timestamp `0x0`. The fork list is therefore **empty for both clients**: block forks at 0
filter as genesis ruleset, and timestamp forks at 0 filter under both geth's
`<= genesis` rule and fukuii's `== 0` rule. Both sides compute bare `CRC32(genesis)` with
`next=0`, and no head timestamp changes that. Fork id cannot explain that failure. The
mechanism floated earlier in this effort is dead; the cause is elsewhere.

### Four devp2p failures now have a measured cause: the eth/72 announcement shape

Predicted from harness source, then confirmed against fukuii's own client log from the
`6c8bc97` devp2p run:

    DECODE_ERROR: Cannot decode message ... - disconnecting. Error: src is not an RLPValue

**Exactly 4** occurrences, all in the container running the eth tests, each immediately
after `ETH69_STATUS: ForkId validation passed`. Exactly 4 tests fail in that flow:
`NewPooledTxs`, `BlobViolations`, `TestBlobTxWithoutSidecar`, `TestBlobTxWithMismatchedSidecar`.

The chain, closed in source:

- go-ethereum's `testBadBlobTx` and `TestBlobViolations` construct
  `NewPooledTransactionHashesPacket72{Types, Sizes, Hashes, Mask}` — 4 fields, including the
  PeerDAS `Mask` — and send it **with no `negotiatedProtoVersion` gate**, unlike the
  `eth72Supported()`-gated tests beside them.
- `ETHPackets.toNewPooledTransactionHashes`'s typed case is
  `RLPList(RLPValue(typesBytes), sizesList: RLPList, hashesList: RLPList)` — exactly 3.
- A 4-element packet falls to `case rlpList: RLPList => fromRlpList[ByteString](rlpList)`,
  which hits the nested `sizes` list where a scalar is required, and
  `rlp/.../RLPImplicits.scala:23` throws `RLPException("src is not an RLPValue")` — the
  exact string in the log.

So these four are neither the wrapper gate nor the BRU. Both earlier attributions were
wrong. fukuii caps at ETH70 and is being handed a message for a protocol it never
negotiated, so rejecting it is arguably correct — which would place these four in the same
bucket as the snap-offset failures: not reachable without eth/71-72 support.

Separately flagged: that generic fallback branch fabricates `types` and `sizes` as
`Seq.fill(hashes.size)(0)` — inventing protocol data it does not have. On an input it can
coerce it would succeed with fabricated metadata instead of failing. That is a
silent-fallback defect independent of eth/72.

---

## The `NewPooledTransactionHashes` fallback: Chesterton's Fence, resolved

The generic fallback branch in `ETHPackets.toNewPooledTransactionHashes` fabricates
`types` and `sizes` as `Seq.fill(hashes.size)(0)`. Before proposing to remove it, the
question was why it exists. It answers itself:

`src/test/scala/.../ETH68MessagesSpec.scala:41-57` is a test named

    "decoding NewPooledTransactionHashes in legacy ETH65 format" should
      "successfully decode and set default types and sizes"

It encodes a bare flat hash list — no types, no sizes, no wrapper — and asserts exactly
those fabricated zeros. The branch exists for **ETH65's pre-EIP-5793 announcement format**.

That field is gone. fukuii supports ETH68/69/70 only; ETH63–67 are removed. Confirmed
structurally, not just by policy: `HelloExchangeState.scala` dispatches negotiation with
cases for `Capability.ETH69` (line 74) and `Capability.ETH68` (line 88) and nothing else —
`case _ =>` (line 100) is `DisconnectedState(IncompatibleP2pProtocolVersion)`. There is no
connection state in which a genuine ETH65 peer reaches this decoder.

So the branch cannot fire for the reason it was written. It can only fire on data matching
no format fukuii understands — which is precisely the eth/72 case that costs four devp2p
tests. On that input it happens to throw (the nested `sizes` list will not coerce to
`ByteString`), so removing it changes nothing for those four. On some other shape that
*does* fully coerce, today's code would silently succeed with invented protocol metadata,
handing tx-pool prioritisation and blob-tx type handling data the peer never sent.

A hard failure is both safer and more honest about attribution. The current log line is
`src is not an RLPValue` — an RLP-internals detail. The protocol-level statement is
"expected the 3-field typed form (ETH68+), got an N-element list".

**When this is taken, the ETH65 test must go or be repurposed in the same change** (into
"any non-3-field shape is a decode failure"), or it becomes a red test pinning removed
behaviour. ~17 lines.

## Those four devp2p failures: correctly blocked, and cheaper to unblock than "eth/72"

Disconnecting is the **right** behaviour, for a structural reason rather than a practical
one. devp2p binds message shape to the negotiated protocol version, not to sniffing bytes.
ETH68's and ETH72's `NewPooledTransactionHashesMsg` share a wire code and carry two
different version-defined shapes; a client knows which to expect from the handshake. Having
negotiated ETH70-or-below, fukuii has agreed it will only receive ETH70-shaped messages on
that connection. A 4-field arrival is not ambiguous input to interpret — it is a message a
compliant peer would not send. There is no decode cleverness at ETH70 that yields the right
answer, because ETH70 has no right answer for that shape.

Two qualifications on urgency, neither on correctness:

1. **Partly a harness gap.** The same go-ethereum file gates other tests behind
   `eth72Supported()`, which skips cleanly when `negotiatedProtoVersion < ETH72`, but
   `testBadBlobTx` and `TestBlobViolations` build `NewPooledTransactionHashesPacket72`
   unconditionally. That is an inconsistency upstream may tighten, at which point these
   four become skips with no fukuii-side work. Not a promise — a reason not to record them
   as permanently blocked.
2. **The unlocking slice is smaller than full eth/72.** These four never touch
   `GetCells`/`Cells`, the PeerDAS/KZG cell-proof surface that made ETH72 the
   large, speculative tier in the earlier scoping. They need only (a) negotiating eth/72 at
   all and (b) a dedicated decoder for the 4-field packet, i.e. the `Capability` case
   objects plus the `ethWireSizeFor`/negotiation/handshake-dispatch plumbing already sized
   as the medium ETH71 tier, plus one message variant. If the goal is un-redding these four
   rather than protocol parity, that is a materially cheaper target.

---

## Measured on `9be2cfd` — the first run carrying the day's fixes

### rpc-compat: 6 → 8. A regression, and mine

239 / 8 / 247 — same denominator, so the move is real.

| method | on 6c8bc97 | on 9be2cfd |
|---|---:|---:|
| `eth_sendRawTransaction` | 1 | **0** |
| `debug_traceTransaction` | 2 | 1 |
| `debug_traceBlockByNumber` | 2 | **5** |
| `debug_traceBlockByHash` | 0 | **1** |
| `eth_config` | 1 | 1 |

The blob-sidecar fix worked and one tracer test cleared. But four tests that were
PASSING now fail, including a method that was not failing until its encoder was
touched.

They were passing **vacuously**. With `getResult` returning `JNothing`,
`debug_traceBlockBy*` emitted an empty array, which trivially satisfies "an array
whose items each carry txHash and result" — no items, nothing to check. Making the
response real is what exposed them.

Two causes, both traceable to decisions recorded in this file:

1. **Memory entries lack the `0x` prefix.** Measured:
   `jsonschema: '/0/result/structLogs/7/memory/0' ... does not match pattern
   '^0x[0-9a-f]{64}$'`. The brief that produced `ab3c063` asserted memory was
   un-prefixed and that the existing encoding was already correct. That was derived
   from go-ethereum's `%x` formatting rather than from the schema the harness
   enforces — the precise error this document repeatedly warns against, committed
   while warning against it.

2. **C7 was the root cause and was deliberately deferred.** The fixture sends
   `params: ["0x1"]` — no config object at all — and its expected output contains
   zero `memory` keys. go-ethereum defaults memory OFF. fukuii computes
   `enableMemory = !config.disableMemory`, and `disableMemory` is a field name no
   caller sends, so it stays false and memory is always ON. Logging C7 and scoping
   it out was a misjudgement: it produces both failure shapes, and a full memory
   snapshot per opcode across a 62-transaction block is also the leading explanation
   for the two remaining `context deadline exceeded` timeouts, which the O(n) fix
   did not clear.

### devp2p: 18 → 18, but the composition changed

The BRU fix had a real, measurable effect that the count hides:

- `ETH69_BRU_POST_HANDSHAKE`: **0 occurrences** (was 40 in one container). The eager
  send is genuinely gone.
- `panic: unhandled eth msg code 17`: **1 occurrence, down from 3**.
- `src is not an RLPValue`: **still exactly 4**, unchanged — that cause is untouched,
  as predicted.

But no test moved from red to green. Two of the three panics became a different
failure:

| test | on 6c8bc97 | on 9be2cfd |
|---|---|---|
| `Transaction` | panic code 17 | **still** panic code 17 |
| `LargeTxRequest` | panic code 17 | `failed to send txs: … i/o timeout` |
| `InvalidTxs` | panic code 17 | `failed to send txs: … i/o timeout` |
| `NewPooledTxs` | disconnect | disconnect |

So the eager BRU was masking a second defect: fukuii does not answer the tx
announcement, and the harness now waits and times out instead of dying. The fix was
necessary and not sufficient, which is different from "it did not work" and also
different from "it worked".

One BRU still reaches a peer — `Transaction` still panics on code 17 — so a
remaining sender is emitting it. `BlockBroadcast.broadcastBlock` and
`announceCanonicalHead` are the legitimate change-driven senders and were left
untouched by design. Worth noting the harness panics on **any** BlockRangeUpdate,
legitimate or not, since its `readEth` has no case for code 17 at all: a
correctly-behaving eth/69 client announcing a genuine range change would also kill
it. That places the residue in the same category as the eth/72 announcement shape —
correct behaviour the harness does not accept.

### graphql: still exactly 2

The inertness oracle held across changes to `BlockchainConfig`, fork-id computation,
both handshake states, `EthInfoService`, `ETHPackets`, `DebugTracingService`,
`StructLogTracer` and the RLP decode paths. It moved in neither direction.

---

## Measurement on `810d6d8` — rpc-compat 8 → 1, and what the last one was

| suite | start | `6c8bc97` | `9be2cfd` | `810d6d8` |
|---|---:|---:|---:|---:|
| rpc-compat | 19 | 6 | 8 | **1** (246 / 1 / 247) |
| graphql | 2 | 2 | 2 | 2 (same two tests) |

Every `debug_*` failure cleared, including both `context deadline exceeded` timeouts.
That settles the open question from the previous entry: the timeouts were the
always-on memory capture (C7), not residual O(n²) cost. The tracer work is done.

### The last rpc-compat failure was one field, and it reconstructs exactly

`eth_config/get-config` differed in a single key. Everything else — `activationTime`,
`blobSchedule`, `chainId`, all 18 precompiles, all 5 system contracts — matched.

```
forkId: -- "0xe54f18d6"   (fukuii)
        ++ "0xe272ecbe"   (expected)
```

Reconstructed from the fixture rather than reasoned about. The chain is
execution-apis `tests/genesis.json`: genesis hash
`44fd89d5…5b3d99`, genesis timestamp 0, block forks 3, 6, 9, 12, 15, 18, **21**, 24,
27, 30, 33, 36 and timestamp forks 390, 420, 450, 480, 510, 540. CRC32 accumulated
over that full list gives `0xe272ecbe`. Accumulated over the same list **with block 21
removed** it gives `0xe54f18d6` — fukuii's value, to the bit. No other single
omission reproduces it:

| fork list | checksum |
|---|---|
| full | `0xe272ecbe` ← expected |
| **minus muirGlacier (21)** | **`0xe54f18d6`** ← fukuii |
| minus mergeNetsplit (36) | `0xb22c635f` |
| minus arrowGlacier (30) | `0x11e136dc` |
| minus grayGlacier (33) | `0x6d87fac3` |
| minus bpo1 (510) | `0xecf4d81f` |

### Cause: a one-character gap between a shell script and a Go fixture

`hive/fukuii/fukuii.sh` read `HIVE_FORK_MUIRGLACIER`. hive exports
`HIVE_FORK_MUIR_GLACIER` — that is the name in execution-apis' `tests/forkenv.json`
(`"HIVE_FORK_MUIR_GLACIER": "21"`) and in hive's own
`clients/go-ethereum/mapper.jq`. The un-underscored spelling survives only in a stale
comment in hive's `geth.sh` and is never set by anything. So the read fell through to
the `$MAX` sentinel and Muir Glacier never entered the checksum chain.

`ForkIdHiveRpcCompatSpec` asserts `0xe272ecbe` for this chain and passed the entire
time the node was advertising `0xe54f18d6`. It asserts what `ForkId` does with a
config; nothing asserted that the adapter **builds** that config. That gap is now
closed by `HiveAdapterForkEnvSpec`, which reads `fukuii.sh` and checks both
directions: no env name the script reads that hive never exports, and no
block-numbered fork hive exports that the script never reads. Negative control run:
restoring the old spelling fails all three of its assertions.

Scope of the defect, measured not assumed: devp2p's fixture sets
`HIVE_FORK_MUIR_GLACIER=0`, and forks at block 0 are filtered from the checksum
anyway, so **devp2p was never affected**. This was worth exactly one rpc-compat test.

While in there, `arrowGlacierBlock`, `grayGlacierBlock`, `mergeNetsplitBlock` and
`depositContractAddress` now prefer hive's exported `HIVE_FORK_ARROW_GLACIER`,
`HIVE_FORK_GRAY_GLACIER`, `HIVE_MERGE_BLOCK_ID` and `HIVE_DEPOSIT_CONTRACT_ADDRESS`,
keeping the genesis `config` reads as fallback. Both real fixtures agree on every one
of those values, so this changes nothing measurable today; it removes the assumption
that a simulator's genesis always carries them.

### graphql: the two failures, finally with numbers

The inertness oracle has held at exactly 2 across every change. Decoding the artifact
gives the actual values for the first time:

| test | expected | got |
|---|---|---|
| `04_eth_estimateGas_contractDeploy` | `0x1b551` (111,953) | `0xa959` (43,353) |
| `07_eth_gasPrice` | `0x10` (16) **or** `0x1` (1) | `0x3437004b` (876,019,787) |

The estimateGas delta is 68,600 = 343 × 200, which is the shape of a missing
`G_codedeposit` charge — a hypothesis to test against the fixture, not a finding.

### devp2p on `810d6d8`: still 18, composition changed again

| sub-suite | total | failing |
|---|---:|---:|
| discv4 | 16 | 0 |
| discv5 | 11 | 0 |
| eth | 25 | 9 |
| snap | 6 | 5 |
| snap2 | 4 | 4 |

Three of the 18 are the `client launch` pseudo-test reporting `exit status 1` — one
per sub-suite that has a launch step, not three distinct defects.

Two causes are now named rather than guessed:

- **snap/2 is not advertised.** `snap2 / Status` fails with
  `could not negotiate snap protocol (remote caps: [eth/68 eth/69 snap/1], local: snap/2)`.
  fukuii offers snap/1; the sub-suite requires snap/2. That is 2 of the 4 snap2
  failures (`Status`, `TrieNodesRemoved`).
- **snap responses ignore the `responseBytes` soft limit.** All four snap failures
  (`AccountRange` 4000, `GetByteCodes` 10000, `GetTrieNodes` 5000, `GetStorageRanges`
  500) report the limit as the last line, and `snap2 / GetBlockAccessLists` reports
  2097152.

The `invalid message code 17` BRU panic is gone from this suite entirely. The eth
residue is now disconnects and i/o timeouts on the pooled-transaction path
(`NewPooledTxs`, `TestBlobTxWithoutSidecar`, `TestBlobTxWithMismatchedSidecar`,
`LargeTxRequest`, `InvalidTxs`, `BlobViolations`), plus `GetBlockAccessLists`
(EIP-7928, expected — Amsterdam is not implemented).

### sync on `810d6d8`: 2 of 12, and the logs say fukuii is NOT missing backfill

Both failures, with their real causes:

- **`sync fukuii from fukuii`** — `dial tcp 172.17.0.5:8545: connect: connection refused`.
  The test ran 16:23:50.374 → 16:23:53.432 and the client it queries was instantiated
  at 16:23:53.352, i.e. **80 ms before the test gave up**. This is hive considering the
  container ready before fukuii has bound 8545, not a sync defect. The liveness gate
  (`HIVE_CHECK_LIVE_PORT`) is the thing to look at.
- **`sync go-ethereum from fukuii`** — `timeout (1m0s elapsed, current head is 0)`.
  The source fukuii logged at 16:23:50
  `CANONICAL_HEAD_ANNOUNCE: no handshaked peers yet for block 3000 — deferring to peer-scan`
  and did not actually announce until **16:25:00**, ~70 s later. hive's budget is 60 s;
  the test ended 16:24:54, six seconds short. The deferral window outlives the test.

**Correction to an earlier working assumption.** It had been supposed that fukuii has
no engine-driven backfill. The client logs from this run show it does, and that it
reaches the wire:

```
ForkChoiceManager   - Fork choice head … not executed yet (SYNCING, notify-only): headerKnown=true
SyncController$Impl - Received CL-driven beacon head d4a8090b… (knownHeader=3000)
BlockBroadcast      - CANONICAL_HEAD_ANNOUNCE: block=3000 … to 1 handshaked peers
BlockFetcher        - [RegularSync] headers=1024 … range=[1-1024]    waiting=1024
BlockFetcher        - [RegularSync] headers=1024 … range=[1025-2048] waiting=2048
BlockFetcher        - [RegularSync] headers=952  … range=[2049-3000] waiting=2744
```

So the engine `Missing Ancestor Syncing` question is not "is backfill implemented" but
whether it starts on that path, and whether executing a backfilled branch ever turns
into `INVALID` + `latestValidHash` on the engine response.

#### `sync fukuii from fukuii`: the readiness gate is on 8551, not 8545

Run to ground rather than left as "timing". hive `simulators/ethereum/sync/main.go:97`:

```go
sinkParams := params.Set("HIVE_BOOTNODE", enode).Set("HIVE_CHECK_LIVE_PORT", "8551")
```

The sync simulator **overrides** the readiness port to 8551 for the sink node.
`internal/libhive/api.go:286` reads `HIVE_CHECK_LIVE_PORT` from the *simulator's*
params, not from the client image's `ENV`, so `hive/fukuii/Dockerfile`'s
`HIVE_CHECK_LIVE_PORT=8545` does not apply to this suite at all.

`StdNode.startNode` deliberately binds 8551 first and 8545 last, awaiting both, so that
"8545 accepting implies 8551 already does" — correct for every simulator that gates on
the default 8545, and exactly backwards for this one. Measured: 8551 bound at
16:23:53.349, hive declared the client up at 16:23:53.352, the simulator's first
`eth_getBlockByNumber` hit 8545 at ~16:23:53.43 and was refused.

**Not fixed, deliberately.** Swapping the order fixes this one test and reinstates the
2026-06-01 bug (`engine_newPayloadV3` hitting an unbound 8551) for every suite that
gates on 8545 — the engine suite is 403 tests. Binding concurrently does not help
either: hive's probe can connect as soon as the *first* port binds, before `startNode`
returns. There is no ordering that satisfies both gates, so this is a trade to decide
deliberately, not to guess at. Options, for the record:
  a. leave it — costs 1 test of 12 in `sync`;
  b. have the adapter ask for 8545-first only when `HIVE_BOOTNODE` is set, which is the
     signal that identifies this simulator's sink node;
  c. shrink the 8551→8545 window by pre-materialising the HTTP server before either bind.

What *did* change: `logback.xml` now carries
`<logger name="com.chipprbots.ethereum.nodebuilder" level="INFO"/>`. `StdNode` logs both
binds as it awaits them, but only the 8551 line was ever visible — `EngineApiHttpServer`
sits under the `consensus.engine` logger while `StdNode` fell through to `ROOT=ERROR`.
The 8545 bind time has been invisible in every hive log collected so far, which is why
this race was twice attributed to the wrong port. The next run will show both.

---

## Engine on `810d6d8`: 78 → 48, and all 30 that cleared were one family

`355 / 48 / 403`, against `325 / 78 / 403` on `6c8bc97` — same denominator, so the
difference is real. Failing test NAMES diffed against
`baselines/engine-failing-6c8bc97.txt`:

- **30 cleared** — every single `Fork ID:` test. All 12 `Genesis=0, Cancun=*`, all 12
  `Genesis=1, Cancun=*`, and all 6 `Paris` ones.
- **0 new failures.** No regression anywhere in the suite.

The new failing set is pinned at `baselines/engine-failing-810d6d8.txt`.

**A prediction was wrong, in the useful direction.** The expectation carried into this
run was that 24 of the 30 would flip from the fork-id work (12 from dropping the
wall-clock substitution, 12 from the genesis-timestamp filter) and that the 6 `Paris`
ones would NOT, because those were failing on `invalid message code: 33` rather than on
the checksum. All 6 flipped too. Deleting the eager post-handshake
`ETH69.BlockRangeUpdate` cleared them: `invalid message code` has gone from 21
occurrences to **zero** across the whole suite, and `wrong fork ID in status` from 10 to
**zero**. Two independent defects, both gone, and the second was worth more than it was
credited for.

### The remaining 48

| count | cause |
|---:|---|
| 34 | `Timeout waiting for main client to detect invalid chain` — Missing Ancestor Syncing |
| 5 | blob bundle count mismatch |
| 4 | `Timeout waiting for sync` — Withdrawals Re-Org Sync |
| 2 | `Request Blob Pooled Transactions`: `expected size 131330, got 146` |
| 1 | `ForkchoiceUpdatedV3 To Request Shanghai Payload, Null Beacon Root` |
| 1 | `In-Order Consecutive Payload Execution` |
| 1 | `GetPayloadBodiesByRange (Sidechain)` |

The two `Request Blob Pooled Transactions` failures changed shape rather than clearing:
they were `invalid message code: 33` and are now a sidecar size mismatch
(`expected size 131330, got 146`). That is a different, and more specific, defect than
the one they were previously attributed to.

38 of the 48 are the Missing Ancestor Syncing family plus the Re-Org Sync timeouts,
which the cross-tab above splits by two discriminators (`Invalid P8` always fails;
`CanonicalReOrg=True` always fails).

### Scoreboard, all on `810d6d8` unless noted

| suite | start | `6c8bc97` | `810d6d8` |
|---|---:|---:|---:|
| rpc-compat | 19 | 6 | **1** (246/1/247) |
| graphql | 2 | 2 | 2 |
| devp2p | 18 | 18 | 18 |
| engine | 97 (fc713a9) | 78 | **48** (355/48/403) |
| sync | — | 1 real | 2 (both timing, diagnosed above) |

---

## The Missing-Ancestor family, diagnosed: two causes, one fixed

Both discriminators from the cross-tab now have named mechanisms with log evidence, not stories.

### Cause A — depth-1 invalid-ancestor propagation (fixed, `94199a1`)

The cross-tab is not really "P8 vs P9". It is the **distance from the CL-supplied tip to the planted
invalid block**:

| case | side chain | invalid | tip sent to fukuii | hops |
|---|---|---|---|---|
| `Invalid P9` (passes) | P1..P9 = blocks 6..14 | P9 = block 14 | block 15, parent = P9 | **1** |
| `Invalid P8` (fails) | P1..P9 = blocks 6..14 | P8 = block 13 | block 15, parent = P9 | **2** |

`EngineApiService:240` tests only `invalidBlocks.containsKey(payload.parentHash)`, and
`markInvalidRecursive` (`:117-126`) cascades only through `acceptedChildrenByParent` — an index
populated solely at `:469-474`, i.e. only for blocks that arrived via `engine_newPayload`. Block 14
arrived over p2p, so the poison chain breaks one link short of the tip.

The decisive evidence is that fukuii reaches the **right verdict** and cannot express it:

```
[BlockFetcher]     [RegularSync] headers=14 … range=[1-14] waiting=14
[EngineApiService] import path reported block 46fd0a65… consensus-invalid, latestValidHash=7d0d9971…
[EngineApiService] newPayload #15: ACCEPTED (parent unknown)   ×80 → timeout
```

`46fd0a65` is side-chain P8, `7d0d9971` is P7. In the passing P9 case the reported block IS the tip's
direct parent, the `:240` guard fires on the next poll, and the response is
`INVALID lvh=0xf9ebef0b40`.

Fixed by carrying an already-proven verdict along a proven link: `provenDescendants` keeps only the
unbroken `parentHash`-linked prefix behind the failing block and stops at the first break, all sharing
one `latestValidHash` — which is what hive asserts (`invalid_ancestor.go:444`). It does **not** widen
`provesConsensusInvalid`. Predicted to flip 8 of the 34 (`CanonicalReOrg=False, Invalid P8`, four
corrupted fields × Paris/Cancun). Prediction, not measurement.

### Cause B — engine-imported blocks carry no ChainWeight (NOT fixed; 28 of 38)

`BlockchainWriter.storeBlock` (`domain/BlockchainWriter.scala:54`) writes header and body only, and
`grep ChainWeight` over `EngineApiService.scala` and `ForkChoiceManager.scala` returns **zero hits**.
Every block imported through `engine_newPayload` therefore has no stored chain weight, and any later
p2p branch resolution across that range is unresolvable:

```
PEER_HANDSHAKE_SUCCESS  … bestHash=4f8c8262… latestBlock=14
PEER-CHAIN-DIVERGE      Peer reports hash=4f8c8262… at block 14; our hash=11d2f6e7…
[RegularSync] headers=14 from=PeerId(c6646db9…) range=[1-14] waiting=14
ERROR [BranchResolution] ChainWeight for 6: c05c5658… not found when resolving branch
```

So the path **does** reach the p2p import layer — the open question from the previous entry — and dies
in `BranchResolution.compareBranch` before any weight comparison happens.

**Blast radius, measured not estimated:** `"not found when resolving branch"` appears in exactly **32 of
486** client logs in the `6c8bc97` artifact. Mapped container-id → test name: **22 Missing-Ancestor
`CanonicalReOrg=True` + all 4 `Withdrawals … Re-Org Sync`** (some tests spawn two containers). That is
**28 of this family's 38 failures, one cause.**

Two blockers stack behind it, both source-verified, both biting the moment weights exist:
`ChainWeight.increase = totalDifficulty + header.difficulty` with post-merge difficulty 0 makes
`newWeight > oldWeight` unsatisfiable in `BranchResolution.compareBranch:69` (its escape hatch at `:73`
is guarded on `oldBlocks.isEmpty`, which excludes exactly the reorg case), and
`ConsensusImpl.importToNewBranch:133` carries the same unsatisfiable guard. So storing the weight alone
just moves the failure to `NoChainSwitch`; steps 1 and 2 are one atomic unit.

### Two corrections to earlier entries in this file

- **The peer-scan deferral is NOT the engine-suite cause.** Measured and refuted: in the failing P8
  container the announce reached a peer at `15:04:15,640` and the header fetch followed 92 ms later.
  That hypothesis stands only for the `sync` suite's 60s budget, where it was measured.
- **The engine-driven backfill has been working by accident.** It chases the *peer's handshake height*,
  not the CL-supplied beacon head. In `CanonicalReOrg=False` the secondary geth connects late and its
  ETH/69 STATUS already advertises height 14, so the range fetch happens to cover the gap. In the 2
  `EmptyTxs=True` never-fetched cases the peer handshakes at head ≈ 0, post-merge geth never gossips,
  and `BlockFetcher.knownTop` freezes at 1 — no `headers=` line appears in the whole log. There is no
  reverse-from-hash beacon sync in fukuii.

### Also flagged, ownership undetermined

`RegularSyncSpec` → "should return updated status after importing blocks" times out at
`RegularSyncFixtures.scala:278`. Reproduced 3/3 at `94199a1` and 1/1 with those files reverted, so it is
not that change. Whether it is a stale test or collateral from the concurrent `BlockPreparator`/`VM`
work is open and being re-checked against the current head.

---

## graphql: one real defect, one stale fixture — and a bad conversion of mine

### Correction first

An earlier entry in this file recorded fukuii's `07_eth_gasPrice` answer `0x3437004b` as
**876,610,123**. That is wrong; `0x3437004b` is **876,019,787**. The figure was mine, it went into the
agent brief, and the "≈590,337 wei tip" derived from it was an artifact of the bad conversion, not a
measurement. The line above is corrected in place.

With the right number the residual vanishes: the fixture chain's head block carries
`baseFee = 0x3437004a = 876,019,786`, and fukuii returns `baseFee + 1` — the 1-wei `min-tip` default.
Nothing unexplained.

### `07_eth_gasPrice` — a stale fixture, self-evidenced

The strong form of this argument needs no appeal to what geth's CI does. **hive's own fixture set
contradicts itself**:

| fixture | asserts |
|---|---|
| `01_eth_blockNumber.json` | head = `0x22` = block **34** |
| `51_eth_getBlock_4844.json` | block 34 `baseFeePerGas` = `0x3437004a` = **876,019,786** |
| `07_eth_gasPrice.json` | accepts only `0x10` (16) or `0x1` (1) |

go-ethereum's current `Resolver.GasPrice()` is `tipcap + head.BaseFee` when `BaseFee != nil`. Given
the head and baseFee that the sibling fixtures themselves pin, 16 or 1 wei is arithmetically
unreachable — for geth as much as for fukuii. The contradiction is internal to the fixture set, so
this holds without trusting any external report.

An alternative was considered and ruled out on the same evidence: that geth's plain `import` leaves
the canonical head at the last PoW block (32, where `baseFee` is nil) without the Engine API — real,
known geth behaviour, but `01_eth_blockNumber` pinning head = 34 kills it.

**Recorded as a known-permanent failure**, not an open bug. The graphql suite cannot reach 0 until
hive updates those accepted values upstream; its floor is 1. This is a source-level proof, not an
execution one — geth was not run against the fixture.

### `04_eth_estimateGas_contractDeploy` — a real fukuii defect, fixed

The graphql simulator does **not** run on the execution-apis chain that rpc-compat uses. Its
`init/testGenesis.json` sets `homesteadBlock` through `londonBlock` all to **33**, so block 32 — the
block the fixture queries — is pure **Frontier**.

Under Frontier (`exceptionalFailedCodeDeposit = false`) a CREATE that cannot pay the code deposit does
not revert. But `VM.saveNewContract` also set no error at all, so `binarySearchGasEstimation`, which
reads `TxResult.vmError`, treated "ran the init code, could not afford to store it" as success.
go-ethereum's `core/vm/evm.go create()` sets `err = ErrCodeStoreOutOfGas` unconditionally —
`IsHomestead` gates only the revert and gas burn, not whether the error is set.

Fixed with a `CodeStoreOutOfGasPreHomestead` error carrying `rollbackOnError = false`, so
`BlockPreparator.executeTransaction` now checks `error.exists(_.rollbackOnError)` rather than blanket
`error.isDefined`: the failure is reported to `estimateGas`/`eth_call` while the already-applied
partial state (nonce bump, endowment transfer) survives for a transaction that really lands in a
block — Frontier's actual leniency, not blanket revert.

Measured against the fixture's exact init bytecode through `StxLedger.binarySearchGasEstimation` on a
Frontier-only config: **43,353 before, 111,953 after** — an exact match to the expected `0x1b551`, and
`111,953 − 43,353 = 68,600 = 343 × 200`, where 343 is the deployed runtime length read from the
`PUSH2 0x0157` in the init code.

### Measured: graphql 2 → 1 on `db1c345`

`51 / 1 / 52`. `04_eth_estimateGas_contractDeploy` **passes** — the Frontier code-deposit diagnosis
was right and the fix works end to end, not just against the unit pin.

The single remaining failure is `07_eth_gasPrice`, returning `0x3437004b` unchanged. That is the
stale-fixture case, so **graphql is now at its floor of 1** and cannot reach 0 until hive updates
`07_eth_gasPrice.json` upstream.

This also sets a two-sided bar for the ETC correction now in progress: `04` must keep passing
(estimation still returns 111,953) *and* the Frontier CREATE semantics must return byte-for-byte to
their pre-`89856a1` behaviour. Both, not either.

### The 8551→8545 window, finally measured — and the decision it closes

The logback change did its job. `JSON-RPC HTTP server bound to …` now appears in hive client logs
for the first time, so the readiness race can be quantified instead of argued about. Four containers
in the `db1c345` sync run:

| container | 8551 bound | 8545 bound | gap |
|---|---|---|---:|
| `4feed877` | 17:33:25,984 | 17:33:26,119 | **+135 ms** |
| `831b8d76` | 17:35:33,482 | 17:35:33,699 | **+217 ms** |
| `b5c60ced` | 17:34:55,748 | 17:34:55,905 | **+157 ms** |
| `2f84c002` | 17:33:31,367 | 17:33:31,562 | **+195 ms** |

Consistently 135–217 ms. Against the earlier measurement of the failing case — hive declared the
client up at `53.352` on the 8551 gate, and the simulator's first `eth_getBlockByNumber` hit 8545 at
`~53.43`, about **80 ms** later — the simulator fires squarely *inside* that window every time. This
is a deterministic failure, not an intermittent one, which matches `sync fukuii from fukuii` failing
in every run rather than flaking.

**Decision: leave it (option a). Closed, not deferred.** The numbers rule the alternatives out:

- Swapping to 8545-first fixes this one test and reinstates the 2026-06-01 bug for every suite that
  gates on the default 8545 — including `engine`, 403 tests — because those sims call 8551 just as
  promptly as this one calls 8545.
- Binding concurrently (option c) would shrink the window to a few ms, comfortably inside the 80 ms
  the simulator takes. But it makes the ordering non-deterministic, so it trades **one deterministic
  failure** for a **small probabilistic flake across 403 tests**. That is a worse risk profile for a
  one-test gain.
- Conditioning the adapter on `HIVE_BOOTNODE` (option b) remains available if this test ever matters
  more than it does now.

`sync` therefore stays at 2 of 12: this one, plus `sync go-ethereum from fukuii` (the ~70 s
`CANONICAL_HEAD_ANNOUNCE` peer-scan deferral against a 60 s budget), which is a separate cause and
still open.

### devp2p on `db1c345`: 18 of 62, composition unchanged

discv4 0/16, discv5 0/11, eth 9/25, snap 5/6, snap2 4/4 — identical to `810d6d8`. The inertness
oracle held across the Muir Glacier fix, the tracer work, the estimateGas change and the INVALID
propagation.

---

## devp2p's snap failures are not fukuii's — and the "responseBytes" reading was wrong

### Correction: `responseBytes` is a request echo, not an error

An earlier entry here recorded the four `snap` failures as "snap responses ignore the `responseBytes`
soft limit", citing that value as the last line of each failure. That reading was mine and it was
wrong. The full text is:

```
   request:
       root: 7714335f…
       range: 0x00…00 - 0xff…ff
       responseBytes: 4000
 test 0 failed: account range request failed: read tcp 172.17.0.3:42266->…:30303: i/o timeout
```

`responseBytes` belongs to the pretty-printed *request*; the error is `i/o timeout`. It precedes
every failure block, including budgets that should return instantly, so it carries no information
about limit handling at all.

### The real cause: a wire-offset mismatch in hive's own devp2p CLI tool

Evidence from both sides of the same connection:

- client side: `AccountRange` test 0 → `read tcp 172.17.0.3:42266->…: i/o timeout`
- server side, same port: `DECODE_ERROR … Cannot decode GetByteCodes. Expected RLPList[3]` — fukuii
  received hive's `GetAccountRange` bytes and decoded them as `GetByteCodes`, exactly 4 canonical
  slots away — and later `Unknown snap/1 message type: 42`

go-ethereum's `cmd/devp2p/internal/ethtest/protocol.go` hardcodes `ethProtoLen = 22` with a comment
admitting it assumes the negotiated capabilities are exactly `{eth, snap}`. Real geth servers do not:
`eth/protocols/eth/protocol.go` has `protocolLengths = {69:18, 70:18, 71:20, 72:22}`, consumed via
`p2p.Protocol{Length: …}`, and `p2p/peer.go matchProtocols` sizes each subprotocol's wire range by the
version actually negotiated. fukuii replicates that correctly in
`RLPxConnectionHandler.scala:125-146`.

The arithmetic closes: fukuii negotiates eth/69, so its `peerSnapBase` is `0x22` (34) and its snap
window is `[34, 42)`. The tool assumes `0x26` (38). The tool's `GetAccountRange` at 38 reads as
fukuii's slot 4 — `GetByteCodes`. The tool's `GetByteCodes` at 42 falls outside the window entirely.
Both observed log lines, exactly.

**Making fukuii match the tool would break real interop** with any compliant eth/69 + snap/1 peer. The
reference is go-ethereum's *server*, not its CLI test helper. No production change was made; a
regression test now pins `[ETH70, ETH69, SNAP1]` → negotiated ETH69 → `peerSnapBase = 0x22` so a later
session does not "fix" this into an interop regression.

A confirming detail: the one `GetTrieNodes` sub-test that appears to pass has `expReject: true` in
`snap_ethtest.go:614-624` — it expects a failed read, so the identical offset failure scores as a pass
there and nowhere else.

### snap/2 is a real build, and it is blocked on Amsterdam

`Capability.scala` has no `SNAP2` at all. Per go-ethereum, snap/2 (`protocolLengths` 10 vs 8) adds
`GetAccessListsMsg`/`AccessListsMsg` and **removes** `GetTrieNodes`/`TrieNodes` from the handler set —
which is precisely what hive's `TrieNodesRemoved` test checks. Serving `GetAccessLists` requires
EIP-7928 block access lists, which this plan lists as slice C, **not yet built**. So snap/2 cannot be
implemented honestly today.

### `client launch` is an artifact, not a defect

`simulators/devp2p/main.go` runs `./devp2p rlpx snap-test` as a subprocess; it reports each case via
TAP *and* exits nonzero if any failed, which surfaces as a separate `client launch` pseudo-test. It
moves in lockstep and clears automatically when the real failures do.

### What this means for devp2p's floor

Of the 18: **9 are snap-related and none is a fukuii defect fixable today** — 5 from the hive tool's
own offset assumption, 4 requiring snap/2 and therefore EIP-7928. Like graphql's stale fixture, this
is a ceiling on what "all green" can mean for this suite until Amsterdam lands or hive updates its
tool.

---

## Measurement on `501368ec9` — rpc-compat is green

All numbers from `summaryResult.pass`; engine diffed by test name.

| suite | before | `501368ec9` | note |
|---|---:|---:|---|
| **rpc-compat** | 1 | **0 / 247** | green — the Muir Glacier reconstruction was right |
| graphql | 1 | 1 / 52 | at its floor (stale upstream fixture) |
| **engine** | 48 | **42 / 403** | 6 cleared, **0 new** — pinned at `baselines/engine-failing-501368ec9.txt` |
| devp2p | 18 | 18 / 62 | unchanged; ~9 are the hive tool / snap/2 ceiling |
| sync | 2 / 12 | **1 / 18** | `sync fukuii from fukuii` passed; see correction below |
| consume-engine | — | 8 / 1473 | first measurement; see below |
| consume-rlp | — | 4 / 1702 | first measurement; see below |
| consensus | — | 0 tests ran | hiveproxy race; see below |

### Engine: Cause A scored — 6 of 8 predicted

All 6 cleared tests are exactly the targeted cell, `Invalid P8, CanonicalReOrg=False`:
GasLimit, ReceiptsRoot and Timestamp, each × Cancun/Paris. The prediction was 8. The 2 that did not
flip are both **GasUsed** × Cancun/Paris. The fix's report named a separate gas-used arm at
`BlockImporter.scala:557` receiving "the same treatment"; the measurement says that arm is not
carrying the verdict. Handed back to its author.

### Correction: the sync readiness race is probabilistic, not deterministic

An earlier entry concluded "Deterministic, not intermittent" for `sync fukuii from fukuii`, from a
135–217 ms bind window against a simulator call measured at ~80 ms. On this run the window was the
same (143–255 ms) and the test **passed**. The window was measured four times; the simulator's call
delay was measured once, and it evidently varies. Overclaimed from a single sample. The decision
to leave it is unchanged — it was sound on the risk trade-off regardless — but it rests on
"probabilistic", not "deterministic".

### consume-*: 8 of 12 failures are a time budget, not fukuii

Both suites ran exactly 60:00 and hit `simulation timed out` with four tests each still being
launched (consume-engine at 1469/1473, consume-rlp at 1698/1702). Their clients died with
`timed out waiting for container startup`; each client log is 238 lines of logback initialisation
and nothing else. Which four land in the tail depends on scheduling — which is why `gates.yml`
records consume-rlp as failing "only intermittently". `--sim.timelimit` raised to 80m (`5804dc8`).

The remaining **4 are real**: `test_all_opcodes` on Paris, Shanghai, Cancun and Prague, each
`INVALID reason=Block has invalid gas used`:

| fork | expected | fukuii | Δ |
|---|---:|---:|---:|
| Paris | 8,298,977 | 8,313,979 | +15,002 |
| Shanghai | 8,283,975 | 8,298,977 | +15,002 |
| Cancun | 8,209,169 | 8,159,271 | −49,898 |
| Prague | 8,209,169 | 8,159,271 | −49,898 |

Pre-merge forks of the same test pass.

### consensus: never actually measured, and it cannot finish

This run's `ethereum/consensus` registered **zero** tests: the simulator's only output is
`Post "http://172.17.0.2:8081/testsuite": connect: connection refused`, 0.4 s after the hiveproxy
container started. A race inside hive; fukuii was never launched.

Worse, this suite has been **cancelled by this branch's own pushes on nearly every run**
(`cancel-in-progress`), so it had never been read at all here. Going back further, it has failed on
every completed run since 5 July, including unrelated `automated/update-bootnodes` branches — and
every one of those is the same shape. Three runs decoded:

| run | tests run | failed | of which timeout tail | genuine |
|---|---:|---:|---:|---:|
| 35566969778 | 1,864 | 7 | 5 | 2 |
| 35678567820 | 1,606 | 7 | 5 | 2 |
| 35695707097 | 1,519 | 7 | 5 | 2 |

The legacy ethereum/tests corpus is far larger than ~1,800 tests, so at parallelism 4 in 60m the
suite **never completes**, and only its first ~1,800 tests are ever exercised. Raising the limit
would expose more tests but would not make it finish. That is a scoping decision for this gate,
recorded here rather than changed.

The 2 genuine failures, identical in all three runs:
`bcStateTests/testOpcode_10.json::testOpcode_1e_Cancun` and `…_Prague` — stateRoot mismatches.
Opcode `0x1e` is **CLZ (EIP-7939), an Osaka opcode**, which must be undefined on Cancun and Prague.
If fukuii enables it early, an undefined-opcode sub-call on those forks would not burn its stipend
and fukuii's gas would come out *low* — the direction and fork set of `test_all_opcodes`' −49,898.
Two independent suites pointing at the same place. Hypothesis, handed to the ETH specialist to
verify by executing the fixture; the Paris/Shanghai +15,002 goes the other way and is probably a
separate cause.

---

## Cause B implemented (`a6c652a17`) — awaiting ETC sign-off before it is pushed

beacon implemented steps 1 and 2 as one unit, and showed rather than asserted that they must be one:
a test that stores a `ChainWeight` for every block still gets `NoChainSwitch`, because with weights
present `compareBranch` sees equal weights (difficulty 0 everywhere) and a non-empty `oldBlocks`,
and refuses.

- **Step 1** — `EngineApiService.storeChainWeightFor` writes `parentWeight.increase(header)` for an
  executed engine block, and writes nothing rather than invent one when the parent has no weight.
- **Step 2** — a PoS arm at both weight sites, sharing `DesignatedHead.leadsToDesignatedHead`: true
  iff the branch tip is the fork-choice head or an ancestor of it through headers already held;
  capped at 1024 hops; false on any doubt (no CL, silent CL, missing header, cap hit).

Two independent gates keep it off PoW chains: `designatedHeadOpt` is `None` unless
`terminalTotalDifficulty.isDefined` (set only by `eth-chain.conf` and `sepolia-chain.conf`), and the
`ConsensusImpl` holder is bound only when the Engine API is enabled on a TTD chain. Three negative
controls, each failing exactly the tests that encode its half. The four ETC specs pass unchanged.

**Predicted, not measured:** ~24 of the 28 targeted clear (24 `CanonicalReOrg=True` + 4
`Withdrawals … Re-Org Sync`), taking engine from 42 to about 18. Excluded from the prediction: the 2
`EmptyTxs=True` cases, whose peer handshakes before it has built anything so `BlockFetcher.knownTop`
freezes at 1 — they need real beacon sync — and the 2 `GasUsed, CanonicalReOrg=True` cases, which
will now execute but hit the gap below. This is the first time fukuii reorganises a PoS chain over
p2p at all, so the currently-passing reorg tests are the thing to watch for regressions.

Held from the remote pending a `forge` review: this changes fork choice, on paths ETC shares.

### The GasUsed residual is an ownership gap, not a propagation gap

The two `GasUsed, Invalid P8` tests that Cause A did not clear produce **no verdict at all**. In the
failing container the node fetches the same `headers=14` as the passing GasLimit case, but logs
neither `import path reported` nor `BlockImporter`'s INFO-level `Gas mismatch`. Nothing is reported,
so there is nothing to propagate.

- `InvalidChainReporter.provesConsensusInvalid` deliberately declines gas-used mismatches, because
  missing bytecode can produce one on an honest block; its comment assigns that case to `BlockImporter`.
- `BlockImporter`'s gas-used arm is reached only via `BlockImportFailed` — when *nothing* in the batch
  executed.
- In hive, blocks 1–12 execute and 13 fails: a partial success, which `ConsensusAdapter` maps to
  `BlockImportedToTop`, keeping the error only in a `log.warn`.

So each side believes the other owns it. The obvious fix — running `findMissingContractCode` on the
partial path — was rejected because that predicate **fails open** (an exception returns `None`, read
as "nothing missing, report invalid") and only checks direct call targets. The proposed fix instead
compares our receipts root with the header's on a gas-used mismatch: if they agree, the header's own
receipts commit to our `cumulativeGasUsed` and the block contradicts itself regardless of state
completeness. It touches `StdValidators`, which ETC shares, so it is under design review, not built.

### Pre-existing unit failures found in passing

- `SyncControllerSpec`: 11 failures, all 25 s timeouts in fast-sync pivot tests — identical with the
  new work stashed at a clean `501368ec9`.
- `RegularSyncSpec` "should return updated status after importing blocks": a 3 s poll timeout,
  failing at every commit checked back to before `94199a1`.

Neither runs in this PR's CI: `testEssential` excludes `SyncTest`, the tag these carry, and `build.sbt`
names that category as "complex actor choreography that times out under CI load"; Tier 2 is skipped
for `staging`-targeted PRs. Pre-existing, outside this gate, not caused by this work. Recorded rather
than changed.

---

## Correction: the consume suites are ~3.5% sampled, not four tests short

The `501368ec9` entry above said consume-engine "stopped at 1469/1473" and consume-rlp "at
1698/1702", with four tests each still launching, and that raising the time limit lets the suite
finish. **Wrong denominator.** 1,473 and 1,702 are the tests hive had *started* — hive only learns of
a test when it begins. pytest's own collection line in the simulator log reads:

| suite | collected | launched in 60m | fraction |
|---|---:|---:|---:|
| consume-engine | **42,234** | 1,473 | ~3.5% |
| consume-rlp | **47,589** | 1,702 | ~3.6% |

`gates.yml`'s recorded 1,499 and 1,808 were truncated runs too, not suite sizes. The error was taking
hive's count as the total without checking pytest's.

Consequences:

- **80m does not finish these suites.** It buys ~1/3 more coverage per run, still well under 5%.
  Completing either at parallelism 4 would take on the order of a day. The 80m change is kept for the
  coverage, with its rationale corrected in the workflow comments.
- **The timeout tail is permanent.** Every run ends `simulation timed out` with ~4 tests killed
  mid-launch. Those remain wall-clock artifacts rather than fukuii failures — that half of the
  earlier entry holds — but they recur every run at whichever position the wall falls.
- **"8 of 1,473" and "4 of 1,702" are failure rates over a ~3.5% prefix**, not suite results. The
  rest of each suite has never been exercised on this branch.
- **consume-engine, consume-rlp and consensus share one shape:** none can complete in CI as
  configured, so none can go green on their current definition, independent of fukuii's
  correctness. Making them gateable needs a scoping decision — e.g. a `--sim.limit` subset that
  completes and is representative of the forks fukuii targets. That is a decision about what the
  gate is for, and is recorded here rather than made.

### The `test_all_opcodes` fix, and what it says about ETH mainnet

Two causes, each confirmed by diffing all 256 result slots against the EEST v5.4.0 fixture:

- **Paris/Shanghai +15,002 — BASEFEE (0x48, EIP-3198) not executable.** `LondonConfigBuilder`
  inherited `MagnetoOpCodes` (ETC's Phoenix/Berlin table, no 0x48), and the Shanghai overlay installed
  `SpiralOpCodes`, also without it. Only slot 0x48 differed. A failed call burns the 35,000 stipend
  plus a cold no-op SSTORE (2,200); a successful one costs 98 and pays 22,100 to set the slot:
  35,000 + 2,200 − 98 − 22,100 = 15,002.
- **Cancun/Prague −49,898 — CLZ (0x1e, EIP-7939, Osaka-only) active early.** The Cancun overlay
  installed `OlympiaOpCodes`, which contains CLZ. The fixture calls 0x1e **twice**:
  2×35,000 + 2,200 + 100 − (2×101 + 22,100 + 100) = 49,898. The consensus suite's `testOpcode_1e`
  lead explained all of it.

Shanghai's value equalling expected-Paris was coincidence: PUSH0's own valid/invalid swing is also
15,002. Not a PUSH0 bug.

**Wider consequence (inferred, not measured):** fukuii's ETH mainnet configuration takes the same
London path, so full-syncing ETH mainnet between London and Shanghai would diverge on any transaction
that executes BASEFEE.

**A pattern, three times over:** an ETH fork overlay borrowing an ETC-named opcode table — Magneto for
London, Spiral for Shanghai, Olympia for Cancun. `OlympiaOpCodes` is now unreferenced in main code and
its doc comment wrongly says ETC uses it. Worth a CHASE-QUEUE entry and a structural guard: ETH tables
should not be defined in terms of ETC-named lists.

The London commit (`aabf156e8`) sits inside `forBlock()`'s block-number dispatch and is under ETC
review before it is pushed.

---

## `0101b9e36` measured, and what the next push carries

### `0101b9e36` — a clean, reproducible baseline

| suite | result | vs `501368ec9` |
|---|---:|---|
| rpc-compat | 0 / 247 | held green |
| graphql | 1 / 52 | held (stale fixture) |
| engine | 42 / 403 | **identical names** — 0 cleared, 0 new |
| devp2p | 18 / 62 | held, same per-sub-suite split |
| sync | 1 / 18 | held |
| consensus | 11 / 2,412 | see below |
| `Test and Build` | **success** | first green run in a long while |

`0101b9e36` carried no engine-relevant change, so engine repeating the same 42 *by name* establishes
the baseline is not flaky. Any delta on the next push is attributable to the code in it.

**consensus ran for real this time** — 2,412 tests, further than any earlier run (1,519–1,864),
reaching tests never exercised before. Of 11 failures:

- 2 genuine: `testOpcode_1e_Cancun` / `_Prague` — the CLZ bug, fixed in the push below.
- 5 `Test was terminated by host` — the simulation's wall-clock tail.
- 4 `Call1024*` (Byzantium, Constantinople, ConstantinopleFix, Frontier) that a first-pass classifier
  marked genuine. **They are not.** Each reads `can't launch node (type fukuii): client did not start:
  timed out waiting for container startup` and ran exactly 120 s — hive's `--client.checktimelimit` —
  launched in the final three minutes before the simulation timed out. A second form of the same tail.
  The classifier matched only "terminated by host"; it now needs both strings.

So consensus has had exactly **two** genuine failures on every run measured, and both are fixed below.

### What the next push carries (validated: 259/259, formatting and compile-all clean)

| commit | change | review |
|---|---|---|
| `a6c652a17` | Cause B — ChainWeight for engine-imported blocks + PoS fork-choice arm | forge: ETC unchanged |
| `d21e86667` | five mis-stated gate comments corrected; tests for ETC's real (unbound) wiring; misconfiguration hazard pinned | follows forge's findings |
| `3065b12e6` | GasUsed: report a mismatch the block's own receipts prove | forge design, conditions A+B, checked line by line |
| `47ebbb9fc`, `aabf156e8` | BASEFEE on ETH London/Paris/Shanghai; no CLZ on Cancun/Prague | forge: ETC unchanged (full table dump, byte-identical) |
| `4bbc0903f` | `test_all_opcodes` pinned against the rebuilt EEST fixture | — |
| `ba93ddf0b` | ETH mainnet Osaka / BPO1 / BPO2 timestamps; geth's full 38-row mainnet fork-id table | ETH-only config |

**Predictions, not measurements:** engine 42 → ~16 (Cause B ~24, GasUsed 2); consume-engine's 4
`test_all_opcodes` clear; consensus's 2 `testOpcode_1e` clear. The currently-passing reorg tests are the
regression risk to watch — this is the first time fukuii reorganises a PoS chain over p2p.

### The mainnet finding, and a test of ours that encoded it

`eth-chain.conf` declared no Osaka, BPO1 or BPO2, under a comment saying Osaka was "not yet scheduled on
mainnet" — stale since Fusaka activated in December 2025. go-ethereum master `MainnetChainConfig`:
Osaka `1764798551`, BPO1 `1765290071`, BPO2 `1767747671`. Without them fukuii's mainnet fork id omitted
three activations, so it **could not peer with ETH mainnet past December 2025**, and ran none of Osaka's
rules there. Same class as the Arrow/Gray Glacier gap found earlier, which blocked peering from December
2021. No hive suite covers it: hive runs its own chains.

`ForkIdEthMainnetSpec`, written earlier in this effort, pinned `next = None` at the Prague head — the
missing-Osaka bug encoded as an expectation. geth's published table says `Next: 1764798551`; two
assertions now expect that. Stricter, not weaker, and named here so it is reviewable. The spec now
carries geth's entire mainnet table, row for row.

**Inferred, not tested:** fukuii now executes Osaka rules on mainnet past 2025-12-03. Any latent Osaka
bug now matters there. Nothing here exercised mainnet Osaka block execution.

### Logged for later

- `EvmConfig.scala:80-81` picks London vs Olympia builder by fork ordering (`spiral > olympia`), not chain
  family; `isEthereum` goes unused. A custom ETC config with olympia < spiral would silently get the ETH
  London table. No shipped config has that shape. Both specialists agree it should select by family.
- `OlympiaOpCodes` is unreferenced in main code, and its doc comment says ETC uses it — a naming trap.
- `BlobGasUtils`' header comment still gives BPO1 as 8/12 and BPO2 as 12/18; the code (10/15, 14/21) is
  right and matches geth.
- Pattern, three times over: ETH fork overlays built from ETC-named opcode tables.

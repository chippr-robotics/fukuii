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

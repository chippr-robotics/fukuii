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

### A pre-existing coverage ceiling, independent of any fix

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
* `consensus` suite health, which its unstable denominator makes unmeasurable as run today.
  That is a CI-integrity defect in its own right.

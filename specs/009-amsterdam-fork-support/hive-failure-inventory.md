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
| snap/1 server does not respond | 4 | `AccountRange`, `GetByteCodes`, `GetTrieNodes`, `GetStorageRanges` — every one `i/o timeout`, never a wrong answer |
| `GetPooledTransactions` not served | 5 | `NewPooledTxs` and `BlobViolations` time out; `Transaction`, `InvalidTxs`, `LargeTxRequest` crash the simulator with a Go panic |
| blob sidecar handling | 2 | `TestBlobTxWithoutSidecar`, `TestBlobTxWithMismatchedSidecar` — both `failed to read GetPooledTransactions message: disconnect` |
| eth/71 block access lists | 1 | `GetBlockAccessLists` — `connection reset by peer` during status exchange |

The first two clusters are the substantial ones. `i/o timeout` on every snap/1 request means
the node accepts the snap connection and then never answers, which is a serving defect rather
than a wrong-response defect. The tx-pool cluster is likely one cause: fukuii either
disconnects or fails to answer `GetPooledTransactions`, and the blob pair reports the same
message, so those 7 may be 1 root cause rather than 7. NOT established either way.

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

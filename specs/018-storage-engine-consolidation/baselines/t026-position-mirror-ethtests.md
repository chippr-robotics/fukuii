# T026, the ethereum/tests half: pre-Atlantis blockchain tests through a position mirror (B-207, 2026-11-22)

**What ran.** `LegacyPreByzantiumBlockchainTestsSpec` over `EthereumTestHelper`, which now puts the `EestPositionMirror` of
T026 part 1 (`PositionTestKit.PositionMirror` behind the `StorageObserver` of `EestBlockchainReplay`) in front of the
storage of the pre-state build and of every block's execution. `EestBlockchainReplay.observedBlockExecution` is the one
place the production `buildInitialWorld` is wrapped; the replay and the helper both call it. After the pre-state and after
each accepted block the mirror's rows are compared with a raw-RLP walk of the state, and any finding fails the fixture
after it ran. `FUKUII_ETHTEST_POSITIONS=0` turns it off.

The mirror replays one chain. A fixture whose blocks are not one chain (side branches: `bcMultiChainTest`,
`bcTotalDifficultyTest`, `bcForkStressTest`, part of `bcGasPricerTest`) is run unmirrored by the helper, as the first run
showed (below). The state root per transaction of Frontier, Homestead and EIP-150 is checked by the existing receipt
validation of this spec, unchanged.

**Fixtures.** ethereum/legacytests `1f581b8c`, `Constantinople/BlockchainTests` (the `ets/tests` submodule, checked out at
`5490db3f`, has no Frontier, Homestead or EIP-150 BlockchainTests: the spec's own header says so). Cloud container, JDK 21.

| Run | Networks | Fixtures run | Passed | Mirrored | Units kept | Reads checked | Findings |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ValidBlocks` | Frontier, Homestead, EIP150, EIP158 | 745 | 745 | 703 | 2,336 | 754,876 | 0 |
| `GeneralStateTests` | same | 4,905 | 4,905 | 4,905 | 9,810 | 226,197 | 0 |
| `GeneralStateTests` + `ValidBlocks` | Byzantium, Constantinople, ConstantinopleFix | 26,437 | 26,437 | 26,395 | 53,552 | 1,512,165 | 0 |

Unmirrored: 42 pre-Byzantium and 42 Byzantium-to-Constantinople fixtures with side-branch blocks (84; 32,087 ran, 32,003 mirrored). A unit is the pre-state
or one accepted block. The two post-Byzantium forks `Istanbul` and `Berlin` are not in this tree (0 run).

**First run, before the linearity gate.** 703 of 745 passed; the 42 that failed were all side-chain fixtures
(`bcTotalDifficultyTest` 22, `bcMultiChainTest` 14, `bcGasPricerTest/RPC_API_Test` 4, `bcForkStressTest` 2), each reporting
`another node was there when the unit began, and the unit wrote no such node there`: the documented limit of the mirror
(it builds each block on the last accepted one), not a position finding. The gate in `EthereumTestHelper.linear` is the
fix; no check was relaxed.

**The check was shown to fail.** `resetOwner` removed from `InMemoryWorldStateProxy.persistState` (line 155), then
`GeneralStateTests`, Frontier to EIP-158: 24 of 4,905 fail, each with `rows are kept for account <owner>, which has no
trie` (for example `NonZeroValue_SUICIDE_ToOneStorageKey_d0g0v0_{Frontier,Homestead,EIP150,EIP158}`); with the mutation
reverted all 4,905 pass.

**The comparison with the C0 baseline (T007, 2026-11-23): done, section below.**

VERIFY: ran `IntegrationTest / testOnly *LegacyPreByzantiumBlockchainTestsSpec` (the four runs above) — result: PASS
VERIFY: ran the mutation run — result: FAIL as intended (24 fixtures), then PASS reverted

## Per-vector comparison with C0's baseline (B-207, 2026-11-23)

Baseline: `c0/ethtests-2026-11-22-legacy-vectors.tsv.gz` (v0.12.1, 21cfa13; 61,314 rows; the uncompressed TSV's SHA-256
starts `2652b185` and ends `dad7`, checked). Staging: `4ab12f037` plus the report switch below, position mirror on, the same
corpus (ethereum/legacytests `1f581b8`, `Constantinople/BlockchainTests` and `Cancun/BlockchainTests`), run as
`FUKUII_LEGACY_TESTS_DIRS=<legacytests>/Constantinople/BlockchainTests:<legacytests>/Cancun/BlockchainTests`
`FUKUII_LEGACY_TESTS_VECTORS=<file> sbt "IntegrationTest / testOnly *LegacyPreByzantiumBlockchainTestsSpec"`
(cloud container, JDK 21; 1 min 14 s and 19 min for the two slices).

`FUKUII_LEGACY_TESTS_VECTORS` is new: the spec appends one `network, name, file, PASS|FAIL` line per vector run (file
relative to the legacytests root, as in the baseline), for both slices. It changes no verdict. The baseline had to be rebuilt
after its run because the spec reported failures only.

| Network | Baseline | Staging | Staging failed |
| --- | --- | --- | --- |
| Frontier | 1,449 | 1,449 | 0 |
| Homestead | 2,417 | 2,417 | 0 |
| EIP150 | 1,475 | 1,475 | 0 |
| EIP158 | 1,476 | 1,476 | 0 |
| Byzantium | 5,286 | 5,286 | 0 |
| Constantinople | 11,111 | 11,111 | 0 |
| ConstantinopleFix | 11,106 | 11,106 | 0 |
| Istanbul | 12,968 | 12,968 | 0 |
| Berlin | 14,026 | 14,026 | 0 |
| **Total** | **61,314** | **61,314** | **0** |

Diff on (file, name) (61,314 distinct on each side; in the baseline 60,470 are distinct by name alone): in the baseline and
not run on staging 0, run on staging and not in the baseline 0, a (file, name) whose network differs 0, FAIL 0. The two row sets
are equal as multisets of (network, name, file); only the order differs (staging runs the pre-Byzantium slice first).
Staging's list is 61,314 rows, all PASS. The position mirror kept 13,391 + 109,214 units over 6,772 + 54,411 mirrored
fixtures (the other 45 + 86 are side-branch fixtures run unmirrored), 4,244,801 reads checked, no finding.

The ratchet reports exactly what it reported on v0.12.1: both cases fail on "known failures that now pass", the same 85 names
(22 in the pre-Byzantium slice, 63 in the other; the CREATE and CREATE2 high-nonce vectors listed in
`ethtests-2026-11-22.txt`). The known list is still stale (B-432); nothing was changed there.

Still open (T007, B-432): ForkChoiceBlockchainTestsSpec under `basic`, and the report-only run under
`FUKUII_LEGACY_TESTS_NETWORK_TYPE=ETC`.

VERIFY: ran `IntegrationTest / testOnly *LegacyPreByzantiumBlockchainTestsSpec` with the corpus above — result: 61,314 run, 61,314 PASS; both test cases FAIL on the stale known-failure ratchet only, as on v0.12.1
VERIFY: ran the (file, name, network) multiset diff of staging's list against the baseline — result: 0 missing, 0 extra, 0 FAIL, nine per-network counts equal

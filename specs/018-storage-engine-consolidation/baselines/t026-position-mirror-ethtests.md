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

**Not done.** The comparison with `spec/baselines/ethtests-<date>.txt` (T007): that file is a C0 deliverable and is not in
the tree, so the comparison waits on B-053/B-203. The pass counts above are what this spec reports on its own ratchet.

VERIFY: ran `IntegrationTest / testOnly *LegacyPreByzantiumBlockchainTestsSpec` (the four runs above) — result: PASS
VERIFY: ran the mutation run — result: FAIL as intended (24 fixtures), then PASS reverted

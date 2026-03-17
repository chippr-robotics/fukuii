# Fukuii Olympia Hard Fork Implementation

**Branch:** `olympia` (rebased onto `alpha` HEAD `891414967` at v0.1.240)
**Author:** Christopher Mercer (chris-mercer) + Claude Opus 4.6
**Date:** 2026-03-06
**Base:** Alpha stabilization (PR #998) — 54 commits, 2,195 tests passing
**Build:** Scala 3.3.4 LTS, JDK 21, sbt 1.10.7
**Reference:** `chris-mercer/core-geth` branch `olympia` (25 commits)

---

## Summary

The `olympia` branch implements the Olympia hard fork (ECIP-1111, ECIP-1112, ECIP-1121) for Fukuii, bringing EIP-1559 fee market, EVM modernization, and new precompiles to the Ethereum Classic client. This is the Scala-side counterpart of core-geth's Olympia implementation.

### Activation Parameters

| Network | Block Number | Estimated Date |
|---------|-------------|----------------|
| Mordor (testnet) | 15,800,850 | ~March 28, 2026 |
| ETC Mainnet | TBD | ~mid-June 2026 |

### ECIP-1112 Treasury Address

`0xd6165F3aF4281037bce810621F62B43077Fb0e37`

---

## ECIP Alignment

The `olympia` branch builds on the ECIP-1066-aligned `alpha` branch and implements:

- **[ECIP-1111](https://ecips.ethereumclassic.org/ECIPs/ecip-1111)** — Olympia EVM and Protocol Upgrades (EIP-1559 + EIP-3198 + baseFee treasury redirect)
- **[ECIP-1112](https://ecips.ethereumclassic.org/ECIPs/ecip-1112)** — Olympia Treasury Contract (deterministic, immutable vault)
- **[ECIP-1121](https://ecips.ethereumclassic.org/ECIPs/ecip-1121)** — Execution Client Specification Alignment (EVM modernization: 12 additional EIPs)

### Multi-Client References

| Client | Branch | Repository |
|--------|--------|------------|
| core-geth | `olympia` | https://github.com/chris-mercer/core-geth/tree/olympia |
| Besu | `olympia` | https://github.com/chris-mercer/besu/tree/olympia |
| Fukuii | `olympia` | https://github.com/chris-mercer/fukuii/tree/olympia |

### Omitted EIP Note

EIP-7642 (eth/69 — history expiry and simpler receipts) is listed in ECIP-1121 but was **not implemented** in any client because it would require changes to the devp2p protocol that could violate consensus compatibility with non-upgraded nodes. ECIP-1121 will be updated to reflect this omission.

### 60M Gas Limit Target

The Olympia fork raises the default gas limit target from 8M to 60M, matching [EIP-7935](https://eips.ethereum.org/EIPS/eip-7935). Gas limit convergence testing was added across all three clients (core-geth, Besu, Fukuii) to ensure a smooth transition — all three converge from 8M to 60M in exactly 2,055 blocks (~7.4 hours at 13s/block).

### Hive Testing

A multi-client hive testing repository coordinates cross-client consensus validation for the Olympia fork with core-geth, Besu, and Fukuii.

### Gorgoroth Trials (Local Network Testing)

Local devnet testing uses the maintained multi-client forks (chris-mercer/core-geth, chris-mercer/besu, chris-mercer/fukuii) — not the deprecated upstream core-geth (last updated 2024) or upstream Besu.

### Activation Timeline

| Phase | Network | Target |
|-------|---------|--------|
| Gorgoroth trials (local devnet) | core-geth `olympia` + Besu `olympia` + Fukuii `olympia` | Complete |
| Mordor testnet activation | block 15,800,850 | ~March 28, 2026 |
| ETC mainnet activation | TBD (~24,751,337) | ~mid-June 2026 (if Mordor runs smoothly) |

---

## Commits (34)

| # | Hash | Message |
|---|------|---------|
| 1 | `1d9950c54` | feat: add Olympia fork scaffold (ECIP-1111/1112/1121) |
| 2 | `1b35b78c1` | feat: add EIP-1559 baseFee to block headers (Phase 2) |
| 3 | `261888f55` | feat: add EIP-1559 Type-2 dynamic fee transactions (Phase 3) |
| 4 | `55c4d1175` | feat: add ECIP-1111 baseFee treasury redirect (Phase 4) |
| 5 | `ce1da53ac` | feat: add Olympia opcodes BASEFEE, TLOAD, TSTORE, MCOPY (Phase 5) |
| 6 | `b54ef7710` | feat: add Olympia EVM rule changes — EIP-6780, EIP-7825, EIP-7623 (Phase 6) |
| 7 | `98f3ce955` | feat: add Olympia precompiles — P256VERIFY, BLS12-381 stubs, ModExp changes (Phase 7) |
| 8 | `f8fddcaae` | feat: add EIP-2935 block hashes in state (Phase 8) |
| 9 | `508c5c80a` | feat: add EIP-7702 Set EOA Account Code — Type-4 transactions (Phase 9) |
| 10 | `61ae2b1cb` | feat: add EIP-7934 block RLP size cap validation (Phase 10) |
| 11 | `37644ce53` | fix: correct consensus-critical gas constants for EIP-7702 and EIP-7951 |
| 12 | `2ff1e8ab5` | fix: register Olympia types in CompositePickler |
| 13 | `bed59f51b` | test: add comprehensive Olympia test suite (26 tests across 7 files) |
| 14 | `8c040810e` | docs: add OLYMPIA-HANDOFF.md for upstream PR |
| 15 | `8e4e99f7d` | fix: set Mordor treasury address and activation block for Olympia |
| 16 | `dfff8570d` | test: add comprehensive Olympia VM opcode and EIP enablement tests (79 tests) |
| 17 | `0a4ffc04b` | fix: add olympiaBlockNumber to pre-Olympia test fixtures |
| 18 | `55d2d4ecc` | docs: update OLYMPIA-HANDOFF.md for fan-out branch structure |
| 19 | `52f30b7e7` | test: add EIP-7883 ModExp repricing + EIP-7935 gas limit Olympia specs |
| 20 | `3fad29ab0` | fix: resolve post-rebase compilation errors from ECIP removal |
| 21 | `4c027f1ca` | docs: update all documentation for Olympia hard fork (ECIP-1111/1112/1121) |
| 22 | `4fdc2be01` | chore: production-grade Olympia cleanup — fix gas target, stale comments, docs, ECIP refs |
| 23 | `bbe055bfb` | fix(eip-7825): correct TX gas cap from 30M to 2^24 (16,777,216) per spec |
| 24 | `bb56cb61e` | docs(olympia): add cross-client verification methodology and audit findings |
| 25 | `e5657d974` | fix(eip-7883): correct ModExp gas formula for Olympia |
| 26 | `68ef04864` | fix(eip-2537): update BLS12-381 to final spec — 7 precompiles at 0x0b-0x11 |
| 27 | `87b19cbc3` | fix: update treasury comments and Gorgoroth address for ECIP-1111 |
| 28 | `5a4a33364` | test: add Olympia-specific Gorgoroth trial tests |
| 29 | `87d443991` | fix: add boopickle pickler for HeaderExtraFields — fixes SyncControllerSpec |
| 30 | `dfea59730` | rpc: implement EIP-7910 eth_config on olympia — fork schedule, precompiles, system contracts |
| 31 | `0cc6be483` | docs: correct EIP-7825 gas cap references from 30M to 2^24 (16,777,216) |
| 32 | `02c34a81c` | docs: extend test matrix with Olympia EIP/ECIP coverage |
| 33 | `7ce239f76` | docs+ops: olympia deep review — ECIP-1111/1112 testing, rebase cleanup |
| 34 | `680397949` | ops: add shared helper prereqs to olympia-only test scripts |

---

## EIP Implementation Status

### ECIP-1111: EIP-1559 Fee Market + Treasury

| EIP | Description | Status | Files Changed |
|-----|-------------|--------|---------------|
| EIP-1559 | BaseFee header field, Type-2 transactions | Complete | BlockHeader.scala, Transaction.scala, SignedTransaction.scala, BlockPreparator.scala, BaseFeeCalculator.scala |
| ECIP-1111 | Treasury receives baseFee × gasUsed | Complete | BlockPreparator.scala (creditBaseFeeToTreasury) |

### ECIP-1112/1121: EVM Modernization

| EIP | Description | Status | Files Changed |
|-----|-------------|--------|---------------|
| EIP-3198 | BASEFEE opcode | Complete | OpCode.scala |
| EIP-1153 | Transient storage (TLOAD/TSTORE) | Complete | OpCode.scala, ProgramState.scala |
| EIP-5656 | MCOPY opcode | Complete | OpCode.scala |
| EIP-6780 | SELFDESTRUCT restriction | Complete | EvmConfig.scala, BlockPreparator.scala |
| EIP-7623 | Floor data gas cost | Complete | BlockPreparator.scala (calcFloorDataGas) |
| EIP-7825 | Per-tx gas limit cap (2^24 = 16,777,216) | Complete | StdSignedTransactionValidator.scala |
| EIP-2935 | Block hashes in state | Complete | BlockExecution.scala, BlockPreparator.scala |
| EIP-7702 | Set EOA Account Code (Type-4 tx) | Complete | Transaction.scala, SignedTransaction.scala, EvmConfig.scala, StdSignedTransactionValidator.scala |
| EIP-7934 | Block RLP size cap (8 MiB) | Complete | StdBlockValidator.scala |
| EIP-7951 | P256VERIFY precompile | Complete | PrecompiledContracts.scala, Secp256r1.scala |
| EIP-2537 | BLS12-381 precompiles | Stubs only | PrecompiledContracts.scala |
| EIP-2565 | ModExp gas repricing | Complete | PrecompiledContracts.scala |

### BLS12-381 Note

BLS12-381 precompile implementations (EIP-2537) are registered as stubs that consume gas and return failure. This matches the approach used in core-geth's initial Olympia implementation. Full cryptographic implementation requires a JVM-compatible BLS library (e.g., Milagro or Blst JNI bindings) and will be added in a follow-up PR.

---

## Consensus-Critical Bugs Fixed

Three bugs were found and fixed in commits 11–12:

### Bug 1: EIP-7702 Auth Tuple Gas (Commit 11)
- **File:** `vm/EvmConfig.scala:251`
- **Was:** `BigInt(25000)` — double the correct value
- **Fixed:** `BigInt(12500)` — matches core-geth `TxAuthTupleGas = 12_500`
- **Impact:** Would have double-charged intrinsic gas for Type-4 transactions

### Bug 2: EIP-7951 P256VERIFY Gas (Commit 11)
- **File:** `vm/PrecompiledContracts.scala:539`
- **Was:** `BigInt(3450)` — half the correct value
- **Fixed:** `BigInt(6900)` — matches core-geth `P256VerifyGas = 6900`
- **Impact:** Would have half-charged gas for P256 signature verification

### Bug 3: Missing Pickler Registrations (Commit 12)
- **File:** `utils/Picklers.scala`
- **Was:** New Olympia types (TransactionWithDynamicFee, SetCodeTransaction, HefPostOlympia) not registered in boopickle CompositePicklers
- **Fixed:** Added standalone picklers and registered in composite picklers
- **Impact:** Would have caused runtime serialization crashes when persisting Olympia blocks/transactions

---

## Test Coverage

26 new Olympia-specific tests across 7 files, mapped to core-geth test coverage:

### Test File 1: `vm/EIP7702AuthGasSpec.scala` (3 tests)

| Test | core-geth Equivalent |
|------|---------------------|
| auth tuple gas is 12500 | TestEIP7702AuthGas |
| scales linearly with list size | TestEIP7702AuthGasMultiple |
| combines with access list + calldata | TestEIP7702FullIntrinsicGas |

### Test File 2: `vm/P256VerifyGasSpec.scala` (2 tests)

| Test | core-geth Equivalent |
|------|---------------------|
| P256Verify gas is 6900 | TestP256VerifyGas |
| constant regardless of input | TestP256VerifyGasConstant |

### Test File 3: `validators/std/EIP7825GasCapSpec.scala` (4 tests)

| Test | core-geth Equivalent |
|------|---------------------|
| reject tx > 2^24 post-Olympia | TestEIP7825GasCapRejectsOverLimit |
| accept tx at exactly 2^24 | TestEIP7825GasCapAllowsAtLimit |
| accept tx > 2^24 pre-Olympia | TestEIP7825GasCapInactivePreFork |
| constant is 2^24 (16,777,216) | TestEIP7825Constant |

### Test File 4: `validators/std/EIP7623FloorDataGasSpec.scala` (5 tests)

| Test | core-geth Equivalent |
|------|---------------------|
| empty payload = 21000 | TestEIP7623EmptyPayload |
| all-nonzero bytes | TestEIP7623FloorDataGasRejectsLowGas |
| all-zero bytes | TestEIP7623AllZero |
| mixed payload | TestEIP7623FloorDataGasAllowsSufficientGas |
| single zero byte | TestEIP7623SingleByte |

### Test File 5: `validators/std/BlockRLPSizeCapSpec.scala` (3 tests)

| Test | core-geth Equivalent |
|------|---------------------|
| constant is 8388608 | TestEIP7934BlockSizeConstant |
| normal blocks pass | TestEIP7934NormalBlocksPass |
| error contains size and cap | TestEIP7934ErrorDefined |

### Test File 6: `utils/PicklerOlympiaSpec.scala` (5 tests)

| Test | Notes |
|------|-------|
| TransactionWithDynamicFee roundtrip | Verifies boopickle serialization |
| SetCodeTransaction roundtrip | Verifies boopickle serialization |
| HefPostOlympia roundtrip | Verifies boopickle serialization |
| HefPostOlympia with checkpoint | Verifies nested checkpoint serialization |
| Mixed transaction types | Sequential legacy + dynamic fee roundtrip |

### Test File 7: `ledger/TreasuryBaseFeeSpec.scala` (4 tests)

| Test | core-geth Equivalent |
|------|---------------------|
| baseFee × gasUsed credited post-Olympia | TestTreasuryBaseFeeRedirect |
| no credit pre-Olympia | TestTreasuryForkBoundary |
| no credit when gasUsed = 0 | TestTreasuryZeroGasUsed |
| no credit when treasury = Address(0) | TestTreasuryNoAddressNoBurn |

### Test File 8: `vm/OlympiaBaseFeeOpcodeSpec.scala` (9 tests)

| Test | Description |
|------|-------------|
| push baseFee value onto the stack | BASEFEE opcode execution |
| return correct baseFee value | BASEFEE with known baseFee header |
| return zero when baseFee is zero | Edge case: zero baseFee |
| handle large baseFee values | Edge case: 1 Gwei baseFee |
| be usable in arithmetic operations | BASEFEE + BASEFEE |
| cost G_base gas (2) | Gas accounting |
| not be recognized pre-Olympia | Invalid opcode pre-fork |
| include BASEFEE in Olympia opcode list | Config verification |
| not include BASEFEE in Spiral opcode list | Config verification |

### Test File 9: `vm/OlympiaTransientStorageSpec.scala` (20 tests)

| Test | Description |
|------|-------------|
| store and retrieve a value | TSTORE + TLOAD roundtrip |
| return 0 for unset keys | TLOAD default value |
| store/retrieve multiple keys | Multi-key isolation |
| overwrite existing value | TSTORE overwrite |
| clear value by storing zero | TSTORE zero semantics |
| different addresses don't interfere | Per-address isolation |
| preserve transient storage in result | Cross-call propagation |
| not affect persistent storage | Transient vs persistent isolation |
| charge G_warm_storage_read for TLOAD | Gas: 100 gas |
| charge G_warm_storage_read for TSTORE | Gas: 100 gas |
| allow TLOAD in static context | Static context: read OK |
| reject TSTORE in static context | Static context: write rejected |
| reject TLOAD pre-Olympia | Invalid opcode pre-fork |
| reject TSTORE pre-Olympia | Invalid opcode pre-fork |
| include TLOAD/TSTORE in Olympia | Config verification |
| not include TLOAD/TSTORE in Spiral | Config verification |

### Test File 10: `vm/OlympiaMcopySpec.scala` (7 tests)

| Test | Description |
|------|-------------|
| copy non-overlapping memory | Basic MCOPY operation |
| handle zero-size copy as no-op | Edge case: size=0 |
| copy small regions (1 byte) | Minimal copy |
| charge correct gas for zero-size | Gas: G_verylow only |
| reject MCOPY pre-Olympia | Invalid opcode pre-fork |
| include MCOPY in Olympia | Config verification |
| not include MCOPY in Spiral | Config verification |

### Test File 11: `vm/OlympiaSelfDestructSpec.scala` (7 tests)

| Test | Description |
|------|-------------|
| eip6780Enabled flag set | Config flag verification |
| NOT delete pre-existing contract | EIP-6780: balance-only transfer |
| delete contract created in same tx | EIP-6780: same-tx destruction |
| always delete pre-Olympia | Pre-fork: unrestricted |
| handle self-destruct to self | Edge case: self-beneficiary |
| not available in static context | Static context rejection |
| not have eip6780Enabled pre-Olympia | Config flag pre-fork |

### Test File 12: `vm/OlympiaEipEnablementSpec.scala` (23 tests)

| Test | Description |
|------|-------------|
| EIP-1559 enabled/disabled | Fork flag verification |
| EIP-1153 enabled/disabled | Fork flag verification |
| EIP-5656 enabled/disabled | Fork flag verification |
| EIP-6780 enabled/disabled (flag + helper) | Fork flag verification (3 tests) |
| EIP-7702 enabled/disabled | Fork flag verification |
| EIP-2935 enabled/disabled | Fork flag verification |
| EIP-2537 enabled/disabled | Fork flag verification |
| EIP-7951 enabled/disabled | Fork flag verification |
| EIP-2935 system contract address | Constant: 0x...002935 |
| EIP-2935 history window | Constant: 8191 |
| EIP-2935 non-empty contract code | Deployed bytecode exists |
| EIP-2935 code prefix | Starts with CALLER (0x33) |
| Olympia opcode set includes all new opcodes | BASEFEE, TLOAD, TSTORE, MCOPY |
| Spiral opcodes are subset of Olympia | Backward compatibility |
| OlympiaFeeSchedule type | Fee schedule config |
| Mystique fee schedule inheritance | Gas constant values |

### Test File 13: `rpcTest/MordorOlympiaSpec.scala` (13 live tests)

| Test | Description |
|------|-------------|
| connected to Mordor | Chain ID = 63 |
| chain synced past reasonable height | > 10M blocks |
| no baseFee pre-Olympia | Block 1M has no baseFee |
| blocks before fork without baseFee | Fork boundary check |
| baseFee on fork activation block | Initial baseFee > 0 |
| dynamic baseFee after fork | Multiple post-fork blocks |
| valid block structure at fork boundary | Hash, gasLimit, difficulty |
| EIP-2935 contract deployed at fork | Code at 0x...002935 |
| no EIP-2935 contract before fork | Empty pre-fork |
| treasury balance post-fork | Treasury address accessible |
| zero treasury baseFee credit pre-fork | Pre-fork state check |
| valid PoW on post-fork blocks | ETChash still active |
| gas limit around 8M post-fork | Gas limit range |

### Pre-existing Olympia Tests (from Phases 1–10)

| File | Tests | Description |
|------|-------|-------------|
| `eip1559/BaseFeeCalculatorSpec.scala` | 8 | BaseFee calculation, fork boundary, elasticity |
| `domain/BlockHeaderSpec.scala` | 6 | HefPostOlympia encoding/decoding, baseFee extraction |
| `vm/EvmConfigEtcForkSelectionSpec.scala` | 4 | Olympia fork config selection |
| `forkid/ForkIdSpec.scala` | updated | Mordor gatherForks includes 15800850 |

**Total Olympia test count:** ~123 tests (26 original + 66 new VM unit + 13 live RPC + 18 pre-existing)

---

## Files Changed (55 files, +3,100 / -95)

### Production Code (32 files)

| Category | Files |
|----------|-------|
| Chain configs | 4 (etc, gorgoroth, mordor, test) |
| Consensus | BaseFeeCalculator, BlockHeaderValidator, StdBlockValidator, StdSignedTransactionValidator |
| Crypto | Secp256r1 (new — P256 / secp256r1 ECDSA) |
| Domain | BlockHeader, Transaction, SignedTransaction, Receipt |
| Ledger | BlockExecution, BlockPreparator |
| Network | BaseETH6XMessages, ETH63 |
| Utils | BlockchainConfig, Picklers |
| VM | EvmConfig, OpCode, PrecompiledContracts, ProgramContext, ProgramResult, ProgramState, VM, BlockchainConfigForEvm |
| External VM | VMServer |

### Test Code (23 files)

| Category | Files |
|----------|-------|
| New test specs (Phase 13) | 7 (EIP7702AuthGas, P256VerifyGas, EIP7825GasCap, EIP7623FloorDataGas, BlockRLPSizeCap, PicklerOlympia, TreasuryBaseFee) |
| New test specs (Stage 3) | 6 (OlympiaBaseFeeOpcode, OlympiaTransientStorage, OlympiaMcopy, OlympiaSelfDestruct, OlympiaEipEnablement, MordorOlympia) |
| Updated specs | 5 (BaseFeeCalculator, BlockHeader, EvmConfigEtcForkSelection, ForkId, VMSpec) |
| Test infrastructure | 5 (ObjectGenerators, BlockHelpers, Tags, Fixtures, VMClientSpec) |

---

## Cross-Client Verification Methodology

Every EIP and ECIP in the Olympia fork is verified using a six-client cross-verification process:

**Reference Clients (ETH production):**
- [go-ethereum](https://github.com/ethereum/go-ethereum) — canonical Go implementation
- [Erigon](https://github.com/erigontech/erigon) — optimized Go implementation
- [Nethermind](https://github.com/NethermindEth/nethermind) — .NET implementation

**ETC Clients (implementation targets):**
- core-geth (`chris-mercer/core-geth`) — Go, forked from go-ethereum
- Fukuii (`chris-mercer/fukuii`) — Scala/JVM, forked from Mantis
- Besu (`chris-mercer/besu`) — Java/JVM, forked from Hyperledger Besu

**Process (per EIP/ECIP):**
1. Read the canonical EIP specification at `eips.ethereum.org`
2. Verify implementation in all 3 ETH production clients (constants, formulas, gas costs, addresses)
3. Verify implementation in all 3 ETC clients against both the spec and ETH implementations
4. Cross-compare ETC clients against each other for consistency
5. Document any discrepancies with severity (consensus-critical vs. cosmetic)

**Catches from this process:**
- **EIP-7825:** All 3 ETC clients had `MaxTxGas = 30,000,000`. ETH clients all use `1 << 24 = 16,777,216` per final spec. Corrected in all 3 ETC clients.
- **EIP-7883:** Fukuii's `PostEIP7883Cost` had 3 formula bugs (wrong `multComplexity`, wrong `adjExpLen` multiplier 8→16, wrong divisor 3→1 and minimum 200→500). Caught by comparing against go-ethereum's `osakaModexpGas()`.
- **EIP-2537:** Core-geth and Fukuii both used the OLD 9-precompile draft (addresses 0x0a-0x12/0x13) instead of the final 7-precompile spec (0x0b-0x11). Gas costs also diverged significantly. Caught by comparing against go-ethereum, Nethermind, and Besu (all correct).
- **EIP-7951:** Fukuii initially had `P256VerifyGas = 3450` (half the correct 6,900). Caught by cross-referencing core-geth.
- **EIP-7702:** Fukuii initially had `TxAuthTupleGas = 25000` (double the correct 12,500). Caught by cross-referencing core-geth.

This methodology ensures implementation parity across all ETC clients and consistency with ETH production client behavior for shared EIPs.

---

## Known Limitations

1. **BLS12-381 stubs** — EIP-2537 precompiles are gas-consuming stubs, not full implementations
2. **No EIP-4844 blob transactions** — Not part of ETC Olympia scope
3. **No execution spec tests** — Relies on Fukuii's existing test infrastructure rather than Ethereum execution spec test vectors
4. **Transaction pool** — Does not yet enforce EIP-1559 fee ordering in mempool (accepts Type-2 txs but orders by gasPrice)

---

## How to Test

```bash
cd /media/dev/2tb/dev/fukuii/fukuii-client

# Compile
sbt compile

# Run all tests (~2,309)
sbt test

# Run only Olympia tests (~123)
sbt "testOnly *BaseFeeCalculatorSpec *BlockHeaderSpec *EIP7702AuthGasSpec *P256VerifyGasSpec *EIP7825GasCapSpec *EIP7623FloorDataGasSpec *BlockRLPSizeCapSpec *PicklerOlympiaSpec *TreasuryBaseFeeSpec *EvmConfigEtcForkSelectionSpec *OlympiaBaseFeeOpcodeSpec *OlympiaTransientStorageSpec *OlympiaMcopySpec *OlympiaSelfDestructSpec *OlympiaEipEnablementSpec"

# Run Olympia live RPC tests (requires running fukuii on Mordor)
sbt "rpcTest:testOnly *MordorOlympiaSpec"
```

---

## Review Focus Areas

1. **Gas constants** — Verify `EvmConfig.scala:251` (12500) and `PrecompiledContracts.scala:539` (6900) match core-geth
2. **Treasury credit order** — `BlockPreparator.creditBaseFeeToTreasury()` must run after block reward, before world state persist
3. **Pickler ordering** — New types appended to END of CompositePickler registrations (boopickle uses index as discriminator)
4. **BaseFee calculation** — `BaseFeeCalculator.scala` elasticity multiplier and target gas usage
5. **Type-4 transaction signing** — `SignedTransaction.scala` EIP-7702 signature scheme with y-parity
6. **Transient storage isolation** — `ProgramState.scala` transient storage cleared per transaction, not per call

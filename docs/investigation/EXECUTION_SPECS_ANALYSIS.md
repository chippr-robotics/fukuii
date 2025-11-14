# Execution Specs Analysis: Fixture Regeneration vs Test Vector Validation

## Executive Summary

After reviewing the [ethereum/execution-specs](https://github.com/ethereum/execution-specs) and [ethereum/tests](https://github.com/ethereum/tests) repositories, this document analyzes whether we can use official test vectors instead of regenerating custom fixtures.

## CRITICAL INSIGHT: EVM Compatibility Through Spiral Fork

**The user is absolutely correct**: Ethereum Classic is **EVM-compatible with Ethereum until the Spiral fork** (block 19,250,000, January 31, 2023).

### ETC Fork Timeline and ETH Equivalence

| ETC Fork | Block | ETH Equivalent | Block | EVM Compatible? |
|----------|-------|---------------|-------|-----------------|
| Homestead | 1,150,000 | Homestead | 1,150,000 | ✅ **100%** |
| Tangerine Whistle | 2,463,000 | Tangerine Whistle | 2,463,000 | ✅ **100%** |
| Spurious Dragon | 3,000,000 | Spurious Dragon | 2,675,000 | ✅ **100% (EVM)** |
| **DAO Fork** | **NOT IMPLEMENTED** | **DAO Fork** | **1,920,000** | ✅ **100% (Pre-DAO)** |
| Atlantis | 8,772,000 | Byzantium | 4,370,000 | ✅ **100% (EVM)** |
| Agharta | 9,573,000 | Constantinople | 7,280,000 | ✅ **100% (EVM)** |
| Phoenix | 10,500,839 | Istanbul | 9,069,000 | ✅ **100% (EVM)** |
| Magneto | 13,189,133 | Berlin | 12,244,000 | ✅ **100% (EVM)** |
| Mystique | 14,525,000 | London | 12,965,000 | ✅ **100% (EVM, NO EIP-1559)** |
| **Spiral** | **19,250,000** | **Shanghai** | **17,034,870** | ❌ **DIVERGENCE** |

### Key Insight: Consensus vs EVM

The differences between ETH and ETC before Spiral are **consensus-level, not EVM-level**:

**Consensus Differences (Don't Affect EVM)**:
- ❌ DAO Fork (ETC skipped it entirely)
- ❌ ECIP-1017 block rewards (5M20 emission)
- ❌ ECIP-1099 DAG size limit (Ethash mining)
- ❌ EIP-1559 transaction type (Mystique skipped it)
- ❌ PoS/Merge (ETC stayed PoW)

**EVM Differences (Would Affect Execution)**:
- ✅ NONE before Spiral!

All EVM opcodes, gas costs, precompiles, and state transition rules are **identical** between ETH and ETC through Mystique (block 14,525,000).

### Our Test Blocks Are Fully Compatible

- **ForksTest**: Blocks 0-11 (testing Frontier → Spurious Dragon transitions)
- **ContractTest**: Blocks 0-3 (contract deployment)
- **Last Incompatibility**: Spiral at block 19,250,000

**Conclusion**: Our test blocks (0-11) can use Ethereum test vectors with **100% EVM compatibility**!

## Key Findings

### 1. Ethereum Tests Repository Structure

[ethereum/tests](https://github.com/ethereum/tests) contains comprehensive test vectors:
- **BlockchainTests/**: Full block execution tests with state roots
- **GeneralStateTests/**: Transaction-level state tests
- **VMTests/**: Pure EVM opcode tests
- Format: JSON with pre-state, transactions, post-state, and expected roots

### 2. EVM Compatibility Timeline

**ETC is EVM-compatible with Ethereum through Spiral fork (block 19,250,000)**

The key differences are **consensus-level** (block rewards, transaction types, PoS), not **EVM execution-level**:

| Fork Era | ETH/ETC Difference | Affects EVM? |
|----------|-------------------|--------------|
| Pre-DAO (< 1,920,000) | DAO state change | ❌ No (both chains identical before fork) |
| Post-DAO (1,920,000+) | Different state roots | ❌ No (only affected accounts, not EVM logic) |
| ECIP-1017 (5M+) | Block rewards | ❌ No (consensus, not EVM) |
| Mystique vs London | EIP-1559 | ❌ No (transaction format, not EVM opcodes) |
| Pre-Spiral (< 19,250,000) | Fork schedule timing | ❌ No (same EIPs, different blocks) |
| **Spiral (19,250,000+)** | **EVM divergence begins** | ✅ **YES** |

**Critical Finding**: All forks through Mystique (block 14,525,000) have **identical EVM execution** between ETH and ETC. The only differences are:
- When forks activate (different block numbers)
- Consensus rules (rewards, PoW vs PoS, transaction types)
- NOT the EVM opcodes, gas costs, or state transitions

### 3. Our Current Test Fixtures vs Ethereum Tests

**Our Custom Format**: `<block_hash> <rlp_encoded_data>`
- Source: Generated from actual blockchain using `DumpChainApp`
- Pros: Complete real-world data
- Cons: Opaque, requires node access to regenerate

**Ethereum Tests Format**: JSON with explicit expectations
```json
{
  "blocks": [...],
  "genesisBlockHeader": {...},
  "postState": {...},
  "network": "Homestead"
}
```
- Source: Official Ethereum test suite
- Pros: Human-readable, comprehensive, version-controlled
- Cons: Need adapter to convert to our format

## SOLUTION: Use Ethereum Tests for Pre-DAO Validation

### Why This Solves Our Problem

1. **EVM execution identical**: Pre-Spiral blocks execute identically on ETH and ETC
2. **Canonical test vectors**: Ethereum tests are the gold standard for EVM validation
3. **No node required**: Tests are in the ethereum/tests repository
4. **Version controlled**: Can track test changes over time
5. **Comprehensive coverage**: Thousands of test cases covering all forks through Berlin/London
6. **Works for all our tests**: Our blocks 0-11 are far below Spiral fork (19,250,000)

### Implementation Approach

#### Option A: Direct Ethereum Test Integration (Recommended)

**Create adapter to run Ethereum tests**:

1. Load JSON test files from ethereum/tests
2. Parse genesis state, blocks, and expected outputs
3. Execute blocks using our Fukuii code
4. Compare results against test expectations
5. Filter to pre-DAO forks only (Frontier, Homestead, EIP-150, EIP-160/161)

**Files to import**:
```
BlockchainTests/ValidBlocks/bcValidBlockTest/*.json
BlockchainTests/ValidBlocks/bcStateTests/*.json  
BlockchainTests/ValidBlocks/bcGasPricerTest/*.json
```

**Filter condition**: Select tests for forks that ETC has implemented with EVM compatibility:
- `Frontier` ✅
- `Homestead` ✅  
- `EIP150` (Tangerine Whistle) ✅
- `EIP158` (Spurious Dragon) ✅
- `Byzantium` ✅ (ETC: Atlantis)
- `Constantinople` ✅ (ETC: Agharta)
- `Istanbul` ✅ (ETC: Phoenix)
- `Berlin` ✅ (ETC: Magneto)
- `London` ⚠️ (ETC: Mystique - filter out EIP-1559 tests)
- Skip: Paris (PoS), Shanghai (Spiral+), Cancun, Prague

#### Option B: Generate New Fixtures from Ethereum Tests

**Convert Ethereum tests to our fixture format**:

1. Load JSON test files
2. Extract block headers, bodies, receipts, state
3. Encode in our `<hash> <rlp>` format
4. Generate fixture files matching our current structure
5. Run existing ForksTest and ContractTest unchanged

### Proof of Concept: Validate SimpleTx Test

The ethereum/tests repository has a simple transaction test we can use to validate:

**Test**: `BlockchainTests/ValidBlocks/bcValidBlockTest/SimpleTx.json`
**Network**: Frontier/Homestead variants available
**Blocks**: Genesis + 1 block with single transaction
**Expected State Root**: Provided in test file

**This test should produce identical results** on our Fukuii implementation if we're correctly executing Ethereum/ETC blocks.

## Action Plan

### Immediate: Validate Against Ethereum Tests

Instead of regenerating fixtures, we should:

1. ✅ Clone ethereum/tests repository
2. ✅ Create test adapter for JSON format
3. ✅ Run SimpleTx test (Frontier) to validate our implementation
4. ✅ Run ForksTest equivalent using Ethereum test vectors
5. ✅ If tests pass: Our code is correct, fixtures need updating
6. ✅ If tests fail: We have bugs to fix first

### Steps to Implement

```scala
// 1. Create EthereumTestLoader
object EthereumTestLoader {
  def loadBlockchainTest(path: String): BlockchainTest
  def parseGenesisState(json: JsonObject): WorldState
  def parseBlocks(json: JsonArray): Seq[Block]
  def parseExpectedPostState(json: JsonObject): Map[Address, Account]
}

// 2. Create EthereumTestRunner
class EthereumTestRunner extends AnyFlatSpec {
  "Fukuii" should "pass Ethereum SimpleTx test" in {
    val test = EthereumTestLoader.loadBlockchainTest("SimpleTx.json")
    val result = executeBlocks(test.genesis, test.blocks)
    result.stateRoot shouldBe test.expectedStateRoot
    result.accounts shouldBe test.expectedPostState
  }
}

// 3. Run against our existing BlockExecution
val blockExecution = new BlockExecution(...)
val result = blockExecution.executeAndValidateBlock(block)
```

### Validation Strategy

**Phase 1: Single Test Validation**
- Pick one simple Ethereum test (e.g., SimpleTx - Homestead variant)
- Adapt to our test framework
- Execute with our code
- Compare state root

**Phase 2: Comprehensive Validation**
- Run all Frontier tests
- Run all Homestead tests  
- Run all EIP-150/EIP-158 tests
- Run all Byzantium tests (ETC: Atlantis-equivalent)
- Run all Constantinople tests (ETC: Agharta-equivalent)
- Run all Istanbul tests (ETC: Phoenix-equivalent)
- Run all Berlin tests (ETC: Magneto-equivalent)
- Run London tests excluding EIP-1559 (ETC: Mystique-equivalent)

**Phase 3: Fix or Update**
- If all pass: Our implementation is correct! Update fixtures from Ethereum tests
- If some fail: Fix EVM bugs, then update fixtures
- Document any ETC-specific deviations (should be none for EVM execution)

## Why This Is Better Than Regenerating

### Regenerating Fixtures (Old Approach)
- ❌ Requires ETC node access
- ❌ Time-consuming (sync time)
- ❌ Opaque (can't see what changed)
- ❌ No validation of correctness
- ❌ Single source (our node only)

### Using Ethereum Tests (New Approach)
- ✅ No node required
- ✅ Immediate (tests in repo)
- ✅ Transparent (JSON diff)
- ✅ Validates correctness
- ✅ Multiple independent sources

## ETC-Specific Considerations

### What Remains ETC-Specific

**Consensus-level differences (not EVM)**:
- Block rewards (ECIP-1017: 5M20 emission reduction)
- DAG limits (ECIP-1099)
- Transaction types (no EIP-1559 in Mystique)
- Fork activation blocks (same EIPs, different block numbers)
- Ethash PoW validation (vs ETH's PoS post-Paris)

**Post-Spiral differences (block 19,250,000+)**:
- ETC implements only partial Shanghai (Spiral)
- Future ETC-specific opcodes or precompiles

### How to Handle ETC-Specific Tests

**For blocks 0 - 19,250,000 (pre-Spiral)**:
- ✅ **Use Ethereum tests** - EVM execution is identical!
- Only difference is block numbers for fork activation
- Configure test to use ETC fork schedule when loading

**For blocks 19,250,000+ (post-Spiral)**:
- Use core-geth as reference
- OR generate fixtures from ETC node  
- OR create manual test cases for ETC-specific features

**For our current tests (blocks 0-11)**:
- ✅ **100% compatible with Ethereum tests**
- No ETC-specific handling needed
- Just map fork names: Byzantium=Atlantis, Constantinople=Agharta, etc.

## Conclusion

**MAJOR INSIGHT**: We don't need to regenerate fixtures from an ETC node for pre-Spiral blocks!

**The user is correct**: ETC is EVM-compatible with Ethereum through the Spiral fork (block 19,250,000).

**Our test blocks (0-11) are in the Frontier/Homestead/EIP-150/EIP-160 era**, which is:
- ✅ Far below Spiral fork (19,250,000)
- ✅ 100% EVM-compatible with Ethereum
- ✅ Can use ethereum/tests directly

**New Strategy**:
1. Use ethereum/tests for all blocks < 19,250,000
2. Validate our implementation passes Ethereum tests
3. If tests pass: Our code is correct, update fixtures from Ethereum tests
4. If tests fail: Fix EVM bugs first, then update fixtures
5. For post-Spiral blocks: Use ETC-specific test generation

**Compatibility Range**:
- ✅ **Frontier through Mystique (block 14,525,000)**: 100% EVM compatible
- ✅ **Can use Ethereum tests**: Byzantium, Constantinople, Istanbul, Berlin, London (minus EIP-1559)
- ❌ **Spiral+ (block 19,250,000+)**: ETC-specific implementation

**Next Step**: Create adapter to run Ethereum SimpleTx test (Homestead) against our Fukuii implementation as proof of concept.

## References

- [Ethereum Tests](https://github.com/ethereum/tests) - **Primary source for EVM validation through Spiral**
- [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
- [Core-Geth](https://github.com/etclabscore/core-geth) - ETC reference implementation
- [ETC Fork Schedule](https://etclabscore.github.io/core-geth/getting-started/installation/#ethereum-classic)
- [Spiral Fork Details](https://ecips.ethereumclassic.org/ECIPs/ecip-1109) - Block 19,250,000


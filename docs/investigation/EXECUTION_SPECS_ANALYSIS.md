# Execution Specs Analysis: Fixture Regeneration vs Test Vector Validation

## Executive Summary

After reviewing the [ethereum/execution-specs](https://github.com/ethereum/execution-specs) repository, this document analyzes whether we can use official test vectors instead of regenerating custom fixtures.

## Key Findings

### 1. Execution Specs Scope
- **Target**: Ethereum mainnet specification and test vectors
- **Coverage**: Frontier through Prague forks
- **Format**: JSON-based state tests with pre/post state expectations
- **Purpose**: Validate EVM execution, state transitions, and consensus rules

### 2. Ethereum vs Ethereum Classic Differences

| Aspect | Ethereum (execution-specs) | Ethereum Classic (fukuii) |
|--------|---------------------------|---------------------------|
| Consensus | Proof-of-Stake (Paris+) | Proof-of-Work (Ethash) |
| DAO Fork | Implemented (block 1920000) | NOT implemented |
| EIP-1559 | Implemented (London) | NOT implemented |
| Hard Forks | ETH-specific schedule | ETC-specific (Atlantis, Agharta, Phoenix, Magneto, Mystique) |
| Difficulty Bomb | Delayed multiple times | Modified (ECIP-1099) |
| Block Rewards | Changed with PoS | ECIP-1017 (5M20 reduction) |

### 3. Our Current Test Fixtures

**Format**: Custom `<block_hash> <rlp_encoded_data>` format
**Source**: Generated from actual ETC blockchain using `DumpChainApp`
**Content**:
- Complete block headers with ETC-specific fields
- Full transaction history with ETC consensus
- State trie nodes matching ETC execution
- Receipt formats (pre/post Byzantium)

## Analysis: Can We Use Execution Specs?

### ✅ What We CAN Validate

1. **Core EVM Opcodes**: Use execution-specs state tests to validate our EVM implementation
   - Arithmetic operations
   - Stack/memory operations
   - Storage operations
   - Control flow
   - Call operations

2. **Common Consensus Rules**: 
   - Gas calculation (pre-EIP-1559)
   - Transaction validation
   - Account state management
   - Trie operations

3. **Shared Hard Forks**:
   - Frontier
   - Homestead
   - EIP-150 (Tangerine Whistle)
   - EIP-155/160/161 (Spurious Dragon)
   - Byzantium (similar to ETC Atlantis)
   - Constantinople (similar to ETC Agharta)

### ❌ What We CANNOT Validate

1. **ETC-Specific Consensus**:
   - ECIP-1017 block rewards (5M20 emission reduction)
   - ECIP-1099 DAG size limit
   - Ethash PoW validation
   - Uncle reward calculations
   - ETC-specific hard fork transitions

2. **Different Transaction Types**:
   - ETC doesn't support EIP-1559 transactions
   - ETC doesn't support blob transactions (EIP-4844)
   - Different transaction encoding post-Berlin

3. **State Root Calculations**:
   - Execution-specs tests won't match ETC block state roots
   - Different genesis states
   - Different block reward recipients
   - Different uncle handling

## Our Test Failure Root Cause

### The Real Problem

Our `ForksTest` and `ContractTest` failures show:
```
Expected: 794c3c380c4272a3e69d83a7dee16d885c5e956674eddf2302788cdfbf0a8a3b
Actual:   225ce73da683bb17cd073a9c008b73ce25b6474a6fc32bd66836e3d6a8
```

**State roots are completely different**, indicating:
1. Different account states after execution
2. Different trie construction
3. Different encoding/serialization
4. OR: Fixtures generated with different code (Scala 2 vs Scala 3)

### Why Execution Specs Won't Help

The execution-specs test vectors:
- Don't include ETC-specific blocks
- Don't match ETC consensus rules
- Don't validate against ETC state roots
- Use different genesis configurations

## Recommended Approach

### Option 1: Regenerate Fixtures (RECOMMENDED)

**Why**: Ensures tests match current Scala 3 implementation

**Process**:
1. Connect `DumpChainApp` to synced ETC node
2. Extract blocks 0-11 for `forksTest`
3. Extract blocks 0-3 for `purchaseContract`
4. Replace existing fixtures
5. Tests should pass with regenerated data

**Pros**:
- ✅ Validates actual ETC execution
- ✅ Tests consensus-critical code
- ✅ Matches our architecture
- ✅ Quick once node access available

**Cons**:
- ❌ Requires ETC node access
- ❌ Time-dependent (sync time)

### Option 2: Adapt Execution Specs Tests

**Why**: Validate core EVM without fixtures

**Process**:
1. Import ethereum/execution-specs state tests
2. Filter for pre-PoS tests (Frontier through Istanbul)
3. Adapt JSON format to our test framework
4. Skip ETC-specific consensus validation
5. Focus on EVM opcode correctness

**Pros**:
- ✅ No node access needed
- ✅ Comprehensive EVM coverage
- ✅ Community-maintained tests
- ✅ Catch EVM bugs

**Cons**:
- ❌ Doesn't test ETC consensus
- ❌ Doesn't validate state roots
- ❌ Requires test adapter implementation
- ❌ Won't fix current failures

### Option 3: Hybrid Approach

**Combine both strategies**:

1. **Short-term**: Regenerate fixtures to fix failing tests
2. **Long-term**: Add execution-specs tests for EVM validation

**Benefits**:
- ✅ Immediate test fix
- ✅ Enhanced EVM coverage
- ✅ Future-proof testing
- ✅ Best of both worlds

## Conclusion

**For the current test failures (ForksTest/ContractTest)**, we MUST regenerate fixtures because:

1. Tests validate **full block execution** including ETC consensus
2. State roots must match **ETC blockchain state**
3. Execution-specs are **Ethereum mainnet**, not ETC
4. Our architecture requires **ETC-specific test data**

**For future test enhancement**, we SHOULD add execution-specs tests because:

1. Validates **core EVM** independent of consensus
2. Catches **opcode-level bugs**
3. Provides **comprehensive coverage**
4. Aligns with **industry standards**

## Action Items

### Immediate (Fix Current Failures)
- [ ] Obtain access to synced ETC node
- [ ] Run `DumpChainApp` to regenerate fixtures
- [ ] Verify ForksTest and ContractTest pass

### Future (Enhanced Testing)
- [ ] Create execution-specs test adapter
- [ ] Import relevant state tests (Frontier-Istanbul)
- [ ] Filter out PoS and EIP-1559 tests
- [ ] Add to CI/CD pipeline

## References

- [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
- [Core-Geth (ETC Reference)](https://github.com/etclabscore/core-geth)
- [ECIP-1017 (5M20)](https://ecips.ethereumclassic.org/ECIPs/ecip-1017)
- [ECIP-1099 (DAG Limit)](https://ecips.ethereumclassic.org/ECIPs/ecip-1099)

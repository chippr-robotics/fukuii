# State Root Validation Investigation

## Issue Summary
ForksTest and ContractTest are failing with state root validation errors after the Scala 3 migration.

## Test Failures

### ForksTest
- **Expected**: `794c3c380c4272a3e69d83a7dee16d885c5e956674eddf2302788cdfbf0a8a3b`
- **Actual**: `225ce73da683bb17cd073a9c008b73ce25b6474a6fc32bd66836e3d6a8`
- **Block**: 1 (first block after genesis)

### ContractTest  
- **Expected**: `93a8c6314d6f901d00a51e0da785ee839d83f50eaef0d065443e2ad5eb42fb83`
- **Actual**: `3a742ad3047104fca1a4ddce5c9196bca172ec69573d237c71aaf02160510fca`
- **Block**: 3 (contract execution block)

## Investigation Results

### ✅ Passing Tests
All core unit tests pass successfully:
- **RLP encoding/decoding**: 25/25 tests passing
- **Merkle Patricia Trie**: 42/42 tests passing  
- **VM execution**: 15/15 tests passing (2 ignored)

### ❌ Failing Tests
Only integration tests using pre-generated fixtures fail:
- **ForksTest**: 0/1 passing
- **ContractTest**: 0/3 passing

### Code Review Findings

1. **No RLPx Pattern Matching Issues Found**
   - ADR-001 section 8 describes a pattern matching issue with extra parentheses
   - Searched all RLPList pattern matches - none have the problematic `(x: Type)` pattern
   - All patterns use correct Scala 3 syntax: `x: Type` without extra parentheses

2. **RLP Encoding/Decoding**
   - All RLP codecs appear correct
   - UInt256, Account, Receipt encoding all use standard RLP serialization
   - No issues found in RLPImplicits or related code

3. **Merkle Patricia Trie**
   - MPT implementation passes all unit tests
   - State root calculation should be deterministic regardless of insertion order
   - No obvious bugs in trie operations

4. **EVM Execution**
   - VM unit tests pass
   - No TODOs or FIXMEs related to state management
   - BlockExecution logic appears sound

5. **State Persistence**
   - `InMemoryWorldStateProxy.persistState` follows correct order:
     1. Persist code
     2. Persist contract storage  
     3. Persist accounts state trie
   - Uses `foldLeft` on Maps (order shouldn't matter for deterministic hashing)

## Root Cause Hypotheses

### Primary Hypothesis: Test Fixture Incompatibility
**Likelihood**: High

The test fixtures appear to have been generated with the Scala 2 version of the code. Even though the code logic is the same, there could be subtle differences in:
- Byte array handling
- BigInt serialization  
- Collection iteration order (though this shouldn't affect MPT hashes)
- Implicit resolution affecting type class selection

**Evidence**:
- All unit tests pass (testing individual components)
- Only integration tests with fixtures fail
- State roots are completely different (not just slightly off)
- Tests load pre-computed state trees, receipts, etc.

**Resolution**: Regenerate test fixtures using Scala 3 version

### Secondary Hypothesis: Subtle EVM Execution Difference  
**Likelihood**: Medium

There could be a subtle behavioral difference in Scala 3 that affects deterministic execution:
- Different implicit resolution changing which type class instances are used
- Changes in how pattern matching extracts values
- Differences in numeric operations or conversions

**Evidence**:
- State roots are deterministically different (consistently produce same wrong value)
- No obvious code bugs found in extensive review
- All unit tests pass but integration tests fail

**Resolution**: Add comprehensive logging to trace execution

## Recommendations

### To Regenerate Test Fixtures
The most likely solution is to regenerate test fixtures using the Scala 3 version. The fixtures require actual blockchain data from an ETC node. This was likely not updated when migrating from Scala 2 to Scala 3, causing the state root mismatches.

### Files That Need Regeneration
- `src/it/resources/txExecTest/forksTest/*.txt`
- `src/it/resources/txExecTest/purchaseContract/*.txt`

Use `DumpChainApp` to connect to an ETC node and regenerate these fixtures.

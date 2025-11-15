# Gas Calculation Issues - Investigation Required

## Summary

During Phase 3 implementation of ethereum/tests integration, we discovered gas calculation discrepancies between our EVM implementation and the official ethereum/tests expectations. **Gas calculation must be identical** - these differences indicate potential bugs or missing EIP implementations.

## Status: 🔴 BLOCKED - Code Review Required

**Current Test Results:**
- ✅ 84 tests passing (SimpleTx, ExtraData32, dataTx, and others)
- ⚠️ 35 tests failing (mostly gas calculation and state root issues)
- 🔴 **3 critical gas calculation discrepancies identified**

## Critical Gas Calculation Issues

### Issue 1: add11 Test (Berlin Network)
**Test:** BlockchainTests/GeneralStateTests/stExample/add11.json  
**Network:** Berlin  
**Expected Gas:** 43,112  
**Actual Gas:** 41,012  
**Difference:** 2,100 gas  

**Description:** Basic ADD opcode test `(add 1 1)` shows gas calculation mismatch

**Contract Code:** `0x600160010160005500`
- PUSH1 0x01
- PUSH1 0x01  
- ADD
- PUSH1 0x00
- SSTORE
- STOP

### Issue 2: addNonConst Tests (Berlin Network)
**Test:** BlockchainTests/GeneralStateTests/stArgsZeroOneBalance/addNonConst.json  
**Network:** Berlin  
**Multiple test cases showing consistent 900 gas difference:**

1. `addNonConst_d0g0v0_Berlin`: Expected 23,412 → Actual 22,512 (diff: 900)
2. `addNonConst_d0g0v1_Berlin`: Expected 43,312 → Actual 42,412 (diff: 900)

**Description:** ADD with non-constant values (PUSH with addresses)

**Contract Code:** `0x73095e7baea6a6c7c4c2dfeb977efac326af552d873173095e7baea6a6c7c4c2dfeb977efac326af552d87310160005500`

### Issue 3: Istanbul Tests
**Status:** Similar patterns observed in Istanbul network tests  
**Note:** Most Istanbul tests are passing, suggesting the issue is Berlin-specific

## Root Cause Analysis

### Likely Cause: EIP-2929 Implementation

**EIP-2929: Gas cost increases for state access opcodes**
- Activated in Berlin fork
- Increases gas costs for SLOAD, *CALL, BALANCE, EXT* opcodes
- Introduces "cold" vs "warm" storage access costs

**Evidence:**
1. Consistent gas difference pattern (900 and 2100)
2. Only affects Berlin network tests
3. Tests involve SSTORE operations (state access)

### Gas Difference Breakdown

For add11 (2100 gas difference):
- SSTORE cold access in Berlin: 22,100 gas (was 20,000 in Istanbul)
- Possible missing: cold access surcharge

For addNonConst (900 gas difference):
- Multiple PUSH operations with addresses
- Possible missing: address access gas costs

## Investigation Required

### Immediate Actions
1. ✅ **DONE:** Flag all gas calculation discrepancies
2. ✅ **DONE:** Document specific test failures with gas differences
3. 🔴 **REQUIRED:** Review EIP-2929 implementation in VM
4. 🔴 **REQUIRED:** Verify gas cost tables for Berlin fork
5. 🔴 **REQUIRED:** Compare with geth/nethermind implementations

### Code Review Checklist
- [ ] Review `src/main/scala/com/chipprbots/ethereum/vm/VM.scala` for EIP-2929 implementation
- [ ] Check gas cost configuration for Berlin fork in `BlockchainConfig`
- [ ] Verify SSTORE gas calculation includes cold/warm access logic
- [ ] Verify SLOAD gas calculation includes cold/warm access logic
- [ ] Check *CALL opcode family gas costs
- [ ] Verify access list handling for EIP-2930

### Testing Strategy
1. Run isolated opcode tests for SSTORE, SLOAD in Berlin
2. Compare gas costs with geth using `debug_traceTransaction`
3. Test with and without access lists (EIP-2930)
4. Validate against ethereum/tests GeneralStateTests

## Impact Assessment

### Severity: HIGH
Gas calculation errors affect:
- Transaction cost estimation
- Block validation
- Network consensus
- User experience (transaction costs)

### Affected Components
- EVM execution engine
- Block validation
- State transition logic
- Gas metering

### Risk
If gas calculations are incorrect:
- Blocks may be incorrectly validated/rejected
- Incompatibility with Ethereum mainnet blocks
- Potential consensus failures
- Transaction cost misestimation

## Resolution Plan

### Phase 1: Investigation (1-2 days)
1. Review EIP-2929 specification
2. Audit current gas cost implementation
3. Identify specific missing/incorrect logic
4. Create test cases for gas cost validation

### Phase 2: Fix Implementation (2-3 days)
1. Implement correct EIP-2929 gas costs
2. Update gas cost tables
3. Add cold/warm storage access tracking
4. Verify all opcodes affected by EIP-2929

### Phase 3: Validation (1-2 days)
1. Re-run all failing tests
2. Verify 100% gas calculation accuracy
3. Run comprehensive ethereum/tests suite
4. Compare with reference implementations

## Blocking Items

**CANNOT PROCEED with Phase 3 CI integration until:**
1. Gas calculation discrepancies are resolved
2. All GeneralStateTests pass with correct gas usage
3. Code review confirms correct EIP-2929 implementation

## References

- [EIP-2929: Gas cost increases for state access opcodes](https://eips.ethereum.org/EIPS/eip-2929)
- [EIP-2930: Optional access lists](https://eips.ethereum.org/EIPS/eip-2930)
- [ethereum/tests Repository](https://github.com/ethereum/tests)
- ADR-015: Ethereum/Tests Adapter Implementation

## Contact

For questions or to assign this investigation, contact the team lead.

---
**Created:** November 15, 2025  
**Status:** INVESTIGATION REQUIRED  
**Priority:** HIGH  
**Blocking:** Phase 3 CI Integration

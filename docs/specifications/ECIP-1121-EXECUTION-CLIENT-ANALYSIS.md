# ECIP-1121: Execution Client Specification Alignment Analysis

**Document Version:** 1.0  
**Date:** 2025-12-30  
**Status:** Research & Planning Phase  
**Target:** Execution Client Specification Alignment for Olympia Hardfork

## Executive Summary

This document provides a comprehensive analysis of ECIP-1121 "Execution Client Specification Alignment" and its implementation requirements for the Fukuii Ethereum Classic client. ECIP-1121 documents the Ethereum execution-layer protocol specifications that ETC execution clients should implement to remain parallel with Ethereum, excluding fee-market governance (handled in ECIP-1111) and blob-based data availability mechanics.

### Key Findings

1. **ECIP-1121 is a Meta-Specification** - It consolidates execution-layer specs from Ethereum's Fusaka, Pectra, and Dencun forks
2. **Explicitly Excludes EIP-1559/EIP-3198** - These are handled separately in ECIP-1111 (Olympia fee market upgrade)
3. **Focuses on Execution-Layer Only** - No consensus, fee market, or data availability changes
4. **13 EIPs to Implement** - Covering gas accounting, EVM safety, cryptography, and optimizations
5. **Fukuii Currently Supports Up to Spiral** - Significant gap to fill for ECIP-1121 compliance

### ECIP-1121 Scope

**Included:** Execution-layer specifications from ETH that are:
- Compatible with Proof-of-Work
- Independent of blob data availability  
- Independent of fee market governance
- Pure EVM/execution behavior

**Explicitly Excluded:**
- EIP-1559 and EIP-3198 (handled in ECIP-1111)
- All Proof-of-Stake related EIPs
- All blob-related EIPs (4788, 4844, 7516, etc.)
- Beacon Chain dependencies

## ECIP-1121 Specification Overview

**Status:** Draft  
**Type:** Meta  
**Created:** 2025-12-14  
**PR:** https://github.com/ethereumclassic/ECIPs/pull/554

### Relationship to Olympia ECIP Set

ECIP-1121 is the execution-layer bookend for the Olympia ECIP option set:
- **ECIP-1111:** Olympia meta-spec (EIP-1559, EIP-3198, Treasury)
- **ECIP-1112-1120:** Treasury, governance, and fee-handling options
- **ECIP-1121:** Execution-layer alignment (THIS SPECIFICATION)

### Ethereum Forks Covered

- **Fusaka (Fulu-Osaka):** Latest Ethereum execution layer
- **Pectra (Prague-Electra):** Prague execution + Electra consensus
- **Dencun (Cancun-Deneb):** Cancun execution + Deneb consensus

## Current Fukuii State Analysis

### Supported Features (as of Spiral - ECIP-1109)

From `src/main/resources/conf/base/chains/etc-chain.conf`:

| Hardfork | Block | EIPs Included | Status |
|----------|-------|---------------|--------|
| Spiral | 19,250,000 | EIP-3855 (PUSH0), EIP-3651 (warm COINBASE), EIP-3860 (initcode limits) | ✅ Active |
| Mystique | 14,525,000 | EIP-3529 (refund reduction) | ✅ Active |
| Magneto | 13,189,133 | Berlin-equivalent | ✅ Active |
| Phoenix | 10,500,839 | Istanbul-equivalent | ✅ Active |

### ECIP-1121 Coverage Gap

**Current State:** Fukuii supports EIPs up to Spiral (roughly Berlin + some Istanbul)

**ECIP-1121 Requirements:** EIPs from Cancun, Pectra, and Fusaka forks

**Gap:** All 13 EIPs listed in ECIP-1121 are NOT yet implemented in Fukuii

## ECIP-1121 Required EIPs - Detailed Analysis

### Category 1: Gas Accounting and State Access (5 EIPs)

#### EIP-7702: Set EOA Account Code
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7702  
**Description:** Add a new transaction type that permanently sets the code for an Externally Owned Account (EOA)

**Impact Assessment:**
- **High Complexity** - Introduces new transaction type (Type-4)
- Allows EOAs to have code like smart contracts
- Requires transaction processing changes
- Requires account state changes
- Security implications for account model

**Implementation Requirements:**
- New transaction type with authorization tuple
- Transaction validation for delegation
- Account state storage for delegated code
- RLP encoding/decoding for Type-4 transactions
- JSON-RPC support for new transaction format

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala`
- `src/main/scala/com/chipprbots/ethereum/domain/Account.scala`
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`

**Estimated Effort:** Large (2-3 weeks)

#### EIP-7623: Increase Calldata Cost
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7623  
**Description:** Increase calldata cost to reduce maximum block size

**Impact Assessment:**
- **Medium Complexity** - Gas cost adjustment
- Reduces block size by making calldata more expensive
- Two-tier pricing: standard and floor pricing

**Implementation Requirements:**
- Update gas cost calculation for calldata
- Add floor gas price logic
- Update transaction validation
- Update fee schedule

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/FeeSchedule.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/`
- Transaction execution logic

**Estimated Effort:** Medium (1 week)

#### EIP-7825: Transaction Gas Limit Cap
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7825  
**Description:** Cap maximum gas used by a transaction to 16,777,216 (2^24)

**Impact Assessment:**
- **Low Complexity** - Simple validation rule
- Protocol-level cap on transaction gas
- Prevents extremely large transactions

**Implementation Requirements:**
- Add validation constant MAX_TX_GAS = 2^24
- Validate transaction gas limit <= MAX_TX_GAS
- Reject transactions exceeding cap

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`
- Configuration files

**Estimated Effort:** Small (1-2 days)

#### EIP-7883: MODEXP Gas Cost Increase
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7883  
**Description:** Increases cost of ModExp precompile (address 0x05)

**Impact Assessment:**
- **Low Complexity** - Gas cost adjustment for precompile
- Security fix: previous cost underpriced actual computation

**Implementation Requirements:**
- Update ModExp precompile gas calculation
- Implement new pricing formula
- Fork-aware activation

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala`
- ModExp gas calculation

**Estimated Effort:** Small (2-3 days)

#### EIP-7935: Set Default Gas Limit to 60 Million
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7935  
**Description:** Recommend new default gas limit value for Fusaka

**Impact Assessment:**
- **Low Complexity** - Configuration change
- Increases default gas limit from 30M to 60M
- Node operators can still override

**Implementation Requirements:**
- Update default gas limit in configuration
- Update client defaults
- Documentation updates

**Files Affected:**
- `src/main/resources/conf/base/chains/*.conf`
- Configuration documentation

**Estimated Effort:** Trivial (< 1 day)

---

### Category 2: EVM Safety and Forward Compatibility (4 EIPs)

#### EIP-7934: RLP Execution Block Size Limit
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7934  
**Description:** Cap maximum RLP-encoded block size to 10 MiB (with 2 MiB margin)

**Impact Assessment:**
- **Medium Complexity** - Block validation rule
- Prevents excessively large blocks
- Total limit: 10 MiB including beacon block overhead

**Implementation Requirements:**
- Add RLP size calculation for blocks
- Validate total RLP size <= 10 MiB
- Reject blocks exceeding limit
- Performance consideration for size calculation

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/BlockValidator.scala`
- `src/main/scala/com/chipprbots/ethereum/domain/Block.scala`

**Estimated Effort:** Medium (1 week)

#### EIP-6780: SELFDESTRUCT Only in Same Transaction
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-6780  
**Description:** SELFDESTRUCT recovers funds but doesn't delete account, except when called in same transaction as creation

**Impact Assessment:**
- **High Complexity** - Major EVM semantics change
- Critical for future state growth management
- Changes SELFDESTRUCT behavior fundamentally

**Implementation Requirements:**
- Track contract creation within transaction
- Modify SELFDESTRUCT opcode behavior
- Full deletion only if created in same tx
- Otherwise: transfer balance, keep account
- Update all SELFDESTRUCT handling

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` (SELFDESTRUCT)
- `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/`

**Estimated Effort:** Large (2 weeks)

#### EIP-7642: eth/69 - History Expiry and Simpler Receipts
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7642  
**Description:** Adds history serving window and removes bloom filter in receipts

**Impact Assessment:**
- **High Complexity** - Network protocol upgrade + storage changes
- New eth/69 protocol version
- Receipt format changes (remove bloom filters)
- History expiry mechanism

**Implementation Requirements:**
- Implement eth/69 protocol messages
- Update receipt format (remove bloom)
- Implement history serving window
- Maintain backward compatibility with eth/68
- Update network message handling

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/` (new ETH69.scala)
- `src/main/scala/com/chipprbots/ethereum/domain/Receipt.scala`
- `src/main/scala/com/chipprbots/ethereum/db/storage/ReceiptStorage.scala`
- Network handshake logic

**Estimated Effort:** Large (3-4 weeks)

#### EIP-7910: eth_config JSON-RPC Method
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7910  
**Description:** JSON-RPC method describing current and next fork configuration

**Impact Assessment:**
- **Low Complexity** - New RPC method
- Improves tooling by exposing fork config
- No consensus impact

**Implementation Requirements:**
- Add eth_config RPC method
- Return current fork configuration
- Return next scheduled fork
- JSON serialization for fork data

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/EthInfoService.scala` (new)
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcController.scala`

**Estimated Effort:** Small (2-3 days)

---

### Category 3: Cryptographic and Precompile Enhancements (2 EIPs)

#### EIP-2537: Precompile for BLS12-381 Curve Operations
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-2537  
**Description:** Adds BLS12-381 curve operations as precompiles for efficient signature verification

**Impact Assessment:**
- **Very High Complexity** - New cryptographic primitives
- Requires BLS12-381 curve implementation
- 9 new precompiled contracts (0x0a through 0x12)
- Critical for cross-chain interoperability

**Precompiles to Implement:**
- BLS12_G1ADD (0x0a)
- BLS12_G1MUL (0x0b)
- BLS12_G1MULTIEXP (0x0c)
- BLS12_G2ADD (0x0d)
- BLS12_G2MUL (0x0e)
- BLS12_G2MULTIEXP (0x0f)
- BLS12_PAIRING (0x10)
- BLS12_MAP_FP_TO_G1 (0x11)
- BLS12_MAP_FP2_TO_G2 (0x12)

**Implementation Requirements:**
- Implement or integrate BLS12-381 library
- Gas cost calculations for each operation
- Input validation and error handling
- Extensive testing with test vectors

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala`
- New crypto library integration
- Build configuration for dependencies

**Estimated Effort:** Very Large (4-6 weeks) - May require cryptography expert

#### EIP-7951: Precompile for secp256r1 Curve Support
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-7951  
**Description:** Add precompile for secp256r1 ECDSA signature verification

**Impact Assessment:**
- **High Complexity** - New cryptographic primitive
- secp256r1 (P-256) is widely used in hardware wallets and TLS
- Enables account abstraction scenarios
- Precompile address: TBD in spec

**Implementation Requirements:**
- Implement secp256r1 signature verification
- Security checks for malleability
- Gas cost calculation
- Input validation
- Test vectors

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala`
- Crypto library integration

**Estimated Effort:** Large (2-3 weeks)

---

### Category 4: Execution Context Optimizations (3 EIPs)

#### EIP-5656: MCOPY - Memory Copying Instruction
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-5656  
**Description:** Efficient EVM instruction for copying memory areas

**Impact Assessment:**
- **Medium Complexity** - New opcode
- Opcode: MCOPY (0x5E)
- More efficient than manual copy loops
- Gas cost: 3 + 3*words_copied + memory_expansion_cost

**Implementation Requirements:**
- Implement MCOPY opcode
- Memory copying logic
- Gas cost calculation
- Add to appropriate opcode list

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`

**Estimated Effort:** Medium (1 week)

#### EIP-2935: Save Historical Block Hashes in State
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-2935  
**Description:** Store and serve last 8191 block hashes as storage slots in system contract

**Impact Assessment:**
- **Very High Complexity** - System contract + consensus change
- Enables stateless execution
- Requires special system contract at address (TBD)
- Stores 8191 block hashes (vs current 256)

**Implementation Requirements:**
- Deploy system contract at genesis/fork
- Update block finalization to write to contract
- Modify BLOCKHASH opcode to read from contract
- Handle transition from old to new system
- Ring buffer logic for 8191 entries

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` (BLOCKHASH)
- System contract deployment logic
- Genesis/fork initialization

**Estimated Effort:** Very Large (3-4 weeks)

#### EIP-1153: Transient Storage Opcodes
**Status:** ❌ Not Implemented  
**Specification:** https://eips.ethereum.org/EIPS/eip-1153  
**Description:** Add opcodes for transient storage (cleared after each transaction)

**Impact Assessment:**
- **High Complexity** - New storage type + opcodes
- Two new opcodes: TLOAD (0x5C), TSTORE (0x5D)
- Transient storage: like storage but discarded after tx
- Useful for reentrancy locks, temporary data

**Implementation Requirements:**
- Add transient storage to ProgramState
- Implement TLOAD and TSTORE opcodes
- Clear transient storage after each transaction
- Gas costs: warm storage costs (100 gas)
- No refunds for transient storage

**Files Affected:**
- `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`

**Estimated Effort:** Large (2 weeks)

---

## Summary of ECIP-1121 EIPs

| EIP | Category | Complexity | Effort | Priority |
|-----|----------|------------|--------|----------|
| EIP-7702 | Gas/State Access | High | 2-3 weeks | High |
| EIP-7623 | Gas/State Access | Medium | 1 week | Medium |
| EIP-7825 | Gas/State Access | Low | 1-2 days | High |
| EIP-7883 | Gas/State Access | Low | 2-3 days | Medium |
| EIP-7935 | Gas/State Access | Low | < 1 day | Low |
| EIP-7934 | Safety | Medium | 1 week | High |
| EIP-6780 | Safety | High | 2 weeks | Critical |
| EIP-7642 | Safety | High | 3-4 weeks | High |
| EIP-7910 | Safety | Low | 2-3 days | Low |
| EIP-2537 | Cryptography | Very High | 4-6 weeks | High |
| EIP-7951 | Cryptography | High | 2-3 weeks | Medium |
| EIP-5656 | Optimization | Medium | 1 week | Medium |
| EIP-2935 | Optimization | Very High | 3-4 weeks | High |
| EIP-1153 | Optimization | High | 2 weeks | High |

**Total Estimated Effort:** 25-38 weeks (6-9 months) if done sequentially

## Deferred and Excluded Specifications

### Explicitly Deferred (Blob-Related)

These are NOT in ECIP-1121 because ETC doesn't implement blobs:
- EIP-4788: Beacon block root in EVM
- EIP-4844: Shard blob transactions
- EIP-7516: BLOBBASEFEE opcode
- EIP-7691: Blob throughput increase
- EIP-7840: Add blob schedule to config
- EIP-7892: Blob parameter hardforks
- EIP-7918: Blob base fee bounds

**Status:** Deferred indefinitely (ETC has no blob support)

### Explicitly Excluded (PoS-Related)

These are NOT in ECIP-1121 because ETC uses PoW:
- EIP-4788: Beacon block root
- EIP-7044: Perpetually valid exits
- EIP-7045: Max attestation inclusion slot
- EIP-7514: Max epoch churn limit
- EIP-7251: Increase MAX_EFFECTIVE_BALANCE
- EIP-7002: Execution layer exits
- EIP-7685: General purpose requests
- EIP-6110: Supply validator deposits
- EIP-7549: Move committee index
- EIP-7917: Deterministic proposer lookahead

**Status:** Permanently excluded (ETC uses PoW, not PoS)

### Excluded (Fee Market - Handled in ECIP-1111)

- **EIP-1559:** Fee market change (in ECIP-1111)
- **EIP-3198:** BASEFEE opcode (in ECIP-1111)

**Status:** Separate implementation track in ECIP-1111

## Implementation Strategy

### Phase 1: Foundation (4-6 weeks)

**Quick Wins - Low Complexity:**
1. EIP-7825: Transaction gas limit cap (1-2 days)
2. EIP-7935: Default gas limit to 60M (< 1 day)
3. EIP-7883: MODEXP gas cost increase (2-3 days)
4. EIP-7910: eth_config RPC method (2-3 days)

**Medium Complexity:**
5. EIP-7623: Increase calldata cost (1 week)
6. EIP-7934: RLP block size limit (1 week)

**Total Phase 1:** ~3 weeks (overlapping work)

### Phase 2: Core EVM Changes (8-10 weeks)

**Opcodes and Storage:**
1. EIP-5656: MCOPY opcode (1 week)
2. EIP-1153: Transient storage (2 weeks)
3. EIP-6780: SELFDESTRUCT changes (2 weeks) - CRITICAL

**System Contracts:**
4. EIP-2935: Historical block hashes (3-4 weeks)

**Total Phase 2:** ~8-9 weeks

### Phase 3: Advanced Features (10-14 weeks)

**Transaction Types:**
1. EIP-7702: Set EOA account code (2-3 weeks)

**Network Protocol:**
2. EIP-7642: eth/69 protocol (3-4 weeks)

**Cryptography:**
3. EIP-2537: BLS12-381 precompiles (4-6 weeks) - Requires crypto expert
4. EIP-7951: secp256r1 precompile (2-3 weeks)

**Total Phase 3:** ~11-16 weeks

### Total Timeline

**Sequential:** 22-35 weeks (5.5-8.5 months)  
**With Parallelization:** ~18-28 weeks (4.5-7 months)

**Recommended:** Start with Phase 1 to get early wins, then parallel work on Phases 2 and 3.

## Testing Requirements

### Per-EIP Testing

Each EIP requires:
- Unit tests for new opcodes/precompiles
- Integration tests for transaction processing
- Cross-fork boundary tests
- Gas cost validation tests
- Edge case and error handling tests

### Ethereum Test Suite

- Run official Ethereum test vectors for each EIP
- Validate against reference implementations
- Cross-client comparison testing

### Testnet Validation

- Deploy to Gorgoroth private testnet first
- Coordinate Mordor testnet activation
- Multi-client testing with Core-Geth/Besu
- Extended validation period (4-8 weeks)

### Performance Testing

- Block processing benchmarks
- New precompile performance
- Memory/storage overhead
- Network protocol performance

## Risk Assessment

### Critical Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| EIP-6780 SELFDESTRUCT changes breaking contracts | Critical | Extensive testing, community notification, testnet validation |
| EIP-2537 BLS12-381 implementation errors | Critical | Use proven crypto libraries, formal verification, security audit |
| EIP-7642 eth/69 protocol incompatibilities | High | Cross-client testing, backward compatibility, phased rollout |
| EIP-2935 system contract vulnerabilities | High | Audit, formal verification, extensive testing |
| EIP-7702 EOA delegation security issues | High | Security review, test attack vectors, clear documentation |

### Medium Risks

| Risk | Severity | Mitigation |
|------|----------|------------|
| Performance degradation from new opcodes | Medium | Benchmarking, optimization, profiling |
| Gas cost miscalculations | Medium | Reference implementation comparison, test vectors |
| Network fragmentation during rollout | Medium | Coordinated activation, clear communication |
| Storage overhead from EIP-2935 | Medium | Monitor disk usage, document requirements |

### Implementation Complexity

**Highest Complexity (>4 weeks each):**
- EIP-2537: BLS12-381 (cryptography expertise required)
- EIP-2935: Historical block hashes (system contract)
- EIP-7642: eth/69 protocol (network protocol)

**Medium-High Complexity (2-3 weeks each):**
- EIP-7702: Set EOA code
- EIP-6780: SELFDESTRUCT changes
- EIP-7951: secp256r1 precompile
- EIP-1153: Transient storage

## Resource Requirements

### Development Team

**Core Blockchain Engineers (2):**
- Consensus layer changes
- Transaction processing
- Block validation
- System contracts

**EVM Engineer (1):**
- Opcode implementations
- VM state management
- Gas calculations

**Cryptography Engineer (1):**
- EIP-2537 (BLS12-381)
- EIP-7951 (secp256r1)
- Security review

**Network Engineer (1):**
- EIP-7642 (eth/69)
- Protocol upgrades
- P2P testing

**QA Engineer (1):**
- Test suite development
- Testnet coordination
- Validation testing

### External Dependencies

**Critical Blockers:**
- ECIP-1121 finalization (currently Draft in PR #554)
- Activation block numbers (Mordor and Mainnet)
- Community consensus on priority
- Cross-client coordination (Core-Geth, Besu)

**Library Dependencies:**
- BLS12-381 cryptography library (Scala/Java)
- secp256r1 signature library
- Updated test vectors from Ethereum

## Coordination Requirements

### Cross-Client Coordination

**Core-Geth:**
- Implementation status of ECIP-1121 EIPs
- Test vector sharing
- Testnet coordination
- Activation timing

**Hyperledger Besu:**
- ETC support roadmap
- Implementation coordination
- Testing collaboration

### Community Coordination

**Stakeholders:**
- Node operators
- Miners
- Exchanges
- dApp developers
- Infrastructure providers

**Communication Plan:**
- Announce implementation roadmap
- Regular progress updates
- Testnet activation notices
- Mainnet upgrade guidance

## Open Questions and Blockers

### Critical Questions

1. **ECIP-1121 Finalization:**
   - When will PR #554 be merged?
   - Are there any changes expected to the EIP list?

2. **Activation Strategy:**
   - Should ECIP-1121 be one hardfork or phased?
   - What is the target timeline?
   - Mordor testnet activation block?
   - Mainnet activation block?

3. **Priority and Scope:**
   - Are all 13 EIPs mandatory for first release?
   - Can we phase implementation (e.g., critical EIPs first)?
   - What is minimum viable implementation?

4. **Relationship to ECIP-1111:**
   - Should ECIP-1121 be implemented before, after, or with ECIP-1111?
   - Any dependencies between the two?

5. **Cryptography Implementation:**
   - Recommended library for BLS12-381?
   - Security audit requirements?
   - Available cryptography expertise?

### Technical Blockers

1. **EIP-2537 (BLS12-381):**
   - Requires specialized cryptography expertise
   - May need external crypto library integration
   - Extensive security testing required

2. **EIP-2935 (Historical Block Hashes):**
   - System contract deployment mechanism
   - Genesis/fork initialization changes
   - Storage format decisions

3. **EIP-7642 (eth/69):**
   - Backward compatibility with eth/68
   - Receipt format migration strategy
   - Network upgrade coordination

4. **EIP-7702 (Set EOA Code):**
   - Security implications for account model
   - Authorization mechanism implementation
   - Edge case handling

## Success Criteria

### Technical Success

- [ ] All 13 ECIP-1121 EIPs implemented correctly
- [ ] Pass Ethereum test vectors for each EIP
- [ ] Cross-client consensus on testnet
- [ ] No performance regression
- [ ] All unit tests passing (>90% coverage)
- [ ] Integration tests passing
- [ ] Testnet validation successful (8+ weeks)

### Operational Success

- [ ] Smooth Mordor testnet activation
- [ ] No critical bugs in testnet
- [ ] Mainnet activation without forks
- [ ] Node operators upgraded successfully
- [ ] No service disruptions

### Community Success

- [ ] Clear documentation available
- [ ] Stakeholders informed and prepared
- [ ] Positive community feedback
- [ ] Cross-client coordination successful

## Next Steps

### Immediate Actions (This Week)

1. **Await ECIP-1121 Finalization**
   - Monitor PR #554 for merge
   - Review any last-minute changes
   - Confirm final EIP list

2. **Stakeholder Communication**
   - Present this analysis to community
   - Discuss priority and timeline
   - Gather feedback on phasing strategy

3. **Technical Planning**
   - Identify available resources
   - Assess cryptography expertise
   - Plan team assignments

### Short-Term (Next Month)

1. **Begin Phase 1 Implementation**
   - Start with quick wins (EIP-7825, 7935, 7883, 7910)
   - Establish testing infrastructure
   - Set up development environment

2. **Coordinate with Other Clients**
   - Reach out to Core-Geth team
   - Share implementation plans
   - Establish communication channel

3. **Library Research**
   - Identify BLS12-381 library candidates
   - Evaluate secp256r1 options
   - Assess security and performance

### Medium-Term (Next Quarter)

1. **Phase 1 Completion**
   - Complete low-complexity EIPs
   - Validate with tests
   - Deploy to private testnet

2. **Begin Phase 2**
   - Start core EVM changes
   - Implement critical EIP-6780
   - Begin system contract work

3. **Testnet Preparation**
   - Prepare Gorgoroth deployment
   - Coordinate Mordor timeline
   - Set up monitoring

## Appendix A: File-Level Change Mapping

### Consensus Layer
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/BlockValidator.scala` - EIP-7934
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala` - EIP-2935
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala` - Multiple EIPs

### Transaction Layer
- `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala` - EIP-7702
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala` - EIP-7825

### EVM Layer
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` - EIP-5656, 1153, 6780, 2935
- `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala` - EIP-1153, 6780
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala` - EIP-2537, 7951, 7883
- `src/main/scala/com/chipprbots/ethereum/vm/FeeSchedule.scala` - EIP-7623
- `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` - Fork configuration

### Network Layer
- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/` - New ETH69.scala for EIP-7642
- `src/main/scala/com/chipprbots/ethereum/domain/Receipt.scala` - EIP-7642

### Storage Layer
- `src/main/scala/com/chipprbots/ethereum/db/storage/ReceiptStorage.scala` - EIP-7642

### RPC Layer
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/` - EIP-7910, EIP-7702 support

### Configuration
- `src/main/resources/conf/base/chains/*.conf` - EIP-7935, fork activation

## Appendix B: Reference Resources

### ECIP-1121 Resources
- **PR #554:** https://github.com/ethereumclassic/ECIPs/pull/554
- **Discussions:** https://github.com/ethereumclassic/ECIPs/discussions/530
- **ECIPs Repository:** https://github.com/ethereumclassic/ECIPs

### EIP Specifications
All EIP links: https://eips.ethereum.org/EIPS/eip-XXXX

### Ethereum Forks
- **Fusaka:** https://ethereum.org/roadmap/fusaka/
- **Pectra:** https://ethereum.org/roadmap/pectra/
- **Cancun-Deneb:** https://github.com/ethereum/execution-specs/blob/master/network-upgrades/mainnet-upgrades/cancun.md

### Test Resources
- **Ethereum Tests:** https://github.com/ethereum/tests
- **Execution Specs:** https://github.com/ethereum/execution-specs

---

**Document Version:** 1.0  
**Last Updated:** 2025-12-30  
**Next Review:** Upon ECIP-1121 finalization (PR #554 merge)

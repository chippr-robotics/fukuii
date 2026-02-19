# ECIP-1121 Execution Client Implementation Checklist

**Status:** Planning Phase  
**Target:** Execution Client Specification Alignment  
**Last Updated:** 2025-12-30

This checklist tracks all work items required to implement ECIP-1121 "Execution Client Specification Alignment" in Fukuii. ECIP-1121 covers 13 EIPs from Ethereum's Fusaka, Pectra, and Dencun forks that are applicable to ETC's execution layer.

**Note:** This is SEPARATE from ECIP-1111 (which handles EIP-1559/EIP-3198/Treasury). ECIP-1121 focuses exclusively on execution-layer specifications.

**Legend:**
- 🔴 Critical / Consensus-Critical
- 🟡 High Priority
- 🟢 Medium Priority  
- ⚪ Low Priority
- ⏱️ Effort: XS (<1d), S (1-2d), M (3-5d), L (1-2w), XL (2+w)

---

## 📋 Pre-Implementation Phase

### Research & Specification Analysis
- [x] 🟢 Fetch and review ECIP-1121 specification from PR #554 (⏱️ S)
- [x] 🟢 Review all 13 included EIPs (⏱️ L)
- [x] 🟢 Analyze current Fukuii state vs ECIP-1121 requirements (⏱️ M)
- [x] 🟢 Create comprehensive analysis document (⏱️ L)
- [x] 🟢 Create implementation checklist (⏱️ S)
- [ ] 🟡 Review Core-Geth ECIP-1121 implementation status (⏱️ M)
- [ ] 🟡 Review Hyperledger Besu plans (⏱️ M)
- [ ] 🟡 Obtain Ethereum test vectors for each EIP (⏱️ M)

### Architecture & Design
- [ ] 🟡 Design phased implementation approach (⏱️ M)
- [ ] 🟡 Create ADR for ECIP-1121 implementation strategy (⏱️ M)
- [ ] 🟡 Design EIP-7702 (EOA code) architecture (⏱️ L)
- [ ] 🟡 Design EIP-2935 (block hashes) system contract (⏱️ L)
- [ ] 🟡 Design EIP-7642 (eth/69) protocol changes (⏱️ L)
- [ ] 🟡 Design EIP-1153 (transient storage) architecture (⏱️ M)
- [ ] 🟢 Plan testing strategy for each EIP (⏱️ M)

### External Dependencies
- [ ] 🔴 Wait for ECIP-1121 PR #554 to merge (⏱️ N/A - External)
- [ ] 🟡 Obtain Mordor testnet activation block number (⏱️ N/A - External)
- [ ] 🟡 Obtain mainnet activation block number (⏱️ N/A - External)
- [ ] 🟡 Identify BLS12-381 cryptography library for Scala/JVM (⏱️ M)
- [ ] 🟡 Identify secp256r1 library for Scala/JVM (⏱️ M)
- [ ] 🟢 Coordinate with Core-Geth team (⏱️ N/A - External)
- [ ] 🟢 Coordinate with Besu team (⏱️ N/A - External)

---

## 🔧 Phase 1: Quick Wins (Low Complexity EIPs)

### EIP-7825: Transaction Gas Limit Cap
**Effort:** 1-2 days | **Priority:** 🟡 High

- [ ] 🔴 Define MAX_TX_GAS constant (2^24 = 16,777,216) (⏱️ XS)
- [ ] 🔴 Add validation in transaction validator (⏱️ XS)
- [ ] 🔴 Reject transactions with gasLimit > MAX_TX_GAS (⏱️ XS)
- [ ] 🟡 Add unit tests for gas limit validation (⏱️ S)
- [ ] 🟡 Add edge case tests (exactly at limit, above limit) (⏱️ S)

**Files:** `src/main/scala/com/chipprbots/ethereum/consensus/validators/std/StdSignedTransactionValidator.scala`

### EIP-7935: Set Default Gas Limit to 60 Million
**Effort:** < 1 day | **Priority:** ⚪ Low

- [ ] ⚪ Update default gas limit in etc-chain.conf to 60M (⏱️ XS)
- [ ] ⚪ Update default gas limit in mordor-chain.conf to 60M (⏱️ XS)
- [ ] ⚪ Update default gas limit in gorgoroth-chain.conf to 60M (⏱️ XS)
- [ ] ⚪ Update documentation on gas limit recommendation (⏱️ XS)

**Files:** `src/main/resources/conf/base/chains/*.conf`

### EIP-7883: MODEXP Gas Cost Increase
**Effort:** 2-3 days | **Priority:** 🟢 Medium

- [ ] 🔴 Review new MODEXP gas cost formula (⏱️ S)
- [ ] 🔴 Update ModExp precompile gas calculation (⏱️ S)
- [ ] 🔴 Add fork-aware activation (⏱️ S)
- [ ] 🟡 Add unit tests for new gas costs (⏱️ M)
- [ ] 🟡 Compare with reference implementation (⏱️ S)
- [ ] 🟡 Test edge cases (large exponents, etc.) (⏱️ M)

**Files:** `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala`

### EIP-7910: eth_config JSON-RPC Method
**Effort:** 2-3 days | **Priority:** ⚪ Low

- [ ] 🟡 Design eth_config response format (⏱️ S)
- [ ] 🟡 Implement eth_config RPC method (⏱️ M)
- [ ] 🟡 Return current fork configuration (⏱️ S)
- [ ] 🟡 Return next scheduled fork (⏱️ S)
- [ ] 🟡 Add JSON serialization for fork data (⏱️ S)
- [ ] 🟢 Add RPC tests (⏱️ M)
- [ ] 🟢 Update API documentation (⏱️ S)

**Files:** 
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/` (new EthConfigService)
- `src/main/scala/com/chipprbots/ethereum/jsonrpc/JsonRpcController.scala`

### EIP-7623: Increase Calldata Cost
**Effort:** 1 week | **Priority:** 🟢 Medium

- [ ] 🔴 Implement two-tier calldata pricing (⏱️ M)
- [ ] 🔴 Add standard calldata cost calculation (⏱️ M)
- [ ] 🔴 Add floor calldata cost calculation (⏱️ M)
- [ ] 🔴 Update transaction gas calculation (⏱️ M)
- [ ] 🟡 Add tests for both pricing tiers (⏱️ M)
- [ ] 🟡 Validate against test vectors (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/vm/FeeSchedule.scala`
- Transaction execution logic

### EIP-7934: RLP Execution Block Size Limit
**Effort:** 1 week | **Priority:** 🟡 High

- [ ] 🔴 Define MAX_BLOCK_RLP_SIZE constant (10 MiB) (⏱️ XS)
- [ ] 🔴 Implement RLP size calculation for blocks (⏱️ M)
- [ ] 🔴 Add block size validation (⏱️ M)
- [ ] 🔴 Reject blocks exceeding limit (⏱️ S)
- [ ] 🟡 Add unit tests for size calculation (⏱️ M)
- [ ] 🟡 Test edge cases (near limit, at limit, over limit) (⏱️ M)
- [ ] 🟡 Performance test for size calculation (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/BlockValidator.scala`
- `src/main/scala/com/chipprbots/ethereum/domain/Block.scala`

---

## 🔧 Phase 2: Core EVM Changes

### EIP-5656: MCOPY - Memory Copying Instruction
**Effort:** 1 week | **Priority:** 🟢 Medium

- [ ] 🔴 Define MCOPY opcode (0x5E) (⏱️ S)
- [ ] 🔴 Implement memory copy logic (⏱️ M)
- [ ] 🔴 Implement gas cost calculation (3 + 3*words + expansion) (⏱️ M)
- [ ] 🔴 Add MCOPY to appropriate opcode list (⏱️ XS)
- [ ] 🔴 Update EvmConfig for fork activation (⏱️ S)
- [ ] 🟡 Add unit tests for MCOPY (⏱️ M)
- [ ] 🟡 Test memory expansion scenarios (⏱️ M)
- [ ] 🟡 Test edge cases (zero length, overlap, etc.) (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala`

### EIP-1153: Transient Storage Opcodes
**Effort:** 2 weeks | **Priority:** 🟡 High

- [ ] 🔴 Add transient storage map to ProgramState (⏱️ M)
- [ ] 🔴 Implement TLOAD opcode (0x5C) (⏱️ M)
- [ ] 🔴 Implement TSTORE opcode (0x5D) (⏱️ M)
- [ ] 🔴 Set gas costs (100 gas for warm access) (⏱️ S)
- [ ] 🔴 Clear transient storage after transaction (⏱️ M)
- [ ] 🔴 Add opcodes to opcode list (⏱️ XS)
- [ ] 🔴 Update EvmConfig (⏱️ S)
- [ ] 🟡 Add comprehensive unit tests (⏱️ L)
- [ ] 🟡 Test transient storage isolation (⏱️ M)
- [ ] 🟡 Test reentrancy scenarios (⏱️ M)
- [ ] 🟡 Validate against test vectors (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`

### EIP-6780: SELFDESTRUCT Only in Same Transaction
**Effort:** 2 weeks | **Priority:** 🔴 Critical

- [ ] 🔴 Track contract creation in transaction context (⏱️ M)
- [ ] 🔴 Modify SELFDESTRUCT opcode behavior (⏱️ L)
- [ ] 🔴 Implement: full deletion only if created in same tx (⏱️ M)
- [ ] 🔴 Implement: transfer balance + keep account otherwise (⏱️ M)
- [ ] 🔴 Update all SELFDESTRUCT handling logic (⏱️ L)
- [ ] 🔴 Update state change tracking (⏱️ M)
- [ ] 🟡 Add extensive unit tests (⏱️ L)
- [ ] 🟡 Test same-transaction destruction (⏱️ M)
- [ ] 🟡 Test cross-transaction behavior (⏱️ M)
- [ ] 🟡 Test edge cases (nested calls, etc.) (⏱️ M)
- [ ] 🟡 Validate against test vectors (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/ProgramState.scala`
- `src/main/scala/com/chipprbots/ethereum/ledger/`

### EIP-2935: Save Historical Block Hashes in State
**Effort:** 3-4 weeks | **Priority:** 🟡 High

#### System Contract Design
- [ ] 🔴 Design system contract for block hash storage (⏱️ L)
- [ ] 🔴 Define contract address (deterministic or hardcoded) (⏱️ S)
- [ ] 🔴 Design ring buffer for 8191 hashes (⏱️ M)
- [ ] 🔴 Create system contract bytecode (⏱️ L)

#### Implementation
- [ ] 🔴 Implement contract deployment at fork activation (⏱️ L)
- [ ] 🔴 Update block finalization to write to contract (⏱️ L)
- [ ] 🔴 Modify BLOCKHASH opcode to read from contract (⏱️ M)
- [ ] 🔴 Handle transition from old to new system (⏱️ L)
- [ ] 🔴 Implement ring buffer indexing logic (⏱️ M)

#### Testing
- [ ] 🟡 Unit tests for system contract (⏱️ L)
- [ ] 🟡 Unit tests for ring buffer (⏱️ M)
- [ ] 🟡 Integration tests for BLOCKHASH opcode (⏱️ M)
- [ ] 🟡 Test transition at fork boundary (⏱️ M)
- [ ] 🟡 Test 8191+ block scenarios (⏱️ M)
- [ ] 🟡 Validate against test vectors (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockPreparator.scala`
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` (BLOCKHASH)
- System contract deployment logic
- Genesis/fork initialization

---

## 🔧 Phase 3: Advanced Features

### EIP-7702: Set EOA Account Code
**Effort:** 2-3 weeks | **Priority:** 🟡 High

#### Transaction Type
- [ ] 🔴 Define Type-4 transaction structure (⏱️ M)
- [ ] 🔴 Add authorization tuple fields (⏱️ M)
- [ ] 🔴 Implement RLP encoding for Type-4 (⏱️ M)
- [ ] 🔴 Implement RLP decoding for Type-4 (⏱️ M)
- [ ] 🔴 Add transaction type discriminator (⏱️ S)

#### Account Code Delegation
- [ ] 🔴 Update Account to support delegated code (⏱️ M)
- [ ] 🔴 Implement authorization signature validation (⏱️ L)
- [ ] 🔴 Implement code delegation in transaction processing (⏱️ L)
- [ ] 🔴 Update account state storage (⏱️ M)

#### Validation & Execution
- [ ] 🔴 Add transaction validation for Type-4 (⏱️ M)
- [ ] 🔴 Validate authorization signatures (⏱️ M)
- [ ] 🔴 Implement execution with delegated code (⏱️ L)
- [ ] 🔴 Handle edge cases (invalid auth, etc.) (⏱️ M)

#### Testing
- [ ] 🟡 Unit tests for Type-4 transactions (⏱️ L)
- [ ] 🟡 Unit tests for authorization (⏱️ M)
- [ ] 🟡 Integration tests for code delegation (⏱️ L)
- [ ] 🟡 Test security scenarios (⏱️ L)
- [ ] 🟡 Validate against test vectors (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/domain/SignedTransaction.scala`
- `src/main/scala/com/chipprbots/ethereum/domain/Account.scala`
- `src/main/scala/com/chipprbots/ethereum/consensus/validators/`
- `src/main/scala/com/chipprbots/ethereum/ledger/BlockExecution.scala`

### EIP-7642: eth/69 - History Expiry and Simpler Receipts
**Effort:** 3-4 weeks | **Priority:** 🟡 High

#### Network Protocol
- [ ] 🔴 Create ETH69 protocol messages (⏱️ L)
- [ ] 🔴 Implement eth/69 handshake (⏱️ M)
- [ ] 🔴 Maintain backward compatibility with eth/68 (⏱️ M)
- [ ] 🔴 Update capability negotiation (⏱️ M)

#### Receipt Format Changes
- [ ] 🔴 Update Receipt to remove bloom filter (⏱️ M)
- [ ] 🔴 Update receipt RLP encoding (⏱️ M)
- [ ] 🔴 Update receipt RLP decoding (⏱️ M)
- [ ] 🔴 Update receipt storage format (⏱️ M)

#### History Expiry
- [ ] 🔴 Implement history serving window (⏱️ L)
- [ ] 🔴 Add configuration for history window (⏱️ S)
- [ ] 🔴 Update sync logic for history limits (⏱️ L)

#### Testing
- [ ] 🟡 Unit tests for eth/69 messages (⏱️ L)
- [ ] 🟡 Unit tests for new receipt format (⏱️ M)
- [ ] 🟡 Integration tests for protocol upgrade (⏱️ L)
- [ ] 🟡 Test backward compatibility (⏱️ L)
- [ ] 🟡 Test history window behavior (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/` (new ETH69.scala)
- `src/main/scala/com/chipprbots/ethereum/domain/Receipt.scala`
- `src/main/scala/com/chipprbots/ethereum/db/storage/ReceiptStorage.scala`
- Network handshake logic

### EIP-2537: Precompile for BLS12-381 Curve Operations
**Effort:** 4-6 weeks | **Priority:** 🟡 High | **Requires:** Crypto Expert

#### Library Integration
- [ ] 🔴 Select and integrate BLS12-381 library (⏱️ XL)
- [ ] 🔴 Verify library security and correctness (⏱️ L)
- [ ] 🔴 Add library dependency to build (⏱️ S)

#### Precompile Implementation (9 precompiles)
- [ ] 🔴 Implement BLS12_G1ADD (0x0a) (⏱️ M)
- [ ] 🔴 Implement BLS12_G1MUL (0x0b) (⏱️ M)
- [ ] 🔴 Implement BLS12_G1MULTIEXP (0x0c) (⏱️ L)
- [ ] 🔴 Implement BLS12_G2ADD (0x0d) (⏱️ M)
- [ ] 🔴 Implement BLS12_G2MUL (0x0e) (⏱️ M)
- [ ] 🔴 Implement BLS12_G2MULTIEXP (0x0f) (⏱️ L)
- [ ] 🔴 Implement BLS12_PAIRING (0x10) (⏱️ XL)
- [ ] 🔴 Implement BLS12_MAP_FP_TO_G1 (0x11) (⏱️ M)
- [ ] 🔴 Implement BLS12_MAP_FP2_TO_G2 (0x12) (⏱️ M)

#### Gas Costs & Validation
- [ ] 🔴 Implement gas cost calculations for each operation (⏱️ L)
- [ ] 🔴 Add input validation for all precompiles (⏱️ L)
- [ ] 🔴 Add error handling (⏱️ M)

#### Testing
- [ ] 🔴 Comprehensive unit tests for each precompile (⏱️ XL)
- [ ] 🔴 Test with official EIP test vectors (⏱️ L)
- [ ] 🔴 Test gas cost accuracy (⏱️ M)
- [ ] 🔴 Test error handling (⏱️ M)
- [ ] 🔴 Security testing (⏱️ XL)
- [ ] 🟡 Performance benchmarking (⏱️ L)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala`
- New crypto library integration
- `build.sbt` (dependencies)

### EIP-7951: Precompile for secp256r1 Curve Support
**Effort:** 2-3 weeks | **Priority:** 🟢 Medium

#### Library Integration
- [ ] 🔴 Select and integrate secp256r1 library (⏱️ L)
- [ ] 🔴 Verify library security (⏱️ M)
- [ ] 🔴 Add library dependency (⏱️ S)

#### Precompile Implementation
- [ ] 🔴 Implement secp256r1 signature verification (⏱️ L)
- [ ] 🔴 Add malleability checks (⏱️ M)
- [ ] 🔴 Implement gas cost calculation (⏱️ M)
- [ ] 🔴 Add input validation (⏱️ M)
- [ ] 🔴 Add error handling (⏱️ M)

#### Testing
- [ ] 🟡 Unit tests for precompile (⏱️ M)
- [ ] 🟡 Test with EIP test vectors (⏱️ M)
- [ ] 🟡 Test security scenarios (⏱️ M)
- [ ] 🟡 Test malleability protection (⏱️ M)

**Files:**
- `src/main/scala/com/chipprbots/ethereum/vm/PrecompiledContracts.scala`
- Crypto library integration

---

## 🧪 Testing Phase

### Unit Testing
- [ ] 🔴 Unit tests for all 13 EIPs (⏱️ XL)
- [ ] 🔴 Achieve >90% code coverage for new code (⏱️ Ongoing)
- [ ] 🟡 Edge case testing for each EIP (⏱️ L)
- [ ] 🟡 Error handling tests (⏱️ M)

### Integration Testing
- [ ] 🔴 Full block processing with new EIPs (⏱️ L)
- [ ] 🔴 Cross-fork boundary tests (⏱️ L)
- [ ] 🟡 Transaction lifecycle tests (⏱️ M)
- [ ] 🟡 Network integration tests (⏱️ M)

### Ethereum Test Suite
- [ ] 🔴 Run official test vectors for each EIP (⏱️ XL)
- [ ] 🔴 Validate all tests pass (⏱️ Ongoing)
- [ ] 🟡 Compare results with reference implementations (⏱️ L)

### Testnet Validation
- [ ] 🔴 Deploy to Gorgoroth private testnet (⏱️ M)
- [ ] 🔴 Run extended validation on Gorgoroth (⏱️ L)
- [ ] 🔴 Coordinate Mordor testnet deployment (⏱️ M)
- [ ] 🔴 Deploy to Mordor testnet (⏱️ M)
- [ ] 🔴 Run multi-client consensus tests on Mordor (⏱️ XL)
- [ ] 🔴 Test all 13 EIPs on testnet (⏱️ XL)
- [ ] 🟡 Test network protocol upgrade (eth/69) (⏱️ L)
- [ ] 🟡 Performance testing on testnet (⏱️ L)

### Performance Testing
- [ ] 🟡 Block processing benchmarks (⏱️ M)
- [ ] 🟡 Precompile performance tests (⏱️ M)
- [ ] 🟡 Network protocol performance (⏱️ M)
- [ ] 🟡 Memory/storage overhead analysis (⏱️ M)

---

## 📚 Documentation Phase

### User Documentation
- [ ] 🟡 Write ECIP-1121 upgrade guide for operators (⏱️ L)
- [ ] 🟡 Document new eth_config RPC method (⏱️ S)
- [ ] 🟡 Document EIP-7702 transaction type (⏱️ M)
- [ ] 🟡 Update FAQ with ECIP-1121 information (⏱️ M)
- [ ] 🟢 Create troubleshooting guide (⏱️ M)

### Developer Documentation
- [ ] 🟡 Document each EIP implementation (⏱️ L)
- [ ] 🟡 Document system contract (EIP-2935) (⏱️ M)
- [ ] 🟡 Document transient storage (EIP-1153) (⏱️ M)
- [ ] 🟡 Document eth/69 protocol (⏱️ M)
- [ ] 🟢 Create architecture diagrams (⏱️ M)

### API Documentation
- [ ] 🟡 Update JSON-RPC API reference (⏱️ M)
- [ ] 🟡 Document eth_config method (⏱️ S)
- [ ] 🟡 Document Type-4 transaction format (⏱️ M)
- [ ] 🟢 Add usage examples (⏱️ M)

### Specification Documents
- [x] 🟡 Create comprehensive analysis document (⏱️ L)
- [x] 🟡 Create implementation checklist (⏱️ M)
- [ ] 🟡 Create ADR for implementation strategy (⏱️ M)
- [ ] 🟢 Document design decisions (⏱️ M)

---

## 🔒 Security & Review Phase

### Code Review
- [ ] 🔴 Internal review of all EIP implementations (⏱️ XL)
- [ ] 🔴 Focus review on cryptography (EIP-2537, 7951) (⏱️ L)
- [ ] 🔴 Focus review on consensus changes (EIP-6780, 2935) (⏱️ L)
- [ ] 🟡 Review network protocol (EIP-7642) (⏱️ M)
- [ ] 🟡 Review transaction handling (EIP-7702) (⏱️ M)

### Security Analysis
- [ ] 🔴 Security audit of cryptographic implementations (⏱️ XL - External)
- [ ] 🔴 Analyze attack vectors for EIP-7702 (⏱️ L)
- [ ] 🔴 Analyze EIP-6780 SELFDESTRUCT security (⏱️ M)
- [ ] 🔴 Analyze EIP-2935 system contract security (⏱️ M)
- [ ] 🟡 Test for DoS vulnerabilities (⏱️ L)

### Formal Verification (Optional)
- [ ] 🟢 Formally verify critical algorithms (⏱️ XL)
- [ ] 🟢 Formally verify cryptographic operations (⏱️ XL)

---

## 🚀 Release Preparation Phase

### Release Engineering
- [ ] 🟡 Create release branch (⏱️ XS)
- [ ] 🟡 Prepare release notes (⏱️ L)
- [ ] 🟡 Update CHANGELOG.md (⏱️ M)
- [ ] 🟡 Tag release candidate (⏱️ XS)
- [ ] 🟡 Build release artifacts (⏱️ M)
- [ ] 🟡 Test release artifacts (⏱️ M)
- [ ] 🟡 Sign release artifacts (⏱️ S)
- [ ] 🟡 Prepare upgrade instructions (⏱️ M)

### Communication
- [ ] 🟡 Announce testnet deployment (⏱️ S)
- [ ] 🟡 Notify node operators of upcoming upgrade (⏱️ M)
- [ ] 🟡 Notify exchanges and service providers (⏱️ M)
- [ ] 🟡 Coordinate with other client teams (⏱️ M)
- [ ] 🟡 Publish blog post about ECIP-1121 (⏱️ M)
- [ ] 🟡 Update documentation site (⏱️ M)

### Deployment
- [ ] 🔴 Release final version (⏱️ S)
- [ ] 🔴 Publish Docker images (⏱️ S)
- [ ] 🔴 Update distribution packages (⏱️ M)
- [ ] 🟡 Update GitHub releases page (⏱️ S)
- [ ] 🟡 Update Docker Hub (⏱️ S)

---

## 📊 Mainnet Activation Phase

### Pre-Activation
- [ ] 🔴 Monitor node upgrade progress (⏱️ Ongoing)
- [ ] 🔴 Provide operator support (⏱️ Ongoing)
- [ ] 🟡 Track readiness metrics (⏱️ Ongoing)
- [ ] 🟡 Set up monitoring dashboards (⏱️ M)

### Activation Day
- [ ] 🔴 Monitor mainnet activation (⏱️ Ongoing)
- [ ] 🔴 Monitor for consensus issues (⏱️ Ongoing)
- [ ] 🔴 Monitor new feature usage (⏱️ Ongoing)
- [ ] 🔴 Monitor network health (⏱️ Ongoing)
- [ ] 🟡 Provide real-time support (⏱️ Ongoing)

### Post-Activation
- [ ] 🔴 Verify all EIPs working correctly (⏱️ L)
- [ ] 🔴 Verify eth/69 protocol adoption (⏱️ M)
- [ ] 🔴 Verify system contract functioning (⏱️ M)
- [ ] 🟡 Monitor for any issues or bugs (⏱️ Ongoing)
- [ ] 🟡 Gather community feedback (⏱️ Ongoing)
- [ ] 🟡 Publish post-activation report (⏱️ M)

---

## 📈 Progress Summary

### Overall Progress: 2% Complete

| Category | Progress | Items Complete | Items Total |
|----------|----------|----------------|-------------|
| Pre-Implementation | 30% | 5 | 17 |
| Phase 1: Quick Wins | 0% | 0 | 30 |
| Phase 2: Core EVM | 0% | 0 | 45 |
| Phase 3: Advanced | 0% | 0 | 85 |
| Testing | 0% | 0 | 25 |
| Documentation | 5% | 2 | 20 |
| Security & Review | 0% | 0 | 10 |
| Release Preparation | 0% | 0 | 20 |
| Mainnet Activation | 0% | 0 | 15 |
| **TOTAL** | **2%** | **7** | **267** |

### By EIP Progress: 0 of 13 Complete

| EIP | Description | Status | Effort |
|-----|-------------|--------|--------|
| EIP-7825 | Transaction gas limit cap | ⏳ Not Started | 1-2 days |
| EIP-7935 | Default gas limit 60M | ⏳ Not Started | < 1 day |
| EIP-7883 | MODEXP gas cost increase | ⏳ Not Started | 2-3 days |
| EIP-7910 | eth_config RPC method | ⏳ Not Started | 2-3 days |
| EIP-7623 | Increase calldata cost | ⏳ Not Started | 1 week |
| EIP-7934 | RLP block size limit | ⏳ Not Started | 1 week |
| EIP-5656 | MCOPY opcode | ⏳ Not Started | 1 week |
| EIP-1153 | Transient storage | ⏳ Not Started | 2 weeks |
| EIP-6780 | SELFDESTRUCT changes | ⏳ Not Started | 2 weeks |
| EIP-2935 | Historical block hashes | ⏳ Not Started | 3-4 weeks |
| EIP-7702 | Set EOA account code | ⏳ Not Started | 2-3 weeks |
| EIP-7642 | eth/69 protocol | ⏳ Not Started | 3-4 weeks |
| EIP-2537 | BLS12-381 precompiles | ⏳ Not Started | 4-6 weeks |
| EIP-7951 | secp256r1 precompile | ⏳ Not Started | 2-3 weeks |

---

## 🎯 Current Sprint Focus

**Sprint Goal:** Complete research and await ECIP-1121 finalization

**This Week:**
1. ✅ Complete ECIP-1121 specification analysis
2. ✅ Create comprehensive documentation
3. ⏳ Await ECIP-1121 PR #554 merge
4. ⏳ Research cryptography libraries
5. ⏳ Coordinate with other client teams

**Next Week (when ECIP-1121 finalizes):**
1. Begin Phase 1 implementation (quick wins)
2. Set up testing infrastructure
3. Integrate cryptography libraries
4. Start EIP-7825, 7935, 7883, 7910

---

## 📝 Notes

### Critical Dependencies
- **ECIP-1121 PR #554 must merge** - Implementation cannot start until specification is final
- **Cryptography expertise required** - EIP-2537 (BLS12-381) needs crypto expert
- **Activation blocks TBD** - Cannot do final release until blocks are set
- **Cross-client coordination essential** - Must work with Core-Geth and Besu

### Implementation Approach
- **Phased rollout recommended** - Start with Phase 1 quick wins
- **Parallel work possible** - Some EIPs can be developed in parallel
- **Testing is critical** - Each EIP must pass Ethereum test vectors
- **Testnet validation mandatory** - Minimum 8 weeks on Mordor before mainnet

### Key Differences from ECIP-1111
- **ECIP-1121:** Execution-layer specifications (13 EIPs, no fee market)
- **ECIP-1111:** Fee market (EIP-1559, EIP-3198, Treasury)
- **These are SEPARATE** - Can be implemented independently
- **ECIP-1121 does NOT include EIP-1559/3198**

### Timeline Estimate
- **Sequential:** 25-38 weeks (6-9 months)
- **With Parallelization:** 18-28 weeks (4.5-7 months)
- **Recommended:** Phased approach with 3 releases

---

**Last Updated:** 2025-12-30  
**Next Review:** Upon ECIP-1121 PR #554 merge

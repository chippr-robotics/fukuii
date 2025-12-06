# Ethereum Mainnet EVM Compatibility Specification

## Overview

This document provides a comprehensive analysis of the Ethereum Improvement Proposals (EIPs), VM opcodes, and protocol features required for Fukuii to claim full Ethereum mainnet execution client compatibility. Fukuii was originally designed as an Ethereum Classic client, and this specification identifies the gaps and requirements for Ethereum mainnet compatibility.

## Table of Contents

1. [Current Implementation Status](#current-implementation-status)
2. [Implemented EIPs](#implemented-eips)
3. [Ethereum Mainnet Fork History](#ethereum-mainnet-fork-history)
4. [Missing EIPs for Full Compatibility](#missing-eips-for-full-compatibility)
5. [Opcode Implementation Status](#opcode-implementation-status)
6. [Precompiled Contracts](#precompiled-contracts)
7. [Consensus and Protocol Differences](#consensus-and-protocol-differences)
8. [Testing and Validation](#testing-and-validation)
9. [Implementation Roadmap](#implementation-roadmap)

---

## Current Implementation Status

### Summary

| Category | Implemented | Partial | Missing |
|----------|-------------|---------|---------|
| Pre-Merge EIPs | 25+ | 2 | 8+ |
| Post-Merge EIPs | 0 | 0 | 10+ |
| Pectra/Fusaka EIPs | 0 | 0 | 8 |
| Opcodes | 142/145 | 0 | 3 |
| Precompiled Contracts | 9/20 | 0 | 11 |

### Compatibility Level

- **ETC Spiral (≈ Shanghai equivalent)**: ✅ Full compatibility
- **Ethereum Berlin**: ✅ Full compatibility
- **Ethereum London**: ⚠️ Partial (EIP-1559 not implemented)
- **Ethereum Paris (The Merge)**: ❌ Not implemented
- **Ethereum Shanghai**: ⚠️ Partial (beacon chain features missing)
- **Ethereum Cancun (Dencun)**: ❌ Not implemented
- **Ethereum Prague (Pectra/Fusaka)**: ❌ Not implemented

---

## Implemented EIPs

### Frontier Era (Block 0)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| N/A | Initial EVM opcodes | ✅ Implemented | 130+ base opcodes |

### Homestead (Block 1,150,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-2 | Homestead Hard-fork Changes | ✅ Implemented | Contract creation, tx validation |
| EIP-7 | DELEGATECALL | ✅ Implemented | Opcode 0xF4 |
| EIP-8 | devp2p Forward Compatibility | ✅ Implemented | Network protocol |

### Tangerine Whistle (Block 2,463,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-150 | Gas cost changes for IO-heavy operations | ✅ Implemented | EXTCODE*, CALL*, SLOAD updates |

### Spurious Dragon (Block 2,675,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-155 | Simple replay attack protection | ✅ Implemented | Chain ID in tx signature |
| EIP-160 | EXP cost increase | ✅ Implemented | G_expbyte = 50 |
| EIP-161 | State trie clearing | ✅ Implemented | noEmptyAccounts flag |
| EIP-170 | Contract code size limit | ✅ Implemented | MAX_CODE_SIZE = 24576 |

### Byzantium (Block 4,370,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-100 | Difficulty adjustment | ✅ Implemented | Uncle inclusion adjustment |
| EIP-140 | REVERT instruction | ✅ Implemented | Opcode 0xFD |
| EIP-196 | BN128 addition and multiplication | ✅ Implemented | Precompiles at 0x06, 0x07 |
| EIP-197 | BN128 pairing check | ✅ Implemented | Precompile at 0x08 |
| EIP-198 | Big integer modular exponentiation | ✅ Implemented | Precompile at 0x05 |
| EIP-211 | RETURNDATASIZE and RETURNDATACOPY | ✅ Implemented | Opcodes 0x3D, 0x3E |
| EIP-214 | STATICCALL | ✅ Implemented | Opcode 0xFA |
| EIP-649 | Difficulty bomb delay | ✅ Implemented | Block reward reduction |
| EIP-658 | Transaction status in receipts | ✅ Implemented | Status field in receipts |

### Constantinople/Petersburg (Block 7,280,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-145 | Bitwise shifting | ✅ Implemented | SHL, SHR, SAR opcodes |
| EIP-1014 | Skinny CREATE2 | ✅ Implemented | Opcode 0xF5 |
| EIP-1052 | EXTCODEHASH | ✅ Implemented | Opcode 0x3F |
| EIP-1234 | Constantinople bomb delay | ✅ Implemented | Block reward = 2 ETH |
| EIP-1283 | Net gas metering for SSTORE | ✅ Implemented | Constantinople only |

### Istanbul (Block 9,069,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-152 | Blake2b F compression | ✅ Implemented | Precompile at 0x09 |
| EIP-1108 | BN128 gas cost reduction | ✅ Implemented | Reduced gas for BN128 ops |
| EIP-1344 | ChainID opcode | ✅ Implemented | Opcode 0x46 |
| EIP-1884 | Opcode repricing | ✅ Implemented | SLOAD, BALANCE, EXTCODEHASH |
| EIP-2028 | Calldata gas reduction | ✅ Implemented | G_txdatanonzero = 16 |
| EIP-2200 | SSTORE gas changes (net metering) | ✅ Implemented | Combined with EIP-1283 |

### Berlin (Block 12,244,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-2565 | ModExp gas cost | ✅ Implemented | Repriced modular exponentiation |
| EIP-2718 | Typed Transaction Envelope | ⚠️ Partial | Type 0 legacy only |
| EIP-2929 | Gas cost increases for state access | ✅ Implemented | Cold/warm access tracking |
| EIP-2930 | Optional access lists | ⚠️ Partial | Access lists parsed but Type 1 tx incomplete |

### London (Block 12,965,000)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-1559 | Fee market change | ❌ Missing | Base fee, priority fee |
| EIP-3198 | BASEFEE opcode | ❌ Missing | Opcode 0x48 |
| EIP-3529 | Reduce refunds | ✅ Implemented | SELFDESTRUCT refund = 0 |
| EIP-3541 | Reject new contracts starting with 0xEF | ✅ Implemented | EOF preparation |

### Paris/The Merge (Block 15,537,394)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-3675 | Upgrade consensus to Proof-of-Stake | ❌ Missing | Beacon chain integration |
| EIP-4399 | DIFFICULTY → PREVRANDAO | ❌ Missing | Opcode behavior change |

### Shanghai (Block 17,034,870)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-3651 | Warm COINBASE | ✅ Implemented | Coinbase in warm addresses |
| EIP-3855 | PUSH0 instruction | ✅ Implemented | Opcode 0x5F |
| EIP-3860 | Limit and meter initcode | ✅ Implemented | MAX_INITCODE_SIZE |
| EIP-4895 | Beacon chain push withdrawals | ❌ Missing | Validator withdrawals |
| EIP-6049 | Deprecate SELFDESTRUCT | ✅ Implemented | Informational only |

### Cancun (Block 19,426,587)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| EIP-1153 | Transient storage opcodes | ❌ Missing | TLOAD, TSTORE |
| EIP-4788 | Beacon block root in EVM | ❌ Missing | Parent beacon root |
| EIP-4844 | Shard Blob Transactions | ❌ Missing | Proto-danksharding |
| EIP-5656 | MCOPY instruction | ❌ Missing | Memory copy opcode |
| EIP-6780 | SELFDESTRUCT changes | ❌ Missing | Same-transaction only |
| EIP-7516 | BLOBBASEFEE opcode | ❌ Missing | Blob gas price |

### Prague/Pectra (Execution: Prague, Consensus: Fusaka - Activated May 7, 2025)
| EIP | Title | Status | Notes |
|-----|-------|--------|-------|
| **Execution Layer EIPs** | | | |
| EIP-2537 | BLS12-381 precompiles | ❌ Missing | BLS curve operations (9 precompiles) |
| EIP-2935 | Serve historical block hashes from state | ❌ Missing | BLOCKHASH opcode improvement |
| EIP-6110 | Supply validator deposits on chain | ❌ Missing | Deposit processing in EL |
| EIP-7002 | Execution layer triggerable exits | ❌ Missing | Validator exits from EL |
| EIP-7251 | Increase MAX_EFFECTIVE_BALANCE | ❌ Missing | Max 2048 ETH effective balance |
| EIP-7549 | Move committee index outside Attestation | ❌ Missing | Consensus layer optimization |
| EIP-7685 | General purpose execution layer requests | ❌ Missing | Request framework |
| EIP-7702 | Set EOA account code for one transaction | ❌ Missing | Account abstraction precursor |
| **Consensus Layer EIPs (Fusaka)** | | | |
| EIP-7251 | Max effective balance (Consensus) | ❌ Missing | Validator consolidation support |
| EIP-7549 | Committee index optimization | ❌ Missing | Attestation efficiency |
| EIP-7594 | PeerDAS (Peer Data Availability Sampling) | ❌ Missing | Data availability layer |

---

## Ethereum Mainnet Fork History

```
Block 0          │ Frontier
Block 200,000    │ Frontier Thawing
Block 1,150,000  │ Homestead
Block 1,920,000  │ DAO Fork (ETC split)
Block 2,463,000  │ Tangerine Whistle
Block 2,675,000  │ Spurious Dragon
Block 4,370,000  │ Byzantium
Block 7,280,000  │ Constantinople/Petersburg
Block 9,069,000  │ Istanbul
Block 9,200,000  │ Muir Glacier
Block 12,244,000 │ Berlin
Block 12,965,000 │ London          ← EIP-1559 (base fee)
Block 13,773,000 │ Arrow Glacier
Block 15,050,000 │ Gray Glacier
Block 15,537,394 │ Paris (The Merge) ← PoS transition
Block 17,034,870 │ Shanghai        ← Withdrawals
Block 19,426,587 │ Cancun (Dencun) ← Proto-danksharding
Slot 10388992    │ Prague (Pectra/Fusaka) ← Account abstraction, MaxEB, EL requests
(May 7, 2025)    │ (Execution: Prague, Consensus: Fusaka)
```

---

## Missing EIPs for Full Compatibility

### Critical Path (Required for Basic Compatibility)

#### 1. EIP-1559: Fee Market Change for ETH 1.0 Chain
**Priority**: 🔴 Critical  
**Complexity**: High  
**Impact**: Transaction processing, block validation

**Requirements**:
- Add `base_fee_per_gas` to block header
- Add `max_fee_per_gas` and `max_priority_fee_per_gas` to transactions
- Implement Type 2 (EIP-1559) transactions
- Calculate effective gas price
- Burn base fee portion
- Implement base fee adjustment algorithm

**Gas Calculation**:
```
effective_gas_price = min(max_fee_per_gas, base_fee_per_gas + max_priority_fee_per_gas)
priority_fee = effective_gas_price - base_fee_per_gas
```

#### 2. EIP-3198: BASEFEE Opcode
**Priority**: 🔴 Critical (depends on EIP-1559)  
**Complexity**: Low  
**Impact**: EVM opcode

**Implementation**:
```scala
case object BASEFEE extends ConstOp(0x48)(s => UInt256(s.env.blockHeader.baseFee.getOrElse(0)))
```

#### 3. EIP-4399: Supplant DIFFICULTY with PREVRANDAO
**Priority**: 🔴 Critical (post-Merge)  
**Complexity**: Low  
**Impact**: DIFFICULTY opcode behavior

**Changes**:
- After The Merge, DIFFICULTY (0x44) returns the beacon chain RANDAO value
- Rename to PREVRANDAO semantically
- Update block header to include `prevRandao` field

#### 4. EIP-3675: Upgrade Consensus to Proof-of-Stake
**Priority**: 🔴 Critical (for mainnet sync)  
**Complexity**: Very High  
**Impact**: Consensus, block production

**Requirements**:
- Engine API implementation (JSON-RPC for consensus/execution layer communication)
- Block building without PoW
- Beacon chain integration
- Fork choice rule changes
- Terminal Total Difficulty (TTD) handling

### High Priority (Required for Modern Features)

#### 5. EIP-4895: Beacon Chain Push Withdrawals
**Priority**: 🟠 High  
**Complexity**: Medium  
**Impact**: Block processing

**Requirements**:
- Add `withdrawals` field to block body
- Process withdrawals as balance credits
- Withdrawal index tracking

#### 6. EIP-1153: Transient Storage Opcodes
**Priority**: 🟠 High  
**Complexity**: Medium  
**Impact**: EVM opcodes

**New Opcodes**:
| Opcode | Name | Description |
|--------|------|-------------|
| 0x5C | TLOAD | Load from transient storage |
| 0x5D | TSTORE | Store to transient storage |

**Implementation Notes**:
- Transient storage is cleared at end of transaction
- Same gas costs as SLOAD/SSTORE warm access (100 gas)
- Per-transaction, per-address key-value store

#### 7. EIP-5656: MCOPY Instruction
**Priority**: 🟠 High  
**Complexity**: Low  
**Impact**: EVM opcode

**New Opcode**:
| Opcode | Name | Description |
|--------|------|-------------|
| 0x5E | MCOPY | Memory copy |

**Gas Calculation**:
```
gas = G_verylow + G_copy * ceil(size / 32) + memory_expansion_cost
```

#### 8. EIP-6780: SELFDESTRUCT Only in Same Transaction
**Priority**: 🟠 High  
**Complexity**: Medium  
**Impact**: SELFDESTRUCT behavior

**Changes**:
- SELFDESTRUCT only deletes the account if called in the same transaction as contract creation
- Otherwise, only transfers ETH, does not delete code or storage
- Enables future state expiry proposals

### Medium Priority (Required for Complete Compliance)

#### 9. EIP-4844: Shard Blob Transactions
**Priority**: 🟡 Medium  
**Complexity**: Very High  
**Impact**: Transaction types, data availability

**Requirements**:
- Type 3 (blob) transactions
- KZG commitments and proofs
- Blob data handling
- Data availability sampling (future)

**New Components**:
- `blob_versioned_hashes` in transactions
- `excess_blob_gas` and `blob_gas_used` in headers
- Precompile at 0x0A (point evaluation)

#### 10. EIP-7516: BLOBBASEFEE Opcode
**Priority**: 🟡 Medium (depends on EIP-4844)  
**Complexity**: Low  
**Impact**: EVM opcode

**New Opcode**:
| Opcode | Name | Description |
|--------|------|-------------|
| 0x4A | BLOBBASEFEE | Get blob base fee |

#### 11. EIP-4788: Beacon Block Root in EVM
**Priority**: 🟡 Medium  
**Complexity**: Medium  
**Impact**: System contract

**Requirements**:
- System contract at address `0x000F3df6D732807Ef1319fB7B8bB8522d0Beac02`
- Stores beacon block roots
- Ring buffer of 8191 entries

### Lower Priority (Future Enhancements)

#### 12. EIP-2537: BLS12-381 Curve Operations
**Priority**: 🟢 Lower → 🟠 High (Included in Pectra)  
**Complexity**: High  
**Impact**: Precompiled contracts

**New Precompiles**:
| Address | Name | Description |
|---------|------|-------------|
| 0x0B | BLS12_G1ADD | G1 point addition |
| 0x0C | BLS12_G1MUL | G1 point multiplication |
| 0x0D | BLS12_G1MSM | G1 multi-scalar multiplication |
| 0x0E | BLS12_G2ADD | G2 point addition |
| 0x0F | BLS12_G2MUL | G2 point multiplication |
| 0x10 | BLS12_G2MSM | G2 multi-scalar multiplication |
| 0x11 | BLS12_PAIRING | Pairing check |
| 0x12 | BLS12_MAP_FP_TO_G1 | Hash to G1 |
| 0x13 | BLS12_MAP_FP2_TO_G2 | Hash to G2 |

**Notes**: 
- Required for efficient BLS signature verification in EVM
- Critical for Ethereum consensus layer integration
- Now part of Pectra/Fusaka (activated May 7, 2025)

---

### Prague/Pectra (Fusaka) EIPs - Activated May 7, 2025

#### 13. EIP-2935: Serve Historical Block Hashes from State
**Priority**: 🟡 Medium  
**Complexity**: Medium  
**Impact**: State storage, BLOCKHASH opcode

**Requirements**:
- System contract at address `0x0aae40965e6800cd9b1f4b05ff21581047e3f91e`
- Stores last 8192 block hashes in a ring buffer
- BLOCKHASH opcode reads from this contract for blocks beyond 256
- Improves historical block hash availability

**Implementation Details**:
```
HISTORY_STORAGE_ADDRESS = 0x0aae40965e6800cd9b1f4b05ff21581047e3f91e
HISTORY_SERVE_WINDOW = 8192
```

**Gas Impact**:
- BLOCKHASH becomes more expensive for historical queries
- But provides much longer lookback window

#### 14. EIP-6110: Supply Validator Deposits on Chain
**Priority**: 🔴 Critical (Execution-Consensus Integration)  
**Complexity**: High  
**Impact**: Deposit contract, block structure

**Requirements**:
- Process deposit events from deposit contract in execution layer
- Add `deposits` field to execution payload
- Remove reliance on consensus layer for deposit processing
- Maximum 8192 deposits per block

**Deposit Contract**: `0x00000000219ab540356cBB839Cbe05303d7705Fa`

**Implementation Notes**:
- Deposits are extracted from logs in execution layer
- Improves execution-consensus layer separation
- Part of Engine API v4

#### 15. EIP-7002: Execution Layer Triggerable Exits
**Priority**: 🟠 High  
**Complexity**: Medium  
**Impact**: Validator exits, block structure

**Requirements**:
- System contract at `0x00431D736Ab7fA9C4d1B0e70c1E2B8a0e79e3C4e`
- Allows validators to trigger exits from execution layer
- Add `exits` field to execution payload
- Maximum 16 exits per block

**Exit Request Structure**:
```scala
case class ExitRequest(
  validatorPubkey: ByteString,  // 48 bytes
  amount: BigInt                 // uint64
)
```

#### 16. EIP-7251: Increase MAX_EFFECTIVE_BALANCE
**Priority**: 🟡 Medium (Consensus Layer)  
**Complexity**: Medium  
**Impact**: Validator economics, consolidations

**Changes**:
- Increases MAX_EFFECTIVE_BALANCE from 32 ETH to 2048 ETH
- Allows validator consolidation
- Reduces beacon chain state size
- Execution layer needs to support consolidation requests

**Impact on Execution Layer**:
- Must support validator consolidation requests
- Part of general request framework (EIP-7685)

#### 17. EIP-7549: Move Committee Index Outside Attestation
**Priority**: 🟢 Lower (Consensus Layer)  
**Complexity**: Low  
**Impact**: Attestation structure

**Notes**:
- Primarily consensus layer change
- Improves attestation efficiency
- No direct execution layer impact
- Minimal execution client changes required

#### 18. EIP-7685: General Purpose Execution Layer Requests
**Priority**: 🔴 Critical  
**Complexity**: Medium  
**Impact**: Block structure, Engine API

**Requirements**:
- Framework for execution layer to consensus layer requests
- Add `requests` field to execution payload
- Support multiple request types (deposits, exits, consolidations)

**Request Types**:
| Type ID | Name | EIP |
|---------|------|-----|
| 0x00 | Deposit | EIP-6110 |
| 0x01 | Exit | EIP-7002 |
| 0x02 | Consolidation | EIP-7251 |

**Implementation**:
```scala
case class ExecutionPayload(
  // ... existing fields ...
  requests: Seq[Request]  // New field
)

sealed trait Request {
  def requestType: Byte
}
```

#### 19. EIP-7702: Set EOA Account Code for One Transaction
**Priority**: 🔴 Critical  
**Complexity**: High  
**Impact**: Transaction types, account abstraction

**Requirements**:
- Implement Type 4 (EIP-7702) transactions
- Add `authorization_list` to transactions
- Temporarily set code for EOA accounts during transaction
- Revert code after transaction completes

**Authorization Structure**:
```scala
case class Authorization(
  chainId: BigInt,
  address: Address,
  nonce: BigInt,
  yParity: Byte,
  r: BigInt,
  s: BigInt
)
```

**Transaction Format**:
- Type: 0x04
- Fields: chain_id, nonce, max_priority_fee_per_gas, max_fee_per_gas, gas_limit, destination, value, data, access_list, authorization_list, signature_y_parity, signature_r, signature_s

**Use Cases**:
- Account abstraction without protocol changes
- Batched transactions from EOAs
- Sponsored transactions
- Gas payment by contract

**Implementation Challenges**:
- Temporary code storage
- Nonce management
- Gas accounting
- Security considerations (reentrancy, etc.)

#### 20. EIP-7594: PeerDAS (Peer Data Availability Sampling)
**Priority**: 🟢 Lower (Consensus Layer)  
**Complexity**: Very High  
**Impact**: Data availability, networking

**Notes**:
- Primarily consensus layer feature
- Improves data availability sampling
- Reduces bandwidth requirements for validators
- Minimal direct execution client impact
- Enables more efficient blob handling

---

## Opcode Implementation Status

### Complete Implementation (142 opcodes)

#### Arithmetic Operations (0x00-0x0B)
| Opcode | Name | Gas | Status |
|--------|------|-----|--------|
| 0x00 | STOP | 0 | ✅ |
| 0x01 | ADD | 3 | ✅ |
| 0x02 | MUL | 5 | ✅ |
| 0x03 | SUB | 3 | ✅ |
| 0x04 | DIV | 5 | ✅ |
| 0x05 | SDIV | 5 | ✅ |
| 0x06 | MOD | 5 | ✅ |
| 0x07 | SMOD | 5 | ✅ |
| 0x08 | ADDMOD | 8 | ✅ |
| 0x09 | MULMOD | 8 | ✅ |
| 0x0A | EXP | 10* | ✅ |
| 0x0B | SIGNEXTEND | 5 | ✅ |

#### Comparison & Bitwise Logic (0x10-0x1D)
| Opcode | Name | Gas | Status |
|--------|------|-----|--------|
| 0x10 | LT | 3 | ✅ |
| 0x11 | GT | 3 | ✅ |
| 0x12 | SLT | 3 | ✅ |
| 0x13 | SGT | 3 | ✅ |
| 0x14 | EQ | 3 | ✅ |
| 0x15 | ISZERO | 3 | ✅ |
| 0x16 | AND | 3 | ✅ |
| 0x17 | OR | 3 | ✅ |
| 0x18 | XOR | 3 | ✅ |
| 0x19 | NOT | 3 | ✅ |
| 0x1A | BYTE | 3 | ✅ |
| 0x1B | SHL | 3 | ✅ |
| 0x1C | SHR | 3 | ✅ |
| 0x1D | SAR | 3 | ✅ |

#### SHA3 (0x20)
| Opcode | Name | Gas | Status |
|--------|------|-----|--------|
| 0x20 | SHA3 | 30* | ✅ |

#### Environmental Information (0x30-0x3F)
| Opcode | Name | Gas | Status |
|--------|------|-----|--------|
| 0x30 | ADDRESS | 2 | ✅ |
| 0x31 | BALANCE | 100-2600* | ✅ |
| 0x32 | ORIGIN | 2 | ✅ |
| 0x33 | CALLER | 2 | ✅ |
| 0x34 | CALLVALUE | 2 | ✅ |
| 0x35 | CALLDATALOAD | 3 | ✅ |
| 0x36 | CALLDATASIZE | 2 | ✅ |
| 0x37 | CALLDATACOPY | 3* | ✅ |
| 0x38 | CODESIZE | 2 | ✅ |
| 0x39 | CODECOPY | 3* | ✅ |
| 0x3A | GASPRICE | 2 | ✅ |
| 0x3B | EXTCODESIZE | 100-2600* | ✅ |
| 0x3C | EXTCODECOPY | 100-2600* | ✅ |
| 0x3D | RETURNDATASIZE | 2 | ✅ |
| 0x3E | RETURNDATACOPY | 3* | ✅ |
| 0x3F | EXTCODEHASH | 100-2600* | ✅ |

#### Block Information (0x40-0x48)
| Opcode | Name | Gas | Status | Notes |
|--------|------|-----|--------|-------|
| 0x40 | BLOCKHASH | 20 | ✅ | |
| 0x41 | COINBASE | 2 | ✅ | |
| 0x42 | TIMESTAMP | 2 | ✅ | |
| 0x43 | NUMBER | 2 | ✅ | |
| 0x44 | DIFFICULTY/PREVRANDAO | 2 | ⚠️ | Returns difficulty, not prevRandao |
| 0x45 | GASLIMIT | 2 | ✅ | |
| 0x46 | CHAINID | 2 | ✅ | |
| 0x47 | SELFBALANCE | 5 | ✅ | |
| 0x48 | BASEFEE | 2 | ❌ | EIP-1559 required |

#### Stack, Memory, Storage, Flow Operations (0x50-0x5F)
| Opcode | Name | Gas | Status |
|--------|------|-----|--------|
| 0x50 | POP | 2 | ✅ |
| 0x51 | MLOAD | 3* | ✅ |
| 0x52 | MSTORE | 3* | ✅ |
| 0x53 | MSTORE8 | 3* | ✅ |
| 0x54 | SLOAD | 100-2100* | ✅ |
| 0x55 | SSTORE | 100-20000* | ✅ |
| 0x56 | JUMP | 8 | ✅ |
| 0x57 | JUMPI | 10 | ✅ |
| 0x58 | PC | 2 | ✅ |
| 0x59 | MSIZE | 2 | ✅ |
| 0x5A | GAS | 2 | ✅ |
| 0x5B | JUMPDEST | 1 | ✅ |
| 0x5C | TLOAD | 100 | ❌ | EIP-1153 |
| 0x5D | TSTORE | 100 | ❌ | EIP-1153 |
| 0x5E | MCOPY | 3* | ❌ | EIP-5656 |
| 0x5F | PUSH0 | 2 | ✅ | EIP-3855 |

#### Push Operations (0x60-0x7F)
All PUSH1-PUSH32 opcodes: ✅ Implemented

#### Dup Operations (0x80-0x8F)
All DUP1-DUP16 opcodes: ✅ Implemented

#### Swap Operations (0x90-0x9F)
All SWAP1-SWAP16 opcodes: ✅ Implemented

#### Log Operations (0xA0-0xA4)
All LOG0-LOG4 opcodes: ✅ Implemented

#### System Operations (0xF0-0xFF)
| Opcode | Name | Gas | Status |
|--------|------|-----|--------|
| 0xF0 | CREATE | 32000* | ✅ |
| 0xF1 | CALL | 100-2600* | ✅ |
| 0xF2 | CALLCODE | 100-2600* | ✅ |
| 0xF3 | RETURN | 0* | ✅ |
| 0xF4 | DELEGATECALL | 100-2600* | ✅ |
| 0xF5 | CREATE2 | 32000* | ✅ |
| 0xFA | STATICCALL | 100-2600* | ✅ |
| 0xFD | REVERT | 0* | ✅ |
| 0xFE | INVALID | all gas | ✅ |
| 0xFF | SELFDESTRUCT | 5000* | ⚠️ | Deprecated, but EIP-6780 not implemented |

### Missing Opcodes Summary

| Opcode | Name | EIP | Priority |
|--------|------|-----|----------|
| 0x48 | BASEFEE | EIP-3198 | 🔴 Critical |
| 0x4A | BLOBBASEFEE | EIP-7516 | 🟡 Medium |
| 0x5C | TLOAD | EIP-1153 | 🟠 High |
| 0x5D | TSTORE | EIP-1153 | 🟠 High |
| 0x5E | MCOPY | EIP-5656 | 🟠 High |

---

## Precompiled Contracts

### Implemented Precompiles

| Address | Name | EIP | Status |
|---------|------|-----|--------|
| 0x01 | ECRECOVER | Frontier | ✅ |
| 0x02 | SHA256 | Frontier | ✅ |
| 0x03 | RIPEMD160 | Frontier | ✅ |
| 0x04 | IDENTITY | Frontier | ✅ |
| 0x05 | MODEXP | EIP-198/2565 | ✅ |
| 0x06 | BN128ADD | EIP-196 | ✅ |
| 0x07 | BN128MUL | EIP-196 | ✅ |
| 0x08 | BN128PAIRING | EIP-197 | ✅ |
| 0x09 | BLAKE2F | EIP-152 | ✅ |

### Missing Precompiles

| Address | Name | EIP | Priority |
|---------|------|-----|----------|
| 0x0A | KZG_POINT_EVALUATION | EIP-4844 | 🟡 Medium |
| 0x0B | BLS12_G1ADD | EIP-2537 | 🟠 High (Pectra) |
| 0x0C | BLS12_G1MUL | EIP-2537 | 🟠 High (Pectra) |
| 0x0D | BLS12_G1MSM | EIP-2537 | 🟠 High (Pectra) |
| 0x0E | BLS12_G2ADD | EIP-2537 | 🟠 High (Pectra) |
| 0x0F | BLS12_G2MUL | EIP-2537 | 🟠 High (Pectra) |
| 0x10 | BLS12_G2MSM | EIP-2537 | 🟠 High (Pectra) |
| 0x11 | BLS12_PAIRING | EIP-2537 | 🟠 High (Pectra) |
| 0x12 | BLS12_MAP_FP_TO_G1 | EIP-2537 | 🟠 High (Pectra) |
| 0x13 | BLS12_MAP_FP2_TO_G2 | EIP-2537 | 🟠 High (Pectra) |

---

## Transaction Types

Ethereum has evolved from a single transaction format to multiple typed transactions using the envelope format (EIP-2718). Fukuii currently only supports Type 0 (legacy) transactions.

### Type 0: Legacy Transactions (Pre-EIP-2718)
**Status**: ✅ Implemented  
**EIP**: N/A (Original format)

**Structure**:
```
rlp([nonce, gasPrice, gasLimit, to, value, data, v, r, s])
```

**Fields**:
- `nonce`: Transaction counter for sender
- `gasPrice`: Gas price in wei
- `gasLimit`: Maximum gas allowed
- `to`: Recipient address (or empty for contract creation)
- `value`: ETH amount to transfer
- `data`: Contract data or initialization code
- `v, r, s`: ECDSA signature components

**Limitations**:
- Fixed gas price (no priority fee)
- No access lists
- No chain ID protection in pre-EIP-155 transactions

### Type 1: Access List Transactions (EIP-2930)
**Status**: ⚠️ Partial (Berlin)  
**EIP**: [EIP-2930](https://eips.ethereum.org/EIPS/eip-2930)

**Structure**:
```
0x01 || rlp([chainId, nonce, gasPrice, gasLimit, to, value, data, accessList, signatureYParity, signatureR, signatureS])
```

**New Fields**:
- `accessList`: List of addresses and storage keys that will be accessed
- Transaction type prefix: `0x01`

**Benefits**:
- Reduced gas costs for pre-declared storage access
- Mitigates effects of EIP-2929 (cold/warm access costs)

### Type 2: EIP-1559 Transactions
**Status**: ❌ Missing (London)  
**EIP**: [EIP-1559](https://eips.ethereum.org/EIPS/eip-1559)

**Structure**:
```
0x02 || rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value, data, accessList, signatureYParity, signatureR, signatureS])
```

**New Fields**:
- `maxPriorityFeePerGas`: Maximum priority fee (tip) to miner
- `maxFeePerGas`: Maximum total fee willing to pay
- Replaces `gasPrice` with fee market mechanism

**Gas Calculation**:
```
effectiveGasPrice = min(maxFeePerGas, baseFeePerGas + maxPriorityFeePerGas)
priorityFee = effectiveGasPrice - baseFeePerGas
```

**Benefits**:
- Predictable base fees
- Better UX (no more gas price guessing)
- ETH burn mechanism (base fee is burned)

### Type 3: Blob Transactions (EIP-4844)
**Status**: ❌ Missing (Cancun/Dencun)  
**EIP**: [EIP-4844](https://eips.ethereum.org/EIPS/eip-4844)

**Structure**:
```
0x03 || rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value, data, accessList, maxFeePerBlobGas, blobVersionedHashes, signatureYParity, signatureR, signatureS])
```

**New Fields**:
- `maxFeePerBlobGas`: Maximum fee per blob gas unit
- `blobVersionedHashes`: Commitments to blob data (not included in block)

**Blob Data**:
- Blobs are ~125KB each (4096 field elements × 32 bytes)
- Not stored in execution layer state
- Designed for rollup data availability
- Separate gas market from regular transactions

**Benefits**:
- Dramatically cheaper data availability for rollups
- Separate blob gas pricing (independent of regular gas)
- Temporary storage (blobs pruned after ~18 days)

### Type 4: Account Abstraction Transactions (EIP-7702)
**Status**: ❌ Missing (Pectra/Fusaka)  
**EIP**: [EIP-7702](https://eips.ethereum.org/EIPS/eip-7702)

**Structure**:
```
0x04 || rlp([chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value, data, accessList, authorizationList, signatureYParity, signatureR, signatureS])
```

**New Field**:
- `authorizationList`: List of authorizations to temporarily set code for EOAs

**Authorization Structure**:
```scala
case class Authorization(
  chainId: BigInt,
  address: Address,      // Contract address to delegate to
  nonce: BigInt,         // Account nonce for replay protection
  yParity: Byte,
  r: BigInt,
  s: BigInt
)
```

**Mechanism**:
1. EOA signs authorization to delegate to a contract
2. During transaction execution, EOA temporarily gets code from contract
3. Code is removed after transaction completes
4. Enables smart contract logic for EOAs

**Benefits**:
- Account abstraction for EOAs without protocol changes
- Batched transactions from EOAs
- Gas sponsorship (someone else pays gas)
- Custom signature schemes
- Social recovery
- Transaction batching

**Security Considerations**:
- Authorization nonce prevents replay attacks
- Chain ID prevents cross-chain replay
- Temporary delegation limits attack surface
- Must validate all authorizations before execution

### Transaction Type Comparison

| Feature | Type 0 | Type 1 | Type 2 | Type 3 | Type 4 |
|---------|--------|--------|--------|--------|--------|
| Gas Pricing | Fixed | Fixed | Dynamic (EIP-1559) | Dynamic + Blob | Dynamic + Blob |
| Access Lists | ❌ | ✅ | ✅ | ✅ | ✅ |
| Base Fee | ❌ | ❌ | ✅ | ✅ | ✅ |
| Blob Data | ❌ | ❌ | ❌ | ✅ | ❌ |
| EOA Code Delegation | ❌ | ❌ | ❌ | ❌ | ✅ |
| Chain ID | Optional | ✅ | ✅ | ✅ | ✅ |
| Use Case | Legacy | Gas optimization | Modern txs | Rollup data | Account abstraction |

### Implementation Status in Fukuii

| Type | Status | Priority | Notes |
|------|--------|----------|-------|
| Type 0 | ✅ Implemented | N/A | Fully supported |
| Type 1 | ⚠️ Partial | 🟠 High | Access list parsing incomplete |
| Type 2 | ❌ Missing | 🔴 Critical | Required for London compatibility |
| Type 3 | ❌ Missing | 🟡 Medium | Required for Cancun compatibility |
| Type 4 | ❌ Missing | 🔴 Critical | Required for Pectra compatibility |

---

## Consensus and Protocol Differences

### Ethereum Classic vs Ethereum Mainnet

| Feature | Ethereum Classic | Ethereum Mainnet |
|---------|------------------|------------------|
| Consensus | Proof of Work (Ethash) | Proof of Stake |
| Block Time | ~13 seconds | 12 seconds (slots) |
| Block Reward | 2.56 ETC (w/ reduction) | N/A (tips only) |
| Difficulty Bomb | Removed | N/A (post-Merge) |
| DAO Fork | Not applied | Applied |
| EIP-1559 | Not implemented | Implemented |
| Beacon Chain | Not applicable | Required |
| Withdrawals | Not applicable | EIP-4895 |
| Blob Transactions | Not applicable | EIP-4844 |

### Key Protocol Differences

1. **Transaction Types**:
   - ETC: Type 0 (legacy) only
   - ETH: Types 0, 1, 2, 3, 4 (legacy, access list, EIP-1559, blob, EIP-7702)

2. **Block Headers**:
   - ETC: Classic header structure with PoW fields
   - ETH: Extended header with `baseFeePerGas`, `withdrawalsRoot`, `blobGasUsed`, `excessBlobGas`, `parentBeaconBlockRoot`

3. **Execution Payload (Post-Pectra)**:
   - Additional field: `requests` (EIP-7685)
   - Includes deposits, exits, and consolidation requests
   
4. **Chain ID**:
   - ETC: 61 (mainnet), 63 (Mordor)
   - ETH: 1 (mainnet)

5. **Network Protocol**:
   - ETC: eth/63-68, snap/1
   - ETH: eth/66-68, snap/1 (with extended message types)

6. **Validator Operations (Pectra)**:
   - ETC: N/A (PoW)
   - ETH: On-chain deposits (EIP-6110), execution-triggered exits (EIP-7002), validator consolidation (EIP-7251)

---

## Testing and Validation

### Ethereum Test Suite Compatibility

Fukuii includes the `ethereum/tests` submodule for compliance testing:

```bash
# Run EVM tests
sbt "testOnly *VMSpec"
sbt "Evm / test"

# Run blockchain tests
sbt "IntegrationTest / test"
```

### Test Categories

| Category | Status | Notes |
|----------|--------|-------|
| General State Tests | ✅ Passing | Core EVM tests |
| Blockchain Tests | ✅ Passing | Pre-Merge blocks |
| VM Tests | ✅ Passing | Opcode tests |
| RLP Tests | ✅ Passing | Encoding tests |
| Transaction Tests | ⚠️ Partial | Type 0 only |
| Beacon Chain Tests | ❌ Missing | Post-Merge |

### Recommended Validation Steps

1. **EVM Compliance**:
   ```bash
   # Run ethereum/tests GeneralStateTests
   sbt "testOnly *GeneralStateTest*"
   ```

2. **Opcode Correctness**:
   ```bash
   # Run VM-specific tests
   sbt "testOnly *OpCode*"
   ```

3. **Precompile Verification**:
   ```bash
   # Run precompiled contract tests
   sbt "testOnly *PrecompiledContracts*"
   ```

4. **Fork Transition Testing**:
   ```bash
   # Test fork activation
   sbt "testOnly *Fork*"
   ```

---

## Implementation Roadmap

### Phase 1: EIP-1559 Support (Estimated: 4-6 weeks)

1. **Week 1-2**: Transaction type infrastructure
   - Implement typed transaction envelope (EIP-2718)
   - Add Type 1 (EIP-2930) access list transactions
   - Add Type 2 (EIP-1559) transactions

2. **Week 3-4**: Block header changes
   - Add `baseFeePerGas` to block header
   - Implement base fee calculation algorithm
   - Update block validation

3. **Week 5-6**: EVM changes
   - Implement BASEFEE opcode (EIP-3198)
   - Update gas price calculations
   - Integration testing

### Phase 2: Missing Cancun EIPs (Estimated: 3-4 weeks)

1. **Week 1**: Transient storage (EIP-1153)
   - Implement TLOAD/TSTORE opcodes
   - Add transient storage tracking per transaction

2. **Week 2**: MCOPY instruction (EIP-5656)
   - Implement memory copy opcode
   - Gas cost calculations

3. **Week 3**: SELFDESTRUCT changes (EIP-6780)
   - Modify SELFDESTRUCT behavior
   - Track contract creation in same transaction

4. **Week 4**: Testing and validation

### Phase 3: Post-Merge Infrastructure (Estimated: 8-12 weeks)

1. **Weeks 1-4**: Engine API
   - Implement JSON-RPC Engine API
   - Payload building and validation
   - Fork choice rule updates

2. **Weeks 5-8**: Beacon chain integration
   - PREVRANDAO support (EIP-4399)
   - Withdrawals processing (EIP-4895)
   - Parent beacon block root (EIP-4788)

3. **Weeks 9-12**: Testing and validation
   - Hive test suite integration
   - Devnet participation
   - Full sync testing

### Phase 4: Proto-Danksharding (Estimated: 6-8 weeks)

1. **Weeks 1-3**: Blob transactions
   - Type 3 transaction support
   - KZG commitment handling
   - Point evaluation precompile

2. **Weeks 4-6**: Block changes
   - Blob gas accounting
   - Excess blob gas tracking
   - BLOBBASEFEE opcode

3. **Weeks 7-8**: Testing and validation

### Phase 5: Pectra/Fusaka Support (Estimated: 10-14 weeks)

1. **Weeks 1-3**: BLS12-381 Precompiles (EIP-2537)
   - Implement 9 BLS12-381 precompiled contracts (0x0B - 0x13)
   - G1/G2 point operations
   - Pairing and mapping functions
   - Extensive testing with official test vectors

2. **Weeks 4-5**: Historical Block Hashes (EIP-2935)
   - Implement system contract for block hash storage
   - Update BLOCKHASH opcode logic
   - Ring buffer implementation (8192 blocks)

3. **Weeks 6-8**: Execution Layer Requests Framework (EIP-7685)
   - Add `requests` field to execution payload
   - Implement request encoding/decoding
   - Update Engine API to v4
   - Request validation and processing

4. **Weeks 9-10**: On-chain Deposits (EIP-6110)
   - Extract deposits from execution layer logs
   - Add `deposits` field to payload
   - Deposit validation (max 8192 per block)
   - Integration with deposit contract

5. **Weeks 11-12**: Execution-Triggered Exits (EIP-7002)
   - Implement exit request system contract
   - Add `exits` field to payload
   - Exit request validation (max 16 per block)
   - Public key and amount processing

6. **Weeks 13-14**: EIP-7702 Transactions (Type 4)
   - Authorization list parsing and validation
   - Temporary code delegation mechanism
   - Nonce verification for authorizations
   - Gas accounting for delegated code
   - Comprehensive security testing

**Note on EIP-7251 and EIP-7549**: These are primarily consensus layer changes with minimal execution layer impact beyond supporting consolidation requests in the general request framework.

**Note on EIP-7594 (PeerDAS)**: This is a consensus layer networking change with no direct execution client implementation required.

---

## Conclusion

Fukuii provides a solid foundation for EVM compatibility up to the Berlin/Istanbul level. To achieve full Ethereum mainnet compatibility including the recently activated Pectra/Fusaka fork (May 7, 2025), the following priorities should be addressed:

1. **Critical**: EIP-1559 and related London fork changes
2. **Critical**: Post-Merge infrastructure (Engine API, PoS)
3. **Critical**: Execution layer requests framework (EIP-7685)
4. **Critical**: Type 4 transactions and account abstraction (EIP-7702)
5. **High**: Missing Cancun opcodes (TLOAD, TSTORE, MCOPY)
6. **High**: BLS12-381 precompiles (EIP-2537) - now part of Pectra
7. **High**: On-chain deposits and execution-triggered exits (EIP-6110, EIP-7002)
8. **Medium**: Proto-danksharding (EIP-4844)
9. **Medium**: Historical block hashes (EIP-2935)

The architecture of Fukuii (based on the well-tested Mantis codebase) provides a clean separation between EVM execution and consensus, which should facilitate these additions. The Pectra/Fusaka fork represents a significant evolution of the Ethereum protocol, introducing important features for account abstraction, validator management, and execution-consensus layer integration.

### Key Takeaways for Pectra/Fusaka

**Execution Layer Changes**:
- **EIP-7702** brings delegated code execution to EOAs, a major step toward account abstraction
- **EIP-2537** adds 9 new precompiles for BLS12-381 operations
- **EIP-2935** improves historical block hash availability
- **EIP-7685** establishes a general framework for execution-to-consensus requests

**Execution-Consensus Integration**:
- **EIP-6110** moves deposit processing to execution layer
- **EIP-7002** enables execution layer to trigger validator exits
- **EIP-7251** supports validator consolidation (minimal execution layer impact)

**Development Impact**:
The Pectra fork significantly increases the complexity of Ethereum execution clients, particularly around:
1. Transaction type handling (Type 4)
2. System contract interactions (multiple new system contracts)
3. Engine API evolution (v4 with request support)
4. Cryptographic operations (BLS12-381)

**Estimated Total Implementation Time**: 30-44 weeks across 5 major phases, assuming sequential development. Parallel development could reduce this timeline significantly.

---

## References

### Official Ethereum Resources
- [Ethereum EIPs Repository](https://github.com/ethereum/EIPs)
- [Ethereum Execution Specs](https://github.com/ethereum/execution-specs)
- [EVM Opcodes Reference](https://www.evm.codes/)
- [Ethereum Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf)
- [Ethereum/tests Repository](https://github.com/ethereum/tests)

### Ethereum Classic Resources
- [ECIP Repository](https://ecips.ethereumclassic.org/)

### Pectra/Fusaka Fork Resources
- [Pectra Network Upgrade Meta](https://eips.ethereum.org/EIPS/eip-7600)
- [EIP-2537: BLS12-381 Precompiles](https://eips.ethereum.org/EIPS/eip-2537)
- [EIP-2935: Historical Block Hashes](https://eips.ethereum.org/EIPS/eip-2935)
- [EIP-6110: On-chain Deposits](https://eips.ethereum.org/EIPS/eip-6110)
- [EIP-7002: Execution Layer Exits](https://eips.ethereum.org/EIPS/eip-7002)
- [EIP-7251: Max Effective Balance](https://eips.ethereum.org/EIPS/eip-7251)
- [EIP-7549: Committee Index Optimization](https://eips.ethereum.org/EIPS/eip-7549)
- [EIP-7685: General Purpose EL Requests](https://eips.ethereum.org/EIPS/eip-7685)
- [EIP-7702: Set Code for EOAs](https://eips.ethereum.org/EIPS/eip-7702)

### Engine API and Consensus
- [Engine API Specification](https://github.com/ethereum/execution-apis/tree/main/src/engine)
- [Consensus Layer Specs](https://github.com/ethereum/consensus-specs)

### Testing and Validation
- [Hive Testing Framework](https://github.com/ethereum/hive)
- [Execution Spec Tests](https://github.com/ethereum/execution-spec-tests)

---

## Document History

- **Initial Version**: Pre-Pectra analysis (focusing on London through Cancun)
- **Current Version**: Updated for Pectra/Fusaka fork activation (May 7, 2025)
  - Added 8 new Pectra/Fusaka EIPs with detailed analysis
  - Updated fork history timeline with Pectra activation
  - Revised priority classifications (EIP-2537 upgraded to High)
  - Added 11 new BLS12-381 precompile addresses
  - Expanded implementation roadmap with Phase 5 (Pectra support)
  - Enhanced conclusion with Pectra-specific development impact analysis
  - Added comprehensive references for Pectra-related EIPs

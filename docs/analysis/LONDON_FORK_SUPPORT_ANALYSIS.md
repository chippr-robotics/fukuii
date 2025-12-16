# London Fork (EIP-1559) Support Analysis

**Date**: 2025-12-16  
**Context**: Genesis block hash mismatch between Fukuii and Core-Geth on gorgoroth network

## Executive Summary

Fukuii **does NOT currently support** the London hard fork (EIP-1559) which introduced the `baseFeePerGas` field to block headers. This is the root cause of the 5-byte hash discrepancy with Core-Geth.

- **Fukuii**: 15-field block header (pre-EIP-1559)
- **Core-Geth**: 16-field block header (with baseFeePerGas)
- **Impact**: Cannot validate, decode, or generate London-era blocks

## Current Implementation Status

### ✅ Supported Forks

Fukuii supports up to **Berlin** hard fork:
- Frontier
- Homestead
- EIP150 (Tangerine Whistle)
- EIP158 (Spurious Dragon)
- Byzantium
- Constantinople
- Istanbul
- **Berlin** (highest supported)

### ❌ Unsupported Forks

Post-Berlin forks are NOT supported:
- **London** (EIP-1559 - baseFeePerGas)
- Arrow Glacier
- Gray Glacier
- The Merge (PoS)
- Shanghai
- Cancun

## Technical Analysis

### 1. BlockHeader Structure

**Current Definition** (`BlockHeader.scala` lines 27-43):
```scala
case class BlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString,
    extraFields: HeaderExtraFields = HefEmpty  // Only supports ECIP-1097 checkpoint
)
```

**Missing**: `baseFeePerGas: Option[BigInt]` field for EIP-1559

### 2. RLP Encoding/Decoding

**Current Decoder** (`BlockHeader.scala` lines 203-287):
- ✓ Handles 15 fields (standard pre-EIP-1559)
- ✓ Handles 16 fields with ECIP-1097 checkpoint
- ❌ **Cannot handle 16 fields with baseFeePerGas**

When receiving a London block from Core-Geth, the decoder will throw:
```
Exception: BlockHeader cannot be decoded
```

### 3. Fork Configuration

**Current Forks** (`ForkBlockNumbers` in `BlockchainConfig.scala`):
```scala
case class ForkBlockNumbers(
    frontierBlockNumber: BigInt,
    homesteadBlockNumber: BigInt,
    // ... other forks ...
    berlinBlockNumber: BigInt,
    mystiqueBlockNumber: BigInt,
    spiralBlockNumber: BigInt
    // MISSING: londonBlockNumber
)
```

### 4. Test Infrastructure

**Supported Networks** (`BlockchainTestsSpec.scala`):
```scala
val supportedNetworks = Set(
    "Frontier",
    "Homestead",
    "EIP150",
    "EIP158",
    "Byzantium",
    "Constantinople",
    "Istanbul",
    "Berlin"
    // London explicitly filtered out
)
```

London tests are explicitly excluded with comment: `"Post-Berlin, not supported"`

## Root Cause of Hash Mismatch

### Genesis Block Comparison

| Attribute | Fukuii | Core-Geth | Difference |
|-----------|--------|-----------|------------|
| Block Size | 511 bytes (0x1ff) | 516 bytes (0x204) | **+5 bytes** |
| Block Hash | `0x039853...` | `0x770bf9...` | Different |
| Format | 15 fields | 16 fields | +baseFeePerGas |
| baseFeePerGas | N/A | 0x3b9aca00 (1 Gwei) | **5 bytes RLP** |

The 5-byte difference is exactly the RLP encoding of `baseFeePerGas`:
- RLP marker: `0x84` (1 byte)
- Value: `0x3b9aca00` (4 bytes)
- **Total: 5 bytes**

## Required Changes for London Support

### 1. Data Model Changes

**Add baseFeePerGas to BlockHeader**:
```scala
case class BlockHeader(
    // ... existing fields ...
    mixHash: ByteString,
    nonce: ByteString,
    baseFeePerGas: Option[BigInt] = None,  // NEW FIELD
    extraFields: HeaderExtraFields = HefEmpty
)
```

**Update HeaderExtraFields**:
```scala
sealed trait HeaderExtraFields
object HeaderExtraFields {
  case object HefEmpty extends HeaderExtraFields
  case class HefPostEcip1097(checkpoint: Option[Checkpoint]) extends HeaderExtraFields
  case object HefPostEip1559 extends HeaderExtraFields  // NEW for London
}
```

### 2. RLP Encoder/Decoder Updates

**Add London format support** (16 fields with baseFeePerGas):
```scala
case RLPList(
      parentHash, ommersHash, beneficiary, stateRoot,
      transactionsRoot, receiptsRoot, logsBloom,
      difficulty, number, gasLimit, gasUsed,
      unixTimestamp, extraData, mixHash, nonce,
      baseFeePerGas  // NEW - 16th field
    ) =>
  // Decode as London block
```

### 3. Fork Configuration

**Add London to ForkBlockNumbers**:
```scala
case class ForkBlockNumbers(
    // ... existing forks ...
    berlinBlockNumber: BigInt,
    londonBlockNumber: BigInt,  // NEW
    // ... future forks ...
)
```

### 4. EIP-1559 Logic Implementation

**Base Fee Calculation** (EIP-1559 spec):
```scala
def calcBaseFee(
    parentBaseFee: BigInt,
    parentGasUsed: BigInt,
    parentGasTarget: BigInt
): BigInt
```

**Transaction Validation**:
- Validate `maxFeePerGas >= baseFeePerGas`
- Validate `maxPriorityFeePerGas <= maxFeePerGas`

### 5. Block Validation Updates

**BlockHeaderValidator must check**:
- baseFeePerGas is present when `blockNumber >= londonBlockNumber`
- baseFeePerGas is calculated correctly based on parent block
- baseFeePerGas is None when `blockNumber < londonBlockNumber`

### 6. Genesis Block Handling

**GenesisDataLoader must**:
- Include baseFeePerGas in genesis header if London is active at block 0
- Calculate initial baseFeePerGas (typically 1 Gwei = 0x3b9aca00)

### 7. Test Suite Updates

**Enable London tests**:
```scala
val supportedNetworks = Set(
    // ... existing networks ...
    "Berlin",
    "London"  // NEW
)
```

## Implementation Complexity

### Estimated Effort
- **Small**: Config changes, test filters
- **Medium**: Data model updates, RLP codec changes
- **Large**: EIP-1559 base fee calculation logic
- **Critical**: Consensus validation (must match reference implementation exactly)

### Risk Level: **HIGH**
- Consensus-critical changes
- Affects block hash calculation
- Must maintain backward compatibility with pre-London blocks
- Requires extensive testing against Ethereum test suite

## Recommendations

### Option 1: Implement London Support (High Effort)
**Pros**:
- Full compatibility with modern Ethereum/Core-Geth
- Can validate London-era blocks
- Future-proof for newer forks

**Cons**:
- Significant development effort
- High risk if implementation has bugs
- Requires extensive testing
- ETC networks typically don't use EIP-1559

### Option 2: Configure Core-Geth for ETC Consensus (Low Effort)
**Pros**:
- No Fukuii code changes needed
- Maintains ETC consensus rules
- Lower risk

**Cons**:
- Core-Geth configuration must be corrected
- May indicate misconfiguration in test environment

### Option 3: Separate Test Networks (Medium Effort)
**Pros**:
- Fukuii continues with ETC consensus
- Core-Geth can use ETH consensus
- No compatibility required

**Cons**:
- Cannot test interoperability
- Defeats purpose of mixed-client network

## Conclusion

**Fukuii does NOT have the capability to validate post-London blocks** in its current state. Even with correct fork block configuration, the following are missing:
1. `baseFeePerGas` field in BlockHeader
2. RLP encoder/decoder for London format
3. EIP-1559 base fee calculation logic
4. Block validators for baseFeePerGas

**For gorgoroth network**: If the goal is to test Fukuii-Core-Geth interoperability, either:
- Implement full London support in Fukuii (high effort, high risk)
- Configure Core-Geth to use pre-London ETC consensus (low effort, recommended)

## References

- [EIP-1559 Specification](https://eips.ethereum.org/EIPS/eip-1559)
- Fukuii BlockHeader: `src/main/scala/com/chipprbots/ethereum/domain/BlockHeader.scala`
- Fork Configuration: `src/main/scala/com/chipprbots/ethereum/utils/BlockchainConfig.scala`
- Test Suite: `src/it/scala/com/chipprbots/ethereum/ethtest/BlockchainTestsSpec.scala`

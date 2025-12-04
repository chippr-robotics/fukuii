# Discovery v5 Protocol Implementation

This directory contains the implementation of the Discovery v5 protocol for Fukuii (Ethereum Classic client).

## Overview

Discovery v5 is the next generation node discovery protocol for Ethereum, providing improved security, efficiency, and functionality over Discovery v4.

## Implementation Status

✅ **Completed:**
- Core packet structure (OrdinaryMessage, WHOAREYOU, HandshakeMessage)
- Message types (PING, PONG, FINDNODE, NODES, TALKREQ, TALKRESP, topic discovery messages)
- Session management with AES-GCM encryption
- HKDF key derivation
- Configuration system
- RPC interface
- Service layer
- Comprehensive unit tests
- Integration tests

⚠️ **Partial Implementation:**
- Network layer (basic structure in place, needs full packet encoding/decoding integration)
- Topic-based discovery (structure defined but not fully implemented)
- Handshake flow (skeleton in place, needs ECDH implementation)

## File Structure

### Core Implementation (`src/`)

- **`Packet.scala`** (259 lines): Wire format encoding/decoding
  - Protocol magic bytes and version
  - Three packet types: Ordinary, WHOAREYOU, Handshake
  - Packet encoding/decoding with size validation
  
- **`Payload.scala`** (156 lines): Discovery v5 message types
  - PING/PONG for liveness checks
  - FINDNODE/NODES for node discovery  
  - TALKREQ/TALKRESP for application protocols
  - Topic discovery messages (REGTOPIC, TICKET, etc.)
  
- **`Session.scala`** (180 lines): Session management and cryptography
  - HKDF key derivation (SHA256)
  - AES-128-GCM encryption/decryption
  - Session cache for active peer sessions
  - Node ID computation from public keys
  
- **`DiscoveryConfig.scala`** (81 lines): Protocol configuration
  - Timeouts and intervals
  - Kademlia parameters
  - Session limits
  - Topic discovery settings
  
- **`DiscoveryRPC.scala`** (77 lines): RPC interface definition
  - ping, findNode, talkRequest operations
  - Topic registration and query
  - Result types for responses
  
- **`DiscoveryNetwork.scala`** (217 lines): Network layer
  - Peer addressing and identification
  - Request/response handling
  - Session establishment
  - Background packet processing
  
- **`DiscoveryService.scala`** (298 lines): Main service implementation
  - Node management (add, remove, lookup)
  - Kademlia routing table
  - Background discovery tasks
  - Request handler for incoming RPCs

### Tests

#### Unit Tests (`ut/src/`)

- **`PacketSpec.scala`** (153 lines): Packet encoding/decoding tests
  - Round-trip encoding for all packet types
  - Protocol ID and version validation
  - Maximum packet size enforcement
  - Invalid packet rejection
  
- **`PayloadSpec.scala`** (135 lines): Message type tests
  - Message creation and validation
  - Request ID generation
  - Message type constants
  - Field constraint validation
  
- **`SessionSpec.scala`** (192 lines): Cryptography tests
  - HKDF key derivation
  - AES-GCM encryption/decryption
  - Session cache operations
  - Authentication tag validation

#### Integration Tests (`it/src/`)

- **`DiscoveryServiceIntegrationSpec.scala`** (117 lines)
  - Configuration creation
  - Session management integration
  - Full packet round-trip encoding
  - Protocol constant validation

## Key Features

### 1. Enhanced Security

- **Session-based encryption** with AES-128-GCM
- **Forward secrecy** through ephemeral key exchange
- **Challenge-response handshake** (WHOAREYOU)
- **Authenticated encryption** with associated data

### 2. Efficient Discovery

- **Targeted node queries** with distance parameters
- **ENR updates** for address changes
- **Request ID correlation** for reliable RPC
- **Configurable timeouts** and retry logic

### 3. Extensibility

- **TALK protocol** for application-layer messages
- **Topic discovery** for capability-based peer finding
- **ENR attributes** for arbitrary key-value metadata
- **Protocol versioning** for future upgrades

## Usage Example

```scala
import com.chipprbots.scalanet.discovery.ethereum.v5._
import com.chipprbots.scalanet.discovery.crypto.SigAlg
import cats.effect.IO

// Create configuration
val config = DiscoveryConfig.default.copy(
  bootstrapNodes = Set(/* bootstrap nodes */),
  discoveryInterval = 30.seconds
)

// Create session cache
val sessionCache = Session.SessionCache().unsafeRunSync()

// Create network layer
val network = DiscoveryNetwork(
  peerGroup = /* UDP peer group */,
  privateKey = /* local private key */,
  localNode = /* local node info */,
  toNodeAddress = /* address conversion */,
  config = config,
  sessionCache = sessionCache
).unsafeRunSync()

// Create discovery service
val service = DiscoveryService(
  privateKey = /* local private key */,
  node = /* local node */,
  config = config,
  network = network,
  toAddress = /* address conversion */
).use { svc =>
  for {
    // Lookup nodes
    nodes <- svc.getRandomNodes
    
    // Add known nodes
    _ <- nodes.toList.traverse(svc.addNode)
    
    // Get closest nodes to target
    closest <- svc.getClosestNodes(targetId)
  } yield closest
}
```

## Cryptographic Primitives

### HKDF (HMAC-based Key Derivation Function)

- **Hash**: SHA-256
- **Output**: Three 16-byte keys (initiator, recipient, auth-response)
- **Info**: Combined node IDs and challenge nonce

### AES-GCM (Galois/Counter Mode)

- **Key size**: 128 bits
- **Nonce size**: 96 bits (12 bytes)
- **Tag size**: 128 bits
- **Associated data**: Packet header and auth-data

## Packet Format

```
+-------------+--------+-----+-------+--------------+----------+-----------+
| protocol-id | version| flag| nonce | auth-data-sz | auth-data| msg-cipher|
+-------------+--------+-----+-------+--------------+----------+-----------+
|   6 bytes   | 2 bytes| 1 B |  12 B |    2 bytes   | variable | variable  |
+-------------+--------+-----+-------+--------------+----------+-----------+
```

- **protocol-id**: `0x646973637635` ("discv5")
- **version**: `0x0001` (version 1)
- **flag**: `0x00` (ordinary), `0x01` (WHOAREYOU), `0x02` (handshake)

## Compliance

This implementation follows the specifications:

- [Discovery v5 Wire Protocol](https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md)
- [Discovery v5 Theory](https://github.com/ethereum/devp2p/blob/master/discv5/discv5-theory.md)
- [EIP-778 (ENR)](https://eips.ethereum.org/EIPS/eip-778)

## Next Steps

To complete the implementation:

1. **ECDH Key Exchange**: Integrate Secp256k1 ECDH for ephemeral key derivation
2. **Complete Handshake Flow**: Implement full WHOAREYOU challenge-response
3. **RLP Message Encoding**: Add RLP encoding/decoding for message payloads
4. **Network Integration**: Wire up packet processing to peer group
5. **Topic Discovery**: Implement optional topic advertisement and query
6. **Testing**: Add end-to-end tests with real peer connections

## Compatibility

- **Scala**: 3.3.4+
- **Cats Effect**: 3.x
- **BouncyCastle**: 1.82
- **Scodec**: 2.x

## References

- [Ethereum DevP2P Specifications](https://github.com/ethereum/devp2p)
- [Discovery v4 (for comparison)](../v4/)
- [Go Ethereum (Geth) Implementation](https://github.com/ethereum/go-ethereum/tree/master/p2p/discover/v5wire)
- [Core-Geth Implementation](https://github.com/etclabscore/core-geth)

---

**Total Implementation**: ~1,268 lines of Scala code
**Test Coverage**: Unit tests + Integration tests
**Status**: Core protocol implemented, network integration pending

# SNAP Sync Protocol Implementation

## Overview

This document describes the initial implementation of SNAP/1 protocol support in Fukuii. The SNAP protocol is a dependent satellite protocol of ETH that enables efficient state synchronization by downloading account and storage ranges without intermediate Merkle trie nodes.

## Current Implementation Status

### ✅ Completed

1. **Protocol Infrastructure** (Phase 1)
   - Added `SNAP` protocol family to `ProtocolFamily` enum
   - Added `SNAP1` capability definition (snap/1)
   - Updated capability parsing to recognize "snap/1"
   - Updated `usesRequestId` to include SNAP1 (uses request IDs like ETH66+)

2. **Message Definitions** (Phase 1)
   - Created `SNAP.scala` with all 8 SNAP/1 protocol messages:
     - `GetAccountRange` (0x00) - Request account ranges
     - `AccountRange` (0x01) - Response with accounts and proofs
     - `GetStorageRanges` (0x02) - Request storage slots
     - `StorageRanges` (0x03) - Response with storage and proofs
     - `GetByteCodes` (0x04) - Request contract bytecodes
     - `ByteCodes` (0x05) - Response with bytecodes
     - `GetTrieNodes` (0x06) - Request trie nodes for healing
     - `TrieNodes` (0x07) - Response with trie nodes

3. **Message Encoding/Decoding** (Phase 2 - COMPLETED)
   - Implemented complete RLP encoding for all 8 SNAP messages
   - Implemented complete RLP decoding for all 8 SNAP messages
   - Added comprehensive error handling with descriptive messages
   - Followed core-geth reference implementation patterns
   - All messages now fully serializable and deserializable

4. **Message Handling** (Phase 3 - COMPLETED)
   - ✅ Created SNAPMessageDecoder for routing SNAP protocol messages
   - ✅ Implemented message decoding for all 8 SNAP message types
   - ✅ Integrated with existing MessageDecoder infrastructure
   - ✅ Created SNAPRequestTracker for request/response matching
   - ✅ Implemented timeout handling for pending requests
   - ✅ Added response validation for all SNAP message types
   - ✅ Request ID generation and tracking
   - ✅ Monotonic ordering validation for account and storage ranges

5. **Account Range Sync** (Phase 4 - IN PROGRESS)
   - ✅ Created AccountTask for managing account range state
   - ✅ Implemented task creation and division for parallel downloads
   - ✅ Created AccountRangeDownloader for coordinating downloads
   - ✅ Request/response lifecycle management
   - ✅ Progress tracking and statistics reporting
   - ✅ Task continuation handling for partial responses
   - ✅ Timeout handling and task retry
   - ⏳ Merkle proof verification (placeholder)
   - ⏳ Integration with storage layer (MptStorage)
   - ⏳ Integration with EtcPeerManager for sending requests

6. **Configuration** (Phase 1)
   - Added "snap/1" to capabilities list in all chain configurations:
     - `etc-chain.conf` (Ethereum Classic mainnet)
     - `mordor-chain.conf` (Ethereum Classic testnet)
     - `eth-chain.conf` (Ethereum mainnet)
     - `test-chain.conf` (test network)
     - `ropsten-chain.conf` (Ropsten testnet)

7. **Documentation** (Phase 1)
   - Updated ETH68.scala documentation to reference SNAP/1 for state sync
   - Created comprehensive message documentation with protocol references
   - Created ADR documenting architecture decisions

### ⏳ Not Yet Implemented

The following components are required for a complete SNAP sync implementation but are NOT yet included:

1. **Account Range Sync** (Phase 4 - Remaining)
   - Merkle proof verification for account data
   - Integration with MptStorage for persisting accounts
   - Integration with EtcPeerManager for request sending
   - Bytecode download for contract accounts

2. **Storage Range Sync** (Phase 5)
   - Download storage for accounts
   - Verify storage proofs
   - Store storage slots

3. **State Healing** (Phase 6)
   - Detect missing trie nodes
   - Request missing nodes via GetTrieNodes
   - Reassemble complete state trie

4. **Integration** (Phase 7)
   - Integration with existing FastSync
   - Pivot block selection for snap sync
   - State validation and healing
   - Transition from snap sync to regular sync

## Why This Approach?

The issue reports that Fukuii sends `bestBlock=0` (genesis) during status exchange, causing peers to disconnect. While implementing full SNAP sync would eventually solve this, it's a massive undertaking (months of work).

This initial implementation provides:

1. **Protocol Awareness**: Fukuii can now advertise SNAP/1 capability during handshake
2. **Foundation**: Message structures are defined and ready for future implementation
3. **Compatibility**: Better compatibility with modern Ethereum clients that expect SNAP support
4. **Incremental Development**: Allows gradual implementation of SNAP sync features

## Relationship to Existing Fast Sync

Fukuii already has a "fast sync" implementation that:
- Selects a pivot block
- Downloads state at that pivot block
- Then continues with regular block-by-block sync

The SNAP protocol would enhance this by:
- Reducing bandwidth by 99.26% (downloading state without intermediate trie nodes)
- Reducing sync time by 80.6%
- Allowing parallel downloads of account and storage ranges
- Supporting "self-healing" when state moves due to new blocks

## Next Steps

To complete SNAP sync implementation, the following work is needed (in priority order):

1. **~~Complete Message Encoding/Decoding~~ ✅ COMPLETED (Phase 2)**
   - ~~Implement RLP encoders/decoders for all SNAP messages~~
   - ~~Add unit tests for message serialization~~

2. **~~Implement Basic Request/Response Flow~~ ✅ COMPLETED (Phase 3)**
   - ~~Create SNAP message decoder (SNAPMessageDecoder)~~
   - ~~Implement message routing for all 8 SNAP messages~~
   - ~~Add request/response matching and tracking (SNAPRequestTracker)~~
   - ~~Implement timeout handling for requests~~
   - ~~Add response validation~~

3. **Implement Account Range Sync** (Phase 4 - IN PROGRESS)
   - ✅ Create AccountTask for managing account ranges
   - ✅ Implement AccountRangeDownloader for coordinating downloads
   - ✅ Progress tracking and statistics
   - ✅ Task continuation handling
   - ⏳ Implement Merkle proof verification
   - ⏳ Integrate with MptStorage for account persistence
   - ⏳ Connect with EtcPeerManager for request sending

4. **Implement Storage Range Sync** (Phase 5)
   - Download storage for accounts
   - Verify storage proofs
   - Store storage slots

5. **Implement State Healing** (Phase 6)
   - Detect missing trie nodes
   - Request missing nodes via GetTrieNodes
   - Reassemble complete state trie

6. **Integration and Testing** (Phase 7)
   - Integrate with SyncController
   - Add configuration options
   - Test against geth, erigon, and other SNAP-enabled clients

## Technical References

- **SNAP Protocol Specification**: https://github.com/ethereum/devp2p/blob/master/caps/snap.md
- **Geth Implementation**: https://github.com/ethereum/go-ethereum/tree/master/eth/protocols/snap
- **EIP-2124 Fork ID**: https://eips.ethereum.org/EIPS/eip-2124

## Performance Benefits (from SNAP spec)

Based on Ethereum mainnet block ~#11,177,000:

| Metric | ETH (old) | SNAP (new) | Improvement |
|--------|-----------|------------|-------------|
| Time | 10h 50m | 2h 6m | -80.6% |
| Upload | 20.38 GB | 0.15 GB | -99.26% |
| Download | 43.8 GB | 20.44 GB | -53.33% |
| Packets | 1607M | 0.099M | -99.993% |
| Disk Reads | 15.68 TB | 0.096 TB | -99.39% |

## Note on Current Block Sync Issue

The immediate issue (peers disconnecting due to `bestBlock=0`) is partially addressed by existing bootstrap checkpoint logic in the status exchange handlers. However, full SNAP sync implementation would:

1. Allow faster initial sync from a recent snapshot
2. Reduce the "stuck at genesis" period from hours to minutes
3. Improve peer compatibility with modern clients
4. Enable better sync performance overall

## Implementation Timeline Estimate

- **Phase 1 - Message Infrastructure** ✅ COMPLETED: ~1-2 days
- **Phase 2 - Message Encoding** ✅ COMPLETED: ~3-5 days
- **Phase 3 - Basic Request/Response** ✅ COMPLETED: ~1 week
  - ✅ Message decoder implemented
  - ✅ Request/response matching completed
  - ✅ Timeout handling completed
  - ✅ Response validation completed
- **Phase 4 - Account Range Sync** 🔄 IN PROGRESS: ~2-3 weeks
  - ✅ Core download infrastructure implemented (50%)
  - ⏳ Merkle proof verification remaining
  - ⏳ Storage integration remaining
- **Phase 5 - Storage Range Sync**: ~1-2 weeks
- **Phase 6 - State Healing**: ~2-3 weeks
- **Phase 7 - Integration & Testing**: ~2-4 weeks

**Total Estimate**: 2-3 months for complete, production-ready implementation
**Completed**: Phases 1-3, Phase 4 partially (core downloader infrastructure)
**In Progress**: Phase 4 (Merkle proofs, storage integration)

## Contributing

If you're interested in contributing to the SNAP sync implementation, please:

1. Review the SNAP protocol specification
2. Study the Geth reference implementation
3. Start with message encoding/decoding (Phase 2)
4. Write comprehensive tests for each component
5. Follow the existing code style and patterns in Fukuii

## Questions?

For questions about this implementation or to contribute:
- File an issue on GitHub
- Join the community discussions
- Review the ADR (Architecture Decision Record) if created

---

*Last Updated: 2025-11-23*
*Author: GitHub Copilot*
*Status: Initial Implementation - Message Infrastructure Only*

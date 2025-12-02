# SNAP Sync Message Routing Implementation Plan

**Date:** 2025-12-02  
**Phase:** Message Routing (P0 Critical Task)  
**Status:** Planning Complete - Ready for Implementation  
**Estimated Effort:** 1-2 weeks

## Executive Summary

This document outlines the plan for implementing message routing from EtcPeerManagerActor to SNAPSyncController. This is the next critical P0 task that will enable actual SNAP sync communication once peer integration is complete.

## Current State

### What Exists
- ✅ All 8 SNAP/1 protocol messages defined (SNAP.scala)
- ✅ SNAPMessageDecoder for decoding SNAP messages
- ✅ SNAPSyncController ready to receive messages
- ✅ EtcPeerManagerActor handles ETH protocol messages
- ✅ Message classification and subscription system

### What's Missing
- ⏳ SNAP message codes not subscribed to in EtcPeerManagerActor
- ⏳ No message routing from EtcPeerManagerActor to SNAPSyncController
- ⏳ No reference to SNAPSyncController in EtcPeerManagerActor
- ⏳ SNAP messages arrive but aren't forwarded

## Architecture Overview

### Current Message Flow (ETH Protocol)
```
Peer → PeerActor → PeerEventBus → EtcPeerManagerActor → [Updates PeerInfo]
```

### Target Message Flow (SNAP Protocol)
```
Peer → PeerActor → PeerEventBus → EtcPeerManagerActor → SNAPSyncController
                                                        ↓
                                              AccountRangeDownloader
                                              StorageRangeDownloader
                                              TrieNodeHealer
                                              ByteCodeDownloader
```

## Implementation Strategy

### Phase 1: Add SNAP Message Subscriptions

**File:** `EtcPeerManagerActor.scala`

**Changes:**
1. Import SNAP message types
2. Add SNAP message codes to subscription list
3. Subscribe to SNAP messages on peer handshake

```scala
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

object EtcPeerManagerActor {
  // Add SNAP codes
  val msgCodesWithInfo: Set[Int] = Set(
    Codes.BlockHeadersCode, 
    Codes.NewBlockCode, 
    Codes.NewBlockHashesCode,
    // SNAP protocol codes
    SNAP.Codes.AccountRangeCode,
    SNAP.Codes.StorageRangesCode,
    SNAP.Codes.TrieNodesCode,
    SNAP.Codes.ByteCodesCode
  )
}
```

### Phase 2: Add SNAPSyncController Reference

**File:** `EtcPeerManagerActor.scala`

**Constructor Parameter:**
```scala
class EtcPeerManagerActor(
    peerManagerActor: ActorRef,
    peerEventBusActor: ActorRef,
    appStateStorage: AppStateStorage,
    forkResolverOpt: Option[ForkResolver],
    snapSyncControllerOpt: Option[ActorRef] = None  // NEW
) extends Actor with ActorLogging
```

**Rationale:** Optional parameter allows gradual rollout and doesn't break existing code.

### Phase 3: Add Message Routing Logic

**File:** `EtcPeerManagerActor.scala`

**In `handlePeersInfoEvents` method:**
```scala
private def handlePeersInfoEvents(peersWithInfo: PeersWithInfo): Receive = {
  
  // Existing ETH message handling
  case MessageFromPeer(message, peerId) if peersWithInfo.contains(peerId) =>
    message match {
      // Route SNAP messages to SNAPSyncController
      case msg: AccountRange =>
        snapSyncControllerOpt.foreach(_ ! msg)
        val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
        NetworkMetrics.ReceivedMessagesCounter.increment()
        context.become(handleMessages(newPeersWithInfo))
      
      case msg: StorageRanges =>
        snapSyncControllerOpt.foreach(_ ! msg)
        val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
        NetworkMetrics.ReceivedMessagesCounter.increment()
        context.become(handleMessages(newPeersWithInfo))
      
      case msg: TrieNodes =>
        snapSyncControllerOpt.foreach(_ ! msg)
        val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
        NetworkMetrics.ReceivedMessagesCounter.increment()
        context.become(handleMessages(newPeersWithInfo))
      
      case msg: ByteCodes =>
        snapSyncControllerOpt.foreach(_ ! msg)
        val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
        NetworkMetrics.ReceivedMessagesCounter.increment()
        context.become(handleMessages(newPeersWithInfo))
      
      // Existing ETH message handling
      case _ =>
        val newPeersWithInfo = updatePeersWithInfo(peersWithInfo, peerId, message, handleReceivedMessage)
        NetworkMetrics.ReceivedMessagesCounter.increment()
        context.become(handleMessages(newPeersWithInfo))
    }
  
  // ... rest of existing handlers
}
```

### Phase 4: Update SyncController Integration

**File:** `SyncController.scala`

**Pass SNAPSyncController reference to EtcPeerManager:**

This requires coordination with node initialization. For now, document as future work since EtcPeerManagerActor is created before SyncController.

**Alternative Approach:** Use event bus pattern or late binding.

## Message Type Details

### SNAP Response Messages (Need Routing)

1. **AccountRange (0x01)**
   - Response to GetAccountRange
   - Contains account data and Merkle proofs
   - Route to: AccountRangeDownloader via SNAPSyncController

2. **StorageRanges (0x03)**
   - Response to GetStorageRanges
   - Contains storage slot data and proofs
   - Route to: StorageRangeDownloader via SNAPSyncController

3. **TrieNodes (0x07)**
   - Response to GetTrieNodes
   - Contains trie node data for healing
   - Route to: TrieNodeHealer via SNAPSyncController

4. **ByteCodes (0x05)**
   - Response to GetByteCodes
   - Contains contract bytecodes
   - Route to: ByteCodeDownloader via SNAPSyncController

### SNAP Request Messages (Not Routed Here)

- GetAccountRange (0x00) - Sent by AccountRangeDownloader
- GetStorageRanges (0x02) - Sent by StorageRangeDownloader
- GetTrieNodes (0x06) - Sent by TrieNodeHealer
- GetByteCodes (0x04) - Sent by ByteCodeDownloader

## Testing Strategy

### Unit Tests
- Test message routing to SNAPSyncController
- Test that SNAP messages don't break ETH message handling
- Test with snapSyncControllerOpt = None (backward compatibility)
- Test with snapSyncControllerOpt = Some(mockController)

### Integration Tests
- Test end-to-end message flow with mock peers
- Verify NetworkMetrics are updated correctly
- Test peer disconnection during SNAP sync

### Manual Testing
- Enable SNAP sync in config
- Monitor logs for message routing
- Verify messages reach SNAPSyncController

## Risk Assessment

### Low Risk ✅
- Optional parameter doesn't break existing code
- Message routing is additive, not replacing
- ETH protocol messages unaffected

### Medium Risk ⚠️
- Message volume could increase metrics overhead
- Need to ensure no memory leaks from routing
- Late binding complexity

### High Risk 🔴
- None identified

## Implementation Checklist

### Preparation
- [ ] Review current EtcPeerManagerActor implementation
- [ ] Understand message classification system
- [ ] Review SNAPSyncController message expectations
- [ ] Create test plan

### Development
- [ ] Add SNAP imports to EtcPeerManagerActor
- [ ] Add SNAP message codes to msgCodesWithInfo
- [ ] Add snapSyncControllerOpt parameter
- [ ] Implement message routing logic
- [ ] Update props method
- [ ] Add logging for SNAP message routing

### Testing
- [ ] Create unit tests for message routing
- [ ] Test backward compatibility (opt = None)
- [ ] Test with mock SNAPSyncController
- [ ] Integration test with full message flow

### Documentation
- [ ] Update EtcPeerManagerActor comments
- [ ] Document in SNAP_SYNC_STATUS.md
- [ ] Update SNAP_SYNC_TODO.md progress

## Alternative Approaches Considered

### 1. Direct Peer Communication
**Approach:** Have downloaders communicate directly with peers  
**Rejected:** Breaks existing architecture, high risk

### 2. Separate SNAP Peer Manager
**Approach:** Create SNAPPeerManagerActor  
**Rejected:** Duplicates code, harder to maintain

### 3. Event Bus Pattern (CHOSEN)
**Approach:** Use existing event bus with SNAP message types  
**Selected:** Fits existing architecture, low risk

## Dependencies

### Requires
- SNAPSyncController must be running
- SNAP message types must be decodable
- PeerEventBus must support SNAP messages

### Blocks
- Peer communication implementation
- ByteCode download implementation
- E2E testing on testnet

## Success Criteria

1. ✅ SNAP messages subscribed in EtcPeerManagerActor
2. ✅ Messages correctly routed to SNAPSyncController
3. ✅ No regression in ETH protocol handling
4. ✅ NetworkMetrics updated correctly
5. ✅ Backward compatible (no SNAP controller = no errors)
6. ✅ Unit tests pass
7. ✅ Integration tests pass

## Timeline

**Week 1:**
- Days 1-2: Implementation
- Days 3-4: Unit testing
- Day 5: Code review and fixes

**Week 2:**
- Days 1-2: Integration testing
- Days 3-4: Documentation
- Day 5: Final review and merge

## Next Steps After Message Routing

Once message routing is complete:

1. **Peer Communication (Weeks 2-4)**
   - Remove simulation timeouts
   - Implement actual peer selection
   - Connect downloaders to peer manager

2. **ByteCode Download (Week 5)**
   - Create ByteCodeDownloader
   - Integrate with message routing
   - Test contract code retrieval

3. **Testing (Weeks 6-8)**
   - Unit tests for all components
   - Integration tests with mock peers
   - E2E tests on Mordor testnet

## Conclusion

Message routing is the critical link between infrastructure and peer communication. This implementation keeps risk low by using optional parameters and the existing event bus architecture. Once complete, SNAP sync will be ready for actual peer integration.

**Recommendation:** Proceed with implementation as planned, starting with Phase 1 (SNAP message subscriptions).

---

**Prepared by:** GitHub Copilot  
**Last Updated:** 2025-12-02  
**Status:** Ready for Implementation

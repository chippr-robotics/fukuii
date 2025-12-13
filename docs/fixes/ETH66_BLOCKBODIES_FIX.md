# ETH66+/ETH68 BlockBodies Response Matching Fix

## Problem Statement

Peers upgraded to ETH66+/ETH68 respond to `GetBlockBodies` requests with request-id tagged payloads. The previous implementation only matched ETH62 responses, causing:

- Every bodies batch to be dropped
- The block queue to never drain  
- Header fetches to stall
- Node sync to fail

## Root Cause

The `PeersClient` actor is responsible for:
1. Adapting outgoing messages to match the peer's protocol version
2. Creating request handlers that expect responses in the correct format

The bug was in step 2: the `responseClassTag()` method was called with the **original** message instead of the **adapted** message, causing a type mismatch.

### Message Flow

1. `BodiesFetcher` sends `ETH62.GetBlockBodies` (line 81 in BodiesFetcher.scala)
2. `PeersClient` adapts it to `ETH66.GetBlockBodies` for ETH66+ peers (lines 170-172 in PeersClient.scala)
3. `PeersClient` creates a `PeerRequestHandler` expecting response type based on `responseClassTag`
4. **BUG**: If `responseClassTag(message)` was used instead of `responseClassTag(adaptedMessage)`:
   - Handler expects `ETH62.BlockBodies` (wrong!)
   - Peer responds with `ETH66.BlockBodies` (correct for ETH66+ peer)
   - Pattern match in `PeerRequestHandler` line 62 fails
   - Response is dropped as unhandled message

## The Fix

**File**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/PeersClient.scala`  
**Line**: 102

```scala
val handler =
  makeRequest(peer, adaptedMessage, responseMsgCode(adaptedMessage), adaptedToSerializable)(
    scheduler,
    responseClassTag(adaptedMessage)  // ✅ Uses adapted message, not original
  )
```

This ensures:
- When peer supports ETH66+, handler expects `ETH66.BlockBodies`
- When peer supports ETH62, handler expects `ETH62.BlockBodies`
- Response type always matches the request type sent to the peer

## Supporting Changes

The fix works in conjunction with other components:

### 1. BodiesFetcher Handles Both Message Types

**File**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BodiesFetcher.scala`  
**Lines**: 51-54

```scala
case AdaptedMessage(peer, eth62Bodies: Eth62BlockBodies) =>
  handleBodiesResponse(peer, eth62Bodies.bodies, protocolLabel = "ETH62")
case AdaptedMessage(peer, eth66Bodies: Eth66BlockBodies) =>
  handleBodiesResponse(peer, eth66Bodies.bodies, protocolLabel = "ETH66")
```

The BodiesFetcher correctly handles responses in both formats.

### 2. ETH66 Decoder Has Backward Compatibility

**File**: `src/main/scala/com/chipprbots/ethereum/network/p2p/messages/ETH66.scala`  
**Lines**: 228-243

The `ETH66.BlockBodies` decoder can fall back to parsing ETH62 format if needed:

```scala
case rlpList: RLPList if rlpList.items.size == 2 =>
  // ETH66+ format: [requestId, [bodies...]]
case rlpList: RLPList =>
  // Backward compatibility: ETH62 format without request-id
  BlockBodies(requestId = 0, rlpList.items.map(_.toBlockBody))
```

### 3. Message Decoders Use ETH66 Format

**File**: `src/main/scala/com/chipprbots/ethereum/network/p2p/MessageDecoders.scala`

For ETH66, ETH67, and ETH68 protocols, the message decoders use the ETH66 message types with backward compatibility built in.

## Verification

### Existing Test Coverage

**File**: `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockFetcherSpec.scala`  
**Line**: 431

The test suite includes a test case that verifies ETH62 requests can receive ETH66 responses:

```scala
// Expect ETH62 format message (no requestId)
peersClient.expectMsgPF() {
  case PeersClient.Request(msg: ETH62GetBlockBodies, _, _) => ()
}

// Respond with ETH66 format (requestId defaults to 0)
val firstGetBlockBodiesResponse = ETH66BlockBodies(0, firstBlocksBatch.map(_.body))
peersClient.reply(PeersClient.Response(fakePeer, firstGetBlockBodiesResponse))
```

### Protocol Version Adaptation

The adaptation logic in `PeersClient.adaptMessageForPeer` (lines 155-185) correctly:
- Checks if peer uses request-id via `Capability.usesRequestId()`
- Converts ETH62 → ETH66 for peers that support request-id
- Converts ETH66 → ETH62 for peers that don't support request-id
- Leaves message unchanged if already in correct format

## Impact

This fix resolves the sync failure issue for nodes connecting to ETH66+ peers. Without this fix:
- Nodes would repeatedly request block bodies
- All responses would be silently dropped
- Sync would stall indefinitely
- Header fetching would timeout

With this fix:
- ETH66+ responses are correctly matched and processed
- Block bodies flow normally through the sync pipeline
- Nodes can sync from both ETH62 and ETH66+ peers
- Cross-version compatibility is maintained

## Related Code

- `PeerRequestHandler`: Uses ClassTag to pattern match responses (line 62)
- `Capability.usesRequestId()`: Determines if peer protocol uses request-id
- `ETH66.nextRequestId`: Generates unique request IDs for ETH66+ messages
- All ETH66+ decoders: Include backward compatibility for ETH62 format

## Testing Recommendations

To fully test this fix in a real environment:
1. Connect to a network with mixed ETH62 and ETH66+ peers
2. Monitor logs for "PEER_REQUEST_SUCCESS" messages with both response types
3. Verify block bodies are received from both peer types
4. Confirm sync progresses normally without stalling
5. Check that no "unhandled message" warnings appear in logs

## References

- ETH66 Specification: https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth66
- ECIP Discussion: https://github.com/ethereumclassic/ECIPs

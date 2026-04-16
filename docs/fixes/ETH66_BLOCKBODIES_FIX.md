# ETH66+/ETH68 BlockBodies Response Matching Fix

## Problem Statement

Peers upgraded to ETH66+/ETH68 respond to `GetBlockBodies` requests with request-id tagged payloads. The sync pipeline was initially dropping these responses because the pattern matching expected ETH62 format responses, causing block queue stalls and sync failures.

## Root Cause

There were two related issues in the sync pipeline:

### Issue 1: Response Type Mismatch (Fixed)
The `PeersClient` actor is responsible for:
1. Adapting outgoing messages to match the peer's protocol version
2. Creating request handlers that expect responses in the correct format

The bug was in step 2: the `responseClassTag()` method was called with the **original** message instead of the **adapted** message, causing a type mismatch when the request format was adapted from ETH62 to ETH66 for newer peers.

### Issue 2: BodiesFetcher Using Legacy Protocol (Fixed)
The `BodiesFetcher` was sending requests using the legacy ETH62 format without request IDs, while `HeadersFetcher` was already upgraded to use ETH66 format. This created unnecessary protocol adaptation overhead and was inconsistent with the rest of the codebase.

## Message Flow (Before Fix)

### Old Flow with Both Issues
1. `BodiesFetcher` sends `ETH62.GetBlockBodies` (no request ID)
2. `PeersClient` adapts it to `ETH66.GetBlockBodies` for ETH66+ peers
3. `PeersClient` creates a `PeerRequestHandler` expecting response type based on `responseClassTag`
4. **Bug 1**: If `responseClassTag(message)` was used instead of `responseClassTag(adaptedMessage)`:
   - Handler expects `ETH62.BlockBodies` (wrong!)
   - Peer responds with `ETH66.BlockBodies` (correct for ETH66+ peer)
   - Pattern match in `PeerRequestHandler` line 62 fails
   - Response is dropped as unhandled message

## The Fix

### Fix 1: Response ClassTag (Already Implemented)

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

### Fix 2: BodiesFetcher Protocol Upgrade (New)

**File**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BodiesFetcher.scala`  
**Lines**: 22-24, 82-83

Updated imports:
```scala
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockBodies => Eth62BlockBodies}
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockBodies => Eth66BlockBodies, GetBlockBodies => Eth66GetBlockBodies}
```

Updated request method:
```scala
private def requestBodies(hashes: Seq[ByteString]): Unit = {
  log.debug("Requesting {} block bodies", hashes.size)
  val msg = Eth66GetBlockBodies(ETH66.nextRequestId, hashes)  // ✅ Now uses ETH66 with request ID
  val resp = makeRequest(Request.create(msg, BestPeer), BodiesFetcher.RetryBodiesRequest)
  // ...
}
```

This change:
- Eliminates unnecessary protocol adaptation overhead
- Makes BodiesFetcher consistent with HeadersFetcher
- Sends requests with proper request IDs for ETH66+ peers
- Allows PeersClient to adapt to ETH62 for older peers if needed (reverse adaptation)

## Supporting Changes

The fix works in conjunction with other components:

### 1. BodiesFetcher Handles Both Message Types

**File**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/regular/BodiesFetcher.scala`  
**Lines**: 52-55

```scala
case AdaptedMessage(peer, eth62Bodies: Eth62BlockBodies) =>
  handleBodiesResponse(peer, eth62Bodies.bodies, protocolLabel = "ETH62")
case AdaptedMessage(peer, eth66Bodies: Eth66BlockBodies) =>
  handleBodiesResponse(peer, eth66Bodies.bodies, protocolLabel = "ETH66")
```

The BodiesFetcher still handles responses in both formats for backward compatibility.

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

### 4. PeersClient Bi-directional Adaptation

**File**: `src/main/scala/com/chipprbots/ethereum/blockchain/sync/PeersClient.scala`  
**Lines**: 155-185

The adaptation logic now works in both directions:
- ETH62 → ETH66 for peers that support request-id (forward adaptation)
- ETH66 → ETH62 for peers that don't support request-id (reverse adaptation)

This ensures compatibility with mixed-protocol networks.

## Verification

### Test Coverage Updated

**File**: `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/BlockFetcherSpec.scala`

All test cases updated to expect ETH66 format requests:
- Line 181: `case PeersClient.Request(msg: ETH66GetBlockBodies, _, _)`
- Line 218: `case PeersClient.Request(msg: ETH66GetBlockBodies, _, _)`
- Lines 265-274: Updated union types and pattern matching
- Line 427: Helper method now expects ETH66 format

Tests verify that:
- Requests are sent in ETH66 format with request IDs
- Responses can be in either ETH62 or ETH66 format
- Both response formats are handled correctly

### Protocol Version Adaptation

The adaptation logic in `PeersClient.adaptMessageForPeer` (lines 155-185) correctly:
- Checks if peer uses request-id via `Capability.usesRequestId()`
- Converts ETH66 → ETH62 for peers that don't support request-id
- Converts ETH62 → ETH66 for peers that support request-id
- Leaves message unchanged if already in correct format

## Impact

This complete fix resolves sync failures with both legacy and modern peers:

### With Both Fixes
- ✅ BodiesFetcher sends modern ETH66 requests (consistent with HeadersFetcher)
- ✅ ETH66+ responses are correctly matched and processed
- ✅ PeersClient adapts requests based on peer capability
- ✅ Block bodies flow normally through the sync pipeline
- ✅ Nodes can sync from both ETH62 and ETH66+ peers
- ✅ Cross-version compatibility is maintained
- ✅ No unnecessary protocol adaptation overhead

### Benefits
1. **Consistency**: All fetchers now use modern protocol by default
2. **Performance**: Eliminates double adaptation (ETH62→ETH66→ETH62 for old peers)
3. **Maintainability**: Codebase follows same pattern across all fetchers
4. **Future-proof**: Ready for ETH68+ protocol versions

## Related Code

- `PeerRequestHandler`: Uses ClassTag to pattern match responses (line 62)
- `Capability.usesRequestId()`: Determines if peer protocol uses request-id
- `ETH66.nextRequestId`: Generates unique request IDs for ETH66+ messages
- `HeadersFetcher`: Already using ETH66.GetBlockHeaders (line 97)
- All ETH66+ decoders: Include backward compatibility for ETH62 format

## Testing Recommendations

To fully test this fix in a real environment:
1. Connect to a network with mixed ETH62 and ETH66+ peers
2. Monitor logs for "PEER_REQUEST_SUCCESS" messages with both response types
3. Verify block bodies are requested in ETH66 format with request IDs
4. Confirm sync progresses normally without stalling
5. Check that no "unhandled message" warnings appear in logs
6. Verify compatibility with both old (ETH62) and new (ETH66+) peers

## References

- ETH66 Specification: https://github.com/ethereum/devp2p/blob/master/caps/eth.md#eth66
- ECIP Discussion: https://github.com/ethereumclassic/ECIPs

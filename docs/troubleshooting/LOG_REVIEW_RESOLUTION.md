# Network Protocol Compatibility Guide

This document provides technical reference for network protocol compatibility. All issues documented here have been resolved.

## Protocol Compatibility Summary

| Protocol | Status | Notes |
|----------|--------|-------|
| ETH63 | ✅ Supported | Legacy support |
| ETH64/65 | ✅ Supported | Full compatibility |
| ETH66 | ✅ Supported | Request-id wrapped messages |
| ETH67 | ✅ Supported | NewPooledTransactionHashes v2 |
| ETH68 | ✅ Supported | Current production version |

---

## ETH66+ Message Adaptation

### Overview

ETH66 and later protocols wrap requests with a request-id for request/response matching. The message adaptation layer automatically handles this.

### How It Works

The `PeersClient.adaptMessageForPeer` method adapts messages based on negotiated capabilities:

```scala
private def adaptMessageForPeer[RequestMsg <: Message](peer: Peer, message: RequestMsg): Message =
  handshakedPeers.get(peer.id) match {
    case Some(peerWithInfo) =>
      val usesRequestId = Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability)
      message match {
        // GetBlockHeaders adaptation
        case eth66: ETH66GetBlockHeaders if !usesRequestId =>
          ETH62.GetBlockHeaders(eth66.block, eth66.maxHeaders, eth66.skip, eth66.reverse)
        case eth62: ETH62.GetBlockHeaders if usesRequestId =>
          ETH66GetBlockHeaders(ETH66.nextRequestId, eth62.block, eth62.maxHeaders, eth62.skip, eth62.reverse)
        // GetBlockBodies adaptation
        case eth66: ETH66GetBlockBodies if !usesRequestId =>
          ETH62.GetBlockBodies(eth66.hashes)
        case eth62: ETH62.GetBlockBodies if usesRequestId =>
          ETH66GetBlockBodies(ETH66.nextRequestId, eth62.hashes)
        // GetReceipts adaptation
        case eth66: ETH66GetReceipts if !usesRequestId =>
          ETH63.GetReceipts(eth66.blockHashes)
        case eth63: ETH63.GetReceipts if usesRequestId =>
          ETH66GetReceipts(ETH66.nextRequestId, eth63.blockHashes)
        case _ => message
      }
```

### Verification

1. **Check message format in logs**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep "ENCODE_MSG"
   ```
   GetReceipts to ETH66+ peer should show format: `f8...<requestId><[hashes]>`

2. **Monitor successful responses**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep -E "Received.*block bodies|Received.*receipts"
   ```
   Should see "(ETH66)" suffix for responses from ETH66+ peers

---

## ForkId Compatibility

### Overview

ForkId validation ensures peers are on compatible chains. Nodes starting from block 0 now use bootstrap pivot for ForkId calculation.

### Implementation

```scala
val forkIdBlockNumber = if (bootstrapPivotBlock > 0) {
  val threshold = math.min(bootstrapPivotBlock / 10, BigInt(100000))
  val shouldUseBootstrap = bestBlockNumber < (bootstrapPivotBlock - threshold)
  if (shouldUseBootstrap) bootstrapPivotBlock else bestBlockNumber
} else bestBlockNumber
```

### Verification

1. **Check ForkId at Startup**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep "Sending status"
   ```
   At block 0, should show: `forkId=ForkId(0xbe46d57c, None)` for ETC mainnet

2. **Monitor Peer Connections**:
   ```bash
   ./bin/fukuii etc 2>&1 | grep -E "PEER_HANDSHAKE_SUCCESS|DISCONNECT_DEBUG"
   ```
   Should see sustained peer connections without immediate 0x10 disconnects

---

## Snappy Compression

### Overview

All ETH protocol messages use Snappy compression (p2pVersion >= 5). The implementation correctly handles both compressed and uncompressed data.

### Logic

```scala
// Always attempt decompression first
Try(Snappy.uncompress(data)) match {
  case Success(decompressed) => decompressed
  case Failure(_) if looksLikeRLP(data) => data  // Fallback for uncompressed
  case Failure(ex) => throw ex
}
```

---

## ETH67 Transaction Announcements

### Overview

ETH67 introduced a new format for `NewPooledTransactionHashes` with types and sizes arrays.

### Implementation

Types are encoded as a byte string (matching Go's `[]byte`):
```scala
RLPValue(types.toArray)  // Not RLPList
```

---

## Key Files

| File | Purpose |
|------|---------|
| `PeersClient.scala` | Message adaptation |
| `FastSync.scala` | Sync request handling |
| `EthNodeStatus64ExchangeState.scala` | ForkId calculation |
| `MessageCodec.scala` | Snappy compression |
| `ETH67.scala` | Transaction announcement encoding |

---

## Related Documentation

- [Block Sync Guide](BLOCK_SYNC_TROUBLESHOOTING.md)
- [Known Issues](../runbooks/known-issues.md)
- [devp2p Specification](https://github.com/ethereum/devp2p)

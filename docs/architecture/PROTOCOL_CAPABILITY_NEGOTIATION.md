# Protocol Capability Negotiation and Geth Compatibility

## Overview

This document explains how Fukuii negotiates protocol capabilities with peers, particularly focusing on compatibility with Geth (go-ethereum) and other Ethereum clients.

## Supported Protocol Versions

### Current Support (as of this update)

Fukuii advertises the following protocol capabilities (`src/main/scala/com/chipprbots/ethereum/utils/InstanceConfig.scala`):

```scala
val supportedCapabilities: List[Capability] = List(
  Capability.ETH68,  // ETH protocol version 68
  Capability.ETH69,  // ETH protocol version 69 (latest)
  Capability.SNAP1   // SNAP/1 protocol (satellite protocol for state sync)
)
```

### Legacy Versions (not advertised)

ETH63 through ETH67 are **not advertised** in the Hello message. Message definitions for
those versions still exist in the codebase (e.g. for parsing peer capability strings), but
Fukuii will not negotiate them. Note that **ETH68 removed `GetNodeData`/`NodeData`** — those
messages do not exist in ETH68+; state retrieval uses the SNAP/1 satellite protocol instead.

## Why Advertise Multiple Versions?

### Why a version list at all?

The DevP2P Hello message carries a *list* of capabilities, and clients advertise every
version they actively support so both sides can find the highest common one. Current Geth
releases advertise `eth/68`, `eth/69`, `snap/1` — exactly the set Fukuii advertises.

Advertising the full supported set ensures:
1. **Maximum compatibility** with peers supporting different protocol versions
2. **Proper negotiation** where both sides can find a common version
3. **Graceful coexistence** with peers that have not yet adopted the newest version (e.g. an
   eth/68-only peer still negotiates `eth/68` with us)

Versions older than ETH68 are deliberately excluded: the live ETC peer set negotiates
ETH68+/SNAP1, and carrying decoders for retired versions adds surface area without benefit.

## Protocol Negotiation Algorithm

The negotiation algorithm in `Capability.negotiate()` works as follows:

```scala
def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] = {
  val ethVersions1 = c1.collect { case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 | ETH69) => cap }
  val ethVersions2 = c2.collect { case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 | ETH69) => cap }

  // For each protocol family, find the highest common version
  val negotiatedCapabilities = List(
    // ETH: find the highest version that BOTH sides advertise (strict intersection).
    // We only return a capability from our own set to guarantee we have a decoder for it.
    if (ethVersions1.nonEmpty && ethVersions2.nonEmpty) {
      val commonVersions = ethVersions1.map(_.version).toSet.intersect(ethVersions2.map(_.version).toSet)
      if (commonVersions.isEmpty) None
      else ethVersions1.find(_.version == commonVersions.max) // always from our side — we have the decoder
    } else None,
    // SNAP: exact match required
    if (snapVersions1.intersect(snapVersions2).nonEmpty) Some(SNAP1) else None
  ).flatten

  negotiatedCapabilities match {
    case Nil => None
    case l   => Some(best(l))
  }
}
```

### Negotiation Examples

#### Example 1: Peer with ETH68 only
- **Peer advertises**: `eth/68`, `snap/1`
- **We advertise**: `eth/68`, `eth/69`, `snap/1`
- **Negotiation**:
  - Common ETH versions: {68}
  - Highest common: 68
  - Find 68 in our list: ✓ Found
- **Result**: `eth/68` and `snap/1` (both use RequestId wrapper)

#### Example 2: Geth peer with multiple versions
- **Peer advertises**: `eth/68`, `eth/69`, `snap/1`
- **We advertise**: `eth/68`, `eth/69`, `snap/1`
- **Negotiation**:
  - Common ETH versions: {68, 69}
  - Highest common: 69
  - Find 69 in our list: ✓ Found
- **Result**: `eth/69` and `snap/1` (both use RequestId wrapper)

#### Example 3: Legacy peer with ETH66 or older
- **Peer advertises**: `eth/66`
- **We advertise**: `eth/68`, `eth/69`, `snap/1`
- **Negotiation**:
  - Common ETH versions: ∅ (we do not advertise anything below 68)
  - No common version
- **Result**: negotiation fails — the peer is disconnected with `IncompatibleP2pProtocolVersion`

## Protocol Differences

### RequestId Wrapper

A critical difference between protocol versions is the use of RequestId wrapper:

| Protocol | RequestId | Description |
|----------|-----------|-------------|
| ETH63    | ❌ No     | Legacy format, no request tracking |
| ETH64    | ❌ No     | Adds ForkId, no request tracking |
| ETH65    | ❌ No     | Adds tx pool messages, no request tracking |
| ETH66    | ✅ Yes    | Adds RequestId to all request/response pairs |
| ETH67    | ✅ Yes    | Enhanced tx announcements with types/sizes |
| ETH68    | ✅ Yes    | **Removes GetNodeData/NodeData** — these messages no longer exist; state is fetched via SNAP instead |
| ETH69    | ✅ Yes    | No total difficulty in Status — chain weight is derived via calibration instead |
| SNAP1    | ✅ Yes    | State sync protocol (satellite to ETH) |

The code checks this using `Capability.usesRequestId()`:

```scala
def usesRequestId(capability: Capability): Boolean = capability match {
  case ETH66 | ETH67 | ETH68 | ETH69 | SNAP1 => true
  case _                                     => false
}
```

### Message Format Adaptation

The `PeersClient` automatically adapts message formats based on the negotiated protocol:

```scala
val usesRequestId = Capability.usesRequestId(peerWithInfo.peerInfo.remoteStatus.capability)

message match {
  case eth66: ETH66GetBlockHeaders if !usesRequestId =>
    // Convert to ETH62 format for older peers
    ETH62.GetBlockHeaders(eth66.block, eth66.maxHeaders, eth66.skip, eth66.reverse)
    
  case eth62: ETH62.GetBlockHeaders if usesRequestId =>
    // Convert to ETH66 format for newer peers
    ETH66GetBlockHeaders(ETH66.nextRequestId, eth62.block, eth62.maxHeaders, eth62.skip, eth62.reverse)
}
```

## Diagnostic Logging

Enhanced logging has been added to help diagnose protocol negotiation issues:

### Capability Exchange
```
[INFO] PEER_CAPABILITIES: clientId=Geth/v1.16.1, p2pVersion=5, capabilities=[eth/68, eth/69, snap/1]
[INFO] OUR_CAPABILITIES: capabilities=[ETH68, ETH69, SNAP1]
```

### Negotiation Result
```
[INFO] CAPABILITY_NEGOTIATION: peerCaps=[eth/68, eth/69, snap/1], ourCaps=[ETH68, ETH69, SNAP1], negotiated=ETH69
[INFO] PROTOCOL_NEGOTIATED: clientId=Geth/v1.16.1, protocol=ETH69, usesRequestId=true
```

### Negotiation Failure
```
[WARN] PROTOCOL_NEGOTIATION_FAILED: clientId=OldClient, peerCaps=[eth/62], ourCaps=[ETH68, ETH69, SNAP1], reason=IncompatibleP2pProtocolVersion
```

## Troubleshooting

### Symptom: peer disconnects with IncompatibleP2pProtocolVersion

**Cause**: The peer only advertises versions below `eth/68` (e.g. `eth/65`). Fukuii advertises `eth/68`, `eth/69`, `snap/1` only, so there is no common version and negotiation fails.

**Solution**: This is expected behavior. The peer needs to be upgraded to support eth/68 or newer.

**Verification**: Check the logs:
```bash
grep "CAPABILITY_NEGOTIATION" fukuii.log
```

### Symptom: Cannot decode messages from peer

**Cause**: Mismatch between negotiated protocol and actual message format used by peer.

**Solution**: 
1. Check the negotiated protocol in logs
2. Verify RequestId wrapper is used correctly
3. Check if peer is sending messages in the wrong format (protocol deviation)

**Verification**:
```bash
grep "Cannot decode\|PROTOCOL_NEGOTIATED" fukuii.log
```

### Symptom: Peer disconnects immediately after handshake

**Cause**: No common protocol version could be negotiated.

**Solution**: Check peer's advertised capabilities and ensure we support at least one version.

**Verification**:
```bash
grep "PROTOCOL_NEGOTIATION_FAILED\|IncompatibleP2pProtocolVersion" fukuii.log
```

## References

- [Ethereum DevP2P Specification](https://github.com/ethereum/devp2p)
- [ETH Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/eth.md)
- [SNAP Protocol Specification](https://github.com/ethereum/devp2p/blob/master/caps/snap.md)
- [Geth Implementation](https://github.com/ethereum/go-ethereum)

## Related Documents

- [RLPX Handshake and Message Encoding](../analysis/RLPX_HANDSHAKE_AND_MESSAGE_ENCODING_ANALYSIS.md) - Low-level protocol details
- [ETH66 Protocol-Aware Message Formatting](../adr/consensus/CON-005-eth66-protocol-aware-message-formatting.md) - RequestId implementation

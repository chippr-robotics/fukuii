# Protocol Capability Negotiation and Geth Compatibility

## Overview

This document explains how Fukuii negotiates protocol capabilities with peers, particularly focusing on compatibility with Geth (go-ethereum) and other Ethereum clients.

## Supported Protocol Versions

### Current Support (as of this update)

Fukuii supports the following protocol capabilities:

```scala
val supportedCapabilities: List[Capability] = List(
  Capability.ETH66,  // ETH protocol version 66
  Capability.ETH67,  // ETH protocol version 67
  Capability.ETH68,  // ETH protocol version 68 (latest)
  Capability.SNAP1   // SNAP/1 protocol (satellite protocol for state sync)
)
```

### Legacy Support

While not actively advertised, Fukuii can also decode messages from:
- **ETH63**: Legacy protocol without ForkId support
- **ETH64**: Adds ForkId support
- **ETH65**: Adds transaction pool messages

These are supported for backward compatibility during the negotiation phase but are not advertised in the Hello message.

## Why Advertise Multiple Versions?

### Incorrect Previous Approach

Previously, Fukuii only advertised `ETH68` and `SNAP1`, based on a misunderstanding that:
> "Per DevP2P spec: advertise only the highest version of each protocol family"

This was **incorrect** and caused negotiation failures with peers that only supported older protocol versions.

### Correct Approach (Aligned with Geth)

**Geth and other Ethereum clients advertise ALL supported protocol versions**, not just the highest one. For example:
- Geth advertises: `eth/66`, `eth/67`, `eth/68`, `snap/1`
- Besu advertises: `eth/66`, `eth/67`, `eth/68`, `snap/1`

This approach ensures:
1. **Maximum compatibility** with peers supporting different protocol versions
2. **Proper negotiation** where both sides can find a common version
3. **Backward compatibility** with older clients that may only support ETH65 or ETH66

## Protocol Negotiation Algorithm

The negotiation algorithm in `Capability.negotiate()` works as follows:

```scala
def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] = {
  // ETH protocol versions are backward compatible
  // If we advertise ETH68 and peer advertises ETH64, we should negotiate ETH64
  // This means we need to find the highest common version for each protocol family

  val ethVersions1 = c1.collect { case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68) => cap }
  val ethVersions2 = c2.collect { case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68) => cap }

  // For each protocol family, find the highest common version
  val negotiatedCapabilities = List(
    // ETH: if both support ETH, use the minimum of their maximum versions
    if (ethVersions1.nonEmpty && ethVersions2.nonEmpty) {
      val maxVersion = math.min(
        ethVersions1.maxBy(_.version).version,
        ethVersions2.maxBy(_.version).version
      )
      // Find the capability with that version number from either side
      ethVersions1.find(_.version == maxVersion)
        .orElse(ethVersions2.find(_.version == maxVersion))
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

#### Example 1: Peer with ETH65 only
- **Peer advertises**: `eth/65`
- **We advertise**: `eth/66`, `eth/67`, `eth/68`, `snap/1`
- **Negotiation**:
  - Our max: 68, Peer max: 65
  - Common version: min(68, 65) = 65
  - Find 65 in peer's list: ✓ Found
- **Result**: `eth/65` (no RequestId wrapper)

#### Example 2: Geth peer with multiple versions
- **Peer advertises**: `eth/66`, `eth/67`, `eth/68`, `snap/1`
- **We advertise**: `eth/66`, `eth/67`, `eth/68`, `snap/1`
- **Negotiation**:
  - Our max: 68, Peer max: 68
  - Common version: min(68, 68) = 68
  - Find 68 in our list: ✓ Found
- **Result**: `eth/68` and `snap/1` (both use RequestId wrapper)

#### Example 3: Legacy peer with ETH64 only
- **Peer advertises**: `eth/64`
- **We advertise**: `eth/66`, `eth/67`, `eth/68`, `snap/1`
- **Negotiation**:
  - Our max: 68, Peer max: 64
  - Common version: min(68, 64) = 64
  - Find 64 in our list: ✗ Not found
  - Find 64 in peer's list: ✓ Found
- **Result**: `eth/64` (no RequestId wrapper)

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
| ETH68    | ✅ Yes    | Removes GetNodeData/NodeData (use SNAP instead) |
| SNAP1    | ✅ Yes    | State sync protocol (satellite to ETH) |

The code checks this using `Capability.usesRequestId()`:

```scala
def usesRequestId(capability: Capability): Boolean = capability match {
  case ETH66 | ETH67 | ETH68 | SNAP1 => true
  case _                             => false
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
[INFO] PEER_CAPABILITIES: clientId=Geth/v1.13.5, p2pVersion=5, capabilities=[eth/66, eth/67, eth/68, snap/1]
[INFO] OUR_CAPABILITIES: capabilities=[ETH66, ETH67, ETH68, SNAP1]
```

### Negotiation Result
```
[INFO] CAPABILITY_NEGOTIATION: peerCaps=[eth/66, eth/67, eth/68, snap/1], ourCaps=[ETH66, ETH67, ETH68, SNAP1], negotiated=ETH68
[INFO] PROTOCOL_NEGOTIATED: clientId=Geth/v1.13.5, protocol=ETH68, usesRequestId=true
```

### Negotiation Failure
```
[WARN] PROTOCOL_NEGOTIATION_FAILED: clientId=OldClient, peerCaps=[eth/62], ourCaps=[ETH66, ETH67, ETH68, SNAP1], reason=IncompatibleP2pProtocolVersion
```

## Troubleshooting

### Symptom: "eth/65 is being selected" when expecting eth/68

**Cause**: The peer only advertises `eth/65`, so negotiation correctly selects the highest common version.

**Solution**: This is expected behavior. The peer needs to be upgraded to support newer protocols.

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

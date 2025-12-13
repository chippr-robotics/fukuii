# Protocol Version Selection Troubleshooting Guide

## Quick Reference: "Why is ETH 65 being selected?"

### Understanding Protocol Negotiation

When Fukuii connects to a peer, both nodes exchange their supported protocol capabilities and negotiate the **highest common version**. If you see ETH 65 being selected when you expected a higher version (like ETH 68), it means:

**The peer only supports up to ETH 65.**

This is normal and expected behavior. Protocol negotiation always selects the highest version that **both** sides support.

## Common Scenarios

### Scenario 1: Peer Only Supports ETH 65

**Symptoms**:
```
[INFO] PEER_CAPABILITIES: clientId=SomeClient, capabilities=[eth/65]
[INFO] OUR_CAPABILITIES: capabilities=[ETH66, ETH67, ETH68, SNAP1]
[INFO] CAPABILITY_NEGOTIATION: peerCaps=[eth/65], ourCaps=[ETH66, ETH67, ETH68, SNAP1], negotiated=eth/65
[INFO] PROTOCOL_NEGOTIATED: protocol=eth/65, usesRequestId=false
```

**Explanation**:
- We advertise: ETH66, ETH67, ETH68
- Peer advertises: ETH65
- Common maximum: min(68, 65) = 65
- Result: ETH 65 is correctly selected

**Action**: None needed. This is correct behavior. If you need a higher protocol version, the peer needs to be upgraded.

### Scenario 2: Peer Supports Multiple Versions Including ETH 68

**Symptoms**:
```
[INFO] PEER_CAPABILITIES: clientId=Geth/v1.13.5, capabilities=[eth/66, eth/67, eth/68, snap/1]
[INFO] OUR_CAPABILITIES: capabilities=[ETH66, ETH67, ETH68, SNAP1]
[INFO] CAPABILITY_NEGOTIATION: peerCaps=[eth/66, eth/67, eth/68, snap/1], ourCaps=[ETH66, ETH67, ETH68, SNAP1], negotiated=eth/68
[INFO] PROTOCOL_NEGOTIATED: protocol=eth/68, usesRequestId=true
```

**Explanation**:
- We advertise: ETH66, ETH67, ETH68
- Peer advertises: ETH66, ETH67, ETH68
- Common maximum: min(68, 68) = 68
- Result: ETH 68 is correctly selected

**Action**: None needed. This is optimal.

### Scenario 3: Legacy Peer with Old Protocol

**Symptoms**:
```
[INFO] PEER_CAPABILITIES: clientId=OldClient, capabilities=[eth/63]
[INFO] OUR_CAPABILITIES: capabilities=[ETH66, ETH67, ETH68, SNAP1]
[INFO] CAPABILITY_NEGOTIATION: peerCaps=[eth/63], ourCaps=[ETH66, ETH67, ETH68, SNAP1], negotiated=eth/63
[INFO] PROTOCOL_NEGOTIATED: protocol=eth/63, usesRequestId=false
```

**Explanation**:
- We advertise: ETH66, ETH67, ETH68
- Peer advertises: ETH63
- Common maximum: min(68, 63) = 63
- Result: ETH 63 is correctly selected

**Action**: The peer is very old and should be upgraded if possible.

## Protocol Version Impact

### Features Available per Protocol

| Feature | ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 |
|---------|-------|-------|-------|-------|-------|-------|
| Block sync | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| ForkId support | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Tx pool messages | ❌ | ❌ | ✅ | ✅ | ✅ | ✅ |
| RequestId tracking | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ |
| Enhanced tx announcements | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| GetNodeData/NodeData | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| SNAP sync recommended | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |

**Key Takeaway**: While older protocols work fine for basic block synchronization, newer protocols add better features:
- **ETH66+**: Request/response tracking prevents timeouts and improves reliability
- **ETH67+**: Better transaction propagation with type and size information
- **ETH68+**: Cleaner state sync via SNAP protocol

### RequestId Wrapper

One of the most important differences:

**ETH63-65**: Messages are sent directly
```scala
GetBlockHeaders(block, maxHeaders, skip, reverse)
```

**ETH66-68**: Messages include a RequestId for tracking
```scala
GetBlockHeaders(requestId=123, block, maxHeaders, skip, reverse)
```

This is handled automatically by Fukuii's `PeersClient` based on the negotiated protocol.

## Diagnostic Commands

### Check Negotiated Protocol for All Peers

```bash
grep "PROTOCOL_NEGOTIATED" fukuii.log | tail -20
```

Example output:
```
[INFO] PROTOCOL_NEGOTIATED: clientId=Geth/v1.13.5, protocol=eth/68, usesRequestId=true
[INFO] PROTOCOL_NEGOTIATED: clientId=CoreGeth/v1.12.14, protocol=eth/67, usesRequestId=true
[INFO] PROTOCOL_NEGOTIATED: clientId=OldClient, protocol=eth/65, usesRequestId=false
```

### Check Capability Negotiation Details

```bash
grep "CAPABILITY_NEGOTIATION" fukuii.log | tail -10
```

### Check for Negotiation Failures

```bash
grep "PROTOCOL_NEGOTIATION_FAILED" fukuii.log
```

### View All Peer Capabilities

```bash
grep "PEER_CAPABILITIES" fukuii.log | tail -20
```

## Frequently Asked Questions

### Q: Why is my node selecting ETH 65 instead of ETH 68?

**A**: Because the peer you're connecting to only supports up to ETH 65. This is normal and expected. Protocol negotiation always picks the highest version that both sides support.

### Q: Will ETH 65 prevent my node from syncing?

**A**: No, ETH 65 is perfectly capable of block synchronization. The main difference is that ETH 66+ adds RequestId tracking which improves reliability, but it's not required for basic sync.

### Q: Should I be concerned about ETH 65 being selected?

**A**: Not usually. It just means you're connecting to an older peer. As long as:
- ✅ The peer has the blocks you need
- ✅ The connection is stable
- ✅ Messages are being decoded correctly

Then ETH 65 will work fine.

### Q: How can I force my node to use ETH 68 only?

**A**: You can't (and shouldn't). Protocol negotiation is automatic and ensures maximum compatibility. If you need ETH 68 features specifically, ensure your peers support ETH 68.

### Q: My logs show "negotiated=eth/65" but I thought we only support ETH 66+?

**A**: After the recent fix, we advertise ETH66, ETH67, ETH68 (not ETH65). However, we can still **decode** ETH65 messages for backward compatibility. If a peer advertises ETH65, we'll negotiate ETH65 and use ETH65-compatible message decoders.

This is different from **advertising** the capability. We don't advertise ETH65 but can negotiate to it if that's the only common protocol.

Wait, this needs verification! Let me check the negotiation logic again...

## Update: ETH65 Negotiation Behavior

After reviewing the code, here's the clarification:

**What we advertise**: `ETH66, ETH67, ETH68, SNAP1`

**What we can negotiate to**: `ETH63, ETH64, ETH65, ETH66, ETH67, ETH68`

**How this works**:
1. When a peer advertises `[eth/65]`, negotiation computes min(68, 65) = 65
2. The negotiation logic searches for version 65 in **either our list or the peer's list**
3. It finds version 65 in the peer's list: `ethVersions2.find(_.version == 65)`
4. Even though we don't advertise ETH65, we can **decode** it via `ETH65MessageDecoder`

This is intentional backward compatibility but may be confusing. The key is:
- **Advertise**: What we tell peers we prefer (ETH66+)
- **Negotiate**: What we can actually work with (ETH63-68)

### Clarification Needed

If we want to support ETH65 peers, we should probably advertise it too. Let me check if this is an oversight...

Actually, looking at the negotiation logic more carefully:

```scala
ethVersions1.find(_.version == maxVersion)
  .orElse(ethVersions2.find(_.version == maxVersion))
```

This will:
1. Try to find the version in our advertised list first
2. Fall back to the peer's list if not found

So if the peer advertises ETH65 and we don't, but our max is 68:
- maxVersion = min(68, 65) = 65
- Find 65 in our list: None
- Find 65 in peer's list: Some(ETH65)
- Result: Use peer's ETH65 capability object

This technically works, but there's a subtle issue: we're using the peer's Capability object, not our own. This should be fine since Capability is just a case object, but it's a bit unusual.

For clarity and correctness, **we should also advertise ETH65** if we want to support ETH65 peers properly.

Let me verify this is working as intended...

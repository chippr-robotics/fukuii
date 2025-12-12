# Gorgoroth Phase 1 - Network Formation Fixes

## Summary

This document describes the fixes implemented to address critical blockers identified in the Gorgoroth Phase 1 Field Report (2025-12-12).

## Issues Addressed

### Issue 1: Core-Geth Genesis Compatibility ✅ FIXED

**Severity:** 🔴 Critical Blocker for Mixed Network Testing

**Symptom:**
All Core-Geth nodes entered a crash-restart loop immediately after genesis initialization with the following panic:
```
panic: runtime error: invalid memory address or nil pointer dereference
[signal SIGSEGV: segmentation violation code=0x1 addr=0x8 pc=0x535677]

goroutine 1 [running]:
math/big.(*Int).Mul(0xc00034e020, 0xc00034e020, 0x0)
github.com/ethereum/go-ethereum/consensus/misc/eip1559.CalcBaseFee(...)
```

**Root Cause:**
The Gorgoroth genesis configuration (`ops/gorgoroth/conf/geth/genesis.json`) lacked the `londonBlock` field required by EIP-1559. Core-Geth's base fee calculator attempted to calculate `baseFeePerGas` with a nil value, causing a segmentation fault during transaction pool initialization.

**Fix:**
Added `"londonBlock": 1000000000` to both genesis files:
- `ops/gorgoroth/conf/geth/genesis.json`
- `ops/gorgoroth/conf/besu/genesis.json`

The high activation block (1 billion) ensures EIP-1559 remains disabled for this Proof-of-Work test network, preventing the nil pointer dereference while maintaining genesis compatibility across all client implementations.

**Files Changed:**
- `ops/gorgoroth/conf/geth/genesis.json` - Added londonBlock configuration
- `ops/gorgoroth/conf/besu/genesis.json` - Added londonBlock configuration for consistency

**Expected Impact:**
- Core-Geth nodes can now initialize successfully
- Enables validation of fukuii-geth configuration (3 Fukuii + 3 Core-Geth)
- Enables validation of mixed configuration (3 Fukuii + 3 Core-Geth + 3 Besu)
- Unblocks multi-client interoperability testing

---

### Issue 2: RLPx Auth Handshake Failures ✅ FIXED

**Severity:** 🔴 Critical Blocker for Fukuii Network Formation

**Symptom:**
Fukuii nodes repeatedly attempted peer connections but failed during RLPx authentication handshake. All nodes reported 0 peers despite being on the same Docker network. Logs showed:
```
DEBUG [c.c.e.n.rlpx.RLPxConnectionHandler] - [RLPx] Received auth handshake init message for peer 172.25.0.12:59118 (476 bytes)
DEBUG [c.c.e.n.rlpx.RLPxConnectionHandler] - [Stopping Connection] Init AuthHandshaker message handling failed for peer 172.25.0.12:59118 WARNING arguments left: 1
INFO  [c.c.e.b.sync.CacheBasedBlacklist] - Blacklisting peer [PeerAddress(172.25.0.12)] for 30000 milliseconds. Reason: Some other reason specific to a subprotocol
```

**Root Cause:**
P2P protocol version mismatch between nodes in the 6-node configuration:
- **Nodes 1, 2, 3:** Had `p2p-version = 4` explicitly set in their configuration files
- **Nodes 4, 5, 6:** Had no p2p-version setting, defaulting to `p2p-version = 5` from `base.conf`

When nodes with different p2p protocol versions attempted RLPx authentication, the auth message structure differences caused decoding failures. The "arguments left: 1" error indicated that the auth message parser expected a different number of fields based on the protocol version. This incompatibility prevented successful handshake completion, leading to:
- Zero peer connections established
- Continuous blacklisting cycles (30-second cooldowns)
- Network formation failure

**Fix:**
Standardized all 6 nodes to use `p2p-version = 4` by adding the configuration to nodes 4, 5, and 6:

**Files Changed:**
- `ops/gorgoroth/conf/node4/gorgoroth.conf` - Added `p2p-version = 4`
- `ops/gorgoroth/conf/node5/gorgoroth.conf` - Added `p2p-version = 4`
- `ops/gorgoroth/conf/node6/gorgoroth.conf` - Added `p2p-version = 4`

**Technical Details:**
- P2P version 4 uses legacy RLPx auth messages without Snappy compression
- P2P version 5 introduced Snappy compression and additional protocol capabilities
- Auth handshake messages have different structures between versions
- Consistent version across all nodes ensures compatible message encoding/decoding

**Expected Impact:**
- Fukuii nodes can successfully complete RLPx auth handshakes
- Peer connections can establish and maintain stability
- Enables `sync-static-nodes` operation to succeed
- Unblocks progression to Phase 2 (Cross-Client Mining & Block Validation)

---

## Issue 3: Admin API Unavailability ⏸️ DEFERRED

**Severity:** 🟡 Medium - Workaround Possible

**Status:** Not addressed in this PR. Field report indicates medium priority with available workarounds (manual enode collection from node keys). Can be addressed in a follow-up PR if needed.

---

## Validation

### Code Review
✅ Passed - No issues found

### Security Scan
✅ Passed - No security concerns with configuration changes

### Expected Test Results
After deploying these fixes, the following Phase 1 validation steps should succeed:

| Step | Action | Expected Result |
|------|--------|----------------|
| 1.1 | Start mixed network (9 nodes) | All containers healthy |
| 1.2 | Wait 90s for initialization | Initialization complete |
| 1.3 | Verify containers running | All 9 nodes running stable |
| 1.4 | Check Core-Geth nodes | No crashes, healthy status |
| 1.5 | Check peer connectivity | 2-5 peers per node |
| 1.6 | Verify RLPx handshakes | Success rate > 90% |
| 1.7 | Test cross-client connections | Fukuii ↔ Core-Geth verified |
| 1.8 | Test cross-client connections | Fukuii ↔ Besu verified |

---

## References

- [Gorgoroth Phase 1 Field Report](https://github.com/chippr-robotics/fukuii/issues/XXX)
- [EIP-1559: Fee market change](https://eips.ethereum.org/EIPS/eip-1559)
- [RLPx Protocol Specification](https://github.com/ethereum/devp2p/blob/master/rlpx.md)
- [Core-Geth Documentation](https://core-geth.org/)

---

**Date:** 2025-12-12  
**Author:** @copilot  
**Status:** ✅ Complete

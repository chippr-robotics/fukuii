# ETC64 Configuration Cleanup

**Date:** 2025-12-11  
**Issue:** Investigate why the handshake is negotiating ETC64 instead of ETH64  
**Status:** ✅ RESOLVED - Configuration files updated

## Investigation Summary

### Root Cause

The investigation revealed that two chain configuration files contained legacy `"etc/64"` capability references:

1. `ops/barad-dur/fukuii-conf/chains/pottery-chain.conf` - line 5
2. `ops/barad-dur/fukuii-conf/chains/testnet-internal-nomad-chain.conf` - line 6

### Critical Finding

**The capabilities field in these configuration files is NOT used by the node software.**

The actual capabilities advertised during the handshake are hardcoded in `src/main/scala/com/chipprbots/ethereum/utils/Config.scala` at line 58:

```scala
val supportedCapabilities: List[Capability] = List(Capability.ETH68, Capability.SNAP1)
```

### Code Analysis

1. **Configuration Loading** (`Config.scala` line 60):
   ```scala
   val blockchains: BlockchainsConfig = BlockchainsConfig(config.getConfig("blockchains"))
   ```

2. **BlockchainConfig Parsing** (`BlockchainConfig.scala` line 118-250):
   - The `fromRawConfig` method loads various blockchain parameters
   - **It does NOT load or use the `capabilities` field**
   - Only network-id, fork blocks, genesis, bootstrap nodes, etc. are loaded

3. **Handshake Process** (`EtcHelloExchangeState.scala` line 90):
   ```scala
   Hello(
     p2pVersion = EtcHelloExchangeState.P2pVersion,
     clientId = Config.clientId,
     capabilities = Config.supportedCapabilities,  // Uses hardcoded value
     listenPort = listenPort,
     nodeId = ByteString(nodeStatus.nodeId)
   )
   ```

4. **Capability Negotiation** (`Capability.scala` line 50-86):
   - Negotiates between peer capabilities and local capabilities
   - Local capabilities always come from `Config.supportedCapabilities`
   - Supports ETH63-68 and SNAP1
   - **No ETC64 capability exists in the codebase**

### Why This Matters

While the `capabilities` field in these config files was not being used at runtime, having `"etc/64"` references:

1. **Confused developers and users** - Made it appear that ETC64 protocol was still supported
2. **Contradicted documentation** - The ETC64_REMOVAL_VALIDATION.md explicitly states ETC64 was removed
3. **Could cause future issues** - If code were ever added to parse these values
4. **Misled troubleshooting** - The problem statement incorrectly suggested handshake was negotiating ETC64

### Actual Handshake Behavior

The handshake process works as follows:

1. Node advertises: `List(Capability.ETH68, Capability.SNAP1)`
2. Peer advertises: Their supported capabilities (e.g., `ETH64`, `ETH65`, etc.)
3. Negotiation selects highest common version per protocol family
4. Result: Usually ETH64, ETH65, ETH66, ETH67, or ETH68 depending on peer support
5. Status exchange uses appropriate message format (ETH64.Status for ETH64+, BaseETH6XMessages.Status for ETH63)

**There is no ETC64 protocol negotiation occurring.**

## Changes Made

Updated both configuration files to:

1. Replace `capabilities = ["etc/64"]` with proper ETH protocol list
2. Add documentation comments explaining the field is not used
3. Reference the actual source of truth: `Config.supportedCapabilities`

### Before:
```conf
capabilities = ["etc/64"]
```

### After:
```conf
# Note: capabilities field is not used by the node software.
# Actual capabilities are determined by Config.supportedCapabilities in Config.scala
# which is currently set to List(Capability.ETH68, Capability.SNAP1)
# This field is preserved for documentation purposes only.
capabilities = ["eth/63", "eth/64", "eth/65", "eth/66", "eth/67", "eth/68", "snap/1"]
```

## Validation

### Configuration Verification

```bash
# Verify no more etc/64 references in config files
grep -r "etc/64" ops/barad-dur/fukuii-conf/chains/ --include="*.conf"
# Result: No matches (✓)

# Verify no active ETC64 code references
grep -r "ETC64" src/main/scala/ --include="*.scala" | grep -v "test\|spec\|docs\|Legacy\|Historical"
# Result: No matches (✓)
```

### Handshake Validation

The handshake process can be validated by:

1. Starting nodes with DEBUG logging enabled
2. Checking logs for capability negotiation:
   - Look for: `Negotiated protocol version with client ... is eth/64` (or ETH65, ETH66, etc.)
   - Should NOT see: `etc/64` anywhere in logs
3. Verify status exchange uses correct message format based on negotiated capability

### Related Documentation

- `docs/validation/ETC64_REMOVAL_VALIDATION.md` - Original validation report
- `docs/validation/P2P_COMMUNICATION_VALIDATION_GUIDE.md` - P2P testing guide
- `docs/validation/EXECUTIVE_SUMMARY.md` - Executive summary of ETC64 removal
- `docs/adr/consensus/CON-007-etc64-rlp-encoding-fix.md` - Archived ADR

## Recommendations

### For Node Operators

1. **No action required** - These configuration file changes do not affect runtime behavior
2. The node will continue to negotiate ETH64-68 protocols as before
3. Discovery and capability whitelisting work correctly

### For Developers

1. **Configuration fields in chain configs are documentation only** unless explicitly loaded in `BlockchainConfig.fromRawConfig`
2. To change advertised capabilities, modify `Config.supportedCapabilities` in `Config.scala`
3. The DevP2P spec recommends advertising only the highest version of each protocol family

### Future Considerations

1. Consider removing unused fields from configuration files entirely
2. Add validation to warn if config files contain unknown/unused fields
3. Document which config fields are actually used vs. documentation-only

## Conclusion

**The handshake was NEVER negotiating ETC64.** The configuration file references were:
- Legacy documentation artifacts
- Not used by the code
- Misleading to developers

All nodes correctly advertise `ETH68` and `SNAP1` capabilities, then negotiate down to the highest common version supported by both peers (typically ETH64-68). The ETC64 protocol was successfully removed from the codebase as documented in previous validation reports.

This cleanup ensures configuration files match the actual runtime behavior and removes confusion about protocol support.

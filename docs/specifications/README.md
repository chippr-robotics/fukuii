# Technical Specifications

This directory contains technical specifications and protocol documentation for Fukuii.

## Contents

### Encoding Specifications
- **[RLP Integer Encoding Specification](RLP_INTEGER_ENCODING_SPEC.md)** - Recursive Length Prefix integer encoding specification

### EVM Compatibility
- **[Ethereum Mainnet EVM Compatibility](ETHEREUM_MAINNET_EVM_COMPATIBILITY.md)** - Comprehensive analysis of EIPs, VM opcodes, and protocol features required for full Ethereum mainnet execution client compatibility

### Protocol Upgrades

#### ECIP-1111: Olympia Hardfork (In Research)
- **[ECIP-1121 Olympia Analysis](ECIP-1121-OLYMPIA-ANALYSIS.md)** - Comprehensive technical analysis of Olympia hardfork requirements, including EIP-1559 and EIP-3198 implementation with BASEFEE treasury redirection
- **[ECIP-1121 Implementation Checklist](ECIP-1121-IMPLEMENTATION-CHECKLIST.md)** - Detailed tracking checklist with 245+ work items for Olympia implementation

**Status:** Research phase complete  
**Key Features:** EIP-1559 (dynamic basefee), EIP-3198 (BASEFEE opcode), Treasury integration  
**Estimated Effort:** 12-14 weeks  
**Blockers:** Treasury address publication, activation blocks TBD

## Related Documentation

- [ADRs](../adr/README.md) - Architecture Decision Records
- [VM ADRs](../adr/vm/README.md) - EVM-specific implementation decisions
- [Consensus ADRs](../adr/consensus/README.md) - Consensus and protocol decisions

## See Also

For Ethereum Improvement Proposal (EIP) implementations, see:
- [VM ADRs](../adr/vm/README.md) - EIP implementation decisions
- [Consensus ADRs](../adr/consensus/README.md) - Protocol-level specifications

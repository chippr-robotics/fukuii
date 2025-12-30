# Technical Specifications

This directory contains technical specifications and protocol documentation for Fukuii.

## Contents

### Encoding Specifications
- **[RLP Integer Encoding Specification](RLP_INTEGER_ENCODING_SPEC.md)** - Recursive Length Prefix integer encoding specification

### EVM Compatibility
- **[Ethereum Mainnet EVM Compatibility](ETHEREUM_MAINNET_EVM_COMPATIBILITY.md)** - Comprehensive analysis of EIPs, VM opcodes, and protocol features required for full Ethereum mainnet execution client compatibility

### Protocol Upgrades

#### ECIP-1121: Execution Client Specification Alignment (In Research)
- **[ECIP-1121 Execution Client Analysis](ECIP-1121-EXECUTION-CLIENT-ANALYSIS.md)** - Comprehensive analysis of execution-layer specifications from Ethereum's Fusaka, Pectra, and Dencun forks applicable to ETC
- **[ECIP-1121 Implementation Checklist](ECIP-1121-IMPLEMENTATION-CHECKLIST.md)** - Detailed tracking checklist with 267+ work items for 13 EIPs covering gas accounting, EVM safety, cryptography, and optimizations

**Status:** Research phase complete, awaiting ECIP-1121 finalization (PR #554)  
**Scope:** 13 execution-layer EIPs (EIP-7702, 7623, 7825, 7883, 7935, 7934, 6780, 7642, 7910, 2537, 7951, 5656, 2935, 1153)  
**Note:** Excludes EIP-1559/EIP-3198 (handled in separate ECIP-1111)  
**Estimated Effort:** 18-28 weeks with parallelization  
**Blockers:** ECIP-1121 PR merge, activation blocks TBD, cryptography library selection

## Related Documentation

- [ADRs](../adr/README.md) - Architecture Decision Records
- [VM ADRs](../adr/vm/README.md) - EVM-specific implementation decisions
- [Consensus ADRs](../adr/consensus/README.md) - Consensus and protocol decisions

## See Also

For Ethereum Improvement Proposal (EIP) implementations, see:
- [VM ADRs](../adr/vm/README.md) - EIP implementation decisions
- [Consensus ADRs](../adr/consensus/README.md) - Protocol-level specifications

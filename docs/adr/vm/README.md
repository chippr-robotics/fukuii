# VM (EVM) ADRs

This directory contains Architecture Decision Records related to the Ethereum Virtual Machine (EVM), EIP implementations, and VM-specific features.

## Naming Convention

VM ADRs use the format: `VM-NNN-title.md` where NNN is a zero-padded sequential number.

Examples:
- `VM-001-eip-3541-implementation.md`
- `VM-002-eip-3529-implementation.md`

## Current ADRs

- [VM-001: EIP-3541 Implementation](VM-001-eip-3541-implementation.md) - Accepted
- [VM-002: EIP-3529 Implementation](VM-002-eip-3529-implementation.md) - Accepted
- [VM-003: EIP-3651 Implementation](VM-003-eip-3651-implementation.md) - Accepted
- [VM-004: EIP-3855 Implementation](VM-004-eip-3855-implementation.md) - Accepted
- [VM-005: EIP-3860 Implementation](VM-005-eip-3860-implementation.md) - Accepted
- [VM-006: EIP-6049 Implementation](VM-006-eip-6049-implementation.md) - Accepted
- [VM-007: EIP-161 noEmptyAccounts Configuration Fix](VM-007-eip-161-noemptyaccounts-fix.md) - Accepted

## Related Specifications

- **[Ethereum Mainnet EVM Compatibility](../specifications/ETHEREUM_MAINNET_EVM_COMPATIBILITY.md)** - Comprehensive analysis of EIPs and VM opcodes required for full Ethereum mainnet execution client compatibility

## Creating a New VM ADR

1. Use the next sequential number (e.g., `VM-008-title.md`)
2. Follow the standard ADR template structure
3. Link it in the index above
4. Update the main ADR README

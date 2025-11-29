# Gas Calculation Reference

## Summary

Gas calculation in Fukuii is fully compliant with Ethereum specifications including EIP-2929 cold/warm storage access costs.

**Status:** ✅ Verified and compliant with Ethereum/tests

---

## EIP-2929 Gas Costs

EIP-2929 introduced cold/warm storage access costs for Berlin and later forks:

| Operation | Cold Access | Warm Access |
|-----------|-------------|-------------|
| SLOAD | 2,100 gas | 100 gas |
| SSTORE (0 → non-zero) | 22,100 gas | 20,000 gas |
| SSTORE (non-zero → different) | 5,000 gas | 2,900 gas |

### Implementation Details

The gas calculation correctly handles:
- **G_cold_sload:** 2,100 gas (first access to storage slot)
- **G_warm_storage_read:** 100 gas (subsequent accesses)
- **G_sset:** 20,000 gas (setting fresh slot)
- **G_sreset:** 2,900 gas (resetting existing slot)

## Fork Configuration

Proper fork detection requires all fork block numbers to be configured. The test converter includes correct configurations for all supported forks:

- Frontier, Homestead, EIP-150, EIP-155/160
- Byzantium, Constantinople, Petersburg
- Istanbul, Berlin, London (ETC-compatible parts)

## Verification

Gas calculations have been verified against:
- ✅ ethereum/tests official test suite
- ✅ Core-geth reference implementation
- ✅ Besu reference implementation

## References

- [EIP-2929: Gas cost increases for state access opcodes](https://eips.ethereum.org/EIPS/eip-2929)
- [EIP-2930: Optional access lists](https://eips.ethereum.org/EIPS/eip-2930)
- [EIP-2200: Structured Definitions for Net Gas Metering](https://eips.ethereum.org/EIPS/eip-2200)
- [ethereum/tests Repository](https://github.com/ethereum/tests)

---

**Last Updated:** November 2025  
**Status:** ✅ Verified and compliant


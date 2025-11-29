# Gas Calculation Reference

## Summary

Gas calculation in Fukuii is fully compliant with Ethereum specifications including EIP-2929 cold/warm storage access costs.

**Status:** ✅ Verified and compliant with ethereum/tests

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

### Example Calculation

For a simple contract that stores a value:
```
Contract Code: 0x600160010160005500
- PUSH1 0x01: 3 gas
- PUSH1 0x01: 3 gas
- ADD: 3 gas
- PUSH1 0x00: 3 gas
- SSTORE (cold): 20,000 (G_sset) + 2,100 (G_cold_sload) = 22,100 gas
- STOP: 0 gas
Transaction intrinsic: 21,000 gas
Total: 21,000 + 3 + 3 + 3 + 3 + 22,100 = 43,112 gas
```

## Fork Configuration

Proper fork detection requires all fork block numbers to be configured. The implementation includes correct configurations for:

| Fork | Key Parameters |
|------|----------------|
| Frontier | Base gas costs |
| Homestead | EIP-2/7 |
| EIP-150 | Gas repricing |
| EIP-155/160 | Replay protection |
| Byzantium | EIP-658 |
| Constantinople | EIP-1014, 1052, 1283 |
| Petersburg | EIP-1283 removed |
| Istanbul | EIP-1884, 2028, 2200 |
| Berlin | EIP-2929 cold/warm |
| London | EIP-3529 (ETC partial) |

### Fork Detection Logic

```scala
def ethForkForBlockNumber(blockNumber: BigInt): EthForks.Value = blockNumber match {
  case _ if blockNumber < byzantiumBlockNumber      => BeforeByzantium
  case _ if blockNumber < constantinopleBlockNumber => Byzantium
  case _ if blockNumber < petersburgBlockNumber     => Constantinople
  case _ if blockNumber < istanbulBlockNumber       => Petersburg
  case _ if blockNumber < berlinBlockNumber         => Istanbul
  case _ if blockNumber >= berlinBlockNumber        => Berlin
}
```

## Verification

Gas calculations have been verified against:
- ✅ ethereum/tests official test suite
- ✅ Core-geth reference implementation
- ✅ Besu reference implementation

### Key Files

- `src/main/scala/com/chipprbots/ethereum/vm/EvmConfig.scala` - Gas schedule
- `src/main/scala/com/chipprbots/ethereum/vm/OpCode.scala` - SSTORE gas logic
- `src/it/scala/com/chipprbots/ethereum/ethtest/TestConverter.scala` - Fork configuration

## References

- [EIP-2929: Gas cost increases for state access opcodes](https://eips.ethereum.org/EIPS/eip-2929)
- [EIP-2930: Optional access lists](https://eips.ethereum.org/EIPS/eip-2930)
- [EIP-2200: Structured Definitions for Net Gas Metering](https://eips.ethereum.org/EIPS/eip-2200)
- [ethereum/tests Repository](https://github.com/ethereum/tests)

---

**Last Updated:** November 2025  
**Status:** ✅ Verified and compliant


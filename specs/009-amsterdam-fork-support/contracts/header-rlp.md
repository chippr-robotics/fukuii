# Contract: Amsterdam block header RLP

**Feature**: [../spec.md](../spec.md) · **Evidence**: [../research.md](../research.md)

## Field ordering — measured, not assumed

Amsterdam headers carry **23** RLP items. The ordering below was established by re-encoding block 600
of the reference fixture from its decoded parts and keccak-hashing the result, which reproduced
`headblock.json.hash` byte-identically. Items 21 and 22 are the two new ones.

| # | Field | Type | Introduced |
|---|---|---|---|
| 0 | `parentHash` | 32 bytes | Frontier |
| 1 | `ommersHash` | 32 bytes | Frontier |
| 2 | `beneficiary` | 20 bytes | Frontier |
| 3 | `stateRoot` | 32 bytes | Frontier |
| 4 | `transactionsRoot` | 32 bytes | Frontier |
| 5 | `receiptsRoot` | 32 bytes | Frontier |
| 6 | `logsBloom` | 256 bytes | Frontier |
| 7 | `difficulty` | big-int | Frontier |
| 8 | `number` | big-int | Frontier |
| 9 | `gasLimit` | big-int | Frontier |
| 10 | `gasUsed` | big-int | Frontier |
| 11 | `unixTimestamp` | big-int | Frontier |
| 12 | `extraData` | bytes | Frontier |
| 13 | `mixHash` | 32 bytes | Frontier |
| 14 | `nonce` | 8 bytes | Frontier |
| 15 | `baseFeePerGas` | big-int | London |
| 16 | `withdrawalsRoot` | 32 bytes | Shanghai |
| 17 | `blobGasUsed` | big-int | Cancun |
| 18 | `excessBlobGas` | big-int | Cancun |
| 19 | `parentBeaconBlockRoot` | 32 bytes | Cancun |
| 20 | `requestsHash` | 32 bytes | Prague |
| **21** | **`blockAccessListHash`** | **32 bytes** | **Amsterdam (EIP-7928)** |
| **22** | **`slotNumber`** | **big-int** | **Amsterdam (EIP-7843)** |

Big-int fields encode as minimal unsigned big-endian, consistent with the existing
`ByteUtils.bigIntToUnsignedByteArray` treatment of `baseFee`, `blobGasUsed` and `excessBlobGas`.

## Arity contract

Decoding maps item count to header shape **exactly**. There is no tolerant fallback.

| Items | Shape |
|---|---|
| 15 | `HefEmpty` |
| 16 | `HefPostOlympia` |
| 17 | `HefPostShanghai` |
| 20 | `HefPostCancun` |
| 21 | `HefPostPrague` |
| 23 | `HefPostAmsterdam` |
| anything else | **reject**, with the count named in the message |

18, 19 and 22 are already rejected today; the only tolerant case is `n if n >= 21`, and it is the one
that produces wrong hashes. Replacing it with `case 21` and `case 23` makes the table uniform.

**Round-trip invariant, for every shape in the table**: `encode(decode(bytes)) == bytes`, and therefore
`hash(decode(bytes)) == hash(bytes)`. This is FR-014 and it is what the current code violates — not
because decoding throws, but because it succeeds with the wrong answer.

## Why rejecting beats tolerating

The `>= 21` catch-all reads as forward-tolerance: accept headers from forks we do not know yet. In a
consensus client that intent inverts. A header we cannot represent is not a header we can hash, and a
wrong hash propagates into parent linkage, `BLOCKHASH`, and every wire response that quotes it. The
measured divergence is canonical `6372c88fef519c6e4bebbe02d8447c084fc8cd759fae25c5b5cc3c6e2be99bfe`
versus truncated `94844dfd59c36d3edcebc229d2d89d659c471fa71057a8368a45760029c2e070`.

Refusing the block is the loud failure the project's "no silent fallbacks that turn hard failures into
quiet corruption" rule asks for. This holds independently of how much of Amsterdam ships — which is why
it is slice A and lands on its own.

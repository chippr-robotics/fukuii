# Contract: Change-Record Schema v1 (RLP)

**Spec**: [../spec.md](../spec.md) DC-1, DC-2, FR-002, FR-008, FR-011, FR-012 | **Shared with**: spec 018 F2
`data-model.md` (exportable part of the changeset; GT-1) | **Vectors**: [golden-vectors.md](golden-vectors.md)

This document is the byte-level contract. A producer on the Hash scheme, the Path scheme or the 018 unified layout
MUST produce identical bytes for the same block (DC-2). A consumer MUST stop on an unknown version byte (FR-011).

Notation: `RLP(x)` is Ethereum RLP (yellow paper appendix B). `uint` is an unsigned integer encoded as the shortest
big-endian byte string with no leading zero (zero is the empty string). `b32` is a 32-byte string. `bytes` is a byte
string of any length. `[]` is an RLP list. Lists are ordered exactly as written.

---

## 1. Framing

```
record := versionByte ++ RLP(envelope)
versionByte := 0x01
```

The version byte is the first byte of the message value and is **not** part of the RLP. Everything after it is a
single RLP list. There is no trailing data.

## 2. Envelope (all record types)

```
envelope := [ type, chainKey, chainId, blockNumber, blockHash, parentHash, body ]

type        := uint   -- 0 block | 1 retraction | 2 finality | 3 witness (reserved)
chainKey    := bytes  -- UTF-8, the 024 chainKey ("etc", "mordor", "sepolia", "plataberget", ...)
chainId     := uint   -- may exceed 2^32 (Platåberget 7091047534)
blockNumber := uint
blockHash   := b32
parentHash  := b32
body        := []     -- per type, §3–§6
```

Identity of a record is `(chainKey, type, blockNumber, blockHash)`. Two records with the same identity carry the same
`body`; a consumer treats the second as a no-op (DC-3).

## 3. Block record (`type = 0`)

```
body     := [ header, receipts, accounts, code ]

header   := RLP(BlockHeader)       -- exactly the bytes fukuii hashes to obtain blockHash
                                   -- (domain/BlockHeader.scala BlockHeaderEnc.toRLPEncodable);
                                   -- keccak(header) == blockHash
receipts := [ receipt* ]           -- one per transaction, in transaction order
receipt  := legacyReceipt | typedReceipt
legacyReceipt := [ postStateOrStatus, cumulativeGasUsed, logsBloom, logs ]
typedReceipt  := bytes             -- txType byte ++ RLP(legacyReceipt), as on the eth/68 wire
                                   -- (blockchain/sync/codec/ReceiptCodecs.scala)
postStateOrStatus := b32 | uint    -- pre-Byzantium root, or 0/1 status (EIP-658)
logsBloom := bytes(256)
logs      := [ log* ] ; log := [ address20, [ topic32* ], data ]

accounts := [ accountEntry* ]      -- sorted by addressHash ascending (bytewise)
accountEntry := [ addressHash, address, prevAccount, newAccount, slots ]
addressHash  := b32                -- keccak256(address)
address      := bytes(20)
prevAccount  := account | ""       -- "" (empty string) = absent before the block
newAccount   := account | ""       -- "" = absent/deleted after the block
account      := [ nonce, balance, storageRoot, codeHash ]   -- domain/Account.scala AccountEnc; storageRoot and
                                                            -- codeHash are always b32 (empty-trie / empty-code hashes
                                                            -- for the empty case, never "")
slots        := [ slotEntry* ]     -- sorted by slotHash ascending
slotEntry    := [ slotHash, slotKey, prevValue, newValue ]
slotHash     := b32                -- keccak256(slotKey)
slotKey      := bytes(32)          -- the storage index, left-padded to 32 bytes
prevValue    := uint               -- "" = zero / absent
newValue     := uint               -- "" = zero / deleted

code      := [ codeEntry* ]        -- sorted by codeHash ascending
codeEntry := [ codeHash, codeBytes ]
codeHash  := b32                   -- keccak256(codeBytes)
codeBytes := bytes
```

Rules:

1. **Netting.** An account or slot whose previous and new values are equal MUST NOT appear. An account entry with no
   account change and no slot change MUST NOT appear.
2. **Apply keys.** `addressHash` and `slotHash` are the keys a follower writes (FR-008). The flat storage key is
   `addressHash ++ slotHash` (64 bytes), never repeated in the record. The preimages `address` and `slotKey` MUST be
   present and MUST hash to the keys beside them; a consumer MAY verify.
3. **Destroyed accounts.** When `newAccount` is `""` and `prevAccount.storageRoot` is not the empty-trie root, `slots`
   MUST list every slot of the previous storage with `newValue = ""`.
4. **Code dependency.** Every `newAccount.codeHash` that is not the empty-code hash MUST either appear in `code` or
   already be known to the consumer from an earlier record or its seed. A producer that cannot know the consumer's
   seed includes it whenever the hash first appears in `accountCodes` for the block (new deployments); redeployment of
   an existing blob is permitted and harmless.
5. **Completeness.** From the consumer's pre-state, applying `accounts` and `code` forward yields a state whose root is
   `header.stateRoot`; applying them backward yields the pre-state (DC-2). The receipts root computed from `receipts`
   equals `header.receiptsRoot`.
6. **No layout data.** No trie node, node hash, path, column-family name or reference count appears anywhere.
7. **Empty block.** A block with no state change still produces a block record with empty `accounts` and `code`.

## 4. Retraction record (`type = 1`)

```
body := []
```

`blockNumber`/`blockHash`/`parentHash` identify the block that is no longer canonical. For one reorganisation,
retractions are emitted newest-first and precede every replacement block record (FR-007). A retraction for a block at
or below the current finality watermark is a producer fault; fukuii never emits one (it halts instead).

## 5. Finality record (`type = 2`)

```
body := []
```

`blockNumber`/`blockHash` are the finalized block; `parentHash` its parent. ETC: one record for block 0; the watermark
never advances (D-6). ETH family: one record each time the applied `engine_forkchoiceUpdated`'s `finalizedBlockHash`
changes. A consumer MUST treat every block at or below the watermark as irrevocable.

## 6. Witness record (`type = 3`, reserved)

```
body          := [ witnessFormat, witnessBytes ]
witnessFormat := uint    -- 0 = spec 017 ExecutionWitness RLP; other values reserved
witnessBytes  := bytes
```

Produced only when enabled by the operator (FR-012). Consumers MUST tolerate its absence and MAY ignore it.

## 7. Ordering across records on one topic

Per `chainKey` the sequence is totally ordered. Within it:

- block records are strictly increasing in `blockNumber` except immediately after a run of retractions, where the
  next block record's `blockNumber` equals the lowest retracted `blockNumber`;
- a run of retractions is strictly decreasing in `blockNumber` and its lowest member's `parentHash` names the block
  the replacement chain builds on (the fork point is canonical and unretracted);
- finality records may appear anywhere and never decrease in `blockNumber`;
- the first record after the position on a fresh start is a finality record (ETC: genesis) or the first block record
  (ETH: finality follows with the first applied FCU).

A gap in `blockNumber` that is not explained by retractions is a fault (DC-4); the consumer stops.

## 8. Versioning

`0x01` is frozen by GT-1 together with the golden vectors. Any change to item order, a type's body or the netting rules
is a new version byte. Adding a record type reuses `0x01` only if the new type's envelope is unchanged and consumers
can skip unknown `type` values — they MUST NOT; so a new type is also a new version. 018 F2's local trie-node undo
records are outside this contract and carry no version byte of this scheme.

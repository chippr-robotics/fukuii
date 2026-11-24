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
                      -- 4 poolAdd | 5 poolRemove | 6 safe | 7 chainStart (amendment 2026-11-24)
chainKey    := bytes  -- UTF-8, the 024 chainKey ("etc", "mordor", "sepolia", "plataberget", ...)
chainId     := uint   -- may exceed 2^32 (Platåberget 7091047534)
blockNumber := uint
blockHash   := b32
parentHash  := b32
body        := []     -- per type, §3–§6
```

Identity of a record is `(chainKey, type, blockNumber, blockHash)`. Two records with the same identity carry the same
`body`; a consumer treats the second as a no-op (DC-3). Pool records (§6a) are the exception: their identity is
`(chainKey, nodeId, session, seq)`, and their `blockNumber`/`blockHash`/`parentHash` name the node's canonical head
when the pool event happened (context only).

## 3. Block record (`type = 0`)

```
body     := [ header, transactions, uncles, withdrawals, receipts, accounts, code ]

header   := RLP(BlockHeader)       -- exactly the bytes fukuii hashes to obtain blockHash
                                   -- (domain/BlockHeader.scala BlockHeaderEnc.toRLPEncodable);
                                   -- keccak(header) == blockHash
transactions := [ tx* ]           -- block order; each tx is the exact item the node stores in a block body
                                   -- transaction list: a legacy transaction as its RLP list, a typed transaction
                                   -- (EIP-2718) as one byte string txType ++ RLP(payload), as in the eth/68 block
                                   -- body (network/p2p/messages/ETHPackets.scala:459-467 `txListItem`;
                                   -- domain/BlockBody.scala:68-89 `blockBodyToRlpEncodable`)
uncles       := [ RLP(BlockHeader)* ]  -- ommer headers, same bytes as `header`; ETC family only, always [] on the
                                   -- ETH family post-merge (domain/BlockBody.scala:15 `uncleNodesList`)
withdrawals  := [ withdrawal* ]    -- EIP-4895; [] when the fork has no withdrawals, including the whole ETC family
withdrawal   := [ index, validatorIndex, address20, amount ]   -- domain/Withdrawal.scala:27-34 `WithdrawalEnc`
                                   -- (all uint; amount in Gwei); domain/BlockBody.scala:16 `withdrawals`
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
5. **Body completeness.** The body items are verifiable against the header without any other input:
   `keccak(trie(transactions))` equals `header.transactionsRoot` (trie keyed by `RLP(index)`, values the tx items
   above); `keccak(RLP(uncles))` equals `header.ommersHash`; when `header.withdrawalsRoot` is present, the root of the
   withdrawals trie equals it, and when it is absent `withdrawals` MUST be `[]`. A producer that cannot satisfy this
   for a block it has executed has a bug and halts export (it never emits a partial body).
6. **State completeness.** From the consumer's pre-state, applying `accounts` and `code` forward yields a state whose
   root is `header.stateRoot`; applying them backward yields the pre-state (DC-2). The receipts root computed from
   `receipts` equals `header.receiptsRoot`.
7. **No layout data.** No trie node, node hash, path, column-family name or reference count appears anywhere.
8. **Empty block.** A block with no state change still produces a block record with empty `accounts` and `code`; a
   block with no transactions, uncles or withdrawals carries `[]` for each.

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

### 5.1 Safe record (`type = 6`, amendment 2026-11-24)

```
body := []
```

`blockNumber`/`blockHash` are the applied `engine_forkchoiceUpdated`'s `safeBlockHash`; `parentHash` its parent.
ETH family only: one record each time the safe hash changes; the ETC family never emits one. Advisory (spec DC-5): a
consumer MAY treat blocks at or below it as unlikely to be retracted and MUST NOT treat them as irrevocable; only §5
makes a block irrevocable. A safe record never names a block below the current finality watermark.

### 5.2 Chain-start record (`type = 7`, amendment 2026-11-24)

```
body      := [ iteration ]
iteration := uint   -- the chain's iteration number where it has them (Ephemery: N of release ephemery-N,
                    -- chainId = 39438000 + N); 0 for a chain without iterations
```

`blockNumber` is 0, `blockHash` the genesis hash, `parentHash` 32 zero bytes, `chainId` the instance's chain ID.
Emitted once per datadir, as the first record the exporter ever publishes from it (spec DC-11, FR-017). A consumer
that sees a `chainStart` whose `chainId` or `blockHash` differs from the previous one on the topic closes the earlier
chain instance; one that repeats the previous one (a restart that re-sends from its position) is a no-op (DC-3).

## 6. Witness record (`type = 3`, reserved)

```
body          := [ witnessFormat, witnessBytes ]
witnessFormat := uint    -- 0 = spec 017 ExecutionWitness RLP; other values reserved
witnessBytes  := bytes
```

Produced only when enabled by the operator (FR-012). Consumers MUST tolerate its absence and MAY ignore it.

## 6a. Pool records (`type = 4, 5`, amendment 2026-11-24; pool topic only)

Published on `fukuii.<chainKey>.txpool.v1` (`topic.md` §8), never on the record topic. Best-effort (spec DC-10).

```
poolAdd body    := [ nodeId, session, seq, observedAt, txHash, sender, tx, source ]
poolRemove body := [ nodeId, session, seq, observedAt, txHash, sender, nonce, reason, replacedBy ]

nodeId     := bytes   -- the publishing node (default: first 8 bytes of its node ID; spec OQ-9)
session    := uint    -- the node process's start time, milliseconds since the Unix epoch
seq        := uint    -- 0, 1, 2, ... per (nodeId, session), across both pool types; contiguous unless records were
                      -- dropped (spec FR-016)
observedAt := uint    -- node wall clock at the pool event, milliseconds since the Unix epoch
txHash     := b32
sender     := bytes(20)   -- recovered sender
tx         := the transaction item exactly as in §3 `transactions` (legacy RLP list, or typed txType ++ RLP(payload)
                      -- as one byte string); a blob transaction in its block-body form, without sidecar
source     := uint    -- 0 peer (gossip or a requested pooled transaction) | 1 local (RPC submission)
                      -- | 2 reorg (back from a retracted block)
nonce      := uint
reason     := uint    -- 0 included | 1 replaced | 2 evicted | 3 expired | 4 dropped
replacedBy := b32 | ""    -- the replacing transaction's hash when reason = 1; "" otherwise
```

Rules:

1. **Full fields.** `tx` decodes to every field of the transaction (type, chain ID where the type has one, nonce, gas
   limit, gas price or fee caps, recipient, value, input, access list, blob versioned hashes, authorization list,
   signature). `keccak(tx)` (for a typed transaction, of the byte string; for legacy, of its RLP) equals `txHash`.
2. **Reasons are the pool's.** `included`: removed because a canonical block includes it. `replaced`: another
   transaction from the same sender at the same nonce took its place. `evicted`: pushed out by the pool's size limit
   or the blob-sidecar byte budget. `expired`: older than the pool's transaction timeout. `dropped`: any other
   removal (an explicit remove, a pool clear, a removal the pool did not ask for).
3. **Order.** Within one `(nodeId, session)`, `seq` increases by one per record in the order the pool acted. Records
   are keyed by `sender` on the topic, so one sender's records stay in order on any partition count. There is no
   order against the record topic.
4. **Gaps.** A `seq` jump means records were dropped (counted in `fukuii_export_pool_dropped_total`). A new `session`
   for a `nodeId` means that node restarted with an empty pool; no `poolRemove` is sent for what it held.

## 7. Ordering across records on one topic

Per `chainKey` the sequence is totally ordered. Within it:

- block records are strictly increasing in `blockNumber` except immediately after a run of retractions, where the
  next block record's `blockNumber` equals the lowest retracted `blockNumber`;
- a run of retractions is strictly decreasing in `blockNumber` and its lowest member's `parentHash` names the block
  the replacement chain builds on (the fork point is canonical and unretracted);
- finality records may appear anywhere and never decrease in `blockNumber`;
- safe records (§5.1) may appear anywhere, never name a block below the finality watermark, and MAY decrease;
- the first record a datadir ever publishes is a `chainStart` (§5.2); on a fresh start it is followed by a finality
  record (ETC: genesis) or the first block record (ETH: finality follows with the first applied FCU);
- `blockNumber` may restart only immediately after a `chainStart` with a new `chainId` or genesis hash (a chain reset,
  spec DC-11); the rules above then apply to the new instance from its genesis.

A gap in `blockNumber` that is not explained by retractions is a fault (DC-4); the consumer stops.

## 8. Versioning

`0x01` is frozen by GT-1 together with the golden vectors. Any change to item order, a type's body or the netting rules
is a new version byte. Adding a record type reuses `0x01` only if the new type's envelope is unchanged and consumers
can skip unknown `type` values — they MUST NOT; so a new type is also a new version. *(Amendment 2026-11-24: types 4
to 7 were added before GT-1 froze `0x01`, with their vectors, so they are part of v1, not a new version.)* 018 F2's local trie-node undo
records are outside this contract and carry no version byte of this scheme.

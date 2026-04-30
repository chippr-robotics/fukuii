# snap/1 Protocol Summary

**EIP-4644** — Ethereum SNAP synchronization protocol
**Status:** Deployed, production, all major clients implement
**Reference implementation:** go-ethereum `eth/protocols/snap/`

---

## Protocol Overview

SNAP (`snap/1`) is a sub-protocol layered over RLPx that enables fast state download by
fetching flat key-value ranges with Merkle proof verification, rather than traversing
the trie node-by-node.

**Why SNAP vs Fast/Full Sync:**
- Fast sync: O(nodes) trie traversal, millions of random DB lookups
- SNAP sync: O(accounts) sequential range scan, locally batched, provably correct
- ~10x faster than fast sync for large state (ETC: ~140M accounts at mainnet)

---

## Message Types (8 total)

| Code | Request | Response |
|------|---------|---------|
| 0x00 | GetAccountRange | — |
| 0x01 | — | AccountRange |
| 0x02 | GetStorageRanges | — |
| 0x03 | — | StorageRanges |
| 0x04 | GetByteCodes | — |
| 0x05 | — | ByteCodes |
| 0x06 | GetTrieNodes | — |
| 0x07 | — | TrieNodes |

All messages include a `RequestId` for matching responses to requests.

---

## GetAccountRange / AccountRange

```
GetAccountRange:
  RequestId  uint64
  RootHash   bytes32   # State root (pivot block)
  StartHash  bytes32   # Range start (inclusive)
  LimitHash  bytes32   # Range limit (inclusive)
  ResponseBytes uint64 # Soft size limit

AccountRange:
  RequestId  uint64
  Accounts   []Account # (hash, RLP-encoded slim account)
  Proof      [][]byte  # Merkle proof nodes
```

**Slim account RLP:** `[nonce, balance, storageRoot, codeHash]` without empty defaults.

**Proof structure:** left boundary proof + right boundary proof if response is a subset.
Full range (origin=0x00, limit=0xFF) with complete data needs no proof.

**Verification:** `VerifyRangeProof(root, start, end, keys, values, proof)` — standard MPT range proof.

---

## GetStorageRanges / StorageRanges

```
GetStorageRanges:
  RequestId      uint64
  RootHash       bytes32
  AccountHashes  []bytes32  # Multiple accounts in one request
  StartingHash   bytes32    # Range start (shared for all accounts)
  LimitHash      bytes32    # Range limit
  ResponseBytes  uint64

StorageRanges:
  RequestId  uint64
  Slots      [][]Slot   # Per-account slot list
  Proof      [][]byte   # Proof for last (incomplete) account only
```

**Key:** Multi-account batching — single request can cover many small contracts.
**Continuation:** If last account's slots are incomplete, proof is provided. Client
requests remaining range with new StartingHash.

---

## GetByteCodes / ByteCodes

```
GetByteCodes:
  RequestId      uint64
  CodeHashes     []bytes32  # Content hashes of bytecodes to fetch
  ResponseBytes  uint64

ByteCodes:
  RequestId  uint64
  Codes      [][]byte  # Bytecode blobs, in request order (empty if not found)
```

**Note:** Server returns in request order; missing codes = empty bytes. Client must
match by position, not by hash comparison of response only.

---

## GetTrieNodes / TrieNodes

```
GetTrieNodes:
  RequestId  uint64
  RootHash   bytes32
  Paths      []PathSet    # PathSet = [][]byte
  ResponseBytes uint64

TrieNodes:
  RequestId  uint64
  Nodes      [][]byte  # RLP-encoded trie node blobs
```

**PathSet encoding:**
- `[accountPath]` — single account trie node
- `[accountPath, storagePath1, storagePath2, ...]` — storage nodes under one account

**Purpose:** Repair trie gaps at chunk boundaries after range download. Healing phase.

---

## Merkle Proof Format

Standard Ethereum MPT range proof:
- Proof for left boundary: all nodes on path from root to first key
- Proof for right boundary: all nodes on path from root to last key (or beyond)
- Together prove that the delivered key-value pairs are a complete, correct subset

---

## Serving Requirements (Server Side)

A SNAP server MUST:
1. Have completed state snapshots (not just trie)
2. Respond to all 4 request types
3. Include valid Merkle proofs in range responses
4. Respect `ResponseBytes` soft limit (may exceed slightly for whole-item boundary)
5. Return consistent data: all slots/accounts from same state root

A SNAP server SHOULD:
- Apply time limits to prevent blocking (core-geth: 2s per handler)
- Check snapshot readiness before serving
- Not starve ETH protocol writes (core-geth: `runtime.Gosched()`)

---

## Client Behavior Requirements

1. Divide account space into N concurrent ranges
2. Verify all range proofs before persisting
3. Track per-range progress for restart
4. Handle empty responses (peer has pruned state → mark stateless)
5. Handle partial responses (continuation → request remaining range)
6. Request storage and bytecode after account data arrives
7. Heal trie boundaries via GetTrieNodes after all ranges complete
8. Persist progress to DB for crash recovery

---

## Important Edge Cases

| Case | Correct Behavior |
|------|-----------------|
| Empty accounts + no proof | Peer stateless for this range, try another peer |
| Empty accounts + proof | Valid — proven empty range |
| `cont=true` in storage | More slots remain, request next range |
| Proof verification fails | Revert, try different peer |
| Wrong state root | Proof will fail — transparent via verification |
| Partial response (< requested bytes) | Valid — server hit time/size limit |
| Response exceeds ResponseBytes | Acceptable per spec (whole item boundary) |

---

## ETC-Specific Notes

- State trie structure is identical to ETH — same MPT format, same proof logic
- State root is in the block header (same field: `Header.StateRoot`)
- Account format is identical (nonce, balance, storageRoot, codeHash)
- Largest accounts on ETC: exchange wallets, token contracts — same order of magnitude as ETH
- ETC mainnet: ~140M+ accounts at state as of 2026
- Mordor testnet: much smaller state, good for testing

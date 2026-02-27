# SNAP/1 E2E Implementation Correctness Evaluation

**Date:** 2026-02-27
**Scope:** Full comparison of fukuii's SNAP/1 implementation against the [devp2p SNAP spec](https://github.com/ethereum/devp2p/blob/master/caps/snap.md), Nethermind (C#), and Besu (Java).

---

## Executive Summary

Fukuii implements a functionally complete SNAP/1 client-side (requesting) sync pipeline covering all four message pairs. The wire protocol encoding, sync orchestration, and adaptive tuning are solid and production-tested against ETC mainnet. However, the evaluation identified **several correctness gaps** ranging from minor spec divergences to substantive verification weaknesses that could cause silent data corruption or compatibility failures against strict peers.

**Severity ratings:** CRITICAL = may cause sync failure or data corruption; HIGH = spec non-compliance that affects interop; MEDIUM = correctness weakness; LOW = minor or cosmetic.

---

## 1. Wire Protocol Messages (SNAP.scala)

### 1.1 Message Codes & Offset — CORRECT with caveat

| Property | Spec | Fukuii | Verdict |
|---|---|---|---|
| GetAccountRange | 0x00 | 0x21 (0x00 + offset) | **Correct** |
| AccountRange | 0x01 | 0x22 | **Correct** |
| GetStorageRanges | 0x02 | 0x23 | **Correct** |
| StorageRanges | 0x03 | 0x24 | **Correct** |
| GetByteCodes | 0x04 | 0x25 | **Correct** |
| ByteCodes | 0x05 | 0x26 | **Correct** |
| GetTrieNodes | 0x06 | 0x27 | **Correct** |
| TrieNodes | 0x07 | 0x28 | **Correct** |

The offset of `0x21` is correct for the common negotiation where ETH/68 is the first capability. However, the devp2p spec computes capability offsets dynamically based on alphabetical capability ordering. Fukuii hardcodes this offset rather than computing it from capability negotiation.

**Finding F1 (MEDIUM):** The `SnapProtocolOffset = 0x21` is hardcoded. If the peer negotiates capabilities in a different order or ETH message count changes, this breaks. Geth, Nethermind, and Besu all compute offsets dynamically.

**Comparison:**
- **Geth:** Dynamic offset based on capability negotiation
- **Nethermind:** Dynamic offset (ProtocolsManager computes per-capability offsets)
- **Besu:** Dynamic offset (SubProtocol registry)
- **Fukuii:** Hardcoded `0x21`

### 1.2 RLP Encoding Structure — CORRECT

All 8 message types follow the correct RLP structure from the spec:

| Message | Spec Structure | Fukuii | Verdict |
|---|---|---|---|
| GetAccountRange | `[reqID, rootHash, startHash, limitHash, bytes]` | Matches | **Correct** |
| AccountRange | `[reqID, accounts, proof]` where accounts = `[[hash, body], ...]` | Matches | **Correct** |
| GetStorageRanges | `[reqID, rootHash, accountHashes, startHash, limitHash, bytes]` | Matches | **Correct** |
| StorageRanges | `[reqID, slots, proof]` where slots = `[[[hash, data], ...], ...]` | Matches | **Correct** |
| GetByteCodes | `[reqID, hashes, bytes]` | Matches | **Correct** |
| ByteCodes | `[reqID, codes]` | Matches | **Correct** |
| GetTrieNodes | `[reqID, rootHash, paths, bytes]` | Matches | **Correct** |
| TrieNodes | `[reqID, nodes]` | Matches | **Correct** |

### 1.3 RLP Encoding of requestId — CORRECT

The `SNAPMessagesSpec` explicitly tests:
- `requestId = 128` encodes as `[0x80]` (no leading zeros)
- `requestId = 0` encodes as empty bytes `[]`

This matches the RLP canonical encoding rules. Well-tested.

### 1.4 Account Body Encoding — NEEDS VERIFICATION

**Finding F2 (HIGH):** The spec defines account body as the "slim" RLP encoding: `[nonce, balance, storageRoot, codeHash]`. Fukuii uses `account.toRLPEncodable` via `AccountImplicits._`. If the `Account` serializer uses full encoding (with additional fields or different ordering), this would cause silent data corruption. The decode path uses `rlp.encode(accountRLP).toAccount`, which double-encodes if accountRLP is already an RLPList.

**Comparison:**
- **Geth:** Uses `types.SlimAccount` for SNAP, distinct from full account encoding
- **Nethermind:** Explicit slim encoding `Account.Slim.Encode()`
- **Besu:** `SlimAccount` vs `WorldState` account encoding
- **Fukuii:** Reuses the same `Account` serializer for both ETH and SNAP contexts

---

## 2. AccountRange Handling

### 2.1 Monotonic Ordering Validation — CORRECT

`SNAPRequestTracker.validateAccountRange()` checks that accounts are strictly monotonically increasing by hash. Uses unsigned lexicographic comparison, which is correct.

### 2.2 Proof Verification — PARTIALLY CORRECT with SIGNIFICANT GAPS

**Finding F3 (CRITICAL): Proof verification does not verify range completeness.**

The spec requires:
> "The responding node **must** Merkle prove the starting hash (even if it does not exist) and the last returned account."

This means the proof must demonstrate:
1. No accounts exist between the requested startingHash and the first returned account
2. No accounts exist between the last returned account and the next hash

Fukuii's `MerkleProofVerifier.verifyAccountRange()`:
- Verifies each account exists at its path in the proof (**correct**)
- Verifies the proof root matches the state root (**correct**)
- **Does NOT verify the proof proves non-existence of gaps** (LEFT-SIDE proof)
- **Does NOT verify the range boundary** (RIGHT-SIDE proof)

This is a standard "range proof" verification. Geth's `trie.VerifyRangeProof()` explicitly checks that no keys exist outside the proven range. Without this, a malicious peer could skip accounts and the client would accept the incomplete set.

**Comparison:**
- **Geth:** `trie.VerifyRangeProof()` — full range proof verification including non-existence at boundaries
- **Nethermind:** `AccountAndStorageVerifier.Verify()` — uses stacktrie-based range proof
- **Besu:** `RangeStorageEntriesCollector` — full range proof
- **Fukuii:** Per-account existence check only, no range completeness proof

**Finding F4 (HIGH): Missing node in proof treated as success.**

In `MerkleProofVerifier.traversePath()`:
```scala
case None =>
  // Node not in proof - this is acceptable for partial proofs
  Right(())
```

When a node hash is not found in the proof map, verification returns `Right(())` (success). This means ANY account would pass verification if the proof is incomplete, because the traversal would simply stop at the first missing node and declare success. A malicious peer could send arbitrary account data with an empty or minimal proof.

**Comparison:**
- **Geth:** Missing nodes in proof cause verification failure unless at a boundary
- **Nethermind:** Explicit tracking of resolved vs unresolved proof nodes
- **Besu:** Missing nodes fail verification
- **Fukuii:** Missing nodes silently succeed

### 2.3 Account Body Verification — CORRECT

`verifyAccountValue()` decodes the RLP account and compares nonce, balance, storageRoot, and codeHash field-by-field. Correct.

### 2.4 Empty Account Range Handling — CORRECT

```scala
if (proof.isEmpty && accounts.isEmpty) {
  if (rootHash == ByteString(MerklePatriciaTrie.EmptyRootHash)) return Right(())
  return Left("Missing proof for empty account range")
}
```

Correctly handles the empty trie case and requires proofs for non-empty tries. Matches spec behavior.

---

## 3. StorageRanges Handling

### 3.1 Multi-Account Batching — MOSTLY CORRECT

**Finding F5 (MEDIUM): origin/limit applies only to first account.**

The spec states:
> "The `startingHash` and `limitHash` fields apply to the first requested account's storage only."

Fukuii correctly implements this in `StorageRangeCoordinator.requestNextRanges()`:
```scala
val first = tasks.dequeue()
val batchTasks: Seq[StorageTask] =
  if (!isInitialRange(first) || peerBatch <= 1) {
    Seq(first)
  } else { ... only batch initial full-range tasks ... }
```

Only tasks requesting the full range (0x00..FF) are batched together. Continuation tasks with non-zero origin are sent as single-account requests. This is correct.

### 3.2 Proof Assignment — CORRECT

```scala
val proofForThisTask = if (idx == servedCount - 1) response.proof else Seq.empty
```

Proofs are applied only to the last served account, matching the spec: "proofs only attached to last incomplete account."

### 3.3 Storage Proof Verification — SAME GAPS AS ACCOUNT RANGE

The `MerkleProofVerifier.verifyStorageRange()` has the same F3 and F4 issues as account range verification. Additionally:

**Finding F6 (MEDIUM): No-proof path accepts data without full verification.**

```scala
if (proof.isEmpty && slots.nonEmpty) {
  return validateStorageSlotsBasic(slots, startHash, endHash)
}
```

When no proof is provided, only basic ordering and bounds checks are done. The spec says proofs can be omitted when the entire storage is returned. However, without verifying that the storage trie root matches after inserting all slots, this accepts unverified data.

**Comparison:**
- **Geth:** Rebuilds the storage trie from slots and verifies root matches `account.storageRoot`
- **Nethermind:** Same approach — trie rebuild + root check
- **Besu:** Verifies rebuilt trie root
- **Fukuii:** No trie root verification for proof-less responses

### 3.4 Continuation Logic — CORRECT

`StorageTask.createContinuation()` correctly increments the last slot hash by 1 and creates a new task from `lastSlot+1` to `task.last`.

---

## 4. ByteCodes Handling

### 4.1 Hash Verification — CORRECT and THOROUGH

`ByteCodeCoordinator.validateReturnedCodes()` verifies:
- Each returned bytecode's keccak256 hash matches a requested hash (**correct**)
- Returned codes are a subsequence of requested hashes, in order (**correct per spec**)
- No duplicate codes (**correct**)

This is actually more thorough than some implementations.

**Comparison:**
- **Geth:** Verifies hash of each code against requested hashes
- **Nethermind:** Same hash verification
- **Besu:** Same hash verification
- **Fukuii:** Hash verification PLUS subsequence ordering check. **Good.**

### 4.2 Batch Size — COMPATIBLE

Fukuii defaults to 1000 hashes per batch with a soft byte limit of 512KB-2MB.
- Geth handler limit: 2MB soft cap (`SNAP_SOFTRESPONSELIMIT`)
- Nethermind: Dynamic batching up to 1000
- Besu: Default ~84 per batch

Fukuii's approach matches Nethermind closely. Compatible.

### 4.3 Per-Hash Failure Tracking — GOOD

The `maxFailuresPerHash = 10` limit prevents infinite re-queuing of hashes no peer can serve. This is defensive and not found in all implementations. **Good engineering practice.**

---

## 5. TrieNodes Handling

### 5.1 Path Encoding — NEEDS VERIFICATION

**Finding F7 (HIGH): GetTrieNodes path structure may be incorrect.**

The spec defines paths as:
> `paths: [[accPath: B, slotPath1: B, slotPath2: B, ...], ...]`

Where:
- A single-element path `[accPath]` requests an account trie node
- Multi-element paths `[accPath, slotPath1, ...]` request storage trie nodes under that account

Fukuii's `GetTrieNodes` encodes `paths: Seq[Seq[ByteString]]` which structurally matches. However, the `TrieNodeHealingCoordinator.queueNodes()` receives `(pathset: Seq[ByteString], hash: ByteString)` pairs from `StateValidator.findMissingNodesWithPaths()`. The semantic correctness depends on whether `StateValidator` produces spec-compliant path sets (account-path-prefix + storage-path-suffix) or just raw trie paths.

**Comparison:**
- **Geth:** `trieTask` explicitly separates account paths (`[][]byte{path}`) from storage paths (`[][]byte{accountPath, storagePath}`)
- **Nethermind:** `PathGroup` class explicitly models the account/storage path hierarchy
- **Besu:** `TrieNodePath` explicitly models the hierarchy
- **Fukuii:** Generic `Seq[Seq[ByteString]]` — correct structure but semantic correctness depends on the path producer

### 5.2 Node Hash Verification — CORRECT

```scala
val nodeHash = ByteString(org.bouncycastle.jcajce.provider.digest.Keccak.Digest256().digest(nodeData.toArray))
if (nodeHash == task.hash) { ... }
```

Each returned node is verified against its expected keccak256 hash. Correct.

### 5.3 Positional Matching — CORRECT per spec

Response nodes are matched positionally to request paths (`nodes.zip(tasksForRequest)`). The spec says nodes must be in request order with potential truncation. Correct.

---

## 6. Sync Orchestration

### 6.1 Phase Ordering — DIFFERENT from Geth/Nethermind

| Phase | Fukuii Order | Geth Order | Nethermind Order |
|---|---|---|---|
| 1 | AccountRange | AccountRange + Storage (interleaved) | AccountRange + Storage (interleaved) |
| 2 | ByteCode | ByteCode (parallel) | ByteCode (parallel) |
| 3 | StorageRange | (done in phase 1) | (done in phase 1) |
| 4 | Healing | Healing | Healing |
| 5 | Validation | Validation | Validation |

**Finding F8 (MEDIUM): Sequential phase ordering is less efficient than interleaved.**

Geth and Nethermind download storage ranges for accounts as soon as those accounts are received (interleaved with account range download). Fukuii waits until ALL accounts are downloaded before starting storage download. This has two consequences:

1. **Performance:** Adds latency — storage download can't start until account phase completes. On ETC mainnet (~2.3M accounts), this could add 10-30 minutes of idle time.
2. **State root staleness:** The longer the sync takes, the more likely the pivot's state root ages out of peers' serve window (128 blocks * ~13s = ~28 min on ETC). Fukuii mitigates this with pivot refresh, but interleaving would be more robust.

**Comparison:**
- **Geth:** Interleaved — accounts and storage run concurrently
- **Nethermind:** Interleaved — same
- **Besu:** Interleaved — same
- **Fukuii:** Sequential — AccountRange → ByteCode → StorageRange → Healing

### 6.2 Pivot Selection — CORRECT with good defensive measures

Pivot selection follows the core-geth pattern: `pivot = networkBest - offset`. The offset of 128 blocks (configurable) is more conservative than geth's 64 (though the code comments say 64 while config shows 128). The implementation includes:
- Peer height validation
- Bootstrap pivot floor
- Staleness checks
- Pivot refresh mechanism

### 6.3 Pivot Refresh — GOOD but not spec-standard

Fukuii's pivot refresh mechanism (in-place root update while preserving downloaded data) is a pragmatic optimization. Content-addressed trie nodes are indeed ~99.9% reusable across adjacent pivot changes. This is similar to what Geth does internally.

### 6.4 Stagnation Detection — GOOD

Account stagnation (15 min) and storage stagnation (20 min) watchdogs with promotion to healing phase is a solid defensive measure not all implementations have.

---

## 7. Response Size Limits

### 7.1 Adaptive Byte Budgeting — GOOD

| Parameter | Fukuii | Geth | Nethermind | Besu |
|---|---|---|---|---|
| Initial request size | 512 KB | 512 KB | 512 KB | 384 KB |
| Max request size | 2 MB | 2 MB | 2 MB | 2 MB |
| Min floor | 50 KB | - | - | - |
| Scale up factor | 1.25x | - | Dynamic | - |
| Scale down factor | 0.5x | - | Dynamic | - |

Fukuii's adaptive per-peer byte budgeting is well-designed. Starting at 512KB and scaling to 2MB matches geth's handler limit.

---

## 8. Error Handling & Resilience

### 8.1 Peer Blacklisting — CORRECT

- 10 total failures → blacklist
- 3 invalid proofs → blacklist
- 5 malformed responses → blacklist

These thresholds are reasonable and match the defensive posture of other implementations.

### 8.2 Stateless Peer Detection — GOOD

The binary classification (stateless vs. servable) with pivot refresh when all peers become stateless is a pragmatic approach. The consecutive timeout threshold (3) for detecting silently failing peers is particularly useful for ETC mainnet.

### 8.3 Circuit Breaker — GOOD

The circuit breaker pattern (10 consecutive failures opens) prevents hammering repeatedly failing operations. Well-implemented.

---

## 9. Test Coverage Assessment

### 9.1 Unit Tests

| Component | Test File | Coverage Quality |
|---|---|---|
| SNAP messages | `SNAPMessagesSpec` | **Minimal** — only tests requestId encoding |
| Controller | `SNAPSyncControllerSpec` | Present |
| Request tracker | `SNAPRequestTrackerSpec` | Present |
| Account coordinator | `AccountRangeCoordinatorSpec` | Present |
| ByteCode coordinator | `ByteCodeCoordinatorSpec` | Present |
| Storage coordinator | `StorageRangeCoordinatorSpec` | Present |
| Healing coordinator | `TrieNodeHealingCoordinatorSpec` | Present |
| Proof verifier | `MerkleProofVerifierSpec` | Present |

**Finding F9 (MEDIUM): SNAPMessagesSpec only tests requestId encoding.**

All 8 message types need round-trip encode/decode tests with real-world data (especially edge cases like empty lists, max-size hashes, etc.). Currently only 2 test cases exist for the entire message layer.

### 9.2 Integration Tests

The `SNAPSyncIntegrationSpec` tests coordinator lifecycle and message flow but **does not test actual SNAP protocol data** (no real account ranges, proofs, or bytecodes). Tests use `TestMptStorage` and `TestProbe` mocks.

**Finding F10 (MEDIUM): No end-to-end test with real Merkle proofs.** The integration tests verify actor message flow but not data correctness.

---

## 10. Summary of Findings

### CRITICAL

| ID | Finding | Impact |
|---|---|---|
| F3 | No range proof verification (gap detection) | Malicious peer can skip accounts; incomplete state goes undetected until validation |
| F4 | Missing proof nodes treated as verification success | Any data passes proof verification with incomplete proofs |

### HIGH

| ID | Finding | Impact |
|---|---|---|
| F2 | Account body encoding uses shared serializer, not slim encoding | Could cause encoding mismatch with strict peers |
| F7 | GetTrieNodes path semantics depend on unverified path producer | Incorrect paths would cause healing failures |

### MEDIUM

| ID | Finding | Impact |
|---|---|---|
| F1 | Hardcoded protocol offset (0x21) | Breaks if capability negotiation order changes |
| F5 | (Noted but correctly handled) | N/A |
| F6 | No trie root verification for proof-less storage responses | Unverified data accepted |
| F8 | Sequential phases instead of interleaved | Longer sync time, higher pivot staleness risk |
| F9 | Minimal message encoding tests | Encoding bugs could go undetected |
| F10 | No e2e test with real proofs | Proof verification logic not exercised in tests |

### LOW

None — all findings are MEDIUM or above.

---

## 11. Recommendations (Priority Order)

1. **Fix F3+F4 (CRITICAL):** Implement full range proof verification following geth's `trie.VerifyRangeProof()`. This requires building a partial trie from the proof nodes and verifying that the delivered accounts/slots are the *complete* set between the proven boundaries. This is the single most important correctness fix.

2. **Fix F2 (HIGH):** Audit the Account RLP serializer used in SNAP contexts. Ensure it produces the "slim" 4-tuple `[nonce, balance, storageRoot, codeHash]` and nothing else. Add a dedicated test comparing encoded output against known-good geth/Nethermind vectors.

3. **Fix F7 (HIGH):** Audit `StateValidator.findMissingNodesWithPaths()` to ensure it produces spec-compliant path sets: single-element for account trie nodes, two-element `[accountPath, storagePath]` for storage trie nodes.

4. **Fix F1 (MEDIUM):** Compute SNAP protocol offset dynamically from capability negotiation rather than hardcoding `0x21`.

5. **Fix F6 (MEDIUM):** For proof-less storage responses, rebuild the storage trie from received slots and verify the root matches `account.storageRoot`.

6. **Fix F8 (MEDIUM):** Interleave storage range download with account range download. Start fetching storage for accounts as they arrive rather than waiting for all accounts to complete.

7. **Fix F9+F10 (MEDIUM):** Add comprehensive round-trip encoding tests for all 8 SNAP message types. Add integration tests using real Merkle proofs (can be generated from a small in-memory trie).

---

## 12. Comparison Matrix

| Feature | Spec | Geth | Nethermind | Besu | Fukuii |
|---|---|---|---|---|---|
| All 8 messages | Required | Yes | Yes | Yes | **Yes** |
| Dynamic msg offset | Required | Yes | Yes | Yes | **No (F1)** |
| Range proof verification | Required | Yes | Yes | Yes | **No (F3)** |
| Slim account encoding | Required | Yes | Yes | Yes | **Unverified (F2)** |
| Monotonic ordering check | Required | Yes | Yes | Yes | **Yes** |
| Proof root verification | Required | Yes | Yes | Yes | **Yes** |
| Interleaved download | Best practice | Yes | Yes | Yes | **No (F8)** |
| Adaptive byte budget | Best practice | Partial | Yes | Partial | **Yes** |
| Peer blacklisting | Best practice | Yes | Yes | Yes | **Yes** |
| Stagnation detection | Best practice | Partial | Partial | Partial | **Yes** |
| Pivot refresh | Best practice | Yes | Yes | Yes | **Yes** |

---

## Conclusion

Fukuii's SNAP/1 implementation is **structurally complete** and demonstrates **good engineering practices** in areas like adaptive byte budgeting, stagnation detection, and peer management. The wire protocol encoding appears correct. However, the **Merkle proof verification is fundamentally incomplete** (F3, F4) — it verifies that individual accounts exist in the proof but does not verify that the proven range is complete (no gaps). This is the same class of vulnerability that snap sync was specifically designed to protect against.

The implementation currently relies on the final state root validation (phase 5) as a safety net. While this catches data integrity issues, it does so late — after potentially hours of sync work. Proper range proof verification (as done by Geth, Nethermind, and Besu) catches malicious or corrupted data immediately and identifies the offending peer.

**Overall assessment: Functionally operational for honest networks, but not hardened against adversarial peers. Priority should be given to fixing F3/F4 (range proof verification) before production use on untrusted networks.**

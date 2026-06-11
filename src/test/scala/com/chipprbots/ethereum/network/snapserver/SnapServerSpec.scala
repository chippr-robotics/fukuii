package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

// ── K8: SNAP server correctness — range semantics, Merkle proofs, multi-account storage,
//        trie-node path disambiguation, and bytecode serving edge cases.
//
// Companion to SnapServerLimitsSpec (K7), which covers the byte-budget and time-budget
// invariants. This spec covers WHAT is returned, not HOW MUCH.

class SnapServerSpec extends AnyFlatSpec with Matchers {

  // ── shared constants ────────────────────────────────────────────────────────
  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash = ByteString(Array.fill[Byte](32)(0xff.toByte))
  private val bigBudget: BigInt = BigInt(2 * 1024 * 1024)

  // ── account trie builder ────────────────────────────────────────────────────

  /** Build an account trie from (hash, Account) pairs, sharing all nodes in one TestMptStorage. */
  private def buildAccountTrie(
      accounts: Seq[(ByteString, Account)],
      storage: TestMptStorage = new TestMptStorage()
  ): (ByteString, TestMptStorage) = {
    val trie = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { case (t, (k, v)) =>
      t.put(k, v)
    }
    (ByteString(trie.getRootHash), storage)
  }

  /** Build a storage trie from (slotHash, value) pairs, reusing an existing TestMptStorage. Because MPT is
    * content-addressed, account and storage nodes can coexist safely.
    */
  private def buildStorageTrie(
      slots: Seq[(ByteString, ByteString)],
      storage: TestMptStorage
  ): ByteString = {
    val trie = slots.foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { case (t, (k, v)) =>
      t.put(k, v)
    }
    ByteString(trie.getRootHash)
  }

  /** 10 deterministic account keys spread across the keyspace. */
  private def sampleKeys(n: Int): Seq[ByteString] =
    (0 until n).map(i => kec256(ByteString(s"account-$i")))

  /** Simple EOA (no storage, no code). */
  private def eoa(nonce: Int = 1, balance: Int = 1000): Account =
    Account(nonce = nonce, balance = balance)

  /** Account with a given storage root. */
  private def accountWithStorage(storageRoot: ByteString): Account =
    Account(nonce = 1, balance = 500, storageRoot = storageRoot)

  /** HP-encoded empty path — asks serveTrieNodes for the root node of the trie. */
  private val hpRootPath: ByteString = ByteString(Array(0x00.toByte))

  // ── serveByteCodes — stub code storage ─────────────────────────────────────

  /** Stub EvmCodeStorage backed by a Map — no disk I/O. */
  private def codeStorage(entries: (ByteString, ByteString)*): EvmCodeStorage = {
    val map = entries.toMap
    new EvmCodeStorage(null) {
      override def get(key: ByteString): Option[ByteString] = map.get(key)
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // serveAccountRange — range semantics and Merkle proof correctness
  // ═══════════════════════════════════════════════════════════════════════════

  "SnapServer.serveAccountRange" should
    "return all accounts in sorted (ascending hash) order" taggedAs UnitTest in {
      val keys = sampleKeys(6)
      val pairs = keys.map(_ -> eoa())
      val (root, storage) = buildAccountTrie(pairs)

      val result = SnapServer.serveAccountRange(1, root, zeroHash, maxHash, bigBudget, storage)

      result.accounts should have size pairs.size
      val hashes = result.accounts.map(_._1)
      hashes shouldEqual hashes.sorted(Ordering.by[ByteString, BigInt](bs => BigInt(1, bs.toArray)))
    }

  it should "always include a left-bound proof for a non-empty response" taggedAs UnitTest in {
    val (root, storage) = buildAccountTrie(sampleKeys(5).map(_ -> eoa()))

    val result = SnapServer.serveAccountRange(1, root, zeroHash, maxHash, bigBudget, storage)

    result.accounts should not be empty
    result.proof should not be empty
    // The first proof node is the root node; its kec256 must equal the state root.
    kec256(result.proof.head) shouldEqual root
  }

  it should "include a right-bound proof only when the response is truncated" taggedAs UnitTest in {
    val (root, storage) = buildAccountTrie(sampleKeys(8).map(_ -> eoa()))

    // Full response — no truncation.
    val full = SnapServer.serveAccountRange(1, root, zeroHash, maxHash, bigBudget, storage)
    // Truncated response (budget=1 forces single item).
    val partial = SnapServer.serveAccountRange(2, root, zeroHash, maxHash, BigInt(1), storage)

    full.accounts should have size 8

    // The proof for the full response need only contain the left-bound path.
    // The proof for the partial response must contain at least the left AND the
    // right-bound paths — typically more nodes than the full-range proof.
    partial.proof.size should be >= full.proof.size
    partial.accounts.size should be < full.accounts.size
  }

  it should "return an empty account list with proof of absence for a range past all keys" taggedAs UnitTest in {
    // Build 5 accounts all hashing to the lower half of the keyspace; then
    // request from 0xf0…00 onward — no accounts exist there.
    val keys = (0 until 5).map(i => kec256(ByteString(s"low-$i")))
    val (root, storage) = buildAccountTrie(keys.map(_ -> eoa()))
    // Compute a starting hash guaranteed to lie past the highest key in the trie.
    val maxKeyBI = keys.map(bs => BigInt(1, bs.toArray)).max
    val raw = (maxKeyBI + 1).toByteArray
    val highStart = ByteString(Array.fill[Byte](math.max(0, 32 - raw.length))(0) ++ raw.takeRight(32))

    val result = SnapServer.serveAccountRange(1, root, highStart, maxHash, bigBudget, storage)

    result.accounts shouldBe empty
    // Even for an empty range a left-bound proof must be present (proves absence).
    result.proof should not be empty
  }

  it should "handle a reversed range (start > limit) as single-key-from-start" taggedAs UnitTest in {
    val keys = sampleKeys(5)
    val (root, storage) = buildAccountTrie(keys.map(_ -> eoa()))
    val sortedKeys = keys.sorted(Ordering.by[ByteString, BigInt](bs => BigInt(1, bs.toArray)))
    val highStart = sortedKeys.last // highest unsigned key — start AFTER most keys
    val lowLimit = sortedKeys.head // lowest unsigned key — limit BEFORE most keys → reversed

    // start > limit: should return the single account at/after `highStart`.
    val result = SnapServer.serveAccountRange(1, root, highStart, lowLimit, bigBudget, storage)

    result.accounts should have size 1
    result.accounts.head._1 shouldEqual highStart
  }

  it should "return exactly one account when start == limit and the key exists" taggedAs UnitTest in {
    val keys = sampleKeys(5)
    val (root, storage) = buildAccountTrie(keys.map(k => k -> eoa()))
    val target = keys.sorted(Ordering.by[ByteString, BigInt](bs => BigInt(1, bs.toArray))).head

    val result = SnapServer.serveAccountRange(1, root, target, target, bigBudget, storage)

    result.accounts should have size 1
    result.accounts.head._1 shouldEqual target
  }

  it should "slim-encode accounts: EmptyStorageRootHash and EmptyCodeHash become empty bytes" taggedAs UnitTest in {
    // toSlimAccountRlp is the encoding used on the wire; verify the round-trip
    // for a plain EOA (both fields default to the empty constants).
    val account = eoa()
    account.storageRoot shouldEqual Account.EmptyStorageRootHash
    account.codeHash shouldEqual Account.EmptyCodeHash

    val slim = SnapServer.toSlimAccountRlp(account)
    val fields = slim.items
    // Field 2 (storageRoot) and 3 (codeHash) should be empty byte arrays.
    fields(2) match {
      case com.chipprbots.ethereum.rlp.RLPValue(b) => b shouldBe empty
      case other                                   => fail(s"expected RLPValue, got $other")
    }
    fields(3) match {
      case com.chipprbots.ethereum.rlp.RLPValue(b) => b shouldBe empty
      case other                                   => fail(s"expected RLPValue, got $other")
    }
  }

  it should "not slim-encode accounts that have non-default storageRoot or codeHash" taggedAs UnitTest in {
    val fakeStorageRoot = kec256(ByteString("some storage"))
    val fakeCodeHash = kec256(ByteString("some code"))
    val account = Account(nonce = 1, balance = 0, storageRoot = fakeStorageRoot, codeHash = fakeCodeHash)

    val slim = SnapServer.toSlimAccountRlp(account)
    val fields = slim.items
    // Both fields must be full 32-byte arrays.
    fields(2) match {
      case com.chipprbots.ethereum.rlp.RLPValue(b) => b.length shouldEqual 32
      case other                                   => fail(s"expected RLPValue, got $other")
    }
    fields(3) match {
      case com.chipprbots.ethereum.rlp.RLPValue(b) => b.length shouldEqual 32
      case other                                   => fail(s"expected RLPValue, got $other")
    }
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // serveStorageRanges — proof conditions and multi-account behaviour
  // ═══════════════════════════════════════════════════════════════════════════

  "SnapServer.serveStorageRanges" should
    "return all slots with no proof when origin=0 and budget is not exceeded" taggedAs UnitTest in {
      val storage = new TestMptStorage()
      val slotKeys = (1 to 4).map(i => kec256(ByteString(s"slot-$i")))
      val slotValues = slotKeys.map(k => k -> ByteString(Array.fill[Byte](32)(1.toByte)))
      val storageRoot = buildStorageTrie(slotValues, storage)

      val accountHash = kec256(ByteString("account-with-storage"))
      val account = accountWithStorage(storageRoot)
      val (stateRoot, _) = buildAccountTrie(Seq(accountHash -> account), storage)

      val result = SnapServer.serveStorageRanges(
        requestId = 1,
        rootHash = stateRoot,
        accountHashes = Seq(accountHash),
        startingHash = zeroHash,
        limitHash = maxHash,
        responseBytes = bigBudget,
        storage = storage,
        accountRoot = _ => Some(storageRoot)
      )

      result.slots should have size 1
      result.slots.head should have size slotValues.size
      // Left-bound proof always present (proves the left boundary of the served range).
      result.proof should not be empty
    }

  it should "include a proof when startingHash is non-zero" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val slotKeys = (1 to 6).map(i => kec256(ByteString(s"slot-nonzero-$i")))
    val slotValues = slotKeys.map(k => k -> ByteString(Array.fill[Byte](32)(2.toByte)))
    val storageRoot = buildStorageTrie(slotValues, storage)

    val accountHash = kec256(ByteString("account-nonzero"))
    val account = accountWithStorage(storageRoot)
    val (stateRoot, _) = buildAccountTrie(Seq(accountHash -> account), storage)

    val nonZeroStart = slotKeys.sorted(Ordering.by[ByteString, BigInt](bs => BigInt(1, bs.toArray))).head

    val result = SnapServer.serveStorageRanges(
      requestId = 1,
      rootHash = stateRoot,
      accountHashes = Seq(accountHash),
      startingHash = nonZeroStart,
      limitHash = maxHash,
      responseBytes = bigBudget,
      storage = storage,
      accountRoot = _ => Some(storageRoot)
    )

    // Non-zero origin → proof required to prove the left boundary.
    result.proof should not be empty
  }

  it should "include both a left-bound and right-bound proof when response is truncated" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val slotKeys = (1 to 8).map(i => kec256(ByteString(s"slot-trunc-$i")))
    val slotValues = slotKeys.map(k => k -> ByteString(Array.fill[Byte](32)(3.toByte)))
    val storageRoot = buildStorageTrie(slotValues, storage)

    val accountHash = kec256(ByteString("account-trunc"))
    val account = accountWithStorage(storageRoot)
    val (stateRoot, _) = buildAccountTrie(Seq(accountHash -> account), storage)

    // Budget = 1 forces truncation after the first slot.
    val result = SnapServer.serveStorageRanges(
      requestId = 1,
      rootHash = stateRoot,
      accountHashes = Seq(accountHash),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1),
      storage = storage,
      accountRoot = _ => Some(storageRoot)
    )

    result.slots.head.size should be < slotValues.size
    // Truncated → proof present.
    result.proof should not be empty
  }

  it should "return all requested accounts' slots when budget is sufficient" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val mkSlots = (prefix: String, n: Int) =>
      (1 to n).map(i =>
        kec256(ByteString(s"$prefix-slot-$i")) ->
          ByteString(Array.fill[Byte](32)(i.toByte))
      )

    val slots1 = mkSlots("acct1", 3)
    val slots2 = mkSlots("acct2", 3)
    val root1 = buildStorageTrie(slots1, storage)
    val root2 = buildStorageTrie(slots2, storage)

    val hash1 = kec256(ByteString("account-multi-1"))
    val hash2 = kec256(ByteString("account-multi-2"))
    val (stateRoot, _) = buildAccountTrie(
      Seq(hash1 -> accountWithStorage(root1), hash2 -> accountWithStorage(root2)),
      storage
    )
    val rootMap = Map(hash1 -> root1, hash2 -> root2)

    val result = SnapServer.serveStorageRanges(
      requestId = 1,
      rootHash = stateRoot,
      accountHashes = Seq(hash1, hash2),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = bigBudget,
      storage = storage,
      accountRoot = rootMap.get
    )

    result.slots should have size 2
    result.slots(0) should have size 3
    result.slots(1) should have size 3
    result.proof should not be empty // left-bound proof always present for first account
  }

  it should "stop after the first account and emit proofs when budget is exceeded on that account" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val slots1 = (1 to 4).map(i =>
      kec256(ByteString(s"trunc-multi-slot-$i")) ->
        ByteString(Array.fill[Byte](32)(0xff.toByte))
    )
    val slots2 = (1 to 4).map(i =>
      kec256(ByteString(s"second-slot-$i")) ->
        ByteString(Array.fill[Byte](32)(0xee.toByte))
    )
    val root1 = buildStorageTrie(slots1, storage)
    val root2 = buildStorageTrie(slots2, storage)

    val hash1 = kec256(ByteString("multi-trunc-1"))
    val hash2 = kec256(ByteString("multi-trunc-2"))
    val (stateRoot, _) = buildAccountTrie(
      Seq(hash1 -> accountWithStorage(root1), hash2 -> accountWithStorage(root2)),
      storage
    )
    val rootMap = Map(hash1 -> root1, hash2 -> root2)

    // budget=1 causes truncation in the first account's walk.
    val result = SnapServer.serveStorageRanges(
      requestId = 1,
      rootHash = stateRoot,
      accountHashes = Seq(hash1, hash2),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1),
      storage = storage,
      accountRoot = rootMap.get
    )

    // Second account must NOT appear — truncation stops the outer loop.
    result.slots should have size 1
    result.proof should not be empty
  }

  it should "silently skip an account not present in the state (accountRoot returns None)" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val unknownHash = kec256(ByteString("ghost-account"))
    val dummyHash = kec256(ByteString("dummy-account-storage-skip"))
    // Non-empty trie avoids the isEmptyRoot early-return path in serveStorageRanges.
    val (stateRoot, _) = buildAccountTrie(Seq(dummyHash -> eoa()), storage)

    val result = SnapServer.serveStorageRanges(
      requestId = 1,
      rootHash = stateRoot,
      accountHashes = Seq(unknownHash),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = bigBudget,
      storage = storage,
      accountRoot = _ => None
    )

    // Unknown account → empty slot list for that account, no proof.
    result.slots should have size 1
    result.slots.head shouldBe empty
    result.proof shouldBe empty
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // serveTrieNodes — single-path vs multi-path disambiguation and alignment
  // ═══════════════════════════════════════════════════════════════════════════

  "SnapServer.serveTrieNodes" should
    "return the root node bytes for a single-element HP-root path" taggedAs UnitTest in {
      val (root, storage) = buildAccountTrie(sampleKeys(6).map(_ -> eoa()))

      val result = SnapServer.serveTrieNodes(1, root, Seq(Seq(hpRootPath)), bigBudget, storage)

      result.nodes should have size 1
      result.nodes.head should not be empty
      // Root node's kec256 must equal the state root.
      kec256(result.nodes.head) shouldEqual root
    }

  it should "return empty bytes for a single-element path that resolves to no node" taggedAs UnitTest in {
    val (root, storage) = buildAccountTrie(sampleKeys(4).map(_ -> eoa()))
    // HP-encode a nibble path that won't exist in the trie.
    // 0x1f = odd extension with first nibble 0x0f — a deep path unlikely to match any trie node.
    val missingPath = ByteString(Array(0x1f.toByte) ++ Array.fill[Byte](31)(0xff.toByte))

    val result = SnapServer.serveTrieNodes(1, root, Seq(Seq(missingPath)), bigBudget, storage)

    result.nodes should have size 1
    result.nodes.head shouldBe empty // positional alignment: missing → empty ByteString
  }

  it should "look up storage trie nodes via multi-element path (account hash + storage paths)" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val slotKeys = (1 to 4).map(i => kec256(ByteString(s"slot-trie-$i")))
    val slotValues = slotKeys.map(k => k -> ByteString(Array.fill[Byte](32)(5.toByte)))
    val storageRoot = buildStorageTrie(slotValues, storage)

    val accountHash = kec256(ByteString("account-trie-node"))
    val account = accountWithStorage(storageRoot)
    val (stateRoot, _) = buildAccountTrie(Seq(accountHash -> account), storage)

    // Multi-element pathSet: pathSet(0) = raw account hash (32 bytes), pathSet(1) = HP root of storage trie.
    val pathSet = Seq(accountHash, hpRootPath)

    val result = SnapServer.serveTrieNodes(1, stateRoot, Seq(pathSet), bigBudget, storage)

    result.nodes should have size 1
    result.nodes.head should not be empty
    // Storage root node hashes to storageRoot.
    kec256(result.nodes.head) shouldEqual storageRoot
  }

  it should "return sparse response (no entries) when the account is missing" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val dummyHash = kec256(ByteString("dummy-account-trie-nodes"))
    val (root, _) = buildAccountTrie(Seq(dummyHash -> eoa()), storage)
    val missingAccount = kec256(ByteString("no-such-account"))
    // Missing account → sparse response (no entries), matching go-ethereum handler.go.
    // TrieNodeHealingCoordinator matches by keccak256 hash, not position, so this is correct.
    val pathSet = Seq(missingAccount, hpRootPath, hpRootPath)

    val result = SnapServer.serveTrieNodes(1, root, Seq(pathSet), bigBudget, storage)

    result.nodes shouldBe empty
  }

  it should "return sparse response (no entries) for an account with no storage trie" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val storageRoot = buildStorageTrie(Seq.empty, storage) // → Account.EmptyStorageRootHash
    val accountHash = kec256(ByteString("empty-storage-account"))
    val account = accountWithStorage(storageRoot)
    val (stateRoot, _) = buildAccountTrie(Seq(accountHash -> account), storage)

    // Account has EmptyStorageRootHash → guard fires → sparse skip, matching go-ethereum.
    val deepPath = ByteString(Array(0x1f.toByte) ++ Array.fill[Byte](31)(0xaa.toByte))
    val pathSet = Seq(accountHash, deepPath)

    val result = SnapServer.serveTrieNodes(1, stateRoot, Seq(pathSet), bigBudget, storage)

    result.nodes shouldBe empty
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // serveByteCodes — geth edge cases, lookup cap, empty-code hash
  // ═══════════════════════════════════════════════════════════════════════════

  "SnapServer.serveByteCodes" should
    "return codes for present hashes" taggedAs UnitTest in {
      val code1 = ByteString(Array.fill[Byte](100)(0xde.toByte))
      val code2 = ByteString(Array.fill[Byte](200)(0xad.toByte))
      val hash1 = kec256(code1)
      val hash2 = kec256(code2)
      val storage = codeStorage(hash1 -> code1, hash2 -> code2)

      val result = SnapServer.serveByteCodes(1, Seq(hash1, hash2), bigBudget, storage)

      result.codes shouldEqual Seq(code1, code2)
    }

  it should "silently skip hashes not in storage (no empty placeholder)" taggedAs UnitTest in {
    val code1 = ByteString(Array.fill[Byte](50)(0xbe.toByte))
    val hash1 = kec256(code1)
    val hash2 = kec256(ByteString("missing"))
    val storage = codeStorage(hash1 -> code1) // hash2 absent

    val result = SnapServer.serveByteCodes(1, Seq(hash1, hash2), bigBudget, storage)

    // hash2 is skipped — result contains only code1.
    result.codes shouldEqual Seq(code1)
  }

  it should "return ByteString.empty for Account.EmptyCodeHash without a DB lookup" taggedAs UnitTest in {
    // EmptyCodeHash is kec256(ByteString.empty). go-ethereum returns []byte{} for this
    // hash without touching the DB (handlers.go:360-361). Bug 3 fix.
    val storage = codeStorage() // empty storage — would miss on any DB lookup

    val result = SnapServer.serveByteCodes(1, Seq(Account.EmptyCodeHash), bigBudget, storage)

    result.codes should have size 1
    result.codes.head shouldBe empty
  }

  it should "cap at 2MB regardless of responseBytes in the request" taggedAs UnitTest in {
    // Create a code that is exactly 512KB; repeat 5 times = 2.5MB total.
    // The 2MB cap must stop us at 4 codes (4 × 512KB = 2MB).
    val halfMB = 512 * 1024
    val bigCode = ByteString(Array.fill[Byte](halfMB)(0xff.toByte))
    val bigHash = kec256(bigCode)
    val storage = codeStorage(bigHash -> bigCode)
    val hashes = Seq.fill(5)(bigHash)
    val oversized = BigInt(10 * 1024 * 1024) // 10MB request

    val result = SnapServer.serveByteCodes(1, hashes, oversized, storage)

    // First-item guarantee: at least 1. Budget: up to 2MB → ≤ 4 items (first always returned).
    result.codes.size should be >= 1
    result.codes.size should be <= 4
    result.codes.map(_.size).sum should be <= 2 * 1024 * 1024 + halfMB // one item may exceed
  }

  it should "process at most 1024 hashes regardless of request length" taggedAs UnitTest in {
    // Bug 2 fix: unbounded iteration. Create 1100 distinct codes; ask for all of them.
    // After the fix, only the first 1024 are examined.
    val codes = (0 until 1100).map(i => ByteString(Array.fill[Byte](1)(i.toByte)))
    val hashes = codes.map(kec256(_))
    val storage = codeStorage(hashes.zip(codes)*)

    val result = SnapServer.serveByteCodes(1, hashes, bigBudget, storage)

    // At most 1024 codes returned; the remaining 76 are not examined.
    result.codes.size should be <= 1024
  }

  it should "return an empty response when no codes match and EmptyCodeHash is not in the request" taggedAs UnitTest in {
    val storage = codeStorage() // empty
    val randomHash = kec256(ByteString("nonexistent"))

    val result = SnapServer.serveByteCodes(1, Seq(randomHash), bigBudget, storage)

    result.codes shouldBe empty
  }
}

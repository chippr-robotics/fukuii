package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.{Callable, Executors, Future => JFuture, TimeUnit}

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.{MerklePatriciaTrie, MptTraversals, byteStringSerializer}
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

/** Reference-behavioral test suite for MerkleProofVerifier Phase 3 (leaf insertion).
  *
  * Tests mirror go-ethereum's proof_test.go scenarios (4096-key trie, sub-ranges, mutations) and Besu's boundary proof
  * contracts. Groups 1–2 reproduce the Phase 3 stall — they FAIL with the current doInsertLeaf implementation
  * (O(N×depth) allocation cascade, ~7m for 8000 leaves) and PASS after the architectural fix. Groups 3–10 verify
  * correctness contracts that must hold both before and after the fix.
  */
class MerkleProofVerifierPhase3Spec extends AnyFlatSpec with Matchers {

  // ── Constants ────────────────────────────────────────────────────────────────

  private val ZeroKey = ByteString(Array.fill(32)(0x00.toByte))
  private val MaxKey = ByteString(Array.fill(32)(0xff.toByte))

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Keys = keccak256(i as big-endian int) — mirrors real ETC state trie distribution where account keys are
    * keccak256(address). Sorted ascending by unsigned byte order.
    */
  private def buildHashedAccounts(n: Int): Seq[(ByteString, Account)] =
    (0 until n)
      .map { i =>
        val keyBytes = kec256(ByteString(java.nio.ByteBuffer.allocate(4).putInt(i).array()))
        ByteString(keyBytes) -> Account(nonce = i.toLong, balance = i.toLong * 1000)
      }
      .sortBy { case (k, _) => BigInt(1, k.toArray) }

  /** Keys uniformly spaced across 2^256. Avoids clustering; exercises extension nodes throughout the trie. */
  private def buildUniformAccounts(n: Int): Seq[(ByteString, Account)] = {
    val step = BigInt(2).pow(256) / n
    (0 until n)
      .map { i =>
        val bigKey = step * i + step / 2
        val raw = bigKey.toByteArray
        val keyBytes = Array.fill(32)(0.toByte)
        val src = if (raw.length >= 32) raw.takeRight(32) else raw
        src.copyToArray(keyBytes, 32 - src.length)
        ByteString(keyBytes) -> Account(nonce = i.toLong, balance = i.toLong)
      }
      .sortBy { case (k, _) => BigInt(1, k.toArray) }
  }

  /** Build an MPT from accounts; return the trie and root hash. */
  private def buildMpt(accounts: Seq[(ByteString, Account)]): (MerklePatriciaTrie[ByteString, Account], ByteString) = {
    val storage = new TestMptStorage()
    val trie = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { case (t, (k, a)) => t.put(k, a) }
    trie -> ByteString(trie.getRootHash)
  }

  /** Generate boundary proof (path(root→firstKey) ∪ path(root→lastKey)), encoded as RLP bytes.
    *
    * Mirrors go-ethereum's trie.Prove(firstKey) + trie.Prove(lastKey). Works for non-existent keys — the trie walks as
    * far as it can and returns the partial path.
    */
  private def boundaryProof(
      trie: MerklePatriciaTrie[ByteString, Account],
      firstKey: ByteString,
      lastKey: ByteString
  ): Seq[ByteString] = {
    val firstNodes = trie.getProof(firstKey).getOrElse(Vector.empty)
    val lastNodes = trie.getProof(lastKey).getOrElse(Vector.empty)
    (firstNodes ++ lastNodes)
      .distinctBy(node => ByteString(node.hash))
      .map(node => ByteString(MptTraversals.encodeNode(node)))
  }

  /** Run block in an isolated thread; return None if it exceeds timeoutMs. Prevents the test suite from hanging when
    * Phase 3 stalls.
    */
  private def runWithTimeout[T](timeoutMs: Long)(block: => T): Option[T] = {
    val ex = Executors.newSingleThreadExecutor()
    try {
      val callable: Callable[T] = () => block
      val f: JFuture[T] = ex.submit(callable)
      try Some(f.get(timeoutMs, TimeUnit.MILLISECONDS))
      catch { case _: java.util.concurrent.TimeoutException => f.cancel(true); None }
    } finally ex.shutdown()
  }

  // ── Shared large fixtures (built once, reused across groups 1-2) ─────────────

  private lazy val hashedAccts4096: Seq[(ByteString, Account)] = buildHashedAccounts(4096)
  private lazy val (hashedTrie4096, hashedRoot4096): (MerklePatriciaTrie[ByteString, Account], ByteString) =
    buildMpt(hashedAccts4096)

  // ── Group 1: Scale tests — reproduce the Phase 3 stall ──────────────────────
  //
  // Mirrors go-ethereum Benchmark* sizes: 10/100/1000/5000.
  // Group 1 tests with >200 accounts FAIL with the current doInsertLeaf (timeout at 15s).
  // They PASS after the architectural fix (SnapHashTrie-based mutable insertion).

  "MerkleProofVerifierPhase3" should "complete full range for 50 hashed accounts within 1 second" taggedAs UnitTest in {
    val accts = buildHashedAccounts(50)
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)
    val result = runWithTimeout(1000L) {
      MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1)
    }
    withClue("Phase 3 timed out >1s for 50 accounts")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  it should "complete full range for 200 hashed accounts within 3 seconds" taggedAs UnitTest in {
    val accts = buildHashedAccounts(200)
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)
    val result = runWithTimeout(3000L) {
      MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1)
    }
    withClue("Phase 3 timed out >3s for 200 accounts")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  it should "complete full range for 1000 hashed accounts within 5 seconds" taggedAs UnitTest in {
    val accts = buildHashedAccounts(1000)
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)
    val result = runWithTimeout(5000L) {
      MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1)
    }
    withClue("Phase 3 timed out >5s for 1000 accounts — architectural fix not applied")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  it should "complete full range for 4096 hashed accounts within 15 seconds" taggedAs UnitTest in {
    val proof = boundaryProof(hashedTrie4096, hashedAccts4096.head._1, hashedAccts4096.last._1)
    val result = runWithTimeout(15000L) {
      MerkleProofVerifier(hashedRoot4096)
        .verifyAccountRange(hashedAccts4096, proof, hashedAccts4096.head._1, hashedAccts4096.last._1)
    }
    withClue("Phase 3 timed out >15s for 4096 accounts — architectural fix not applied")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  it should "complete full range for 4096 uniform accounts within 15 seconds" taggedAs UnitTest in {
    val accts = buildUniformAccounts(4096)
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)
    val result = runWithTimeout(15000L) {
      MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1)
    }
    withClue("Phase 3 timed out >15s for 4096 uniform accounts — architectural fix not applied") {
      result shouldBe defined
    }
    result.get shouldBe Right(())
  }

  // ── Group 2: Sub-range coverage — go-ethereum TestRangeProof pattern ─────────
  //
  // go-ethereum's primary test: from a 4096-key trie, verify random sub-ranges.
  // Fukuii equivalent: deterministic sub-ranges at 25%, 50%, 75%.
  // These also timeout with current code; pass after fix.

  it should "complete first-25-percent sub-range of 4096-account trie within 15 seconds" taggedAs UnitTest in {
    val sub = hashedAccts4096.take(hashedAccts4096.size / 4)
    val proof = boundaryProof(hashedTrie4096, sub.head._1, sub.last._1)
    val result = runWithTimeout(15000L) {
      MerkleProofVerifier(hashedRoot4096).verifyAccountRange(sub, proof, sub.head._1, sub.last._1)
    }
    withClue("Phase 3 timed out on first-25% sub-range")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  it should "complete middle-50-percent sub-range of 4096-account trie within 15 seconds" taggedAs UnitTest in {
    val n = hashedAccts4096.size
    val sub = hashedAccts4096.slice(n / 4, n * 3 / 4)
    val proof = boundaryProof(hashedTrie4096, sub.head._1, sub.last._1)
    val result = runWithTimeout(15000L) {
      MerkleProofVerifier(hashedRoot4096).verifyAccountRange(sub, proof, sub.head._1, sub.last._1)
    }
    withClue("Phase 3 timed out on middle-50% sub-range")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  it should "complete last-25-percent sub-range of 4096-account trie within 15 seconds" taggedAs UnitTest in {
    val sub = hashedAccts4096.drop(hashedAccts4096.size * 3 / 4)
    val proof = boundaryProof(hashedTrie4096, sub.head._1, sub.last._1)
    val result = runWithTimeout(15000L) {
      MerkleProofVerifier(hashedRoot4096).verifyAccountRange(sub, proof, sub.head._1, sub.last._1)
    }
    withClue("Phase 3 timed out on last-25% sub-range")(result shouldBe defined)
    result.get shouldBe Right(())
  }

  // ── Group 3: Non-existent edge proof — go-ethereum TestRangeProofWithNonExistentProof ──
  //
  // Besu's SNAP server uses startKeyHash = task.startHash (may not exist in trie).
  // go-ethereum: startKey = decreaseKey(actual_first_key).
  // Fukuii verifier must handle allowNonExistent=true in resolveEdgePath for both boundaries.

  it should "verify range when startHash is ZeroKey (non-existent left boundary proof)" taggedAs UnitTest in {
    val accts = buildHashedAccounts(100)
    val (trie, root) = buildMpt(accts)
    // ZeroKey is before all kec256-derived keys (kec256 hashes never collide with 0x00..00)
    val proof = boundaryProof(trie, ZeroKey, accts.last._1)
    MerkleProofVerifier(root).verifyAccountRange(
      accounts = accts,
      proof = proof,
      startHash = ZeroKey,
      endHash = accts.last._1
    ) shouldBe Right(())
  }

  it should "verify range when endHash is MaxKey (non-existent right boundary proof)" taggedAs UnitTest in {
    val accts = buildHashedAccounts(100)
    val (trie, root) = buildMpt(accts)
    // MaxKey is after all kec256-derived keys
    val proof = boundaryProof(trie, accts.head._1, MaxKey)
    MerkleProofVerifier(root).verifyAccountRange(
      accounts = accts,
      proof = proof,
      startHash = accts.head._1,
      endHash = MaxKey
    ) shouldBe Right(())
  }

  it should "verify range when both startHash and endHash are non-existent (ZeroKey to MaxKey)" taggedAs UnitTest in {
    val accts = buildHashedAccounts(50)
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, ZeroKey, MaxKey)
    MerkleProofVerifier(root).verifyAccountRange(
      accounts = accts,
      proof = proof,
      startHash = ZeroKey,
      endHash = MaxKey
    ) shouldBe Right(())
  }

  // ── Group 4: Single-element proof — go-ethereum TestOneElementRangeProof ──────

  it should "verify single-element range when firstKey equals lastKey (existent proof)" taggedAs UnitTest in {
    val accts = buildHashedAccounts(50)
    val target = accts(25)
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, target._1, target._1)
    MerkleProofVerifier(root).verifyAccountRange(Seq(target), proof, target._1, target._1) shouldBe Right(())
  }

  it should "verify single-element range with non-existent startHash" taggedAs UnitTest in {
    // Single-account trie: startHash (ZeroKey) is before the only account.
    // The range [ZeroKey..targetKey] contains exactly one account — no gap created.
    val targetKey = ByteString(kec256(ByteString("single-account-nonexistent-start")))
    val target = Account(nonce = 42)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage).put(targetKey, target)
    val proof = boundaryProof(trie, ZeroKey, targetKey)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      accounts = Seq(targetKey -> target),
      proof = proof,
      startHash = ZeroKey,
      endHash = targetKey
    ) shouldBe Right(())
  }

  it should "verify single-element range with non-existent endHash" taggedAs UnitTest in {
    // Single-account trie: endHash (MaxKey) is after the only account.
    val targetKey = ByteString(kec256(ByteString("single-account-nonexistent-end")))
    val target = Account(nonce = 43)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage).put(targetKey, target)
    val proof = boundaryProof(trie, targetKey, MaxKey)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      accounts = Seq(targetKey -> target),
      proof = proof,
      startHash = targetKey,
      endHash = MaxKey
    ) shouldBe Right(())
  }

  // ── Group 5: All-elements proof — go-ethereum TestAllElementsProof ────────────
  //
  // Nil proof = server returning complete trie. Fukuii takes the SnapHashTrie (streaming hash) path.

  it should "accept nil proof for 200 hashed accounts when hash matches" taggedAs UnitTest in {
    val accts = buildHashedAccounts(200)
    val snapTrie = new SnapHashTrie(_ => ())
    accts.foreach { case (k, a) => snapTrie.update(k.toArray, Account.accountSerializer.toBytes(a)) }
    val root = snapTrie.commit()
    MerkleProofVerifier(root).verifyAccountRange(
      accounts = accts,
      proof = Seq.empty,
      startHash = ZeroKey,
      endHash = MaxKey
    ) shouldBe Right(())
  }

  it should "reject nil proof for 200 accounts when hash does not match" taggedAs UnitTest in {
    val accts = buildHashedAccounts(200)
    val wrongRoot = ByteString(kec256(ByteString("wrong-root-intentionally")))
    MerkleProofVerifier(wrongRoot).verifyAccountRange(
      accounts = accts,
      proof = Seq.empty,
      startHash = ZeroKey,
      endHash = MaxKey
    ) shouldBe a[Left[_, _]]
  }

  // ── Group 6: Bad proof mutations — go-ethereum TestBadRangeProof ──────────────
  //
  // Six distinct mutations; every one must produce Left.

  private val badProofAccts: Seq[(ByteString, Account)] = buildHashedAccounts(50)
  private val (badProofTrie, badProofRoot): (MerklePatriciaTrie[ByteString, Account], ByteString) =
    buildMpt(badProofAccts)
  private val badProofFull: Seq[ByteString] =
    boundaryProof(badProofTrie, badProofAccts.head._1, badProofAccts.last._1)

  it should "detect tampered key in the middle of a valid range" taggedAs UnitTest in {
    val tamperedKey = ByteString(kec256(ByteString("definitely-not-in-trie")))
    val mid = 25
    val tampered = badProofAccts.updated(mid, tamperedKey -> badProofAccts(mid)._2)
    MerkleProofVerifier(badProofRoot).verifyAccountRange(
      tampered,
      badProofFull,
      badProofAccts.head._1,
      badProofAccts.last._1
    ) shouldBe a[Left[_, _]]
  }

  it should "detect tampered value in the middle of a valid range" taggedAs UnitTest in {
    val wrongAcct = Account(nonce = 99999, balance = 0)
    val tampered = badProofAccts.updated(25, badProofAccts(25)._1 -> wrongAcct)
    MerkleProofVerifier(badProofRoot).verifyAccountRange(
      tampered,
      badProofFull,
      badProofAccts.head._1,
      badProofAccts.last._1
    ) shouldBe a[Left[_, _]]
  }

  it should "detect omitted account (gap) in the middle of a valid range" taggedAs UnitTest in {
    val gapped = badProofAccts.take(25) ++ badProofAccts.drop(26)
    MerkleProofVerifier(badProofRoot).verifyAccountRange(
      gapped,
      badProofFull,
      badProofAccts.head._1,
      badProofAccts.last._1
    ) shouldBe a[Left[_, _]]
  }

  it should "detect out-of-order accounts (swap two adjacent entries)" taggedAs UnitTest in {
    val swapped = badProofAccts
      .updated(24, badProofAccts(25))
      .updated(25, badProofAccts(24))
    MerkleProofVerifier(badProofRoot).verifyAccountRange(
      swapped,
      badProofFull,
      badProofAccts.head._1,
      badProofAccts.last._1
    ) shouldBe a[Left[_, _]]
  }

  it should "detect proof built from different trie (wrong proof nodes)" taggedAs UnitTest in {
    val otherAccts = buildHashedAccounts(50).map { case (k, _) => k -> Account(nonce = 999) }
    val (otherTrie, _) = buildMpt(otherAccts)
    val wrongProof = boundaryProof(otherTrie, otherAccts.head._1, otherAccts.last._1)
    MerkleProofVerifier(badProofRoot).verifyAccountRange(
      badProofAccts,
      wrongProof,
      badProofAccts.head._1,
      badProofAccts.last._1
    ) shouldBe a[Left[_, _]]
  }

  it should "detect extra fabricated account added to the range" taggedAs UnitTest in {
    // Duplicate an existing entry — monotonicity check fires immediately
    val withDuplicate = badProofAccts.take(26) ++ badProofAccts.slice(25, 26) ++ badProofAccts.drop(26)
    MerkleProofVerifier(badProofRoot).verifyAccountRange(
      withDuplicate,
      badProofFull,
      badProofAccts.head._1,
      badProofAccts.last._1
    ) shouldBe a[Left[_, _]]
  }

  // ── Group 7: Gapped range — go-ethereum TestGappedRangeProof ─────────────────
  //
  // 10 sequential keys; return [2..8] but skip index 5. Gap must be detected via hash mismatch.

  it should "detect missing account in the middle of a sequential sub-range (gapped)" taggedAs UnitTest in {
    val keys = (0 until 10).map { i =>
      ByteString(Array.fill(31)(0.toByte) :+ i.toByte) -> Account(nonce = i.toLong)
    }
    val storage = new TestMptStorage()
    val trie = keys.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { case (t, (k, a)) => t.put(k, a) }
    val root = ByteString(trie.getRootHash)
    val subRange = keys.slice(2, 9) // indices 2..8
    val proof = boundaryProof(trie, subRange.head._1, subRange.last._1)

    // Full sub-range passes:
    MerkleProofVerifier(root).verifyAccountRange(subRange, proof, subRange.head._1, subRange.last._1) shouldBe Right(())

    // Remove index 5 → gap detected:
    val missingKey = ByteString(Array.fill(31)(0.toByte) :+ 5.toByte)
    val gapped = subRange.filterNot(_._1 == missingKey)
    MerkleProofVerifier(root)
      .verifyAccountRange(gapped, proof, subRange.head._1, subRange.last._1) shouldBe a[Left[_, _]]
  }

  // ── Group 8: Shared-prefix keys — go-ethereum TestRangeProofKeysWithSharedPrefix ──
  //
  // Keys 0xAA10...0000 and 0xAA20...0000 — forces insertIntoLeaf to create ExtensionNode + BranchNode.

  it should "verify range for two keys sharing 0xAA prefix (ExtensionNode insertion path)" taggedAs UnitTest in {
    val key1 = ByteString(0xaa.toByte +: 0x10.toByte +: Array.fill(30)(0.toByte))
    val key2 = ByteString(0xaa.toByte +: 0x20.toByte +: Array.fill(30)(0.toByte))
    val acct1 = Account(nonce = 1)
    val acct2 = Account(nonce = 2)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage).put(key1, acct1).put(key2, acct2)
    val proof = boundaryProof(trie, key1, key2)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      Seq(key1 -> acct1, key2 -> acct2),
      proof,
      key1,
      key2
    ) shouldBe Right(())
  }

  it should "verify range for keys sharing 31 of 32 bytes (deep common prefix)" taggedAs UnitTest in {
    val key1 = ByteString(Array.fill(31)(0xab.toByte) :+ 0x00.toByte)
    val key2 = ByteString(Array.fill(31)(0xab.toByte) :+ 0xff.toByte)
    val acct1 = Account(nonce = 1)
    val acct2 = Account(nonce = 2)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage).put(key1, acct1).put(key2, acct2)
    val proof = boundaryProof(trie, key1, key2)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      Seq(key1 -> acct1, key2 -> acct2),
      proof,
      key1,
      key2
    ) shouldBe Right(())
  }

  // ── Group 9: doInsertLeaf structural path coverage ───────────────────────────
  //
  // Each test targets a specific branch in doInsertLeaf/insertIntoLeaf/insertIntoExtension.
  // All use small tries so they're fast and precisely targeted.

  it should "insert into NullNode (empty subtrie created by Phase-2 pruning)" taggedAs UnitTest in {
    // 3 accounts with sequential nibble-distinct keys; pruning removes middle → NullNode
    val key1 = ByteString(Array(0x10.toByte) ++ Array.fill(31)(0.toByte))
    val key2 = ByteString(Array(0x20.toByte) ++ Array.fill(31)(0.toByte))
    val key3 = ByteString(Array(0x30.toByte) ++ Array.fill(31)(0.toByte))
    val acct = Account(nonce = 1)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(key1, acct)
      .put(key2, acct)
      .put(key3, acct)
    val proof = boundaryProof(trie, key1, key3)
    // All 3 re-inserted; key2 hits NullNode after pruning
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      Seq(key1 -> acct, key2 -> acct, key3 -> acct),
      proof,
      key1,
      key3
    ) shouldBe Right(())
  }

  it should "insert into LeafNode with no common prefix (first nibble diverges → BranchNode)" taggedAs UnitTest in {
    val key1 = ByteString(Array(0x10.toByte) ++ Array.fill(31)(0.toByte))
    val key2 = ByteString(Array(0x20.toByte) ++ Array.fill(31)(0.toByte))
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(key1, Account(nonce = 1))
      .put(key2, Account(nonce = 2))
    val proof = boundaryProof(trie, key1, key2)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      Seq(key1 -> Account(nonce = 1), key2 -> Account(nonce = 2)),
      proof,
      key1,
      key2
    ) shouldBe Right(())
  }

  it should "insert into BranchNode with 16 children (all nibble positions occupied)" taggedAs UnitTest in {
    val accts = (0 until 16)
      .map { i =>
        ByteString(Array(i.toByte) ++ Array.fill(31)(0.toByte)) -> Account(nonce = i.toLong)
      }
      .sortBy { case (k, _) => BigInt(1, k.toArray) }
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)
    MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1) shouldBe Right(())
  }

  it should "insert where ExtensionNode fully covers the key prefix (ml == sharedNibbles.length)" taggedAs UnitTest in {
    // 3 accounts: two share long extension (0xFF...prefix), one branches off it
    val key1 = ByteString(Array.fill(16)(0xff.toByte) ++ Array.fill(15)(0.toByte) :+ 0x01.toByte)
    val key2 = ByteString(Array.fill(16)(0xff.toByte) ++ Array.fill(15)(0.toByte) :+ 0x02.toByte)
    val key3 = ByteString(Array.fill(16)(0xff.toByte) ++ Array.fill(15)(0.toByte) :+ 0x03.toByte)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(key1, Account(nonce = 1))
      .put(key2, Account(nonce = 2))
      .put(key3, Account(nonce = 3))
    val proof = boundaryProof(trie, key1, key3)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      Seq(key1 -> Account(nonce = 1), key2 -> Account(nonce = 2), key3 -> Account(nonce = 3)),
      proof,
      key1,
      key3
    ) shouldBe Right(())
  }

  it should "insert where ExtensionNode has no common prefix with key (ml == 0 → split)" taggedAs UnitTest in {
    // key3(0x40) < key1(0x80...00) < key2(0x80...01)
    // key1 and key2 share first nibble 8 → BranchNode at root, then ExtensionNode under child[8]
    // key3 is under child[4] — diverges from ExtensionNode at child[8] with ml=0 in insertIntoExtension
    val key1 = ByteString(Array(0x80.toByte) ++ Array.fill(31)(0.toByte))
    val key2 = ByteString(Array(0x80.toByte) ++ Array.fill(30)(0.toByte) :+ 0x01.toByte)
    val key3 = ByteString(Array(0x40.toByte) ++ Array.fill(31)(0.toByte))
    val acct1 = Account(nonce = 1)
    val acct2 = Account(nonce = 2)
    val acct3 = Account(nonce = 3)
    val storage = new TestMptStorage()
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(key1, acct1)
      .put(key2, acct2)
      .put(key3, acct3)
    // Sorted: key3(0x40) < key1(0x80...00) < key2(0x80...01)
    val accts = Seq(key3 -> acct3, key1 -> acct1, key2 -> acct2)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)
    MerkleProofVerifier(ByteString(trie.getRootHash)).verifyAccountRange(
      accts,
      proof,
      accts.head._1,
      accts.last._1
    ) shouldBe Right(())
  }

  // ── Group 10: Regression guards ─────────────────────────────────────────────

  it should "not cycle when same proof bytes are used for two independent verifications (77b9fa222)" taggedAs UnitTest in {
    // Regression guard for commit 77b9fa222: shared MptNode reference between two resolveEdgePath calls
    // caused a cycle in Phase 3. Fresh decode per HashNode lookup eliminates this.
    val accts = (0 until 16)
      .map { i =>
        ByteString(Array(i.toByte) ++ Array.fill(31)(0.toByte)) -> Account(nonce = i.toLong)
      }
      .sortBy { case (k, _) => BigInt(1, k.toArray) }
    val (trie, root) = buildMpt(accts)
    val proof = boundaryProof(trie, accts.head._1, accts.last._1)

    val r1 = MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1)
    val r2 = MerkleProofVerifier(root).verifyAccountRange(accts, proof, accts.head._1, accts.last._1)
    r1 shouldBe Right(())
    r2 shouldBe Right(())
  }

  it should "exhibit at most linear Phase 3 growth (10x accounts < 25x longer)" taggedAs UnitTest in {
    // Discriminates B1 (O(N×depth), uniform per-leaf cost) from B3 (O(N²), growing per-leaf cost).
    // With current code this test passes but 4096-account tests timeout — confirms B1 not B3.
    // After fix: ms1000 ≈ 10 × ms100, not 100×.
    val accts100 = buildHashedAccounts(100)
    val accts1000 = buildHashedAccounts(1000)
    val (trie100, root100) = buildMpt(accts100)
    val (trie1000, root1000) = buildMpt(accts1000)
    val proof100 = boundaryProof(trie100, accts100.head._1, accts100.last._1)
    val proof1000 = boundaryProof(trie1000, accts1000.head._1, accts1000.last._1)

    val t0 = System.currentTimeMillis()
    MerkleProofVerifier(root100).verifyAccountRange(
      accts100,
      proof100,
      accts100.head._1,
      accts100.last._1
    ) shouldBe Right(())
    val ms100 = System.currentTimeMillis() - t0

    val t1 = System.currentTimeMillis()
    MerkleProofVerifier(root1000).verifyAccountRange(
      accts1000,
      proof1000,
      accts1000.head._1,
      accts1000.last._1
    ) shouldBe Right(())
    val ms1000 = System.currentTimeMillis() - t1

    withClue(s"100 accts=${ms100}ms, 1000 accts=${ms1000}ms — quadratic growth detected (B3 hypothesis confirmed)") {
      ms1000 should be < (ms100 * 25L).max(5000L)
    }
  }
}

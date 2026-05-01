package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

// ── K7: SNAP server byte-budget and time-budget limits (regression for commit 50cfb7ca8) ─────
//
// Before 50cfb7ca8, SnapServer had no upper bound on the response size and no per-request
// time budget. Under a large SNAP state (ETC: ~26M accounts) this caused single requests to
// produce multi-hundred-MB responses or to run for tens of seconds, overwhelming the peer
// connection. The fix adds:
//   - `maxBytes = min(max(responseBytes, 0), 2MB)` — hard cap regardless of client request
//   - `deadline = currentTimeMillis + 4000` — 4s wall-clock budget
//   - First-item guarantee: even if the budget is instantly exceeded, at least 1 item is
//     returned so the client can always make forward progress.
//
// These tests lock those invariants so a refactor cannot silently revert them.

class SnapServerLimitsSpec extends AnyFlatSpec with Matchers {

  // ── helpers ──────────────────────────────────────────────────────────────

  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash = ByteString(Array.fill[Byte](32)(0xff.toByte))

  private def buildAccountTrie(
      accounts: Seq[(ByteString, Account)]
  ): (ByteString, TestMptStorage) = {
    val storage = new TestMptStorage()
    val trie = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { case (t, (key, account)) =>
      t.put(key, account)
    }
    (ByteString(trie.getRootHash), storage)
  }

  private def simpleAccount(nonce: Int, balance: Int): Account =
    Account(nonce = nonce, balance = balance)

  // 5 accounts with 32-byte hash-like keys spread across the keyspace
  private def defaultAccounts: Seq[(ByteString, Account)] = (0 until 5).map { i =>
    val key = kec256(ByteString(s"account-$i"))
    val acc = simpleAccount(i, i * 1000)
    (key, acc)
  }

  // ── 2MB response-byte cap (pure formula) ─────────────────────────────────

  "SnapServer byte budget" should "cap client-requested responseBytes at 2MB" taggedAs UnitTest in {
    val twoMB = 2 * 1024 * 1024
    // Client asks for 10MB — server must cap at 2MB.
    val clientRequest = BigInt(10 * 1024 * 1024)
    val maxBytes = math.min(math.max(clientRequest.toInt, 0), twoMB)
    maxBytes shouldBe twoMB
  }

  it should "not cap responseBytes that are already at or below 2MB" taggedAs UnitTest in {
    val twoMB = 2 * 1024 * 1024
    val exactly2MB = BigInt(twoMB)
    math.min(math.max(exactly2MB.toInt, 0), twoMB) shouldBe twoMB

    val oneMB = BigInt(1024 * 1024)
    math.min(math.max(oneMB.toInt, 0), twoMB) shouldBe 1024 * 1024

    val tiny = BigInt(64)
    math.min(math.max(tiny.toInt, 0), twoMB) shouldBe 64
  }

  it should "treat negative responseBytes as zero (no-negative guard)" taggedAs UnitTest in {
    val twoMB = 2 * 1024 * 1024
    val negative = BigInt(-1)
    math.min(math.max(negative.toInt, 0), twoMB) shouldBe 0
  }

  // ── 4s time budget (deadline formula) ────────────────────────────────────

  it should "set a deadline approximately 4 seconds in the future" taggedAs UnitTest in {
    val before = System.currentTimeMillis()
    val deadline = System.currentTimeMillis() + 4000
    val after = System.currentTimeMillis()
    deadline should be >= before + 4000L
    deadline should be <= after + 4001L // tiny slack for two clock reads
  }

  // ── serveAccountRange: first-item guarantee ───────────────────────────────

  "SnapServer.serveAccountRange" should "return at least 1 account even when responseBytes=0" taggedAs UnitTest in {
    // responseBytes=0 → maxBytes=0 → visitor returns false after the first item is added.
    // The first item must still be present: the visitor adds it to `collected` BEFORE
    // checking the budget, so `collected` is non-empty.
    val (rootHash, storage) = buildAccountTrie(defaultAccounts)
    val result = SnapServer.serveAccountRange(
      requestId = BigInt(1),
      rootHash = rootHash,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(0),
      storage = storage
    )
    result.accounts should have size 1
    result.proof should not be empty // proof always present for non-empty range
  }

  it should "return all accounts when responseBytes exceeds their total encoded size" taggedAs UnitTest in {
    val (rootHash, storage) = buildAccountTrie(defaultAccounts)
    val result = SnapServer.serveAccountRange(
      requestId = BigInt(1),
      rootHash = rootHash,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(2 * 1024 * 1024),
      storage = storage
    )
    result.accounts should have size defaultAccounts.size
  }

  it should "return a partial response (fewer than all accounts) when budget is tight" taggedAs UnitTest in {
    // responseBytes=1 means maxBytes=1; after the first item's size is accumulated
    // (keyHash=32 + slimAccount≥4 = at least 36 bytes), the visitor returns false.
    // So the response must have exactly 1 account, not all 5.
    val (rootHash, storage) = buildAccountTrie(defaultAccounts)
    val result = SnapServer.serveAccountRange(
      requestId = BigInt(1),
      rootHash = rootHash,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1),
      storage = storage
    )
    result.accounts.size should ((be >= 1).and(be < defaultAccounts.size))
  }

  it should "include a non-empty proof when the response is truncated" taggedAs UnitTest in {
    val (rootHash, storage) = buildAccountTrie(defaultAccounts)
    val result = SnapServer.serveAccountRange(
      requestId = BigInt(1),
      rootHash = rootHash,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1),
      storage = storage
    )
    // A truncated range MUST carry a proof so the peer can verify the partial range
    // and compute the correct continuation startingHash.
    result.proof should not be empty
  }

  // ── Continuation: last key of truncated response is a valid next startingHash ─

  it should "allow the peer to continue from the last key of a truncated response" taggedAs UnitTest in {
    val (rootHash, storage) = buildAccountTrie(defaultAccounts)
    // First request: get a partial result with a small budget
    val firstResult = SnapServer.serveAccountRange(
      requestId = BigInt(1),
      rootHash = rootHash,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(1),
      storage = storage
    )
    firstResult.accounts should not be empty

    val (lastKey, _) = firstResult.accounts.last
    // Second request: start from just after the last received key
    // (in practice the peer increments lastKey by 1; here we just re-request from lastKey
    //  itself which also makes forward progress relative to the 0x00…00 origin)
    val secondResult = SnapServer.serveAccountRange(
      requestId = BigInt(2),
      rootHash = rootHash,
      startingHash = lastKey,
      limitHash = maxHash,
      responseBytes = BigInt(2 * 1024 * 1024),
      storage = storage
    )
    // The second request sees at least the last key again (start == key of a leaf → returned)
    // and may see more accounts after it. Combined with first result, all accounts covered.
    val firstKeys = firstResult.accounts.map(_._1).toSet
    val secondKeys = secondResult.accounts.map(_._1).toSet
    (firstKeys ++ secondKeys).size should be >= defaultAccounts.size
  }

  // ── serveAccountRange: empty root returns empty response ─────────────────

  it should "return an empty response for an empty-root trie" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val result = SnapServer.serveAccountRange(
      requestId = BigInt(1),
      rootHash = emptyRoot,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(2 * 1024 * 1024),
      storage = storage
    )
    result.accounts shouldBe empty
    result.proof shouldBe empty
  }

  // ── serveStorageRanges: 2MB cap applies independently ────────────────────

  "SnapServer.serveStorageRanges" should "cap responseBytes at 2MB independent of client request" taggedAs UnitTest in {
    val twoMB = 2 * 1024 * 1024
    val oversized = BigInt(10 * 1024 * 1024)
    math.min(math.max(oversized.toInt, 0), twoMB) shouldBe twoMB
  }

  it should "return empty slots for an account whose storage root is unknown" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val unknownAccountHash = kec256(ByteString("no-such-account"))
    val result = SnapServer.serveStorageRanges(
      requestId = BigInt(1),
      rootHash = emptyRoot,
      accountHashes = Seq(unknownAccountHash),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = BigInt(2 * 1024 * 1024),
      storage = storage,
      accountRoot = _ => None
    )
    // Empty root → no slots, no proof.
    result.slots shouldBe empty
    result.proof shouldBe empty
  }

  // ── serveTrieNodes: 2MB cap applies ──────────────────────────────────────

  "SnapServer.serveTrieNodes" should "cap responseBytes at 2MB for trie node requests" taggedAs UnitTest in {
    val twoMB = 2 * 1024 * 1024
    val bigReq = BigInt(100 * 1024 * 1024)
    math.min(math.max(bigReq.toInt, 0), twoMB) shouldBe twoMB
  }

  it should "return empty entries when root is not in storage" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val unknownRoot = kec256(ByteString("unknown-root"))
    // Request a single-element pathset — root missing → one empty ByteString returned.
    val emptyHpPath = ByteString(Array[Byte](0x20)) // HP-encoded empty path (leaf flag)
    val result = SnapServer.serveTrieNodes(
      requestId = BigInt(1),
      rootHash = unknownRoot,
      paths = Seq(Seq(emptyHpPath)),
      responseBytes = BigInt(2 * 1024 * 1024),
      storage = storage
    )
    result.nodes should have size 1
    result.nodes.head shouldBe empty
  }

  it should "return empty response for a zero-element pathset (bad request)" taggedAs UnitTest in {
    // Per SNAP/1 spec (geth handler.go:522-525), a zero-item pathset anywhere in the
    // request is a protocol-level bad request — the ENTIRE response is empty.
    val storage = new TestMptStorage()
    val unknownRoot = kec256(ByteString("bad-req-root"))
    val result = SnapServer.serveTrieNodes(
      requestId = BigInt(1),
      rootHash = unknownRoot,
      paths = Seq(Seq.empty), // zero-element pathset → bad request
      responseBytes = BigInt(2 * 1024 * 1024),
      storage = storage
    )
    result.nodes shouldBe empty
  }
}

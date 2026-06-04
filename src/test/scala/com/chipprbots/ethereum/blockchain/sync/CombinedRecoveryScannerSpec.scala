package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits.AccountEnc
import com.chipprbots.ethereum.testing.Tags._

/** CombinedRecoveryScanner is the cohesive integration of ShardEnumerator (#1) + CombinedRecoveryScan (#2) +
  * RecoveryProgress (#3). Its correctness bar:
  *   - EQUIVALENCE: the sharded (and parallel) scan finds EXACTLY the gaps a single whole-trie pass finds — the
  *     partition + merge must neither miss nor invent a gap, sequentially or under concurrency.
  *   - RESUME: a crash mid-scan resumes from the last persisted shard, re-scans only the remaining shards, and yields
  *     the same final gap set as an uninterrupted run — the load-bearing flaky-system guarantee.
  *   - DEDUP: a storage root referenced by accounts in different shards is reported once (driver-level cross-shard
  *     dedup).
  *   - DOWNLOAD-RESUME: a complete checkpoint skips the scan entirely and returns the persisted gaps.
  */
class CombinedRecoveryScannerSpec extends AnyFunSuite {

  /** A fresh in-memory state + EVM store with helpers to plant present/missing bytecode and storage. */
  private class Fixture {
    val ds = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
    val evm = new EvmCodeStorage(ds)
    private val build = stateStorage.getBackingStorage(0)

    /** Fresh per-shard handle, as production uses (getBackingStorage(pivot)). */
    def handle(): MptStorage = stateStorage.getBackingStorage(0)

    def presentCode(seed: Int): ByteString = {
      val code = Array.fill[Byte](8)(seed.toByte)
      val h = ByteString(kec256(code))
      evm.put(h, ByteString(code)).commit()
      h
    }
    def missingCodeHash(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x5a)))

    def presentStorageRoot(seed: Int): ByteString =
      ByteString(
        MerklePatriciaTrie[Array[Byte], Array[Byte]](build)
          .put(Array[Byte](seed.toByte, 0x01), Array[Byte](seed.toByte, 0x11))
          .put(Array[Byte](seed.toByte, 0x02), Array[Byte](seed.toByte, 0x22))
          .getRootHash
      )
    def missingStorageRoot(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x3c)))

    /** Account key keccak(seed) — uniformly spread across first nibbles, like real state. */
    def acct(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte)))

    /** Account key with an explicit first nibble (to force two accounts into different shards). */
    def acctWithNibble(nibble: Int, tag: Int): ByteString =
      ByteString(Array.tabulate[Byte](32)(i => if (i == 0) (nibble << 4).toByte else if (i == 1) tag.toByte else 0))

    def stateRootOf(accounts: Seq[(ByteString, Account)]): ByteString =
      ByteString(
        accounts
          .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](build)) { case (t, (h, a)) =>
            t.put(h.toArray, a.toBytes)
          }
          .getRootHash
      )
  }

  private def acc(storageRoot: ByteString, codeHash: ByteString): Account =
    Account(nonce = UInt256.Zero, storageRoot = storageRoot, codeHash = codeHash)

  /** The proven single whole-trie pass (Task #2), used as the equivalence reference. */
  private def referenceGaps(f: Fixture, stateRoot: ByteString): (Set[ByteString], Set[(ByteString, ByteString)]) = {
    val ref = new CombinedRecoveryScan(f.handle(), f.evm)
    ref.scanFrom(stateRoot)
    (ref.missingBytecodes.toSet, ref.missingStorageTries.toSet)
  }

  /** A trie spanning several shards with a mix of present/missing bytecode and storage, every storageRoot unique. */
  private def gappyState(f: Fixture): ByteString =
    f.stateRootOf(
      Seq(
        f.acct(1) -> acc(f.presentStorageRoot(1), f.presentCode(1)), // all present
        f.acct(2) -> acc(Account.EmptyStorageRootHash, f.missingCodeHash(2)), // missing code
        f.acct(3) -> acc(f.missingStorageRoot(3), Account.EmptyCodeHash), // missing storage
        f.acct(4) -> acc(f.missingStorageRoot(4), f.presentCode(4)), // missing storage, present code
        f.acct(5) -> acc(f.presentStorageRoot(5), f.missingCodeHash(5)), // present storage, missing code
        f.acct(6) -> acc(f.presentStorageRoot(6), Account.EmptyCodeHash), // present
        f.acct(7) -> Account.empty() // EOA
      )
    )

  test("sequential sharded scan finds exactly the single-pass gaps", UnitTest, SyncTest) {
    val f = new Fixture
    val root = gappyState(f)
    val (refCode, refStorage) = referenceGaps(f, root)

    val r = new CombinedRecoveryScanner(root, () => f.handle(), f.evm, new AppStateStorage(EphemDataSource())).run()
    assert(r.missingBytecodes.toSet == refCode)
    assert(r.missingStorageTries.toSet == refStorage)
    assert(r.missingBytecodes.size == r.missingBytecodes.distinct.size, "no duplicate bytecode gaps")
    assert(r.missingStorageTries.size == r.missingStorageTries.distinct.size, "no duplicate storage gaps")
  }

  test("parallel sharded scan (concurrency=4) finds exactly the single-pass gaps", UnitTest, SyncTest) {
    val f = new Fixture
    val root = gappyState(f)
    val (refCode, refStorage) = referenceGaps(f, root)

    val r = new CombinedRecoveryScanner(
      root,
      () => f.handle(),
      f.evm,
      new AppStateStorage(EphemDataSource()),
      concurrency = 4
    ).run()
    assert(r.missingBytecodes.toSet == refCode)
    assert(r.missingStorageTries.toSet == refStorage)
  }

  test(
    "resumes from the last completed shard after a crash, same final gaps, no re-scan of done shards",
    UnitTest,
    SyncTest
  ) {
    val f = new Fixture
    // One account per nibble 1..8 → 8 shards, each with a missing storage gap.
    val root =
      f.stateRootOf((1 to 8).map(n => f.acctWithNibble(n, 0) -> acc(f.missingStorageRoot(n), Account.EmptyCodeHash)))
    val (refCode, refStorage) = referenceGaps(f, root)

    val app = new AppStateStorage(EphemDataSource())
    // Crash right after the first shard's progress is persisted.
    val crashing =
      new CombinedRecoveryScanner(
        root,
        () => f.handle(),
        f.evm,
        app,
        onShardPersisted = n => if (n == 1) throw new RuntimeException("boom")
      )
    intercept[RuntimeException](crashing.run())

    val afterCrash = app.getRecoveryProgress()
    assert(afterCrash.exists(_.completedShards.size == 1), "exactly one shard should be persisted after the crash")
    val doneIdx = afterCrash.get.completedShards.head

    // Resume on the SAME store; record which shards it actually re-scans.
    val rescanned = mutable.ArrayBuffer.empty[Int]
    val resumed =
      new CombinedRecoveryScanner(
        root,
        () => f.handle(),
        f.evm,
        app,
        onShardScanStart = i => rescanned.synchronized(rescanned += i)
      ).run()

    assert(!rescanned.contains(doneIdx), s"resume must not re-scan the already-completed shard $doneIdx")
    assert(rescanned.size == afterCrash.get.shardCount - 1, "resume must scan exactly the remaining shards")
    assert(resumed.missingBytecodes.toSet == refCode)
    assert(resumed.missingStorageTries.toSet == refStorage)
  }

  test("a storage root shared across shards is reported once (cross-shard dedup)", UnitTest, SyncTest) {
    val f = new Fixture
    val sharedRoot = f.missingStorageRoot(99)
    // Two accounts, forced into DIFFERENT shards (nibble 1 vs 15), both missing the SAME storage root.
    val root = f.stateRootOf(
      Seq(
        f.acctWithNibble(1, 1) -> acc(sharedRoot, Account.EmptyCodeHash),
        f.acctWithNibble(15, 2) -> acc(sharedRoot, Account.EmptyCodeHash)
      )
    )
    val r = new CombinedRecoveryScanner(
      root,
      () => f.handle(),
      f.evm,
      new AppStateStorage(EphemDataSource()),
      concurrency = 2
    ).run()
    assert(
      r.missingStorageTries.count(_._2 == sharedRoot) == 1,
      "shared missing storage root must be deduped to one entry"
    )
    assert(r.missingStorageTries.size == 1)
  }

  test("a complete checkpoint skips the scan and returns persisted gaps (download resume)", UnitTest, SyncTest) {
    val f = new Fixture
    val root = gappyState(f)
    val app = new AppStateStorage(EphemDataSource())
    val full = new CombinedRecoveryScanner(root, () => f.handle(), f.evm, app).run()
    assert(app.getRecoveryProgress().exists(_.isComplete))

    var scanned = false
    val again =
      new CombinedRecoveryScanner(root, () => f.handle(), f.evm, app, onShardScanStart = _ => scanned = true).run()
    assert(!scanned, "a complete checkpoint must not trigger any shard walk")
    assert(again.missingBytecodes.toSet == full.missingBytecodes.toSet)
    assert(again.missingStorageTries.toSet == full.missingStorageTries.toSet)
  }

  test("empty trie → no gaps and no checkpoint persisted", UnitTest, SyncTest) {
    val f = new Fixture
    val app = new AppStateStorage(EphemDataSource())
    val r =
      new CombinedRecoveryScanner(ByteString(MerklePatriciaTrie.EmptyRootHash), () => f.handle(), f.evm, app).run()
    assert(r.missingBytecodes.isEmpty && r.missingStorageTries.isEmpty)
    assert(app.getRecoveryProgress().isEmpty, "an empty trie must not persist a checkpoint")
  }
}

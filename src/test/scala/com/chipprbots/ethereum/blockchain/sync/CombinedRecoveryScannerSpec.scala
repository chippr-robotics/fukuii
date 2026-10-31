package com.chipprbots.ethereum.blockchain.sync

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.testing.Tags.*

/** CombinedRecoveryScanner is the cohesive integration of ShardEnumerator (#1) + CombinedRecoveryScan (#2) +
  * RecoveryProgress (#3). Its correctness bar:
  *   - EQUIVALENCE: the sharded (and parallel) scan finds EXACTLY the gaps a single whole-trie pass finds — the
  *     partition + merge must neither miss nor invent a gap, sequentially or under concurrency.
  *   - RESUME: a crash mid-scan resumes from the last persisted shard, re-scans only the remaining shards, and yields
  *     the same final gap set as an uninterrupted run — the load-bearing flaky-system guarantee.
  *   - DEDUP: a storage root referenced by accounts in different shards is reported once (driver-level cross-shard
  *     dedup).
  *   - DOWNLOAD-RESUME: a complete checkpoint skips the scan entirely and returns the persisted gaps.
  *   - HOLES (spec 022 T050a): an absent node below the root is counted and walked around, so the pass covers the rest
  *     of the trie. An absent root, or a node that does not decode, is a failure and not a hole.
  */
class CombinedRecoveryScannerSpec extends AnyFunSuite:

  /** A fresh in-memory state + EVM store with helpers to plant present/missing bytecode and storage. */
  private class Fixture:
    val ds: EphemDataSource = EphemDataSource()
    val (stateStorage, nodes, _) = StateStorage.createTestStateStorage(ds)
    val evm = new EvmCodeStorage(ds)
    private val build = stateStorage.getBackingStorage(0)

    /** Fresh per-shard handle, as production uses (getBackingStorage(pivot)). */
    def handle(): MptStorage = stateStorage.getBackingStorage(0)

    def presentCode(seed: Int): ByteString =
      val code = Array.fill[Byte](8)(seed.toByte)
      val h = ByteString(kec256(code))
      evm.put(h, ByteString(code)).commit()
      h
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
      ByteString(
        Array.tabulate[Byte](32)(i => if i == 0 then (nibble << 4).toByte else if i == 1 then tag.toByte else 0)
      )

    def stateRootOf(accounts: Seq[(ByteString, Account)]): ByteString =
      ByteString(
        accounts
          .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](build)) { case (t, (h, a)) =>
            t.put(h.toArray, a.toBytes)
          }
          .getRootHash
      )

  private def acc(storageRoot: TrieRoot, codeHash: CodeHash): Account =
    Account(nonce = UInt256.Zero, storageRoot = storageRoot, codeHash = codeHash)

  /** The proven single whole-trie pass (Task #2), used as the equivalence reference. */
  private def referenceGaps(f: Fixture, stateRoot: ByteString): (Set[ByteString], Set[(ByteString, ByteString)]) =
    val ref = new CombinedRecoveryScan(f.handle(), f.evm)
    ref.scanFrom(stateRoot)
    (ref.missingBytecodes.toSet, ref.missingStorageTries.toSet)

  /** A trie spanning several shards with a mix of present/missing bytecode and storage, every storageRoot unique. */
  private def gappyState(f: Fixture): ByteString =
    f.stateRootOf(
      Seq(
        f.acct(1) -> acc(TrieRoot(f.presentStorageRoot(1)), CodeHash(f.presentCode(1))), // all present
        f.acct(2) -> acc(Account.EmptyStorageRootHash, CodeHash(f.missingCodeHash(2))), // missing code
        f.acct(3) -> acc(TrieRoot(f.missingStorageRoot(3)), Account.EmptyCodeHash), // missing storage
        f.acct(4) -> acc(
          TrieRoot(f.missingStorageRoot(4)),
          CodeHash(f.presentCode(4))
        ), // missing storage, present code
        f.acct(5) -> acc(
          TrieRoot(f.presentStorageRoot(5)),
          CodeHash(f.missingCodeHash(5))
        ), // present storage, missing code
        f.acct(6) -> acc(TrieRoot(f.presentStorageRoot(6)), Account.EmptyCodeHash), // present
        f.acct(7) -> Account.empty() // EOA
      )
    )

  test("sequential sharded scan finds exactly the single-pass gaps", UnitTest) {
    val f = new Fixture
    val root = gappyState(f)
    val (refCode, refStorage) = referenceGaps(f, root)

    val r = new CombinedRecoveryScanner(root, () => f.handle(), f.evm, new AppStateStorage(EphemDataSource())).run()
    assert(r.missingBytecodes.toSet == refCode)
    assert(r.missingStorageTries.toSet == refStorage)
    assert(r.missingBytecodes.size == r.missingBytecodes.distinct.size, "no duplicate bytecode gaps")
    assert(r.missingStorageTries.size == r.missingStorageTries.distinct.size, "no duplicate storage gaps")
  }

  test("parallel sharded scan (concurrency=4) finds exactly the single-pass gaps", UnitTest) {
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
    UnitTest
  ) {
    val f = new Fixture
    // One account per nibble 1..8 → 8 shards, each with a missing storage gap.
    val root =
      f.stateRootOf(
        (1 to 8).map(n => f.acctWithNibble(n, 0) -> acc(TrieRoot(f.missingStorageRoot(n)), Account.EmptyCodeHash))
      )
    val (refCode, refStorage) = referenceGaps(f, root)

    val app = new AppStateStorage(EphemDataSource())
    // Crash right after the first shard's progress is persisted.
    val crashing =
      new CombinedRecoveryScanner(
        root,
        () => f.handle(),
        f.evm,
        app,
        onShardPersisted = n => if n == 1 then throw new RuntimeException("boom")
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

  test("a storage root shared across shards is reported once (cross-shard dedup)", UnitTest) {
    val f = new Fixture
    val sharedRoot = f.missingStorageRoot(99)
    // Two accounts, forced into DIFFERENT shards (nibble 1 vs 15), both missing the SAME storage root.
    val root = f.stateRootOf(
      Seq(
        f.acctWithNibble(1, 1) -> acc(TrieRoot(sharedRoot), Account.EmptyCodeHash),
        f.acctWithNibble(15, 2) -> acc(TrieRoot(sharedRoot), Account.EmptyCodeHash)
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

  test("a complete checkpoint skips the scan and returns persisted gaps (download resume)", UnitTest) {
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

  test("empty trie → no gaps and no checkpoint persisted", UnitTest) {
    val f = new Fixture
    val app = new AppStateStorage(EphemDataSource())
    val r =
      new CombinedRecoveryScanner(ByteString(MerklePatriciaTrie.EmptyRootHash), () => f.handle(), f.evm, app).run()
    assert(r.missingBytecodes.isEmpty && r.missingStorageTries.isEmpty)
    assert(app.getRecoveryProgress().isEmpty, "an empty trie must not persist a checkpoint")
  }

  /** Four contracts, each with something missing that only the walk can find:
    *   - code `underHole` and `beside` under first nibble 1, in sibling leaves one branch down;
    *   - code `alone` under nibble 2;
    *   - a storage root under nibble 3.
    */
  private class HoleFixture:
    val f = new Fixture
    val beside: ByteString = f.missingCodeHash(1)
    val underHole: ByteString = f.missingCodeHash(2)
    val alone: ByteString = f.missingCodeHash(3)
    val storageGap: ByteString = f.missingStorageRoot(4)
    val root: ByteString = f.stateRootOf(
      Seq(
        f.acctWithNibble(1, 0x10) -> acc(Account.EmptyStorageRootHash, CodeHash(beside)),
        f.acctWithNibble(1, 0x20) -> acc(Account.EmptyStorageRootHash, CodeHash(underHole)),
        f.acctWithNibble(2, 0x10) -> acc(Account.EmptyStorageRootHash, CodeHash(alone)),
        f.acctWithNibble(3, 0x10) -> acc(TrieRoot(storageGap), Account.EmptyCodeHash)
      )
    )

    private def branchAtOrBelow(node: MptNode): BranchNode = node match
      case b: BranchNode    => b
      case e: ExtensionNode => branchAtOrBelow(e.next)
      case h: HashNode      => branchAtOrBelow(f.handle().get(h.hash))
      case other            => fail(s"no branch below $other")

    private def hashOf(node: MptNode): ByteString = ByteString(node.asInstanceOf[HashNode].hash)

    private lazy val rootBranch = branchAtOrBelow(HashNode(root.toArray))

    /** The node at the top of first nibble `n`: a depth-1 child, which the enumerator meets. */
    def depthOneChild(n: Int): ByteString = hashOf(rootBranch.children(n))

    /** The leaf holding `underHole`, one branch below nibble 1: which only the walk meets. */
    def deeperNode: ByteString = hashOf(branchAtOrBelow(rootBranch.children(1)).children(2))

    def drop(hash: ByteString): Unit =
      f.nodes.update(Seq(hash), Nil)
      ()

    /** A node that is there and reads as RLP, and is no kind of trie node. */
    def corrupt(hash: ByteString): Unit =
      val threeItems = rlp.encode(
        rlp.RLPList(rlp.RLPValue(Array[Byte](1)), rlp.RLPValue(Array[Byte](2)), rlp.RLPValue(Array[Byte](3)))
      )
      f.nodes.update(Nil, Seq(hash -> threeItems))
      ()

    def scanner(
        app: AppStateStorage,
        concurrency: Int = 1,
        onPersisted: Int => Unit = _ => ()
    ): CombinedRecoveryScanner =
      new CombinedRecoveryScanner(
        root,
        () => f.handle(),
        f.evm,
        app,
        concurrency = concurrency,
        onShardPersisted = onPersisted
      )

  test(
    "an absent depth-1 child: the pass completes, with the gaps of the rest of the trie and the hole counted",
    UnitTest
  ) {
    val h = new HoleFixture
    h.drop(h.depthOneChild(2))
    val app = new AppStateStorage(EphemDataSource())

    val r = h.scanner(app).run()

    assert(r.holes == 1)
    assert(
      r.missingBytecodes.toSet == Set(h.beside, h.underHole),
      "nibble 2's code is under the hole; nibble 1's is not"
    )
    assert(r.missingStorageTries.map(_._2).toSet == Set(h.storageGap))
    assert(app.getRecoveryProgress().exists(_.isComplete), "a pass that completed checkpoints as one")
  }

  test("an absent deeper node: the walk goes on over its siblings and the later shards", UnitTest) {
    val h = new HoleFixture
    h.drop(h.deeperNode)

    val r = h.scanner(new AppStateStorage(EphemDataSource())).run()

    assert(r.holes == 1)
    assert(r.missingBytecodes.toSet == Set(h.beside, h.alone), "only what is under the hole goes unseen")
    assert(r.missingStorageTries.map(_._2).toSet == Set(h.storageGap))
  }

  test("holes in several shards under parallel walks are all counted", UnitTest) {
    val h = new HoleFixture
    h.drop(h.deeperNode)
    h.drop(h.depthOneChild(2))

    val r = h.scanner(new AppStateStorage(EphemDataSource()), concurrency = 4).run()

    assert(r.holes == 2)
    assert(r.missingBytecodes.toSet == Set(h.beside))
    assert(r.missingStorageTries.map(_._2).toSet == Set(h.storageGap))
  }

  test(
    "the hole count is this session's: a resume does not walk what is done, and the format does not hold it",
    UnitTest
  ) {
    val h = new HoleFixture
    h.drop(h.deeperNode) // in the first shard
    val app = new AppStateStorage(EphemDataSource())
    intercept[RuntimeException](
      h.scanner(app, onPersisted = n => if n == 1 then throw new RuntimeException("boom")).run()
    )
    assert(app.getRecoveryProgress().exists(_.completedShards.size == 1))

    val resumed = h.scanner(app).run()
    assert(resumed.holes == 0, "the shard with the hole was walked by the session that crashed")
    assert(resumed.missingBytecodes.toSet == Set(h.beside, h.alone), "what that session found was kept")

    val again = h.scanner(app).run()
    assert(again.holes == 0, "a complete checkpoint walks nothing")
    assert(again.missingBytecodes.toSet == resumed.missingBytecodes.toSet)
  }

  test("an absent root is a failure, not an empty trie: it throws and leaves no checkpoint", UnitTest) {
    val h = new HoleFixture
    h.drop(h.root)
    val app = new AppStateStorage(EphemDataSource())

    intercept[MerklePatriciaTrie.MissingNodeException](h.scanner(app).run())

    assert(app.getRecoveryProgress().isEmpty)
  }

  test("a node that will not decode is a failure, not a hole, at the enumerator and in the walk", UnitTest) {
    val top = new HoleFixture
    top.corrupt(top.depthOneChild(2))
    intercept[MerklePatriciaTrie.MPTException](top.scanner(new AppStateStorage(EphemDataSource())).run())

    val deeper = new HoleFixture
    deeper.corrupt(deeper.deeperNode)
    intercept[MerklePatriciaTrie.MPTException](deeper.scanner(new AppStateStorage(EphemDataSource())).run())
  }

  test(
    "the first shard to fail stops the rest: a walk under way gives up, nothing more is persisted, and that failure is thrown",
    UnitTest
  ) {
    val f = new Fixture
    val root =
      f.stateRootOf(
        (1 to 8).map(n => f.acctWithNibble(n, 0) -> acc(TrieRoot(f.missingStorageRoot(n)), Account.EmptyCodeHash))
      )
    // Two walks, by shard. Shard 1 waits until shard 0 is inside its walk, then fails, and is held there with its failure
    // visible and its own future not yet failed. Shard 0 goes on to its first leaf only then, so all it can do is give up,
    // and its future fails first: that is the wait's exception, and all it says is that it was stopped. Only the end of
    // run() lets shard 1 go, so reaching the assertions means shard 0's walk has ended, and a walk that ignored the stop
    // would have persisted by then.
    val walkUnderWay = new CountDownLatch(1)
    val failureVisible = new CountDownLatch(1)
    val runEnded = new CountDownLatch(1)
    val failedOnce = new java.util.concurrent.atomic.AtomicBoolean(false)
    val handles = new AtomicInteger(0)
    val persisted = new AtomicInteger(0)
    val scanner = new CombinedRecoveryScanner(
      root,
      () =>
        if handles.incrementAndGet() > 1 then
          walkUnderWay.countDown()
          assert(failureVisible.await(10, TimeUnit.SECONDS))
        f.handle()
      ,
      f.evm,
      new AppStateStorage(EphemDataSource()),
      concurrency = 2,
      onShardScanStart = idx =>
        if idx == 1 then
          assert(walkUnderWay.await(10, TimeUnit.SECONDS))
          throw new RuntimeException("boom"),
      onShardPersisted = _ => persisted.incrementAndGet(): Unit,
      onShardFailed = () =>
        if failedOnce.compareAndSet(false, true) then
          failureVisible.countDown()
          runEnded.await(10, TimeUnit.SECONDS): Unit
    )

    val thrown =
      try intercept[RuntimeException](scanner.run())
      finally runEnded.countDown()

    assert(thrown.getMessage == "boom")
    assert(persisted.get() == 0, "shard 0 had a leaf to read and a progress write to make, and made neither")
    assert(handles.get() == 2, "the shards after these two never began")
  }

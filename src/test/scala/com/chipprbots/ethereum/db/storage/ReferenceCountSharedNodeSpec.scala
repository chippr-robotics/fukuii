package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.testing.Tags.*

/** #1692. A node has a reference count: the number of positions that emitted it. A writer that skips a node already
  * present (SNAP heal, the checkpoint importer) counts a subtree shared by two contracts once, and the first block to
  * rewrite a slot of one of them sends the nodes on its path to zero. Pruning then deleted nodes the other contract
  * still reads. On a datadir whose counts are not proven exact, pruning deletes no trie node.
  *
  * Contract A has a storage trie written by emission. Contract B has the same storage root, present but counted by
  * nobody: B is the trie read at the root A replaced.
  */
class ReferenceCountSharedNodeSpec extends AnyFlatSpec with Matchers:

  private val history = 4
  private val slots = 40

  private def key(i: Int): ByteString = kec256(ByteString(s"slot$i"))
  private def value(tag: String, i: Int): ByteString = ByteString(s"$tag-$i")

  /** What the storage asks at each prune, given the app state of the same data source. */
  private type Decide = AppStateStorage => () => Boolean
  private val never: Decide = _ => () => false
  private val always: Decide = _ => () => true
  private val unreadable: Decide = _ => () => throw new RuntimeException("disk")
  private val stamped: Decide = app => () => RefCountTrust.nodesMayBeDeleted(app)

  private class Setup(decide: Decide):
    val dataSource: EphemDataSource = EphemDataSource()
    val nodeStorage = new NodeStorage(dataSource)
    val app = new AppStateStorage(dataSource)
    val stateStorage = new ReferenceCountedStateStorage(nodeStorage, history, nodesMayBeDeleted = decide(app))

    /** The storage trie of contract A written at block `bn`: every node counted once per position that emits it. */
    def writeTrie(bn: Int): ByteString =
      val storage = stateStorage.getBackingStorage(bn)
      val trie = (0 until slots).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value("a", i))
      }
      ByteString(trie.getRootHash)

    /** One block writing to A: a slot rewritten on top of `root`. The old path to the slot loses a reference. */
    def rewrite(bn: Int, root: ByteString, slot: Int, tag: String): ByteString =
      val storage = stateStorage.getBackingStorage(bn)
      ByteString(
        MerklePatriciaTrie[ByteString, ByteString](root.toArray, storage).put(key(slot), value(tag, slot)).getRootHash
      )

    /** Reads every slot; throws if any trie node of the state under `root` is gone. */
    def readAll(root: ByteString): Unit =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, stateStorage.getReadOnlyStorage)
      (0 until slots).foreach(i => trie.get(key(i)))

    /** Saves blocks `from` to `to` on a linear chain, each pruning the death row `history` blocks back. */
    def saveBlocks(from: Int, to: Int): Unit =
      (from to to).foreach(bn => stateStorage.onBlockSave(bn, bn - 1)(() => ()))

    def hasNodeBookkeeping(bn: Int): Boolean =
      Seq(ReferenceCountNodeStorage.drRowKey(bn), ByteString("sck".getBytes) ++ ByteString(BigInt(bn).toByteArray))
        .exists(nodeStorage.get(_).isDefined)

  "Pruning a datadir whose counts are not proven exact" should "keep a node another position still reads" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(never):
    val root: ByteString = writeTrie(0)
    readAll(root)
    val retainedBefore = PruningMetrics.retainedNodes
    val bytesBefore = PruningMetrics.retainedBytes

    val next: ByteString = rewrite(1, root, 3, "b")
    saveBlocks(1, 1 + history + 1)

    withClue("B reads the root A replaced: ")(noException should be thrownBy readAll(root))
    noException should be thrownBy readAll(next)
    withClue("the nodes it kept are counted, so the disk cost can be read off: ") {
      PruningMetrics.retainedNodes should be > retainedBefore
      PruningMetrics.retainedBytes should be > bytesBefore
    }

  it should "still prune the death row, snapshot rows and counts of the height" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(never):
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    hasNodeBookkeeping(1) shouldBe true
    saveBlocks(1, 1 + history + 1)
    hasNodeBookkeeping(1) shouldBe false

  it should "keep them when the read that decides fails" taggedAs (UnitTest, DatabaseTest) in new Setup(unreadable):
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    saveBlocks(1, 1 + history + 1)
    noException should be thrownBy readAll(root)

  it should "keep the nodes a rolled-back block created" taggedAs (UnitTest, DatabaseTest) in new Setup(never):
    val root: ByteString = writeTrie(0)
    val next: ByteString = rewrite(1, root, 3, "b")
    stateStorage.onBlockRollback(1, 1)(() => ())
    noException should be thrownBy readAll(root)
    noException should be thrownBy readAll(next)

  "Pruning a datadir whose counts are exact" should "still delete a node that is dead, so the guard is not vacuous" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(always):
    val root: ByteString = writeTrie(0)
    val next: ByteString = rewrite(1, root, 3, "b")
    saveBlocks(1, 1 + history + 1)

    withClue("the root block 1 replaced was counted once, so it is dead and deleted: ") {
      an[MPTException] should be thrownBy readAll(root)
    }
    noException should be thrownBy readAll(next)

  it should "delete the nodes a rolled-back block created" taggedAs (UnitTest, DatabaseTest) in new Setup(always):
    val root: ByteString = writeTrie(0)
    val next: ByteString = rewrite(1, root, 3, "b")
    stateStorage.onBlockRollback(1, 1)(() => ())
    noException should be thrownBy readAll(root)
    an[MPTException] should be thrownBy readAll(next)

  it should "keep a node that both contracts emitted, and delete it once neither uses it" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(always):
    val root: ByteString = writeTrie(0)
    writeTrie(0) // the copy of B, counted: every node of the trie is at two references
    val first: ByteString = rewrite(1, root, 3, "b")
    val second: ByteString = rewrite(2, first, 3, "c")
    saveBlocks(1, 2 + history + 1)

    withClue("B still uses the root: ")(noException should be thrownBy readAll(root))
    withClue("nothing uses the root of block 1 after block 2: ") {
      an[MPTException] should be thrownBy readAll(first)
    }
    noException should be thrownBy readAll(second)

  "The decision wired to the app state" should "delete on a datadir the genesis stamped" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(stamped):
    app.markDatadirCreated(countsExact = true).commit()
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    saveBlocks(1, 1 + history + 1)
    an[MPTException] should be thrownBy readAll(root)

  it should "keep everything on a datadir with no stamp, like one an older binary made" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(stamped):
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    saveBlocks(1, 1 + history + 1)
    noException should be thrownBy readAll(root)

  it should "stop deleting at the next prune once a Hash SNAP starts in the same process" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(stamped):
    app.markDatadirCreated(countsExact = true).commit()
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    RefCountTrust.withdrawAtSnapStart(app, hashScheme = true) shouldBe true
    saveBlocks(1, 1 + history + 1)
    noException should be thrownBy readAll(root)

  it should "keep deleting after a Path SNAP starts: its publish decides" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(stamped):
    app.markDatadirCreated(countsExact = true).commit()
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    RefCountTrust.withdrawAtSnapStart(app, hashScheme = false) shouldBe false
    saveBlocks(1, 1 + history + 1)
    an[MPTException] should be thrownBy readAll(root)

  it should "stop deleting at the next prune once a Path hand-off that is not clean publishes" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(stamped):
    app.markDatadirCreated(countsExact = true).commit()
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    RefCountTrust.withdrawAtPathPublish(app, handOffClean = false) shouldBe true
    saveBlocks(1, 1 + history + 1)
    noException should be thrownBy readAll(root)

  it should "keep deleting after a Path hand-off that is clean" taggedAs (UnitTest, DatabaseTest) in new Setup(stamped):
    app.markDatadirCreated(countsExact = true).commit()
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    RefCountTrust.withdrawAtPathPublish(app, handOffClean = true) shouldBe false
    saveBlocks(1, 1 + history + 1)
    an[MPTException] should be thrownBy readAll(root)

  it should "stop deleting once a checkpoint import has begun, on either scheme" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup(stamped):
    app.markDatadirCreated(countsExact = true).commit()
    val root: ByteString = writeTrie(0)
    rewrite(1, root, 3, "b")
    app.beginCheckpointImport(root, 0L).commitSync()
    saveBlocks(1, 1 + history + 1)
    noException should be thrownBy readAll(root)

package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*

/** `ReferenceCountNodeStorage.rollbackReporting` against a real storage: a rolled-back block must leave the database
  * byte-identical to what it was before the block, including a block that wrote the same node more than once (every
  * transaction persists the trie, so a hot node is rewritten per transaction) and the per-snapshot keys.
  */
class ReferenceCountRollbackSpec extends AnyFlatSpec with Matchers:

  private val keyCount = 30
  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String, i: Int): ByteString = ByteString(s"$tag-$i-padding-to-force-a-hashed-leaf-node")

  class Setup:
    val dataSource: EphemDataSource = EphemDataSource()
    val nodeStorage = new NodeStorage(dataSource)
    val stateStorage = new ReferenceCountedStateStorage(nodeStorage, 64, 0L)

    def dbContent: Map[Seq[Byte], Seq[Byte]] =
      dataSource.storage.map { case (k, v) => k.array().toSeq -> v.toSeq }.toMap

    def genesis(): ByteString =
      val storage = stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value("g", i))
      }
      ByteString(trie.getRootHash)

    /** One "transaction": a persist of slot 0 against `root`, under block `bn`. */
    def tx(bn: Int, root: ByteString, tag: String): ByteString =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, stateStorage.getBackingStorage(bn))
        .put(key(0), if tag == "g" then value("g", 0) else value(tag, 0))
      ByteString(trie.getRootHash)

  "ReferenceCountNodeStorage.rollbackReporting" should "restore a byte-identical DB for a block that writes one node twice" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val root0 = genesis()
    val before = dbContent
    // y is created by the first transaction (no snapshot value), left by the second, and created AGAIN by the third:
    // its snapshots are None, then Some(intermediate). Replaying both resurrected the node at refs == 1.
    val r1 = tx(1, root0, "y")
    val r2 = tx(1, r1, "g")
    r2 shouldBe root0
    val r3 = tx(1, r2, "y")
    r3 shouldBe r1
    dbContent should not be before

    stateStorage.onBlockRollback(1, 0)(() => ())

    dbContent shouldBe before

  it should "leave no snapshot or death-row key behind" taggedAs (UnitTest, DatabaseTest) in new Setup:
    val root0 = genesis()
    val before = dbContent
    tx(1, tx(1, root0, "y"), "z")
    stateStorage.onBlockRollback(1, 0)(() => ())
    dbContent shouldBe before

  it should "restore only the block rolled back, leaving earlier blocks' counts alone" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val root0 = genesis()
    val r1 = tx(1, root0, "a")
    val afterBlock1 = dbContent
    tx(2, tx(2, r1, "b"), "a")
    stateStorage.onBlockRollback(2, 1)(() => ())
    dbContent shouldBe afterBlock1

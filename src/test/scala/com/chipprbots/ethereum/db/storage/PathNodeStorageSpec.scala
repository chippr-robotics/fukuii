package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** `PathNodeStorage`'s batch helpers (spec 022 T013) and its single-batch heal write (FR-008, D10). Keys and values
  * must be byte-identical to the one-node writes healing used before, and a heal write with its deletes must be ONE
  * `dataSource.update` call: RocksDB applies one call as one `WriteBatch`, so a crash keeps all of it or none.
  */
class PathNodeStorageSpec extends AnyFlatSpec with Matchers:

  /** An `EphemDataSource` that keeps every `update` call. */
  final private class Recording extends EphemDataSource(Map.empty):
    val calls: scala.collection.mutable.Buffer[Seq[DataUpdate]] = scala.collection.mutable.Buffer.empty
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      calls += dataSourceUpdates
      super.update(dataSourceUpdates)

  private val owner = ByteString(Array.fill[Byte](32)(0x3c))
  private def nibbles(ns: Int*): Array[Byte] = ns.map(_.toByte).toArray
  private def compact(ns: Array[Byte]): Seq[Byte] = HexPrefix.encode(ns, isLeaf = false).toSeq
  private val rlp = Array[Byte](1, 2, 3)

  private def flat(u: DataSourceUpdateOptimized): (Seq[Byte], Seq[Seq[Byte]], Seq[(Seq[Byte], Seq[Byte])]) =
    (u.namespace.toSeq, u.toRemove.map(_.toSeq), u.toUpsert.map { case (k, v) => (k.toSeq, v.toSeq) })

  "PathNodeStorage" should "build account-trie updates with the keys and values writeAccountNode and deleteAccountNode commit (T013)" taggedAs UnitTest in {
    val ds = new Recording
    val pns = new PathNodeStorage(ds)
    pns.writeAccountNode(nibbles(1, 2, 3), rlp)
    pns.deleteAccountNode(nibbles(4))
    ds.calls.toSeq.map(_.map { case u: DataSourceUpdateOptimized => flat(u); case other => fail(s"$other") }) shouldBe
      Seq(Seq(flat(pns.accountNodeUpsert(nibbles(1, 2, 3), rlp))), Seq(flat(pns.accountNodeRemoval(nibbles(4)))))

    flat(pns.accountNodeUpsert(nibbles(1, 2, 3), rlp)) shouldBe
      ((Namespaces.StateTriePathNamespace.toSeq, Nil, Seq((compact(nibbles(1, 2, 3)), rlp.toSeq))))
    flat(pns.accountNodeRemoval(nibbles(4))) shouldBe
      ((Namespaces.StateTriePathNamespace.toSeq, Seq(compact(nibbles(4))), Nil))
    // the storage-trie mirror these follow
    flat(pns.storageNodeUpsert(owner, nibbles(5), rlp)) shouldBe
      ((Namespaces.StorageTriePathNamespace.toSeq, Nil, Seq((owner.toSeq ++ compact(nibbles(5)), rlp.toSeq))))
  }

  it should "write a node and delete positions in both tries in one update, removals first (FR-008)" taggedAs UnitTest in {
    val ds = new Recording
    val pns = new PathNodeStorage(ds)
    pns.writeAccountNode(nibbles(0, 5), Array[Byte](9))
    pns.writeStorageNode(owner, Array.empty[Byte], Array[Byte](8))
    ds.calls.clear()

    pns.writeNodeWithDeletes(None, nibbles(0), rlp, Seq((None, nibbles(0, 5)), (Some(owner), Array.empty[Byte])))

    ds.calls should have size 1
    ds.calls.head.map { case u: DataSourceUpdateOptimized => flat(u); case other => fail(s"$other") } shouldBe Seq(
      flat(pns.accountNodeRemoval(nibbles(0, 5))),
      flat(pns.storageNodeRemoval(owner, Array.empty[Byte])),
      flat(pns.accountNodeUpsert(nibbles(0), rlp))
    )
    pns.readAccountNode(nibbles(0)).map(_.toSeq) shouldBe Some(rlp.toSeq)
    pns.readAccountNode(nibbles(0, 5)) shouldBe None
    pns.readStorageNode(owner, Array.empty[Byte]) shouldBe None
  }

  it should "write a storage-trie node with no deletes exactly as writeStorageNode does" taggedAs UnitTest in {
    val ds = new Recording
    val pns = new PathNodeStorage(ds)
    pns.writeNodeWithDeletes(Some(owner), nibbles(7, 1), rlp, Nil)
    pns.writeStorageNode(owner, nibbles(7, 1), rlp)
    val asFlat = ds.calls.toSeq.map(_.map {
      case u: DataSourceUpdateOptimized => flat(u); case other => fail(s"$other")
    })
    asFlat(0) shouldBe asFlat(1)
    pns.readStorageNode(owner, nibbles(7, 1)).map(_.toSeq) shouldBe Some(rlp.toSeq)
  }

  it should "keep the written node when a delete names the same position" taggedAs UnitTest in {
    val pns = new PathNodeStorage(EphemDataSource())
    pns.writeNodeWithDeletes(None, nibbles(3), rlp, Seq((None, nibbles(3))))
    pns.readAccountNode(nibbles(3)).map(_.toSeq) shouldBe Some(rlp.toSeq)
  }

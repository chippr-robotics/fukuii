package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** `PathNodeStorage`'s batch helpers (spec 022 T013): keys and values byte-identical to the one-node writes. */
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

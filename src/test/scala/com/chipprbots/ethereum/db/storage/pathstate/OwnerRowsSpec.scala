package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T090): the rows of one storage trie, listed by a scan of CF `u` from the owner's first key
  * (data-model 3.6). What a block that replaces the trie deletes.
  */
class OwnerRowsSpec extends AnyFlatSpec with Matchers:

  import PathStateTestKit.*

  private def ownerOf(bytes: Int*): ByteString =
    ByteString(Array.fill[Byte](32 - bytes.size)(0x42.toByte) ++ bytes.map(_.toByte))

  private val node: Array[Byte] = Array.fill[Byte](40)(9)

  // paths of every length parity, and first nibbles from 0 to f: the bytes after the owner run from 00 to 1f
  private val paths: Seq[Array[Byte]] =
    Seq(Array.emptyByteArray) ++ (0 to 15).map(n => Array(n.toByte)) ++
      (0 to 15).map(n => Array(n.toByte, 0xf.toByte)) ++ Seq(Array.fill[Byte](63)(0xf), Array.fill[Byte](64)(0xf))

  dataSources.foreach { case (name, onDataSource) =>
    s"The rows of a storage trie on $name" should "be all of that owner's and no other's, in key order" taggedAs UnitTest in {
      onDataSource { source =>
        val rows = new PathNodeStorage(source)
        // an owner, the one just below it, the one just above it, one that is a prefix-neighbour, and the last of all
        val middle = ownerOf(0x7f, 0xff)
        val below = ownerOf(0x7f, 0xfe)
        val above = ownerOf(0x80, 0x00)
        val last = ByteString(Array.fill[Byte](32)(0xff.toByte))
        val first = ByteString(Array.fill[Byte](32)(0))
        val owners = Seq(first, below, middle, above, last)
        owners.foreach(o => paths.foreach(path => rows.writeStorageNode(o, path, node ++ o.takeRight(1))))
        rows.writeAccountNode(Array[Byte](1, 2), node)

        owners.foreach { o =>
          val listed = rows.storageRows(o, Int.MaxValue).get
          withClue(s"owner ${o.takeRight(2)}: ") {
            listed.map(_._1.owner).toSet shouldBe Set(Some(o))
            listed.map(_._1.hp).toSet shouldBe paths
              .map(path => ByteString(HexPrefix.encode(path, isLeaf = false)))
              .toSet
            listed.map(_._1.path.length).toSet shouldBe paths.map(_.length).toSet
            all(listed.map(_._2)) shouldBe ByteString(node) ++ o.takeRight(1)
            // unsigned order of the row keys, on either data source
            val keys = listed.map(row => ByteString(row._1.rowKey))
            keys shouldBe keys.sortWith((a, b) => java.util.Arrays.compareUnsigned(a.toArray, b.toArray) < 0)
          }
        }
        rows.storageRows(ownerOf(0x11, 0x11), 10) shouldBe Some(Nil)
        rows.storageRows(ownerOf(0x11, 0x11), 0) shouldBe Some(Nil)
      }
    }

    // The scan is made a chunk at a time, so that the data source's read lock is let go in between.
    it should "be the same rows whatever the size of the chunks they are read in" taggedAs UnitTest in {
      onDataSource { source =>
        val rows = new PathNodeStorage(source)
        val (owner, neighbour) = (ownerOf(1), ownerOf(2))
        paths.foreach { path =>
          rows.writeStorageNode(owner, path, node)
          rows.writeStorageNode(neighbour, path, node)
        }
        val whole = rows.storageRows(owner, Int.MaxValue).get
        whole should have size paths.size
        // 1: every row is the last of its chunk; a size that divides the count: the last chunk is full, and one more
        // read finds nothing; sizes around the count
        Seq(1, 2, 3, 5, 7, paths.size / 2, paths.size - 1, paths.size, paths.size + 1).foreach { chunk =>
          withClue(s"chunks of $chunk: ")(rows.storageRows(owner, Int.MaxValue, chunkRows = chunk) shouldBe Some(whole))
        }
        an[IllegalArgumentException] should be thrownBy rows.storageRows(owner, 10, chunkRows = 0)
      }
    }

    it should "not be listed when there are more rows, or more bytes of them, than it is told may be" taggedAs UnitTest in {
      onDataSource { source =>
        val rows = new PathNodeStorage(source)
        val owner = ownerOf(1)
        paths.foreach(path => rows.writeStorageNode(owner, path, node))
        val whole = rows.storageRows(owner, Int.MaxValue).get
        val bytes = whole.map((position, row) => position.rowKey.length.toLong + row.length).sum
        Seq(1, 4, PathNodeStorage.StorageRowsChunk).foreach { chunk =>
          withClue(s"chunks of $chunk: ") {
            rows.storageRows(owner, paths.size, chunkRows = chunk) shouldBe Some(whole)
            rows.storageRows(owner, paths.size - 1, chunkRows = chunk) shouldBe None
            rows.storageRows(owner, 0, chunkRows = chunk) shouldBe None
            rows.storageRows(owner, Int.MaxValue, maxBytes = bytes, chunkRows = chunk) shouldBe Some(whole)
            rows.storageRows(owner, Int.MaxValue, maxBytes = bytes - 1, chunkRows = chunk) shouldBe None
          }
        }
      }
    }
  }

  // The scan ends before owner ++ 0xff. That is above every row of the owner because of what a row key is.
  "The key of a row" should "begin, after its owner, with a byte below 0x20 for a path of any length" taggedAs UnitTest in {
    for
      length <- 0 to 64
      nibble <- Seq(0, 7, 15)
    do
      val first = HexPrefix.encode(Array.fill[Byte](length)(nibble.toByte), isLeaf = false).head & 0xff
      withClue(s"length $length, nibble $nibble: ")(first should be < 0x20)
  }

  "An owner that is not an account hash" should "be refused" taggedAs UnitTest in {
    val rows = new PathNodeStorage(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
    an[IllegalArgumentException] should be thrownBy rows.storageRows(ByteString(1, 2, 3), 10)
  }

  "The updates of a set of positions" should "delete where no node is given and write where one is, in both tries" taggedAs
    UnitTest in {
      dataSources.foreach { case (_, onDataSource) =>
        onDataSource { source =>
          val rows = new PathNodeStorage(source)
          val owner = ownerOf(1)
          rows.writeAccountNode(Array[Byte](1), node)
          rows.writeAccountNode(Array[Byte](2), node)
          rows.writeStorageNode(owner, Array[Byte](1), node)
          rows.writeStorageNode(owner, Array[Byte](2), node)
          val other = ByteString(Array.fill[Byte](33)(5))
          def at(o: Option[ByteString], nibble: Int) =
            PositionKey(o, ByteString(HexPrefix.encode(Array(nibble.toByte), isLeaf = false)))
          source.update(
            rows.rowUpdates(
              Seq(
                at(None, 1) -> None,
                at(None, 3) -> Some(other),
                at(Some(owner), 2) -> None,
                at(Some(owner), 1) -> Some(other)
              )
            )
          )
          rows.readAccountNode(Array[Byte](1)) shouldBe None
          rows.readAccountNode(Array[Byte](2)).map(ByteString(_)) shouldBe Some(ByteString(node))
          rows.readAccountNode(Array[Byte](3)).map(ByteString(_)) shouldBe Some(other)
          rows.readStorageNode(owner, Array[Byte](2)) shouldBe None
          rows.readStorageNode(owner, Array[Byte](1)).map(ByteString(_)) shouldBe Some(other)
          rows.rowUpdates(Nil) shouldBe empty
        }
      }
    }

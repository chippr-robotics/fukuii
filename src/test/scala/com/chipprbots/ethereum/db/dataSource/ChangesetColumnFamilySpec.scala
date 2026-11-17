package com.chipprbots.ethereum.db.dataSource

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.pathstate.BlockDiff
import com.chipprbots.ethereum.db.storage.pathstate.BlockDiffCodec
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T068: a database made before the changeset column family existed gains it, empty, the next time it
  * is opened, and nothing it held is touched. RocksDB is opened with `setCreateMissingColumnFamilies(true)`, as it was
  * when the block-access-list family was added in 0.9.0.
  */
class ChangesetColumnFamilySpec extends AnyFlatSpec with Matchers:

  private def config(directory: File): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = directory.getAbsolutePath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  "The namespace list" should "name the changeset column family, with the byte v, once" taggedAs UnitTest in {
    Namespaces.StateChangesetNamespace shouldBe IndexedSeq('v'.toByte)
    Namespaces.nsSeq.count(_ == Namespaces.StateChangesetNamespace) shouldBe 1
    Namespaces.nsSeq.distinct.size shouldBe Namespaces.nsSeq.size
  }

  "A database made before the changeset column family existed" should "gain it empty at its next start, and keep what it held" taggedAs
    UnitTest in {
      val directory = Files.createTempDirectory("changeset-cf-rocksdb").toFile
      try
        val before = Namespaces.nsSeq.filterNot(_ == Namespaces.StateChangesetNamespace)
        val key = "an old key".getBytes("UTF-8")
        val value = "an old value".getBytes("UTF-8")

        val old = RocksDbDataSource(config(directory), before)
        try old.update(Seq(DataSourceUpdateOptimized(Namespaces.AppStateNamespace, Nil, Seq(key -> value))))
        finally old.close()

        val reopened = RocksDbDataSource(config(directory), Namespaces.nsSeq)
        try
          reopened.getOptimized(Namespaces.AppStateNamespace, key).map(ByteString(_)) shouldBe Some(ByteString(value))
          // the new family is there, holds nothing, and takes a record
          reopened.scanRange(
            Namespaces.StateChangesetNamespace,
            Array.emptyByteArray,
            Array.fill[Byte](34)(0xff.toByte)
          ) shouldBe empty
          val storage = new ChangesetStorage(reopened)
          val block = BlockDiff(
            ByteString(Array.fill[Byte](32)(1)),
            5,
            ByteString(Array.fill[Byte](32)(2)),
            ByteString(Array.fill[Byte](32)(3)),
            ByteString(Array.fill[Byte](32)(4)),
            Map.empty
          )
          val record = ChangesetStorage.recordKey(block.blockHash) -> BlockDiffCodec.encode(block)
          reopened.update(Seq(DataSourceUpdateOptimized(Namespaces.StateChangesetNamespace, Nil, Seq(record))))
          storage.get(block.blockHash) shouldBe Some(Right(block))
        finally reopened.destroy()
      finally deleteRecursively(directory)
    }

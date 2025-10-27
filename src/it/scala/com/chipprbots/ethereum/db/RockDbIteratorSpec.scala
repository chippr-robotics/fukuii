package com.chipprbots.ethereum.db

import java.nio.file.Files

import akka.util.ByteString

import cats.effect.Resource
import cats.effect.Deferred
import cats.effect.Ref
import cats.effect.IO

import cats.effect.IO
import fs2.Stream

import scala.util.Random

import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FlatSpecBase
import com.chipprbots.ethereum.ResourceFixtures
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage

class RockDbIteratorSpec extends FlatSpecBase with ResourceFixtures with Matchers {
  type Fixture = RocksDbDataSource

  override def fixtureResource: Resource[IO, RocksDbDataSource] = RockDbIteratorSpec.buildRockDbResource()

  def genRandomArray(): Array[Byte] = {
    val arr = new Array[Byte](32)
    Random.nextBytes(arr)
    arr
  }

  def genRandomByteString(): ByteString =
    ByteString.fromArrayUnsafe(genRandomArray())

  def writeNValuesToDb(n: Int, db: RocksDbDataSource, namespace: IndexedSeq[Byte]): IO[Unit] = {
    val iterable = 0 until n
    Stream.emits(iterable).evalMap { _ =>
      IO(db.update(Seq(DataSourceUpdateOptimized(namespace, Seq(), Seq((genRandomArray(), genRandomArray()))))))
    }.compile.drain
  }

  it should "cancel ongoing iteration" in testCaseT { db =>
    val largeNum = 1000000
    val finishMark = 20000
    for {
      counter <- Ref.of[IO, Int](0)
      cancelMark <- Deferred[IO, Unit]
      _ <- writeNValuesToDb(largeNum, db, Namespaces.NodeNamespace)
      fib <- db
        .iterate(Namespaces.NodeNamespace)
        .map(_.toOption.get)
        .evalMap { _ =>
          for {
            cur <- counter.updateAndGet(i => i + 1)
            _ <- if (cur == finishMark) cancelMark.complete(()) else IO.unit
          } yield ()
        }
        .compile.drain.start
      _ <- cancelMark.get
      // take in mind this test also check if all underlying rocksdb resources has been cleaned as if cancel
      // would not close underlying DbIterator, whole test would kill jvm due to rocksdb error at native level because
      // iterators needs to be closed before closing db.
      _ <- fib.cancel
      finalCounter <- counter.get
    } yield assert(finalCounter < largeNum)
  }

  it should "read all key values in db" in testCaseT { db =>
    val largeNum = 100000
    for {
      counter <- Ref.of[IO, Int](0)
      _ <- writeNValuesToDb(largeNum, db, Namespaces.NodeNamespace)
      _ <- db
        .iterate(Namespaces.NodeNamespace)
        .map(_.toOption.get)
        .evalMap { _ =>
          counter.update(current => current + 1)
        }
        .compile.drain
      finalCounter <- counter.get
    } yield assert(finalCounter == largeNum)
  }

  it should "iterate over keys and values from different namespaces" in testCaseT { db =>
    val codeStorage = new EvmCodeStorage(db)
    val codeKeyValues = (1 to 10).map(i => (ByteString(i.toByte), ByteString(i.toByte))).toList

    val nodeStorage = new NodeStorage(db)
    val nodeKeyValues = (20 to 30).map(i => (ByteString(i.toByte), ByteString(i.toByte).toArray)).toList

    for {
      _ <- IO(codeStorage.update(Seq(), codeKeyValues).commit())
      _ <- IO(nodeStorage.update(Seq(), nodeKeyValues))
      result <- (
        codeStorage.storageContent.map(_.toOption.get).map(_._1).compile.toList,
        nodeStorage.storageContent.map(_.toOption.get).map(_._1).compile.toList
      ).parTupled
      (codeResult, nodeResult) = result
    } yield {
      codeResult shouldEqual codeKeyValues.map(_._1)
      nodeResult shouldEqual nodeKeyValues.map(_._1)
    }
  }

  it should "iterate over keys and values " in testCaseT { db =>
    val keyValues = (1 to 100).map(i => (ByteString(i.toByte), ByteString(i.toByte))).toList
    for {
      _ <- IO(
        db.update(
          Seq(
            DataSourceUpdateOptimized(Namespaces.NodeNamespace, Seq(), keyValues.map(e => (e._1.toArray, e._2.toArray)))
          )
        )
      )
      elems <- db.iterate(Namespaces.NodeNamespace).map(_.toOption.get).compile.toList
    } yield {
      val deserialized = elems.map { case (bytes, bytes1) => (ByteString(bytes), ByteString(bytes1)) }
      assert(elems.size == keyValues.size)
      assert(keyValues == deserialized)
    }
  }

  it should "return empty list when iterating empty db" in testCaseT { db =>
    for {
      elems <- db.iterate().compile.toList
    } yield assert(elems.isEmpty)
  }
}

object RockDbIteratorSpec {
  def getRockDbTestConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig {
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
    }

  def buildRockDbResource(): Resource[IO, RocksDbDataSource] =
    Resource.make {
      IO {
        val tempDir = Files.createTempDirectory("temp-iter-dir")
        RocksDbDataSource(getRockDbTestConfig(tempDir.toAbsolutePath.toString), Namespaces.nsSeq)
      }
    }(db => IO(db.destroy()))
}

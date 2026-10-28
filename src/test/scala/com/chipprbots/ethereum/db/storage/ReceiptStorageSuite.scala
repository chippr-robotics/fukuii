package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.testing.Tags.*

class ReceiptStorageSuite extends AnyFunSuite with ScalaCheckPropertyChecks with ObjectGenerators:

  test("ReceiptStorage insert", UnitTest, DatabaseTest) {
    forAll(Gen.listOf(byteStringOfLengthNGen(32))) { blockByteArrayHashes =>
      val blockHashes = blockByteArrayHashes.distinct
      val receipts = receiptsGen(blockHashes.length).sample.get
      val blockHashesReceiptsPair = receipts.zip(blockHashes)

      val storage = new ReceiptStorage(EphemDataSource())
      val batchUpdates = blockHashesReceiptsPair.foldLeft(storage.emptyBatchUpdate) {
        case (updates, (receiptList, blockHash)) =>
          updates.and(storage.put(blockHash, receiptList))
      }
      batchUpdates.commit()

      blockHashesReceiptsPair.foreach { case (rs, bh) =>
        val obtainedReceipts: Option[Seq[Receipt]] = storage.get(bh)
        assert(obtainedReceipts.contains(rs))
      }
    }
  }

  test("ReceiptStorage delete", UnitTest, DatabaseTest) {
    forAll(Gen.listOf(byteStringOfLengthNGen(32))) { blockByteArrayHashes =>
      val blockHashes = blockByteArrayHashes.distinct
      val receipts = receiptsGen(blockHashes.length).sample.get
      val blockHashesReceiptsPair = receipts.zip(blockHashes)

      // Receipts are inserted
      val storage = new ReceiptStorage(EphemDataSource())
      val storageInsertions = blockHashesReceiptsPair.foldLeft(storage.emptyBatchUpdate) {
        case (updates, (receiptList, blockHash)) =>
          updates.and(storage.put(blockHash, receiptList))
      }
      storageInsertions.commit()

      // Receipts are deleted
      val (toDelete, toLeave) = blockHashesReceiptsPair.splitAt(Gen.choose(0, blockHashesReceiptsPair.size).sample.get)
      val storageDeletions = toDelete.foldLeft(storage.emptyBatchUpdate) { case (updates, (_, blockHash)) =>
        updates.and(storage.remove(blockHash))
      }
      storageDeletions.commit()

      toLeave.foreach { case (rs, bh) =>
        val obtainedReceipts = storage.get(bh)
        assert(obtainedReceipts.contains(rs))
      }
      toDelete.foreach { case (_, bh) => assert(storage.get(bh).isEmpty) }
    }
  }

  // #1675: the chain downloader's backfill scans ask, per block, whether its receipts are stored. containsKey answers
  // from the raw read alone: the same answer as get(..).isDefined, without decoding the receipts.
  test("ReceiptStorage containsKey answers get(..).isDefined without decoding the value", UnitTest, DatabaseTest) {
    val dataSource = EphemDataSource()
    val storage = new ReceiptStorage(dataSource)
    val stored = ByteString(Array.fill[Byte](32)(1))
    val storedEmpty = ByteString(Array.fill[Byte](32)(2))
    val absent = ByteString(Array.fill[Byte](32)(3))
    val undecodable = ByteString(Array.fill[Byte](32)(4))
    storage.put(stored, receiptsGen(1).sample.get.head).and(storage.put(storedEmpty, Seq.empty)).commit()
    // A value that claims five receipts and holds none: no decoder accepts it.
    dataSource.update(
      Seq(DataSourceUpdate(storage.namespace, Nil, Seq(storage.keySerializer(undecodable) -> IndexedSeq[Byte](5))))
    )

    Seq(stored, storedEmpty, absent).foreach(h => assert(storage.containsKey(h) == storage.get(h).isDefined))
    assert(storage.containsKey(stored))
    assert(storage.containsKey(storedEmpty))
    assert(!storage.containsKey(absent))
    assert(Try(storage.get(undecodable)).isFailure)
    assert(storage.containsKey(undecodable)) // never decoded
  }

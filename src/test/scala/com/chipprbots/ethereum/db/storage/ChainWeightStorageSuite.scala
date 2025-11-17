package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import boopickle.Default._

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ChainWeightStorage.LegacyChainWeight
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.ByteUtils.compactPickledBytes

class ChainWeightStorageSuite extends AnyFunSuite with ScalaCheckPropertyChecks with ObjectGenerators {
  test("ChainWeightStorage insert", UnitTest, DatabaseTest) {
    forAll(Gen.listOf(byteStringOfLengthNGen(32))) { blockByteArrayHashes =>
      val blockHashes = blockByteArrayHashes.distinct
      val weightList = Gen.listOf(chainWeightGen).sample.get
      val blockHashesWeightsPairs = weightList.zip(blockHashes)

      val storage = new ChainWeightStorage(EphemDataSource())
      val batchUpdates = blockHashesWeightsPairs.foldLeft(storage.emptyBatchUpdate) {
        case (updates, (weight, blockHash)) =>
          updates.and(storage.put(blockHash, weight))
      }
      batchUpdates.commit()

      blockHashesWeightsPairs.foreach { case (weight, blockHash) => assert(storage.get(blockHash).contains(weight)) }
    }
  }

  test("ChainWeightStorage delete", UnitTest, DatabaseTest) {
    forAll(Gen.listOf(byteStringOfLengthNGen(32))) { blockByteArrayHashes =>
      val blockHashes = blockByteArrayHashes.distinct
      val weightList = Gen.listOf(chainWeightGen).sample.get
      val blockHashesWeightsPairs = weightList.zip(blockHashes)

      // Chain weight of blocks is inserted
      val storage = new ChainWeightStorage(EphemDataSource())
      val storageInsertions = blockHashesWeightsPairs.foldLeft(storage.emptyBatchUpdate) {
        case (updates, (td, blockHash)) =>
          updates.and(storage.put(blockHash, td))
      }
      storageInsertions.commit()

      // Chain weight of blocks is deleted
      val (toDelete, toLeave) = blockHashesWeightsPairs.splitAt(Gen.choose(0, blockHashesWeightsPairs.size).sample.get)
      val storageDeletions = toDelete.foldLeft(storage.emptyBatchUpdate) { case (updates, (_, blockHash)) =>
        updates.and(storage.remove(blockHash))
      }
      storageDeletions.commit()

      toLeave.foreach { case (weight, blockHeader) =>
        assert(storage.get(blockHeader).contains(weight))
      }
      toDelete.foreach { case (_, bh) => assert(storage.get(bh).isEmpty) }
    }
  }

  test("ChainWeightStorage handles legacy format without messScore", UnitTest, DatabaseTest) {
    // This test simulates data that was serialized before the messScore field was added
    val blockHash = byteStringOfLengthNGen(32).sample.get
    val lastCheckpointNumber = BigInt(100)
    val totalDifficulty = BigInt(5000)

    // Create legacy format data (2-field ChainWeight before messScore was added)
    val legacyData = LegacyChainWeight(lastCheckpointNumber, totalDifficulty)
    val serializedLegacyData = compactPickledBytes(Pickle.intoBytes(legacyData))

    // Manually insert legacy data into storage
    val dataSource = EphemDataSource()
    val storage = new ChainWeightStorage(dataSource)
    dataSource.update(
      Seq(
        DataSourceUpdate(
          storage.namespace,
          Seq(),
          Seq(blockHash.toIndexedSeq -> serializedLegacyData.toIndexedSeq)
        )
      )
    )

    // Verify that legacy data can be read and is migrated to new format
    val retrieved = storage.get(blockHash)
    assert(retrieved.isDefined, "Should successfully deserialize legacy format")
    assert(retrieved.get.lastCheckpointNumber == lastCheckpointNumber)
    assert(retrieved.get.totalDifficulty == totalDifficulty)
    assert(retrieved.get.messScore.isEmpty, "messScore should be None for migrated legacy data")
  }

  test("ChainWeightStorage handles current format with messScore", UnitTest, DatabaseTest) {
    // This test verifies that new format with messScore works correctly
    val blockHash = byteStringOfLengthNGen(32).sample.get
    val chainWeight = ChainWeight(BigInt(100), BigInt(5000), Some(BigInt(6000)))

    val storage = new ChainWeightStorage(EphemDataSource())
    storage.put(blockHash, chainWeight).commit()

    val retrieved = storage.get(blockHash)
    assert(retrieved.isDefined)
    assert(retrieved.get == chainWeight)
    assert(retrieved.get.messScore.contains(BigInt(6000)))
  }

  test("ChainWeightStorage handles current format without messScore", UnitTest, DatabaseTest) {
    // This test verifies that new format with messScore = None works correctly
    val blockHash = byteStringOfLengthNGen(32).sample.get
    val chainWeight = ChainWeight(BigInt(100), BigInt(5000), None)

    val storage = new ChainWeightStorage(EphemDataSource())
    storage.put(blockHash, chainWeight).commit()

    val retrieved = storage.get(blockHash)
    assert(retrieved.isDefined)
    assert(retrieved.get == chainWeight)
    assert(retrieved.get.messScore.isEmpty)
  }

  test("ChainWeightStorage handles corrupted data gracefully", UnitTest, DatabaseTest) {
    // This test verifies that corrupted data throws a clear error
    val blockHash = byteStringOfLengthNGen(32).sample.get
    val corruptedData = ByteString(1, 2, 3) // Invalid pickled data

    val dataSource = EphemDataSource()
    val storage = new ChainWeightStorage(dataSource)
    dataSource.update(
      Seq(
        DataSourceUpdate(
          storage.namespace,
          Seq(),
          Seq(blockHash.toIndexedSeq -> corruptedData.toIndexedSeq)
        )
      )
    )

    // Verify that corrupted data throws an IllegalStateException with helpful message
    val exception = intercept[IllegalStateException] {
      storage.get(blockHash)
    }
    assert(
      exception.getMessage.contains("Failed to deserialize ChainWeight"),
      s"Error message should indicate deserialization failure. Got: ${exception.getMessage}"
    )
  }
}

package com.chipprbots.ethereum.db.storage

import org.scalacheck.Gen
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.testing.Tags._

class BlockHeadersStorageSpec extends AnyWordSpec with ScalaCheckPropertyChecks with ObjectGenerators {

  "BlockHeadersStorage" should {

    "insert block header properly" taggedAs (UnitTest, DatabaseTest) in {
      forAll(Gen.listOfN(32, ObjectGenerators.blockHeaderGen)) { blockHeaders =>
        val storage = new BlockHeadersStorage(EphemDataSource())
        val headers = blockHeaders.distinct
        val storagesUpdates = blockHeaders.foldLeft(storage.emptyBatchUpdate) { case (updates, blockHeader) =>
          updates.and(storage.put(blockHeader.hash, blockHeader))
        }
        storagesUpdates.commit()

        checkIfIsInStorage(headers, storage)
      }
    }

    "delete block header properly" taggedAs (UnitTest, DatabaseTest) in {
      forAll(Gen.listOfN(32, ObjectGenerators.blockHeaderGen)) { blockHeaders =>
        val storage = new BlockHeadersStorage(EphemDataSource())
        val headers = blockHeaders.distinct
        val storageInsertions = blockHeaders.foldLeft(storage.emptyBatchUpdate) { case (updates, blockHeader) =>
          updates.and(storage.put(blockHeader.hash, blockHeader))
        }
        storageInsertions.commit()

        // Mapping of block headers is inserted
        checkIfIsInStorage(headers, storage)

        // Mapping of block headers is deleted
        val (toDelete, toLeave) = headers.splitAt(Gen.choose(0, headers.size).sample.get)

        val storageDeletions = toDelete.foldLeft(storage.emptyBatchUpdate) { case (updates, blockHeader) =>
          updates.and(storage.remove(blockHeader.hash))
        }
        storageDeletions.commit()

        checkIfIsInStorage(toLeave, storage)
        toDelete.foreach(header => assert(storage.get(header.hash).isEmpty))
      }
    }
  }

  def checkIfIsInStorage(headers: List[BlockHeader], totalStorage: BlockHeadersStorage): Unit =
    headers.foreach(header => assert(totalStorage.get(header.hash).contains(header)))

}

package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

class BlockFirstSeenStorageSpec extends AnyFlatSpec with Matchers {

  // In-memory implementation for testing
  class InMemoryBlockFirstSeenStorage extends BlockFirstSeenStorage {
    private val storage = mutable.Map[ByteString, Long]()

    override def put(blockHash: ByteString, timestamp: Long): Unit =
      storage(blockHash) = timestamp

    override def get(blockHash: ByteString): Option[Long] =
      storage.get(blockHash)

    override def remove(blockHash: ByteString): Unit =
      storage.remove(blockHash)
  }

  "BlockFirstSeenStorage" should "store and retrieve timestamps" in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val blockHash = ByteString("block1")
    val timestamp = 1234567890L

    storage.put(blockHash, timestamp)
    storage.get(blockHash) shouldBe Some(timestamp)
  }

  it should "return None for non-existent blocks" in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val blockHash = ByteString("nonexistent")

    storage.get(blockHash) shouldBe None
  }

  it should "update existing timestamps" in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val blockHash = ByteString("block1")

    storage.put(blockHash, 1000L)
    storage.get(blockHash) shouldBe Some(1000L)

    storage.put(blockHash, 2000L)
    storage.get(blockHash) shouldBe Some(2000L)
  }

  it should "remove timestamps" in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val blockHash = ByteString("block1")

    storage.put(blockHash, 1000L)
    storage.get(blockHash) shouldBe Some(1000L)

    storage.remove(blockHash)
    storage.get(blockHash) shouldBe None
  }

  it should "check if block exists using contains" in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val blockHash1 = ByteString("block1")
    val blockHash2 = ByteString("block2")

    storage.put(blockHash1, 1000L)

    storage.contains(blockHash1) shouldBe true
    storage.contains(blockHash2) shouldBe false
  }

  it should "handle multiple blocks independently" in {
    val storage = new InMemoryBlockFirstSeenStorage()
    val block1 = ByteString("block1")
    val block2 = ByteString("block2")
    val block3 = ByteString("block3")

    storage.put(block1, 1000L)
    storage.put(block2, 2000L)
    storage.put(block3, 3000L)

    storage.get(block1) shouldBe Some(1000L)
    storage.get(block2) shouldBe Some(2000L)
    storage.get(block3) shouldBe Some(3000L)

    storage.remove(block2)

    storage.get(block1) shouldBe Some(1000L)
    storage.get(block2) shouldBe None
    storage.get(block3) shouldBe Some(3000L)
  }

  it should "handle ByteString hashes correctly" in {
    val storage = new InMemoryBlockFirstSeenStorage()

    // Create different ByteStrings with same content
    val hash1 = ByteString(Array[Byte](1, 2, 3, 4))
    val hash2 = ByteString(Array[Byte](1, 2, 3, 4))

    storage.put(hash1, 1000L)

    // Should retrieve using equivalent ByteString
    storage.get(hash2) shouldBe Some(1000L)
  }
}

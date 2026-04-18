package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.testing.Tags._

/** Tests for FlatSlotStorage — O(1) slot reads by (accountHash, slotHash).
  *
  * Verified against Besu BonsaiFlatDbStrategy:
  *   - putFlatAccountStorageValueByStorageSlotHash: key = accountHash ++ slotHash
  *   - storageToPairStream: seekFrom(accountHash ++ startSlotHash), takeWhile prefix matches
  */
class FlatSlotStorageSpec extends AnyFlatSpec with Matchers {

  "FlatSlotStorage" should "store and retrieve a slot by account and slot hash" taggedAs UnitTest in new TestSetup {
    val accountHash = ByteString(Array.fill(32)(0xaa.toByte))
    val slotHash = ByteString(Array.fill(32)(0x01.toByte))
    val value = ByteString(Array.fill(32)(0xff.toByte))

    storage.putSlotsBatch(accountHash, Seq(slotHash -> value)).commit()
    storage.getSlot(accountHash, slotHash) shouldBe Some(value)
  }

  it should "return None for missing slot" taggedAs UnitTest in new TestSetup {
    val accountHash = ByteString(Array.fill(32)(0xaa.toByte))
    val slotHash = ByteString(Array.fill(32)(0x01.toByte))
    storage.getSlot(accountHash, slotHash) shouldBe None
  }

  it should "store multiple slots for an account in batch" taggedAs UnitTest in new TestSetup {
    val accountHash = ByteString(Array.fill(32)(0xbb.toByte))
    val slots = (1 to 5).map { i =>
      ByteString(Array.fill(32)(i.toByte)) -> ByteString(Array.fill(32)((i * 10).toByte))
    }

    storage.putSlotsBatch(accountHash, slots).commit()

    slots.foreach { case (slotHash, expected) =>
      storage.getSlot(accountHash, slotHash) shouldBe Some(expected)
    }
  }

  it should "isolate slots between different accounts" taggedAs UnitTest in new TestSetup {
    val account1 = ByteString(Array.fill(32)(0x01.toByte))
    val account2 = ByteString(Array.fill(32)(0x02.toByte))
    val slotHash = ByteString(Array.fill(32)(0xaa.toByte))
    val value1 = ByteString(Array.fill(32)(0x11.toByte))
    val value2 = ByteString(Array.fill(32)(0x22.toByte))

    storage.putSlotsBatch(account1, Seq(slotHash -> value1)).commit()
    storage.putSlotsBatch(account2, Seq(slotHash -> value2)).commit()

    storage.getSlot(account1, slotHash) shouldBe Some(value1)
    storage.getSlot(account2, slotHash) shouldBe Some(value2)
  }

  it should "return Stream.empty from seekStorageRange with non-RocksDB backend" taggedAs UnitTest in new TestSetup {
    import cats.effect.unsafe.IORuntime
    implicit val runtime: IORuntime = IORuntime.global

    val accountHash = ByteString(Array.fill(32)(0xaa.toByte))
    val slotHash = ByteString(Array.fill(32)(0x01.toByte))
    val value = ByteString(Array.fill(32)(0xff.toByte))
    storage.putSlotsBatch(accountHash, Seq(slotHash -> value)).commit()

    // EphemDataSource is not RocksDB — seekStorageRange falls through to Stream.empty
    val results =
      storage.seekStorageRange(accountHash, ByteString(Array.fill(32)(0x00.toByte))).compile.toVector.unsafeRunSync()
    results shouldBe empty
  }

  trait TestSetup {
    val dataSource = EphemDataSource()
    val storage = new FlatSlotStorage(dataSource)
  }
}

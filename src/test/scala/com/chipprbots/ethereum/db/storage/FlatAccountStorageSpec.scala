package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.testing.Tags._

/** Tests for FlatAccountStorage — O(1) account reads by keccak256(address).
  */
class FlatAccountStorageSpec extends AnyFlatSpec with Matchers {

  "FlatAccountStorage" should "store and retrieve an account by hash" taggedAs (UnitTest) in new TestSetup {
    val hash = ByteString(Array.fill(32)(0xAA.toByte))
    val rlpAccount = ByteString(Array.fill(64)(0x01.toByte))

    storage.put(hash, rlpAccount).commit()
    storage.getAccount(hash) shouldBe Some(rlpAccount)
  }

  it should "return None for missing hash" taggedAs (UnitTest) in new TestSetup {
    val hash = ByteString(Array.fill(32)(0xBB.toByte))
    storage.getAccount(hash) shouldBe None
  }

  it should "store multiple accounts in batch" taggedAs (UnitTest) in new TestSetup {
    val accounts = (1 to 5).map { i =>
      ByteString(Array.fill(32)(i.toByte)) -> ByteString(Array.fill(64)(i.toByte))
    }

    storage.putAccountsBatch(accounts).commit()

    accounts.foreach { case (hash, expected) =>
      storage.getAccount(hash) shouldBe Some(expected)
    }
  }

  it should "overwrite existing account on re-put" taggedAs (UnitTest) in new TestSetup {
    val hash = ByteString(Array.fill(32)(0xCC.toByte))
    val v1 = ByteString(Array.fill(64)(0x01.toByte))
    val v2 = ByteString(Array.fill(64)(0x02.toByte))

    storage.put(hash, v1).commit()
    storage.getAccount(hash) shouldBe Some(v1)

    storage.put(hash, v2).commit()
    storage.getAccount(hash) shouldBe Some(v2)
  }

  it should "return Stream.empty from seekFrom with non-RocksDB backend" taggedAs (UnitTest) in new TestSetup {
    import cats.effect.unsafe.IORuntime
    implicit val runtime: IORuntime = IORuntime.global

    val hash = ByteString(Array.fill(32)(0xAA.toByte))
    val rlpAccount = ByteString(Array.fill(64)(0x01.toByte))
    storage.put(hash, rlpAccount).commit()

    // EphemDataSource is not RocksDB, so seekFrom returns Stream.empty
    val results = storage.seekFrom(ByteString(Array.fill(32)(0x00.toByte))).compile.toVector.unsafeRunSync()
    results shouldBe empty
  }

  trait TestSetup {
    val dataSource = EphemDataSource()
    val storage = new FlatAccountStorage(dataSource)
  }
}

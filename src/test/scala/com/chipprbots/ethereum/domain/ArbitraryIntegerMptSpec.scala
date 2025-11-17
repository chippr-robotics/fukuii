package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.Generators._

class ArbitraryIntegerMptSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  def keyGen: Gen[BigInt] = byteArrayOfNItemsGen(128).map(BigInt.apply)
  def valueGen: Gen[BigInt] = byteArrayOfNItemsGen(128).map(BigInt.apply)

  "ArbitraryIntegerMpt" should "insert and retrieve values" in new TestSetup {
    forAll(Gen.listOfN(10, keyGen), Gen.listOfN(10, valueGen)) { (keys, values) =>
      val afterInsert = emptyMpt.update(Nil, keys.zip(values))

      keys.zip(values).foreach { case (k, v) =>
        afterInsert.get(k) shouldBe Some(v)
      }
    }
  }

  it should "remove values" in new TestSetup {
    forAll(Gen.listOfN(10, keyGen), Gen.listOfN(10, valueGen)) { (keys, values) =>
      val afterInsert =
        emptyMpt.update(Nil, keys.zip(values))

      keys.zip(values).foreach { case (k, v) =>
        afterInsert.get(k) shouldBe Some(v)
      }

      // remove every 2nd key
      val afterRemove =
        keys.zip(values).zipWithIndex.filter(_._2 % 2 == 0).foldLeft(afterInsert) { case (mpt, ((k, _), _)) =>
          mpt.remove(k)
        }

      keys.zip(values).zipWithIndex.foreach {
        case ((k, _), index) if index % 2 == 0 => afterRemove.get(k) shouldBe None
        case ((k, v), _)                       => afterRemove.get(k) shouldBe Some(v)
      }
    }
  }

  it should "handle zero values correctly" in new TestSetup {
    val key = BigInt(1)
    val zeroValue = BigInt(0)

    val afterInsert = emptyMpt.put(key, zeroValue)
    afterInsert.get(key) shouldBe Some(zeroValue)
  }

  it should "handle serialization of zero value" in new TestSetup {
    // Test that zero value can be serialized and deserialized
    val zeroValue = BigInt(0)
    val bytes = ArbitraryIntegerMpt.bigIntSerializer.toBytes(zeroValue)
    val deserialized = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(bytes)
    deserialized shouldBe zeroValue
  }

  it should "handle empty byte arrays in deserialization" in new TestSetup {
    // This is the critical edge case that was causing the network sync error
    val emptyBytes = Array.empty[Byte]
    val deserialized = ArbitraryIntegerMpt.bigIntSerializer.fromBytes(emptyBytes)
    deserialized shouldBe BigInt(0)
  }

  it should "handle zero-length byte arrays from MPT storage" in new TestSetup {
    // Simulate what happens when MPT returns an empty byte array
    val key = BigInt(1)
    val value = BigInt(0)

    val mptWithValue = emptyMpt.put(key, value)
    val retrieved = mptWithValue.get(key)
    retrieved shouldBe Some(value)
  }

  it should "handle multiple zero values" in new TestSetup {
    val keys = List(BigInt(1), BigInt(2), BigInt(3))
    val values = List(BigInt(0), BigInt(0), BigInt(0))

    val afterInsert = emptyMpt.update(Nil, keys.zip(values))

    keys.zip(values).foreach { case (k, v) =>
      afterInsert.get(k) shouldBe Some(v)
    }
  }

  it should "handle mixed zero and non-zero values" in new TestSetup {
    val keys = List(BigInt(1), BigInt(2), BigInt(3), BigInt(4))
    val values = List(BigInt(0), BigInt(100), BigInt(0), BigInt(200))

    val afterInsert = emptyMpt.update(Nil, keys.zip(values))

    keys.zip(values).foreach { case (k, v) =>
      afterInsert.get(k) shouldBe Some(v)
    }
  }

  trait TestSetup extends EphemBlockchainTestSetup {
    val emptyMpt: MerklePatriciaTrie[BigInt, BigInt] = ArbitraryIntegerMpt.storageMpt(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      storagesInstance.storages.stateStorage.getReadOnlyStorage
    )
  }

}

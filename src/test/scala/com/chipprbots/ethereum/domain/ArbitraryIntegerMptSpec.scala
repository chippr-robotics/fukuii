package com.chipprbots.ethereum.domain

import akka.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
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
        case ((k, v), index) if index % 2 != 0 => afterRemove.get(k) shouldBe Some(v)
      }
    }
  }

  trait TestSetup extends EphemBlockchainTestSetup {
    val emptyMpt: MerklePatriciaTrie[BigInt, BigInt] = ArbitraryIntegerMpt.storageMpt(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      storagesInstance.storages.stateStorage.getReadOnlyStorage
    )
  }

}

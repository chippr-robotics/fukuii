package com.chipprbots.ethereum.utils

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import boopickle.DefaultBasic._

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Picklers._

/** Verify boopickle roundtrip for Olympia-specific types. */
class PicklerOlympiaSpec extends AnyFlatSpec with Matchers {

  def roundtrip[T: Pickler](value: T): T = {
    val buf = Pickle.intoBytes(value)
    Unpickle[T].fromBytes(buf)
  }

  "TransactionWithDynamicFee" should "roundtrip through boopickle" taggedAs (OlympiaTest, UnitTest) in {
    val tx: Transaction = TransactionWithDynamicFee(
      chainId = BigInt(63),
      nonce = BigInt(42),
      maxPriorityFeePerGas = BigInt(1000000000),
      maxFeePerGas = BigInt(2000000000),
      gasLimit = BigInt(21000),
      receivingAddress = Some(Address(1)),
      value = BigInt(1000),
      payload = ByteString.empty,
      accessList = Nil
    )
    val result = roundtrip(tx)
    result shouldBe tx
  }

  "SetCodeTransaction" should "roundtrip through boopickle" taggedAs (OlympiaTest, UnitTest) in {
    val auth = SetCodeAuthorization(
      chainId = BigInt(63),
      address = Address(2),
      nonce = BigInt(0),
      v = BigInt(0),
      r = BigInt(123456),
      s = BigInt(789012)
    )
    val tx: Transaction = SetCodeTransaction(
      chainId = BigInt(63),
      nonce = BigInt(1),
      maxPriorityFeePerGas = BigInt(1000000000),
      maxFeePerGas = BigInt(2000000000),
      gasLimit = BigInt(50000),
      receivingAddress = Some(Address(3)),
      value = BigInt(0),
      payload = ByteString(Array(0x01.toByte, 0x02.toByte)),
      accessList = Nil,
      authorizationList = List(auth)
    )
    val result = roundtrip(tx)
    result shouldBe tx
  }

  "HefPostOlympia" should "roundtrip through boopickle" taggedAs (OlympiaTest, UnitTest) in {
    val hef: HeaderExtraFields = HefPostOlympia(BigInt(1000000000))
    val result = roundtrip(hef)
    result shouldBe hef
  }

  it should "roundtrip with different baseFee values" taggedAs (OlympiaTest, UnitTest) in {
    val hef: HeaderExtraFields = HefPostOlympia(BigInt(7))
    val result = roundtrip(hef)
    result shouldBe hef
  }

  "Mixed transaction types" should "roundtrip in sequence" taggedAs (OlympiaTest, UnitTest) in {
    val legacy: Transaction = LegacyTransaction(
      nonce = BigInt(0),
      gasPrice = BigInt(20000000000L),
      gasLimit = BigInt(21000),
      receivingAddress = Address(1),
      value = BigInt(1000),
      payload = ByteString.empty
    )
    val dynamic: Transaction = TransactionWithDynamicFee(
      chainId = BigInt(63),
      nonce = BigInt(1),
      maxPriorityFeePerGas = BigInt(1000000000),
      maxFeePerGas = BigInt(2000000000),
      gasLimit = BigInt(21000),
      receivingAddress = Some(Address(2)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )

    roundtrip(legacy) shouldBe legacy
    roundtrip(dynamic) shouldBe dynamic
  }
}

package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.PrecompiledContracts.P256Verify

/** EIP-7951: Verify P256VERIFY gas cost = 6,900. */
class P256VerifyGasSpec extends AnyFlatSpec with Matchers {

  val etcFork: EtcForks.Value = EtcForks.Spiral
  val ethFork: EthForks.Value = EthForks.Berlin

  "P256Verify gas" should "be 6900" taggedAs (OlympiaTest, VMTest) in {
    val input = ByteString(new Array[Byte](160)) // standard 160-byte input
    P256Verify.gas(input, etcFork, ethFork) shouldBe BigInt(6900)
  }

  it should "be constant regardless of input size" taggedAs (OlympiaTest, VMTest) in {
    val empty = ByteString.empty
    val short = ByteString(new Array[Byte](32))
    val full = ByteString(new Array[Byte](160))

    P256Verify.gas(empty, etcFork, ethFork) shouldBe BigInt(6900)
    P256Verify.gas(short, etcFork, ethFork) shouldBe BigInt(6900)
    P256Verify.gas(full, etcFork, ethFork) shouldBe BigInt(6900)
  }
}

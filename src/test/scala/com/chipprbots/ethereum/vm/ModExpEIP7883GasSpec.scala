package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.PrecompiledContracts.ModExp

// scalastyle:off magic.number
/** EIP-7883: Verify ModExp gas cost repricing at Olympia fork level.
  *
  * EIP-7883 changes from EIP-2565:
  *   - x <= 32: multComplexity = 16 (flat constant)
  *   - x >  32: multComplexity = 2 * ceil(x/8)^2
  *   - No GQUADDIVISOR (removed entirely)
  *   - Minimum gas = 500 (was 200)
  *   - Exponent length multiplier = 16 (was 8)
  *
  * EIP-7823: Post-Olympia, ModExp rejects operands > 1024 bytes.
  */
class ModExpEIP7883GasSpec extends AnyFlatSpec with Matchers {

  val etcForkOlympia: EtcForks.Value = EtcForks.Olympia
  val etcForkSpiral: EtcForks.Value = EtcForks.Spiral
  val ethFork: EthForks.Value = EthForks.Berlin

  /** Builds a ModExp input: 3x32-byte length headers + zero-filled operands. */
  private def buildModExpInput(baseLen: Int, expLen: Int, modLen: Int): ByteString = {
    def toBytes32(n: Int): ByteString = {
      val arr = new Array[Byte](32)
      arr(28) = ((n >> 24) & 0xff).toByte
      arr(29) = ((n >> 16) & 0xff).toByte
      arr(30) = ((n >> 8) & 0xff).toByte
      arr(31) = (n & 0xff).toByte
      ByteString(arr)
    }
    toBytes32(baseLen) ++ toBytes32(expLen) ++ toBytes32(modLen) ++
      ByteString(new Array[Byte](baseLen)) ++
      ByteString(new Array[Byte](expLen)) ++
      ByteString(new Array[Byte](modLen))
  }

  /** Builds ModExp input with a specific exponent value (first 32 bytes of exp). */
  private def buildModExpInputWithExp(baseLen: Int, expLen: Int, modLen: Int, expFirstByte: Byte): ByteString = {
    def toBytes32(n: Int): ByteString = {
      val arr = new Array[Byte](32)
      arr(28) = ((n >> 24) & 0xff).toByte
      arr(29) = ((n >> 16) & 0xff).toByte
      arr(30) = ((n >> 8) & 0xff).toByte
      arr(31) = (n & 0xff).toByte
      ByteString(arr)
    }
    val expData = new Array[Byte](expLen)
    if (expLen > 0) expData(0) = expFirstByte
    toBytes32(baseLen) ++ toBytes32(expLen) ++ toBytes32(modLen) ++
      ByteString(new Array[Byte](baseLen)) ++
      ByteString(expData) ++
      ByteString(new Array[Byte](modLen))
  }

  "ModExp gas (EIP-7883)" should "return minimum gas of 500 for small inputs" taggedAs (OlympiaTest, VMTest) in {
    // All zero operands of length 1 — x=1, x<=32 so multComplexity=16
    // adjustedExp=0, max(0,1)=1, gas=16*1=16, min(500,16)=500
    val input = buildModExpInput(1, 1, 1)
    val cost = ModExp.gas(input, etcForkOlympia, ethFork)
    cost shouldBe BigInt(500)
  }

  it should "be more expensive than EIP-2565 for all input sizes" taggedAs (OlympiaTest, VMTest) in {
    // EIP-7883 removes /3 divisor and raises minimum to 500, so it's always >= EIP-2565
    val input = buildModExpInputWithExp(64, 64, 64, 0x80.toByte)
    val eip7883Cost = ModExp.gas(input, etcForkOlympia, ethFork)
    val eip2565Cost = ModExp.gas(input, etcForkSpiral, ethFork)
    eip7883Cost should be >= eip2565Cost
    info(s"64-byte: EIP-7883=$eip7883Cost vs EIP-2565=$eip2565Cost")
  }

  it should "be significantly more expensive than EIP-2565 for large inputs" taggedAs (OlympiaTest, VMTest) in {
    // For x=256: EIP-7883 uses 2*words^2 without /3 divisor
    val input = buildModExpInputWithExp(256, 256, 256, 0x80.toByte)
    val eip7883Cost = ModExp.gas(input, etcForkOlympia, ethFork)
    val eip2565Cost = ModExp.gas(input, etcForkSpiral, ethFork)
    eip7883Cost should be > eip2565Cost
    info(s"256-byte: EIP-7883=$eip7883Cost vs EIP-2565=$eip2565Cost (${(eip7883Cost.toDouble / eip2565Cost.toDouble * 100 - 100).round}% increase)")
  }

  it should "calculate correct complexity for 256-byte inputs" taggedAs (OlympiaTest, VMTest) in {
    // x=256: words=ceil(256/8)=32, complexity=2*32^2=2048
    // With all-zero exponent: adjustedExp=0, max(0,1)=1
    // gas = max(500, 2048 * 1) = 2048
    val input = buildModExpInput(256, 32, 256)
    val cost = ModExp.gas(input, etcForkOlympia, ethFork)
    cost shouldBe BigInt(2048)
  }

  it should "calculate correct complexity for 1024-byte inputs" taggedAs (OlympiaTest, VMTest) in {
    // x=1024: words=ceil(1024/8)=128, complexity=2*128^2=32768
    // With all-zero exponent: adjustedExp=0, max(0,1)=1
    // gas = max(500, 32768 * 1) = 32768
    val input = buildModExpInput(1024, 32, 1024)
    val cost = ModExp.gas(input, etcForkOlympia, ethFork)
    cost shouldBe BigInt(32768)
  }

  it should "use pre-Olympia cost for Spiral fork" taggedAs (OlympiaTest, VMTest) in {
    // Spiral (pre-Olympia) should use EIP-2565, not EIP-7883
    // x=256: words=32, complexity=32^2=1024 (no +12*words)
    // gas = max(200, 1024 * 1 / 3) = max(200, 341) = 341
    val input = buildModExpInput(256, 32, 256)
    val cost = ModExp.gas(input, etcForkSpiral, ethFork)
    cost shouldBe BigInt(341)
  }
}
// scalastyle:on magic.number

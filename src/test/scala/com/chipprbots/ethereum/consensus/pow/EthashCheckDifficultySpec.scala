package com.chipprbots.ethereum.consensus.pow

import java.math.BigInteger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.BigIntegers
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.consensus.pow.EthashUtils.EthashProofOfWork
import com.chipprbots.ethereum.consensus.pow.EthashUtils.checkDifficulty
import com.chipprbots.ethereum.testing.Tags.*

/** B-164: the Ethash seal bound, `result <= 2^256 / difficulty`, as core-geth `consensus/ethash/consensus.go`
  * `verifySeal` computes it with unbounded integers (`target := new(big.Int).Div(two256, header.Difficulty)`, rejected
  * if `new(big.Int).SetBytes(result).Cmp(target) > 0`), and refuses a difficulty of 0 or below
  * (`errInvalidDifficulty`).
  *
  * The bound used to be computed from `difficulty.toLong` into a 32-byte array: difficulty 1 threw (2^256 needs 33
  * bytes), 0 divided by zero, and a difficulty above 2^63 was cut to its low 64 bits.
  */
class EthashCheckDifficultySpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private val Two256 = BigInt(1) << 256
  private val Max256 = Two256 - 1

  /** A seal whose result is `n`, as the 32 big-endian bytes hashimoto produces. */
  private def resultOf(n: BigInt): EthashProofOfWork =
    require(n >= 0 && n <= Max256)
    val bytes = n.toByteArray.dropWhile(_ == 0)
    EthashProofOfWork(ByteString(new Array[Byte](32)), ByteString(new Array[Byte](32 - bytes.length) ++ bytes))

  /** The bound as it was before B-164, for the range where it was defined (2 <= difficulty < 2^63): it must agree. */
  private def previousBound(difficulty: Long, pow: EthashProofOfWork): Boolean =
    val target =
      BigIntegers.asUnsignedByteArray(32, BigInteger.ONE.shiftLeft(256).divide(BigInteger.valueOf(difficulty)))
    val result = pow.difficultyBoundary.toArray[Byte]
    java.util.Arrays.compareUnsigned(target, result) >= 0

  "checkDifficulty" should "refuse a difficulty of 0, whatever the result" taggedAs (UnitTest, ConsensusTest) in {
    checkDifficulty(BigInt(0), resultOf(0)) shouldBe false
    checkDifficulty(BigInt(0), resultOf(Max256)) shouldBe false
  }

  it should "refuse a negative difficulty, whatever the result" taggedAs (UnitTest, ConsensusTest) in {
    checkDifficulty(BigInt(-1), resultOf(0)) shouldBe false
    checkDifficulty(BigInt(-1), resultOf(Max256)) shouldBe false
    checkDifficulty(-(BigInt(1) << 200), resultOf(0)) shouldBe false
  }

  it should "accept every result on a difficulty of 1 (target 2^256), without throwing" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    checkDifficulty(BigInt(1), resultOf(0)) shouldBe true
    checkDifficulty(BigInt(1), resultOf(BigInt(1) << 255)) shouldBe true
    checkDifficulty(BigInt(1), resultOf(Max256)) shouldBe true
  }

  it should "split at the target 2^256 / (2^63 + 1), above the Long range" taggedAs (UnitTest, ConsensusTest) in {
    val difficulty = (BigInt(1) << 63) + 1
    val target = Two256 / difficulty
    checkDifficulty(difficulty, resultOf(target)) shouldBe true
    checkDifficulty(difficulty, resultOf(target - 1)) shouldBe true
    checkDifficulty(difficulty, resultOf(target + 1)) shouldBe false
  }

  it should "split at the target 2^256 / 2^200 = 2^56" taggedAs (UnitTest, ConsensusTest) in {
    val difficulty = BigInt(1) << 200
    val target = Two256 / difficulty
    target shouldBe (BigInt(1) << 56)
    checkDifficulty(difficulty, resultOf(target)) shouldBe true
    checkDifficulty(difficulty, resultOf(target - 1)) shouldBe true
    checkDifficulty(difficulty, resultOf(target + 1)) shouldBe false
  }

  it should "accept a difficulty of 2^256 only for the result 0 and 1 (target 1)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    checkDifficulty(Two256, resultOf(0)) shouldBe true
    checkDifficulty(Two256, resultOf(1)) shouldBe true
    checkDifficulty(Two256, resultOf(2)) shouldBe false
    // Above 2^256 the target is 0: only the result 0 meets it.
    checkDifficulty(Two256 + 1, resultOf(0)) shouldBe true
    checkDifficulty(Two256 + 1, resultOf(1)) shouldBe false
  }

  it should "split at the target at Mordor's minimum difficulty 131072" taggedAs (UnitTest, ConsensusTest) in {
    val difficulty = BigInt(131072)
    val target = Two256 / difficulty
    checkDifficulty(difficulty, resultOf(target)) shouldBe true
    checkDifficulty(difficulty, resultOf(target + 1)) shouldBe false
  }

  it should "give the answer the previous bound gave for every difficulty it handled (2 to 2^63 - 1)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val difficulties = Gen.oneOf(Gen.choose(2L, 1L << 20), Gen.choose(2L, Long.MaxValue))
    val near = for
      d <- difficulties
      offset <- Gen.choose(-2, 2)
    yield (d, ((Two256 / d) + offset).max(0).min(Max256))
    val anywhere = for
      d <- difficulties
      bytes <- Gen.listOfN(32, Gen.choose(Byte.MinValue, Byte.MaxValue))
    yield (d, BigInt(1, bytes.toArray))
    forAll(Gen.oneOf(near, anywhere), minSuccessful(2000)) { case (d, n) =>
      val pow = resultOf(n)
      checkDifficulty(BigInt(d), pow) shouldBe previousBound(d, pow)
    }
  }

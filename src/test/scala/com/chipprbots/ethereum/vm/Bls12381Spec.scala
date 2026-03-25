package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s._
import org.json4s.native.JsonMethods._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.PrecompiledContracts._

/** EIP-2537 BLS12-381 precompile tests using geth reference test vectors. */
class Bls12381Spec extends AnyWordSpec with Matchers with Inspectors {

  // Skip all tests if native library is not available
  assume(Bls12381.isAvailable, "BLS12-381 native library not available — skipping tests")

  private def hexToBytes(hex: String): ByteString =
    if (hex.isEmpty) ByteString.empty
    else ByteString(hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  private def loadTestVectors(resource: String): List[JValue] = {
    val stream = getClass.getClassLoader.getResourceAsStream(resource)
    require(stream != null, s"Test resource not found: $resource")
    val json = parse(scala.io.Source.fromInputStream(stream).mkString)
    json.asInstanceOf[JArray].arr
  }

  private case class SuccessVector(name: String, input: ByteString, expected: ByteString, gas: BigInt)
  private case class FailVector(name: String, input: ByteString)

  private def parseSuccess(vectors: List[JValue]): List[SuccessVector] = vectors.map { v =>
    implicit val formats: DefaultFormats.type = DefaultFormats
    SuccessVector(
      name = (v \ "Name").extract[String],
      input = hexToBytes((v \ "Input").extract[String]),
      expected = hexToBytes((v \ "Expected").extract[String]),
      gas = BigInt((v \ "Gas").extract[Long])
    )
  }

  private def parseFail(vectors: List[JValue]): List[FailVector] = vectors.map { v =>
    implicit val formats: DefaultFormats.type = DefaultFormats
    FailVector(
      name = (v \ "Name").extract[String],
      input = hexToBytes((v \ "Input").extract[String])
    )
  }

  private val dummyEtcFork = EtcForks.Olympia
  private val dummyEthFork = EthForks.Berlin // latest ETH fork enum value in fukuii

  "BLS12-381 native library" should {
    "be available on this platform" taggedAs UnitTest in {
      Bls12381.isAvailable shouldBe true
    }
  }

  "BlsG1Add (0x0b)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsG1Add.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsG1Add.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG1Add.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG1Add.exec(v.input) shouldBe None
        }
      }
    }

    "charge 375 gas" taggedAs UnitTest in {
      BlsG1Add.gas(ByteString(new Array[Byte](256)), dummyEtcFork, dummyEthFork) shouldBe BigInt(375)
    }
  }

  "BlsG1MultiExp (0x0c)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsG1MultiExp.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsG1MultiExp.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG1MultiExp.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG1MultiExp.exec(v.input) shouldBe None
        }
      }
    }

    "return None for empty input" taggedAs UnitTest in {
      BlsG1MultiExp.exec(ByteString.empty) shouldBe None
    }

    "charge 0 gas for empty input" taggedAs UnitTest in {
      BlsG1MultiExp.gas(ByteString.empty, dummyEtcFork, dummyEthFork) shouldBe BigInt(0)
    }

    "charge correct gas for k=1" taggedAs UnitTest in {
      val input = ByteString(new Array[Byte](160))
      BlsG1MultiExp.gas(input, dummyEtcFork, dummyEthFork) shouldBe BigInt(12000) // 12000 * 1 * 1000 / 1000
    }

    "match gas from test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name} gas: ") {
          BlsG1MultiExp.gas(v.input, dummyEtcFork, dummyEthFork) shouldBe v.gas
        }
      }
    }
  }

  "BlsG2Add (0x0d)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsG2Add.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsG2Add.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG2Add.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG2Add.exec(v.input) shouldBe None
        }
      }
    }

    "charge 600 gas" taggedAs UnitTest in {
      BlsG2Add.gas(ByteString(new Array[Byte](512)), dummyEtcFork, dummyEthFork) shouldBe BigInt(600)
    }
  }

  "BlsG2MultiExp (0x0e)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsG2MultiExp.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsG2MultiExp.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG2MultiExp.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsG2MultiExp.exec(v.input) shouldBe None
        }
      }
    }

    "return None for empty input" taggedAs UnitTest in {
      BlsG2MultiExp.exec(ByteString.empty) shouldBe None
    }

    "charge 0 gas for empty input" taggedAs UnitTest in {
      BlsG2MultiExp.gas(ByteString.empty, dummyEtcFork, dummyEthFork) shouldBe BigInt(0)
    }

    "match gas from test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name} gas: ") {
          BlsG2MultiExp.gas(v.input, dummyEtcFork, dummyEthFork) shouldBe v.gas
        }
      }
    }
  }

  "BlsPairing (0x0f)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsPairing.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsPairing.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsPairing.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsPairing.exec(v.input) shouldBe None
        }
      }
    }

    "return None for empty input" taggedAs UnitTest in {
      BlsPairing.exec(ByteString.empty) shouldBe None
    }

    "charge 0 gas for empty input" taggedAs UnitTest in {
      BlsPairing.gas(ByteString.empty, dummyEtcFork, dummyEthFork) shouldBe BigInt(0)
    }

    "charge correct gas for k=1" taggedAs UnitTest in {
      val input = ByteString(new Array[Byte](384))
      BlsPairing.gas(input, dummyEtcFork, dummyEthFork) shouldBe BigInt(32600 + 37700) // 70300
    }

    "match gas from test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name} gas: ") {
          BlsPairing.gas(v.input, dummyEtcFork, dummyEthFork) shouldBe v.gas
        }
      }
    }
  }

  "BlsMapG1 (0x10)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsMapG1.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsMapG1.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsMapG1.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsMapG1.exec(v.input) shouldBe None
        }
      }
    }

    "charge 5500 gas" taggedAs UnitTest in {
      BlsMapG1.gas(ByteString(new Array[Byte](64)), dummyEtcFork, dummyEthFork) shouldBe BigInt(5500)
    }
  }

  "BlsMapG2 (0x11)" should {
    val successVectors = parseSuccess(loadTestVectors("bls12381/blsMapG2.json"))
    val failVectors = parseFail(loadTestVectors("bls12381/fail-blsMapG2.json"))

    "compute correct results for all geth test vectors" taggedAs UnitTest in {
      forAll(successVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsMapG2.exec(v.input) shouldBe Some(v.expected)
        }
      }
    }

    "return None for invalid inputs" taggedAs UnitTest in {
      forAll(failVectors) { v =>
        withClue(s"${v.name}: ") {
          BlsMapG2.exec(v.input) shouldBe None
        }
      }
    }

    "charge 23800 gas" taggedAs UnitTest in {
      BlsMapG2.gas(ByteString(new Array[Byte](128)), dummyEtcFork, dummyEthFork) shouldBe BigInt(23800)
    }
  }
}

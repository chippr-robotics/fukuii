package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.testing.Tags.*

/** Tests for the KZGPointEvaluation precompile (EIP-4844, address 0x0A).
  *
  * Loads the mainnet KZG trusted setup so that CKZG4844JNI.verifyKzgProof actually runs. Without the setup c-kzg throws
  * `RuntimeException: Trusted Setup is not loaded.`, which raises NativeBackendUnavailable instead of failing the call
  * (B-135, NativeBackendsSpec).
  *
  * Test vectors from go-ethereum: core/vm/testdata/precompiles/pointEvaluation.json
  */
class KzgPointEvaluationSpec extends AnyFunSuite with BeforeAndAfterAll with Matchers:

  // Load once per JVM via the shared fixture; never free here. Freeing the process-global native setup
  // in afterAll races with KzgCellProofsSpec when suites run concurrently (parallelExecution = true).
  override def beforeAll(): Unit = KzgTestSetup.ensureLoaded()

  // go-ethereum pointEvaluation1 vector (192 bytes = 384 hex chars)
  private val validInput = ByteString(
    Hex.decode(
      "01e798154708fe7789429634053cbf9f99b619f9f084048927333fce637f549b" +
        "564c0a11a0f704f4fc3e8acfe0f8245f0ad1347b378fbf96e206da11a5d36306" +
        "24d25032e67a7e6a4910df5834b8fe70e6bcfeeac0352434196bdf4b2485d5a1" +
        "8f59a8d2a1a625a17f3fea0fe5eb8c896db3764f3185481bc22f91b4aaffcca2" +
        "5f26936857bc3a7c2539ea8ec3a952b7873033e038326e87ed3e1276fd140253" +
        "fa08e9fc25fb2d9a98527fc22a2c9612fbeafdad446cbc7bcdbdcd780af2c16a"
    )
  )

  private val expectedOutput = ByteString(
    Hex.decode(
      "0000000000000000000000000000000000000000000000000000000000001000" +
        "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001"
    )
  )

  test("KZGPointEvaluation valid proof returns FIELD_ELEMENTS_PER_BLOB ++ BLS_MODULUS", SlowTest, VMTest) {
    val result = PrecompiledContracts.KzgPointEvaluation.exec(validInput)
    result shouldBe Some(expectedOutput)
  }

  test("KZGPointEvaluation invalid proof (corrupted proof bytes) reverts", SlowTest, VMTest) {
    // Corrupt one byte of the proof field (bytes 144-191); passes hash/field checks but fails KZG verify
    val arr = validInput.toArray
    arr(144) = (arr(144) ^ 0xff.toByte).toByte
    val result = PrecompiledContracts.KzgPointEvaluation.exec(ByteString(arr))
    result shouldBe None
  }

  test("KZGPointEvaluation wrong input length reverts", UnitTest, VMTest) {
    val result = PrecompiledContracts.KzgPointEvaluation.exec(ByteString(Array.fill[Byte](191)(0)))
    result shouldBe None
  }

  test("KZGPointEvaluation wrong versioned hash version byte reverts", UnitTest, VMTest) {
    val badVersion = validInput.toArray
    badVersion(0) = 0x02.toByte // version 0x02 instead of 0x01
    val result = PrecompiledContracts.KzgPointEvaluation.exec(ByteString(badVersion))
    result shouldBe None
  }

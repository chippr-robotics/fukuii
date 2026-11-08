package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.typesafe.config.ConfigFactory
import ethereum.ckzg4844.CKZGException
import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.NativeBackends.*

/** B-135: a missing or broken native backend never becomes a precompile result.
  *
  * The BLS12-381 and KZG precompiles used to read "library missing" as an ordinary failure, so a node without the
  * library consumed the call's gas, returned nothing, and forked itself off the network without a word. The call-time
  * cases drive [[NativeBackends.blsOperation]] and [[NativeBackends.kzgProofVerifies]], which the precompiles call,
  * with stand-in backends. The startup cases pin which backends each shipped chain needs and the self-check that stops
  * the node.
  */
class NativeBackendsSpec extends AnyFlatSpec with Matchers:

  private val G1Add: Byte = LibEthPairings.BLS12_G1ADD_OPERATION_RAW_VALUE
  private val blsInput = ByteString(new Array[Byte](256))

  private def bls(isEnabled: Boolean)(answer: => Option[Array[Byte]]): BlsNative = new BlsNative:
    def enabled: Boolean = isEnabled
    def perform(operation: Byte, input: Array[Byte]): Option[Array[Byte]] = answer

  private def kzg(answer: => Boolean): KzgNative = new KzgNative:
    def verifyKzgProof(commitment: Array[Byte], z: Array[Byte], y: Array[Byte], proof: Array[Byte]): Boolean = answer

  private def verifyWith(native: KzgNative): Boolean =
    kzgProofVerifies(native, new Array[Byte](48), new Array[Byte](32), new Array[Byte](32), new Array[Byte](48))

  // ── Call time: BLS12-381 ───────────────────────────────────────────────────────────────────────────────────────────

  "A BLS12-381 precompile whose library did not load" should "raise NativeBackendUnavailable, not fail the call" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val missing = bls(isEnabled = false)(fail("a library that did not load must not be called"))
    val error = the[NativeBackendUnavailable] thrownBy blsOperation(missing, G1Add, blsInput)
    error.backend shouldBe Backend.Bls12381
    error.getMessage should include("bls12-381")
    error.getMessage should include("LibEthPairings.ENABLED is false")
    // Fatal for every `case NonFatal(e)` handler on the import and RPC paths.
    NonFatal(error) shouldBe false
  }

  "A BLS12-381 native call that throws" should "raise NativeBackendUnavailable carrying the cause" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val cause = new IllegalStateException("JNA could not invoke eip2537_perform_operation")
    val error = the[NativeBackendUnavailable] thrownBy blsOperation(bls(isEnabled = true)(throw cause), G1Add, blsInput)
    error.backend shouldBe Backend.Bls12381
    error.getCause shouldBe cause
  }

  "A BLS12-381 input the library rejects" should "stay an ordinary precompile failure" taggedAs (UnitTest, VMTest) in {
    blsOperation(bls(isEnabled = true)(None), G1Add, blsInput) shouldBe None
  }

  "A BLS12-381 result the library computes" should "be returned byte for byte" taggedAs (UnitTest, VMTest) in {
    val output = Array.tabulate[Byte](128)(_.toByte)
    blsOperation(bls(isEnabled = true)(Some(output)), G1Add, blsInput) shouldBe Some(ByteString(output))
  }

  // ── Call time: KZG ─────────────────────────────────────────────────────────────────────────────────────────────────

  "A KZG point evaluation without the trusted setup" should "raise NativeBackendUnavailable, not fail the call" taggedAs (
    UnitTest,
    VMTest
  ) in {
    // What c-kzg throws when nothing loaded the setup (a plain RuntimeException, not a CKZGException).
    val error = the[NativeBackendUnavailable] thrownBy verifyWith(
      kzg(throw new RuntimeException("Trusted Setup is not loaded."))
    )
    error.backend shouldBe Backend.Kzg
    error.getMessage should include("c-kzg-4844")
    NonFatal(error) shouldBe false
  }

  "A KZG point evaluation whose library did not load" should "raise NativeBackendUnavailable" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val cause = new UnsatisfiedLinkError("ethereum.ckzg4844.CKZG4844JNI.verifyKzgProof([B[B[B[B)Z")
    val error = the[NativeBackendUnavailable] thrownBy verifyWith(kzg(throw cause))
    error.backend shouldBe Backend.Kzg
    error.getCause shouldBe cause
  }

  "A KZG input c-kzg rejects" should "stay an ordinary precompile failure" taggedAs (UnitTest, VMTest) in {
    // C_KZG_BADARGS: a commitment or proof that is not a valid point.
    verifyWith(kzg(throw new CKZGException(1, "Invalid commitment"))) shouldBe false
  }

  "A KZG proof c-kzg checks" should "return c-kzg's answer" taggedAs (UnitTest, VMTest) in {
    verifyWith(kzg(true)) shouldBe true
    verifyWith(kzg(false)) shouldBe false
  }

  // ── Startup ────────────────────────────────────────────────────────────────────────────────────────────────────────

  private lazy val shippedRoot = ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  private def withOlympiaAt(config: BlockchainConfig, block: BigInt): BlockchainConfig =
    config.copy(forkBlockNumbers = config.forkBlockNumbers.copy(olympiaBlockNumber = block))

  "The backends a chain needs" should "be both on the ETH chains, from Cancun and Prague" taggedAs (
    UnitTest,
    VMTest
  ) in {
    NativeBackends.required(shipped("eth")) shouldBe Set(Backend.Bls12381, Backend.Kzg)
    NativeBackends.required(shipped("sepolia")) shouldBe Set(Backend.Bls12381, Backend.Kzg)
  }

  it should "be none on ETC and Mordor while Olympia is not scheduled" taggedAs (UnitTest, VMTest) in {
    NativeBackends.required(shipped("etc")) shouldBe empty
    NativeBackends.required(shipped("mordor")) shouldBe empty
    NativeBackends.required(withOlympiaAt(shipped("etc"), BigInt(Long.MaxValue))) shouldBe empty
  }

  it should "be both on ETC and Mordor once Olympia is scheduled, which activates the Osaka precompile set" taggedAs (
    UnitTest,
    VMTest
  ) in {
    NativeBackends.required(withOlympiaAt(shipped("etc"), BigInt(25_000_000))) shouldBe Set(
      Backend.Bls12381,
      Backend.Kzg
    )
    NativeBackends.required(withOlympiaAt(shipped("mordor"), BigInt(15_000_000))) shouldBe
      Set(Backend.Bls12381, Backend.Kzg)
  }

  "The startup self-check" should "name each required backend whose probe fails" taggedAs (UnitTest, VMTest) in {
    val errors = NativeBackends.selfCheck(
      Set(Backend.Bls12381, Backend.Kzg),
      {
        case Backend.Bls12381 => Left("the native library did not load (LibEthPairings.ENABLED is false)")
        case Backend.Kzg      => Right(())
      }
    )
    errors should have size 1
    errors.head should include("bls12-381")
    errors.head should include("LibEthPairings.ENABLED is false")
    errors.head should include("will not start")
  }

  it should "pass when every required backend works, and probe nothing a chain does not need" taggedAs (
    UnitTest,
    VMTest
  ) in {
    NativeBackends.selfCheck(Set(Backend.Bls12381, Backend.Kzg), _ => Right(())) shouldBe empty
    NativeBackends.selfCheck(
      Set.empty,
      backend => fail(s"$backend is not required and must not be probed")
    ) shouldBe empty
  }

  it should "pass the real BLS12-381 library through the precompile's own path" taggedAs (UnitTest, VMTest) in {
    assume(LibEthPairings.ENABLED, "BLS12-381 native library not loaded")
    NativeBackends.probeBackend(Backend.Bls12381) shouldBe Right(())
  }

  it should "pass the real c-kzg library once the trusted setup is loaded" taggedAs (UnitTest, VMTest) in {
    // Cancelled, not failed, where c-kzg has no native build or the setup does not load, as the BLS case above.
    val loaded =
      try
        KzgTestSetup.ensureLoaded()
        true
      catch case _: (Exception | LinkageError) => false
    assume(loaded, "c-kzg-4844 native library or KZG trusted setup not loaded")
    NativeBackends.probeBackend(Backend.Kzg) shouldBe Right(())
  }

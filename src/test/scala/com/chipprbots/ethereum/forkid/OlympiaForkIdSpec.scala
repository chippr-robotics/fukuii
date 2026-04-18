package com.chipprbots.ethereum.forkid

import java.util.zip.CRC32

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils._
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.forkid.ForkIdValidator.ioLogger
import com.chipprbots.ethereum.testing.Tags._

// scalastyle:off magic.number
/** Verifies ForkId computation and peer validation at the Olympia fork boundary.
  *
  * Tests that ForkId correctly transitions when Olympia activates, and that ForkIdValidator properly accepts/rejects
  * peers based on fork compatibility.
  *
  * Reference: EIP-2124 (Fork Identifier), all 5 reference clients implement this. Fukuii H-015 backlog item.
  */
class OlympiaForkIdSpec extends AnyFlatSpec with Matchers {

  implicit val runtime: IORuntime = IORuntime.global

  // Use a deterministic genesis hash for testing
  private val genesisHash = ByteString(Hex.decode("a68ebde7932eccb177d38d55dcc6461a019dd795a681e59b5a3e4f3a7259a3f1"))

  // Simplified fork list: Spiral at 9957000, Olympia at 15800000
  private val SpiralBlock: BigInt = 9957000
  private val OlympiaBlock: BigInt = 15800000

  // Fork list representing a chain with Spiral + Olympia
  private val forksWithOlympia: List[BigInt] = List(SpiralBlock, OlympiaBlock)

  // Fork list representing a chain that only knows about Spiral (no Olympia awareness)
  @annotation.nowarn("msg=unused private member")
  private val forksWithoutOlympia: List[BigInt] = List(SpiralBlock)

  // Pre-compute CRC32 checksums for expected ForkId hashes
  private def crc32(genesis: ByteString, forkBlocks: BigInt*): Long = {
    val crc = new CRC32()
    crc.update(genesis.asByteBuffer)
    forkBlocks.foreach(b => crc.update(bigIntToBytes(b, 8)))
    crc.getValue()
  }

  private val hashGenesis = crc32(genesisHash)
  private val hashSpiral = crc32(genesisHash, SpiralBlock)
  private val hashOlympia = crc32(genesisHash, SpiralBlock, OlympiaBlock)

  private def validatePeer(forks: List[BigInt])(head: BigInt, remoteForkId: ForkId): ForkIdValidationResult =
    ForkIdValidator
      .validatePeer[IO](genesisHash, forks)(head, remoteForkId)
      .unsafeRunSync()

  // ===== ForkId Computation =====

  "OlympiaForkId" should "announce Olympia as next fork before activation" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    // Before Spiral: genesis hash, next = Spiral
    ForkId.create(genesisHash, forksWithOlympia)(0) shouldBe ForkId(hashGenesis, Some(SpiralBlock))

    // After Spiral, before Olympia: Spiral hash, next = Olympia
    ForkId.create(genesisHash, forksWithOlympia)(SpiralBlock) shouldBe ForkId(hashSpiral, Some(OlympiaBlock))
    ForkId.create(genesisHash, forksWithOlympia)(OlympiaBlock - 1) shouldBe ForkId(hashSpiral, Some(OlympiaBlock))
  }

  it should "update hash and clear next at Olympia activation" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    // At Olympia block: hash includes Olympia, no next fork
    ForkId.create(genesisHash, forksWithOlympia)(OlympiaBlock) shouldBe ForkId(hashOlympia, None)
    ForkId.create(genesisHash, forksWithOlympia)(OlympiaBlock + 1000) shouldBe ForkId(hashOlympia, None)
  }

  it should "not include Olympia in fork list when set to noFork placeholder" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    // When olympiaBlockNumber = noFork (10^18), gatherForks should exclude it
    val forks = ForkId.gatherForks(configWithOlympiaPlaceholder)
    forks should not contain ForkId.noFork

    // Spiral should be the last fork in the gathered list
    forks.last shouldBe SpiralBlock
  }

  it should "include Olympia in fork list when set to a real block number" taggedAs (
    UnitTest,
    OlympiaTest
  ) in {
    val forks = ForkId.gatherForks(configWithOlympia)
    forks should contain(OlympiaBlock)

    // ForkId at Spiral should announce Olympia as next
    ForkId.create(genesisHash, configWithOlympia)(SpiralBlock) shouldBe ForkId(hashSpiral, Some(OlympiaBlock))
  }

  // ===== Peer Validation: Same Fork State =====

  it should "accept peer with matching ForkId (both post-Olympia)" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Both at Olympia, same hash, no next fork
    validate(OlympiaBlock + 100, ForkId(hashOlympia, None)) shouldBe Connect
  }

  it should "accept peer with matching ForkId (both pre-Olympia, aware of Olympia)" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Both in Spiral era, both know about upcoming Olympia
    validate(SpiralBlock + 1000, ForkId(hashSpiral, Some(OlympiaBlock))) shouldBe Connect
  }

  // ===== Peer Validation: Remote is Syncing =====

  it should "accept syncing remote (remote at Spiral, local at Olympia)" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Remote is at Spiral era, knows Olympia is next → remote is just syncing
    validate(OlympiaBlock + 100, ForkId(hashSpiral, Some(OlympiaBlock))) shouldBe Connect
  }

  // ===== Peer Validation: Stale Remote =====

  it should "reject stale remote that does not know about Olympia" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Local is post-Olympia, remote is at Spiral with no next fork announced
    // Remote needs software update — doesn't know about Olympia at all
    validate(OlympiaBlock + 100, ForkId(hashSpiral, None)) shouldBe ErrRemoteStale
  }

  // ===== Peer Validation: Local is Syncing =====

  it should "accept when local is syncing (local at Spiral, remote at Olympia)" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Local is at Spiral, remote is already at Olympia. Local can catch up.
    validate(SpiralBlock + 1000, ForkId(hashOlympia, None)) shouldBe Connect
  }

  // ===== Peer Validation: Incompatible Fork =====

  it should "reject incompatible remote (different fork hash)" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Remote announces a completely unknown hash — different chain or incompatible fork
    validate(OlympiaBlock + 100, ForkId(0xdeadbeefL, None)) shouldBe ErrLocalIncompatibleOrStale
  }

  it should "reject remote that forked at a different Olympia block" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validate = validatePeer(forksWithOlympia) _

    // Remote is at Spiral but announces a wrong next fork block (not our Olympia)
    // This means remote expects a fork at a different block — chain split scenario
    validate(OlympiaBlock + 100, ForkId(hashSpiral, Some(OlympiaBlock + 1000))) shouldBe ErrRemoteStale
  }

  // ===== Cross-Configuration: Upgraded vs Non-Upgraded Nodes =====

  it should "detect incompatibility between Olympia-aware and non-aware nodes after fork" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    // Simulate: Local node has Olympia fork, remote does NOT
    // After Olympia activates, the hashes diverge
    val validateLocal = validatePeer(forksWithOlympia) _

    // Remote is running old software: at block past Olympia but hash is still Spiral-era
    // (remote's chain has no Olympia fork, so hash stays at hashSpiral)
    // Local sees remote at Spiral hash with no next fork — stale
    validateLocal(OlympiaBlock + 100, ForkId(hashSpiral, None)) shouldBe ErrRemoteStale
  }

  it should "allow connection from non-upgraded node before fork activates" taggedAs (
    UnitTest,
    OlympiaTest,
    NetworkTest
  ) in {
    val validateLocal = validatePeer(forksWithOlympia) _

    // Pre-fork: local knows about Olympia, remote does not, but we're both in Spiral era.
    // Remote at Spiral with no next fork. Per EIP-2124 rule 3 (superset check):
    // remote hash (hashSpiral) is in local's checksum list at the Spiral index,
    // so remote might just be syncing or unaware of future forks — still safe to connect.
    // This is by design: before a fork activates, we can't distinguish upgraded from
    // non-upgraded nodes because their chain state is identical.
    validateLocal(SpiralBlock + 1000, ForkId(hashSpiral, None)) shouldBe Connect
  }

  // ===== Helper: ForkId.create with fork list (private API, test via wrapper) =====

  // Use ForkId.create with a custom BlockchainConfig for integration testing
  private object ForkIdHelper {
    def create(genesis: ByteString, forks: List[BigInt])(head: BigInt): ForkId = {
      val crc = new CRC32()
      crc.update(genesis.asByteBuffer)
      val next = forks.find { fork =>
        if (fork <= head) crc.update(bigIntToBytes(fork, 8))
        fork > head
      }
      new ForkId(crc.getValue(), next)
    }
  }

  // Minimal BlockchainConfig with Spiral + Olympia at placeholder (noFork)
  // All forks set to 0 (filtered by gatherForks) except Spiral and Olympia
  private val configWithOlympiaPlaceholder: BlockchainConfig = BlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 0,
      eip106BlockNumber = 0,
      eip150BlockNumber = 0,
      eip155BlockNumber = 0,
      eip160BlockNumber = 0,
      eip161BlockNumber = 0,
      difficultyBombPauseBlockNumber = 0,
      difficultyBombContinueBlockNumber = 0,
      difficultyBombRemovalBlockNumber = 0,
      byzantiumBlockNumber = 0,
      constantinopleBlockNumber = 0,
      istanbulBlockNumber = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 0,
      phoenixBlockNumber = 0,
      petersburgBlockNumber = 0,
      ecip1099BlockNumber = 0,
      muirGlacierBlockNumber = 0,
      magnetoBlockNumber = 0,
      berlinBlockNumber = 0,
      mystiqueBlockNumber = 0,
      spiralBlockNumber = SpiralBlock,
      olympiaBlockNumber = ForkId.noFork
    ),
    daoForkConfig = None,
    maxCodeSize = None,
    chainId = 0x3f,
    networkId = 7,
    monetaryPolicyConfig = MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L),
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    accountStartNonce = UInt256.Zero,
    bootstrapNodes = Set(),
    gasTieBreaker = false,
    ethCompatibleStorage = true
  )

  // Same config but with Olympia at a real block number
  private val configWithOlympia: BlockchainConfig =
    configWithOlympiaPlaceholder.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = OlympiaBlock))

  // Wrap ForkId.create to use List[BigInt] directly (matching the private test API pattern)
  implicit private class ForkIdCreateWithList(obj: ForkId.type) {
    def create(genesis: ByteString, forks: List[BigInt])(head: BigInt): ForkId =
      ForkIdHelper.create(genesis, forks)(head)
  }
}

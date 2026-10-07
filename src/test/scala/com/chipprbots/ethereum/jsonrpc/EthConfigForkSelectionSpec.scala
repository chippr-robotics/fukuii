package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe

import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** End-to-end fork selection for EIP-7910 `eth_config` on a timestamp-gated (ETH-family) chain.
  *
  * The encoder shape is pinned separately by `EthConfigJsonShapeSpec`; this spec covers the part that picks WHICH fork
  * is current. The schedule is hive's rpc-compat fixture (shanghai 390 … bpo2 540) and the head sits at 540, so the
  * active fork is BPO2 and nothing follows it.
  *
  * The previous implementation walked the block-numbered ETC schedule regardless of network type, so on an ETH chain it
  * reported an ETC fork's activation block and fabricated `next`/`last` entries from a 1e18 sentinel.
  */
class EthConfigForkSelectionSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with OptionValues
    with MockFactory:

  implicit private val classicActorSystem: ActorSystem = system.toClassic

  "eth_config on an ETH-family chain" should "report the active timestamp fork, with no fork after it" in new TestSetup:
    val response = ethService.config(ConfigRequest()).unsafeRunSync().toOption.value

    val current = response.current.value
    current.activationTime shouldBe Some(540L)
    current.activationBlock shouldBe None
    current.forkId should not be empty

    // BPO2 blob parameters (EIP-7892), in blobs: target 14, max 21.
    val blobs = current.blobSchedule.value
    blobs.target shouldBe BigInt(14)
    blobs.max shouldBe BigInt(21)
    blobs.baseFeeUpdateFraction shouldBe BigInt(11684671)

    current.precompiles.keySet should contain("KZG_POINT_EVALUATION")
    current.precompiles should have size 18
    current.systemContracts.keySet shouldBe Set(
      "BEACON_ROOTS_ADDRESS",
      "CONSOLIDATION_REQUEST_PREDEPLOY_ADDRESS",
      "DEPOSIT_CONTRACT_ADDRESS",
      "HISTORY_STORAGE_ADDRESS",
      "WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS"
    )

    // BPO2 is the final scheduled fork — EIP-7910 wants null, not a sentinel object.
    response.next shouldBe None
    response.last shouldBe None

  it should "report the next and last forks while the head is mid-schedule" in new TestSetup:
    override def headTime: Long = 430L // past cancun (420), before prague (450)

    val response = ethService.config(ConfigRequest()).unsafeRunSync().toOption.value
    response.current.value.activationTime shouldBe Some(420L)
    response.next.value.activationTime shouldBe Some(450L)
    response.last.value.activationTime shouldBe Some(540L)

    // Cancun predates the BLS precompiles (Prague) and P256VERIFY (Osaka).
    response.current.value.precompiles should have size 10
    response.current.value.systemContracts.keySet shouldBe Set("BEACON_ROOTS_ADDRESS")

  class TestSetup(implicit system: ActorSystem) extends EphemBlockchainTestSetup:
    def headTime: Long = 540L

    /** hive rpc-compat fixture schedule (tests/genesis.json). */
    override lazy val blockchainConfig = super.blockchainConfig.copy(
      networkType = NetworkType.ETH,
      forkTimestamps = ForkTimestamps(
        shanghaiTimestamp = Some(390L),
        cancunTimestamp = Some(420L),
        pragueTimestamp = Some(450L),
        osakaTimestamp = Some(480L),
        bpo1Timestamp = Some(510L),
        bpo2Timestamp = Some(540L)
      )
    )

    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val keyStore: KeyStore = mock[KeyStore]
    override lazy val stxLedger: StxLedger = mock[StxLedger]
    override lazy val mining: TestMining = buildTestMining().withBlockGenerator(blockGenerator)
    override lazy val miningConfig = MiningConfigs.miningConfig

    val syncingController: TestProbe = TestProbe()

    // Genesis doubles as the head: `config` only needs block 0 (for the fork-id genesis hash)
    // and the best block's timestamp.
    val genesis: Block = Block(
      Fixtures.Blocks.Genesis.header.copy(unixTimestamp = Timestamp(headTime)),
      Fixtures.Blocks.Genesis.body
    )
    blockchainWriter.save(genesis, Nil, ChainWeight.zero, saveAsBestBlock = true)

    lazy val ethService = new EthInfoService(
      blockchain,
      blockchainReader,
      blockchainConfig,
      mining,
      stxLedger,
      keyStore,
      syncingController.ref.toTyped[SyncController.Command],
      Capability.ETH63,
      Timeouts.shortTimeout,
      system.toTyped.scheduler
    )

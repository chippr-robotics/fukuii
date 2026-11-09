package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*

/** #1727 class C: `blockOverrides.beaconRoot` is the simulated block's parent beacon block root (go-ethereum
  * `BlockOverrides.BeaconRoot`; `makeHeaders`, simulate.go:654-660). It is the header's `parentBeaconBlockRoot`, so the
  * EIP-4788 system call stores it, from Cancun on; before Cancun a header has no such field and the override is not
  * read. Without it the root is zero, as it was.
  *
  * The chain is the Amsterdam fixture schedule (Shanghai 0, Cancun 60, Prague 120, Osaka 180, Amsterdam 360) on an
  * empty state.
  */
// scalastyle:off magic.number
class EthSimulateBeaconRootSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(new Array[Byte](32))
  private val Root: ByteString = ByteString(Array.fill[Byte](32)(0xab.toByte))

  /** A node whose best block sits at `baseTimestamp`, in the header shape its fork gives it. */
  private class Node(baseTimestamp: Long) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: com.chipprbots.ethereum.utils.BlockchainConfig = amsterdamConfig

    private val ts = Timestamp(baseTimestamp)
    private val baseExtraFields =
      if amsterdamConfig.isAmsterdamTimestamp(ts) then
        HefPostAmsterdam(7, emptyRoot, 0, 0, zero32, zero32, BlockAccessList.EmptyHash, slotNumber = 29)
      else if amsterdamConfig.isPragueTimestamp(ts) then HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)
      else HefPostShanghai(7, emptyRoot)

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = Difficulty(0),
          stateRoot = TrieRoot(emptyRoot),
          gasLimit = GasAmount(30_000_000),
          gasUsed = GasAmount.Zero,
          unixTimestamp = ts,
          extraFields = baseExtraFields
        ),
        BlockBody.empty
      ),
      Nil,
      ChainWeight.zero,
      saveAsBestBlock = true
    )

    private lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      amsterdamConfig
    )

    /** The simulated blocks of one block state call at `time`, with the overrides `beaconRoot` and `number` given. */
    def simulate(time: Long, beaconRoot: Option[ByteString], number: Option[Int] = None): Seq[SimulateBlockResult] =
      val overrides = BlockOverrides(time = Some(BigInt(time)), beaconRoot = beaconRoot, number = number.map(BigInt(_)))
      // an Amsterdam block gets the four request contracts a real chain has (see RequestContractStubs)
      val requestContracts =
        Option.when(amsterdamConfig.isAmsterdamTimestamp(Timestamp(time)))(RequestContractStubs.overrides)
      service
        .ethSimulate(
          EthSimulateRequest(Seq(BlockStateCall(blockOverrides = Some(overrides), stateOverrides = requestContracts)))
        )
        .unsafeRunSync()
        .fold(err => fail(s"eth_simulateV1 failed: $err"), _.blocks)

  private def root(block: SimulateBlockResult): Option[ByteString] = block.header.parentBeaconBlockRoot.map(_.value)

  "eth_simulateV1" should "take a Prague block's parentBeaconBlockRoot from blockOverrides.beaconRoot" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(baseTimestamp = 132)
    val block = node.simulate(time = 144, Some(Root)).head
    block.header.extraFields shouldBe a[HefPostPrague]
    root(block) shouldBe Some(Root)
    // the response carries it, and the block hash commits to it
    EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(EthSimulateResponse(Seq(block))) match
      case JArray(rendered :: Nil) => (rendered \ "parentBeaconBlockRoot") shouldBe JString("0x" + "ab" * 32)
      case other                   => fail(s"expected one simulated block, got $other")
  }

  it should "store the override in the EIP-4788 ring buffer, so the state root moves" taggedAs (UnitTest, RPCTest) in {
    val withRoot = new Node(132).simulate(time = 144, Some(Root)).head.header
    val without = new Node(132).simulate(time = 144, None).head.header
    withRoot.stateRoot should not be without.stateRoot
    withRoot.hash should not be without.hash
  }

  it should "leave the root zero when the block overrides do not give one" taggedAs (UnitTest, RPCTest) in {
    root(new Node(132).simulate(time = 144, None).head) shouldBe Some(zero32)
  }

  it should "take an Amsterdam block's parentBeaconBlockRoot from blockOverrides.beaconRoot too" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val block = new Node(baseTimestamp = 348).simulate(time = 360, Some(Root)).head
    block.header.extraFields shouldBe a[HefPostAmsterdam]
    root(block) shouldBe Some(Root)
  }

  it should "not read beaconRoot for a block before Cancun, whose header has no such field" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val block = new Node(baseTimestamp = 12).simulate(time = 24, Some(Root)).head
    block.header.extraFields shouldBe a[HefPostShanghai]
    root(block) shouldBe None
  }

  it should "give a gap block the zero root, and the overridden block its own" taggedAs (UnitTest, RPCTest) in {
    val blocks = new Node(baseTimestamp = 132).simulate(time = 156, Some(Root), number = Some(31))
    blocks.map(_.header.number.value) shouldBe Seq(BigInt(30), BigInt(31))
    blocks.map(root) shouldBe Seq(Some(zero32), Some(Root))
  }

package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** WI-14 (#1430): `eth_simulateV1` builds a simulated block in the header shape of the fork active at its timestamp.
  *
  * The header cascade stopped at Prague, so a simulated block at an Amsterdam timestamp was 21 fields: its hash was not
  * an Amsterdam hash and SLOTNUM read a slot the header did not have. From Amsterdam it is the 23-field
  * `HefPostAmsterdam`, with the empty access list's hash and slot 0 (see `EthSimulateService.buildBlockHeader`), and
  * the response carries both new fields. Before Amsterdam, and on ETC, the shape and the response are what they were.
  *
  * The chain is the Amsterdam fixture schedule (Osaka 180, Amsterdam 360) on an empty state; the simulated blocks carry
  * no calls, so the header shape is the only thing that varies.
  */
// scalastyle:off magic.number
class EthSimulateAmsterdamHeaderSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(Array.fill[Byte](32)(0))

  /** A node whose best block sits at `baseTimestamp`, in the shape its fork gives it. */
  private class Node(config: BlockchainConfig, baseTimestamp: Long) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    private val baseExtraFields: HeaderExtraFields =
      if config.isAmsterdamTimestamp(Timestamp(baseTimestamp)) then
        HefPostAmsterdam(7, emptyRoot, 0, 0, zero32, zero32, BlockAccessList.EmptyHash, slotNumber = 29)
      else if config.isPragueTimestamp(Timestamp(baseTimestamp)) then HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)
      else HefEmpty

    val base: Block = Block(
      Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(29),
        difficulty = if baseExtraFields == HefEmpty then Difficulty(1000) else Difficulty(0),
        stateRoot = TrieRoot(emptyRoot),
        gasLimit = GasAmount(30_000_000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(baseTimestamp),
        extraFields = baseExtraFields
      ),
      BlockBody.empty
    )
    blockchainWriter.save(base, Nil, ChainWeight.zero, saveAsBestBlock = true)

    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      config
    )

    /** One simulated block, at `timestamp` when given (the base block's + 12 otherwise), with no calls. */
    def simulate(timestamp: Option[Long] = None): EthSimulateResponse =
      val request = EthSimulateRequest(
        Seq(BlockStateCall(blockOverrides = Some(BlockOverrides(time = timestamp.map(BigInt(_))))))
      )
      service.ethSimulate(request).unsafeRunSync().fold(err => fail(s"eth_simulateV1 failed: $err"), identity)

  private def rendered(response: EthSimulateResponse): JValue =
    EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(response) match
      case JArray(block :: Nil) => block
      case other                => fail(s"expected one simulated block, got $other")

  "eth_simulateV1" should "build the 23-field Amsterdam header for a block at the Amsterdam timestamp" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(amsterdamConfig, baseTimestamp = 348) // Osaka base; the simulated block lands on 360
    val header = node.simulate(Some(360)).blocks.head.header

    header.unixTimestamp shouldBe Timestamp(360)
    header.extraFields shouldBe a[HefPostAmsterdam]
    header.blockAccessListHash shouldBe Some(BlockAccessList.EmptyHash)
    header.slotNumber shouldBe Some(BigInt(0))
    header.requestsHash shouldBe Some(EmptyRequestsHash)
    // The shape the block validator requires of an Amsterdam header, and one its RLP decoder reads back whole.
    BlockHeader.validateFieldCount(header, amsterdamConfig) shouldBe Right(())
    header.toBytes.toBlockHeader shouldBe header
  }

  it should "keep the Amsterdam shape past the fork" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(amsterdamConfig, baseTimestamp = 360)
    node.simulate().blocks.head.header.extraFields shouldBe a[HefPostAmsterdam]
  }

  it should "report blockAccessListHash and slotNumber for an Amsterdam block" taggedAs (UnitTest, RPCTest) in {
    val json = rendered(new Node(amsterdamConfig, baseTimestamp = 348).simulate(Some(360)))
    json \ "blockAccessListHash" shouldBe
      JString("0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")
    json \ "slotNumber" shouldBe JString("0x0")
  }

  it should "build the 21-field Prague header one block before Amsterdam, as before" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(amsterdamConfig, baseTimestamp = 336)
    val response = node.simulate(Some(359))
    response.blocks.head.header.extraFields shouldBe a[HefPostPrague]
    val json = rendered(response)
    json \ "blockAccessListHash" shouldBe JNothing
    json \ "slotNumber" shouldBe JNothing
  }

  it should "build the Prague header on a chain that schedules no Amsterdam" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(preAmsterdamConfig, baseTimestamp = 348)
    node.simulate(Some(360)).blocks.head.header.extraFields shouldBe a[HefPostPrague]
  }

  it should "leave an ETC chain's pre-London header shape alone" taggedAs (UnitTest, RPCTest) in {
    // The loaded test chain is ETC-typed; its timestamp forks sit in the far future (9,999,999,994+), so at 1,000,000
    // the shape follows the parent, here with no base fee.
    val etc: BlockchainConfig = Config.blockchains.blockchainConfig
    val node = new Node(etc, baseTimestamp = 1_000_000L)
    val response = node.simulate()
    response.blocks.head.header.extraFields shouldBe HefEmpty
    rendered(response) \ "slotNumber" shouldBe JNothing
  }

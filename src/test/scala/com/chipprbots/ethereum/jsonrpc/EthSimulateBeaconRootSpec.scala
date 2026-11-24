package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

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
  private class Node(baseTimestamp: Long, config: BlockchainConfig = amsterdamConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    private val ts = Timestamp(baseTimestamp)
    private val baseExtraFields =
      if config.isAmsterdamTimestamp(ts) then
        HefPostAmsterdam(7, emptyRoot, 0, 0, zero32, zero32, BlockAccessList.EmptyHash, slotNumber = 29)
      else if config.isPragueTimestamp(ts) then HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)
      else if config.isShanghaiTimestamp(ts) then HefPostShanghai(7, emptyRoot)
      else HefEmpty // ETC before Olympia

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = Difficulty(0),
          // the request contracts a real chain has: every Prague or later block calls them, a gap block too (B-163)
          stateRoot = TrieRoot(
            RequestContractStubs
              .deployedRoot(storagesInstance.storages.evmCodeStorage, blockchain.getBackingMptStorage(0))
          ),
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
      config
    )

    /** The simulated blocks of one block state call at `time`, with the overrides `beaconRoot` and `number` given.
      *
      * The EIP-4788 and EIP-2935 contracts are deployed by state overrides, as a real chain has them, unless
      * `systemContracts` is false: block import (and so the simulation) writes nothing where they have no code. `extra`
      * is added to the state overrides, after them; `calls` are the block's calls.
      */
    def simulate(
        time: Long,
        beaconRoot: Option[ByteString],
        number: Option[Int] = None,
        systemContracts: Boolean = true,
        extra: Map[Address, StateOverride] = Map.empty,
        calls: Seq[TxArgs] = Nil,
        validation: Boolean = false
    ): Seq[SimulateBlockResult] =
      val overrides = BlockOverrides(time = Some(BigInt(time)), beaconRoot = beaconRoot, number = number.map(BigInt(_)))
      val preambleContracts =
        if systemContracts then SystemContracts else Map.empty[Address, StateOverride]
      val stateOverrides = preambleContracts ++ extra
      service
        .ethSimulate(
          EthSimulateRequest(
            Seq(
              BlockStateCall(
                blockOverrides = Some(overrides),
                stateOverrides = Option.when(stateOverrides.nonEmpty)(stateOverrides),
                calls = Option.when(calls.nonEmpty)(calls)
              )
            ),
            validation = validation
          )
        )
        .unsafeRunSync()
        .fold(err => fail(s"eth_simulateV1 failed: $err"), _.blocks)

    /** The base block's header: the simulated block's parent. */
    def baseHeader: BlockHeader = blockchainReader.getBestBlockHeader.get

    /** The base block's hash: the simulated block's parent. */
    def baseHash: ByteString = blockchainReader.getBestBlockHeader.get.hash.value

  /** The canonical EIP-4788 and EIP-2935 contracts, deployed by state overrides. */
  private val SystemContracts: Map[Address, StateOverride] = Map(
    BlockExecution.BeaconRootContractAddress -> StateOverride(code = Some(BlockExecution.BeaconRootsCode)),
    BlockExecution.HistoryStorageAddress -> StateOverride(code = Some(BlockExecution.HistoryStorageCode))
  )

  /** A uint256 as 32 bytes of calldata. */
  private def word(n: BigInt): ByteString =
    val raw = n.toByteArray.dropWhile(_ == 0)
    ByteString(new Array[Byte](32 - raw.length) ++ raw)

  /** A call to the EIP-4788 contract's `get`: the root stored for `timestamp`. */
  private def readBeaconRoot(timestamp: Long): TxArgs =
    TxArgs(to = Some(BlockExecution.BeaconRootContractAddress), data = Some(word(timestamp)))

  /** A call to the EIP-2935 contract's `get`: the hash stored for block `number`. */
  private def readBlockHash(number: Long): TxArgs =
    TxArgs(to = Some(BlockExecution.HistoryStorageAddress), data = Some(word(number)))

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

  // B-180: the preamble is block import's (BlockExecution.applyBlockPreamble). Where the EIP-4788 contract has no code,
  // import writes nothing, and go-ethereum's system call to an account without code changes nothing; the simulation
  // used to create the account and write the two slots anyway.
  it should "write nothing where the EIP-4788 contract has no code, as block import does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val withRoot = new Node(132).simulate(time = 144, Some(Root), systemContracts = false).head.header
    val without = new Node(132).simulate(time = 144, None, systemContracts = false).head.header
    withRoot.stateRoot shouldBe without.stateRoot
  }

  it should "let a call read the root the preamble stored for its block" taggedAs (UnitTest, RPCTest) in {
    val block = new Node(132).simulate(time = 144, Some(Root), calls = Seq(readBeaconRoot(144))).head
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), Root))
  }

  // B-180 (was B-166): go-ethereum's processBlock applies the state overrides and then runs ProcessBeaconBlockRoot /
  // ProcessParentBlockHash (simulate.go), so a `stateDiff` on a slot the preamble writes is overwritten by it. fukuii
  // wrote the slots first and the override after them.
  it should "run the preamble after the state overrides, so a stateDiff on its slots does not survive" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val junk = Map(BigInt(144) -> BigInt(999), BigInt(144 + 8191) -> BigInt(0xdead))
    val beacon = BlockExecution.BeaconRootContractAddress
    val diffed = Map(beacon -> SystemContracts(beacon).copy(stateDiff = Some(junk)))
    val overridden =
      new Node(132).simulate(time = 144, Some(Root), extra = diffed, calls = Seq(readBeaconRoot(144))).head
    overridden.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), Root))
    overridden.header.stateRoot shouldBe
      new Node(132).simulate(time = 144, Some(Root), calls = Seq(readBeaconRoot(144))).head.header.stateRoot
  }

  /** Code other than the canonical contracts: called by SYSTEM_ADDRESS it stores the calldata at slot 0; called by
    * anyone else it returns slot 0.
    */
  private val SlotZeroCode: ByteString = com.chipprbots.ethereum.utils.ByteStringUtils.string2hash(
    "33" + "73" + "ff" * 19 + "fe" + "14" + "6025" + "57" + "600054" + "600052" + "6020" + "6000" + "f3" +
      "5b" + "600035" + "600055" + "00"
  )

  // B-517: go-ethereum's processBlock applies the state overrides and then runs ProcessBeaconBlockRoot /
  // ProcessParentBlockHash as EVM calls, so code a `code` override puts at either contract runs. Import's preamble
  // wrote the canonical contract's slots directly whatever the code, so the override had no effect; it now runs code
  // that is not the canonical contract's, and the simulation reaches that through import's own preamble.
  it should "run the code a state override puts at the EIP-4788 contract before Amsterdam" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val beacon = BlockExecution.BeaconRootContractAddress
    val block = new Node(132)
      .simulate(
        time = 144,
        Some(Root),
        extra = Map(beacon -> StateOverride(code = Some(SlotZeroCode))),
        calls = Seq(TxArgs(to = Some(beacon)))
      )
      .head
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), Root))
  }

  it should "run the code a state override puts at the EIP-2935 contract before Amsterdam" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val history = BlockExecution.HistoryStorageAddress
    val node = new Node(132)
    val block = node
      .simulate(
        time = 144,
        None,
        extra = Map(history -> StateOverride(code = Some(SlotZeroCode))),
        calls = Seq(TxArgs(to = Some(history)))
      )
      .head
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), node.baseHash))
  }

  it should "store the parent hash in the EIP-2935 contract of a Prague block" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(132)
    val block = node.simulate(time = 144, None, calls = Seq(readBlockHash(29))).head
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), node.baseHash))
  }

  // B-180: on ETH the history contract is deployed by a transaction, never by the client (EIP-2935; import's
  // applyEip2935). The simulation used to deploy it, so a call reached code the chain does not have.
  it should "not deploy the EIP-2935 contract where the chain has none" taggedAs (UnitTest, RPCTest) in {
    val block = new Node(132).simulate(time = 144, None, systemContracts = false, calls = Seq(readBlockHash(29))).head
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), ByteString.empty))
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

  // ── ETC (B-180): import's preamble on ETC's block-number schedule ─────────

  /** An ETC chain (no fork timestamps, every ETC fork at 0) whose Olympia activates at block `olympia`. */
  private def etcConfig(olympia: BigInt): BlockchainConfig =
    preAmsterdamConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          // ETC's schedule: the ETC forks before Olympia (Spiral brings PUSH0, which the history contract runs)
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 0,
          olympiaBlockNumber = olympia
        )
      )

  // ECIP-1112 / ECIP-1121: from Olympia, import deploys the EIP-2935 history contract where it has no code and writes
  // the parent hash (BlockExecution.applyEip2935, gated by block number). The simulation's copy gated it on Prague,
  // which ETC never reaches, so a simulated Olympia block had neither.
  "eth_simulateV1 on ETC" should "deploy the EIP-2935 contract and store the parent hash at Olympia, as import does" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val node = new Node(baseTimestamp = 1000, etcConfig(olympia = 30))
    val block = node.simulate(time = 1012, None, systemContracts = false, calls = Seq(readBlockHash(29))).head
    block.header.number.value shouldBe BigInt(30)
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), node.baseHash))
  }

  it should "write nothing before Olympia, where the history contract has no code" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val node = new Node(baseTimestamp = 1000, etcConfig(olympia = 31))
    val block = node.simulate(time = 1012, None, systemContracts = false, calls = Seq(readBlockHash(29))).head
    block.calls.map(c => (c.status, c.returnData)) shouldBe Seq((BigInt(1), ByteString.empty))
  }

  // ECIP-1111: Olympia is a block-number fork, so its activation block carries a base fee although its parent has
  // none. Import's header validator (validateBaseFee) and miner (BlockGeneratorSkeleton.baseFeeFields) both take it from
  // BaseFeeCalculator.calcBaseFee: InitialBaseFee at activation, then the EIP-1559 update. The simulation took the
  // header's shape from the parent, so the activation block had no base fee.
  it should "give the Olympia activation block import's base fee, and the next block the EIP-1559 update" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val config = etcConfig(olympia = 30)
    val node = new Node(baseTimestamp = 1000, config)
    node.baseHeader.baseFee shouldBe None
    val blocks = node.simulate(time = 1024, None, number = Some(31), validation = true)
    blocks.map(_.header.number.value) shouldBe Seq(BigInt(30), BigInt(31))
    val activation = blocks.head.header
    activation.extraFields shouldBe a[HefPostOlympia]
    activation.baseFee shouldBe Some(BaseFeeCalculator.calcBaseFee(node.baseHeader, config))
    activation.baseFee shouldBe Some(BaseFeeCalculator.InitialBaseFee)
    // block 30 used no gas: the base fee falls by 1/8, floored at the chain's floor
    val next = blocks(1).header
    next.baseFee shouldBe Some(BaseFeeCalculator.calcBaseFee(activation, config))
    next.baseFee shouldBe Some(
      (BaseFeeCalculator.InitialBaseFee - BaseFeeCalculator.InitialBaseFee / 8).max(config.baseFeeFloor)
    )
  }

  it should "give the Olympia activation block a zero base fee without validation, as go-ethereum's simulator does" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val block = new Node(baseTimestamp = 1000, etcConfig(olympia = 30)).simulate(time = 1012, None).head
    block.header.extraFields shouldBe HefPostOlympia(BigInt(0))
  }

  it should "keep an ETC block before Olympia without a base fee" taggedAs (UnitTest, RPCTest, OlympiaTest) in {
    val block =
      new Node(baseTimestamp = 1000, etcConfig(olympia = 31)).simulate(time = 1012, None, validation = true).head
    block.header.extraFields shouldBe HefEmpty
  }

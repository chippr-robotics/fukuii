package com.chipprbots.ethereum.txExecTest

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.txExecTest.util.FixtureProvider
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig

/** Spec 018 Track C, C6 (T238b): [[ECIP1017Test]]'s recorded chain, imported from its genesis on the hash store and on
  * the unified state layout.
  *
  * The fixture is a chain recorded by another client with an era of three blocks; its first 602 blocks are imported
  * here, 189 ommers among them. Its headers carry that client's state roots, which import must compute. Each block is
  * imported on both layouts through `BlockExecution.executeAndValidateBlocks`, so both reach the recorded root, and
  * after each block:
  *   - the rows on the unified layout are exactly that block's state as the hash store holds it, and its record is
  *     whole (no tombstone) and valid;
  *   - what each miner and each ommer's miner was paid is the same on both layouts, and is what ECIP-1017 says,
  *     computed here from the formula (`5 ETC * 0.8^era`, `reward / 32` for each ommer included, and `(8 + u - n) / 8`
  *     of 5 ETC to an ommer's miner in era 0, `reward / 32` after it).
  */
class ECIP1017OnPositionsTest extends AnyFlatSpec with Matchers:

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private val EraDuration = 3
  private val FiveEtc = BigInt("5000000000000000000")

  // ECIP1017Test's configuration
  private val config: BlockchainConfig = BlockchainConfig(
    monetaryPolicyConfig = MonetaryPolicyConfig(EraDuration, 0.2, 5000000000000000000L, 3000000000000000000L),
    maxCodeSize = None,
    chainId = ChainId(0x3d),
    networkId = 1,
    forkBlockNumbers = ForkBlockNumbers.Empty.copy(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 1150000,
      eip150BlockNumber = 2500000,
      eip160BlockNumber = 3000000,
      eip155BlockNumber = 3000000
    ),
    customGenesisFileOpt = None,
    customGenesisJsonOpt = None,
    daoForkConfig = None,
    bootstrapNodes = Set(),
    accountStartNonce = UInt256.Zero,
    ethCompatibleStorage = true,
    gasTieBreaker = false
  )

  final private class Node(val source: EphemDataSource, guard: Boolean) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config
    override lazy val vm: VMImpl = new VMImpl
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard
    lazy val execution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new BlockValidation(mining, blockchainReader, blockQueue)
    )

    /** The fixture's genesis, by the node's own loader: no account, under the recorded genesis header. */
    def loadGenesis(genesis: BlockHeader): Unit =
      val storages = storagesInstance.storages
      val loader = new GenesisDataLoader(
        blockchainReader,
        blockchainWriter,
        storages.evmCodeStorage,
        storages.stateStorage,
        countsExact => storages.appStateStorage.markDatadirCreated(countsExact).commitSync(),
        storages.pathStateStorage,
        () => storages.appStateStorage.getStateLayout()
      ):
        override protected def prepareHeader(data: GenesisData, root: Array[Byte])(implicit
            blockchainConfig: BlockchainConfig
        ): BlockHeader =
          if ByteString(root) == genesis.stateRoot.value then genesis else super.prepareHeader(data, root)
      val data = GenesisData(
        nonce = genesis.nonce,
        mixHash = Some(genesis.mixHash.value),
        difficulty = genesis.difficulty.value.toString,
        extraData = genesis.extraData,
        gasLimit = genesis.gasLimit.value.toString,
        coinbase = genesis.beneficiary,
        timestamp = genesis.unixTimestamp.toString,
        alloc = Map.empty
      )
      loader.loadGenesisData(data).get
      blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(genesis.hash)

    def importOne(block: Block): (List[BlockData], Option[BlockExecutionError]) =
      val weight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true)

    def balanceAtHead(address: Address): BigInt =
      blockchainReader
        .accountAt(blockchainReader.getBestBlockHeader.get, address)
        .map(_.balance.toBigInt)
        .getOrElse(BigInt(0))

  private def era(n: BigInt): Int = ((n - 1) / EraDuration).toInt
  private def blockReward(n: BigInt): BigInt = FiveEtc * BigInt(4).pow(era(n)) / BigInt(5).pow(era(n))
  private def ommerReward(n: BigInt, u: BigInt): BigInt =
    if era(n) == 0 then (8 + u - n) * FiveEtc / 8 else blockReward(n) / 32

  /** What ECIP-1017 pays for `block`, by address: its miner, and the miner of each of its ommers. */
  private def paidFor(block: Block): Map[Address, BigInt] =
    val n = block.header.number.value
    val ommers = block.body.uncleNodesList
    val miner = Address(block.header.beneficiary) -> (blockReward(n) + (blockReward(n) / 32) * ommers.size)
    (miner +: ommers.map(o => Address(o.beneficiary) -> ommerReward(n, o.number.value)))
      .groupMapReduce(_._1)(_._2)(_ + _)

  "ECIP1017Test's recorded chain" should "import on the unified layout to the recorded roots, paying each miner what the hash store pays and ECIP-1017 says" taggedAs (
    IntegrationTest,
    ConsensusTest,
    SlowTest
  ) in {
    val fixtures = FixtureProvider.loadFixtures("/txExecTest/ecip1017Test")
    val genesis = fixtures.blockByNumber(0).header
    genesis.stateRoot.value shouldBe ByteString(MerklePatriciaTrie.EmptyRootHash)
    val blocks = (1 to 602).map(n => fixtures.blockByNumber(n))

    val onHashes = new Node(EphemDataSource(), guard = false)
    onHashes.loadGenesis(genesis)
    val onPositions = new Node(EphemDataSource(), guard = true)
    onPositions.loadGenesis(genesis)
    val pathState = onPositions.storagesInstance.storages.pathStateStorage.get
    val nodes = new NodeStorage(onHashes.source)
    val rows = new PathNodeStorage(onPositions.source)
    val records = new ChangesetStorage(onPositions.source)
    def stateOf(header: BlockHeader): Disk =
      val positions = RawTrieWalk.statePositions(
        header.stateRoot.value.toArray,
        hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
      )
      (
        positions.getOrElse(None, Map.empty),
        positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
      )
    def disk: Disk = PathStateTestKit.allRows(rows)

    var tombstones = 0
    var ommers = 0
    var paidInTotal = BigInt(0)
    blocks.foreach { block =>
      val header = block.header
      withClue(s"block ${header.number} (era ${era(header.number.value)}): ") {
        val owed = paidFor(block)
        val before = owed.keys.map(a => a -> onPositions.balanceAtHead(a)).toMap
        owed.keys.foreach(a => onHashes.balanceAtHead(a) shouldBe before(a))

        val (reference, referenceError) = onHashes.importOne(block)
        referenceError shouldBe None
        reference.map(_.block.header.hash) shouldBe List(header.hash)
        val (imported, error) = onPositions.importOne(block)
        error shouldBe None
        imported.map(_.block.header.hash) shouldBe List(header.hash)

        // the recorded root, held on the unified layout as the hash store holds it
        pathState.anchor shouldBe Some(BlockRef(header.hash.value, header.number.value, header.stateRoot.value))
        disk shouldBe stateOf(header)
        val record = records.get(header.hash.value).flatMap(_.toOption)
        record.map(_.validate) shouldBe Some(Nil)
        tombstones += record.count(_.incomplete)

        // each miner paid alike on both layouts, and what the formula says
        owed.foreach { (address, amount) =>
          withClue(s"$address: ") {
            val paid = onPositions.balanceAtHead(address) - before(address)
            onHashes.balanceAtHead(address) shouldBe onPositions.balanceAtHead(address)
            paid shouldBe amount
          }
        }
        ommers += block.body.uncleNodesList.size
        paidInTotal += owed.values.sum
      }
    }
    PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
    tombstones shouldBe 0
    ommers shouldBe 189
    // the reward of era 193 and after is nothing: 5 ETC * 0.8^193 is less than a wei
    blockReward(blocks.last.header.number.value) shouldBe BigInt(0)
    val head = blocks.last.header
    info(
      s"${blocks.size} blocks, $ommers ommers, head root 0x${Hex.toHexString(head.stateRoot.value.toArray)} " +
        s"on both layouts, $paidInTotal wei paid as the formula says, $tombstones tombstones"
    )
  }

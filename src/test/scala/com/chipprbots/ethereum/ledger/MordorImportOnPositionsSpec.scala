package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.snap.PowHeaderFixtures
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C3b (forge): real Mordor blocks imported onto the unified state layout under the rules a Mordor
  * node runs, with nothing trusted.
  *
  * The headers are Mordor's own, from its genesis on (`mordor/headers-0-256.txt`), and each block is imported through
  * `BlockExecution.executeAndValidateBlocks` with the shipped Mordor configuration and the proof-of-work validators:
  * the Ethash seal, the difficulty, the gas limit, the ommers. A block with no transaction and no ommer is its header
  * and an empty body, and its state root is the chain's: the ECIP-1017 reward of era 0 paid to its miner, block after
  * block, which is what these blocks change.
  *
  * The chain is imported on the hash store, which keeps every block's state, and on the unified layout: after each
  * block the rows are that block's state, and the records take them back to the genesis state.
  */
class MordorImportOnPositionsSpec extends AnyFlatSpec with Matchers:

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  final private class Node(source: EphemDataSource, guard: Boolean) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = PowHeaderFixtures.mordorConfig
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.PoW)
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
    def loadGenesis(): BlockHeader =
      GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages).loadGenesisData()
      blockchainReader.getBlockHeaderByNumber(0).get
    def importOne(block: Block): (List[BlockData], Option[BlockExecutionError]) =
      val weight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true)

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  private val headers: IndexedSeq[BlockHeader] = PowHeaderFixtures.fromGenesis
  // the blocks that are their header alone: no transaction, no ommer
  private val blocks: IndexedSeq[Block] =
    headers.tail
      .takeWhile(h => h.transactionsRoot.value == BlockHeader.EmptyMpt && h.ommersHash.value == BlockHeader.EmptyOmmers)
      .map(Block(_, BlockBody(Nil, Nil)))

  "Mordor's first blocks, under Mordor's own rules" should "import onto the unified layout to the state Mordor has" taggedAs
    (UnitTest, ConsensusTest) in {
      blocks.size should be >= 200

      // the reference: the hash store, every block's state kept
      val hashSource = EphemDataSource()
      val onHashes = new Node(hashSource, guard = false)
      onHashes.loadGenesis().hash shouldBe headers.head.hash
      val nodes = new NodeStorage(hashSource)
      def stateOf(header: BlockHeader): Disk =
        val positions = RawTrieWalk.statePositions(
          header.stateRoot.value.toArray,
          hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
        )
        (
          positions.getOrElse(None, Map.empty),
          positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
        )

      // the unified layout: Mordor's genesis committed by the node (it allocates nothing: no row, the empty root)
      val source = EphemDataSource()
      val onPositions = new Node(source, guard = true)
      val genesis = onPositions.loadGenesis()
      genesis.hash shouldBe headers.head.hash
      val pathState = onPositions.storagesInstance.storages.pathStateStorage.get
      val rows = new PathNodeStorage(source)
      val records = new ChangesetStorage(source)
      def disk: Disk = PathStateTestKit.allRows(rows)
      disk shouldBe stateOf(genesis)

      var miners = Set.empty[ByteString]
      blocks.foreach { block =>
        val header = block.header
        withClue(s"Mordor block ${header.number}: ") {
          val (reference, referenceError) = onHashes.importOne(block)
          referenceError shouldBe None
          reference should have size 1

          val (imported, error) = onPositions.importOne(block)
          error shouldBe None
          imported.map(_.block.header.hash) shouldBe List(header.hash)
          disk shouldBe stateOf(header)
          pathState.anchor shouldBe Some(refOf(header))
          onPositions.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(header.hash)
          onPositions.blockchainReader.getChainWeightByHash(header.hash) shouldBe
            onHashes.blockchainReader.getChainWeightByHash(header.hash)
          records.get(header.hash.value).flatMap(_.toOption).map(_.validate) shouldBe Some(Nil)
        }
        miners += header.beneficiary
      }
      // a bad seal is the header validator's to refuse, on this layout as on the other: nothing is touched
      val last = blocks.last.header
      val forged = Block(last.copy(nonce = ByteString(Array.fill[Byte](8)(1))), BlockBody(Nil, Nil))
      val before = disk
      // (a block at the head's own height with another seal: its parent is the anchor's parent, and its seal is bad)
      val (none, refused) = onPositions.importOne(forged)
      none shouldBe empty
      refused should not be empty
      disk shouldBe before
      pathState.anchor shouldBe Some(refOf(last))

      // the hash store holds no node: one writer of the state, and not that one
      PathStateTestKit.hashStoreRows(source) shouldBe empty

      // and the records take the rows back to the genesis state
      val chain = genesis +: blocks.map(_.header)
      chain.zip(chain.tail).reverse.foreach { (parent, header) =>
        val record = records.get(header.hash.value).flatMap(_.toOption).get
        source.update(rows.rowUpdates(record.changes.view.mapValues(_.prior)))
        withClue(s"Mordor block ${header.number} undone: ")(disk shouldBe stateOf(parent))
      }
      info(s"${blocks.size} Mordor blocks, ${miners.size} miners paid")
    }

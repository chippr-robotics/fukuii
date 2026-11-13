package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockDiff
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C3b (T075's substitute, T084, T093): a real chain imported block by block onto the unified state
  * layout, through the node's own import path.
  *
  * The chain is the execution-apis test chain ([[RpcCompatNode]]): proof-of-work blocks, the merge, and every fork up
  * to Amsterdam with its block access lists. It is imported three times, each through
  * `BlockExecution.executeAndValidateBlocks`, the path a synced block and a chain file take:
  *
  *   - on the hash store, by the harness's own loop, which keeps every block's state: the reference;
  *   - on the hash store, through the import path: what the guard being off must leave as it is;
  *   - on a datadir whose position store holds the genesis state, with the guard on.
  *
  * Every block validates against its header in each (state root, receipts root, gas used, logs bloom, requests hash,
  * access-list hash), so the three agree on those by construction. What is held here beyond that is the disk: after
  * each block the rows of CF `t` and CF `u` are exactly the state the reference holds for that block, the anchor and
  * the best block are that block, and its changeset record takes the rows back to the parent's.
  */
class ImportOnPositionsSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private trait Shared(source: EphemDataSource) extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = source

  /** A node over `source`, on the hash store or, with `guard`, on the unified layout. */
  private def nodeOver(source: EphemDataSource, guard: Boolean): RpcCompatNode =
    new RpcCompatNode:
      override lazy val storagesInstance
          : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
        new Shared(source) with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
          override protected def statePathLive: Boolean = guard

  /** The genesis, loaded as the node loads it: onto the hash store, or committed to the unified layout. */
  private def loadGenesis(node: RpcCompatNode): BlockHeader =
    given BlockchainConfig = node.blockchainConfig
    GenesisDataLoader
      .over(node.blockchainReader, node.blockchainWriter, node.storagesInstance.storages)
      .loadGenesisData()
    node.blockchainReader.getBlockHeaderByNumber(0).get

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  private def importPath(node: RpcCompatNode): BlockExecution =
    new HeaderTrustingBlockExecution(
      node.blockchain,
      node.blockchainReader,
      node.blockchainWriter,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      new BlockValidation(node.mining, node.blockchainReader, node.blockQueue)
    )

  private def importOne(
      node: RpcCompatNode,
      execution: BlockExecution,
      block: Block,
      adopt: Boolean = true
  ): (List[BlockData], Option[BlockExecutionError]) =
    given BlockchainConfig = node.blockchainConfig
    val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
    execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = adopt)

  // ─── The reference: the chain on the hash store, every block's state kept ─────────────────────────────────────────
  private lazy val reference = new RpcCompatNode
  private lazy val referenceHead = reference.importChain()

  /** The rows a block's state is, by a walk of its raw nodes in the reference's store. */
  private def stateOf(header: BlockHeader): Disk =
    val nodes = reference.storagesInstance.storages.nodeStorage
    val positions = RawTrieWalk.statePositions(
      header.stateRoot.value.toArray,
      hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
    )
    (
      positions.getOrElse(None, Map.empty),
      positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
    )

  // ─── The chain on the unified layout ───────────────────────────────────────────────────────────────────────────────
  // The datadir starts empty, and its genesis is committed by the node on the unified layout (C3c).
  private lazy val source = EphemDataSource()
  private lazy val onPositions: RpcCompatNode =
    referenceHead // the reference first: it says what each state is
    nodeOver(source, guard = true)
  private lazy val genesis: BlockHeader = loadGenesis(onPositions)
  private lazy val execution = importPath(onPositions)
  private def pathState = onPositions.storagesInstance.storages.pathStateStorage.get
  private def disk: Disk = PathStateTestKit.allRows(new PathNodeStorage(source))
  private lazy val records = new ChangesetStorage(source)
  private def record(header: BlockHeader): BlockDiff = records.get(header.hash.value) match
    case Some(Right(diff)) => diff
    case other             => fail(s"no changeset record of block ${header.number}: $other")

  private lazy val blocks: Seq[Block] = RpcCompatNode.blocks

  "A chain imported onto the unified layout" should "start from a genesis committed as rows, with nothing in the hash store" taggedAs
    UnitTest in {
      blocks.size should be > 20
      referenceHead.header.hash shouldBe blocks.last.header.hash
      genesis.hash shouldBe reference.blockchainReader.getBlockHeaderByNumber(0).get.hash
      pathState.anchor shouldBe Some(refOf(genesis))
      disk shouldBe stateOf(genesis)
      onPositions.blockchainReader.getBestBlockNumber shouldBe BigInt(0)
    }

  // dm 7.6: a path-live store has one writer, the staged commit.
  it should "refuse a block executed with nothing staged, and one that is not adopted as it is executed" taggedAs
    UnitTest in {
      given BlockchainConfig = onPositions.blockchainConfig
      val before = disk
      an[IllegalStateException] should be thrownBy execution.executeBlockNoValidation(blocks.head)
      // a side branch, a reorganisation's blocks: executed, and adopted later or never. The batch ends on it as on a
      // fault of this node's state, which it is, and not as on an error of the block (C4b): nothing was judged.
      val unadopted = importOne(onPositions, execution, blocks.head, adopt = false) match
        case (Nil, Some(BlockExecutionError.StateFaulted(fault: StateUnavailable))) => fault
        case other => fail(s"the block that is not adopted was answered with $other")
      unadopted.reason should include("not adopted")
      // a payload ahead of the head: its state committed apart from the block
      // refused before it runs, not when its state comes to be committed
      val apart = the[StateUnavailable] thrownBy execution.executeAndValidateBlock(blocks.head, alreadyValidated = true)
      apart.reason should include("committed apart from the block")
      disk shouldBe before
      pathState.anchor shouldBe Some(refOf(genesis))
      onPositions.blockchainReader.getBestBlockNumber shouldBe BigInt(0)
    }

  it should "hold, after every block, exactly that block's state, with the block as anchor and best block" taggedAs
    UnitTest in {
      var parent = genesis
      var positions = 0
      var storageRowsDeleted = 0
      var tombstones = 0
      blocks.foreach { block =>
        val header = block.header
        withClue(s"block ${header.number}: ") {
          // a block that is not this chain's next block leaves everything as it was: here, the right block with
          // another state root, which fails the check made after execution
          if header.number.value % 7 == 3 then
            val before = disk
            val wrong = block.copy(header = header.copy(stateRoot = parent.stateRoot))
            val (none, error) = importOne(onPositions, execution, wrong)
            none shouldBe empty
            error should not be empty
            disk shouldBe before
            pathState.anchor shouldBe Some(refOf(parent))
            onPositions.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(parent.hash)
            records.get(wrong.header.hash.value) shouldBe None

          val (imported, error) = importOne(onPositions, execution, block)
          error shouldBe None
          imported.map(_.block.header.hash) shouldBe List(header.hash)

          disk shouldBe stateOf(header)
          pathState.anchor shouldBe Some(refOf(header))
          onPositions.storagesInstance.storages.appStateStorage.getStateAnchor() shouldBe Some(refOf(header))
          onPositions.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(header.hash)
          // the block's own records went with its state: what the reference stored for it
          onPositions.blockchainReader.getReceiptsByHash(header.hash) shouldBe
            reference.blockchainReader.getReceiptsByHash(header.hash)
          onPositions.blockchainReader.getBlockAccessListByHash(header.hash) shouldBe
            reference.blockchainReader.getBlockAccessListByHash(header.hash)

          val diff = record(header)
          diff.validate shouldBe empty
          diff.incomplete shouldBe false
          (diff.parentHash, diff.preStateRoot, diff.postStateRoot) shouldBe
            (parent.hash.value, parent.stateRoot.value, header.stateRoot.value)
          tombstones += (if diff.incomplete then 1 else 0)
          positions += diff.changes.size
          storageRowsDeleted += diff.changes.count((position, change) =>
            position.owner.isDefined && change.next.isEmpty
          )
        }
        parent = header
      }
      tombstones shouldBe 0
      parent.hash shouldBe referenceHead.header.hash
      // one writer of the state, and it is not the hash store's (data-model 7.6): 60 blocks later it holds what the
      // genesis load put there, and no node more
      PathStateTestKit.hashStoreRows(source) shouldBe empty
      // every block changes the state: a reward, a fee, a system contract's slot
      positions should be >= blocks.size
      info(s"${blocks.size} blocks, $positions positions changed, $storageRowsDeleted rows of storage tries deleted")
    }

  it should "take a block that is applied already as done, and write nothing of its state again" taggedAs UnitTest in {
    val before = disk
    val epoch = pathState.clock.epoch
    val (imported, error) = importOne(onPositions, execution, blocks.last)
    error shouldBe None
    imported.map(_.block.header.hash) shouldBe List(blocks.last.header.hash)
    disk shouldBe before
    pathState.clock.epoch shouldBe epoch
    pathState.anchor shouldBe Some(refOf(blocks.last.header))
  }

  // The records alone take the disk back, block by block, to the genesis state.
  it should "be undone by its changeset records, down to the genesis state" taggedAs UnitTest in {
    val rows = new PathNodeStorage(source)
    val headers = genesis +: blocks.map(_.header)
    headers.zip(headers.tail).reverse.foreach { (parent, header) =>
      source.update(rows.rowUpdates(record(header).changes.view.mapValues(_.prior)))
      withClue(s"block ${header.number} undone: ")(disk shouldBe stateOf(parent))
    }
    disk shouldBe stateOf(genesis)
  }

  // ─── The head, and a consensus client ──────────────────────────────────────────────────────────────────────────────

  // Until the overlay the head can only be the block whose state the disk holds: a move to any other block needs that
  // block's state.
  "forkchoiceUpdated on the unified layout" should "answer SYNCING for a head that is not the block the disk holds, and move nothing" taggedAs
    UnitTest in {
      import cats.effect.unsafe.implicits.global
      import com.chipprbots.ethereum.consensus.engine.EngineApiService
      import com.chipprbots.ethereum.consensus.engine.ForkChoiceState
      import com.chipprbots.ethereum.consensus.engine.PayloadStatus
      given BlockchainConfig = onPositions.blockchainConfig
      given org.apache.pekko.actor.typed.Scheduler = system.scheduler
      val engine = new EngineApiService(
        onPositions.blockchainReader,
        onPositions.blockchainWriter,
        execution,
        onPositions.forkChoiceManager,
        None,
        stateHeldInPlace = Some(() => pathState.anchor.map(_.hash))
      )
      val zero = ByteString(new Array[Byte](32))
      val head = blocks.last.header
      val before = disk
      // a block this node has, below the head: a move back
      val earlier = blocks(blocks.size - 3).header
      val moved = engine.forkchoiceUpdated(ForkChoiceState(earlier.hash.value, zero, zero), None).unsafeRunSync()
      moved.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Syncing)
      moved.map(_.payloadId) shouldBe Right(None)
      // a block it does not have
      val unknown = ByteString(Array.fill[Byte](32)(0x5a))
      engine
        .forkchoiceUpdated(ForkChoiceState(unknown, zero, zero), None)
        .unsafeRunSync()
        .map(_.payloadStatus.status) shouldBe
        Right(PayloadStatus.Syncing)
      onPositions.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(head.hash)
      pathState.anchor shouldBe Some(refOf(head))
      disk shouldBe before

      // (a payload asked for on the block the disk holds is built: `ProposerOnPositionsSpec`)

      // the block the disk holds is a head like any other
      engine
        .forkchoiceUpdated(ForkChoiceState(head.hash.value, zero, zero), None)
        .unsafeRunSync()
        .map(_.payloadStatus.status) shouldBe
        Right(PayloadStatus.Valid)
      onPositions.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(head.hash)
      pathState.anchor shouldBe Some(refOf(head))
      disk shouldBe before
    }

  // ─── The access-list prefetch ──────────────────────────────────────────────────────────────────────────────────────

  // T093, from T045 (beacon): a block that arrives with its access list has its parent's rows read ahead, through a
  // view. It only warms caches: the block must execute to the same state with it.
  "A block that arrives with its access list" should "be prefetched from its parent's rows, and commit the same state" taggedAs
    UnitTest in {
      import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
      import com.chipprbots.ethereum.db.storage.StagedBlockState
      import com.chipprbots.ethereum.domain.BlockAccessList
      import com.chipprbots.ethereum.domain.BlockchainImpl
      val other = EphemDataSource()
      var readersAsked, readersGiven = 0
      val node = new RpcCompatNode:
        override lazy val storagesInstance
            : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
          new Shared(other)
            with LocalPruningConfigBuilder
            with Storages.DefaultStorages
            with TestInstanceConfigProvider:
            override protected def statePathLive: Boolean = true
        // the production staging, with the prefetch's request for a reader counted
        override lazy val blockchain: BlockchainImpl =
          new BlockchainImpl(
            storagesInstance.storages.blockHeadersStorage,
            storagesInstance.storages.blockBodiesStorage,
            storagesInstance.storages.blockNumberMappingStorage,
            storagesInstance.storages.receiptStorage,
            storagesInstance.storages.chainWeightStorage,
            storagesInstance.storages.transactionMappingStorage,
            storagesInstance.storages.appStateStorage,
            storagesInstance.storages.stateStorage,
            blockchainReader,
            storagesInstance.storages.pathStateStorage
          ):
            override def stageBlockState(of: BlockHeader): StagedBlockState =
              val staged = super.stageBlockState(of)
              new StagedBlockState:
                override def storage: com.chipprbots.ethereum.db.storage.MptStorage = staged.storage
                override def pending: Option[com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate] =
                  staged.pending
                override def commitsInPlace: Boolean = staged.commitsInPlace
                override def commitWith(
                    blockNumber: BigInt,
                    blockHash: ByteString,
                    extra: Option[com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate]
                ): Unit = staged.commitWith(blockNumber, blockHash, extra)
                override def isBlockApplied(blockHash: ByteString): Boolean = staged.isBlockApplied(blockHash)
                override def discard(): Unit = staged.discard()
                override def prefetchReader(
                    budgetBytes: Long,
                    onLoaded: ByteString => Unit
                ): Option[PrefetchNodeReader] =
                  val reader = staged.prefetchReader(budgetBytes, onLoaded)
                  readersAsked += 1
                  if reader.isDefined then readersGiven += 1
                  reader
      loadGenesis(node).hash shouldBe genesis.hash
      val withLists = importPath(node)
      given BlockchainConfig = node.blockchainConfig
      var supplied = 0
      blocks.foreach { block =>
        val list = reference.blockchainReader
          .getBlockAccessListByHash(block.header.hash)
          .map(bytes =>
            BlockAccessList.decode(bytes).fold(e => fail(s"the reference's list does not decode: $e"), identity)
          )
        supplied += list.size
        val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
        val (imported, error) = withLists.executeAndValidateBlocksWithAccessLists(
          List(block),
          weight,
          adoptEachBlock = true,
          list.map(block.header.hash.value -> _).toMap
        )
        withClue(s"block ${block.header.number}: ") {
          error shouldBe None
          imported should have size 1
          PathStateTestKit.allRows(new PathNodeStorage(other)) shouldBe stateOf(block.header)
        }
      }
      // the chain has Amsterdam blocks, each was handed with its list, and each prefetch got a reader of the view
      supplied should be > 0
      readersAsked shouldBe supplied
      readersGiven shouldBe supplied
    }

  // ─── The same path with the guard off ─────────────────────────────────────────────────────────────────────────────
  "The import path on the hash store" should "import the same chain to the same head, as it did before" taggedAs
    UnitTest in {
      val node = nodeOver(EphemDataSource(), guard = false)
      loadGenesis(node).hash shouldBe genesis.hash
      val hashPath = importPath(node)
      blocks.foreach { block =>
        val (imported, error) = importOne(node, hashPath, block)
        withClue(s"block ${block.header.number}: ") {
          error shouldBe None
          imported should have size 1
        }
      }
      node.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(referenceHead.header.hash)
      node.storagesInstance.storages.pathStateStorage shouldBe None
      // nothing of the unified layout was written
      PathStateTestKit.allRows(new PathNodeStorage(node.storagesInstance.dataSource)) shouldBe (Map.empty, Map.empty)
      node.storagesInstance.storages.appStateStorage.getStateAnchor() shouldBe None
      node.storagesInstance.storages.appStateStorage.getChangesetPrunedTo() shouldBe None
    }

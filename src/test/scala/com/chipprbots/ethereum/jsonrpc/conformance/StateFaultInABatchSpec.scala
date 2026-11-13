package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C4b (T142, T144): a batch of blocks is being imported on the unified layout, and between two of
  * its blocks another writer moves the head. The state the next block was executed on is then no longer the one the
  * disk holds, and the block cannot be committed. That is a fault of this node's state and no fault of the block:
  *
  *   - the blocks before it are adopted, each with its state, as a shorter batch would have left them;
  *   - the block it was met at wrote nothing, and neither it nor the blocks after it are judged, reported invalid or
  *     dropped from the queue: they are handed back to be tried again;
  *   - the best block is the block the disk holds, whoever moved it.
  *
  * The chain is the execution-apis test chain ([[RpcCompatNode]]), imported from its genesis on the unified layout by
  * the node's own consensus.
  */
class StateFaultInABatchSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit
  private given IORuntime = IORuntime.global

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  // The chain on the hash store: it says what each block's state is.
  private lazy val reference: RpcCompatNode =
    val node = new RpcCompatNode
    node.importChain()
    node

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

  /** A node on the unified layout with its genesis committed, and its consensus over an execution that runs `inside`
    * right after a block is executed and before it is committed.
    */
  final private class Node:
    val source: EphemDataSource = EphemDataSource()
    private trait Own extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    val node: RpcCompatNode =
      reference // first: it says what each state is
      new RpcCompatNode:
        override lazy val storagesInstance
            : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
          new Own with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
            override protected def statePathLive: Boolean = true
    given BlockchainConfig = node.blockchainConfig
    GenesisDataLoader
      .over(node.blockchainReader, node.blockchainWriter, node.storagesInstance.storages)
      .loadGenesisData()

    /** Run once, inside the execution of the block of that hash: after it is executed, before it is committed. */
    var inside: Map[ByteString, () => Unit] = Map.empty

    /** Run once, before the block of that hash is executed: after the block before it was committed. */
    var before: Map[ByteString, () => Unit] = Map.empty

    private def executionOf(hooked: Boolean): BlockExecution =
      new HeaderTrustingBlockExecution(
        node.blockchain,
        node.blockchainReader,
        node.blockchainWriter,
        node.storagesInstance.storages.evmCodeStorage,
        node.mining.blockPreparator,
        new BlockValidation(node.mining, node.blockchainReader, node.blockQueue)
      ):
        override def executeAndValidateStaged(
            block: Block,
            alreadyValidated: Boolean,
            staged: StagedBlockState
        )(implicit
            blockchainConfig: BlockchainConfig
        ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
          if hooked then
            before.get(block.hash.value).foreach { run =>
              before -= block.hash.value
              run()
            }
          val executed = super.executeAndValidateStaged(block, alreadyValidated, staged)
          if hooked then
            inside.get(block.hash.value).foreach { run =>
              inside -= block.hash.value
              run()
            }
          executed

    /** Another writer of this node: it commits `block` as the head, as an import of it would. */
    def otherWriterImports(block: Block): Unit =
      val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      executionOf(hooked = false).executeAndValidateBlocks(List(block), weight, adoptEachBlock = true) match
        case (List(_), None) => ()
        case other           => fail(s"the other writer's block ${block.header.number.value} was not imported: $other")

    var reported: Vector[ByteString] = Vector.empty
    private val reporter = new InvalidChainReporter:
      def reportInvalid(blockHash: ByteString, latestValidHash: ByteString): Unit = reported :+= blockHash

    val execution: BlockExecution = executionOf(hooked = true)
    val consensus =
      new ConsensusImpl(node.blockchainReader, node.blockchainWriter, execution, Some(reporter))
    val adapter = new ConsensusAdapter(
      consensus,
      node.blockchainReader,
      node.blockQueue,
      new BlockValidation(node.mining, node.blockchainReader, node.blockQueue),
      IORuntime.global,
      Some(reporter)
    )

    def pathState = node.storagesInstance.storages.pathStateStorage.get
    def disk: Disk = PathStateTestKit.allRows(new PathNodeStorage(source))
    val records = new ChangesetStorage(source)
    def best: Option[BlockHeader] = node.blockchainReader.getBestBlock.map(_.header)

  private lazy val blocks: Vector[Block] = RpcCompatNode.blocks.toVector

  /** Another block on `block`'s parent with `block`'s own transactions, and so its state: a competing head. */
  private def siblingOf(block: Block): Block =
    block.copy(header = block.header.copy(extraData = ByteString("a competing head".getBytes)))

  "A batch whose third block's state was moved from under it" should "adopt the two before it, judge none of the rest, and leave the best block on the block the disk holds" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      import on.given
      val batch = blocks.take(5)
      val competing = siblingOf(batch(2))
      competing.hash should not be batch(2).hash
      // between the execution of the third block and its commit, the other writer makes its own third block the head
      on.inside = Map(batch(2).hash.value -> (() => on.otherWriterImports(competing)))

      val result = on.consensus.evaluateBranch(NonEmptyList.fromListUnsafe(batch.toList)).unsafeRunSync()

      result match
        case ConsensusStateFault(fault, imported, toEnqueue) =>
          fault shouldBe a[StateUnavailable]
          imported.map(_.block) shouldBe batch.take(2).toList
          toEnqueue shouldBe batch.drop(2).toList
        case other => fail(s"the batch was answered with $other")
      // no block was reported invalid: none was judged
      on.reported shouldBe empty

      // the disk holds the other writer's block, and that is the best block: the pointer was not put back on a block
      // of the batch
      on.pathState.anchor shouldBe Some(refOf(competing.header))
      on.best.map(_.hash) shouldBe Some(competing.header.hash)
      on.disk shouldBe stateOf(batch(2).header)
      // the first two are on the chain with their records; the third has none, and the last two were never stored
      batch.take(2).foreach { block =>
        on.node.blockchainReader.getCanonicalHashByNumber(block.header.number.value) shouldBe Some(block.header.hash)
        on.records.get(block.header.hash.value).map(_.isRight) shouldBe Some(true)
      }
      on.node.blockchainReader.getCanonicalHashByNumber(batch(2).header.number.value) shouldBe
        Some(competing.header.hash)
      on.records.get(batch(2).header.hash.value) shouldBe None
      batch.drop(3).foreach { block =>
        on.node.blockchainReader.getBlockHeaderByHash(block.header.hash) shouldBe None
        on.node.blockchainReader.getCanonicalHashByNumber(block.header.number.value) shouldBe None
      }
    }

  // The same, with the head moved after the second block was committed and before the third is executed: the fault is
  // then met where the block's state is taken, not where it is committed.
  it should "end the same way when the head moved before the third block was executed" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      import on.given
      val batch = blocks.take(5)
      val competing = siblingOf(batch(2))
      on.before = Map(batch(2).hash.value -> (() => on.otherWriterImports(competing)))

      on.consensus.evaluateBranch(NonEmptyList.fromListUnsafe(batch.toList)).unsafeRunSync() match
        case ConsensusStateFault(fault, imported, toEnqueue) =>
          fault shouldBe a[StateUnavailable]
          imported.map(_.block) shouldBe batch.take(2).toList
          toEnqueue shouldBe batch.drop(2).toList
        case other => fail(s"the batch was answered with $other")
      on.reported shouldBe empty
      on.pathState.anchor shouldBe Some(refOf(competing.header))
      on.best.map(_.hash) shouldBe Some(competing.header.hash)
      on.disk shouldBe stateOf(batch(2).header)
      on.records.get(batch(2).header.hash.value) shouldBe None
    }

  "Any fault of the state met in a batch" should "end it with the blocks before adopted and the fault itself, not thrown" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      import on.given
      val batch = blocks.take(4)
      // the view the third block read through went stale: another kind of fault than a state that is not held
      val stale = new StaleStateViewException(refOf(batch(1).header), 2L, 6L, "the disk moved on")
      on.before = Map(batch(2).hash.value -> (() => throw stale))

      val (executed, error) =
        on.execution.executeAndValidateBlocks(batch.toList, ChainWeight.zero, adoptEachBlock = true)

      executed.map(_.block) shouldBe batch.take(2).toList
      error match
        case Some(BlockExecutionError.StateFaulted(fault)) => (fault should be).theSameInstanceAs(stale)
        case other                                         => fail(s"the batch ended with $other")
      // and what tells the endings apart takes it for no error of the block
      BlockExecution.BatchOutcome.of(executed, error) shouldBe BlockExecution.BatchOutcome(executed, None, Some(stale))
      on.pathState.anchor shouldBe Some(refOf(batch(1).header))
      on.best.map(_.hash) shouldBe Some(batch(1).header.hash)
      on.disk shouldBe stateOf(batch(1).header)
    }

  it should "be answered to the importer as a state fault with the adopted blocks, and keep the rest queued" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      import on.given
      // the first two are the head; the batch that follows is three blocks, and the head moves under its first
      blocks.take(2).foreach(on.otherWriterImports)
      val batch = blocks.slice(2, 5)
      val competing = siblingOf(batch.head)
      on.inside = Map(batch.head.hash.value -> (() => on.otherWriterImports(competing)))

      val result = on.adapter.evaluateBranch(NonEmptyList.fromListUnsafe(batch.toList)).unsafeRunSync()

      result match
        case BlockImportStateFault(fault, imported) =>
          fault shouldBe a[StateUnavailable]
          // the fault was met at the first block of this batch: nothing of it was adopted
          imported shouldBe Nil
        case other => fail(s"the batch was answered with $other")
      on.reported shouldBe empty
      // not judged, so not dropped: the blocks wait in the queue to be tried again
      batch.foreach(block => on.node.blockQueue.isQueued(block.hash) shouldBe true)
      on.best.map(_.hash) shouldBe Some(competing.header.hash)
      on.pathState.anchor shouldBe Some(refOf(competing.header))
    }

  "A batch on a head nothing moved" should "be imported whole, as it was" taggedAs (UnitTest, ConsensusTest) in {
    val on = new Node
    import on.given
    val batch = blocks.take(5)
    on.consensus.evaluateBranch(NonEmptyList.fromListUnsafe(batch.toList)).unsafeRunSync() match
      case com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranch(imported) =>
        imported.map(_.block) shouldBe batch.toList
      case other => fail(s"the batch was answered with $other")
    on.pathState.anchor shouldBe Some(refOf(batch.last.header))
    on.best.map(_.hash) shouldBe Some(batch.last.header.hash)
    on.disk shouldBe stateOf(batch.last.header)
  }

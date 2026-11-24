package com.chipprbots.ethereum.jsonrpc.conformance

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.util.chaining.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.consensus.engine.EngineApiService
import com.chipprbots.ethereum.consensus.engine.ExecutionPayload
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.engine.ForkChoiceState
import com.chipprbots.ethereum.consensus.engine.ForkchoiceUpdatedResponse
import com.chipprbots.ethereum.consensus.engine.PayloadAttributes
import com.chipprbots.ethereum.consensus.engine.PayloadStatus
import com.chipprbots.ethereum.consensus.engine.PayloadStatusV1
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock
import com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig
import com.chipprbots.ethereum.db.storage.pathstate.StateFaultMark
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.StateReconciliation
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.ledger.StateRecovery
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C4b part 2b (T137): a move of the head on the unified layout writes the node's index of blocks by
  * number, its best-block pointer and its transaction lookups '''in the batch that moves the state''', as
  * [[com.chipprbots.ethereum.domain.BlockchainWriter.canonicalHeadUpdate]] builds them; and a batch that fails writes
  * none of them. The chain is the execution-apis test chain ([[RpcCompatNode]]), imported on the unified layout by the
  * node's own execution, with its real headers, bodies and transactions.
  */
class HeadMoveOnTheChainSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit

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

  /** An in-memory source whose writes fail when told to. (A subclass: the storages ask whether a source is the
    * in-memory one, to know the shape of its keys.)
    *
    * '''It also watches the writes of the head''' (beacon, C4b part 3b): a write of the best block's rows or of the
    * index of blocks by number, made while the state has an anchor by a thread that does not hold the state lock, is a
    * writer that moves the head without the state, whatever its name. Each is kept in `offTheLock`; nothing is refused,
    * so a flow runs as the node runs it and the test says what it found.
    */
  final private class Failing extends EphemDataSource(Map.empty):
    @volatile var fails = false
    @volatile var watching: Option[PathStateStorage] = None
    val offTheLock = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val writes = new java.util.concurrent.atomic.AtomicInteger(0)
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      if fails then throw new java.io.IOException("the disk is full")
      writes.incrementAndGet()
      watching.filter(state => state.anchor.isDefined && !state.lock.isHeldByCurrentThread).foreach { _ =>
        dataSourceUpdates.flatMap(headWritten).foreach(offTheLock.add)
      }
      super.update(dataSourceUpdates)

  private val bestBlockKeys = Set(AppStateStorage.Keys.BestBlockNumber, AppStateStorage.Keys.BestBlockHash)

  /** What of the head an update writes: the index of blocks by number, and the best block's rows by their key. */
  private def headWritten(update: DataUpdate): Seq[String] =
    def of(namespace: IndexedSeq[Byte], keys: Seq[Array[Byte]]): Seq[String] =
      if keys.isEmpty then Nil
      else if namespace == Namespaces.HeightsNamespace then Seq("the index of blocks by number")
      else if namespace == Namespaces.AppStateNamespace then
        keys.map(key => new String(key, java.nio.charset.StandardCharsets.UTF_8)).filter(bestBlockKeys)
      else Nil
    update match
      case DataSourceUpdate(namespace, toRemove, toUpsert) =>
        of(namespace, (toRemove ++ toUpsert.map(_._1)).map(_.toArray))
      case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) => of(namespace, toRemove ++ toUpsert.map(_._1))

  /** A node on the unified layout with its genesis committed: on a datadir of its own, or on `opened`, another node's,
    * as the node is started again after it stopped (C4d part 3). `overlay` changes the bounds of the executed blocks
    * kept beside the disk.
    */
  final private class Node(
      opened: Option[Failing] = None,
      overlay: StateConfig.Overlay => StateConfig.Overlay = identity,
      tuned: StateConfig => StateConfig = identity
  ):
    val source: Failing = opened.getOrElse(new Failing)
    private trait Own extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    val node: RpcCompatNode =
      reference
      new RpcCompatNode:
        override lazy val storagesInstance
            : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
          new Own with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
            override protected def statePathLive: Boolean = true
            override protected def stateConfig: StateConfig =
              val config = StateConfig(com.chipprbots.ethereum.utils.Config.config)
              tuned(config.copy(overlay = overlay(config.overlay)))
    given BlockchainConfig = node.blockchainConfig
    GenesisDataLoader
      .over(node.blockchainReader, node.blockchainWriter, node.storagesInstance.storages)
      .loadGenesisData()
    source.watching = node.storagesInstance.storages.pathStateStorage

    private val execution = new HeaderTrustingBlockExecution(
      node.blockchain,
      node.blockchainReader,
      node.blockchainWriter,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      new BlockValidation(node.mining, node.blockchainReader, node.blockQueue)
    )

    /** The node's Engine API over this node, told the block whose state is held by `held`, as the node tells it. */
    def engine(
        held: () => Option[ByteString],
        choices: com.chipprbots.ethereum.consensus.engine.ForkChoiceManager = node.forkChoiceManager,
        recovery: Option[StateRecovery] = None,
        escalation: Option[com.chipprbots.ethereum.blockchain.sync.EscalationWatch] = None,
        pool: Option[
          org.apache.pekko.actor.typed.ActorRef[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command]
        ] = None
    ): EngineApiService =
      given org.apache.pekko.actor.typed.Scheduler = system.scheduler
      new EngineApiService(
        reader,
        writer,
        execution,
        choices,
        pool,
        stateHeldInPlace = Some(held),
        stateRecovery = recovery,
        escalationWatch = escalation
      )

    /** A recovery over this node's state, which keeps the tickets it hands out and the blocks it executes, in order, by
      * the thread they ran on (C4d part 3). `fails` fails a block's execution as its execution would, untouched.
      */
    final class Recovery(
        maxBlocks: Int,
        fails: Block => Option[BlockExecutionError] = _ => None,
        after: (Block, Seq[com.chipprbots.ethereum.domain.Receipt]) => Unit = (_, _) => (),
        // C4d part 4: before a block is committed in place, and whether it was
        adopting: Block => Unit = _ => (),
        // C5a part 8: how far a branch is adopted, how far an extension is caught up over, how many blocks at a time
        maxAdopted: Int = StateRecovery.DefaultMaxAdoptedBlocks,
        catchUp: Int = 0,
        chunk: Int = StateRecovery.CatchUpChunk
    ) extends StateRecovery(reader, writer, execution, pathState, maxBlocks, maxAdopted.max(maxBlocks), catchUp, chunk):
      val adopted = new java.util.concurrent.ConcurrentLinkedQueue[BigInt]()
      override protected def adoptBlock(
          block: Block,
          parentWeight: ChainWeight,
          beforeCommit: () => Option[BlockExecutionError]
      ): Either[BlockExecutionError, Any] =
        adopting(block)
        fails(block)
          .fold(super.adoptBlock(block, parentWeight, beforeCommit))(Left(_))
          .tap(_.foreach(_ => adopted.add(block.header.number.value)))
      def adoptedBlocks: Seq[BigInt] = adopted.toArray(Array.empty[BigInt]).toSeq
      val tickets = new java.util.concurrent.ConcurrentLinkedQueue[StateRecovery.Ticket]()
      val executed = new java.util.concurrent.ConcurrentLinkedQueue[(BigInt, String, Boolean)]()
      override def requestFor(
          head: ByteString,
          sink: StateRecovery.Sink,
          why: String,
          authority: StateRecovery.Authority
      ): StateRecovery.Ticket =
        super.requestFor(head, sink, why, authority).tap(tickets.add)
      override protected def execute(
          block: Block,
          alreadyValidated: Boolean
      ): Either[BlockExecutionError, com.chipprbots.ethereum.ledger.BlockImportGate.Executed] =
        executed.add((block.header.number.value, Thread.currentThread.getName, alreadyValidated))
        fails(block).fold(super.execute(block, alreadyValidated))(Left(_)).tap(_.foreach(done => after(block, done._1)))

      /** What each recovery handed out so far came to. */
      def outcomes: Seq[StateRecovery.Outcome] =
        tickets
          .toArray(Array.empty[StateRecovery.Ticket])
          .toSeq
          .map(_.outcome.get(60, java.util.concurrent.TimeUnit.SECONDS))
      private def all = executed.toArray(Array.empty[(BigInt, String, Boolean)]).toSeq
      def executedBlocks: Seq[BigInt] = all.map(_._1)
      def threads: Set[String] = all.map(_._2).toSet

      /** Each block executed, and whether its execution took it as validated before: it was executed once already. */
      def validated: Seq[(BigInt, Boolean)] = all.map(e => (e._1, e._3))

    // C4b part 4: a datadir staged as a defect would leave it, and the reconciliation at start.
    def storages = node.storagesInstance.storages
    def reconcile(testMode: Boolean = false): StateReconciliation.Outcome =
      StateReconciliation.run(storages.appStateStorage, pathState, reader, writer, testMode)
    def putBest(hash: ByteString, number: BigInt): Unit =
      storages.appStateStorage.putBestBlockInfo(BlockInfo(hash, number)).commit()
    def putIndex(number: BigInt, hash: ByteString): Unit = storages.blockNumberMappingStorage.put(number, hash).commit()
    def dropIndex(number: BigInt): Unit = storages.blockNumberMappingStorage.remove(number).commit()
    def putAppState(key: String, value: String): Unit =
      source.update(
        Seq(
          DataSourceUpdate(
            Namespaces.AppStateNamespace,
            Nil,
            Seq(key.getBytes.toIndexedSeq -> value.getBytes.toIndexedSeq)
          )
        )
      )
    def dropAppState(key: String): Unit =
      source.update(Seq(DataSourceUpdate(Namespaces.AppStateNamespace, Seq(key.getBytes.toIndexedSeq), Nil)))

    /** The datadir but for the finalized block a fork choice names (C4d part 1). */
    def withoutFinalized: Map[ByteString, ByteString] =
      val key = ByteString(Namespaces.AppStateNamespace.toArray ++ AppStateStorage.Keys.StateFinalized.getBytes)
      everything - key

    /** The datadir but for what the reconciliation may write: the index, the transaction lookups, the best block. */
    def outsideTheHead: Map[ByteString, ByteString] =
      everything.filterNot { (key, _) =>
        key.head == Namespaces.HeightsNamespace.head || key.head == Namespaces.TransactionMappingNamespace.head ||
        (key.head == Namespaces.AppStateNamespace.head &&
          bestBlockKeys(new String(key.tail.toArray, java.nio.charset.StandardCharsets.UTF_8)))
      }

    /** `block` executed and committed in place as the head, as an import of it does. */
    def imports(block: Block): Unit =
      val weight = node.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true) match
        case (List(_), None) => ()
        case other           => fail(s"block ${block.header.number.value} was not imported: $other")

    def pathState: PathStateStorage = node.storagesInstance.storages.pathStateStorage.get
    def reader = node.blockchainReader
    def writer = node.blockchainWriter

    /** A block as its header gives it, by its hash: what a move is planned from. */
    def link(hash: ByteString): Option[HeadMovePlan.Link] =
      reader.getBlockHeaderByHash(com.chipprbots.ethereum.domain.BlockHash(hash)).map { header =>
        HeadMovePlan.Link(refOf(header), header.parentHash.value)
      }

    def headerOf(ref: BlockRef): BlockHeader =
      reader.getBlockHeaderByHash(com.chipprbots.ethereum.domain.BlockHash(ref.hash)).get

    /** The writer's rows for the batch that ends on a block: what makes it the canonical head. */
    def headRows(chunk: HeadMovePlan.Chunk): Option[DataSourceBatchUpdate] =
      Some(writer.canonicalHeadUpdate(headerOf(chunk.head), reader).batch)

    def move(target: BlockHeader, store: PathStateStorage = pathState): PathStateStorage.MoveOutcome =
      store.moveHead(refOf(target), link, headRows)

    def txsOf(block: Block): Seq[ByteString] = block.body.transactionList.map(_.hash.value)

    /** The canonical chain of the block the state is at, oldest first: its ancestry by parent hash, from block 1. */
    def ancestryOf(ref: BlockRef): Vector[BlockHeader] =
      Iterator
        .iterate(Option(headerOf(ref)))(_.flatMap(h => reader.getBlockHeaderByHash(h.parentHash)))
        .takeWhile(_.exists(_.number.value > 0))
        .flatten
        .toVector
        .reverse

    /** Every transaction of every block of `ancestry` is looked up in that block. */
    def lookedUpIn(ancestry: Seq[BlockHeader]): Unit =
      ancestry.foreach { header =>
        reader.getBlockBodyByHash(header.hash).toSeq.flatMap(_.transactionList.map(_.hash.value)).foreach { tx =>
          withClue(s"a transaction of block ${header.number.value}: ")(lookup(tx) shouldBe Some(header.hash.value))
        }
      }

    /** The block a transaction is looked up in. */
    def lookup(tx: ByteString): Option[ByteString] =
      node.storagesInstance.storages.transactionMappingStorage.get(tx).map(_.blockHash)

    def disk: Disk = PathStateTestKit.allRows(new PathNodeStorage(source))
    def everything: Map[ByteString, ByteString] =
      source.storage.map((key, value) => ByteString(key.array()) -> ByteString(value))
    val records = new ChangesetStorage(source)

    /** The index of blocks by number, from 1 up to where it has no entry. */
    def index: Vector[ByteString] =
      Iterator
        .from(1)
        .map(n => reader.getCanonicalHashByNumber(BigInt(n)))
        .takeWhile(_.isDefined)
        .map(_.get.value)
        .toVector

    /** The disk holds `header`'s state, it is the anchor and the best block, and the index is its ancestry. */
    def holds(header: BlockHeader, ancestry: Seq[Block]): Unit =
      withClue(s"block ${header.number.value}: ") {
        pathState.anchor shouldBe Some(refOf(header))
        node.storagesInstance.storages.appStateStorage.getStateAnchor() shouldBe Some(refOf(header))
        reader.getBestBlockNumber shouldBe header.number.value
        node.storagesInstance.storages.appStateStorage.getBestBlockInfo().hash shouldBe header.hash.value
        index shouldBe ancestry.map(_.header.hash.value).toVector
        disk shouldBe stateOf(header)
      }

  private lazy val blocks: Vector[Block] = RpcCompatNode.blocks.toVector

  /** Another block on `block`'s parent with `block`'s own transactions, and so its state: a competing head. */
  private def siblingOf(block: Block): Block =
    block.copy(header = block.header.copy(extraData = ByteString("a competing head".getBytes)))

  "A move of the head on the unified layout" should "make the target the canonical head in the batch that moves the state: index, best block, lookups, anchor" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      on.holds(chain.last.header, chain)

      // down to the second block: three reversed by their records
      on.move(chain(1).header) match
        case moved: PathStateStorage.MoveOutcome.Moved => (moved.reversed, moved.applied) shouldBe (3, 0)
        case other                                     => fail(s"not moved: $other")
      on.holds(chain(1).header, chain.take(2))
      // the transactions of the blocks it left are looked up nowhere
      val left = chain.drop(2).flatMap(on.txsOf)
      left should not be empty
      left.foreach(tx => on.lookup(tx) shouldBe None)

      // a competing third block, committed in place on it as an import would
      val competing = siblingOf(chain(2))
      on.imports(competing)
      on.holds(competing.header, chain.take(2) :+ competing)
      val txs = competing.body.transactionList.map(_.hash.value)
      txs should not be empty
      txs.foreach(tx => on.lookup(tx) shouldBe Some(competing.header.hash.value))

      // across to the old head: the competing block reversed, the three of the old chain applied from their records
      on.move(chain.last.header) match
        case moved: PathStateStorage.MoveOutcome.Moved =>
          (moved.fork, moved.reversed, moved.applied, moved.recorded) shouldBe (refOf(chain(1).header), 1, 3, 0)
        case other => fail(s"not moved: $other")
      on.holds(chain.last.header, chain)
      // the transactions the two third blocks share are found in the one that is canonical
      txs.foreach(tx => on.lookup(tx) shouldBe Some(chain(2).header.hash.value))
      // every block of both branches keeps its record
      (chain :+ competing).foreach(block => on.records.get(block.header.hash.value).map(_.isRight) shouldBe Some(true))
    }

  it should "keep the index, the pointer and the lookups those of the state's block after each batch of a move across" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      on.move(chain(1).header)
      val competing = siblingOf(chain(2))
      on.imports(competing)
      on.imports(siblingOf(chain(3)).pipe(b => b.copy(header = b.header.copy(parentHash = competing.header.hash))))
      on.pathState.anchor.map(_.number) shouldBe Some(BigInt(4))
      // a store over the same disk that writes one block a batch
      val oneAtATime = new PathStateStorage(
        new PathNodeStorage(on.source),
        on.node.storagesInstance.storages.appStateStorage,
        settings = PathStateStorage.CommitSettings(maxBlocksPerMove = 1)
      )
      var seen = Vector.empty[BigInt]
      oneAtATime.moveHead(
        refOf(chain.last.header),
        on.link,
        chunk =>
          val at = oneAtATime.anchor.get
          seen :+= at.number
          val ancestry = on.ancestryOf(at)
          on.reader.getBestBlockNumber shouldBe at.number
          on.index shouldBe ancestry.map(_.hash.value)
          on.lookedUpIn(ancestry)
          on.disk shouldBe stateOf(on.headerOf(at))
          on.headRows(chunk)
      ) match
        case moved: PathStateStorage.MoveOutcome.Moved =>
          (moved.reversed, moved.applied, moved.batches) shouldBe (2, 3, 5)
        case other => fail(s"not moved: $other")
      // down the competing branch to the fork, then up the old chain
      seen shouldBe Vector(4, 3, 2, 3, 4).map(BigInt(_))
      on.index shouldBe chain.map(_.header.hash.value)
      on.lookedUpIn(chain.map(_.header))
      on.disk shouldBe stateOf(chain.last.header)
    }

  it should "write none of it when its batch fails: no index entry, no pointer, no anchor, no row" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      val before = on.everything
      on.source.fails = true
      an[java.io.IOException] should be thrownBy on.move(chain(1).header)
      on.source.fails = false
      on.everything shouldBe before
      on.holds(chain.last.header, chain)
      // and made again it is whole
      on.move(chain(1).header)
      on.holds(chain(1).header, chain.take(2))
    }

  it should "leave the index the ancestry of the block the state is at, after each of its batches" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(6)
      chain.foreach(on.imports)
      // a store over the same disk that writes one block a batch (the node's writes many)
      val oneAtATime = new PathStateStorage(
        new PathNodeStorage(on.source),
        on.node.storagesInstance.storages.appStateStorage,
        settings = PathStateStorage.CommitSettings(maxBlocksPerMove = 1)
      )
      var seen = Vector.empty[BigInt]
      oneAtATime.moveHead(
        refOf(chain.head.header),
        on.link,
        chunk =>
          // between two batches: the best block is the block the state is at, and the index is its ancestry
          val at = oneAtATime.anchor.get
          seen :+= at.number
          on.reader.getBestBlockNumber shouldBe at.number
          on.index shouldBe chain.take(at.number.toInt).map(_.header.hash.value)
          on.disk shouldBe stateOf(on.headerOf(at))
          on.headRows(chunk)
      ) match
        case moved: PathStateStorage.MoveOutcome.Moved => moved.batches shouldBe 5
        case other                                     => fail(s"not moved: $other")
      seen shouldBe Vector(6, 5, 4, 3, 2).map(BigInt(_))
      oneAtATime.anchor shouldBe Some(refOf(chain.head.header))
      on.reader.getBestBlockNumber shouldBe 1
      on.index shouldBe Vector(chain.head.header.hash.value)
      on.disk shouldBe stateOf(chain.head.header)
    }

  "A writer of the head" should "refuse on the unified layout to move the best block or the index without the state" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      val before = on.everything
      val refusals: Seq[(String, () => Unit)] = Seq(
        "saveBestKnownBlocks" -> (() =>
          on.writer.saveBestKnownBlocks(chain(1).header.hash, chain(1).header.number.value)
        ),
        "setCanonicalChainHead" -> (() =>
          on.writer
            .setCanonicalChainHead(chain(1).header.number.value, chain(1).header.hash, chain.last.header.number.value)
        ),
        "rewriteCanonicalIndex" -> (() =>
          on.writer.rewriteCanonicalIndex(Nil, Seq(chain.last.header.number.value), None, Nil, Nil, on.reader)
        ),
        "promoteToCanonicalHead" -> (() => on.writer.promoteToCanonicalHead(chain(1).header, on.reader)),
        "removeBlock" -> (() => on.node.blockchain.removeBlock(chain.last.header.hash)),
        // not in test mode: the chain is not removed for a new genesis either
        "removeChainForNewGenesis" -> (() => on.node.blockchain.removeChainForNewGenesis())
      )
      refusals.foreach { (named, write) =>
        withClue(s"$named: ") {
          an[IllegalStateException] should be thrownBy write()
          on.everything shouldBe before
        }
      }
      on.holds(chain.last.header, chain)
    }

  // C4b part 3b: the node's one way to move the head with the state.
  "The writer's move of the head" should "write each batch with the rows of the block it ends on, and stop where it is refused" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      on.move(chain(1).header)
      val competing = siblingOf(chain(2))
      on.imports(competing)
      val third = siblingOf(chain(3)).pipe(b => b.copy(header = b.header.copy(parentHash = competing.header.hash)))
      on.imports(third)
      // a writer over a store on the same disk that writes one block a batch
      val storages = on.node.storagesInstance.storages
      val oneAtATime = new PathStateStorage(
        new PathNodeStorage(on.source),
        storages.appStateStorage,
        settings = PathStateStorage.CommitSettings(maxBlocksPerMove = 1, checksBestBlock = true)
      )
      val writer = new BlockchainWriter(
        storages.blockHeadersStorage,
        storages.blockBodiesStorage,
        storages.blockNumberMappingStorage,
        storages.transactionMappingStorage,
        storages.receiptStorage,
        storages.chainWeightStorage,
        storages.appStateStorage,
        storages.blockAccessListStorage,
        pathState = Some(oneAtATime)
      )
      // what `holds` says, of the store that moved (the node's own store is not told of moves over its disk)
      def heldAt(header: BlockHeader, ancestry: Seq[Block]): Unit =
        withClue(s"block ${header.number.value}: ") {
          oneAtATime.anchor shouldBe Some(refOf(header))
          storages.appStateStorage.getStateAnchor() shouldBe Some(refOf(header))
          storages.appStateStorage.getBestBlockInfo().hash shouldBe header.hash.value
          on.reader.getBestBlockNumber shouldBe header.number.value
          on.index shouldBe ancestry.map(_.header.hash.value).toVector
          on.disk shouldBe stateOf(header)
        }
      val started = oneAtATime.movesStarted
      // refused at the first batch that applies a block: the two that reverse stand, down to the fork
      (the[StateUnavailable] thrownBy writer.moveHeadInPlace(
        chain.last.header,
        on.reader,
        chunk => Option.when(chunk.forward.nonEmpty)("no block is applied in this test")
      )).reason shouldBe "no block is applied in this test"
      heldAt(chain(1).header, chain.take(2))
      // and across: each batch carries its own block's rows, or the next batch would find the best block moved alone
      writer.moveHeadInPlace(chain.last.header, on.reader) match
        case moved: PathStateStorage.MoveOutcome.Moved => (moved.applied, moved.batches) shouldBe (3, 3)
        case other                                     => fail(s"not moved: $other")
      heldAt(chain.last.header, chain)
      // one move each, however many batches it wrote (B-249)
      oneAtATime.movesStarted shouldBe started + 2
    }

  // C4b part 3b: the fork choice moves the head through a move, under the state lock, or not at all.
  private def choiceOf(head: Block, chain: Seq[Block]): ForkChoiceState =
    ForkChoiceState(head.header.hash.value, chain.head.header.hash.value, chain.head.header.hash.value)

  "The fork choice on the unified layout" should "confirm the head the state is at, and change nothing" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      val choices = on.node.forkChoiceManager
      val before = on.withoutFinalized
      choices.applyForkChoiceState(choiceOf(chain.last, chain)) shouldBe Right(())
      choices.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.withoutFinalized shouldBe before; on.pathState.finalizedBlock.map(_.number) shouldBe Some(BigInt(1))
      on.holds(chain.last.header, chain)
      on.pathState.moveInProgress shouldBe false
    }

  // T145. The Engine API lets a fork choice through only for the block the state is at, and a block committed in place
  // after that look made the head move on. A fork choice written then as before 3b (promoteToCanonicalHead, outside
  // the state lock) moved the best block back to the old head and deleted the new head's index entry, under a state
  // that stayed the new head's: two heads, which the next commit then refused to write over.
  it should "not move the head back when a block was committed in place after the head was looked at" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.take(3).foreach(on.imports)
      val choices = on.node.forkChoiceManager
      val looked = choiceOf(chain(2), chain)
      on.reader.isHeldInPlace(looked.headBlockHash) shouldBe true
      // the next block, committed in place between the look and the write
      on.imports(chain(3))
      val before = on.everything
      choices.applyForkChoiceState(looked) shouldBe Left(ForkChoiceManager.Refusal.Syncing)
      on.everything shouldBe before
      on.holds(chain(3).header, chain)
      // not taken as a head that was applied
      choices.getHeadBlockHash shouldBe None
      // and the chain goes on from the head it has
      on.imports(blocks(4))
      on.holds(blocks(4).header, blocks.take(5))
    }

  // T145 end to end (beacon, part 3b's read): the commit lands where it can, between the Engine API's look at the head
  // and the fork choice's write. The head the fork choice names is then a VALID ancestor of the canonical head, and is
  // answered as one: VALID, no payload, nothing written.
  "The Engine API's fork choice on the unified layout" should "answer a head that a commit in place made an ancestor VALID, building nothing and writing nothing" taggedAs
    (UnitTest, ConsensusTest) in {
      Seq(false, true).foreach { withAttributes =>
        withClue(s"with payload attributes $withAttributes: ") {
          val on = new Node
          val chain = blocks.take(4)
          chain.take(3).foreach(on.imports)
          val looked = choiceOf(chain(2), chain)
          // the next block committed in place after every look the Engine API takes at the head, the gate's and its
          // own answer for an ancestor's, and before the fork choice is written
          val committing = new com.chipprbots.ethereum.consensus.engine.ForkChoiceManager(on.reader, on.writer):
            override def applyForkChoiceState(newState: ForkChoiceState): Either[ForkChoiceManager.Refusal, Unit] =
              on.imports(chain(3))
              super.applyForkChoiceState(newState)
          val engine = on.engine(() => on.pathState.anchor.map(_.hash), committing)
          val attributes = Option.when(withAttributes)(
            PayloadAttributes(
              chain(3).header.unixTimestamp.toLong + 12,
              ByteString(new Array[Byte](32)),
              com.chipprbots.ethereum.domain.Address(0x42),
              withdrawals = Some(Nil),
              parentBeaconBlockRoot = Some(ByteString(new Array[Byte](32)))
            )
          )
          val answer = engine
            .forkchoiceUpdated(looked, attributes)
            .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
          answer shouldBe Right(
            ForkchoiceUpdatedResponse(PayloadStatusV1(PayloadStatus.Valid, Some(looked.headBlockHash)), None)
          )
          on.holds(chain(3).header, chain)
          committing.getHeadBlockHash shouldBe None
          on.source.offTheLock.toArray.toSeq shouldBe empty
        }
      }
    }

  it should "confirm the head the state is at through the Engine API, under the state lock" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      val before = on.withoutFinalized
      val answer = on
        .engine(() => on.pathState.anchor.map(_.hash))
        .forkchoiceUpdated(choiceOf(chain.last, chain), None)
        .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      answer.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.withoutFinalized shouldBe before
      // the finalized block it names is the store's (C4d part 1)
      on.pathState.finalizedBlock shouldBe Some(FinalizedBlock(1, chain.head.header.hash.value))
      on.storages.appStateStorage.getStateFinalized() shouldBe on.pathState.finalizedBlock
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  // B-253: on the unified layout a block committed in place moves the head before any fork choice names it; the pool
  // is reset from the head it was last reset to, so those blocks leave it too
  it should "reset the tx pool over the blocks committed in place since the last fork choice" taggedAs
    (UnitTest, ConsensusTest) in {
      import com.chipprbots.ethereum.transactions.PendingTransactionsManager
      import scala.concurrent.duration.*
      val on = new Node
      val chain = blocks.take(10)
      chain.take(5).foreach(on.imports)
      val pool = createTestProbe[PendingTransactionsManager.Command]()
      val engine = on.engine(() => on.pathState.anchor.map(_.hash), pool = Some(pool.ref))
      def choose(head: Block, upTo: Seq[Block]) =
        engine
          .forkchoiceUpdated(choiceOf(head, upTo), None)
          .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
          .map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      def told(onto: Seq[Block]): Unit =
        val included = onto.map(_.body.transactionList).filter(_.nonEmpty)
        if included.isEmpty then pool.expectNoMessage(200.millis)
        else pool.expectMessage(PendingTransactionsManager.HeadReset(included, Nil))
      choose(chain(4), chain.take(5))
      told(Seq(chain(4)))
      // five blocks committed in place: the head is at the last before the fork choice names it
      chain.slice(5, 10).foreach(on.imports)
      on.reader.getBestBlockNumber shouldBe chain.last.header.number.value
      choose(chain.last, chain)
      chain.slice(5, 10).exists(_.body.transactionList.nonEmpty) shouldBe true
      told(chain.slice(5, 10))
      pool.expectNoMessage(200.millis)
    }

  // C4d part 2b: the fork choice moves the head up, or across, as one move; never down to an ancestor of the head.
  "The fork choice on the unified layout" should "move the head up the chain it left, and never down to an ancestor of the head" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      on.move(chain(1).header)
      val choices = on.node.forkChoiceManager
      val before = on.everything
      val started = on.pathState.movesStarted
      // down to the first block, an ancestor of the head: not followed, nothing written, no move counted
      choices.applyForkChoiceState(choiceOf(chain.head, chain)) shouldBe Left(ForkChoiceManager.Refusal.Syncing)
      on.everything shouldBe before
      on.pathState.movesStarted shouldBe started
      choices.getHeadBlockHash shouldBe None
      // up the chain it left, along its records: followed, as one move, the head and the index with the state
      choices.applyForkChoiceState(choiceOf(chain.last, chain)) shouldBe Right(())
      on.holds(chain.last.header, chain)
      choices.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.pathState.movesStarted shouldBe started + 1
      on.source.offTheLock.toArray.toSeq shouldBe empty
      on.pathState.moveInProgress shouldBe false
    }

  // C4d part 0: a record that is not what it should be is this datadir's corruption: the state fails closed (no
  // further in-place write, no state given out) and the fork choice is answered as one not followed.
  it should "fail the state closed on a record that is not there, and answer the head as one not followed" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      // the record of the head lost: the way back from it is this datadir's corruption, not a fork choice to wait out
      on.source.update(
        Seq(
          DataSourceUpdateOptimized(
            Namespaces.StateChangesetNamespace,
            Seq(ChangesetStorage.recordKey(chain.last.header.hash.value)),
            Nil
          )
        )
      )
      val before = on.everything
      // not followed, and said for what it is: a fault of this node, which counts towards giving the state up (C5a
      // part 6). The consensus client is answered SYNCING all the same
      val refused = on.node.forkChoiceManager.applyForkChoiceState(choiceOf(chain(2), chain))
      refused.left.toOption.exists(_.isInstanceOf[ForkChoiceManager.Refusal.LocalFault]) shouldBe true
      withClue("nothing is written but the record of the fault (B-252): ") {
        on.everything.filter((key, _) => before.contains(key)) shouldBe before
        (on.everything.keySet -- before.keySet).map(_.utf8String).toList match
          case List(added) => added should endWith(StateFaultMark.Key)
          case other       => fail(s"written beside the fault: $other")
        new AppStateStorage(on.source).getStateFault().get should include("changeset records are corrupt")
      }
      on.node.forkChoiceManager.getHeadBlockHash shouldBe None
      on.pathState.hasFailedClosed shouldBe true
      // and nothing more is written in place: not the next block, not a confirmation of the head
      an[org.scalatest.exceptions.TestFailedException] should be thrownBy on.imports(blocks(4))
      withClue("every fork choice refused while the state is closed says so: ") {
        on.node.forkChoiceManager
          .applyForkChoiceState(choiceOf(chain.last, chain))
          .left
          .toOption
          .exists(_.isInstanceOf[ForkChoiceManager.Refusal.LocalFault]) shouldBe true
      }
      on.holds(chain.last.header, chain)
      // closed until its state is cleared: the fault is recorded, and a restart opens the store closed (B-252)
      (the[StateUnavailable] thrownBy on.pathState.moveHead(
        refOf(chain.last.header),
        on.link,
        on.headRows
      )).reason should include("takes no write")
    }

  // C4d part 0 (B-249): a confirmation of the head moves nothing, and is no move to whoever builds on the head.
  it should "count a move that moves the head, and not a confirmation of it" taggedAs (UnitTest, ConsensusTest) in {
    val on = new Node
    val chain = blocks.take(4)
    chain.foreach(on.imports)
    val before = on.pathState.movesStarted
    on.node.forkChoiceManager.applyForkChoiceState(choiceOf(chain.last, chain)) shouldBe Right(())
    on.pathState.movesStarted shouldBe before
    // nor one that is refused before anything is written
    on.node.forkChoiceManager.applyForkChoiceState(choiceOf(chain(1), chain)) shouldBe Left(
      ForkChoiceManager.Refusal.Syncing
    )
    on.pathState.movesStarted shouldBe before
    on.move(chain(1).header)
    on.pathState.movesStarted shouldBe before + 1
  }

  /** The payload a consensus client sends for `block`: the fields its header and body carry. */
  private def payloadOf(block: Block): ExecutionPayload =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
    import com.chipprbots.ethereum.rlp.encode as rlpEncode
    val h = block.header
    ExecutionPayload(
      parentHash = h.parentHash.value,
      feeRecipient = com.chipprbots.ethereum.domain.Address(h.beneficiary),
      stateRoot = h.stateRoot.value,
      receiptsRoot = h.receiptsRoot.value,
      logsBloom = h.logsBloom.value,
      prevRandao = h.mixHash.value,
      blockNumber = h.number.value,
      gasLimit = h.gasLimit.value,
      gasUsed = h.gasUsed.value,
      timestamp = h.unixTimestamp.toLong,
      extraData = h.extraData,
      baseFeePerGas = h.baseFee.getOrElse(BigInt(0)),
      blockHash = h.hash.value,
      transactions =
        block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))),
      withdrawals = block.body.withdrawals,
      blobGasUsed = h.blobGasUsed,
      excessBlobGas = h.excessBlobGas,
      parentBeaconBlockRoot = h.parentBeaconBlockRoot.map(_.value),
      executionRequests = h.requestsHash.map(_ => Nil)
    )

  // C4d part 2a: a payload is executed as a forward layer beside the state the disk holds, and stored by its hash only.
  "A payload on the unified layout" should "be executed as a forward layer, VALID, stored by its hash only, and move nothing" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      // after the merge (block 36) and Shanghai (39): the blocks a consensus client sends as payloads
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      def newPayload(block: Block) =
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      val before = on.disk
      // one on the block whose state is held, and one on that: a layer on a layer
      Seq(blocks(39), blocks(40)).foreach { block =>
        withClue(s"block ${block.header.number.value}: ") {
          val status = newPayload(block)
          withClue(status.validationError.getOrElse(""))(status.status shouldBe PayloadStatus.Valid)
          // its state is held, beside the disk's
          on.pathState.view(refOf(block.header)).block shouldBe refOf(block.header)
          // stored by its hash, with its receipts, and named by no height of the index (I6: the index is the ancestry
          // of the block whose state is held)
          on.reader.getBlockHeaderByHash(block.header.hash) shouldBe Some(block.header)
          on.reader.getReceiptsByHash(block.header.hash) should not be empty
          on.reader.getCanonicalHashByNumber(block.header.number.value) shouldBe None
        }
      }
      // nothing moved: the disk, the anchor, the best block and the index are the held block's
      on.disk shouldBe before
      on.holds(chain.last.header, chain)
      on.source.offTheLock.toArray.toSeq shouldBe empty
      // and the fork choice to it is not followed before part 2b
      on.node.forkChoiceManager.getHeadBlockHash shouldBe None
    }

  it should "be followed by a fork choice: up two layers, across to a side branch, with a payload built on the new head, and refused where it would reverse the finalized block" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
      def newPayload(block: Block) = engine.newPayload(payloadOf(block)).unsafeRunSync()
      def choose(head: Block, finalized: ByteString, attributes: Option[PayloadAttributes] = None) =
        engine
          .forkchoiceUpdated(ForkChoiceState(head.header.hash.value, finalized, finalized), attributes)
          .unsafeRunSync()
      val (forty, fortyOne) = (blocks(39), blocks(40))
      Seq(forty, fortyOne).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      // up two layers, as one move: the head, the index and the anchor follow, and the head names block 40 finalized.
      // Its attributes are refused (-38003) after the move, which stands (execution-apis: the fork choice is applied,
      // then the attributes are looked at)
      val zeroTime = PayloadAttributes(0, ByteString(new Array[Byte](32)), com.chipprbots.ethereum.domain.Address(0x42))
      choose(fortyOne, forty.header.hash.value, Some(zeroTime)).left.toOption.getOrElse("") should startWith("ATTR:")
      on.holds(fortyOne.header, chain :+ forty :+ fortyOne)
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(fortyOne.header.hash.value)
      // a side branch from block 40: across, along block 41's record and the side block's layer
      val side = siblingOf(fortyOne)
      newPayload(side).status shouldBe PayloadStatus.Valid
      val attributes = PayloadAttributes(
        side.header.unixTimestamp.toLong + 12,
        ByteString(new Array[Byte](32)),
        com.chipprbots.ethereum.domain.Address(0x42),
        withdrawals = Some(Nil),
        parentBeaconBlockRoot = side.header.parentBeaconBlockRoot.map(_.value)
      )
      val across = choose(side, forty.header.hash.value, Some(attributes))
      across.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      // a payload is built on the head it moved to, and the block built is VALID when it comes back as a payload: the
      // state it was built on is the state the move left
      val payloadId = across.toOption.flatMap(_.payloadId)
      payloadId should not be empty
      val built = engine.getPayload(payloadId.get).unsafeRunSync().fold(fail(_), identity)
      built.header.parentHash shouldBe side.header.hash
      newPayload(built).status shouldBe PayloadStatus.Valid
      on.holds(side.header, chain :+ forty :+ side)
      // a fork choice whose head is on the other side of the finalized block 40: refused -38002, nothing moved
      val below = siblingOf(forty)
      newPayload(below).status shouldBe PayloadStatus.Valid
      val before = on.everything
      choose(below, ForkChoiceManager.ZeroHash).left.toOption.getOrElse("") should startWith("invalid forkchoice state")
      on.everything shouldBe before
      // and the head the import path follows is the one followed last, not the one refused (B-297)
      on.node.forkChoiceManager.getRequestedHeadBlockHash shouldBe Some(side.header.hash.value)
      on.holds(side.header, chain :+ forty :+ side)
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  // B-126: every payload here is stored by its hash only, so ChainWeightReconcile's final step, which checks the
  // heights the index names, never sees one; a payload's weight is its parent's when newPayload ran. The fork choice
  // that moves the head to it re-derives it from the parent's weight as it is then.
  it should "have the weights of the blocks a fork choice moves the head to re-derived from their parents' (B-126)" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
      val (forty, fortyOne) = (blocks(39), blocks(40))
      Seq(forty, fortyOne).foreach(block =>
        engine.newPayload(payloadOf(block)).unsafeRunSync().status shouldBe
          PayloadStatus.Valid
      )
      val held = on.reader.getChainWeightByHash(chain.last.header.hash).get
      on.reader.getChainWeightByHash(fortyOne.header.hash) shouldBe Some(
        held
          .increase(forty.header)
          .increase(
            fortyOne.header
          )
      )
      // a repair rewrites the held block's weight after the payloads took theirs from it
      val repaired = ChainWeight.totalDifficultyOnly(held.totalDifficulty.value + 1000)
      on.storages.chainWeightStorage.put(chain.last.header.hash.value, repaired).commit()

      engine
        .forkchoiceUpdated(
          ForkChoiceState(fortyOne.header.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash),
          None
        )
        .unsafeRunSync()
        .map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)

      on.holds(fortyOne.header, chain :+ forty :+ fortyOne)
      on.reader.getChainWeightByHash(forty.header.hash) shouldBe Some(repaired.increase(forty.header))
      on.reader.getChainWeightByHash(fortyOne.header.hash) shouldBe Some(
        repaired.increase(forty.header).increase(fortyOne.header)
      )
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  it should "be executed on a block behind the held one, as a side branch, and answer ACCEPTED on a parent it does not know" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(40)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      def newPayload(block: Block) =
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      // a sibling of the held block, on its parent, whose state the records hold
      val side = siblingOf(chain.last)
      val status = newPayload(side)
      withClue(status.validationError.getOrElse(""))(status.status shouldBe PayloadStatus.Valid)
      // its state is held: a payload on it executes, through its layer and the records that lead to its parent
      val onSide = blocks(40).pipe(b => b.copy(header = b.header.copy(parentHash = side.header.hash)))
      val next = newPayload(onSide)
      withClue(next.validationError.getOrElse(""))(next.status shouldBe PayloadStatus.Valid)
      on.reader.getCanonicalHashByNumber(side.header.number.value).map(_.value) shouldBe Some(
        chain.last.header.hash.value
      )
      on.holds(chain.last.header, chain)
      // a payload whose parent is not known: nothing executed, stored by its hash
      val orphan = blocks(41)
      newPayload(orphan).status shouldBe PayloadStatus.Accepted
      an[StateUnavailable] should be thrownBy on.pathState.view(refOf(orphan.header))
      on.holds(chain.last.header, chain)
      // a payload whose parent was executed and whose layer is not held any more: ACCEPTED, stored by its hash, and
      // nothing executed (its parent's state is executed again by whoever needs it, part 3)
      on.writer.dropForwardLayer(side.header.hash.value)
      val afterDrop = blocks(40).pipe(b =>
        b.copy(header = b.header.copy(parentHash = side.header.hash, extraData = ByteString("again".getBytes)))
      )
      newPayload(afterDrop).status shouldBe PayloadStatus.Accepted
      on.reader.getBlockHeaderByHash(afterDrop.header.hash) shouldBe Some(afterDrop.header)
      on.reader.getReceiptsByHash(afterDrop.header.hash) shouldBe None
      on.holds(chain.last.header, chain)
    }

  // C4d part 3 (T161, T169, T156): the stored blocks of a head whose state is no longer held are executed again, oldest
  // first, apart, on the recovery's own thread; the head is moved by the next fork choice, as for any executed payload.
  private def recoveringEngine(
      on: Node,
      recovery: StateRecovery,
      escalation: Option[com.chipprbots.ethereum.blockchain.sync.EscalationWatch] = None
  ) =
    val engine = on.engine(() => on.pathState.anchor.map(_.hash), recovery = Some(recovery), escalation = escalation)
    given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
    (
      (block: Block) => engine.newPayload(payloadOf(block)).unsafeRunSync(),
      (head: Block) =>
        engine
          .forkchoiceUpdated(
            ForkChoiceState(head.header.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash),
            None
          )
          .unsafeRunSync()
    )

  private def statusOf(answer: Either[String, ForkchoiceUpdatedResponse]): Any =
    answer.map(_.payloadStatus.status).fold(identity, identity)

  "A state that is no longer held" should "be had again by executing its stored blocks, oldest first, on the recovery's thread, when a fork choice names a head above them" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 8)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      val (forty, fortyOne) = (blocks(39), blocks(40))
      Seq(forty, fortyOne).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      // their layers go, as a restart takes them
      Seq(fortyOne, forty).foreach(block => on.writer.dropForwardLayer(block.header.hash.value))
      on.pathState.stateAvailable(refOf(fortyOne.header)) shouldBe false
      // the fork choice is answered SYNCING, and moves nothing; the two blocks are executed again
      val before = on.everything
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      recovery.outcomes shouldBe Seq(StateRecovery.Outcome.Recovered(2))
      recovery.executedBlocks shouldBe Seq(BigInt(40), BigInt(41))
      recovery.threads shouldBe Set(StateRecovery.ThreadName)
      on.holds(chain.last.header, chain)
      // the state is held again, beside the disk's, and the next fork choice follows the head
      on.pathState.stateAvailable(refOf(fortyOne.header)) shouldBe true
      on.everything.keySet shouldBe before.keySet
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Valid
      on.holds(fortyOne.header, chain :+ forty :+ fortyOne)
      on.source.offTheLock.toArray.toSeq shouldBe empty
      // a head whose state is held asks for nothing
      recovery.requestFor(fortyOne.header.hash.value, null, "a test").outcome.get() shouldBe
        StateRecovery.Outcome.AlreadyHeld
    }

  it should "be had again for a payload ACCEPTED on a parent whose state is not held, which a fork choice then follows" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 8)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      val (forty, fortyOne, fortyTwo) = (blocks(39), blocks(40), blocks(41))
      newPayload(forty).status shouldBe PayloadStatus.Valid
      on.writer.dropForwardLayer(forty.header.hash.value)
      // its parent was executed and its state is not held: ACCEPTED, and the two are executed again, apart
      newPayload(fortyOne).status shouldBe PayloadStatus.Accepted
      recovery.outcomes shouldBe Seq(StateRecovery.Outcome.Recovered(2))
      // each validated in full, the one executed before too
      recovery.validated shouldBe Seq((BigInt(40), false), (BigInt(41), false))
      // what newPayload keeps of an executed payload is kept of the one executed again: receipts, chain weight
      on.reader.getReceiptsByHash(fortyOne.header.hash) should not be empty
      on.reader.getChainWeightByHash(fortyOne.header.hash) should not be empty
      on.reader.getCanonicalHashByNumber(fortyOne.header.number.value) shouldBe None
      // a payload on it now executes, and a fork choice follows the head
      newPayload(fortyTwo).status shouldBe PayloadStatus.Valid
      statusOf(choose(fortyTwo)) shouldBe PayloadStatus.Valid
      on.holds(fortyTwo.header, chain :+ forty :+ fortyOne :+ fortyTwo)
      // a payload whose parent is not known asks for nothing
      newPayload(blocks(43)).status shouldBe PayloadStatus.Accepted
      recovery.tickets.size shouldBe 1
    }

  it should "be had again for a fork choice whose head was stored and never executed, on a parent that is stored" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      // a node that recovers nothing kept block 41 ACCEPTED, on block 40 whose layer went: stored, never executed
      val plain = on.engine(() => on.pathState.anchor.map(_.hash))
      def plainPayload(block: Block) =
        plain.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      val (forty, fortyOne) = (blocks(39), blocks(40))
      plainPayload(forty).status shouldBe PayloadStatus.Valid
      on.writer.dropForwardLayer(forty.header.hash.value)
      plainPayload(fortyOne).status shouldBe PayloadStatus.Accepted
      on.reader.getReceiptsByHash(fortyOne.header.hash) shouldBe None
      // the fork choice to it is SYNCING, and has the two executed again; the next is followed
      val recovery = new on.Recovery(maxBlocks = 8)
      val (_, choose) = recoveringEngine(on, recovery)
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      recovery.outcomes shouldBe Seq(StateRecovery.Outcome.Recovered(2))
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Valid
      on.holds(fortyOne.header, chain :+ forty :+ fortyOne)
    }

  it should "mark a block never executed before that fails invalid, with what stands on it, and execute nothing above it" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 8)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      val forty = blocks(39)
      newPayload(forty).status shouldBe PayloadStatus.Valid
      on.writer.dropForwardLayer(forty.header.hash.value)
      // a block whose header names another state than its execution leaves, on a parent whose state is not held:
      // ACCEPTED, as it cannot be judged yet; and one on it
      val bad = blocks(40).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      val onBad = blocks(41).pipe(b => b.copy(header = b.header.copy(parentHash = bad.header.hash)))
      newPayload(bad).status shouldBe PayloadStatus.Accepted
      recovery.outcomes.head shouldBe a[StateRecovery.Outcome.Invalid]
      // its parent is invalid now: so is it, at once
      newPayload(onBad).status shouldBe PayloadStatus.Invalid
      // executed again: block 40 holds, and the bad block is invalid with block 40 its latest valid block
      val chosen = choose(bad).toOption.get.payloadStatus
      chosen.status shouldBe PayloadStatus.Invalid
      chosen.latestValidHash shouldBe Some(forty.header.hash.value)
      choose(onBad).toOption.get.payloadStatus.status shouldBe PayloadStatus.Invalid
      recovery.executedBlocks.count(_ == BigInt(41)) shouldBe 1
      on.reader.getBlockHeaderByHash(bad.header.hash) shouldBe None
      on.pathState.stateAvailable(refOf(forty.header)) shouldBe true
      on.holds(chain.last.header, chain)
    }

  it should "not mark invalid a block executed before that fails now: the fault is the node's" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val forty = blocks(39)
      val recovery = new on.Recovery(
        maxBlocks = 8,
        fails = block =>
          Option.when(block.header.hash == forty.header.hash)(
            BlockExecutionError.ValidationAfterExecError("a disk that returns another state")
          )
      )
      val (newPayload, choose) = recoveringEngine(on, recovery)
      // executed, VALID, and its layer gone; then it fails when it is executed again
      on.engine(() => on.pathState.anchor.map(_.hash))
        .newPayload(payloadOf(forty))
        .unsafeRunSync()(using
          cats.effect.unsafe.IORuntime.global
        )
        .status shouldBe PayloadStatus.Valid
      on.writer.dropForwardLayer(forty.header.hash.value)
      newPayload(blocks(40)).status shouldBe PayloadStatus.Accepted
      recovery.outcomes.head shouldBe a[StateRecovery.Outcome.LocalFault]
      statusOf(choose(forty)) shouldBe PayloadStatus.Syncing
      on.reader.getBlockHeaderByHash(forty.header.hash) shouldBe Some(forty.header)
    }

  it should "mark the head's chain invalid above a block it rejects, after a restart that remembers no payload of theirs" taggedAs
    (UnitTest, ConsensusTest) in {
      val first = new Node
      val chain = blocks.take(39)
      chain.foreach(first.imports)
      val plain = first.engine(() => first.pathState.anchor.map(_.hash))
      def plainPayload(block: Block) =
        plain.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      val forty = blocks(39)
      val bad = blocks(40).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      val onBad = blocks(41).pipe(b => b.copy(header = b.header.copy(parentHash = bad.header.hash)))
      plainPayload(forty).status shouldBe PayloadStatus.Valid
      first.writer.dropForwardLayer(forty.header.hash.value)
      Seq(bad, onBad).foreach(block => plainPayload(block).status shouldBe PayloadStatus.Accepted)
      // started again: no layer is held, and no payload is remembered
      val again = new Node(opened = Some(first.source))
      val recovery = new again.Recovery(maxBlocks = 8)
      val (_, choose) = recoveringEngine(again, recovery)
      statusOf(choose(onBad)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.head shouldBe a[StateRecovery.Outcome.Invalid]
      // the head is INVALID, with the bad block's parent its latest valid block, and so is the bad block
      Seq(onBad, bad).foreach { block =>
        val chosen = choose(block).toOption.get.payloadStatus
        chosen.status shouldBe PayloadStatus.Invalid
        chosen.latestValidHash shouldBe Some(forty.header.hash.value)
        again.reader.getBlockHeaderByHash(block.header.hash) shouldBe None
      }
      again.holds(chain.last.header, chain)
    }

  it should "go on past a block whose state a fork choice put behind the anchor while it was being had again" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val plain = on.engine(() => on.pathState.anchor.map(_.hash))
      given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
      def plainPayload(block: Block) = plain.newPayload(payloadOf(block)).unsafeRunSync()
      val (forty, fortyOne, fortyTwo) = (blocks(39), blocks(40), blocks(41))
      plainPayload(forty).status shouldBe PayloadStatus.Valid
      on.writer.dropForwardLayer(forty.header.hash.value)
      plainPayload(fortyOne).status shouldBe PayloadStatus.Accepted
      // once block 41 is executed again (its receipts kept, as the sink keeps them), a payload on it and a fork choice to
      // that move the head past it: its layer is promoted, and its state is behind the anchor
      val moved = new java.util.concurrent.atomic.AtomicReference[Any]("not yet")
      val recovery = new on.Recovery(
        maxBlocks = 8,
        after = (block, receipts) =>
          if block.header.hash == fortyOne.header.hash then
            on.writer.storeReceipts(block.header.hash, receipts).commit()
            plainPayload(fortyTwo)
            moved.set(
              statusOf(
                plain
                  .forkchoiceUpdated(
                    ForkChoiceState(fortyTwo.header.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash),
                    None
                  )
                  .unsafeRunSync()
              )
            )
      )
      val sink = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
      recovery
        .requestFor(fortyOne.header.hash.value, sink, "a test")
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe
        StateRecovery.Outcome.Recovered(2)
      moved.get shouldBe PayloadStatus.Valid
      on.holds(fortyTwo.header, chain :+ forty :+ fortyOne :+ fortyTwo)
    }

  it should "find a block never executed whose requests are not those its header commits to invalid" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      // block 45 is the chain's first with a requests hash (Prague)
      val chain = blocks.take(44)
      chain.foreach(on.imports)
      val prague = blocks(44)
      val tampered = prague.header.extraFields match
        case fields: com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague =>
          prague.copy(header =
            prague.header.copy(extraFields = fields.copy(requestsHash = ByteString(Array.fill[Byte](32)(1))))
          )
        case other => fail(s"block 45 is not a Prague block: $other")
      // stored as a payload whose parent was not known is, and named by a fork choice
      on.writer.storeBlockByHashOnly(tampered).commit()
      val recovery = new on.Recovery(maxBlocks = 8)
      val (_, choose) = recoveringEngine(on, recovery)
      statusOf(choose(tampered)) shouldBe PayloadStatus.Syncing
      recovery.outcomes match
        case Seq(StateRecovery.Outcome.Invalid(block, reason)) =>
          block shouldBe refOf(tampered.header)
          reason should include("INVALID_REQUESTS")
        case other => fail(s"not rejected: $other")
      val chosen = choose(tampered).toOption.get.payloadStatus
      chosen.status shouldBe PayloadStatus.Invalid
      chosen.latestValidHash shouldBe Some(chain.last.header.hash.value)
      on.holds(chain.last.header, chain)
    }

  it should "have a side block's state again on a fork behind the anchor, which a fork choice then moves across to" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(40)
      chain.foreach(on.imports)
      val side = siblingOf(chain.last)
      on.engine(() => on.pathState.anchor.map(_.hash))
        .newPayload(payloadOf(side))
        .unsafeRunSync()(using
          cats.effect.unsafe.IORuntime.global
        )
        .status shouldBe PayloadStatus.Valid
      on.writer.dropForwardLayer(side.header.hash.value)
      val recovery = new on.Recovery(maxBlocks = 8)
      val (_, choose) = recoveringEngine(on, recovery)
      statusOf(choose(side)) shouldBe PayloadStatus.Syncing
      recovery.outcomes shouldBe Seq(StateRecovery.Outcome.Recovered(1))
      statusOf(choose(side)) shouldBe PayloadStatus.Valid
      on.holds(side.header, chain.init :+ side)
    }

  // spec 018 C5a part 6: the Engine API tells the escalation's watch how each recovery a fork choice asked for ended.
  it should "count each fork choice that names a head it cannot move to for a fault of its own, and say when the count is reached" taggedAs
    (UnitTest, ConsensusTest) in {
      import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger
      import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
      import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
      val on = new Node
      blocks.take(39).foreach(on.imports)
      val told = new java.util.concurrent.LinkedBlockingQueue[EscalationKind]()
      val said = new java.util.concurrent.LinkedBlockingQueue[String]()
      // two refused fork choices in a row, whatever the time between them
      val watch = new EscalationWatch(
        new EscalationTrigger(refusals = 2),
        () => System.currentTimeMillis(),
        kind => told.put(kind),
        (_, why) => said.put(why)
      )
      // a block executed before fails now: the fault is the node's, never the block's
      val (forty, fortyOne) = (blocks(39), blocks(40))
      val recovery = new on.Recovery(
        maxBlocks = 1,
        fails = block =>
          Option.when(block.header.hash == forty.header.hash)(
            BlockExecutionError.ValidationAfterExecError("a disk that returns another state")
          )
      )
      val (newPayload, choose) = recoveringEngine(on, recovery, Some(watch))
      Seq(forty, fortyOne).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      Seq(fortyOne, forty).foreach(block => on.writer.dropForwardLayer(block.header.hash.value))

      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.LocalFault]
      withClue("the first refusal waits: ")(told.isEmpty shouldBe true)

      // a fork choice the node follows ends the run: the refusal after it is the first of a new one
      statusOf(choose(blocks(38))) shouldBe PayloadStatus.Valid
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      withClue("after a head that was followed: ")(told.isEmpty shouldBe true)
      // and so does one that names an ancestor of the head, which is answered VALID and moves nothing
      statusOf(choose(blocks(37))) shouldBe PayloadStatus.Valid
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      withClue("after an ancestor of the head was named: ")(told.isEmpty shouldBe true)

      // named again, inside the recovery's cool-down: it ends at once as it ended, and counts as a fork choice refused
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      told.poll(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe EscalationKind.LocalFault
      Option(said.poll(60, java.util.concurrent.TimeUnit.SECONDS)).getOrElse("") should include("a fault of this node")
      statusOf(choose(fortyOne)) shouldBe PayloadStatus.Syncing
      told.poll(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe EscalationKind.LocalFault
      withClue("said once for the run: ")(said.isEmpty shouldBe true)
    }

  it should "execute nothing for a chain longer than it covers, a missing body, or a head below no held state, and not try such a head again at once" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val sink = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit =
          fail(s"block ${block.number} rejected")
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      Seq(blocks(39), blocks(40)).foreach { block =>
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
        on.writer.dropForwardLayer(block.header.hash.value)
      }
      val head = blocks(40).header.hash.value
      def outcomeOf(recovery: on.Recovery): StateRecovery.Outcome =
        recovery.requestFor(head, sink, "a test").outcome.get(60, java.util.concurrent.TimeUnit.SECONDS)
      // two blocks have no state held, and it covers one
      val short = new on.Recovery(maxBlocks = 1)
      val tooLong = outcomeOf(short)
      tooLong shouldBe a[StateRecovery.Outcome.TooLong]
      short.executedBlocks shouldBe empty
      // asked again within the cool-down: the answer it had, and nothing walked again
      (outcomeOf(short) should be).theSameInstanceAs(tooLong)
      short.inFlight shouldBe 0
      // the header of a block on the way is not stored
      val missing = unknownHash(7)
      new on.Recovery(maxBlocks = 8).requestFor(missing, sink, "a test").outcome.get() shouldBe
        StateRecovery.Outcome.MissingHeader(missing)
      // nor the body of block 40: nothing is executed, not even block 40's
      on.storages.blockBodiesStorage.remove(blocks(39).header.hash.value).commit()
      val noBody = new on.Recovery(maxBlocks = 8)
      outcomeOf(noBody) shouldBe StateRecovery.Outcome.MissingBody(refOf(blocks(39).header))
      noBody.executedBlocks shouldBe empty
      on.holds(chain.last.header, chain)
    }

  it should "stop at a block whose state was not kept, and execute nothing above it" taggedAs
    (UnitTest, ConsensusTest) in {
      // no layer is kept: each is more than a layer may hold
      val on = new Node(overlay = _.copy(maxLayerBytes = 1))
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 8)
      val (newPayload, _) = recoveringEngine(on, recovery)
      // block 40 is executed, VALID, and not kept; block 41 on it is ACCEPTED, and recovered
      newPayload(blocks(39)).status shouldBe PayloadStatus.Valid
      on.pathState.stateAvailable(refOf(blocks(39).header)) shouldBe false
      newPayload(blocks(40)).status shouldBe PayloadStatus.Accepted
      recovery.outcomes shouldBe Seq(StateRecovery.Outcome.NotKept(refOf(blocks(39).header)))
      recovery.executedBlocks shouldBe Seq(BigInt(40))
    }

  it should "run one recovery per head, queue a few, answer Busy beyond them, and end those waiting when it closes" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      Seq(blocks(39), blocks(40)).foreach { block =>
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
        on.writer.dropForwardLayer(block.header.hash.value)
      }
      val entered = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val recovery = new on.Recovery(
        maxBlocks = 8,
        fails = _ =>
          entered.countDown()
          release.await(60, java.util.concurrent.TimeUnit.SECONDS)
          None
      )
      val delivered = new java.util.concurrent.atomic.AtomicInteger(0)
      val sink = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit =
          delivered.incrementAndGet()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = ()
      val head = blocks(40).header.hash.value
      val running = recovery.requestFor(head, sink, "a test")
      entered.await(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      // the same head again: the recovery under way
      (recovery.requestFor(head, sink, "again") should be).theSameInstanceAs(running)
      // three more payloads' heads wait, and a fourth is not taken: the last place is a fork choice's (C4d part 4)
      val payloads = (1 until StateRecovery.QueueSize).map(n => recovery.requestFor(unknownHash(n), sink, "a test"))
      recovery.requestFor(unknownHash(99), sink, "a test").outcome.get() shouldBe StateRecovery.Outcome.Busy
      val choice =
        recovery.requestFor(unknownHash(98), sink, "a test", StateRecovery.Authority.ForkChoice)
      // and beyond it, nothing is taken
      recovery
        .requestFor(unknownHash(97), sink, "a test", StateRecovery.Authority.ForkChoice)
        .outcome
        .get() shouldBe StateRecovery.Outcome.Busy
      val waiting = payloads :+ choice
      recovery.inFlight shouldBe 1 + StateRecovery.QueueSize
      // closing ends those waiting, and the one under way after its block: block 41 is not executed
      val closing = new Thread(() => recovery.close())
      closing.start()
      waiting.foreach(_.outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Closed)
      release.countDown()
      closing.join(60000)
      running.outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Closed
      recovery.executedBlocks shouldBe Seq(BigInt(40))
      // and nothing of the block it finished was written: the data source closes after it
      delivered.get shouldBe 0
      recovery.requestFor(head, sink, "after").outcome.get() shouldBe StateRecovery.Outcome.Closed
      recovery.inFlight shouldBe 0
    }

  // B-307 (beacon, review of C5a part 7b): before the state is cleared for a sync from scratch
  it should "be reset around a clear: those waiting are ended, the one under way ends after its block, and it takes recoveries again" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      blocks.take(39).foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      Seq(blocks(39), blocks(40)).foreach { block =>
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
        on.writer.dropForwardLayer(block.header.hash.value)
      }
      val entered = new java.util.concurrent.CountDownLatch(1)
      val release = new java.util.concurrent.CountDownLatch(1)
      val recovery = new on.Recovery(
        maxBlocks = 8,
        fails = _ =>
          entered.countDown()
          release.await(60, java.util.concurrent.TimeUnit.SECONDS)
          None
      )
      val sink = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = ()
      val head = blocks(40).header.hash.value
      val running = recovery.requestFor(head, sink, "a test")
      entered.await(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
      val waiting = recovery.requestFor(unknownHash(1), sink, "a test")

      // the one under way does not end in the time given: nothing is run, and recoveries are taken as before
      var ran = 0
      recovery.reset(scala.concurrent.duration.DurationInt(200).millis)(ran += 1) shouldBe false
      ran shouldBe 0
      // those that waited were ended all the same
      waiting.outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Closed
      recovery.isClosed shouldBe false

      // with the time it needs: what is run inside sees no recovery running, and one asked for meanwhile is refused
      val inside = new java.util.concurrent.atomic.AtomicReference[(Int, StateRecovery.Outcome)]()
      val resetting = new Thread(() =>
        recovery.reset() {
          ran += 1
          inside.set((recovery.inFlight, recovery.requestFor(unknownHash(2), sink, "meanwhile").outcome.get()))
        }: Unit
      )
      resetting.start()
      // The recovery under way is released only once the reset has begun. Released before it, the recovery goes on to
      // block 41 and ends as recovered, which a slow machine showed (CI, 2026-11-16).
      val begun = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
      while !recovery.isResetting && System.nanoTime() < begun do Thread.onSpinWait()
      recovery.isResetting shouldBe true
      release.countDown()
      resetting.join(60000)
      running.outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Closed
      ran shouldBe 1
      inside.get() shouldBe ((0, StateRecovery.Outcome.Closed))
      // block 41 was not executed: the recovery ended after the block it was on
      recovery.executedBlocks shouldBe Seq(BigInt(40))
      // and it is not stopped: the same head is recovered now
      recovery.isClosed shouldBe false
      recovery.requestFor(head, sink, "after").outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) should not be
        StateRecovery.Outcome.Closed
    }

  it should "forget, at a reset, the heads whose recovery failed" taggedAs (UnitTest, ConsensusTest) in {
    val on = new Node
    blocks.take(39).foreach(on.imports)
    val recovery = new on.Recovery(maxBlocks = 8)
    val sink = new StateRecovery.Sink:
      def executed(
          block: Block,
          receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
          accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
      ): Unit = ()
      def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = ()
    // a head whose header is not stored yet: its recovery fails
    val head = blocks(40).header.hash.value
    recovery.requestFor(head, sink, "a test").outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe
      a[StateRecovery.Outcome.MissingHeader]
    // its blocks arrive; inside the cool-down the head is answered as it ended, without a look
    val engine = on.engine(() => on.pathState.anchor.map(_.hash))
    Seq(blocks(39), blocks(40)).foreach { block =>
      engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      on.writer.dropForwardLayer(block.header.hash.value)
    }
    recovery.requestFor(head, sink, "again").outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe
      a[StateRecovery.Outcome.MissingHeader]
    recovery.reset()(()) shouldBe true
    // after the reset it is looked at again, and recovered
    recovery.requestFor(head, sink, "after the reset").outcome.get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe
      a[StateRecovery.Outcome.Recovered]
  }

  it should "execute no further block once it is stopping, after the one it finished" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      Seq(blocks(39), blocks(40)).foreach { block =>
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
        on.writer.dropForwardLayer(block.header.hash.value)
      }
      val recovery = new on.Recovery(maxBlocks = 8)
      // the node begins to stop once block 40 is kept, before block 41 is begun
      val sink = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit =
          new Thread(() => recovery.close()).start()
          val until = System.nanoTime() + 60_000_000_000L
          while !recovery.isClosed && System.nanoTime() < until do Thread.onSpinWait()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
      recovery
        .requestFor(blocks(40).header.hash.value, sink, "a test")
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe
        StateRecovery.Outcome.Closed
      recovery.executedBlocks shouldBe Seq(BigInt(40))
    }

  // C4d part 4 (T159): a branch longer than the forward layers may hold is committed in place for a fork choice that
  // names its head, the head moved with it; a block found invalid on the way takes the head back to the last one
  // followed. The layers are three here, and a recovery as layers covers one block.
  private def tightNode = new Node(overlay = _.copy(maxForwardLayers = 3))

  private def choiceTo(head: Block, finalized: ByteString = ForkChoiceManager.ZeroHash) =
    ForkChoiceState(head.header.hash.value, finalized, finalized)

  "A branch longer than the forward layers may hold" should "be committed in place for a fork choice to its head, and followed by the next" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      val branch = blocks.slice(39, 44) // blocks 40 to 44
      // 40 to 42 are kept as layers; 43 does not fit, and is VALID; 44 stands on a state that is not held: ACCEPTED,
      // and a payload's recovery keeps layers, and moves no head
      branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      on.pathState.stateAvailable(refOf(branch(3).header)) shouldBe false
      newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.TooLong]
      recovery.adoptedBlocks shouldBe empty
      on.holds(chain.last.header, chain)
      // the fork choice to 44: SYNCING; the head is moved up the layers to 42, and 43 and 44 are committed in place
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(2)
      recovery.adoptedBlocks shouldBe Seq(BigInt(43), BigInt(44))
      on.holds(branch(4).header, chain ++ branch)
      // the fork choice it followed last is not the adoption's to change: the next one confirms the head
      on.node.forkChoiceManager.getHeadBlockHash shouldBe None
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Valid
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(branch(4).header.hash.value)
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  it should "take the head back to the last one a fork choice followed when a block of it is invalid" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      // the consensus client followed 39; then block 40 was committed in place: the adoption starts at 40
      statusOf(choose(chain.last)) shouldBe PayloadStatus.Valid
      on.imports(blocks(39))
      blocks.slice(40, 44).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid) // 41 to 44
      on.pathState.stateAvailable(refOf(blocks(43).header)) shouldBe false
      // a block on 44 whose header names another state than its execution leaves, stored and never executed
      val bad = blocks(44).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      on.writer.storeBlockByHashOnly(bad).commit()
      statusOf(choose(bad)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.Invalid]
      recovery.adoptedBlocks shouldBe Seq(BigInt(44))
      // back at 39, the head the consensus client followed, and not at 40, where the adoption began
      on.holds(chain.last.header, chain)
      val chosen = choose(bad).toOption.get.payloadStatus
      chosen.status shouldBe PayloadStatus.Invalid
      chosen.latestValidHash shouldBe Some(blocks(43).header.hash.value)
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
    }

  // B-464 (beacon): a fork choice to another head that has moved the head, and not yet recorded the head it followed,
  // when the adoption finds a block invalid. The head the fork choice followed last is still the old one: moved back to
  // it, the head would leave the block the consensus client is about to be told VALID for
  it should "leave the head where a fork choice in flight moved it when a block of it is invalid" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node(overlay = _.copy(maxForwardLayers = 8))
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val branch = blocks.slice(39, 43) // blocks 40 to 43
      val bad = blocks(43).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      // a competing 40', on 39, which the consensus client goes on to as the adoption commits 44
      val side = siblingOf(branch.head)
      val inFlight = new java.util.concurrent.atomic.AtomicBoolean(false)
      val recovery = new on.Recovery(
        maxBlocks = 1,
        fails = block =>
          Option.when(block.header.hash == bad.header.hash) {
            // the fork choice to 40', on its own thread: the head it names is recorded as asked for, and the head is
            // moved to it, and nothing more yet (ForkChoiceManager records the head it followed after the move)
            val choice = new Thread(() =>
              on.node.forkChoiceManager.notifyBeaconHead(choiceTo(side))
              on.writer.moveHeadInPlace(side.header, on.reader, forkChoice = true)
              inFlight.set(true)
            )
            choice.start()
            choice.join()
            BlockExecutionError.ValidationAfterExecError("a state root that is not the block's")
          }
      )
      val (newPayload, choose) = recoveringEngine(on, recovery)
      statusOf(choose(chain.last)) shouldBe PayloadStatus.Valid
      branch.foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      newPayload(side).status shouldBe PayloadStatus.Valid
      on.writer.storeBlockByHashOnly(bad).commit()
      // 44 stands on 43, a layer: more than the one block a recovery keeps as layers, so it is committed in place
      statusOf(choose(bad)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.Invalid]
      inFlight.get() shouldBe true
      recovery.adoptedBlocks shouldBe empty
      // the head is on 40', where the fork choice moved it, and not back on 39, the head it followed before
      on.holds(side.header, chain :+ side)
      // the fork choice records the head it followed: 40', the block the head is at
      on.node.forkChoiceManager.applyForkChoiceState(choiceTo(side)) shouldBe Right(())
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(side.header.hash.value)
      on.holds(side.header, chain :+ side)
    }

  it should "stop before its next commit when the consensus client names another head" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val branch = blocks.slice(39, 44)
      // as block 44 is about to be committed, the consensus client names 42 instead
      val recovery = new on.Recovery(
        maxBlocks = 1,
        adopting = block =>
          if block.header.hash == branch(4).header.hash then
            on.node.forkChoiceManager.notifyBeaconHead(choiceTo(branch(2)))
      )
      val (newPayload, choose) = recoveringEngine(on, recovery)
      branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Superseded
      // 43 was committed; 44 was validated and not committed; the head is not moved back
      recovery.adoptedBlocks shouldBe Seq(BigInt(43))
      on.holds(branch(3).header, chain ++ branch.take(4))
      // named again at once: a recovery that was superseded is no failure to wait out. 44 alone is left, on the head:
      // executed as a layer, and the next fork choice moves the head up to it
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Recovered(1)
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Valid
      on.holds(branch(4).header, chain ++ branch)
    }

  // B-490: the blocks an adoption makes canonical, by its move up the layers and by its commits in place, took their
  // weights from their parents' when they were executed, or when the adoption read the parent's weight, before the
  // commit's lock. A chain-weight repair (ChainWeightReconcile) that rewrote the canonical weights in between, and whose
  // final step checked the index and set its marker before they were canonical, would leave them their parents' old
  // ones. The adoption re-derives them, as a fork choice does (B-126).
  it should "re-derive the weights of the blocks it makes canonical from their parents' as they are then (B-490)" taggedAs
    (UnitTest, ConsensusTest) in {
      for during <- Seq(false, true) do
        withClue(if during then "repaired as block 44 is committed: " else "repaired before the fork choice: ") {
          val on = tightNode
          val chain = blocks.take(39)
          chain.foreach(on.imports)
          val base = chain.last.header
          val branch = blocks.slice(39, 44) // blocks 40 to 44
          val repaired = repairedWeightOf(on, base)
          val recovery = new on.Recovery(
            maxBlocks = 1,
            // the commit of 44, whose parent's weight the adoption has read already
            adopting = block => if during && block.header.hash == branch(4).header.hash then repairCanonical(on, base)
          )
          val (newPayload, choose) = recoveringEngine(on, recovery)
          branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
          newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
          if !during then repairCanonical(on, base)
          // the head is moved up the layers to 42 (40 to 42 made canonical), and 43 and 44 are committed in place
          statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
          recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(2)
          on.holds(branch(4).header, chain ++ branch)
          weightsOf(on, branch) shouldBe weightsOn(repaired, branch)
          // the next fork choice confirms the head, and leaves them so
          statusOf(choose(branch(4))) shouldBe PayloadStatus.Valid
          weightsOf(on, branch) shouldBe weightsOn(repaired, branch)
        }
    }

  /** An attempt that holds the records above the head, open on a thread of its own until what is returned is called: an
    * adoption on another thread, as an import's reverse-then-commit is (B-294).
    */
  private def anotherAttempt(on: Node): () => Unit =
    val opened = new java.util.concurrent.CountDownLatch(1)
    val done = new java.util.concurrent.CountDownLatch(1)
    val anchor = on.pathState.anchor.get
    val thread = new Thread(() =>
      on.pathState.withRecordsHeld(anchor, on.pathState.anchor) {
        opened.countDown()
        done.await()
      }
    )
    thread.start()
    opened.await()
    () =>
      done.countDown()
      thread.join()

  // B-294 (beacon W1): while another thread's adoption holds the records, the hold is refused, asking again. No fault
  // of this node and no verdict on the branch: superseded, so that it is asked for again at once and not counted
  it should "be superseded, not failed, while another thread's adoption is open, and committed in place once it ends" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      val branch = blocks.slice(39, 44)
      branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
      val release = anotherAttempt(on)
      try
        statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
        recovery.outcomes.last shouldBe StateRecovery.Outcome.Superseded
        recovery.adoptedBlocks shouldBe empty
        on.holds(chain.last.header, chain)
      finally release()
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(2)
      on.holds(branch(4).header, chain ++ branch)
    }

  /** A branch from block 39: 40' to 44', the chain's blocks but for their extra data (before Prague a block's state
    * does not depend on its parent's hash).
    */
  private def sideFrom39: Vector[Block] =
    blocks
      .slice(39, 44)
      .foldLeft(Vector.empty[Block]) { (made, block) =>
        val parent = made.lastOption.map(_.header.hash).getOrElse(block.header.parentHash)
        made :+ block
          .copy(header = block.header.copy(parentHash = parent, extraData = ByteString("a competing head".getBytes)))
      }

  it should "be committed across a fork, and the head taken back across it when its tip is invalid, along records only its hold keeps" taggedAs
    (UnitTest, ConsensusTest) in {
      // two blocks of records are kept: the way back across the fork is pruned by the branch's commits, but for the hold
      val on = new Node(
        overlay = _.copy(maxForwardLayers = 3),
        tuned = config =>
          config.copy(
            pruning = config.pruning.copy(history = 2),
            retention = config.retention.copy(finalityMargin = 0, maxBlocks = 2, withoutFinality = 2)
          )
      )
      val chain = blocks.take(41)
      chain.foreach(on.imports)
      // seen while 43' is committed: how far the adoption has got, and what the safe tag names
      val seen = new java.util.concurrent.ConcurrentLinkedQueue[(Option[StateRecovery.Progress], BigInt)]()
      lazy val tags = new com.chipprbots.ethereum.jsonrpc.ResolveBlock:
        def blockchain = on.node.blockchain
        def blockchainReader = on.reader
        def mining = on.node.mining
        override def forkChoiceManagerOpt: Option[ForkChoiceManager] = Some(on.node.forkChoiceManager)
      def safeNow: BigInt =
        tags
          .resolveBlock(com.chipprbots.ethereum.jsonrpc.BlockParam.Safe)
          .map(_.block.header.number.value)
          .getOrElse(BigInt(-1))
      lazy val recovery: on.Recovery = new on.Recovery(
        maxBlocks = 1,
        adopting = _ => seen.add((recovery.adoption, safeNow))
      )
      val (newPayload, choose) = recoveringEngine(on, recovery)
      // followed with 41 as its safe block and 39, the fork, finalized: a head on the branch holds it
      on.engine(() => on.pathState.anchor.map(_.hash), recovery = Some(recovery))
        .forkchoiceUpdated(
          ForkChoiceState(chain.last.header.hash.value, chain.last.header.hash.value, chain(38).header.hash.value),
          None
        )
        .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
        .map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      safeNow shouldBe 41
      val side = sideFrom39
      side.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      on.pathState.stateAvailable(refOf(side(3).header)) shouldBe false
      val bad = side(4).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      on.writer.storeBlockByHashOnly(bad).commit()
      statusOf(choose(bad)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.Invalid]
      // 43' was committed across the fork, 44' found invalid: the head is back on 41, the head followed
      recovery.adoptedBlocks shouldBe Seq(BigInt(43))
      // while the head was on the branch: the adoption from 41 to 44', at 42' then 43'; the safe block 41, which the
      // head had left, was named by where the two chains meet, 39 (part 4b)
      seen.toArray(Array.empty[(Option[StateRecovery.Progress], BigInt)]).toSeq shouldBe Seq(
        (Some(StateRecovery.Progress(39, 42, 44)), BigInt(39)),
        (Some(StateRecovery.Progress(39, 43, 44)), BigInt(39))
      )
      recovery.adoption shouldBe None
      safeNow shouldBe 41
      on.holds(chain.last.header, chain)
      val chosen = choose(bad).toOption.get.payloadStatus
      chosen.status shouldBe PayloadStatus.Invalid
      chosen.latestValidHash shouldBe Some(side(3).header.hash.value)
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  it should "move nothing when the consensus client names another head before it runs" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1)
      val (newPayload, _) = recoveringEngine(on, recovery)
      val branch = blocks.slice(39, 44)
      branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
      recovery.outcomes
      val elsewhere = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
        override def requestedHead: Option[ByteString] = Some(chain.last.header.hash.value)
      val before = on.everything
      recovery
        .requestFor(branch(4).header.hash.value, elsewhere, "a test", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Superseded
      on.everything shouldBe before
      on.holds(chain.last.header, chain)
    }

  it should "take the head back on a block executed before that fails now, and mark nothing invalid" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val branch = blocks.slice(39, 44)
      val recovery = new on.Recovery(
        maxBlocks = 1,
        fails = block =>
          Option.when(block.header.hash == branch(3).header.hash)(
            BlockExecutionError.ValidationAfterExecError("a disk that returns another state")
          )
      )
      val (newPayload, choose) = recoveringEngine(on, recovery)
      statusOf(choose(chain.last)) shouldBe PayloadStatus.Valid
      branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.LocalFault]
      on.holds(chain.last.header, chain)
      statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
      on.reader.getBlockHeaderByHash(branch(4).header.hash) shouldBe Some(branch(4).header)
    }

  it should "go on when the consensus client's new head extends the branch" taggedAs (UnitTest, ConsensusTest) in {
    val on = tightNode
    val chain = blocks.take(39)
    chain.foreach(on.imports)
    val branch = blocks.slice(39, 44)
    val next = blocks(44)
    val recovery = new on.Recovery(
      maxBlocks = 1,
      adopting = block =>
        if block.header.hash == branch(4).header.hash then
          // block 45 arrives, and the consensus client names it
          on.writer.storeBlockByHashOnly(next).commit()
          on.node.forkChoiceManager.notifyBeaconHead(choiceTo(next))
    )
    val (newPayload, choose) = recoveringEngine(on, recovery)
    branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
    newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
    statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
    recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(2)
    on.holds(branch(4).header, chain ++ branch)
  }

  it should "stop before its next commit when the node stops" taggedAs (UnitTest, ConsensusTest) in {
    val on = tightNode
    val chain = blocks.take(39)
    chain.foreach(on.imports)
    val branch = blocks.slice(39, 44)
    lazy val recovery: on.Recovery = new on.Recovery(
      maxBlocks = 1,
      adopting = block =>
        if block.header.hash == branch(4).header.hash then
          new Thread(() => recovery.close()).start()
          val until = System.nanoTime() + 60_000_000_000L
          while !recovery.isClosed && System.nanoTime() < until do Thread.onSpinWait()
    )
    val (newPayload, choose) = recoveringEngine(on, recovery)
    branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
    newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
    statusOf(choose(branch(4))) shouldBe PayloadStatus.Syncing
    recovery.outcomes.last shouldBe StateRecovery.Outcome.Closed
    recovery.adoptedBlocks shouldBe Seq(BigInt(43))
    on.holds(branch(3).header, chain ++ branch.take(4))
  }

  it should "be committed in place when the layers it stands on leave no room for it, though each would fit" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      val branch = blocks.slice(39, 42) // 40 to 42, each kept as a layer, then 42's dropped
      branch.foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      on.writer.dropForwardLayer(branch(2).header.hash.value)
      // one block to execute, on two layers: more than a recovery as layers covers
      statusOf(choose(branch(2))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(1)
      on.holds(branch(2).header, chain ++ branch)
    }

  it should "be committed in place when a fork choice's block does not fit as a layer" taggedAs
    (UnitTest, ConsensusTest) in {
      // no layer is kept
      val on = new Node(overlay = _.copy(maxLayerBytes = 1))
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 8)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      newPayload(blocks(39)).status shouldBe PayloadStatus.Valid
      on.pathState.stateAvailable(refOf(blocks(39).header)) shouldBe false
      statusOf(choose(blocks(39))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(1)
      statusOf(choose(blocks(39))) shouldBe PayloadStatus.Valid
      on.holds(blocks(39).header, chain :+ blocks(39))
    }

  it should "never be committed in place for a payload, however long it is with what it stands on" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      // a recovery of two blocks as layers, on two layers: within its reach, and long
      val recovery = new on.Recovery(maxBlocks = 2)
      val (newPayload, _) = recoveringEngine(on, recovery)
      blocks.slice(39, 42).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      on.writer.dropForwardLayer(blocks(41).header.hash.value)
      // even with the consensus client naming it as its head
      on.node.forkChoiceManager.notifyBeaconHead(choiceTo(blocks(42)))
      newPayload(blocks(42)).status shouldBe PayloadStatus.Accepted
      recovery.outcomes.last should not be a[StateRecovery.Outcome.Adopted]
      recovery.adoptedBlocks shouldBe empty
      on.holds(chain.last.header, chain)
    }

  it should "move nothing when another head is named between its look and its move" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = tightNode
      val chain = blocks.take(39)
      chain.foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1)
      val (newPayload, _) = recoveringEngine(on, recovery)
      val branch = blocks.slice(39, 44)
      branch.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      newPayload(branch(4)).status shouldBe PayloadStatus.Accepted
      recovery.outcomes
      // the tip is named when the adoption looks, and another head from then on
      val asked = new java.util.concurrent.atomic.AtomicInteger(0)
      val flipping = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
        override def requestedHead: Option[ByteString] =
          Some(if asked.getAndIncrement() == 0 then branch(4).header.hash.value else chain.last.header.hash.value)
      val before = on.everything
      recovery
        .requestFor(branch(4).header.hash.value, flipping, "a test", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Superseded
      on.everything shouldBe before
      on.holds(chain.last.header, chain)
    }

  // B-297 (beacon): another writer, the import path's move of the consensus layer's branch, takes the head up the
  // branch between the adoption's walk and its move. Moved to the block the walk found held, the head would go back down.
  it should "not move the head back down when another writer took it up the branch, or past its tip, after its walk" taggedAs
    (UnitTest, ConsensusTest) in {
      // block 42, of the chain above the block the walk finds held (41); block 44, above the tip (43). Block 45 is
      // Prague's, which these payloads do not carry
      for to <- Seq(42, 44) do
        val on = new Node
        val chain = blocks.take(38)
        chain.foreach(on.imports)
        val recovery = new on.Recovery(maxBlocks = 1)
        val (newPayload, _) = recoveringEngine(on, recovery)
        val branch = blocks.slice(38, 44)
        branch.take(3).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
        branch.drop(3).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
        recovery.outcomes
        val target = branch(to - 39)
        // as the adoption looks whether it is still asked for, the blocks up to the target are executed and the head
        // moved up to it
        val moved = new java.util.concurrent.atomic.AtomicBoolean(false)
        val racing = new StateRecovery.Sink:
          def executed(
              block: Block,
              receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
              accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
          ): Unit = ()
          def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
          override def requestedHead: Option[ByteString] =
            if !moved.getAndSet(true) then
              branch.slice(3, to - 38).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
              on.writer.moveHeadInPlace(target.header, on.reader)
            Some(branch(5).header.hash.value)
        withClue(s"the head moved to block $to: ") {
          recovery
            .requestFor(branch(4).header.hash.value, racing, "a test", StateRecovery.Authority.ForkChoice)
            .outcome
            .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Superseded
          // nothing was moved back or committed: the head is where the other writer took it
          recovery.adoptedBlocks shouldBe empty
          on.holds(target.header, chain ++ branch.take(to - 38))
        }
    }

  // C5a part 8 (the user's decision of 2026-11-15): after a sync hands off the head is the pivot, and the consensus
  // client's head is a straight extension of it, longer than a branch is adopted over

  /** A node at block 20, with blocks 21 to `upTo` stored by their hashes and never executed, as payloads the consensus
    * client sent while the node synced.
    */
  private def handedOff(upTo: Int = 44): Node =
    val on = new Node
    blocks.take(20).foreach(on.imports)
    blocks.slice(20, upTo).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
    on

  "A straight extension of the head, longer than a branch is adopted over" should "be caught up over in place, a chunk at a time, for a fork choice to its tip" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = handedOff()
      val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
      val (_, choose) = recoveringEngine(on, recovery)
      // 24 blocks above the head: SYNCING, and committed in place one by one
      statusOf(choose(blocks(43))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(24)
      recovery.adoptedBlocks shouldBe (21 to 44).map(BigInt(_))
      on.holds(blocks(43).header, blocks.take(44))
      // the next fork choice confirms the head
      statusOf(choose(blocks(43))) shouldBe PayloadStatus.Valid
    }

  // B-490: a catch-up makes canonical the blocks kept as layers above the head (by its move up to them) and the blocks
  // it commits in place, a chunk at a time. So does the p2p import path's hand-off of the consensus layer's branch
  // (`BranchHandOff`, `EngineApiService.handOffBranch`), which asks the same recovery. Their weights are re-derived
  // from their parents' as they are then, as a fork choice's are (B-126): a repair of the canonical weights between
  // their execution, or the read of a parent's weight, and their commit leaves none of them the old one.
  it should "re-derive the weights of the blocks it makes canonical from their parents' as they are then (B-490)" taggedAs
    (UnitTest, ConsensusTest) in {
      for
        byHandOff <- Seq(false, true)
        during <- Seq(false, true)
      do
        withClue(
          (if byHandOff then "handed off by the p2p import path, " else "for a fork choice, ") +
            (if during then "repaired as block 47 is committed: " else "repaired before: ")
        ) {
          val on = new Node
          blocks.take(39).foreach(on.imports)
          val base = blocks(38).header
          val repaired = repairedWeightOf(on, base)
          val recovery = new on.Recovery(
            maxBlocks = 1,
            maxAdopted = 4,
            catchUp = 30,
            chunk = 5,
            // the first block of the second chunk, whose parent's weight the catch-up has read already
            adopting = block => if during && block.header.hash == blocks(46).header.hash then repairCanonical(on, base)
          )
          val engine =
            on.engine(() => on.pathState.anchor.map(_.hash), recovery = Some(recovery))
          given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
          // 40 and 41 executed as layers on the head, with their weights; 42 to 60 stored by their hashes only
          blocks
            .slice(39, 41)
            .foreach(block => engine.newPayload(payloadOf(block)).unsafeRunSync().status shouldBe PayloadStatus.Valid)
          blocks.slice(41, 60).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
          if !during then repairCanonical(on, base)
          val tip = blocks(59)
          if byHandOff then
            on.node.forkChoiceManager.notifyBeaconHead(choiceTo(tip))
            engine.handOffBranch(tip.header.hash.value, "a test") shouldBe true
          else
            statusOf(
              engine
                .forkchoiceUpdated(choiceTo(tip), None)
                .unsafeRunSync()
            ) shouldBe PayloadStatus.Syncing
          // the head is moved up the layers to 41, and 42 to 60 are committed in place
          recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(19)
          on.holds(tip.header, blocks.take(60))
          val above = blocks.slice(39, 60)
          weightsOf(on, above) shouldBe weightsOn(repaired, above)
        }
    }

  /** A weight for `base` that is not the one stored: what a chain-weight repair would write. */
  private def repairedWeightOf(on: Node, base: BlockHeader): ChainWeight =
    ChainWeight.totalDifficultyOnly(on.reader.getChainWeightByHash(base.hash).get.totalDifficulty.value + 1000)

  /** What a chain-weight repair (ChainWeightReconcile) writes: `base`'s weight repaired, and every canonical block
    * above it (those the index names, up to the best block) re-derived from it. Blocks the index does not name it never
    * sees.
    */
  private def repairCanonical(on: Node, base: BlockHeader): Unit =
    val to = repairedWeightOf(on, base)
    on.storages.chainWeightStorage.put(base.hash.value, to).commit()
    (base.number.value + 1 to on.reader.getBestBlockNumber)
      .map(n => on.reader.getBlockHeaderByNumber(n))
      .takeWhile(_.isDefined)
      .flatten
      .foldLeft(to) { (weight, header) =>
        val next = weight.increase(header)
        on.storages.chainWeightStorage.put(header.hash.value, next).commit()
        next
      }

  /** The stored weight of each of `chain`. */
  private def weightsOf(on: Node, chain: Seq[Block]): Seq[Option[ChainWeight]] =
    chain.map(block => on.reader.getChainWeightByHash(block.header.hash))

  /** Each of `chain`'s exact weight, `chain` standing on a block of weight `from`. */
  private def weightsOn(from: ChainWeight, chain: Seq[Block]): Seq[Option[ChainWeight]] =
    chain.scanLeft(from)((weight, block) => weight.increase(block.header)).tail.map(Some(_))

  // B-316/B-480 (beacon): after a hand-off the index of blocks by number runs ahead of the anchor, over blocks whose
  // state no view reaches. A fork choice's walk stops by the records only where the head's chain leaves the index
  // BEHIND the anchor: a block of the index above it is reached by no record, and a stop there would refuse every
  // catch-up after a hand-off whose head is not the index's own chain
  it should "be caught up over in place when the index runs ahead of the head over it, as after a hand-off" taggedAs
    (UnitTest, ConsensusTest) in {
      // the head's chain as the index names it (21 to 44), and a branch of it that leaves it at block 30 (31' to 35')
      val side = blocks
        .slice(30, 35)
        .foldLeft(Vector.empty[Block]) { (made, block) =>
          val parent = made.lastOption.map(_.header.hash).getOrElse(block.header.parentHash)
          made :+ block
            .copy(header = block.header.copy(parentHash = parent, extraData = ByteString("a competing head".getBytes)))
        }
      for (tip, chainTo, adopted) <- Seq((blocks(43), blocks.take(44), 24), (side.last, blocks.take(30) ++ side, 15))
      do
        withClue(s"to block ${tip.header.number.value}: ") {
          val on = handedOff()
          side.foreach(block => on.writer.storeBlockByHashOnly(block).commit())
          // the index names blocks 21 to 44, and the lookups of their transactions name them, as a sync leaves them;
          // the head and the state are at block 20
          (21 to 44).foreach(n => on.writer.storeBlock(blocks(n - 1)).commit())
          on.pathState.anchor.map(_.number) shouldBe Some(BigInt(20))
          on.pathState.stateAvailable(refOf(blocks(29).header)) shouldBe false
          // B-482 (beacon): the index above each block committed, and the lookups of the transactions of the old
          // blocks above it, as the disk holds them right after its commit, read before the next block is committed:
          // what a node killed between the two would keep
          val afterEach = scala.collection.mutable.Map.empty[Int, (Seq[Option[ByteString]], Seq[Option[ByteString]])]
          val recovery = new on.Recovery(
            maxBlocks = 1,
            maxAdopted = 4,
            catchUp = 30,
            chunk = 5,
            adopting = block =>
              val below = block.header.number.value.toInt - 1
              afterEach(below) = (
                (below + 1 to 44).map(h => on.reader.getCanonicalHashByNumber(h).map(_.value)),
                blocks.slice(below, 44).flatMap(on.txsOf).map(on.lookup)
              )
          )
          val (_, choose) = recoveringEngine(on, recovery)
          statusOf(choose(tip)) shouldBe PayloadStatus.Syncing
          // walked down to the anchor, and not stopped on the index above it: caught up over, every block
          recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(adopted)
          on.pathState.anchor shouldBe Some(refOf(tip.header))
          on.reader.getBestBlockNumber shouldBe tip.header.number.value
          on.disk shouldBe stateOf(tip.header)
          chainTo.foreach(block =>
            on.reader.getCanonicalHashByNumber(block.header.number.value) shouldBe Some(block.header.hash)
          )
          // every transaction of the head's chain above the anchor is looked up in its block there
          on.lookedUpIn(chainTo.drop(20).map(_.header))
          // B-482: the chain left the index at block 31'. The old chain's entries above the head are gone, and so are
          // the lookups of the transactions only those blocks hold (go-ethereum's reorg deletes both)
          val left = blocks.slice(chainTo.size, 44)
          if tip eq side.last then
            left.map(_.header.number.value) shouldBe (36 to 44).map(BigInt(_))
            left.flatMap(on.txsOf) should not be empty
          left.foreach(block => on.reader.getCanonicalHashByNumber(block.header.number.value) shouldBe None)
          left.flatMap(on.txsOf).foreach(tx => on.lookup(tx) shouldBe None)
          // and so right after the very commit that leaves the index, in its batch: no kill leaves them behind. Where
          // the chain is the index's own, its entries above the head stay until they are committed over
          (21 until tip.header.number.value.toInt).foreach { n =>
            val (above, lookups) = afterEach(n)
            withClue(s"after block $n: ") {
              if n <= 30 || (tip eq blocks(43)) then
                above shouldBe (n + 1 to 44).map(h => Some(blocks(h - 1).header.hash.value))
                lookups.foreach(_ should not be empty)
              else
                above.foreach(_ shouldBe None)
                lookups.foreach(_ shouldBe None)
            }
          }
        }
    }

  // B-482: the clean-up is the block's own commit's, whoever commits it. A block imported onto the head, stored by
  // nothing before its commit, keeps the lookups of the transactions it shares with the block it replaces
  "A block committed as the head where the index names another block at its height" should "clear the index above it and the lookups only the blocks it drops hold, in its own batch, and keep its own" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      blocks.take(20).foreach(on.imports)
      // the index names blocks 21 to 25 above the head, with their lookups, as a sync leaves them
      (21 to 25).foreach(n => on.writer.storeBlock(blocks(n - 1)).commit())
      val competing = siblingOf(blocks(20))
      on.reader.getBlockHeaderByHash(competing.header.hash) shouldBe None
      val writesBefore = on.source.writes.get
      on.imports(competing)
      // one batch: the block, its state, and the clean-up
      on.source.writes.get - writesBefore shouldBe 1
      on.holds(competing.header, blocks.take(20) :+ competing)
      (22 to 25).foreach(n => on.reader.getCanonicalHashByNumber(n) shouldBe None)
      val own = on.txsOf(competing)
      own should not be empty
      own.foreach(tx => on.lookup(tx) shouldBe Some(competing.header.hash.value))
      val dropped = blocks.slice(21, 25).flatMap(on.txsOf)
      dropped should not be empty
      dropped.foreach(tx => on.lookup(tx) shouldBe None)
    }

  // B-492 (vault): a gap at the block's height, left by a removal that stopped at the first height with no entry, with
  // another chain's entries above it
  it should "clear the index above it where the index names nothing at its height and names a block above it" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      blocks.take(20).foreach(on.imports)
      (21 to 25).foreach(n => on.writer.storeBlock(blocks(n - 1)).commit())
      on.dropIndex(21)
      val competing = siblingOf(blocks(20))
      val writesBefore = on.source.writes.get
      on.imports(competing)
      on.source.writes.get - writesBefore shouldBe 1
      on.holds(competing.header, blocks.take(20) :+ competing)
      (22 to 25).foreach(n => on.reader.getCanonicalHashByNumber(n) shouldBe None)
      on.txsOf(competing).foreach(tx => on.lookup(tx) shouldBe Some(competing.header.hash.value))
      val dropped = blocks.slice(21, 25).flatMap(on.txsOf)
      dropped should not be empty
      dropped.foreach(tx => on.lookup(tx) shouldBe None)
    }

  it should "leave the index above it where the index names the block itself: its own chain, committed over in turn" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      blocks.take(20).foreach(on.imports)
      (21 to 25).foreach(n => on.writer.storeBlock(blocks(n - 1)).commit())
      on.imports(blocks(20))
      on.pathState.anchor shouldBe Some(refOf(blocks(20).header))
      on.index shouldBe blocks.take(25).map(_.header.hash.value)
      on.lookedUpIn(blocks.take(25).map(_.header))
    }

  "A straight extension of the head, longer than a branch is adopted over" should "be a head that cannot be reached when it is longer than the catch-up covers" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val on = handedOff()
    val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 10)
    val (_, choose) = recoveringEngine(on, recovery)
    val before = on.everything
    statusOf(choose(blocks(43))) shouldBe PayloadStatus.Syncing
    recovery.outcomes.last shouldBe a[StateRecovery.Outcome.TooLong]
    recovery.adoptedBlocks shouldBe empty
    on.everything shouldBe before
    // and as far as a branch is adopted when no catch-up is configured: the bound of before
    val none = handedOff()
    val asBefore = new none.Recovery(maxBlocks = 1, maxAdopted = 4)
    asBefore.maxCatchUp shouldBe 4
    val (_, chooseNone) = recoveringEngine(none, asBefore)
    statusOf(chooseNone(blocks(43))) shouldBe PayloadStatus.Syncing
    asBefore.outcomes.last shouldBe a[StateRecovery.Outcome.TooLong]
  }

  it should "leave the head at the last valid block when a block of it is invalid: there is no head to go back to" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = handedOff(upTo = 30)
      // block 31 names another state than its execution leaves; 32 stands on it
      val bad = blocks(30).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      on.writer.storeBlockByHashOnly(bad).commit()
      val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
      val (_, choose) = recoveringEngine(on, recovery)
      statusOf(choose(bad)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.Invalid]
      // blocks 21 to 30 are the head's chain now, and stay: nothing is moved back
      recovery.adoptedBlocks shouldBe (21 to 30).map(BigInt(_))
      on.holds(blocks(29).header, blocks.take(30))
      val chosen = choose(bad).toOption.get.payloadStatus
      chosen.status shouldBe PayloadStatus.Invalid
      chosen.latestValidHash shouldBe Some(blocks(29).header.hash.value)
    }

  // B-294 (beacon W1): the move up the executed blocks kept, which a catch-up makes first, is refused, asking again,
  // while another thread's adoption is open: superseded, as a commit refused on the way up is, and not a fault
  it should "be superseded, not failed, when its first move is refused while another thread's adoption is open" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      blocks.take(39).foreach(on.imports)
      val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 2, catchUp = 30, chunk = 5)
      val (newPayload, choose) = recoveringEngine(on, recovery)
      // 40 and 41 are kept as layers above the head, and 42 to 44 are stored and never executed: more than a branch is
      // adopted over, so they are caught up over, and the head is moved up to 41 first
      blocks.slice(39, 41).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      blocks.slice(41, 44).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
      val release = anotherAttempt(on)
      try
        statusOf(choose(blocks(43))) shouldBe PayloadStatus.Syncing
        recovery.outcomes.last shouldBe StateRecovery.Outcome.Superseded
        recovery.adoptedBlocks shouldBe empty
        on.holds(blocks(38).header, blocks.take(39))
      finally release()
      statusOf(choose(blocks(43))) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(3)
      on.holds(blocks(43).header, blocks.take(44))
    }

  // spec 018 C5a part 10 (beacon C6): told as the move of the head it made, not as the body it stopped at, so that the
  // escalation does not count a catch-up that committed blocks as a refusal
  it should "stop at a block whose body is not stored, keep what it committed, and say it moved the head" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val on = handedOff(upTo = 30)
    // block 31 has its header and no body; 32 to 44 are whole
    on.writer.storeBlockHeaderByHashOnly(blocks(30).header).commit()
    blocks.slice(31, 44).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
    val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
    val (_, choose) = recoveringEngine(on, recovery)
    statusOf(choose(blocks(43))) shouldBe PayloadStatus.Syncing
    recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(10)
    // two chunks of five were committed; the third was not begun
    recovery.adoptedBlocks shouldBe (21 to 30).map(BigInt(_))
    on.holds(blocks(29).header, blocks.take(30))
  }

  it should "stop before its next commit when the consensus client names a head its chain does not lead to" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = handedOff()
      // a block of another chain, stored: one whose header is not stored yet would not stop a catch-up
      val beside = blocks(20).pipe(b => b.copy(header = b.header.copy(extraData = ByteString("beside".getBytes))))
      on.writer.storeBlockByHashOnly(beside).commit()
      val elsewhere = new java.util.concurrent.atomic.AtomicBoolean(false)
      val recovery = new on.Recovery(
        maxBlocks = 1,
        maxAdopted = 4,
        catchUp = 30,
        chunk = 5,
        // once block 27 is to be committed, the consensus client is on another chain
        adopting = block => if block.header.number.value == 27 then elsewhere.set(true)
      )
      val named = new StateRecovery.Sink:
        def executed(
            block: Block,
            receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
            accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
        ): Unit = ()
        def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
        override def requestedHead: Option[ByteString] =
          Some(if elsewhere.get() then beside.header.hash.value else blocks(43).header.hash.value)
      recovery
        .requestFor(blocks(43).header.hash.value, named, "a test", StateRecovery.Authority.ForkChoice)
        .outcome
        .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Superseded
      // block 27 was committed (it was already asked for when the head changed); the blocks above it were not
      on.pathState.anchor.map(_.number) should (be(Some(BigInt(27))).or(be(Some(BigInt(26)))))
      recovery.adoptedBlocks.max should be <= BigInt(27)
    }

  it should "go on from the head at the fork choice that follows a stop" taggedAs (UnitTest, ConsensusTest) in {
    val on = handedOff(upTo = 30)
    on.writer.storeBlockHeaderByHashOnly(blocks(30).header).commit()
    blocks.slice(31, 44).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
    val first = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
    statusOf(recoveringEngine(on, first)._2(blocks(43))) shouldBe PayloadStatus.Syncing
    first.outcomes.last shouldBe StateRecovery.Outcome.Adopted(10)
    // the body arrives; as after a restart, another recovery over the same datadir
    on.writer.storeBlockByHashOnly(blocks(30)).commit()
    val second = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
    statusOf(recoveringEngine(on, second)._2(blocks(43))) shouldBe PayloadStatus.Syncing
    second.outcomes.last shouldBe StateRecovery.Outcome.Adopted(14)
    on.holds(blocks(43).header, blocks.take(44))
  }

  // spec 018 C5a part 10 (beacon C5): while a sync from scratch runs no state is held, and a walk from no anchor would
  // end as a fault or as a chain that cannot be reached, and be counted
  it should "ask for no recovery, and count nothing, while no state is held" taggedAs (UnitTest, ConsensusTest) in {
    val on = handedOff()
    val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
    val watch = new com.chipprbots.ethereum.blockchain.sync.EscalationWatch(
      new com.chipprbots.ethereum.blockchain.sync.EscalationTrigger(1),
      () => 0L,
      _ => fail("escalated"),
      (_, _) => ()
    )
    val run = watch.currentRun
    val engine = on.engine(() => None, recovery = Some(recovery), escalation = Some(watch))
    given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
    val answer = engine
      .forkchoiceUpdated(
        ForkChoiceState(blocks(43).header.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash),
        None
      )
      .unsafeRunSync()
    statusOf(answer) shouldBe PayloadStatus.Syncing
    recovery.tickets shouldBe empty
    recovery.executedBlocks shouldBe empty
    watch.currentRun shouldBe run
  }

  // beacon, after code: the blocks of the chunks not read yet stand on the invalid block too
  it should "answer INVALID for the head when a block far below it is invalid, with the last valid block" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = handedOff(upTo = 30)
      // block 31 names another state than its execution leaves; the blocks above it, to 44, stand on it
      val bad = blocks(30).pipe(b => b.copy(header = b.header.copy(stateRoot = TrieRoot.Empty)))
      val above = blocks.slice(31, 44).foldLeft(Vector(bad)) { (made, block) =>
        made :+ block.copy(header = block.header.copy(parentHash = made.last.header.hash))
      }
      above.foreach(block => on.writer.storeBlockByHashOnly(block).commit())
      val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 4, catchUp = 30, chunk = 5)
      val (_, choose) = recoveringEngine(on, recovery)
      val head = above.last
      statusOf(choose(head)) shouldBe PayloadStatus.Syncing
      recovery.outcomes.last shouldBe a[StateRecovery.Outcome.Invalid]
      on.holds(blocks(29).header, blocks.take(30))
      // the head is ten blocks above the invalid one, in a chunk that was never read: it is known invalid all the same
      val chosen = choose(head).toOption.get.payloadStatus
      chosen.status shouldBe PayloadStatus.Invalid
      chosen.latestValidHash shouldBe Some(blocks(29).header.hash.value)
    }

  it should "go on when the consensus client names a head that stands on its tip, and one it has not sent the block of" taggedAs
    (UnitTest, ConsensusTest) in {
      Seq[(String, Node => ByteString)](
        (
          "a head on its tip",
          on =>
            on.writer.storeBlockByHashOnly(blocks(44)).commit(); blocks(44).header.hash.value
        ),
        ("a head whose header is not stored", _ => unknownHash(6))
      ).foreach { (what, later) =>
        withClue(s"$what: ") {
          val on = handedOff()
          val next = later(on)
          val moved = new java.util.concurrent.atomic.AtomicBoolean(false)
          val recovery = new on.Recovery(
            maxBlocks = 1,
            maxAdopted = 4,
            catchUp = 30,
            chunk = 5,
            adopting = block => if block.header.number.value == 27 then moved.set(true)
          )
          val named = new StateRecovery.Sink:
            def executed(
                block: Block,
                receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
                accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
            ): Unit = ()
            def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
            override def requestedHead: Option[ByteString] =
              Some(if moved.get() then next else blocks(43).header.hash.value)
          recovery
            .requestFor(blocks(43).header.hash.value, named, "a test", StateRecovery.Authority.ForkChoice)
            .outcome
            .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe StateRecovery.Outcome.Adopted(24)
          on.holds(blocks(43).header, blocks.take(44))
        }
      }
    }

  it should "begin from an executed block kept beside the disk, made the head first" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val on = new Node
    blocks.take(39).foreach(on.imports)
    val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 2, catchUp = 30, chunk = 3)
    val (newPayload, choose) = recoveringEngine(on, recovery)
    // block 40 is executed and kept as a layer; 41 to 44 are stored and never executed: more than a branch is adopted
    // over, and a straight extension of the head by way of the layer
    newPayload(blocks(39)).status shouldBe PayloadStatus.Valid
    on.pathState.isApplied(blocks(39).header.hash.value) shouldBe false
    blocks.slice(40, 44).foreach(block => on.writer.storeBlockByHashOnly(block).commit())
    statusOf(choose(blocks(43))) shouldBe PayloadStatus.Syncing
    recovery.outcomes.last shouldBe StateRecovery.Outcome.Adopted(4)
    recovery.adoptedBlocks shouldBe (41 to 44).map(BigInt(_))
    on.holds(blocks(43).header, blocks.take(44))
  }

  // the 64 stays the bound of a branch (the user's decision): only a straight extension is caught up over
  it should "not be taken for a branch that leaves the chain, however far a catch-up covers" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val on = new Node
    blocks.take(39).foreach(on.imports)
    // five blocks beside the chain, from block 38: stored, never executed, and longer than a branch is adopted over
    val first = blocks(38).pipe(b => b.copy(header = b.header.copy(extraData = ByteString("beside".getBytes))))
    val side = blocks.slice(39, 43).foldLeft(Vector(first)) { (made, block) =>
      made :+ block.copy(header = block.header.copy(parentHash = made.last.header.hash))
    }
    side.foreach(block => on.writer.storeBlockByHashOnly(block).commit())
    val recovery = new on.Recovery(maxBlocks = 1, maxAdopted = 2, catchUp = 30, chunk = 3)
    val (_, choose) = recoveringEngine(on, recovery)
    val before = on.everything
    statusOf(choose(side.last)) shouldBe PayloadStatus.Syncing
    recovery.outcomes.last match
      case StateRecovery.Outcome.TooLong(why) => why should include("no extension")
      case other                              => fail(s"not a head that cannot be reached: $other")
    recovery.adoptedBlocks shouldBe empty
    on.everything shouldBe before
  }

  it should "move nothing when it would reverse the finalized block" taggedAs (UnitTest, ConsensusTest) in {
    val on = tightNode
    val chain = blocks.take(41)
    chain.foreach(on.imports)
    val recovery = new on.Recovery(maxBlocks = 1)
    val (newPayload, choose) = recoveringEngine(on, recovery)
    // the consensus client followed 41 with 40 finalized
    val engine = on.engine(() => on.pathState.anchor.map(_.hash), recovery = Some(recovery))
    engine
      .forkchoiceUpdated(choiceTo(chain.last, chain(39).header.hash.value), None)
      .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      .map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
    // a branch from 39: 40' to 44', whose blocks are the chain's but for their extra data
    val side = sideFrom39
    side.take(4).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
    newPayload(side(4)).status shouldBe PayloadStatus.Accepted
    // the payload's own recovery is over
    recovery.outcomes
    val before = on.everything
    val asked = recovery.tickets.size
    // a fork choice that named it before, answered SYNCING, left it the head the import path follows
    on.node.forkChoiceManager.notifyBeaconHead(choiceTo(side(4)))
    // the fork choice to its tip, a head never executed, is not consistent with the finalized block: -38002, and no
    // adoption is asked for (part 4b, the user's decision)
    choose(side(4)).left.toOption.getOrElse("") should startWith("invalid forkchoice state")
    recovery.tickets.size shouldBe asked
    // which it is no longer (B-297): the import path follows the head followed last
    on.node.forkChoiceManager.getRequestedHeadBlockHash shouldBe Some(chain.last.header.hash.value)
    // asked all the same, the adoption refuses before anything moves
    val named = new StateRecovery.Sink:
      def executed(
          block: Block,
          receipts: Seq[com.chipprbots.ethereum.domain.Receipt],
          accessList: Option[com.chipprbots.ethereum.domain.BlockAccessList]
      ): Unit = ()
      def rejected(block: Block, error: BlockExecutionError, above: List[Block]): Unit = fail("rejected")
      override def requestedHead: Option[ByteString] = Some(side(4).header.hash.value)
    recovery
      .requestFor(side(4).header.hash.value, named, "a test", StateRecovery.Authority.ForkChoice)
      .outcome
      .get(60, java.util.concurrent.TimeUnit.SECONDS) shouldBe a[StateRecovery.Outcome.NotAdopted]
    recovery.adoptedBlocks shouldBe empty
    on.everything shouldBe before
    on.holds(chain.last.header, chain)
  }

  "A fork choice to a head never executed" should "be -38002 when its chain names another block below the finalized one, and not when its chain is not known there" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(41)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
      def choose(head: Block) = engine.forkchoiceUpdated(choiceTo(head), None).unsafeRunSync()
      engine
        .forkchoiceUpdated(choiceTo(chain.last, chain(39).header.hash.value), None)
        .unsafeRunSync()
        .map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      // a block at 39, below the finalized 40, beside the 39 the index names: stored and never executed
      val beside = siblingOf(chain(38))
      on.writer.storeBlockByHashOnly(beside).commit()
      choose(beside).left.toOption.getOrElse("") should startWith("invalid forkchoice state")
      // a block whose parent is not known: its chain says nothing of the finalized block
      val unknown = blocks(44).pipe(b =>
        b.copy(header = b.header.copy(parentHash = com.chipprbots.ethereum.domain.BlockHash(unknownHash(5))))
      )
      on.writer.storeBlockByHashOnly(unknown).commit()
      statusOf(choose(unknown)) shouldBe PayloadStatus.Syncing
      // with nothing named at 39 (a resync from an older checkpoint), the block beside it says nothing either
      on.dropIndex(39)
      statusOf(choose(beside)) shouldBe PayloadStatus.Syncing
    }

  "Whether a state is held" should "say so of the anchor, a block behind it, an executed block and one on it, a side block behind the anchor, and of nothing else" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(40)
      chain.foreach(on.imports)
      val engine = on.engine(() => on.pathState.anchor.map(_.hash))
      def newPayload(block: Block) =
        engine.newPayload(payloadOf(block)).unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      val side = siblingOf(chain.last)
      Seq(blocks(40), blocks(41), side).foreach(block => newPayload(block).status shouldBe PayloadStatus.Valid)
      Seq(chain.last, chain(37), chain(20), blocks(40), blocks(41), side).foreach { block =>
        withClue(s"block ${block.header.number.value}: ")(
          on.pathState.stateAvailable(refOf(block.header)) shouldBe true
        )
      }
      // a layer gone, and what stood on it; a block never executed; a block of no chain
      on.writer.dropForwardLayer(blocks(40).header.hash.value)
      on.pathState.stateAvailable(refOf(blocks(40).header)) shouldBe false
      on.pathState.stateAvailable(refOf(blocks(41).header)) shouldBe false
      on.pathState.stateAvailable(refOf(blocks(44).header)) shouldBe false
      on.pathState.stateAvailable(BlockRef(unknownHash(3), 40, unknownHash(4))) shouldBe false
      // asked under the state lock: refused, as it reads records
      an[IllegalStateException] should be thrownBy on.pathState.lock.locked("a test")(
        on.pathState.stateAvailable(refOf(chain.last.header))
      )
      // a state that failed closed holds nothing
      on.writer.failStateClosed("a test")
      on.pathState.stateAvailable(refOf(chain.last.header)) shouldBe false
    }

  // T156, in process: the node stops between newPayload(B) and forkchoiceUpdated(B), and starts again with no peers
  // on the same datadir. The layer of B went with it; B is executed again, and the consensus client reaches VALID.
  "A node started again between a payload and its fork choice" should "execute the payload again and follow the fork choice, with no sync" taggedAs
    (UnitTest, ConsensusTest) in {
      val first = new Node
      val chain = blocks.take(39)
      chain.foreach(first.imports)
      val (forty, fortyOne) = (blocks(39), blocks(40))
      first
        .engine(() => first.pathState.anchor.map(_.hash))
        .newPayload(payloadOf(forty))
        .unsafeRunSync()(using
          cats.effect.unsafe.IORuntime.global
        )
        .status shouldBe PayloadStatus.Valid
      // started again on the same datadir: what was on disk is as the first node left it
      val again = new Node(opened = Some(first.source))
      again.reconcile() shouldBe a[StateReconciliation.Outcome.Consistent]
      again.pathState.stateAvailable(refOf(forty.header)) shouldBe false
      val recovery = new again.Recovery(maxBlocks = 8)
      val (newPayload, choose) = recoveringEngine(again, recovery)
      // the fork choice to B: SYNCING, and B executed again; then VALID
      statusOf(choose(forty)) shouldBe PayloadStatus.Syncing
      recovery.outcomes shouldBe Seq(StateRecovery.Outcome.Recovered(1))
      statusOf(choose(forty)) shouldBe PayloadStatus.Valid
      again.holds(forty.header, chain :+ forty)
      // and the variant: the next payload comes first, ACCEPTED (neither VALID nor INVALID), and its fork choice VALID
      val third = new Node(opened = Some(again.source))
      val more = new third.Recovery(maxBlocks = 8)
      val (thirdPayload, thirdChoice) = recoveringEngine(third, more)
      newPayload(fortyOne).status shouldBe PayloadStatus.Valid
      third.pathState.stateAvailable(refOf(fortyOne.header)) shouldBe false
      thirdPayload(blocks(41)).status shouldBe PayloadStatus.Accepted
      more.outcomes shouldBe Seq(StateRecovery.Outcome.Recovered(2))
      statusOf(thirdChoice(blocks(41))) shouldBe PayloadStatus.Valid
      third.holds(blocks(41).header, chain :+ forty :+ fortyOne :+ blocks(41))
    }

  // C4d part 1 (T160, T171): the finalized block a fork choice names, kept with the move that followed it.
  "The finalized block" should "be the fork choice's once its head is followed, kept across a restart, and held to by a move" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      on.pathState.finalizedBlock shouldBe None
      // a fork choice refused names nothing: the next block committed in place since the look at the head
      on.imports(blocks(4))
      val withFinality =
        ForkChoiceState(chain.last.header.hash.value, chain(1).header.hash.value, chain(1).header.hash.value)
      on.node.forkChoiceManager.applyForkChoiceState(withFinality) shouldBe Left(ForkChoiceManager.Refusal.Syncing)
      on.pathState.finalizedBlock shouldBe None
      on.storages.appStateStorage.getStateFinalized() shouldBe None
      // one followed names it, in the batch that confirmed the head
      val head = blocks(4)
      val followed = ForkChoiceState(head.header.hash.value, chain(1).header.hash.value, chain(1).header.hash.value)
      on.node.forkChoiceManager.applyForkChoiceState(followed) shouldBe Right(())
      val named = FinalizedBlock(chain(1).header.number.value, chain(1).header.hash.value)
      on.pathState.finalizedBlock shouldBe Some(named)
      on.storages.appStateStorage.getStateFinalized() shouldBe Some(named)
      on.source.offTheLock.toArray.toSeq shouldBe empty
      // the zero hash names none, and leaves it
      on.node.forkChoiceManager.applyForkChoiceState(
        ForkChoiceState(head.header.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash)
      ) shouldBe Right(())
      on.pathState.finalizedBlock shouldBe Some(named)
      // one below it is taken, as go-ethereum takes it (logged): on the held state's chain it only loosens what is kept
      val lower = ForkChoiceState(head.header.hash.value, chain.head.header.hash.value, chain.head.header.hash.value)
      on.node.forkChoiceManager.applyForkChoiceState(lower) shouldBe Right(())
      on.pathState.finalizedBlock shouldBe Some(FinalizedBlock(1, chain.head.header.hash.value))
      on.node.forkChoiceManager.applyForkChoiceState(followed) shouldBe Right(())
      on.pathState.finalizedBlock shouldBe Some(named)
      // a store opened again on the datadir holds it, and a move does not reverse it
      val reopened = new PathStateStorage(new PathNodeStorage(on.source), on.storages.appStateStorage)
      reopened.finalizedBlock shouldBe Some(named)
      // a row it cannot read is no absent block: a store is not opened over it
      val unreadable = new Failing
      unreadable.update(
        Seq(
          DataSourceUpdate(
            Namespaces.AppStateNamespace,
            Nil,
            Seq(AppStateStorage.Keys.StateFinalized.getBytes.toIndexedSeq -> "v1:not-a-block".getBytes.toIndexedSeq)
          )
        )
      )
      an[IllegalStateException] should be thrownBy new PathStateStorage(
        new PathNodeStorage(unreadable),
        new AppStateStorage(unreadable)
      )
      val before = on.everything
      (the[ReorgTooDeep] thrownBy on.move(chain.head.header)).reason shouldBe ReorgRefusal.Finalized
      on.everything shouldBe before
      // and above it a move goes as before
      on.move(chain(1).header) shouldBe a[PathStateStorage.MoveOutcome.Moved]
      on.holds(chain(1).header, chain.take(2))
    }

  // C4b part 4 (T150): the reconciliation at start. The block whose state is held wins, always (vault, forge): the
  // best block and the index are made its own, and nothing else is written.
  private def unknownHash(n: Int): ByteString = ByteString(Array.fill[Byte](32)(n.toByte))

  "The reconciliation at start" should "make the best block and the index the held block's when the best block is above it, and the chain go on" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      on.move(chain(1).header)
      // a best block left above the state, with the index up to it and the lookups of its blocks' transactions
      chain.drop(2).foreach(b => on.putIndex(b.header.number.value, b.header.hash.value))
      val above = chain.drop(2).flatMap { b =>
        b.body.transactionList.zipWithIndex.map { (tx, i) =>
          on.storages.transactionMappingStorage
            .put(
              tx.hash.value,
              com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation(b.header.hash.value, i)
            )
            .commit()
          tx.hash.value
        }
      }
      above should not be empty
      on.putBest(chain.last.header.hash.value, chain.last.header.number.value)
      val before = on.outsideTheHead
      // what was staged above was written off the lock on purpose: the watch is of the reconciliation's own writes
      on.source.offTheLock.clear()
      on.reconcile() match
        case StateReconciliation.Outcome.Reconciled(anchor, from, _, removed, _) =>
          (anchor, from.number, removed) shouldBe (refOf(chain(1).header), chain.last.header.number.value, 3)
        case other => fail(s"not reconciled: $other")
      on.holds(chain(1).header, chain.take(2))
      on.outsideTheHead shouldBe before
      on.source.offTheLock.toArray.toSeq shouldBe empty
      // a transaction of a block above the state is looked up in no block
      above.foreach(tx => on.lookup(tx) shouldBe None)
      // once: a second start writes nothing
      val writes = on.source.writes.get
      on.reconcile() shouldBe StateReconciliation.Outcome.Consistent(refOf(chain(1).header))
      on.source.writes.get shouldBe writes
      // and the blocks above come back as blocks to execute, not as blocks already there
      chain.drop(2).foreach(on.imports)
      on.holds(chain.last.header, chain)
      on.node.forkChoiceManager.applyForkChoiceState(choiceOf(chain.last, chain)) shouldBe Right(())
    }

  it should "make them the held block's whatever the best block was: below it, on another branch, with no hash, unknown" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = blocks.take(4)
      val shapes: Seq[(String, Node => Unit)] = Seq(
        "below" -> { on =>
          on.putBest(chain(1).header.hash.value, 2)
          Seq(3, 4).foreach(n => on.dropIndex(n))
        },
        "on another branch" -> { on =>
          val side = siblingOf(chain(2))
          on.storages.blockHeadersStorage.put(side.header.hash.value, side.header).commit()
          on.putIndex(3, side.header.hash.value)
          on.dropIndex(4)
          on.putBest(side.header.hash.value, 3)
        },
        "with no hash" -> (_.putBest(ByteString.empty, 7)),
        "unknown" -> { on =>
          on.putIndex(9, unknownHash(9))
          on.putBest(unknownHash(9), 9)
        }
      )
      shapes.foreach { (shape, stage) =>
        withClue(s"a best block $shape: ") {
          val on = new Node
          chain.foreach(on.imports)
          stage(on)
          val before = on.outsideTheHead
          on.reconcile() shouldBe a[StateReconciliation.Outcome.Reconciled]
          on.holds(chain.last.header, chain)
          on.outsideTheHead shouldBe before
          on.reconcile() shouldBe StateReconciliation.Outcome.Consistent(refOf(chain.last.header))
        }
      }
    }

  // I6: the walk does not stop at the first entry that matches, and entries above a gap are found
  it should "hold the index to the held block's ancestry with the best block in place: a side entry under matching ones, a hole, an entry above a gap" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      // a side block at height 2 that holds the transactions the chain includes at height 3 (beacon)
      val side = siblingOf(chain(1)).copy(body = chain(2).body)
      on.storages.blockHeadersStorage.put(side.header.hash.value, side.header).commit()
      on.storages.blockBodiesStorage.put(side.header.hash.value, side.body).commit()
      on.putIndex(2, side.header.hash.value)
      on.dropIndex(4)
      on.putIndex(8, unknownHash(8))
      val before = on.outsideTheHead
      on.reconcile() match
        case StateReconciliation.Outcome.Reconciled(_, from, rewritten, removed, _) =>
          (from.number, rewritten, removed) shouldBe (chain.last.header.number.value, 2, 1)
        case other => fail(s"not reconciled: $other")
      on.holds(chain.last.header, chain)
      on.outsideTheHead shouldBe before
      // a transaction of the side block that the chain includes higher up is looked up in the block that includes it
      val shared = on.txsOf(chain(2))
      shared should not be empty
      shared.foreach(tx => on.lookup(tx) shouldBe Some(chain(2).header.hash.value))
    }

  it should "leave what lies past its window and its cap, as it says" taggedAs (UnitTest, ConsensusTest) in {
    val on = new Node
    val chain = blocks.take(5)
    chain.foreach(on.imports)
    val side = siblingOf(chain(0))
    on.storages.blockHeadersStorage.put(side.header.hash.value, side.header).commit()
    on.putIndex(1, side.header.hash.value) // four blocks below the held one: past a window of two
    on.putIndex(6, unknownHash(6)) // the first height above: within a cap of two
    on.putIndex(8, unknownHash(8)) // three above: past it
    StateReconciliation.run(
      on.storages.appStateStorage,
      on.pathState,
      on.reader,
      on.writer,
      testMode = false,
      window = 2,
      scanAbove = 2
    ) match
      case StateReconciliation.Outcome.Reconciled(_, _, rewritten, removed, _) => (rewritten, removed) shouldBe (0, 1)
      case other                                                               => fail(s"not reconciled: $other")
    on.reader.getCanonicalHashByNumber(1).map(_.value) shouldBe Some(side.header.hash.value)
    on.reader.getCanonicalHashByNumber(6) shouldBe None
    on.reader.getCanonicalHashByNumber(8).map(_.value) shouldBe Some(unknownHash(8))
  }

  // Beacon, part 4's read: after the head is lowered at start, the consensus layer's head is above it. Its fork choice is
  // answered SYNCING with nothing written, and its head is recorded as the one asked for.
  it should "leave the Engine API following the consensus layer's head above it, along the records kept" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      on.move(chain(1).header)
      chain.drop(2).foreach(b => on.putIndex(b.header.number.value, b.header.hash.value))
      on.putBest(chain.last.header.hash.value, chain.last.header.number.value)
      on.reconcile() shouldBe a[StateReconciliation.Outcome.Reconciled]
      // what was staged above was written off the lock on purpose: the watch is of what follows
      on.source.offTheLock.clear()
      val answer = on
        .engine(() => on.pathState.anchor.map(_.hash))
        .forkchoiceUpdated(choiceOf(chain.last, chain), None)
        .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      // the blocks above the head it was lowered to kept their records: the fork choice moves the head back up along
      // them (C4d part 2b)
      answer.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      on.node.forkChoiceManager.getRequestedHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.holds(chain.last.header, chain)
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  it should "refuse what it cannot mend, writing nothing, and leave test mode's reset residue to the next reset" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = blocks.take(3)
      def staged(stage: Node => Unit): Node =
        val on = new Node
        chain.foreach(on.imports)
        stage(on)
        on
      // the held block's header gone: refused; in test mode, a reset that stopped between its two writes
      val noHeader = staged(on => on.storages.blockHeadersStorage.remove(chain.last.header.hash.value).commit())
      val writes = noHeader.source.writes.get
      val everything = noHeader.everything
      (the[IllegalStateException] thrownBy noHeader.reconcile()).getMessage should include("is not stored")
      noHeader.reconcile(testMode = true) shouldBe
        StateReconciliation.Outcome.LeftForTheReset(refOf(chain.last.header))
      noHeader.source.writes.get shouldBe writes
      noHeader.everything shouldBe everything
      // the anchor's row naming another state root than its header
      val contradicted = staged(on =>
        on.putAppState(
          com.chipprbots.ethereum.db.storage.pathstate.StateAnchor.Key,
          com.chipprbots.ethereum.db.storage.pathstate.StateAnchor
            .encode(refOf(chain.last.header).copy(stateRoot = unknownHash(1)))
        )
      )
      val beforeContradicted = contradicted.everything
      (the[IllegalStateException] thrownBy contradicted.reconcile()).getMessage should include("its header says")
      contradicted.everything shouldBe beforeContradicted
      // the held block's weight gone
      val noWeight = staged(on => on.storages.chainWeightStorage.remove(chain.last.header.hash.value).commit())
      (the[IllegalStateException] thrownBy noWeight.reconcile()).getMessage should include("chain weight")
      // no anchor: nothing to do, unless the datadir says it was synced
      val noAnchor = staged(on => on.dropAppState(com.chipprbots.ethereum.db.storage.pathstate.StateAnchor.Key))
      noAnchor.reconcile() shouldBe StateReconciliation.Outcome.NoAnchor
      noAnchor.storages.appStateStorage.snapSyncDone().commit()
      (the[IllegalStateException] thrownBy noAnchor.reconcile()).getMessage should include("SNAP")
      // no stamp: not this layout's datadir, the loader's to judge
      val noStamp = staged(on => on.dropAppState(com.chipprbots.ethereum.db.storage.pathstate.StateLayout.Key))
      noStamp.reconcile() shouldBe StateReconciliation.Outcome.NotOnTheUnifiedLayout
    }

  it should "write nothing when its batch fails, and mend the datadir at the next start" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      on.putBest(chain(1).header.hash.value, 2)
      val before = on.everything
      on.source.fails = true
      an[java.io.IOException] should be thrownBy on.reconcile()
      on.source.fails = false
      on.everything shouldBe before
      on.reconcile() shouldBe a[StateReconciliation.Outcome.Reconciled]
      on.holds(chain.last.header, chain)
    }

  // Beacon 5b: behind the source ratchet and the refusals, the writes themselves. Every flow that is reached on the
  // unified layout writes the best block and the index under the state lock, or not at all.
  "Every write of the head on the unified layout" should "be made under the state lock: commits, moves, the fork choice" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.take(4).foreach(on.imports)
      val choices = on.node.forkChoiceManager
      choices.applyForkChoiceState(choiceOf(chain(3), chain)) shouldBe Right(())
      on.move(chain(1).header)
      val competing = siblingOf(chain(2))
      on.imports(competing)
      // across the fork, along the competing block's record and the old chain's records (C4d part 2b)
      choices.applyForkChoiceState(choiceOf(chain(3), chain)) shouldBe Right(())
      on.move(chain(3).header)
      on.imports(chain(4))
      choices.applyForkChoiceState(choiceOf(chain(4), chain)) shouldBe Right(())
      on.holds(chain(4).header, chain)
      on.source.offTheLock.toArray.toSeq shouldBe empty

      // and the watch sees a write that is not: the best block's rows written alone
      val appState = on.node.storagesInstance.storages.appStateStorage
      appState.putBestBlockInfo(appState.getBestBlockInfo()).commit()
      on.source.offTheLock.toArray.toSeq.toSet shouldBe bestBlockKeys
    }

  "A batch that moves the head" should "not be written over a best block that another writer moved alone" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.take(4).foreach(on.imports)
      // a writer that moves the best block without the state (a defect the guards above are there to stop)
      val appState = on.node.storagesInstance.storages.appStateStorage
      appState
        .putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(chain(1).header.hash.value, 2))
        .commit()
      val before = on.everything
      // neither a block's commit nor a move is written over it
      (the[IllegalStateException] thrownBy on.imports(chain(4))).getMessage should
        include("is not the block the state is held at")
      // (contract code is kept by its hash as a block is executed, outside its batch, on either layout: nothing of the
      // head is in it)
      val code = com.chipprbots.ethereum.db.storage.Namespaces.CodeNamespace.head
      val after = on.everything
      val changed = (after.keySet ++ before.keySet).filter(key => after.get(key) != before.get(key))
      changed.filterNot(_.head == code) shouldBe empty
      val beforeMove = on.everything
      (the[IllegalStateException] thrownBy on.move(chain(1).header)).getMessage should
        include("is not the block the state is held at")
      on.everything shouldBe beforeMove
      on.pathState.moveInProgress shouldBe false
      // nor the caller's rows for the head as it is: a move to the block the state is at
      (the[IllegalStateException] thrownBy on.move(on.headerOf(on.pathState.anchor.get))).getMessage should
        include("is not the block the state is held at")
      on.everything shouldBe beforeMove
      // the best block's own hash with another number is no less a best block moved alone
      val anchor = on.pathState.anchor.get
      appState
        .putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(anchor.hash, anchor.number + 1))
        .commit()
      val beforeAgain = on.everything
      an[IllegalStateException] should be thrownBy on.move(chain(1).header)
      on.everything shouldBe beforeAgain
    }

  "A move in the node" should "not be written without the rows that move the best block with the state" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      val before = on.everything
      (the[IllegalStateException] thrownBy on.pathState.moveHead(
        refOf(chain(1).header),
        on.link,
        _ => None
      )).getMessage should
        include("without the rows that move the best block")
      on.everything shouldBe before
      on.holds(chain.last.header, chain)
    }

  "The link a move is planned from" should "be the header stored under a hash, and never a row of the index of blocks by number" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(5)
      chain.foreach(on.imports)
      val storages = on.node.storagesInstance.storages
      // a reader whose index of blocks by number throws at every read
      val numbers = new com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage(on.source):
        override def get(key: BigInt): Option[com.chipprbots.ethereum.db.storage.BlockHeadersStorage.BlockHeaderHash] =
          throw new IllegalStateException(s"the index of blocks by number was read, at $key")
      val byHashOnly = new com.chipprbots.ethereum.domain.BlockchainReader(
        storages.blockHeadersStorage,
        storages.blockBodiesStorage,
        numbers,
        storages.stateStorage,
        storages.receiptStorage,
        storages.appStateStorage,
        storages.chainWeightStorage,
        storages.blockAccessListStorage,
        storages.pathStateStorage
      )
      val head = chain.last.header
      byHashOnly.headerLink(head.hash.value) shouldBe Some(HeadMovePlan.Link(refOf(head), head.parentHash.value))
      byHashOnly.headerLink(ByteString(new Array[Byte](32))) shouldBe None
      byHashOnly.ancestorAt(head, 2).map(_.hash) shouldBe Some(chain(1).header.hash)
      byHashOnly.ancestorAt(head, head.number.value).map(_.hash) shouldBe Some(head.hash)
      byHashOnly.ancestorAt(chain(1).header, 4) shouldBe None
      // a move down planned and made by that link alone
      on.pathState.moveHead(
        refOf(chain(1).header),
        byHashOnly.headerLink,
        c => Some(on.writer.canonicalHeadUpdate(on.headerOf(c.head), on.reader).batch)
      )
      on.holds(chain(1).header, chain.take(2))

      // a header stored under a hash that is not its own is no link, and no ancestor is found through one
      val headers = storages.blockHeadersStorage
      val elsewhere = ByteString(Array.fill[Byte](32)(7))
      headers.put(elsewhere, chain(1).header).commit()
      byHashOnly.headerLink(elsewhere) shouldBe None
      headers.put(chain(1).header.hash.value, chain(2).header).commit()
      byHashOnly.ancestorAt(head, 1) shouldBe None
    }

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
import com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
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
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      if fails then throw new java.io.IOException("the disk is full")
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

  /** A node on the unified layout with its genesis committed. */
  final private class Node:
    val source: Failing = new Failing
    private trait Own extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    val node: RpcCompatNode =
      reference
      new RpcCompatNode:
        override lazy val storagesInstance
            : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
          new Own with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
            override protected def statePathLive: Boolean = true
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
        choices: com.chipprbots.ethereum.consensus.engine.ForkChoiceManager = node.forkChoiceManager
    ): EngineApiService =
      given org.apache.pekko.actor.typed.Scheduler = system.scheduler
      new EngineApiService(reader, writer, execution, choices, None, stateHeldInPlace = Some(held))

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
          on.writer.rewriteCanonicalIndex(Nil, Seq(chain.last.header.number.value), None, on.reader)
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
      val before = on.everything
      choices.applyForkChoiceState(choiceOf(chain.last, chain)) shouldBe Right(())
      choices.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.everything shouldBe before
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
      choices.applyForkChoiceState(looked) shouldBe Left("SYNCING")
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
            override def applyForkChoiceState(newState: ForkChoiceState): Either[String, Unit] =
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
      val before = on.everything
      val answer = on
        .engine(() => on.pathState.anchor.map(_.hash))
        .forkchoiceUpdated(choiceOf(chain.last, chain), None)
        .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global)
      answer.map(_.payloadStatus.status) shouldBe Right(PayloadStatus.Valid)
      on.node.forkChoiceManager.getHeadBlockHash shouldBe Some(chain.last.header.hash.value)
      on.everything shouldBe before
      on.source.offTheLock.toArray.toSeq shouldBe empty
    }

  "The fork choice on the unified layout" should "move the head in no direction off the block the state is at, before C4d" taggedAs
    (UnitTest, ConsensusTest) in {
      val on = new Node
      val chain = blocks.take(4)
      chain.foreach(on.imports)
      on.move(chain(1).header)
      val choices = on.node.forkChoiceManager
      val before = on.everything
      // up the chain it left, whose records are kept; and down to the first block
      Seq(chain.last, chain(2), chain.head).foreach { head =>
        withClue(s"block ${head.header.number.value}: ") {
          choices.applyForkChoiceState(choiceOf(head, chain)) shouldBe Left("SYNCING")
          on.everything shouldBe before
        }
      }
      choices.getHeadBlockHash shouldBe None
      on.holds(chain(1).header, chain.take(2))
      on.pathState.moveInProgress shouldBe false
    }

  it should "throw a record that is not there, and not answer it as a head it has not got" taggedAs
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
      (the[ReorgTooDeep] thrownBy on.node.forkChoiceManager.applyForkChoiceState(
        choiceOf(chain(2), chain)
      )).reason shouldBe
        ReorgRefusal.Corrupt
      on.everything shouldBe before
      on.node.forkChoiceManager.getHeadBlockHash shouldBe None
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
      choices.applyForkChoiceState(choiceOf(chain(3), chain)) shouldBe Left("SYNCING")
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

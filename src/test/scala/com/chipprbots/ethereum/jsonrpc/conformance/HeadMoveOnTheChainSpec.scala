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
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
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
    */
  final private class Failing extends EphemDataSource(Map.empty):
    @volatile var fails = false
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      if fails then throw new java.io.IOException("the disk is full")
      super.update(dataSourceUpdates)

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

    private val execution = new HeaderTrustingBlockExecution(
      node.blockchain,
      node.blockchainReader,
      node.blockchainWriter,
      node.storagesInstance.storages.evmCodeStorage,
      node.mining.blockPreparator,
      new BlockValidation(node.mining, node.blockchainReader, node.blockQueue)
    )

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

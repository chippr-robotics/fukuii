package com.chipprbots.ethereum.blockchain.sync.regular
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.FishingOutcome
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime
import cats.syntax.traverse.*

import scala.compiletime.uninitialized
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.*
import scala.math.BigInt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.Assertion
import org.scalatest.diagrams.Diagrams
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.ResourceFixtures
import com.chipprbots.ethereum.WordSpecBase
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchStateNode
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.InvalidateBlocksFrom
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
import com.chipprbots.ethereum.jsonrpc.NewBlockImported
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockBodies as ETHGetBlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.BlockHash
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewBlockHashes.NewBlockHashes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

class RegularSyncSpec
    extends WordSpecBase
    with ResourceFixtures
    with Matchers
    with AsyncMockFactory
    with Diagrams
    with RegularSyncFixtures:
  type Fixture = RegularSyncFixture

  // This spec is an AsyncWordSpec built on the cats-effect Resource[IO, ...] machinery, which is
  // incompatible with the synchronous ScalaTestWithActorTestKit base trait (it breaks async test
  // discovery). Each fixture now owns a per-test ActorTestKit (RegularSyncFixture.testKit) instead of
  // the former hand-rolled ActorSystem() + TestKit.shutdownActorSystem lifecycle. Per-test isolation
  // is preserved: each test gets a fresh testKit-owned Classic system, shut down via fixture.shutdownFixture().

  // fixtureResource builds a fresh fixture and shuts down its testKit on release.
  val fixtureResource: Resource[IO, Fixture] =
    Resource.make(IO(new Fixture))(fixture => IO(fixture.shutdownFixture()))

  // OnTop variant resource with the same teardown (used by the "return SyncDone when on top" test).
  val onTopFixtureResource: Resource[IO, OnTopFixture] =
    Resource.make(IO(new OnTopFixture))(fixture => IO(fixture.shutdownFixture()))

  /** An importer on the unified state layout that has started a fork recovery for block 15, with the head at block 20
    * and the common block at 10: what it does next is the resolver's answer, which the test gives it.
    */
  abstract class ForkRecoveryOnTheUnifiedLayout(
      name: String,
      refusal: Option[ReorgTooDeep] = None,
      // false: the recovery is left to the importer, which starts one when an import has failed often enough
      recoveryStarted: Boolean = true,
      // false: the same importer on the hash layout
      unified: Boolean = true,
      // the block whose import started the recovery
      failedAt: BigInt = BigInt(15),
      // how many blocks the fetcher holds ready, where not the default (B-300 g)
      readyBlocks: Option[Int] = None,
      // false: this node has no header at the common block
      lcaHeaderStored: Boolean = true
  ) extends Fixture:
    // the branches told to the escalation as out of reach, by an adapter that records them, and who held the import
    // lock as each was (B-300 g)
    val outOfReach: java.util.concurrent.ConcurrentLinkedQueue[
      (List[BlockHeader], StateFault, Option[String])
    ] = new java.util.concurrent.ConcurrentLinkedQueue()

    override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
      blockHeadersPerRequest = 2,
      blockBodiesPerRequest = 2,
      blockFetcherTickInterval = 60.seconds,
      peerResponseTimeout = 1.second,
      maxReadyBlocksQueueSize = readyBlocks.getOrElse(defaultSyncConfig.maxReadyBlocksQueueSize)
    )

    val capturedBest: BigInt = testBlocks.last.number.value
    val lca: BigInt = BigInt(10)
    val lcaHeader: BlockHeader = testBlocks.find(_.number.value == lca).get.header

    override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
    (() => blockchainReader.getBestBlockNumber).when().returns(capturedBest)
    (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)
    (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(lca).returns(Option.when(lcaHeaderStored)(lcaHeader))
    (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(BigInt(0)).returns(Some(BlockHelpers.genesis.header))
    testBlocks.filter(b => b.number.value > lca).foreach { b =>
      (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(b.number.value).returns(Some(b.hash))
    }
    val abandoned: java.util.concurrent.ConcurrentLinkedQueue[(BigInt, ByteString)] =
      new java.util.concurrent.ConcurrentLinkedQueue()
    private val recordingReorgState = new com.chipprbots.ethereum.consensus.ReorgStateHandler:
      override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = blocks.foreach(abandoned.add(_))
      override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = ()

    override lazy val blockchainWriter: BlockchainWriter = stub[BlockchainWriter]
    (() => blockchainWriter.holdsStateInPlace).when().returns(unified)
    (blockchainWriter.refusalOfMoveBackTo(_: BlockHeader, _: BlockchainReader)).when(*, *).returns(refusal)

    val importerFetcher: TestProbe = TestProbe(s"$name-fork-fetcher")
    val importerSupervisor: TestProbe = TestProbe(s"$name-fork-supervisor")
    private val importerBroadcaster: TestProbe = TestProbe(s"$name-fork-broadcaster")
    private val forkBlockTopic = testKit.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](s"$name-fork-topic"),
      s"$name-fork-topic"
    )
    val importer: TypedActorRef[BlockImporter.Command] = testKit.spawn(
      BlockImporter.apply(
        importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
        consensusAdapter,
        blockchainReader,
        blockchainWriter,
        stateStorage,
        evmCodeStorage,
        branchResolution,
        syncConfig,
        ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
        importerBroadcaster.ref,
        pendingTransactionsManager.ref.toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
        forkBlockTopic,
        importerSupervisor.ref,
        peerEventBus.ref,
        networkPeerManager.ref,
        blockchain,
        blacklist,
        this,
        None,
        recordingReorgState,
        exhausts = exhausts,
        importLock = importLock
      ),
      s"$name-fork-recovery-importer"
    )
    importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
    importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])
    if recoveryStarted then importer ! BlockImporter.StartForkRecovery(failedAt)

    /** What the fetcher, and the supervisor, were sent that no assertion has taken yet: until neither is sent more. */
    def toTheFetcher(): Seq[Any] = importerFetcher.receiveWhile(max = 2.seconds, idle = 300.millis) { case m => m }
    def toTheSupervisor(): Seq[Any] = importerSupervisor.receiveWhile(max = 2.seconds, idle = 300.millis) { case m =>
      m
    }

    /** Neither the index, nor the best block, nor a block's reference counts. */
    def nothingMoved(): Unit =
      (blockchainWriter
        .setCanonicalChainHead(_: BigInt, _: com.chipprbots.ethereum.domain.BlockHash, _: BigInt))
        .verify(*, *, *)
        .never()
      abandoned.isEmpty shouldBe true

    /** An adapter that records the branches told to it as out of reach, and imports nothing. */
    def recordingOutOfReach(): ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      override def branchOutOfReach(
          headers: List[BlockHeader],
          fault: StateFault
      )(implicit blockchainConfig: BlockchainConfig): Unit =
        outOfReach.add((headers, fault, importLock.heldBy)): Unit

    /** `count` headers of a side branch that leaves this node's chain at `from`, of more difficulty than its blocks. */
    def sideBranch(from: BlockHeader, count: Int): Vector[BlockHeader] =
      (1 to count).foldLeft(Vector.empty[BlockHeader]) { (made, i) =>
        val parent = made.lastOption.getOrElse(from)
        made :+ from.copy(
          parentHash = parent.hash,
          number = BlockNumber(from.number.value + i),
          difficulty = Difficulty(parent.difficulty.value * 2),
          extraData = ByteString(s"of the side branch $i")
        )
      }

    /** The next request for headers sent to a peer: the request, the peer, and where its answer is to go. */
    def headersAsked(): (ETHGetBlockHeaders, PeerId, TypedActorRef[PeerEvent]) =
      val (request, peer) = networkPeerManager.fishForSpecificMessage(5.seconds) {
        case NetworkPeerManagerActor.SendMessageCmd(message, peer)
            if message.underlyingMsg.isInstanceOf[ETHGetBlockHeaders] =>
          (message.underlyingMsg.asInstanceOf[ETHGetBlockHeaders], peer)
      }
      val answerTo = peerEventBus.fishForSpecificMessage(5.seconds) {
        case SubscribeCmd(MessageClassifier(codes, _), subscriber) if codes.contains(Codes.BlockHeadersCode) =>
          subscriber
      }
      (request, peer, answerTo)

    /** Every request for headers answered from `branch`, which stands on block `from`, until one is answered short. */
    def answerFrom(branch: Vector[BlockHeader], from: BigInt): Seq[ETHGetBlockHeaders] =
      @scala.annotation.tailrec
      def go(asked: Vector[ETHGetBlockHeaders]): Vector[ETHGetBlockHeaders] =
        val (request, peer, answerTo) = headersAsked()
        val first = request.block.left.toOption.get
        val answer = branch.slice((first - from - 1).toInt, (first - from - 1 + request.maxHeaders).toInt)
        answerTo ! MessageFromPeer(BlockHeaders(request.requestId, answer), peer)
        if answer.size < request.maxHeaders then asked :+ request else go(asked :+ request)
      go(Vector.empty)

    /** The branches told to the escalation, once `count` have been: until then, waited for. */
    def toldOutOfReach(
        count: Int
    ): List[(List[BlockHeader], StateFault, Option[String])] =
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
      while outOfReach.size < count && System.nanoTime() < deadline do Thread.onSpinWait()
      import scala.jdk.CollectionConverters.*
      outOfReach.asScala.toList

  /** An importer on the unified state layout whose every import of block 15 fails as an invalid block: the importer
    * starts the fork recovery itself (B-300).
    */
  abstract class ImportThatKeepsFailingOnTheUnifiedLayout(name: String)
      extends ForkRecoveryOnTheUnifiedLayout(name, recoveryStarted = false):
    // a block of another chain at height 15: one of this node's own is dropped from a batch before it is imported
    val failing: Block =
      val own = testBlocks.find(_.number.value == 15).get
      own.copy(header = own.header.copy(extraData = ByteString("of another chain")))
    // what the importer says of its own block at that height when it detects the fork
    (blockchainReader
      .getBlockHeaderByNumber(_: BigInt))
      .when(BigInt(15))
      .returns(testBlocks.find(_.number.value == 15).map(_.header))
    val imports = new java.util.concurrent.atomic.AtomicInteger(0)
    val importsBegun = new java.util.concurrent.atomic.AtomicInteger(0)
    // held while a test looks at the importer in the middle of an import
    @volatile var holdTheImport: Option[java.util.concurrent.CountDownLatch] = None

    override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      // a proof-of-work chain: no consensus layer refuses a branch (B-297)
      override def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean = false
      override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] =
        IO.blocking {
          importsBegun.incrementAndGet()
          holdTheImport.foreach(_.await(30, java.util.concurrent.TimeUnit.SECONDS))
          imports.incrementAndGet()
          BlockImportFailed("invalid")
        }

    override lazy val branchResolution: BranchResolution = new BranchResolution(blockchainReader):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
        NewBetterBranch(Nil)

    /** One more batch that is block 15 alone, and the import of it ended. */
    def failOnce(): Unit =
      val before = imports.get()
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(failing)))
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
      while imports.get() <= before && System.nanoTime() < deadline do Thread.onSpinWait()
      imports.get() shouldBe before + 1

  /** What the importer's on-demand fetch writes through, on the unified layout (spec 018 C5b): it records every call
    * and answers `outcome`. The rules that make a write safe (state lock, anchor compare-and-set, the reach of the
    * anchor's root) are pinned on the real store by RepairNodeAtSpec and DeleteBadNodeAtSpec.
    */
  final class RecordingPositionRepairs(outcome: () => RepairOutcome)
      extends com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs:
    import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
    val repaired = new java.util.concurrent.ConcurrentLinkedQueue[(PositionKey, ByteString, ByteString)]()
    val deleted = new java.util.concurrent.ConcurrentLinkedQueue[(PositionKey, ByteString)]()
    override def anchor: Option[com.chipprbots.ethereum.db.storage.pathstate.BlockRef] = None
    override def rawRowsAt(
        requests: Seq[(PositionKey, ByteString)]
    ): Seq[com.chipprbots.ethereum.db.storage.RawNodeRead] = Nil
    override def repairNodeAt(position: PositionKey, expectedHash: ByteString, bytes: Array[Byte]): RepairOutcome =
      repaired.add((position, expectedHash, ByteString(bytes)))
      outcome()
    override def deleteBadNodeAt(
        position: PositionKey,
        expectedHash: ByteString
    ): com.chipprbots.ethereum.db.storage.BadEntryDelete =
      deleted.add((position, expectedHash))
      com.chipprbots.ethereum.db.storage.BadEntryDelete.NoLongerBad

  /** An importer spawned alone, with probes for whom it tells of an import (B-448): new-head subscribers, the pool and
    * the broadcaster to peers. Every batch it is handed is answered `answer`; this node's chain is the genesis block.
    */
  abstract class BatchImportTells(
      name: String,
      followsConsensusLayer: Boolean = false,
      reporting: Boolean = false
  ) extends Fixture:
    val answer: java.util.concurrent.atomic.AtomicReference[BlockImportResult] =
      new java.util.concurrent.atomic.AtomicReference(BlockEnqueued)
    // what happens while a batch is evaluated, after its blocks are committed and before the importer tells of them
    val duringImport: java.util.concurrent.atomic.AtomicReference[() => Unit] =
      new java.util.concurrent.atomic.AtomicReference(() => ())
    // the head the pool was last reset to, as the node shares it with the fork choice (B-515)
    val txPoolHead: com.chipprbots.ethereum.transactions.TxPoolHead =
      new com.chipprbots.ethereum.transactions.TxPoolHead
    // what the importer reported to the consensus layer as invalid, with each block's latest valid hash
    val reportedInvalid: java.util.concurrent.ConcurrentLinkedQueue[(ByteString, ByteString)] =
      new java.util.concurrent.ConcurrentLinkedQueue()
    val newHeads: TypedTestProbe[Topic.Command[NewBlockImported]] =
      testKit.createTestProbe[Topic.Command[NewBlockImported]](s"$name-new-heads")
    val pool: TypedTestProbe[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command] =
      testKit.createTestProbe[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command](s"$name-pool")
    val toPeers: TypedTestProbe[BlockBroadcasterActor.BroadcasterMsg] =
      testKit.createTestProbe[BlockBroadcasterActor.BroadcasterMsg](s"$name-broadcaster")
    val importerFetcher: TestProbe = TestProbe(s"$name-fetcher")
    val importerSupervisor: TestProbe = TestProbe(s"$name-supervisor")

    override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      // a proof-of-work chain: no consensus layer refuses a branch (B-297)
      override def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean = false
      override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] = IO { evaluations.incrementAndGet(); duringImport.get()(); answer.get() }
      // an announced block is answered the same
      override def evaluateBranchBlock(block: Block)(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] = IO { evaluations.incrementAndGet(); answer.get() }
      override def reportsInvalidChains: Boolean = reporting
      override def reportInvalidChain(blockHash: ByteString, latestValidHash: ByteString): Unit =
        reportedInvalid.add(blockHash -> latestValidHash): Unit

    // how many branches were handed to the consensus: a batch whose rest is dropped is not evaluated again
    val evaluations: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)

    // the old branch the branch resolution predicts the import will move the head off (B-460)
    val predictedOldBranch: java.util.concurrent.atomic.AtomicReference[List[Block]] =
      new java.util.concurrent.atomic.AtomicReference(Nil)

    override lazy val branchResolution: BranchResolution = new BranchResolution(
      blockchainReader,
      Option.when(followsConsensusLayer)(com.chipprbots.ethereum.consensus.engine.DesignatedHead(() => None))
    ):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
        NewBetterBranch(predictedOldBranch.get)

    val importer: TypedActorRef[BlockImporter.Command] = testKit.spawn(
      BlockImporter.apply(
        importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
        consensusAdapter,
        blockchainReader,
        blockchainWriter,
        stateStorage,
        evmCodeStorage,
        branchResolution,
        syncConfig,
        ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
        toPeers.ref,
        pool.ref,
        newHeads.ref,
        importerSupervisor.ref,
        peerEventBus.ref,
        networkPeerManager.ref,
        blockchain,
        blacklist,
        this,
        exhausts = exhausts,
        importLock = importLock,
        txPoolHead = txPoolHead
      ),
      s"$name-importer"
    )
    importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
    importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

    def weighed(block: Block): BlockData =
      BlockData(block, Nil, ChainWeight.totalDifficultyOnly(block.number.value))

    def importBatch(blocks: List[Block]): Unit =
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.fromListUnsafe(blocks)))

    // What the pool was sent, in order, until it is sent no more
    private lazy val toThePool: Seq[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command] =
      import com.chipprbots.ethereum.transactions.PendingTransactionsManager.HeadReset
      // the import's own last message to the pool is its reset (B-253): waited for, then whatever follows it
      val first = pool.fishForMessage(5.seconds) {
        case _: HeadReset => FishingOutcome.Complete
        case _            => FishingOutcome.Continue
      }
      val rest = Iterator
        .continually(scala.util.Try(pool.receiveMessage(500.millis)).toOption)
        .takeWhile(_.isDefined)
        .flatten
        .toList
      first ++ rest

    /** The transactions the pool was told to drop, one entry per block. */
    def removedFromThePool(): Seq[Seq[SignedTransaction]] = toThePool.flatMap {
      case com.chipprbots.ethereum.transactions.PendingTransactionsManager.RemoveTransactions(txs) => Seq(txs)
      case com.chipprbots.ethereum.transactions.PendingTransactionsManager.HeadReset(included, _)  => included
      case _                                                                                       => Nil
    }

    /** The transactions handed back to the pool, by a reset or by a hand-back of their own (the branch resolution's,
      * which B-460 retired: one would show here as a second entry).
      */
    def takenBackByThePool(): Seq[Seq[SignedTransaction]] = toThePool.collect {
      case com.chipprbots.ethereum.transactions.PendingTransactionsManager.AddUncheckedTransactions(txs)
          if txs.nonEmpty =>
        txs
      case com.chipprbots.ethereum.transactions.PendingTransactionsManager.HeadReset(_, txs) if txs.nonEmpty =>
        txs
    }

    /** Whether the pool was sent anything that hands transactions back besides a reset (B-460: nothing is). */
    def handedBackAheadOfAReset(): Boolean = toThePool.exists(
      _.isInstanceOf[com.chipprbots.ethereum.transactions.PendingTransactionsManager.AddUncheckedTransactions]
    )

    /** The pool is sent nothing for this import (asked once the import is known to be done). */
    def poolToldNothing(): Unit = pool.expectNoMessage(1.second)

    /** The pool's resets: one per move of the head. */
    def resetsOfThePool(): Int =
      toThePool.count(_.isInstanceOf[com.chipprbots.ethereum.transactions.PendingTransactionsManager.HeadReset])

    /** Where the fetcher was sent back to, and which block's peer it blamed (B-338). */
    def fetcherRewound(): InvalidateBlocksFrom =
      importerFetcher
        .fishForMessage(5.seconds) {
          case _: InvalidateBlocksFrom => true
          case _                       => false
        }
        .asInstanceOf[InvalidateBlocksFrom]

    /** The head the importer reported as progress. */
    def progressReported(): BigInt =
      importerSupervisor
        .fishForMessage(5.seconds) {
          case _: RegularSync.ProgressProtocol.ImportedBlock => true
          case _                                             => false
        }
        .asInstanceOf[RegularSync.ProgressProtocol.ImportedBlock]
        .blockNumber

    /** No peer is told of a fetched batch, and no more new-head events come. */
    def noneTold(): Unit =
      toPeers.expectNoMessage(1.second)
      newHeads.expectNoMessage(200.millis)

  /** An importer whose import of block 15 meets a missing trie node, until the node is written: the importer on the
    * unified layout (`unified`) or on the hash layout, and following a consensus layer (Ethereum,
    * `followsConsensusLayer`) or not (the proof-of-work importer, Classic). The two differ in the branch resolution and
    * in the adapter's refusal of a branch that hangs from a refused one; the missing-node machinery is the same code
    * (spec 018 T212).
    */
  abstract class MissingNodeOnTheUnifiedLayout(
      name: String,
      unified: Boolean,
      followsConsensusLayer: Boolean,
      // what one repair of the position store answers
      outcome: () => RepairOutcome = () => RepairOutcome.Written
  ) extends ForkRecoveryOnTheUnifiedLayout(name, recoveryStarted = false, unified = unified):
    // a block of another chain at height 15: this node's own is dropped from a batch before it is imported (PoW)
    val failing: Block =
      val own = testBlocks.find(_.number.value == 15).get
      own.copy(header = own.header.copy(extraData = ByteString("of another chain")))
    val node: ByteString = ByteString(s"a trie node of $name")
    val hash: ByteString = ByteString(kec256(node.toArray))
    val address: ByteString = ByteString(Array.fill[Byte](20)(7))
    val locationOf: ByteString = ByteString(HexPrefix.encode(Array[Byte](1, 2, 3), isLeaf = false))
    def missing: MissingNodeException

    /** The hash-keyed store, which only the hash layout writes: it records what it is asked, and answers `Written`. */
    final class RecordingStateStorage extends StateStorage:
      val repaired = new java.util.concurrent.ConcurrentLinkedQueue[(ByteString, ByteString, BigInt)]()
      val deleted = new java.util.concurrent.ConcurrentLinkedQueue[ByteString]()
      override def getBackingStorage(bn: BigInt): com.chipprbots.ethereum.db.storage.MptStorage = ???
      override def getReadOnlyStorage: com.chipprbots.ethereum.db.storage.MptStorage = ???
      override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(f: () => Unit): Unit = ()
      override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(f: () => Unit): Unit = ()
      override def saveNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): Unit = ???
      override def canRepairNodes: Boolean = true
      override def repairNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): RepairOutcome =
        repaired.add((nodeHash, ByteString(nodeEncoded), bn))
        nodeIsWritten = true
        RepairOutcome.Written
      override def deleteBadNode(
          nodeHash: ByteString,
          stillReached: () => Boolean
      ): com.chipprbots.ethereum.db.storage.BadEntryDelete =
        deleted.add(nodeHash)
        com.chipprbots.ethereum.db.storage.BadEntryDelete.NoLongerBad
      override def getNode(nodeHash: ByteString): Option[com.chipprbots.ethereum.mpt.MptNode] = None
      override def forcePersist(reason: StateStorage.FlushSituation): Boolean = true

    override lazy val stateStorage: StateStorage = new RecordingStateStorage
    def hashStore: RecordingStateStorage = stateStorage.asInstanceOf[RecordingStateStorage]

    val repairs = new RecordingPositionRepairs(() =>
      val answered = outcome()
      if answered == RepairOutcome.Written then nodeIsWritten = true
      answered
    )
    (() => blockchainWriter.positionRepairs).when().returns(Some(repairs))

    val evaluations = new java.util.concurrent.atomic.AtomicInteger(0)
    // what the escalation's feed was told of a hole no repair fills (B-291)
    val holesTold = new java.util.concurrent.ConcurrentLinkedQueue[(List[Block], MissingNodeException)]()
    @volatile var nodeIsWritten: Boolean = false
    @volatile var importedOk: Boolean = false
    // held after an evaluation is counted, while a test delivers into the running import (B-408)
    @volatile var holdTheImport: Option[java.util.concurrent.CountDownLatch] = None

    override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      override def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean = false
      override def holeNotRepaired(blocks: List[Block], missing: MissingNodeException)(implicit
          blockchainConfig: BlockchainConfig
      ): Unit = holesTold.add((blocks, missing))
      override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] =
        IO.blocking {
          evaluations.incrementAndGet()
          holdTheImport.foreach(_.await(30, java.util.concurrent.TimeUnit.SECONDS))
          if nodeIsWritten then
            importedOk = true
            BlockImportedToTop(Nil)
          else BlockImportFailedDueToMissingNode(missing)
        }

    override lazy val branchResolution: BranchResolution = new BranchResolution(
      blockchainReader,
      Option.when(followsConsensusLayer)(com.chipprbots.ethereum.consensus.engine.DesignatedHead(() => None))
    ):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult = NewBetterBranch(Nil)

    def importFailing(): Unit =
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(failing)))

    def fetchAsked(): BlockFetcher.FetchStateNode =
      importerFetcher.fishForSpecificMessage(10.seconds) { case m: BlockFetcher.FetchStateNode => m }

    def deliverNode(): Unit =
      importer ! BlockImporter.FetcherResponse(BlockFetcher.FetchedStateNode(NodeData(List(node))))

    def evaluated(count: Int): Unit = awaitCond(evaluations.get() >= count, 10.seconds)

    /** The node, delivered until `done`, as a fetcher asked again answers again: for the tests whose import asks for it
      * more than once. A node that lands while the import that asked for it runs is handed to that import's wait
      * (B-408), so one delivery is enough for one fetch.
      */
    def deliverUntil(done: => Boolean): Unit =
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
      while !done && System.nanoTime() < deadline do
        deliverNode()
        scala.util.Try(awaitCond(done, 300.millis))
      withClue("the importer did not take the node: ")(done shouldBe true)

  /** An importer whose branch resolution is scripted for a side branch from block 15: its first block is weighed and
    * not taken; the block after it leaves it not taken; the one after that makes it the heavier (B-300 f).
    */
  abstract class BranchNotTakenFixture(
      name: String,
      unified: Boolean,
      takenAt: Int = 17,
      // a chain that follows a consensus layer: the consensus client names the head, and no branch is weighed here
      followsConsensusLayer: Boolean = false,
      readyBlocks: Option[Int] = None,
      // false: the chain weight of block 14, the block the branch stands on, is not stored (B-385)
      forkWeightStored: Boolean = true,
      // true: a block of this node's chain above the SNAP pivot is not stored, and leaves the branch unweighed (B-407)
      gapInOwnChain: Boolean = false
  ) extends ForkRecoveryOnTheUnifiedLayout(name, recoveryStarted = false, unified = unified, readyBlocks = readyBlocks):
    val imports = new java.util.concurrent.atomic.AtomicInteger(0)
    val resolved = new java.util.concurrent.atomic.AtomicInteger(0)
    // the branches told to the escalation as ones whose fork weight is not stored (B-385)
    val unweighed: java.util.concurrent.ConcurrentLinkedQueue[List[BlockHeader]] =
      new java.util.concurrent.ConcurrentLinkedQueue()
    // the branches told to the escalation as ones a gap in this node's chain leaves unweighed (B-407)
    val overAGap: java.util.concurrent.ConcurrentLinkedQueue[ChainGap] =
      new java.util.concurrent.ConcurrentLinkedQueue()

    private val sideBlocks: Map[Int, Block] =
      (15 to 22).foldLeft(Map.empty[Int, Block]) { (built, number) =>
        // above this node's head (block 20) the branch's blocks are made from its head's
        val own = testBlocks
          .find(_.number.value == number)
          .getOrElse(testBlocks.last.copy(header = testBlocks.last.header.copy(number = BlockNumber(number))))
        val parent = built.get(number - 1).map(_.header.hash).getOrElse(own.header.parentHash)
        built.updated(
          number,
          own.copy(header = own.header.copy(parentHash = parent, extraData = ByteString("of the side branch")))
        )
      }
    def side(number: Int): Block = sideBlocks(number)

    private def record(tip: Int): BranchNotTaken =
      BranchNotTaken(
        side(tip).header,
        BigInt(14),
        testBlocks.find(_.number.value == 14).get.hash.value,
        ChainWeight.totalDifficultyOnly(tip),
        BigInt(tip)
      )

    override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      // a proof-of-work chain: no consensus layer refuses a branch (B-297)
      override def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean = false
      override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] =
        IO { imports.incrementAndGet(); BlockImportedToTop(Nil) }
      override def branchOutOfReach(
          headers: List[BlockHeader],
          fault: StateFault
      )(implicit blockchainConfig: BlockchainConfig): Unit =
        outOfReach.add((headers, fault, importLock.heldBy)): Unit
      override def forkWeightNotStored(headers: List[BlockHeader])(implicit
          blockchainConfig: BlockchainConfig
      ): Unit = unweighed.add(headers): Unit
      override def chainGapNotStored(gap: ChainGap)(implicit blockchainConfig: BlockchainConfig): Unit =
        overAGap.add(gap): Unit

    override lazy val branchResolution: BranchResolution = new BranchResolution(
      blockchainReader,
      Option.when(followsConsensusLayer)(com.chipprbots.ethereum.consensus.engine.DesignatedHead(() => None))
    ):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
        resolved.incrementAndGet()
        if headers.head.hash == side(15).header.hash then NoChainSwitch else UnknownBranch
      override def notTaken(headers: NonEmptyList[BlockHeader]): Option[BranchNotTaken] =
        Option.when(forkWeightStored && headers.head.hash == side(15).header.hash)(record(15))
      override def withoutForkWeight(headers: NonEmptyList[BlockHeader]): Option[NonEmptyList[BlockHeader]] =
        Option.when(!forkWeightStored && headers.head.hash == side(15).header.hash)(headers)
      override def acrossAGap(headers: NonEmptyList[BlockHeader]): Option[ChainGap] =
        Option.when(gapInOwnChain && headers.head.hash == side(15).header.hash)(
          ChainGap(BigInt(16), bodyOnly = false, headers)
        )
      override def extended(
          branch: BranchNotTaken,
          headers: NonEmptyList[BlockHeader]
      ): Option[(BranchNotTaken, Boolean)] =
        Option.when(headers.head.parentHash == branch.tip.hash) {
          val tip = headers.last.number.value.toInt
          (record(tip), tip >= takenAt)
        }

    /** One batch that is `block` alone, and the importer has resolved it. */
    def deliver(block: Block): Unit =
      val before = resolved.get()
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(block)))
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
      while resolved.get() <= before && System.nanoTime() < deadline do Thread.onSpinWait()
      resolved.get() shouldBe before + 1

  /** An importer whose branch resolution answers every batch `NoChainSwitch`, on the unified layout (`unified`) or on
    * the hash layout, with what it offers the ommer pool recorded (B-299). This node's chain is the test blocks, its
    * head block 20; a side branch leaves it at block 14 or at block 16.
    */
  abstract class OmmerOfferFixture(name: String, unified: Boolean)
      extends ForkRecoveryOnTheUnifiedLayout(name, recoveryStarted = false, unified = unified):
    val resolved = new java.util.concurrent.atomic.AtomicInteger(0)

    def own(number: Int): Block = testBlocks.find(_.number.value == number).get

    /** `count` blocks of a side branch that stands on this node's block `from`. */
    def sideOf(from: Int, count: Int): List[Block] =
      (1 to count).foldLeft(List.empty[Block]) { (made, i) =>
        val parent = made.lastOption.map(_.header.hash).getOrElse(own(from).hash)
        val template = own(from + i)
        made :+ template.copy(
          header = template.header.copy(parentHash = parent, extraData = ByteString(s"of the side branch from $from"))
        )
      }

    override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      // a proof-of-work chain: no consensus layer refuses a branch (B-297)
      override def refusedWithTheirParent(blocks: NonEmptyList[Block]): Boolean = false
      override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] = IO.pure(BlockImportedToTop(Nil))

    override lazy val branchResolution: BranchResolution = new BranchResolution(blockchainReader):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
        resolved.incrementAndGet()
        NoChainSwitch
      override def notTaken(headers: NonEmptyList[BlockHeader]): Option[BranchNotTaken] = None
      override def withoutForkWeight(headers: NonEmptyList[BlockHeader]): Option[NonEmptyList[BlockHeader]] = None
      override def acrossAGap(headers: NonEmptyList[BlockHeader]): Option[ChainGap] = None

    /** One batch, and the importer has resolved it. */
    def deliver(blocks: List[Block]): Unit =
      val before = resolved.get()
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.fromListUnsafe(blocks)))
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
      while resolved.get() <= before && System.nanoTime() < deadline do Thread.onSpinWait()
      resolved.get() shouldBe before + 1

    /** The headers the ommer pool was offered, until it is sent no more. */
    def offered(): Seq[BlockHeader] =
      ommersPool.receiveWhile(max = 3.seconds, idle = 500.millis) { case m => m }.flatMap {
        case com.chipprbots.ethereum.ommers.OmmersPool.AddOmmers(headers) => headers
        case _                                                            => Nil
      }

  /** An importer whose every import of block 15 is refused because the window its ommers are checked against is not
    * stored whole (B-301): the headers of blocks 9 to 14 are stored, each committing to a body of one ommer, and of
    * their bodies only those of blocks 13 and 14. The check reads the bodies of blocks 10 to 14, the five nearest that
    * name ommers (B-302), so those of 10, 11 and 12 are missing. With `windowHeaderMissing`, the header of block 11 is
    * not stored either. Nothing below block 9 is stored, which the ommers' check does not read.
    */
  abstract class AncestorBodiesMissing(name: String, windowHeaderMissing: Boolean = false)
      extends ForkRecoveryOnTheUnifiedLayout(name, recoveryStarted = false):
    /** The body every window block commits to: one ommer, so the check of ommers reads it (B-302). */
    val windowBody: BlockBody = BlockBody(Nil, Seq(testBlocks.head.header))
    private val windowOmmersHash =
      import com.chipprbots.ethereum.consensus.pow.blocks.OmmersSeqEnc
      com.chipprbots.ethereum.domain.BlockHash(
        org.apache.pekko.util.ByteString(com.chipprbots.ethereum.crypto.kec256(windowBody.uncleNodesList.toBytes))
      )
    val window: List[Block] =
      testBlocks.filter(b => b.number.value >= 9 && b.number.value <= 14).foldLeft(List.empty[Block]) { (built, own) =>
        val parent = built.lastOption.map(_.hash).getOrElse(own.header.parentHash)
        val header = own.header.copy(
          parentHash = parent,
          transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
          ommersHash = windowOmmersHash
        )
        built :+ Block(header, windowBody)
      }
    def windowBlock(number: Int): Block = window.find(_.number.value == number).get
    val failing: Block =
      val own = testBlocks.find(_.number.value == 15).get
      own.copy(header = own.header.copy(parentHash = window.last.hash))
    window
      .filterNot(b => windowHeaderMissing && b.number.value == 11)
      .foreach(b =>
        (blockchainReader
          .getBlockHeaderByHash(_: com.chipprbots.ethereum.domain.BlockHash))
          .when(b.hash)
          .returns(Some(b.header))
      )
    window
      .filter(_.number.value >= 13)
      .foreach(b =>
        (blockchainReader
          .getBlockBodyByHash(_: com.chipprbots.ethereum.domain.BlockHash))
          .when(b.hash)
          .returns(Some(b.body))
      )
    (blockchainWriter
      .storeBlockBody(_: com.chipprbots.ethereum.domain.BlockHash, _: BlockBody))
      .when(*, *)
      .returns(
        com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate(
          com.chipprbots.ethereum.db.dataSource.EphemDataSource(),
          Array.empty
        )
      )
    val imports = new java.util.concurrent.atomic.AtomicInteger(0)

    override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
      override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
          blockExecutionScheduler: IORuntime,
          blockchainConfig: BlockchainConfig
      ): IO[BlockImportResult] =
        IO {
          imports.incrementAndGet()
          BlockImportStateFault(
            new com.chipprbots.ethereum.db.storage.pathstate.AncestorNotStored(6, 2, "the bodies below are not stored")
          )
        }

    override lazy val branchResolution: BranchResolution = new BranchResolution(blockchainReader):
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
        NewBetterBranch(Nil)

    /** One more batch that is block 15 alone, and the import of it ended. */
    def failOnce(): Unit =
      val before = imports.get()
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(failing)))
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
      while imports.get() <= before && System.nanoTime() < deadline do Thread.onSpinWait()
      imports.get() shouldBe before + 1

    /** Nobody was blamed for block 15: no peer is blacklisted, and the fetcher was never told to blame one. */
    def noPeerBlamed(sentToTheFetcher: Seq[Any]): Unit =
      blacklist.keys shouldBe empty
      sentToTheFetcher.collect {
        case m: BlockFetcher.InvalidateBlocksFrom if m.toBlacklist.isDefined => m
        case m: BlockFetcher.BlockImportFailed                               => m
      } shouldBe empty

  def sync[T <: Fixture](test: => T): Future[Assertion] =
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      val fixture = test
      try succeed
      finally fixture.shutdownFixture()
    }

  "Regular Sync" when {
    "initializing" should {
      "subscribe for new blocks, new hashes, new block headers and ETH/69 BlockRangeUpdate" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          regularSync ! SyncProtocol.Start

          peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
            Set(
              Codes.NewBlockCode,
              Codes.NewBlockHashesCode,
              Codes.BlockHeadersCode,
              Codes.BlockRangeUpdateCode
            ),
            PeerSelector.AllPeers
          )
      )

      "subscribe to handshaked peers list" taggedAs (UnitTest, SyncTest) in sync(new Fixture:
        regularSync // unlazy
        networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd]
      )
    }

    "fetching blocks" should {
      "fetch headers and bodies concurrently" taggedAs (UnitTest, SyncTest) in sync(new Fixture:
        regularSync ! SyncProtocol.Start

        val sub136: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub136.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
          defaultPeer,
          BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
        )
        peersClient.expectMsgAllOfEq(
          blockHeadersChunkRequest(1),
          blockBodiesRequest(testBlocksChunked.head.hashes)
        )
      )

      "blacklist peer which caused failed request" taggedAs (UnitTest, SyncTest) in sync(new Fixture:
        regularSync ! SyncProtocol.Start

        peersClient.expectMsgType[PeersClient.Request[ETHGetBlockHeaders]].replyTo ! PeersClient.RequestFailed(
          defaultPeer,
          BlacklistReason.RegularSyncRequestFailed("a random reason")
        )
        peersClient.expectMsg(
          PeersClient.BlacklistPeer(defaultPeer.id, BlacklistReason.RegularSyncRequestFailed("a random reason"))
        )
      )

      "not blacklist peer which returns headers not matching current state during reorg" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          var blockFetcher: TypedActorRef[PeerEvent] = uninitialized

          regularSync ! SyncProtocol.Start
          val sub168: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
          blockFetcher = sub168.subscriber

          peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
            defaultPeer,
            BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
          )

          // Full-sized first batch bumps knownTop, so the fetcher emits the
          // bodies request AND the next-chunk headers prefetch in parallel.
          // Capture each reply address (Typed AskPattern carries it in Request.replyTo).
          var bodiesSender: TypedActorRef[PeersClient.ResponseMessage] = null
          var nextHeadersSender: TypedActorRef[PeersClient.ResponseMessage] = null
          def classifyNext(): Unit = peersClient.expectMsgPF() {
            case PeersClient.Request(msg: ETHGetBlockBodies, _, _, replyTo)
                if msg.hashes == testBlocksChunked.head.headers.map(_.hash) =>
              bodiesSender = replyTo
            case PeersClient.Request(_: ETHGetBlockHeaders, _, _, replyTo) =>
              nextHeadersSender = replyTo
          }
          classifyNext()
          classifyNext()

          bodiesSender ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), testBlocksChunked.head.bodies))

          blockFetcher ! MessageFromPeer(
            NewBlock(
              testBlocks.last,
              ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
            ),
            defaultPeer.id
          )
          // Headers from a much later chunk — HeadersNotMatchingWaitingHeaders fires.
          // The peer is NOT blacklisted: during a reorg, an honest peer on an alternative
          // chain will return headers that don't extend the local waiting state. Besu
          // AbstractPeerTask.java distinguishes HeadersNotMatchingExpected (no penalty) from
          // InvalidHeaders (blacklist). Expect a retry request instead of BlacklistPeer.
          nextHeadersSender ! PeersClient.Response(defaultPeer, BlockHeaders(BigInt(0), testBlocksChunked(5).headers))
          peersClient.fishForSpecificMessage() { case PeersClient.Request(_: ETHGetBlockHeaders, _, _, _) =>
            ()
          }
      )

      "not blacklist peer which returns headers not forming a chain" in sync(new Fixture:
        // HeadersNotFormingSeq: headers don't internally chain (e.g. skipped blocks).
        // During a reorg an honest peer may send a valid fork segment that doesn't chain
        // to our expected sequence. No blacklist — just drop and retry.
        regularSync ! SyncProtocol.Start

        peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
          defaultPeer,
          BlockHeaders(BigInt(0), testBlocks.headers.filter(_.number.value % 2 == 0))
        )
        peersClient.fishForSpecificMessage() { case PeersClient.Request(_: ETHGetBlockHeaders, _, _, _) =>
          ()
        }
      )

      // Deleted: "blacklist peer which sends headers/bodies that were not requested"
      // These tests expected BlacklistPeer for unsolicited data, but BlockFetcher drops
      // unsolicited data silently instead of blacklisting — behavior was never implemented.

      "wait for time defined in config until issuing a retry request due to no suitable peer" in sync(
        new Fixture:
          regularSync ! SyncProtocol.Start

          peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.NoSuitablePeer
          peersClient.expectNoMessage(syncConfig.syncRetryInterval)
          peersClient.expectMsgEq(blockHeadersChunkRequest(0))
      )

      "not fetch new blocks if fetcher's queue reached size defined in configuration" in sync(new Fixture:
        override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
          syncRetryInterval = testKitSettings.DefaultTimeout.duration,
          maxFetcherQueueSize = 1,
          maxReadyBlocksQueueSize = 1,
          blockBodiesPerRequest = 2,
          blockHeadersPerRequest = 2,
          blocksBatchSize = 2
        )

        regularSync ! SyncProtocol.Start

        val sub249: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub249.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.header.difficulty.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
          defaultPeer,
          BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
        )

        // Now expects ETH66 GetBlockBodies with requestId
        // requestId is dynamic (generated per request) so we ignore it with _
        val expectedHashes = testBlocksChunked.head.hashes.toSet
        val bodiesReplyTo: TypedActorRef[PeersClient.ResponseMessage] = peersClient.expectMsgPF() {
          case PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, replyTo) if hashes.toSet == expectedHashes =>
            replyTo
        }
        bodiesReplyTo ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), testBlocksChunked.head.bodies))

        peersClient.expectNoMessage()
      )
    }

    "resolving branches" should {

      // Regression: before the fix, ImportDone(ResolvingBranch(n)) never dispatched PickBlocks,
      // leaving BlockFetcher's waiting headers stranded and causing an infinite loop.
      "dispatch StrictPickBlocks after ImportDone(ResolvingBranch(n)), preventing infinite branch-resolution loop" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().returns(BigInt(1000))
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)

          val importerFetcher: TestProbe = TestProbe("importerFetcher")
          val importerSupervisor: TestProbe = TestProbe("importerSupervisor")
          val importerBroadcaster: TestProbe = TestProbe("importerBroadcaster")

          for depth <- List(1, 5, 64, 128) do
            val lca = BigInt(depth)
            val blockTopic: org.apache.pekko.actor.typed.ActorRef[
              org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
            ] = testKit.spawn(
              org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
                "block-imported-topic"
              ),
              s"block-imported-topic-depth-$depth"
            )
            // testKit.spawn keeps the Typed ref so testKit.stop can terminate it safely. Using the
            // Classic system.stop on a testKit-guardian child sends StopChild to the guardian, which
            // only accepts TestKitCommand → ClassCastException → whole-system shutdown (8a-retro batch 4).
            val importer = testKit
              .spawn(
                BlockImporter.apply(
                  importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
                  consensusAdapter,
                  blockchainReader,
                  blockchainWriter,
                  stateStorage,
                  evmCodeStorage,
                  branchResolution,
                  syncConfig,
                  ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
                  importerBroadcaster.ref,
                  pendingTransactionsManager.ref
                    .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
                  blockTopic,
                  importerSupervisor.ref,
                  peerEventBus.ref,
                  networkPeerManager.ref,
                  blockchain,
                  blacklist,
                  this,
                  exhausts = exhausts,
                  importLock = importLock
                ),
                s"test-importer-depth-$depth"
              )

            // Started as it is spawned (#1705).
            importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
            importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

            importer ! BlockImporter.ImportDone(BlockImporter.ResolvingBranch(lca), BlockImporter.DefaultBlockImport)

            val msg = importerFetcher.fishForSpecificMessage(5.seconds) {
              case m: BlockFetcher.StrictPickBlocks if m.from == lca => m
            }
            assert(msg.from == lca, s"StrictPickBlocks.from mismatch for depth=$depth")

            testKit.stop(importer)
            importerFetcher.expectNoMessage(200.millis)
      )

      // §9b divergence path: after repeated UnknownParent strikes BlockImporter escalates to
      // StartForkRecovery, spawns FastSyncBranchResolverActor, and transitions to `resolvingFork`.
      // When the resolver replies BranchResolvedSuccessful(lca), the importer must rewind the
      // canonical chain to the resolver's LCA (NOT the blind 128-block rewind) and invalidate
      // the fetcher's blocks from lca + 1. We drive the importer directly into resolvingFork and
      // inject the resolver reply via the public StartForkRecovery + private[regular] BranchResolverMsg
      // commands, bypassing the heavy peer-driven binary search inside the real resolver.
      "rewind canonical chain to resolver LCA on BranchResolvedSuccessful (divergence path)" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val capturedBest: BigInt = testBlocks.last.number.value // 20
          val lca: BigInt = BigInt(10)
          val lcaHeader: BlockHeader = testBlocks.find(_.number.value == lca).get.header
          val masterPeer: Peer = defaultPeer

          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().returns(capturedBest)
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)
          (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(lca).returns(Some(lcaHeader))
          // the canonical index above the LCA, which the rewind must read BEFORE it deletes it
          // (and at the LCA, which the rewind checks is still the block it rewinds to, and the first height above the
          // best, where the index ends: B-511)
          testBlocks.filter(b => b.number.value >= lca).foreach { b =>
            (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(b.number.value).returns(Some(b.hash))
          }
          (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(capturedBest + 1).returns(None)
          val abandonedByRewind: java.util.concurrent.ConcurrentLinkedQueue[(BigInt, ByteString)] =
            new java.util.concurrent.ConcurrentLinkedQueue()
          val recordingReorgState: com.chipprbots.ethereum.consensus.ReorgStateHandler =
            new com.chipprbots.ethereum.consensus.ReorgStateHandler:
              override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit =
                blocks.foreach(abandonedByRewind.add(_))
              override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = ()

          override lazy val blockchainWriter: BlockchainWriter = stub[BlockchainWriter]

          val importerFetcher: TestProbe = TestProbe("forkImporterFetcher")
          val importerSupervisor: TestProbe = TestProbe("forkImporterSupervisor")
          val importerBroadcaster: TestProbe = TestProbe("forkImporterBroadcaster")

          val forkBlockTopic: org.apache.pekko.actor.typed.ActorRef[
            org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
          ] = testKit.spawn(
            org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
              "fork-block-imported-topic"
            ),
            "fork-block-imported-topic"
          )

          // testKit.spawn keeps the Typed ref so testKit.stop can terminate it safely (Classic
          // system.stop on a testKit-guardian child crashes the guardian — 8a-retro batch 4).
          val importer: TypedActorRef[BlockImporter.Command] = testKit
            .spawn(
              BlockImporter.apply(
                importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
                consensusAdapter,
                blockchainReader,
                blockchainWriter,
                stateStorage,
                evmCodeStorage,
                branchResolution,
                syncConfig,
                ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
                importerBroadcaster.ref,
                pendingTransactionsManager.ref
                  .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
                forkBlockTopic,
                importerSupervisor.ref,
                peerEventBus.ref,
                networkPeerManager.ref,
                blockchain,
                blacklist,
                this,
                None,
                recordingReorgState,
                exhausts = exhausts,
                importLock = importLock
              ),
              "test-fork-recovery-importer"
            )

          // Started as it is spawned (#1705).
          importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
          importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

          // Escalate to fork recovery — spawns FastSyncBranchResolverActor and enters resolvingFork.
          importer ! BlockImporter.StartForkRecovery(BigInt(15))

          // Inject the resolver's success reply directly (simulating BranchResolvedSuccessful).
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, masterPeer)()
          )

          // The importer must invalidate the fetcher's blocks from lca + 1 (resolver-driven rollback).
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          assert(
            invalidate.fromBlock == lca + 1,
            s"expected InvalidateBlocksFrom(${lca + 1}), got ${invalidate.fromBlock}"
          )

          // The canonical chain head must be rewound to the resolver's LCA, not a blind 128-block rewind.
          (blockchainWriter
            .setCanonicalChainHead(_: BigInt, _: com.chipprbots.ethereum.domain.BlockHash, _: BigInt))
            .verify(lca, lcaHeader.hash, capturedBest)
            .once()

          // ... and the blocks the rewind un-canonicalised have their reference-count changes undone (#76).
          import scala.jdk.CollectionConverters.*
          abandonedByRewind.asScala.toList shouldBe
            testBlocks.filter(_.number.value > lca).map(b => (b.number.value, b.hash.value))

          testKit.stop(importer)
      )

      // B-298: the resolver refused the master peer's chain on the hash layout (too deep, or not the heavier). Nothing
      // is rewound: no blind rewind, no head moved, and the fetcher goes on from the head
      "rewind nothing and fetch on from the head when the resolver refuses the peer's chain, on the hash layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val capturedBest: BigInt = testBlocks.last.number.value // 20

          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().returns(capturedBest)
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)
          override lazy val blockchainWriter: BlockchainWriter = stub[BlockchainWriter]

          val importerFetcher: TestProbe = TestProbe("refusedForkImporterFetcher")
          val importerSupervisor: TestProbe = TestProbe("refusedForkImporterSupervisor")

          val forkBlockTopic: org.apache.pekko.actor.typed.ActorRef[
            org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
          ] = testKit.spawn(
            org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
              "refused-fork-block-imported-topic"
            ),
            "refused-fork-block-imported-topic"
          )

          val importer: TypedActorRef[BlockImporter.Command] = testKit.spawn(
            BlockImporter.apply(
              importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
              consensusAdapter,
              blockchainReader,
              blockchainWriter,
              stateStorage,
              evmCodeStorage,
              branchResolution,
              syncConfig,
              ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
              TestProbe("refusedForkImporterBroadcaster").ref,
              pendingTransactionsManager.ref
                .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
              forkBlockTopic,
              importerSupervisor.ref,
              peerEventBus.ref,
              networkPeerManager.ref,
              blockchain,
              blacklist,
              this,
              None,
              exhausts = exhausts,
              importLock = importLock
            ),
            "test-refused-fork-recovery-importer"
          )

          importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
          importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

          importer ! BlockImporter.StartForkRecovery(BigInt(15))
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolutionFailed(
              FastSyncBranchResolverActor.BranchResolutionFailed.BranchNotHeavier(BigInt(10))
            )
          )

          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          invalidate.fromBlock shouldBe capturedBest + 1
          invalidate.toBlacklist shouldBe None

          // neither the resolver's common block nor the blind rewind's floor: the head is not moved
          (blockchainWriter
            .setCanonicalChainHead(_: BigInt, _: com.chipprbots.ethereum.domain.BlockHash, _: BigInt))
            .verify(*, *, *)
            .never()

          testKit.stop(importer)
      )

      // B-346 (the user's decision of 2026-11-18): a heavier chain leaves this one below the floor on the hash layout.
      // Nothing is rewound: the state is given up for a SNAP re-sync through RegularSyncStuck, once for the fork; a
      // second recovery that finds the same fork does not ask again
      "escalate once to a SNAP re-sync, rewinding nothing, when the resolver finds a heavier chain below the floor" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val capturedBest: BigInt = testBlocks.last.number.value // 20

          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().returns(capturedBest)
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None)
          override lazy val blockchainWriter: BlockchainWriter = stub[BlockchainWriter]

          val importerFetcher: TestProbe = TestProbe("heavierForkImporterFetcher")
          val importerSupervisor: TestProbe = TestProbe("heavierForkImporterSupervisor")

          val forkBlockTopic: org.apache.pekko.actor.typed.ActorRef[
            org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
          ] = testKit.spawn(
            org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
              "heavier-fork-block-imported-topic"
            ),
            "heavier-fork-block-imported-topic"
          )

          val importer: TypedActorRef[BlockImporter.Command] = testKit.spawn(
            BlockImporter.apply(
              importerFetcher.ref.toTyped[BlockFetcher.FetchCommand],
              consensusAdapter,
              blockchainReader,
              blockchainWriter,
              stateStorage,
              evmCodeStorage,
              branchResolution,
              syncConfig,
              ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
              TestProbe("heavierForkImporterBroadcaster").ref,
              pendingTransactionsManager.ref
                .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
              forkBlockTopic,
              importerSupervisor.ref,
              peerEventBus.ref,
              networkPeerManager.ref,
              blockchain,
              blacklist,
              this,
              None,
              exhausts = exhausts,
              importLock = importLock
            ),
            "test-heavier-fork-recovery-importer"
          )

          importerFetcher.expectMsgClass(3.seconds, classOf[BlockFetcher.Start])
          importerSupervisor.expectMsgClass(3.seconds, classOf[RegularSync.ProgressProtocol.StartingFrom])

          val commonHash: ByteString = testBlocks.head.hash.value
          def heavierFound(): Unit =
            importer ! BlockImporter.StartForkRecovery(BigInt(15))
            importer ! BlockImporter.BranchResolverMsg(
              FastSyncBranchResolverActor.BranchHeavierBeyondReach(BigInt(1), commonHash, defaultPeer, weighed = 40)
            )

          heavierFound()
          val stuck: SyncProtocol.RegularSyncStuck = importerSupervisor.fishForSpecificMessage(5.seconds) {
            case m: SyncProtocol.RegularSyncStuck => m
          }
          stuck.blockNumber shouldBe BigInt(1)
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          invalidate.fromBlock shouldBe capturedBest + 1
          invalidate.toBlacklist shouldBe None

          // the same fork again: the fetcher is told as before, and the escalation is not asked for a second time. The
          // importer tells the supervisor before the fetcher, so once the fetcher has heard, a second request would be
          // in the supervisor's queue already
          heavierFound()
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
          importerSupervisor.receiveWhile(100.millis) { case m => m }.collect { case m: SyncProtocol.RegularSyncStuck =>
            m
          } shouldBe empty

          // nothing is rewound: neither to the common block nor to the blind rewind's floor
          (blockchainWriter
            .setCanonicalChainHead(_: BigInt, _: com.chipprbots.ethereum.domain.BlockHash, _: BigInt))
            .verify(*, *, *)
            .never()

          testKit.stop(importer)
      )

      // Spec 018 C4e part 6 (the user's decision of 2026-11-15): on the unified state layout fork recovery moves no
      // state. The head stays, the fetcher is sent back to the common block, and the peer's branch is judged by weight
      // when it arrives. As the case above, driven by the resolver's answer.
      "send the fetcher back to the common block and move nothing, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("lca"):
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          invalidate.fromBlock shouldBe lca + 1
          invalidate.toBlacklist shouldBe None
          // the next pick spans the fork: from the common block up to the block that failed (B-300 W4)
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (lca + 1, BigInt(15))
          nothingMoved()
          testKit.stop(importer)
      )

      // B-300 W4 (forge): asked for up to this node's head, a branch that is the heavier and the shorter was never
      // picked; asked for above the head, the pick would wait for blocks no rule says the peer has
      "pick the fetched branch up to the block that failed, and no higher than the head, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("above-the-head", failedAt = BigInt(30)):
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (lca + 1, capturedBest)
          testKit.stop(importer)
      )

      "send the fetcher back below the head and move nothing when no common block was found, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("blind"):
          importer ! BlockImporter.BranchResolverMsg(FastSyncBranchResolverActor.BranchResolutionFailed.noCommonBlock)
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          // max(head - 128, the SNAP pivot) + 1
          invalidate.fromBlock shouldBe BigInt(1)
          invalidate.toBlacklist shouldBe None
          nothingMoved()
          // nothing is escalated: a state that cannot be synced again from here is the escalation's (C5a)
          toTheSupervisor().collect { case stuck: SyncProtocol.RegularSyncStuck => stuck } shouldBe Nil
          testKit.stop(importer)
      )

      // B-300 W3 (forge): an announced block imported while a branch is fetched ended the resolution, so the next
      // pick was a plain one and the branch was never weighed whole
      "import no announced block while a branch is fetched, and keep fetching the branch after a mined one, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("announced"):
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.StrictPickBlocks => m }
          val own = testBlocks.last
          val announced: Block = own.copy(header = own.header.copy(extraData = ByteString("announced")))
          importer ! BlockImporter.ImportNewBlock(announced, defaultPeer.id)
          importer ! BlockImporter.PickBlocks
          val afterTheAnnounce: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (afterTheAnnounce.from, afterTheAnnounce.atLEastWith) shouldBe (lca + 1, BigInt(15))
          importedBlocksSet.contains(announced) shouldBe false

          // a block mined here is imported, and the branch is still the one being fetched when its import has ended
          val mined: Block = own.copy(header = own.header.copy(extraData = ByteString("mined")))
          importer ! BlockImporter.MinedBlock(mined)
          val imported: Long = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
          while !importedBlocksSet.contains(mined) && System.nanoTime() < imported do Thread.onSpinWait()
          importedBlocksSet.contains(mined) shouldBe true
          toTheFetcher().collect { case m: BlockFetcher.PickBlocks => m } shouldBe Nil
          importer ! BlockImporter.PickBlocks
          val afterTheMined: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (afterTheMined.from, afterTheMined.atLEastWith) shouldBe (lca + 1, BigInt(15))
          nothingMoved()
          testKit.stop(importer)
      )

      "end the resolution of a branch when a mined block's import ends, on the hash layout, as it always has" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("hash-mined", recoveryStarted = false, unified = false):
          // a batch whose parent this node does not know: the importer goes back and resolves the branch
          override lazy val branchResolution: BranchResolution = new BranchResolution(blockchainReader):
            override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult = UnknownBranch
          val own = testBlocks.last
          importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(own)))
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.StrictPickBlocks => m }
          val mined: Block = own.copy(header = own.header.copy(extraData = ByteString("mined")))
          importer ! BlockImporter.MinedBlock(mined)
          val imported: Long = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
          while !importedBlocksSet.contains(mined) && System.nanoTime() < imported do Thread.onSpinWait()
          importedBlocksSet.contains(mined) shouldBe true
          // the pick that follows the mined block's import is a plain one
          val picks: Seq[Any] = toTheFetcher().collect {
            case m: BlockFetcher.PickBlocks       => m
            case m: BlockFetcher.StrictPickBlocks => m
          }
          picks should not be empty
          picks.collect { case m: BlockFetcher.StrictPickBlocks => m } shouldBe Nil
          testKit.stop(importer)
      )

      // B-300 (f) (forge): a lighter branch was fetched again and again: nothing of it is stored, so the batch that
      // follows it hung from an unknown block, the fetcher went 64 blocks back, and the same answer came again
      "go on above a branch that is not taken, and fetch it whole once it has become the heavier, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("not-taken", unified = true):
          // the branch's first block: weighed, and not taken
          deliver(side(15))
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          // what follows stands on it: still not the heavier, so nothing is fetched again and the pick goes on
          deliver(side(16))
          val afterTheSecond: Seq[Any] = toTheFetcher()
          afterTheSecond.collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          afterTheSecond.collect { case m: BlockFetcher.PickBlocks => m } should not be empty
          // with the third it is the heavier: fetched whole from the block it leaves this chain at, up to its tip
          deliver(side(17))
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          (invalidate.fromBlock, invalidate.toBlacklist) shouldBe (BigInt(15), None)
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (BigInt(15), BigInt(17))
          imports.get() shouldBe 0
          nothingMoved()
          // weighed, with the weight of the block it stands on stored: nothing is told to the escalation
          unweighed.isEmpty shouldBe true
          testKit.stop(importer)
      )

      // B-299 (forge): a batch that is not taken offered its first block to the ommer pool as an ommer, though after a
      // fetcher reset from the common block that block is this node's own. Only the branch's first block off this
      // node's chain is offered, as core-geth offers only side blocks as uncles
      for (layout, unified) <- Seq("the unified state layout" -> true, "the hash layout" -> false) do
        s"offer the ommer pool no block of this node's own chain from a branch not taken, on $layout" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new OmmerOfferFixture(s"ommer-offer-$unified", unified):
            // as the fetcher serves after a reset from the common block: this node's own blocks, the chain whole
            deliver(List(own(15), own(16), own(17)))
            // this node's own blocks, then a branch that leaves its chain at block 16
            val leavesAt16: List[Block] = sideOf(16, 2)
            deliver(List(own(15), own(16)) ++ leavesAt16)
            // a branch whole, from block 14
            val leavesAt14: List[Block] = sideOf(14, 2)
            deliver(leavesAt14)
            val headers: Seq[BlockHeader] = offered()
            withClue(headers.map(h => s"${h.number} ${h.hashAsHexString}").mkString("\n")) {
              headers.map(_.hash).toSet shouldBe Set(leavesAt16.head.hash, leavesAt14.head.hash)
              headers.exists(h => testBlocks.exists(_.hash == h.hash)) shouldBe false
            }
            testKit.stop(importer)
        )

      // B-385: where the chain weight of the block a branch stands on is not stored, the fork choice answers
      // NoChainSwitch with an error and nothing else ends the wait for it: told to the escalation's feed
      "tell the escalation of a branch whose fork weight is not stored, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("unweighed", unified = true, forkWeightStored = false):
          deliver(side(15))
          awaitCond(!unweighed.isEmpty, 5.seconds)
          unweighed.peek() shouldBe List(side(15).header)
          imports.get() shouldBe 0
          nothingMoved()
          testKit.stop(importer)
      )

      "tell the escalation nothing of a branch whose fork weight is not stored on a chain that follows a consensus layer" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture(
          "unweighed-pos",
          unified = true,
          followsConsensusLayer = true,
          forkWeightStored = false
        ):
          deliver(side(15))
          toTheFetcher()
          unweighed.isEmpty shouldBe true
          testKit.stop(importer)
      )

      "tell the escalation nothing of a branch whose fork weight is not stored on the hash layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("unweighed-hash", unified = false, forkWeightStored = false):
          deliver(side(15))
          toTheFetcher()
          unweighed.isEmpty shouldBe true
          testKit.stop(importer)
      )

      // B-407: where a gap in this node's chain above the SNAP pivot leaves the branch unweighed (B-395 NoChainSwitch),
      // nothing else ends the wait for it: told to the escalation's feed, in its own words, and not as a branch whose
      // fork weight is not stored, though that weight is not stored either
      "tell the escalation of a branch a gap in this node's chain leaves unweighed, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("over-a-gap", unified = true, forkWeightStored = false, gapInOwnChain = true):
          deliver(side(15))
          awaitCond(!overAGap.isEmpty, 5.seconds)
          overAGap.peek() shouldBe ChainGap(BigInt(16), bodyOnly = false, NonEmptyList.one(side(15).header))
          unweighed.isEmpty shouldBe true
          imports.get() shouldBe 0
          nothingMoved()
          testKit.stop(importer)
      )

      "tell the escalation nothing of a branch a gap leaves unweighed on a chain that follows a consensus layer" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture(
          "over-a-gap-pos",
          unified = true,
          followsConsensusLayer = true,
          gapInOwnChain = true
        ):
          deliver(side(15))
          toTheFetcher()
          overAGap.isEmpty shouldBe true
          testKit.stop(importer)
      )

      "tell the escalation nothing of a branch a gap leaves unweighed on the hash layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("over-a-gap-hash", unified = false, gapInOwnChain = true):
          deliver(side(15))
          toTheFetcher()
          overAGap.isEmpty shouldBe true
          testKit.stop(importer)
      )

      // forge: a branch that is the heavier only with its tip, above this node's head, fetched up to the head, read as
      // the lighter without its tip, and was remembered and sent for again
      "fetch a branch that has become the heavier up to its tip, above the head, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("not-taken-above", unified = true, takenAt = 22):
          deliver(side(15))
          (16 to 21).foreach(number => deliver(side(number)))
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          deliver(side(22))
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (BigInt(15), BigInt(22))
          testKit.stop(importer)
      )

      // B-300 (g): a branch not taken that becomes the heavier further above its common block than the fetcher holds
      // is not fetched; the fork recovery it starts finds a peer, refuses it there, and escalates it
      "refuse a branch not taken that has become the heavier beyond the fetch, and escalate it, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        // 17 - 14 = 3 blocks above the block the branch leaves this chain at: one more than the fetcher holds
        new BranchNotTakenFixture("taken-beyond", unified = true, readyBlocks = Some(2)):
          val commonHeader: BlockHeader = testBlocks.find(_.number.value == 14).get.header
          (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(BigInt(14)).returns(Some(commonHeader))
          deliver(side(15))
          deliver(side(16))
          toTheFetcher()
          deliver(side(17))
          // nothing of it is fetched
          toTheFetcher().collect {
            case m: BlockFetcher.InvalidateBlocksFrom => m
            case m: BlockFetcher.StrictPickBlocks     => m
          } shouldBe Nil
          // the recovery it started is answered with the block the branch leaves this chain at
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(BigInt(14), defaultPeer)()
          )
          val branch: Vector[BlockHeader] = (15 to 22).map(side(_).header).toVector
          answerFrom(branch, BigInt(14)).head.block shouldBe Left(BigInt(15))
          val told: List[(List[BlockHeader], StateFault, Option[String])] = toldOutOfReach(1)
          told.map(_._1) shouldBe List(branch.toList)
          told.head._2
            .asInstanceOf[ReorgTooDeep]
            .reason shouldBe ReorgRefusal.BeyondTheFetch
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          imports.get() shouldBe 0
          nothingMoved()
          testKit.stop(importer)
      )

      // the reach of a branch not taken is its tip, above this node's head where it is: here only the tip is further
      // above the common block (22 - 14 = 8) than the fetcher holds (7); the head is not (20 - 14 = 6)
      "measure a branch not taken that was refused beyond the fetch from its tip above the head, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("taken-tip", unified = true, takenAt = 22, readyBlocks = Some(7)):
          val commonHeader: BlockHeader = testBlocks.find(_.number.value == 14).get.header
          (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(BigInt(14)).returns(Some(commonHeader))
          (15 to 21).foreach(number => deliver(side(number)))
          toTheFetcher()
          deliver(side(22))
          // nothing of it is fetched; the recovery it starts is answered with the block it leaves this chain at
          toTheFetcher().collect {
            case m: BlockFetcher.InvalidateBlocksFrom => m
            case m: BlockFetcher.StrictPickBlocks     => m
          } shouldBe Nil
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(BigInt(14), defaultPeer)()
          )
          // refused there too, and escalated: its headers are asked for from the block above the common block
          headersAsked()._1.block shouldBe Left(BigInt(15))
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          nothingMoved()
          testKit.stop(importer)
      )

      // the recovery a branch not taken starts can find its common block elsewhere: that fork is not the remembered
      // branch, and is measured from this node's head. At block 18 it is 2 blocks below the head, within the 3 the
      // fetcher holds, though the remembered tip (22) is 4 above it
      "measure the fork a refused branch's recovery finds at another block from the head, and fetch it as usual, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("taken-elsewhere", unified = true, takenAt = 22, readyBlocks = Some(3)):
          val otherCommon: BlockHeader = testBlocks.find(_.number.value == 18).get.header
          (blockchainReader.getBlockHeaderByNumber(_: BigInt)).when(BigInt(18)).returns(Some(otherCommon))
          (15 to 21).foreach(number => deliver(side(number)))
          toTheFetcher()
          deliver(side(22))
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(BigInt(18), defaultPeer)()
          )
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          (invalidate.fromBlock, invalidate.toBlacklist) shouldBe (BigInt(19), None)
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (BigInt(19), capturedBest)
          networkPeerManager
            .receiveWhile(max = 1.second, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          outOfReach.isEmpty shouldBe true
          testKit.stop(importer)
      )

      "fetch a branch not taken that has become the heavier at exactly the reach of the fetcher, as before, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("taken-at-the-cap", unified = true, readyBlocks = Some(3)):
          deliver(side(15))
          deliver(side(16))
          toTheFetcher()
          deliver(side(17))
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          invalidate.fromBlock shouldBe BigInt(15)
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (BigInt(15), BigInt(17))
          outOfReach.isEmpty shouldBe true
          testKit.stop(importer)
      )

      "remember no branch on a chain that follows a consensus layer, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BranchNotTakenFixture("not-taken-pos", unified = true, followsConsensusLayer = true):
          deliver(side(15))
          toTheFetcher()
          deliver(side(16))
          // nothing was remembered: the batch is an unknown branch, and the fetcher goes back for it
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.InvalidateBlocksFrom => m }
          testKit.stop(importer)
      )

      "go back for an unknown branch as it always has, on the hash layout" taggedAs (UnitTest, SyncTest) in sync(
        new BranchNotTakenFixture("not-taken-hash", unified = false):
          deliver(side(15))
          toTheFetcher()
          deliver(side(16))
          // 16 - branch-resolution-request-size, no lower than the genesis
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          invalidate.fromBlock shouldBe (BigInt(16) - syncConfig.branchResolutionRequestSize).max(0)
          testKit.stop(importer)
      )

      // B-300 (forge): nothing is rewound on the unified layout, so the block that started a recovery fails again
      // after it; without the latch every fifth failure would start another, for ever
      "make one fork recovery for a block that keeps failing, and go on evicting the peer that serves it, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ImportThatKeepsFailingOnTheUnifiedLayout("latch"):
          var evictedAt: Vector[Int] = Vector.empty
          var recoveries = 0
          (1 to 15).foreach { failure =>
            failOnce()
            val sent = toTheFetcher()
            if sent.exists(_.isInstanceOf[BlockFetcher.BlockImportFailed]) then evictedAt :+= failure
            recoveries += sent.count {
              case m: BlockFetcher.InvalidateBlocksFrom => m.fromBlock == lca + 1
              case _                                    => false
            }
            if failure == 6 then
              // the import of a batch ends the resolution, as it ends: the pick that follows is a plain one
              sent.collect { case m: BlockFetcher.StrictPickBlocks => m } shouldBe Nil
              sent.collect { case m: BlockFetcher.PickBlocks => m } should not be empty
            if failure == 5 then
              // the fifth failure started the recovery: the resolver's answer is given here
              importer ! BlockImporter.BranchResolverMsg(
                FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
              )
              val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
                case m: BlockFetcher.InvalidateBlocksFrom if m.fromBlock == lca + 1 => m
              }
              invalidate.toBlacklist shouldBe None
              recoveries += 1
              // the pick that follows spans the fork
              val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
                case m: BlockFetcher.StrictPickBlocks => m
              }
              (pick.from, pick.atLEastWith) shouldBe (lca + 1, BigInt(15))
          }
          // one recovery, at the fifth failure: the tenth and the fifteenth start none
          recoveries shouldBe 1
          // the peer that serves the block is dropped at every third failure, counted again from the recovery and
          // from each fifth failure after it, where the strikes start again
          evictedAt shouldBe Vector(3, 8, 13)
          nothingMoved()
          testKit.stop(importer)
      )

      // B-300 (forge): the import that starts a recovery sends the fetcher back to the block that failed as it ends,
      // and its end clears the branch being resolved; an answer acted on before that would be undone by both
      "hold the resolver's answer until the import under way has ended, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ImportThatKeepsFailingOnTheUnifiedLayout("held"):
          val release = new java.util.concurrent.CountDownLatch(1)
          holdTheImport = Some(release)
          importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(failing)))
          val entered: Long = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
          while importsBegun.get() < 1 && System.nanoTime() < entered do Thread.onSpinWait()
          importsBegun.get() shouldBe 1
          // a recovery is started while that import runs, and the resolver answers at once
          importer ! BlockImporter.StartForkRecovery(BigInt(15))
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          // nothing is done about the answer while the import runs
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m.fromBlock } shouldBe Nil
          release.countDown()
          // the import ends: it sends the fetcher back to the block that failed, and only then is the answer acted on
          val rewinds: Seq[BigInt] = Seq(
            importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.InvalidateBlocksFrom =>
              m.fromBlock
            },
            importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.InvalidateBlocksFrom =>
              m.fromBlock
            }
          )
          rewinds shouldBe Seq(BigInt(15), lca + 1)
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (lca + 1, BigInt(15))
          nothingMoved()
          testKit.stop(importer)
      )

      "leave the fetcher where it is when the head could not be moved back to the common block, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout(
          "deep",
          refusal = Some(
            new ReorgTooDeep(
              ReorgRefusal.Deep,
              "below what the records reach"
            )
          )
        ):
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          // it goes on picking from where it was: no block is fetched again from the common block
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.PickBlocks => m }
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          nothingMoved()
          toTheSupervisor().collect { case stuck: SyncProtocol.RegularSyncStuck => stuck } shouldBe Nil
          testKit.stop(importer)
      )

      // B-300 (g) (the user's decision of 2026-11-16): a branch whose common block is further below the head than the
      // fetcher holds ready is never picked whole. It is refused, and escalated with the master peer's headers
      "refuse a branch beyond the fetch and escalate it with the master peer's headers, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        // the head is 10 blocks above the common block: one more than the fetcher holds
        new ForkRecoveryOnTheUnifiedLayout("beyond", readyBlocks = Some(9)):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          val branch: Vector[BlockHeader] = sideBranch(lcaHeader, 12)
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          val asked: Seq[ETHGetBlockHeaders] = answerFrom(branch, lca)
          // asked of the master peer, from the block above the common block, a request at a time
          asked.map(_.block) shouldBe (0 until asked.size).map(i => Left(lca + 1 + 2 * i))
          asked.map(_.maxHeaders).distinct shouldBe Seq(BigInt(2))
          val told: List[(List[BlockHeader], StateFault, Option[String])] = toldOutOfReach(1)
          told.map(_._1) shouldBe List(branch.toList)
          val fault: ReorgTooDeep = told.head._2.asInstanceOf[ReorgTooDeep]
          fault.reason shouldBe ReorgRefusal.BeyondTheFetch
          // told under the import lock
          told.head._3 shouldBe Some(BlockImporter.BranchOutOfReachHolder)
          // nothing was fetched from the common block, and the importer goes on picking from where it was
          toTheFetcher().collect {
            case m: BlockFetcher.InvalidateBlocksFrom => m
            case m: BlockFetcher.StrictPickBlocks     => m
          } shouldBe Nil
          importer ! BlockImporter.PickBlocks
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.PickBlocks => m }
          nothingMoved()
          toTheSupervisor().collect { case stuck: SyncProtocol.RegularSyncStuck => stuck } shouldBe Nil
          testKit.stop(importer)
      )

      "ask for the branch's reach above the common block and two requests more, and no more, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("beyond-wanted", readyBlocks = Some(9)):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          // the peer has more than is asked for
          val branch: Vector[BlockHeader] = sideBranch(lcaHeader, 40)
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          // (20 - 10) + 2 * 2 headers, two at a time; the last request answered whole ends the fetch
          (1 to 7).foreach { _ =>
            val (request, peer, answerTo) = headersAsked()
            val first = request.block.left.toOption.get
            answerTo ! MessageFromPeer(
              BlockHeaders(request.requestId, branch.slice((first - lca - 1).toInt, (first - lca + 1).toInt)),
              peer
            )
          }
          toldOutOfReach(1).map(_._1) shouldBe List(branch.take(14).toList)
          networkPeerManager
            .receiveWhile(max = 1.second, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          testKit.stop(importer)
      )

      // forge, B-300 g: at most MaxBranchHeadersFetched headers, however far above the common block the branch reaches.
      // Here the reach and two requests more are 10 + 2 x 4096, above the cap: two requests of 4096 make the cap
      "fetch no more of a branch's headers than the most it is to fetch, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("capped", readyBlocks = Some(9)):
          override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
            blockHeadersPerRequest = BlockImporter.MaxBranchHeadersFetched / 2,
            blockBodiesPerRequest = 2,
            blockFetcherTickInterval = 60.seconds,
            peerResponseTimeout = 1.second,
            maxReadyBlocksQueueSize = 9
          )
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          // the peer has more than the cap, of one difficulty
          val branch: Vector[BlockHeader] =
            (1 to BlockImporter.MaxBranchHeadersFetched + 100).foldLeft(Vector.empty[BlockHeader]) { (made, i) =>
              made :+ lcaHeader.copy(
                parentHash = made.lastOption.getOrElse(lcaHeader).hash,
                number = BlockNumber(lca + i),
                extraData = ByteString(s"of the side branch $i")
              )
            }
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          val asked: Seq[ETHGetBlockHeaders] = (1 to 2).map { _ =>
            val (request, peer, answerTo) = headersAsked()
            val first = request.block.left.toOption.get
            answerTo ! MessageFromPeer(
              BlockHeaders(
                request.requestId,
                branch.slice((first - lca - 1).toInt, (first - lca - 1 + request.maxHeaders).toInt)
              ),
              peer
            )
            request
          }
          asked.map(_.maxHeaders).sum shouldBe BigInt(BlockImporter.MaxBranchHeadersFetched)
          toldOutOfReach(1).map(_._1.size) shouldBe List(BlockImporter.MaxBranchHeadersFetched)
          // and no third request
          networkPeerManager
            .receiveWhile(max = 1.second, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          testKit.stop(importer)
      )

      "fetch a branch at exactly the reach of the fetcher from the common block, as before, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("at-the-cap", readyBlocks = Some(10)):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          val invalidate: InvalidateBlocksFrom = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.InvalidateBlocksFrom => m
          }
          (invalidate.fromBlock, invalidate.toBlacklist) shouldBe (lca + 1, None)
          val pick: BlockFetcher.StrictPickBlocks = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.StrictPickBlocks => m
          }
          (pick.from, pick.atLEastWith) shouldBe (lca + 1, BigInt(15))
          // no header is asked of any peer
          networkPeerManager
            .receiveWhile(max = 1.second, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          outOfReach.isEmpty shouldBe true
          nothingMoved()
          testKit.stop(importer)
      )

      "escalate a branch whose common block the head could not be moved back to, as the same refusal, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout(
          "deep-escalated",
          refusal = Some(
            new ReorgTooDeep(
              ReorgRefusal.Deep,
              "below what the records reach"
            )
          )
        ):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          val branch: Vector[BlockHeader] = sideBranch(lcaHeader, 12)
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          answerFrom(branch, lca).head.block shouldBe Left(lca + 1)
          val told: List[(List[BlockHeader], StateFault, Option[String])] = toldOutOfReach(1)
          told.map(_._1) shouldBe List(branch.toList)
          // the refusal itself is the fault told, not its message
          val refused: Option[ReorgTooDeep] = blockchainWriter.refusalOfMoveBackTo(lcaHeader, blockchainReader)
          (told.head._2 eq refused.get) shouldBe true
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          nothingMoved()
          testKit.stop(importer)
      )

      "escalate nothing when this node has no header at the common block, and go on picking, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("no-header", readyBlocks = Some(9), lcaHeaderStored = false):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          importer ! BlockImporter.PickBlocks
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.PickBlocks => m }
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          networkPeerManager
            .receiveWhile(max = 1.second, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          outOfReach.isEmpty shouldBe true
          nothingMoved()
          testKit.stop(importer)
      )

      "escalate nothing when the request for the branch's headers times out, and go on picking, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("timeout", readyBlocks = Some(9)):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          val (request, peer, answerTo) = headersAsked()
          // not answered: the request gives up after the peer response timeout, and stops listening for the answer
          peerEventBus.fishForSpecificMessage(10.seconds) {
            case com.chipprbots.ethereum.network.PeerEventBusActor.UnsubscribeAllCmd(subscriber)
                if subscriber == answerTo =>
              ()
          }
          // an answer that comes after it is not taken
          answerTo ! MessageFromPeer(BlockHeaders(request.requestId, sideBranch(lcaHeader, 12)), peer)
          importer ! BlockImporter.PickBlocks
          importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.PickBlocks => m }
          networkPeerManager
            .receiveWhile(max = 1.second, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          outOfReach.isEmpty shouldBe true
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          nothingMoved()
          testKit.stop(importer)
      )

      // forge, B-300 g: each refused recovery would otherwise start a fetch of its own
      "start no second fetch of a branch's headers while one runs, on the unified state layout" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new ForkRecoveryOnTheUnifiedLayout("one-fetch", readyBlocks = Some(9)):
          override lazy val consensusAdapter: ConsensusAdapter = recordingOutOfReach()
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          headersAsked()._1.block shouldBe Left(lca + 1)
          // not answered: a second recovery is refused the same way while the first fetch waits
          importer ! BlockImporter.StartForkRecovery(BigInt(15))
          importer ! BlockImporter.BranchResolverMsg(
            FastSyncBranchResolverActor.BranchResolvedSuccessful(lca, defaultPeer)()
          )
          networkPeerManager
            .receiveWhile(max = 700.millis, idle = 300.millis) { case m => m }
            .collect { case m: NetworkPeerManagerActor.SendMessageCmd => m } shouldBe Nil
          toTheFetcher().collect { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe Nil
          outOfReach.isEmpty shouldBe true
          testKit.stop(importer)
      )

      "go back to earlier block in order to find a common parent with new branch" in sync(
        new Fixture:
          override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
          override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
          (() => blockchainReader.getBestBlockNumber).when().onCall(() => bestBlock.number.value)
          (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None) // no SNAP sync pivot
          // No canonical index in this stubbed world, so the importer's PoW prefix trim (#1592) drops nothing.
          (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(*).returns(None)
          override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]
          (consensusAdapter
            .evaluateBranchBlock(_: Block)(using _: IORuntime, _: BlockchainConfig))
            .when(*, *, *)
            .onCall((block, _, _) => fakeEvaluateBlock(block))
          (consensusAdapter
            .evaluateBranch(_: NonEmptyList[Block])(using _: IORuntime, _: BlockchainConfig))
            .when(*, *, *)
            .onCall((nel, _, _) => fakeEvaluateBatch(nel))
          override lazy val branchResolution: BranchResolution = new FakeBranchResolution()
          override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
            blockHeadersPerRequest = 5,
            blockBodiesPerRequest = 5,
            blocksBatchSize = 5,
            syncRetryInterval = 1.second,
            printStatusInterval = 0.5.seconds,
            branchResolutionRequestSize = 6
          )

          val commonPart: List[Block] = testBlocks.take(syncConfig.blocksBatchSize)
          val alternativeBranch: List[Block] =
            BlockHelpers.generateChain(syncConfig.blocksBatchSize * 2, commonPart.last)
          val alternativeBlocks: List[Block] = commonPart ++ alternativeBranch

          class BranchResolutionAutoPilot(didResponseWithNewBranch: Boolean, blocks: List[Block])
              extends PeersClientAutoPilot(blocks):
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              // Handle ETH68/69 GetBlockHeaders
              case PeersClient.Request(ETHGetBlockHeaders(_, Left(nr), maxHeaders, _, _), _, _, replyTo)
                  if nr >= alternativeBranch.numberAtUnsafe(syncConfig.blocksBatchSize) && !didResponseWithNewBranch =>
                val responseHeaders = alternativeBranch.headers.filter(_.number.value >= nr).take(maxHeaders.toInt)
                replyTo ! PeersClient.Response(defaultPeer, BlockHeaders(BigInt(0), responseHeaders))
                Some(new BranchResolutionAutoPilot(true, alternativeBlocks))
              // Handle ETH68/69 GetBlockBodies
              case PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, replyTo)
                  if !hashes.toSet.subsetOf(blocks.hashes.toSet) &&
                    hashes.toSet.subsetOf(testBlocks.hashes.toSet) =>
                val matchingBodies = hashes.flatMap(hash => testBlocks.find(_.hash.value == hash)).map(_.body)
                replyTo ! PeersClient.Response(defaultPeer, BlockBodies(BigInt(0), matchingBodies))
                None
            }

          peersClient.setAutoPilot(new BranchResolutionAutoPilot(didResponseWithNewBranch = false, testBlocks))

          Await.result(consensusAdapter.evaluateBranchBlock(BlockHelpers.genesis).unsafeToFuture(), remainingOrDefault)

          regularSync ! SyncProtocol.Start

          val sub383: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
          sub383.subscriber ! MessageFromPeer(
            NewBlock(
              alternativeBlocks.last,
              ChainWeight.totalDifficultyOnly(alternativeBlocks.last.number.value).totalDifficulty.value
            ),
            defaultPeer.id
          )
          // increase timeout slightly to reduce intermittent flakiness in forked test JVMs
          awaitCond(bestBlock == alternativeBlocks.last, 10.seconds)
      )
    }

    "go back to earlier positive block in order to resolve a fork when branch smaller than branch resolution size" in sync(
      new Fixture:
        override lazy val blockchainReader: BlockchainReader = stub[BlockchainReader]
        override lazy val blockchain: BlockchainImpl = stub[BlockchainImpl]
        (() => blockchainReader.getBestBlockNumber).when().onCall(() => bestBlock.number.value)
        (() => blockchainReader.getSnapSyncPivotBlock).when().returns(None) // no SNAP sync pivot
        // No canonical index in this stubbed world, so the importer's PoW prefix trim (#1592) drops nothing.
        (blockchainReader.getCanonicalHashByNumber(_: BigInt)).when(*).returns(None)
        override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]
        (consensusAdapter
          .evaluateBranchBlock(_: Block)(using _: IORuntime, _: BlockchainConfig))
          .when(*, *, *)
          .onCall((block, _, _) => fakeEvaluateBlock(block))
        (consensusAdapter
          .evaluateBranch(_: NonEmptyList[Block])(using _: IORuntime, _: BlockchainConfig))
          .when(*, *, *)
          .onCall((nel, _, _) => fakeEvaluateBatch(nel))
        override lazy val branchResolution: BranchResolution = new FakeBranchResolution()
        override lazy val syncConfig: SyncConfig = defaultSyncConfig.copy(
          syncRetryInterval = 1.second,
          printStatusInterval = 0.5.seconds,
          branchResolutionRequestSize = 12, // Over the original branch size

          // Big so that they don't impact the test
          blockHeadersPerRequest = 50,
          blockBodiesPerRequest = 50,
          blocksBatchSize = 50
        )

        val originalBranch: List[Block] = BlockHelpers.generateChain(10, BlockHelpers.genesis)
        val betterBranch: List[Block] = BlockHelpers.generateChain(originalBranch.size * 2, BlockHelpers.genesis)

        class ForkingAutoPilot(blocksToRespond: List[Block], forkedBlocks: Option[List[Block]])
            extends PeersClientAutoPilot(blocksToRespond):
          override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
            case req @ PeersClient.Request(ETHGetBlockBodies(_, hashes), _, _, _) =>
              handleForkLogic(hashes, req, sender)
          }

          private def handleForkLogic(hashes: Seq[ByteString], req: Any, sender: ActorRef): Option[AutoPilot] =
            val defaultResult = defaultHandlers(sender)(req)
            if forkedBlocks.nonEmpty && hashes.contains(blocksToRespond.last.hash) then
              Some(new ForkingAutoPilot(forkedBlocks.get, None))
            else defaultResult

        peersClient.setAutoPilot(new ForkingAutoPilot(originalBranch, Some(betterBranch)))

        Await.result(consensusAdapter.evaluateBranchBlock(BlockHelpers.genesis).unsafeToFuture(), remainingOrDefault)

        regularSync ! SyncProtocol.Start

        val sub445: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        val blockFetcher: TypedActorRef[PeerEvent] = sub445.subscriber
        sub445.subscriber ! MessageFromPeer(
          NewBlock(
            originalBranch.last,
            ChainWeight.totalDifficultyOnly(originalBranch.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        awaitCond(bestBlock == originalBranch.last, 5.seconds)

        // As node will be on top, we have to re-trigger the fetching process by simulating a block from the fork being broadcasted
        blockFetcher ! MessageFromPeer(
          NewBlock(
            betterBranch.last,
            ChainWeight.totalDifficultyOnly(betterBranch.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )
        awaitCond(bestBlock == betterBranch.last, 5.seconds)
    )

    "fetching state node" should {
      abstract class MissingStateNodeFixture extends Fixture:
        val failingBlock: Block = testBlocksChunked.head.head
        setImportResult(
          failingBlock,
          IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))
        )

      "blacklist peer which returns empty response" in sync(new MissingStateNodeFixture:
        val failingPeer: Peer = peerByNumber(1)

        peersClient.setAutoPilot(
          new PeersClientAutoPilot:
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, replyTo) =>
                replyTo ! PeersClient.Response(failingPeer, NodeData(Nil))
                None
            }
        )

        regularSync ! SyncProtocol.Start

        fishForBlacklistPeer(failingPeer)
      )

      "blacklist peer which returns invalid node" in sync(new MissingStateNodeFixture:
        val failingPeer: Peer = peerByNumber(1)
        peersClient.setAutoPilot(
          new PeersClientAutoPilot:
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, replyTo) =>
                replyTo ! PeersClient.Response(failingPeer, NodeData(List(ByteString("foo"))))
                None
            }
        )

        regularSync ! SyncProtocol.Start

        fishForBlacklistPeer(failingPeer)
      )

      "retry fetching node if validation failed" taggedAs (UnitTest, SyncTest) in sync(
        new MissingStateNodeFixture:
          def fishForFailingBlockNodeRequest(): Boolean = peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetNodeData(hash :: Nil), _, _, _) if hash == failingBlock.hash.value => true
          }

          class WrongNodeDataPeersClientAutoPilot(var handledRequests: Int = 0) extends PeersClientAutoPilot:
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, replyTo) =>
                val response = handledRequests match
                  case 0 => Some(PeersClient.Response(peerByNumber(1), NodeData(Nil)))
                  case 1 => Some(PeersClient.Response(peerByNumber(2), NodeData(List(ByteString("foo")))))
                  case _ => None

                response.foreach(replyTo ! _)
                Some(new WrongNodeDataPeersClientAutoPilot(handledRequests + 1))
            }

          peersClient.setAutoPilot(new WrongNodeDataPeersClientAutoPilot())

          regularSync ! SyncProtocol.Start

          fishForFailingBlockNodeRequest()
          fishForFailingBlockNodeRequest()
          fishForFailingBlockNodeRequest()
      )

      // B-327 (forge W3 on #1880): on the unified layout the consensus adapter answers a node missing from the held
      // state as a fault of this node (HeldStateIncomplete; ConsensusAdapterEscalationSpec pins the conversion). The
      // importer must not fetch it: a fetched node is written to the hash-keyed store, which this layout never reads.
      // Missing contract code is still fetched by its hash into the code store both layouts share (the two cases below).
      "ask no peer for a node missing from the held state on the unified layout, and write nothing to the node store" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          val missingHash: ByteString = kec256(ByteString("a node of the held state that is not stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportStateFault(
                new com.chipprbots.ethereum.db.storage.pathstate.HeldStateIncomplete(
                  new MissingNodeException(missingHash)
                )
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          // the block is fetched again, as for any fault of this node's state, and no node is asked for
          val requests: Seq[Any] = peersClient.receiveWhile(3.seconds) { case request => request }
          withClue("the sync went on asking peers for blocks: ")(requests should not be empty)
          requests.collect { case r @ PeersClient.Request(GetNodeData(_), _, _, _) => r } shouldBe empty
          storagesInstance.storages.nodeStorage.get(missingHash) shouldBe None
      )

      // Devnet-8 block 318074: an account the node held had a codeHash and no bytecode, execution ran the call as a
      // call to an EOA, and the block was reported INVALID. Missing code is now a missing-data condition: the importer
      // asks a snap peer for it (GetNodeData has no peers on ETH68+) and retries the block.
      "request missing contract code over SNAP GetByteCodes, not GetNodeData, once bulk recovery has been tried" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          // The bulk recovery is requested once per process; this is every miss after it, on a node where it is allowed.
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.snapSyncDone().commit() // a SNAP-synced node
          (1 to BlockImporter.BulkCodeRecoveryAfterMisses).foreach(i =>
            BlockImporter.claimBulkCodeRecovery(ByteString(s"spent the claim $i"))
          )
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code this node never stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )

          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetByteCodes(_, hashes, _), _, _, _) if hashes == Seq(codeHash) => true
          }
      )

      // #1694: the scan stops regular sync for as long as reading the whole state takes, estimated at hours on Sepolia, and
      // one missing contract does not earn that. A fetch took 100 ms on Ephemery.
      "fetch the first missing contract code alone on a SNAP-synced node, with no bulk recovery" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.snapSyncDone().commit() // a SNAP-synced node
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code this node never stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetByteCodes(_, hashes, _), _, _, _) if hashes == Seq(codeHash) => true
          }
          BlockImporter.distinctMissingCodesForTests shouldBe 1
          val toSupervisor: Seq[Object] = supervisor.receiveWhile(1.second) { case m => m }
          toSupervisor.collect {
            case com.chipprbots.ethereum.blockchain.sync.SyncController.WrappedSyncProtocol(
                  r: SyncProtocol.MissingCodeNeedsBulkRecovery
                ) =>
              r
          } shouldBe empty
      )

      // Several distinct misses show there are many to find, as on a datadir from before healing waited on code, and one
      // fetch per contract is one full re-execution of the block each. The miss that shows it asks for the bulk recovery
      // instead, and does not fetch that hash on its own.
      "ask SyncController for a bulk bytecode recovery once enough distinct contract codes have been missing" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.snapSyncDone().commit() // a SNAP-synced node
          (1 until BlockImporter.BulkCodeRecoveryAfterMisses).foreach(i =>
            BlockImporter.claimBulkCodeRecovery(ByteString(s"an earlier miss $i")) shouldBe false
          )
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code this node never stored"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          supervisor.fishForSpecificMessage(max = 10.seconds) {
            case com.chipprbots.ethereum.blockchain.sync.SyncController.WrappedSyncProtocol(
                  SyncProtocol.MissingCodeNeedsBulkRecovery(number, hash)
                ) if number == failingBlock.number.value && hash == codeHash =>
              true
          }
          // ...and it did NOT also start a single-hash fetch.
          val seen: Seq[Object] = peersClient.receiveWhile(1.second) { case m => m }
          seen.collect { case PeersClient.Request(_: GetByteCodes, _, _, _) => 1 } shouldBe empty
      )

      "count a code met again once, and ask for a bulk recovery at most once a process" taggedAs (
        UnitTest,
        SyncTest
      ) in {
        BlockImporter.resetBulkCodeRecoveryForTests()
        val same = ByteString("the same code, met again")
        (1 to BlockImporter.BulkCodeRecoveryAfterMisses * 2).foreach(_ =>
          BlockImporter.claimBulkCodeRecovery(same) shouldBe false
        )
        BlockImporter.distinctMissingCodesForTests shouldBe 1
        val claims = (1 to BlockImporter.BulkCodeRecoveryAfterMisses * 2).map(i =>
          BlockImporter.claimBulkCodeRecovery(ByteString(s"miss $i"))
        )
        claims.count(identity) shouldBe 1
        withClue("what it keeps is bounded: ") {
          BlockImporter.distinctMissingCodesForTests shouldBe BlockImporter.BulkCodeRecoveryAfterMisses
        }
        BlockImporter.resetBulkCodeRecoveryForTests()
        scala.concurrent.Future.successful(succeed)
      }

      "fetch missing contract code alone, without asking for a bulk recovery, on a node that did not SNAP-sync" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          BlockImporter.resetBulkCodeRecoveryForTests()
          storagesInstance.storages.appStateStorage.clearSnapSyncDone().commit()
          val failingBlock: Block = testBlocksChunked.head.head
          val codeHash: ByteString = kec256(ByteString("contract code of a node that executed its way here"))
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingCodeException(codeHash, ByteString(Array.fill[Byte](20)(0x16)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetByteCodes(_, hashes, _), _, _, _) if hashes == Seq(codeHash) => true
          }
          // The refused escalation counted nothing towards a bulk recovery.
          BlockImporter.distinctMissingCodesForTests shouldBe 0
      )

      // #1694: a storage hole met writing carries no position. It is found from the parent's root, not guessed as the
      // storage root, which drew an honest reply of the wrong node and a blacklisting.
      abstract class LocationlessStorageHoleFixture extends Fixture:
        val failingBlock: Block = testBlocksChunked.head.head
        val holder: Address = Address(ByteString(Array.fill[Byte](20)(0x2b)))
        private val store = storagesInstance.storages.stateStorage.getBackingStorage(0)
        // 64 slots: a storage root whose children are all referenced by hash.
        val storageRoot: ByteString =
          import MerklePatriciaTrie.defaultByteArraySerializable
          val trie = (0 until 64).foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](store)) { (t, i) =>
            t.put(kec256(BigInt(i).toByteArray), Array.fill[Byte](32)(i.toByte))
          }
          store.persist()
          ByteString(trie.getRootHash)
        val (holeSlot, hole) = store.get(storageRoot.toArray) match
          case branch: BranchNode =>
            branch.children.zipWithIndex.collectFirst { case (HashNode(h), i) => (i, ByteString(h)) }.get
          case other => fail(s"the storage root is no branch: $other")
        val stateRoot: ByteString =
          val accounts =
            MerklePatriciaTrie[Address, Account](store).put(holder, Account(storageRoot = TrieRoot(storageRoot)))
          store.persist()
          ByteString(accounts.getRootHash)
        private val parent = failingBlock.header.copy(stateRoot = TrieRoot(stateRoot))

        override lazy val blockchainReader: BlockchainReader = new BlockchainReader(
          storagesInstance.storages.blockHeadersStorage,
          storagesInstance.storages.blockBodiesStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.receiptStorage,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.chainWeightStorage,
          storagesInstance.storages.blockAccessListStorage
        ):
          override def getBestBlockNumber: BigInt = BigInt(0)
          override def getSnapSyncPivotBlock: Option[BigInt] = None
          override def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] =
            Some(BlockHelpers.genesis.header)
          override def getBlockHeaderByHash(hash: com.chipprbots.ethereum.domain.BlockHash): Option[BlockHeader] =
            if hash == failingBlock.header.parentHash then Some(parent) else super.getBlockHeaderByHash(hash)

        override lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)
        // The importer walks the storage trie through this; the fixture's own is a stub that reads nothing.
        override lazy val stateStorage: StateStorage = storagesInstance.storages.stateStorage
        // These tests are about a storage hole, not about branch resolution. The reader above answers the genesis hash
        // with the parent header below, which no real chain does, so real resolution reads the first batch as an unknown
        // branch; what imported it was the pivot floor's direct import, which now takes only a batch that continues the
        // chain (B-007). Resolution is settled here instead: the batch extends the head.
        override lazy val branchResolution: BranchResolution = stub[BranchResolution]
        branchResolution.resolveBranch.when(*).returns(NewBetterBranch(Nil))

        def holeAt(hash: ByteString): Unit =
          setImportResult(
            failingBlock,
            IO.pure(BlockImportFailedDueToMissingNode(new MissingStorageNodeException(hash, holder.bytes, None)))
          )

      "ask for a storage hole with no position at the position found under the parent's root" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(new LocationlessStorageHoleFixture:
        holeAt(hole)
        peersClient.setAutoPilot(new PeersClientAutoPilot)

        regularSync ! SyncProtocol.Start

        val expected: Seq[Seq[ByteString]] =
          Seq(Seq(kec256(holder.bytes), ByteString(HexPrefix.encode(Array(holeSlot.toByte), isLeaf = false))))
        peersClient.fishForSpecificMessage(max = 10.seconds) {
          case PeersClient.Request(GetTrieNodes(_, root, paths, _), _, _, _)
              if root == stateRoot && paths == expected =>
            true
        }
        // Located, so the fetch is waited on: nothing counts as exhausted at once (forge, #1694).
        TestProbe().expectNoMessage(500.millis)
        exhausts.get shouldBe 0
      )

      "ask no peer for a storage hole that cannot be located, and back off as after an exhausted fetch" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(new LocationlessStorageHoleFixture:
        holeAt(kec256(ByteString("referred to by nothing present")))
        peersClient.setAutoPilot(
          new PeersClientAutoPilot:
            // Left unanswered so a request, if one is sent, stays on the probe: the default answer looks the hash up as
            // a block's, throws for a node's, and the probe then never records the request.
            override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
              case PeersClient.Request(GetNodeData(_), _, _, _) => None
            }
        )

        regularSync ! SyncProtocol.Start

        awaitCond(exhausts.get == 1, 10.seconds)
        val asked: Seq[Object] = peersClient.receiveWhile(2.seconds) { case m => m }
        asked.collect {
          case r @ PeersClient.Request(_: GetNodeData, _, _, _)  => r
          case r @ PeersClient.Request(_: GetTrieNodes, _, _, _) => r
        } shouldBe empty
      )

      // forge, beacon: a retry tick counted as an exhaust made the first real exhaust the third, so a hole no peer served
      // for about 150 s cost a re-SNAP. The user's decision of 2026-11-02: only exhausts count.
      "not count its retries towards the re-SNAP, only exhausted fetches" taggedAs (UnitTest, SyncTest) in sync(
        new MissingStateNodeFixture:
          BlockImporter.resolvingRetryInterval = 100.millis
          try
            peersClient.setAutoPilot(
              new PeersClientAutoPilot:
                override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
                  case PeersClient.Request(GetNodeData(_), _, _, _) => None // never answered, so never exhausted here
                }
            )

            regularSync ! SyncProtocol.Start

            peersClient.fishForSpecificMessage(max = 10.seconds) {
              case PeersClient.Request(GetNodeData(hash :: Nil), _, _, _) if hash == failingBlock.hash.value => true
            }
            TestProbe().expectNoMessage(2.seconds) // twenty retry ticks
            exhausts.get shouldBe 0
          finally BlockImporter.resolvingRetryInterval = 30.seconds
      )

      // forge, #1694: an exhaust landing while a retry of the blocked import ran was dropped, and the re-import started
      // the fetch again from its first try. With only exhausts counting, that could hold the re-SNAP off.
      "count an exhausted fetch that lands while a retry of the import runs" taggedAs (UnitTest, SyncTest) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          // Slow, so the exhaust below lands inside the retry's import rather than between imports.
          setImportResult(
            failingBlock,
            IO.sleep(2.seconds) *>
              IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))
          )
          BlockImporter.resolvingRetryInterval = 100.millis
          try
            peersClient.setAutoPilot(
              new PeersClientAutoPilot:
                override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
                  case PeersClient.Request(GetNodeData(_), _, _, _) => None // the exhaust comes from the test
                }
            )

            regularSync ! SyncProtocol.Start

            peersClient.fishForSpecificMessage(max = 10.seconds) {
              case PeersClient.Request(GetNodeData(hash :: Nil), _, _, _) if hash == failingBlock.hash.value => true
            }
            // The first import has ended; its retry starts 100 ms later and runs for 2 s.
            TestProbe().expectNoMessage(1.second)
            exhausts.get shouldBe 0
            system.actorSelection(s"${regularSync.path}/block-importer-*") !
              BlockImporter.FetcherResponse(BlockFetcher.FetchedStateNode(NodeData(Nil)))

            awaitCond(exhausts.get == 1, 5.seconds)
            succeed
          finally BlockImporter.resolvingRetryInterval = 30.seconds
      )

      // Only exhausts count now, so the escalation must still come from them alone.
      "still ask for a SNAP re-sync after three exhausted fetches of one block" taggedAs (UnitTest, SyncTest) in sync(
        new LocationlessStorageHoleFixture:
          BlockImporter.exhaustBackoff = 200.millis
          try
            holeAt(kec256(ByteString("referred to by nothing present"))) // exhausts at once, each try
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            supervisor.fishForSpecificMessage(max = 15.seconds) {
              case SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(number, _))
                  if number == failingBlock.number.value =>
                true
            }
            exhausts.get shouldBe 0
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // forge, #1694: the counter outlived a block imported, so holes the audit filled days apart added up to a re-SNAP.
      "clear its exhausts once a block is imported" taggedAs (UnitTest, SyncTest) in sync(
        new LocationlessStorageHoleFixture:
          BlockImporter.exhaustBackoff = 1.second
          try
            holeAt(kec256(ByteString("referred to by nothing present")))
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            awaitCond(exhausts.get == 1, 10.seconds)
            // The audit fills the hole during the backoff, so the next try imports the block.
            setImportResult(
              failingBlock,
              IO.pure(
                BlockImportedToTop(
                  List(BlockData(failingBlock, Nil, ChainWeight.totalDifficultyOnly(failingBlock.number.value)))
                )
              )
            )
            awaitCond(exhausts.get == 0, 10.seconds)
            supervisor.receiveWhile(500.millis) { case m => m }.collect {
              case s @ SyncController.WrappedSyncProtocol(_: SyncProtocol.RegularSyncStuck) => s
            } shouldBe empty
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // spec 022 T062: on Mordor a corrupt account leaf blacklisted the honest provider on every import attempt, and a
      // fetched node saved later could not clear it under basic pruning's upsert. A stored entry that is not its node now
      // reads as a missing node; the importer deletes the entry, then fetches, and blames nobody.
      "delete a stored entry that is not its node before fetching it, and blacklist no peer" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          // Archive pruning, which can delete a bad entry; the fixture's own state storage is a stub.
          override lazy val stateStorage: StateStorage = storagesInstance.storages.stateStorage
          val corrupt: ByteString = kec256(ByteString("a trie node this node holds junk for"))
          storagesInstance.storages.nodeStorage.put(corrupt, Array(0x01.toByte))
          // What execution raises for it: the world state rewraps the corrupt node as a missing account node.
          setImportResult(
            failingBlock,
            IO.pure(
              BlockImportFailedDueToMissingNode(
                new MissingAccountNodeException(corrupt, ByteString(Array.fill[Byte](20)(0x2b)))
              )
            )
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot) // GetTrieNodes is left unanswered

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case b: PeersClient.BlacklistPeer                  => fail(s"a peer was blamed for a local fault: $b")
            case PeersClient.Request(_: GetTrieNodes, _, _, _) => true
          }
          withClue("the entry was deleted before the fetch went out: ") {
            storagesInstance.storages.nodeStorage.get(corrupt) shouldBe None
          }
          peersClient.receiveWhile(1.second) { case m => m }.collect { case b: PeersClient.BlacklistPeer => b } shouldBe
            empty
      )

      "leave a good stored node alone when a missing node is met under its hash" taggedAs (UnitTest, SyncTest) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          override lazy val stateStorage: StateStorage = storagesInstance.storages.stateStorage
          val node: Array[Byte] = MerklePatriciaTrie.EmptyEncoded
          val hash: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
          storagesInstance.storages.nodeStorage.put(hash, node)
          setImportResult(failingBlock, IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(hash))))
          peersClient.setAutoPilot(
            new PeersClientAutoPilot:
              override def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
                case PeersClient.Request(GetNodeData(_), _, _, _) => None
              }
          )

          regularSync ! SyncProtocol.Start

          peersClient.fishForSpecificMessage(max = 10.seconds) {
            case PeersClient.Request(GetNodeData(h :: Nil), _, _, _) if h == hash => true
          }
          storagesInstance.storages.nodeStorage.get(hash).map(_.toSeq) shouldBe Some(node.toSeq)
      )

      // spec 022 T062: a trie error that is not a missing node reached the importer as a string and took the invalid-block
      // path: the honest provider blacklisted each attempt, evicted at three strikes, fork detection at five, and never
      // an escalation (1,986 blacklistings in 45 minutes on Mordor). It is this node's fault: no peer is blamed, and it
      // escalates like exhausted fetches.
      "treat a trie error that is not a missing node as its own fault: no peer blamed, a re-SNAP at the third" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          BlockImporter.exhaustBackoff = 200.millis
          try
            setImportResult(
              failingBlock,
              IO.pure(BlockImportFailed(BlockExecutionError.MPTError(new MPTException("Invalid Node")).toString))
            )
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            supervisor.fishForSpecificMessage(max = 15.seconds) {
              case SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(number, _))
                  if number == failingBlock.number.value =>
                true
            }
            exhausts.get shouldBe 0
            withClue("neither blacklisted nor evicted: ") {
              peersClient.receiveWhile(500.millis) { case m => m }.collect { case b: PeersClient.BlacklistPeer =>
                b
              } shouldBe
                empty
            }
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // B-336: the same fault met after a prefix of the batch became the head. The prefix is counted as imported, and the
      // escalation is of the failing block, not of the batch's first block, which is on the chain already.
      "escalate a trie error met after a prefix of the batch became the head from the failing block, blaming no peer" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val prefixBlock: Block = testBlocksChunked.head.head
          val failingBlock: Block = testBlocksChunked.head(1)
          val localFault: String = BlockExecutionError.MPTError(new MPTException("Invalid Node")).toString
          // the prefix became the head at the first try only: a try again starts at the failing block
          val tries = new java.util.concurrent.atomic.AtomicInteger(0)
          // two exhausts met below the prefix: the chain moves past them when the prefix is imported, as at any import,
          // and the escalation counts from the failing block (unaccounted, the third exhaust is at once, of block 1)
          exhausts.increment()
          exhausts.increment()
          BlockImporter.exhaustBackoff = 200.millis
          try
            setImportResult(
              failingBlock,
              IO {
                val imported =
                  if tries.getAndIncrement() == 0 then
                    val weight = ChainWeight.totalDifficultyOnly(10001)
                    // as the import leaves it: the prefix is the head
                    blockchainWriter.save(prefixBlock, Nil, weight, saveAsBestBlock = true)
                    List(BlockData(prefixBlock, Nil, weight))
                  else Nil
                new BlockImportFailedAt(localFault, failingBlock.hash.value, imported)
              }
            )
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            supervisor.fishForSpecificMessage(max = 15.seconds) {
              case SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(number, _))
                  if number == failingBlock.number.value =>
                true
              case SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(number, _)) =>
                fail(s"escalated block $number, not the failing block ${failingBlock.number}")
            }
            withClue("neither blacklisted nor evicted: ") {
              peersClient.receiveWhile(500.millis) { case m => m }.collect { case b: PeersClient.BlacklistPeer =>
                b
              } shouldBe
                empty
            }
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // Spec 018 Track C, C4b: the state a block was to be executed on was moved from under the batch by another
      // writer, or is not held. The block was not judged. It is fetched and tried again, and nobody is blamed for it.
      "try a block again that this node's state could not take, and space the tries out from the third: no peer blamed" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
          // the wait between two spaced-out tries: longer than this test
          BlockImporter.exhaustBackoff = 1.hour
          try
            setImportResult(
              failingBlock,
              IO {
                attempts.incrementAndGet()
                BlockImportStateFault(
                  new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the head moved under the batch")
                )
              }
            )
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            // tried, fetched again and tried again at the importer's usual pace (the pick sent at once after a rewind
            // finds the fetcher emptied; the retry tick brings the next): three times, as often as a failed block is
            // tried before its peer is evicted
            awaitCond(attempts.get == 3, 15.seconds)
            // Then the tries are spaced out. Over three of the importer's usual retry intervals, in which a block
            // that is tried at the usual pace is tried again, none comes; and nobody is blacklisted or evicted.
            withClue("neither blacklisted nor evicted: ") {
              peersClient
                .receiveWhile(syncConfig.syncRetryInterval * 3) { case m => m }
                .collect { case b: PeersClient.BlacklistPeer => b } shouldBe empty
            }
            attempts.get shouldBe 3
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      // B-462: refused because another thread's adoption holds the head (B-294). No fault of this node's state: tried
      // again and spaced out as a fault is, and said at WARN as busy, never at ERROR. No peer blamed.
      "try again a block refused while another adoption holds the head, spaced out, said at WARN as busy and never at ERROR" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
          // the importer logs through Pekko to SLF4J, off this thread: what it says is collected as it arrives
          val logger: Logger = org.slf4j.LoggerFactory
            .getLogger("com.chipprbots.ethereum.blockchain.sync.regular")
            .asInstanceOf[Logger]
          val appender = new ListAppender[ILoggingEvent]
          val previousLevel = logger.getLevel
          appender.start()
          logger.addAppender(appender)
          logger.setLevel(Level.WARN)
          def said: List[ILoggingEvent] =
            import scala.jdk.CollectionConverters.*
            appender.synchronized(appender.list.asScala.toList)
          BlockImporter.exhaustBackoff = 1.hour
          try
            setImportResult(
              failingBlock,
              IO {
                attempts.incrementAndGet()
                BlockImportStateFault(
                  new com.chipprbots.ethereum.db.storage.pathstate.StateBusy(
                    "the adoption on thread 'adopter'",
                    "the commit of the block was not made: an adoption is moving the head"
                  )
                )
              }
            )
            peersClient.setAutoPilot(new PeersClientAutoPilot)

            regularSync ! SyncProtocol.Start

            awaitCond(attempts.get == 3, 15.seconds)
            // the third in a row is said once, at WARN, with the back-off
            awaitCond(said.exists(_.getFormattedMessage.contains("times running while another adoption")), 15.seconds)
            withClue("neither blacklisted nor evicted: ") {
              peersClient
                .receiveWhile(syncConfig.syncRetryInterval * 3) { case m => m }
                .collect { case b: PeersClient.BlacklistPeer => b } shouldBe empty
            }
            attempts.get shouldBe 3
            val busy = said.filter(_.getFormattedMessage.startsWith("[STATE-BUSY]"))
            busy.size should be >= 3
            busy.map(_.getLevel).distinct shouldBe List(Level.WARN)
            said.filter(e => e.getLevel == Level.ERROR && e.getFormattedMessage.contains("STATE")) shouldBe empty
            said.exists(_.getFormattedMessage.contains("[LOCAL-STATE-FAULT]")) shouldBe false
          finally
            BlockImporter.exhaustBackoff = 5.minutes
            logger.setLevel(previousLevel)
            logger.detachAppender(appender)
      )

      // Spec 018 C5a, B-297: the consensus layer's branch refused for what no attempt changes while the head stays (the
      // finalized block, a depth the records do not reach). Fetched again it is refused again, without end: it is tried
      // once, the fetcher goes on, what hangs from it is dropped with it, and nobody is blamed.
      "not fetch again a branch refused while the head stays, nor what hangs from it: no peer blamed" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new Fixture:
          val failingBlock: Block = testBlocksChunked.head.head
          val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
          val askedAbout = new java.util.concurrent.ConcurrentLinkedQueue[BigInt]()
          refusedWithTheirParent.set { blocks =>
            askedAbout.add(blocks.head.number.value)
            true
          }
          setImportResult(
            failingBlock,
            IO {
              attempts.incrementAndGet()
              BlockImportStateFault(
                new com.chipprbots.ethereum.db.storage.pathstate.RefusedForThisHead(
                  new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the finalized block")
                )
              )
            }
          )
          peersClient.setAutoPilot(new PeersClientAutoPilot)

          regularSync ! SyncProtocol.Start

          awaitCond(attempts.get == 1, 15.seconds)
          withClue("neither blacklisted nor evicted: ") {
            peersClient
              .receiveWhile(syncConfig.syncRetryInterval * 3) { case m => m }
              .collect { case b: PeersClient.BlacklistPeer => b } shouldBe empty
          }
          // tried once: neither fetched again nor fetched from below by the batch that hangs from it, which is refused
          // with it and never handed to consensus
          withClue(s"asked about the batches from ${askedAbout.toArray.mkString(",")}: ") {
            attempts.get shouldBe 1
            askedAbout should not be empty
            askedAbout.toArray.foreach(_.asInstanceOf[BigInt] should be > failingBlock.number.value)
          }
          importedBlocksSet.filter(_.number.value > failingBlock.number.value) shouldBe empty
      )

      // B-301: after a SNAP sync the bodies of the six blocks below a block may not be stored, and a node restarted
      // straight into regular sync has no hand-off to fetch them. The importer fetches them itself.
      "fetch the bodies missing below a block whose ommers cannot be checked, store those that match, and try the block again at once" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new AncestorBodiesMissing("ancestor-bodies"):
          // the wait between two spaced-out tries: longer than this test
          BlockImporter.exhaustBackoff = 1.hour
          try
            failOnce()
            val fetch: BlockFetcher.FetchAncestorBodies = importerFetcher.fishForSpecificMessage(5.seconds) {
              case m: BlockFetcher.FetchAncestorBodies => m
            }
            withClue("exactly the bodies missing of the parent's window, highest first: ") {
              fetch.headers.map(_.number.value) shouldBe Seq(12, 11, 10).map(BigInt(_))
              fetch.headers shouldBe Seq(12, 11, 10).map(windowBlock(_).header)
            }
            // Twice more while the fetch is in flight, the second into the back-off: no second request.
            failOnce()
            failOnce()
            val meanwhile = toTheFetcher()
            withClue("one request in flight: ") {
              meanwhile.collect { case m: BlockFetcher.FetchAncestorBodies => m } shouldBe empty
            }
            noPeerBlamed(meanwhile)

            // Two bodies that are the ones their headers commit to, and one that is not.
            val wrong = BlockBody(testBlocks.head.body.transactionList, Nil)
            fetch.replyTo ! BlockFetcher.FetchedAncestorBodies(
              fetch.headers,
              Seq(
                windowBlock(12).header -> windowBody,
                windowBlock(11).header -> wrong,
                windowBlock(10).header -> windowBody
              )
            )
            withClue("tried again at once, not at the end of the back-off: ") {
              importerFetcher.fishForSpecificMessage(5.seconds) { case _: BlockFetcher.PickBlocks => true }
            }
            // The streak started over, and the fetch is no longer in flight: the next fault is a first one, and asks
            // again for what is still missing (the stub's stored bodies do not change).
            failOnce()
            val after = toTheFetcher()
            after.collect { case m: BlockFetcher.FetchAncestorBodies => m } should have size 1
            withClue("not backed off: ") {
              after.collect { case m: BlockFetcher.PickBlocks => m } should not be empty
            }
            noPeerBlamed(after)
            // Verified last: ScalaMock refuses a call to a stub after a verify.
            Seq(12, 10).foreach { number =>
              (blockchainWriter
                .storeBlockBody(_: com.chipprbots.ethereum.domain.BlockHash, _: BlockBody))
                .verify(windowBlock(number).hash, windowBody)
                .once()
            }
            withClue("a body that is not the one its header commits to is not stored: ") {
              (blockchainWriter
                .storeBlockBody(_: com.chipprbots.ethereum.domain.BlockHash, _: BlockBody))
                .verify(windowBlock(11).hash, *)
                .never()
            }
          finally BlockImporter.exhaustBackoff = 5.minutes
      )

      "fetch no body below a block whose ommers cannot be checked when a header of that window is missing" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new AncestorBodiesMissing("ancestor-header", windowHeaderMissing = true):
          failOnce()
          val sent: Seq[Any] = toTheFetcher()
          sent.collect { case m: BlockFetcher.FetchAncestorBodies => m } shouldBe empty
          withClue("fetched again from the block, as before: ") {
            sent.collect { case m: BlockFetcher.InvalidateBlocksFrom => m.fromBlock } shouldBe Seq(BigInt(15))
          }
          noPeerBlamed(sent)
          (blockchainWriter
            .storeBlockBody(_: com.chipprbots.ethereum.domain.BlockHash, _: BlockBody))
            .verify(*, *)
            .never()
      )

      // forge, B-301 B1: a fork recovery's behaviour dropped the answer, and the one slot for a fetch stayed taken for
      // good: every later fault only logged a fetch in flight, and the endless back-off was back.
      "free the slot of a fetch of ancestor bodies whose answer arrives during a fork recovery, and store the bodies" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new AncestorBodiesMissing("ancestor-bodies-fork"):
          failOnce()
          val fetch: BlockFetcher.FetchAncestorBodies = importerFetcher.fishForSpecificMessage(5.seconds) {
            case m: BlockFetcher.FetchAncestorBodies => m
          }
          importer ! BlockImporter.StartForkRecovery(failing.number.value)
          fetch.replyTo ! BlockFetcher.FetchedAncestorBodies(fetch.headers, Seq(windowBlock(12).header -> windowBody))
          importer ! BlockImporter.BranchResolverMsg(FastSyncBranchResolverActor.BranchResolutionFailed.noCommonBlock)
          importerFetcher.fishForSpecificMessage(5.seconds) { case _: BlockFetcher.InvalidateBlocksFrom => true }
          toTheFetcher()

          failOnce()
          withClue("the slot is free, so the next fault fetches again: ") {
            toTheFetcher().collect { case m: BlockFetcher.FetchAncestorBodies => m } should have size 1
          }
          (blockchainWriter
            .storeBlockBody(_: com.chipprbots.ethereum.domain.BlockHash, _: BlockBody))
            .verify(windowBlock(12).hash, windowBody)
            .once()
          testKit.stop(importer)
      )

      // forge, B-301 B1: an answer that never comes holds the slot only until the deadline.
      "ask again for ancestor bodies when the fetch in flight is not answered within the deadline" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new AncestorBodiesMissing("ancestor-bodies-lost"):
          // Zero: every fetch is past its deadline by the next fault, whatever the machine's speed. The importer reads
          // the age of a fetch off System.nanoTime, which never runs backwards, so `age < deadline` is never true. A
          // deadline of 1 ms raced the test (B-446): on a warm JVM the second fault came within the same millisecond
          // as the first fetch, which then rightly still held the slot. That a fetch within its deadline holds the slot
          // is pinned by the test that fetches the bodies and stores those that match.
          BlockImporter.ancestorBodiesFetchDeadline = Duration.Zero
          try
            failOnce()
            importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.FetchAncestorBodies => m }
            failOnce()
            // the unanswered fetch no longer holds the slot: the second fault asks again (waited for, not sampled in a
            // quiet window, which a loaded machine can outlast)
            importerFetcher.fishForSpecificMessage(5.seconds) { case m: BlockFetcher.FetchAncestorBodies => m }
          finally BlockImporter.ancestorBodiesFetchDeadline = 10.minutes
      )

      "recognise a block execution's trie error by the text it reaches the importer as" taggedAs (
        UnitTest,
        SyncTest
      ) in {
        val error = BlockExecutionError.MPTError(new MPTException("Invalid Node"))
        // Pinned: ConsensusImpl passes `error.toString` on as it is when the branch extends the head...
        error.toString should startWith("MPTError(")
        Consensus.isLocalStateFault(error.toString) shouldBe true
        // ...and after the shared prefix when it reorganises (ConsensusImplSpec drives the text ConsensusImpl writes).
        Consensus.isLocalStateFault(s"${Consensus.ReorganiseFailurePrefix}$error") shouldBe true
        // A missing node never arrives as text, and nothing else that does is this node's own state.
        Consensus.isLocalStateFault("test error") shouldBe false
        Consensus.isLocalStateFault(
          BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString
        ) shouldBe false
        Consensus.isLocalStateFault(
          s"${Consensus.ReorganiseFailurePrefix}${BlockExecutionError.MissingParentError}"
        ) shouldBe false
        scala.concurrent.Future.successful(succeed)
      }

      "save fetched node" in sync(new Fixture:
        val failingBlock: Block = testBlocksChunked.head.head

        override lazy val blockchainReader: BlockchainReader = new BlockchainReader(
          storagesInstance.storages.blockHeadersStorage,
          storagesInstance.storages.blockBodiesStorage,
          storagesInstance.storages.blockNumberMappingStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.receiptStorage,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.chainWeightStorage,
          storagesInstance.storages.blockAccessListStorage
        ):
          override def getBestBlockNumber: BigInt = BigInt(0)
          override def getSnapSyncPivotBlock: Option[BigInt] = None
          override def getBlockHeaderByNumber(number: BigInt): Option[BlockHeader] =
            Some(BlockHelpers.genesis.header)

        override lazy val blockchain: BlockchainImpl = BlockchainImpl(storagesInstance.storages, blockchainReader)

        override lazy val consensusAdapter: ConsensusAdapter = new ConsensusAdapter(null, null, null, null, null):
          override def evaluateBranchBlock(block: Block)(implicit
              blockExecutionScheduler: IORuntime,
              blockchainConfig: BlockchainConfig
          ): IO[BlockImportResult] =
            IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))

          override def evaluateBranch(blocks: NonEmptyList[Block])(implicit
              blockExecutionScheduler: IORuntime,
              blockchainConfig: BlockchainConfig
          ): IO[BlockImportResult] =
            if repairNodeWasCalled then
              reimportedAfterSave = true
              IO.pure(BlockImportedToTop(Nil))
            else IO.pure(BlockImportFailedDueToMissingNode(new MissingNodeException(failingBlock.hash.value)))

        override lazy val branchResolution: BranchResolution = new BranchResolution(blockchainReader):
          override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult =
            NewBetterBranch(Nil)

        peersClient.setAutoPilot(new PeersClientAutoPilot)

        @volatile var reimportedAfterSave: Boolean = false
        @volatile var repairNodeWasCalled: Boolean = false
        @volatile var saveNodeWasCalled: Boolean = false
        val nodeData: List[ByteString] = List(ByteString(failingBlock.header.toBytes: Array[Byte]))

        override lazy val stateStorage: StateStorage = new StateStorage:
          override def getBackingStorage(bn: BigInt): com.chipprbots.ethereum.db.storage.MptStorage = ???
          override def getReadOnlyStorage: com.chipprbots.ethereum.db.storage.MptStorage = ???
          override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(f: () => Unit): Unit = ()
          override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(f: () => Unit): Unit = ()
          // #1692: the importer repairs the node (never lowering a count it finds); a blind saveNode is the old path.
          override def saveNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): Unit =
            saveNodeWasCalled = true
          override def canRepairNodes: Boolean = true
          override def repairNode(nodeHash: ByteString, nodeEncoded: Array[Byte], bn: BigInt): RepairOutcome =
            val expectedNode = nodeData.head
            nodeHash should be(kec256(expectedNode))
            nodeEncoded should be(expectedNode.toArray)
            bn should be(failingBlock.number)
            repairNodeWasCalled = true
            RepairOutcome.Written
          override def getNode(nodeHash: ByteString): Option[com.chipprbots.ethereum.mpt.MptNode] = None
          override def forcePersist(reason: StateStorage.FlushSituation): Boolean = true

        regularSync ! SyncProtocol.Start

        // From Start to the repair is the whole chain (fetch, import, missing node, node fetch, repair), which the
        // testkit's default 3 s did not always cover on a loaded runner (B-352). Nothing here is timed: the wait ends
        // when the repair is seen, and the bound only stops a hang.
        awaitCond(repairNodeWasCalled, 30.seconds)
        saveNodeWasCalled shouldBe false
        // The importer must resume the import as soon as the node arrives, not wait out the 30 s ResolvingMissingNode
        // SyncRetryTick (which stays only as a fallback). The bound is far below that tick.
        awaitCond(reimportedAfterSave, 10.seconds)
      )
    }

    "fetch a missing node by its position and write it there, on the unified layout (spec 018 C5b, T212)" should {
      for (chain, follows) <- List("Classic (proof-of-work importer)" -> false, "Ethereum (consensus layer)" -> true) do
        s"fill a hole in the account trie by repairNodeAt, on $chain" taggedAs (UnitTest, SyncTest) in sync(
          new MissingNodeOnTheUnifiedLayout(s"acct-$follows", unified = true, followsConsensusLayer = follows):
            override def missing: MissingNodeException =
              new MissingAccountNodeException(hash, address, Some(locationOf))
            val at: com.chipprbots.ethereum.db.storage.pathstate.PositionKey =
              com.chipprbots.ethereum.db.storage.pathstate.PositionKey(None, locationOf)

            importFailing()
            val asked: FetchStateNode = fetchAsked()
            asked.hash shouldBe hash
            asked.paths shouldBe Some(Seq(Seq(locationOf)))
            repairs.deleted.peek() shouldBe ((at, hash))

            // One answer, as the fetcher gives: wherever it lands, before or after the import's end, it is written once
            // and the import is tried again at once, not after the wait's 30 s (B-408).
            deliverNode()
            evaluated(2)
            repairs.repaired.peek() shouldBe ((at, hash, node))
            repairs.repaired.size shouldBe 1
            // never the hash-keyed store, which this layout does not read
            hashStore.repaired shouldBe empty
        )

        // B-408: the import sends its fetch from inside its own IO, before its ImportDone moves the importer into the
        // wait. A node answered in that gap was written as a late arrival, the wait then ran out its 30 s for it, and
        // the spec's answer-until-done loop wrote it a second time. Here the node lands while the import is held, so
        // it reaches the importer, by its mailbox's order, before that import's ImportDone.
        s"take a node that lands while the import runs as the answer to that import's fetch, on $chain" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new MissingNodeOnTheUnifiedLayout(s"gap-$follows", unified = true, followsConsensusLayer = follows):
            override def missing: MissingNodeException =
              new MissingAccountNodeException(hash, address, Some(locationOf))
            val held = new java.util.concurrent.CountDownLatch(1)
            holdTheImport = Some(held)

            importFailing()
            evaluated(1)
            deliverNode()
            held.countDown()
            fetchAsked()
            evaluated(2)
            importedOk shouldBe true
            repairs.repaired.size shouldBe 1
            repairs.repaired.peek() shouldBe (
              (com.chipprbots.ethereum.db.storage.pathstate.PositionKey(None, locationOf), hash, node)
            )
            // not saved as a late arrival at the head and then again by the wait
            hashStore.repaired shouldBe empty
        )

        // The held nodes are capped (forge, B-408 review): a fetcher that answers more than the one request it was given
        // cannot grow them without bound. Past the cap the oldest goes to storage as a late arrival; the newest, here
        // the one the import asked for, is still the answer its wait takes.
        s"hold at most ${BlockImporter.HeldNodesCapacity} nodes during an import and still take the newest as the answer, on $chain" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new MissingNodeOnTheUnifiedLayout(s"cap-$follows", unified = true, followsConsensusLayer = follows):
            override def missing: MissingNodeException =
              new MissingAccountNodeException(hash, address, Some(locationOf))
            val held = new java.util.concurrent.CountDownLatch(1)
            holdTheImport = Some(held)

            importFailing()
            evaluated(1)
            (1 to BlockImporter.HeldNodesCapacity).foreach { i =>
              importer ! BlockImporter.FetcherResponse(
                BlockFetcher.FetchedStateNode(NodeData(List(ByteString(Array.fill[Byte](40)(i.toByte)))))
              )
            }
            deliverNode()
            held.countDown()
            fetchAsked()
            evaluated(2)
            importedOk shouldBe true
            repairs.repaired.size shouldBe 1
            repairs.repaired.peek() shouldBe (
              (com.chipprbots.ethereum.db.storage.pathstate.PositionKey(None, locationOf), hash, node)
            )
        )

        s"fill a hole in a storage trie at the position of its own owner, on $chain" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new MissingNodeOnTheUnifiedLayout(s"stor-$follows", unified = true, followsConsensusLayer = follows):
            override def missing: MissingNodeException =
              new MissingStorageNodeException(hash, address, Some(locationOf))

            importFailing()
            fetchAsked().paths shouldBe Some(Seq(Seq(ByteString(kec256(address.toArray)), locationOf)))
            deliverUntil(evaluations.get() >= 2)
            repairs.repaired.peek() shouldBe (
              (
                com.chipprbots.ethereum.db.storage.pathstate
                  .PositionKey(Some(ByteString(kec256(address.toArray))), locationOf),
                hash,
                node
              )
            )
        )

      "retry the import at once when the anchor moved, and take the fetch as exhausted after MaxRepairDeclines" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new MissingNodeOnTheUnifiedLayout(
          "declines",
          unified = true,
          followsConsensusLayer = false,
          outcome = () => RepairOutcome.NotReached
        ):
          override def missing: MissingNodeException = new MissingAccountNodeException(hash, address, Some(locationOf))

          @volatile var back: Option[BlockFetcher.InvalidateBlocksFrom] = None
          def exhaustedFetch: Boolean =
            back = back.orElse(
              importerFetcher
                .receiveWhile(50.millis, 50.millis) { case m: BlockFetcher.InvalidateBlocksFrom => m }
                .headOption
            )
            back.isDefined
          importFailing()
          (1 to BlockImporter.MaxRepairDeclines).foreach { round =>
            fetchAsked()
            // an import is tried again at once for each of the declines but the last, which ends in the exhausted fetch
            if round < BlockImporter.MaxRepairDeclines then deliverUntil(evaluations.get() >= round + 1)
            else deliverUntil(exhaustedFetch)
          }
          // the last decline is an exhausted fetch: the fetcher goes back for the block, no peer blamed
          back.get.toBlacklist shouldBe None
          // a node that lands in the gap before the wait is written too, and counts as no decline
          repairs.repaired.size should be >= BlockImporter.MaxRepairDeclines
          evaluations.get() shouldBe BlockImporter.MaxRepairDeclines
      )

      // forge, B-291: the importer's exhausts reach their threshold on a repair that is declined for good. RegularSyncStuck
      // is ignored on the unified layout, so the hole is told to the escalation's feed instead, once, and the
      // import is then tried again only at the back-off's pace, not in the resolving loop.
      "tell the escalation of a hole whose repair is always declined exactly once, and send no RegularSyncStuck" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new MissingNodeOnTheUnifiedLayout(
          "declined-for-good",
          unified = true,
          followsConsensusLayer = false,
          outcome = () => RepairOutcome.NotReached
        ):
          override def missing: MissingNodeException = new MissingAccountNodeException(hash, address, Some(locationOf))

          val savedBackoff = BlockImporter.exhaustBackoff
          BlockImporter.exhaustBackoff = 20.millis
          try
            // one run of the fetch is MaxRepairDeclines declines, the last of which is an exhausted fetch
            def cycle(): Unit =
              importFailing()
              var back = false
              (1 to BlockImporter.MaxRepairDeclines).foreach { round =>
                fetchAsked()
                val before = evaluations.get()
                if round < BlockImporter.MaxRepairDeclines then deliverUntil(evaluations.get() > before)
                else
                  deliverUntil {
                    back = back || importerFetcher
                      .receiveWhile(50.millis, 50.millis) { case m: BlockFetcher.InvalidateBlocksFrom => m }
                      .nonEmpty
                    back
                  }
              }
            (1 until BlockImporter.StuckEscapeThreshold).foreach { _ =>
              cycle()
              holesTold.size shouldBe 0
            }
            // the exhaust that reaches the threshold tells the feed
            cycle()
            awaitCond(holesTold.size == 1, 10.seconds)
            holesTold.peek()._1.map(_.hash) shouldBe List(failing.hash)
            holesTold.peek()._2.hash shouldBe hash
            holesTold.peek()._2.location shouldBe Some(locationOf)
            // and no more, however many times it is tried again at the same tip
            (1 to 3).foreach(_ => cycle())
            importerSupervisor
              .receiveWhile(300.millis) { case m => m }
              .collect { case m: SyncProtocol.RegularSyncStuck => m } shouldBe Nil
            holesTold.size shouldBe 1
          finally BlockImporter.exhaustBackoff = savedBackoff
      )

      "go on once a repair that was declined is written" taggedAs (UnitTest, SyncTest) in sync {
        val answers = new java.util.concurrent.atomic.AtomicInteger(0)
        new MissingNodeOnTheUnifiedLayout(
          "declined-once",
          unified = true,
          followsConsensusLayer = true,
          outcome = () => if answers.getAndIncrement() == 0 then RepairOutcome.NotReached else RepairOutcome.Written
        ):
          override def missing: MissingNodeException = new MissingAccountNodeException(hash, address, Some(locationOf))

          importFailing()
          fetchAsked()
          deliverUntil(importedOk)
          repairs.repaired.size should be >= 2
          // not an exhausted fetch: the fetcher is not sent back for the block
          importerFetcher.receiveWhile(500.millis) { case m: BlockFetcher.InvalidateBlocksFrom => m } shouldBe empty
      }

      "write nothing for a hole that names no position" taggedAs (UnitTest, SyncTest) in sync(
        new MissingNodeOnTheUnifiedLayout("no-position", unified = true, followsConsensusLayer = false):
          override def missing: MissingNodeException = new MissingAccountNodeException(hash, address, None)

          importFailing()
          fetchAsked()
          deliverUntil(evaluations.get() >= 2)
          // nothing names where it goes: the importer asks again rather than write by hash
          repairs.repaired shouldBe empty
          repairs.deleted shouldBe empty
          hashStore.repaired shouldBe empty
      )
    }

    "keep to the hash store, as before, with the unified layout off (spec 018 C5b, T212)" should {
      for (chain, follows) <- List("Classic" -> false, "Ethereum" -> true) do
        s"repairNode and deleteBadNode by hash on $chain, and never touch the position store" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new MissingNodeOnTheUnifiedLayout(s"hash-$follows", unified = false, followsConsensusLayer = follows):
            override def missing: MissingNodeException =
              new MissingAccountNodeException(hash, address, Some(locationOf))
            importFailing()
            fetchAsked()
            deliverUntil(evaluations.get() >= 2)
            import scala.jdk.CollectionConverters.*
            hashStore.deleted.asScala.toSet shouldBe Set(hash)
            // by hash, as before; a node delivered again after the import is a late arrival, written at the best block
            hashStore.repaired.asScala.map((h, bytes, _) => (h, bytes)).toSet shouldBe Set((hash, node))
            hashStore.repaired.asScala.map(_._3).toSet.subsetOf(Set(BigInt(15), BigInt(20))) shouldBe true
            repairs.repaired shouldBe empty
            repairs.deleted shouldBe empty
        )
    }

    "catching the top" should {
      "ignore new blocks if they are too new" in sync(new Fixture:
        override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]

        val newBlock: Block = testBlocks.last

        regularSync ! SyncProtocol.Start
        val sub576: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]

        sub576.subscriber ! MessageFromPeer(
          NewBlock(newBlock, ChainWeight.totalDifficultyOnly(BigInt(1)).totalDifficulty.value),
          defaultPeer.id
        )

        // Wait for actor to finish processing and verify it never calls evaluateBranchBlock
        // Use assertForDuration to continuously verify the mock is never called
        assertForDuration(
          (consensusAdapter
            .evaluateBranchBlock(_: Block)(using _: IORuntime, _: BlockchainConfig))
            .verify(*, *, *)
            .never(),
          remainingOrDefault
        )
      )

      "retry fetch of block that failed to import" in sync(new Fixture:
        val failingBlock: Block = testBlocksChunked(1).head

        testBlocksChunked.head.foreach(setImportResult(_, IO.pure(BlockImportedToTop(Nil))))
        setImportResult(failingBlock, IO.pure(BlockImportFailed("test error")))

        peersClient.setAutoPilot(new PeersClientAutoPilot())

        regularSync ! SyncProtocol.Start

        val sub598: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub598.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        awaitCond(didTryToImportBlock(failingBlock))

        peersClient.fishForMsgEq(blockHeadersChunkRequest(1))
      )
    }

    "on top" should {
      "import received new block" in sync(new OnTopFixture:
        goToTop()

        sendNewBlock()

        awaitCond(importedNewBlock)
      )

      "broadcast imported block" in sync(new OnTopFixture:
        networkPeerManager.setAutoPilot(
          new AutoPilot:
            def run(sender: ActorRef, msg: Any): AutoPilot = msg match
              case cmd: GetHandshakedPeersCmd =>
                cmd.replyTo ! HandshakedPeers(handshakedPeers)
                this
              case _ => this
        )

        goToTop()

        sendNewBlock()
        awaitCond(importedNewBlock)

        networkPeerManager.fishForSpecificMessageMatching(max = 10.seconds) {
          case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
            message.underlyingMsg match
              case NewBlock(block, _) if block == newBlock => true
              case _                                       => false
          case _ => false
        }
      )

      "fetch hashes if received NewHashes message" in sync(new OnTopFixture:
        goToTop()

        blockFetcher !
          MessageFromPeer(NewBlockHashes(List(BlockHash(newBlock.hash.value, newBlock.number.value))), defaultPeer.id)

        peersClient.expectMsgPF() { case PeersClient.Request(ETHGetBlockHeaders(_, _, _, _, _), _, _, _) =>
          true
        }
      )
    }

    "handling mined blocks" should {
      "not import when importing other blocks" in sync(new Fixture:
        val headPromise: Promise[BlockImportResult] = Promise()
        setImportResult(testBlocks.head, IO.fromFuture(IO.pure(headPromise.future)))
        val minedBlock: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        peersClient.setAutoPilot(new PeersClientAutoPilot())

        regularSync ! SyncProtocol.Start

        val sub665: SubscribeCmd = peerEventBus.expectMsgType[SubscribeCmd]
        sub665.subscriber ! MessageFromPeer(
          NewBlock(
            testBlocks.last,
            ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
          ),
          defaultPeer.id
        )

        awaitCond(didTryToImportBlock(testBlocks.head))
        regularSync ! SyncProtocol.MinedBlock(minedBlock)
        // Wait and verify the minedBlock is not imported while another import is in progress
        // Use assertForDuration to continuously verify the block is not imported
        assertForDuration(
          didTryToImportBlock(minedBlock) shouldBe false,
          remainingOrDefault / 2
        )
        // Clean up by completing the promise
        headPromise.success(BlockImportedToTop(Nil))
      )

      "import when on top" in sync(new OnTopFixture:
        goToTop()

        regularSync ! SyncProtocol.MinedBlock(newBlock)

        awaitCond(importedNewBlock)
      )

      "import when not on top and not importing other blocks" in sync(new Fixture:
        val minedBlock: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
        setImportResult(minedBlock, IO.pure(BlockImportedToTop(Nil)))

        regularSync ! SyncProtocol.Start

        regularSync ! SyncProtocol.MinedBlock(minedBlock)

        awaitCond(didTryToImportBlock(minedBlock))
      )

      "broadcast after successful import" in sync(new OnTopFixture:
        goToTop()

        val peersCmd724: GetHandshakedPeersCmd = networkPeerManager.expectMsgType[GetHandshakedPeersCmd]
        peersCmd724.replyTo ! HandshakedPeers(handshakedPeers)

        regularSync ! SyncProtocol.MinedBlock(newBlock)

        networkPeerManager.fishForSpecificMessageMatching() {
          case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
            message.underlyingMsg match
              case NewBlock(block, _) if block == newBlock => true
              case _                                       => false
          case _ => false
        }
      )
    }

    // B-297 (forge): a reorganisation on the unified layout tells the side blocks it stands on too. B-344 (the user's
    // decision of 2026-11-23, core-geth): peers are told of the new head only, with its own total difficulty, never
    // another block's (two lists combined by `mapN` were every pairing); the pool is told of every block told; new-head
    // subscribers are told of the blocks the import executed only, not of a stored side block it made canonical.
    "broadcasting blocks told together" should {
      "send the new head only, with its own total difficulty, tell the pool of every block and subscribers of the executed" in sync(
        new OnTopFixture:
          val newHeads: TypedTestProbe[Topic.Command[NewBlockImported]] =
            testKit.createTestProbe[Topic.Command[NewBlockImported]]()
          override lazy val blockTopic: TypedActorRef[Topic.Command[NewBlockImported]] = newHeads.ref
          val sideParent: Block = BlockHelpers.generateBlock(testBlocks.init.last)
          val told: Block = BlockHelpers.generateBlock(sideParent)
          override def newBlockResult: BlockImportResult =
            ChainReorganised(
              Nil,
              List(sideParent, told),
              List(ChainWeight.totalDifficultyOnly(1111), ChainWeight.totalDifficultyOnly(2222)),
              // `told` was executed by this import; `sideParent` is a stored side block the branch stands on
              branchBlocks = 1
            )
          goToTop()

          val peers: GetHandshakedPeersCmd = networkPeerManager.expectMsgType[GetHandshakedPeersCmd]
          peers.replyTo ! HandshakedPeers(handshakedPeers)

          regularSync ! SyncProtocol.MinedBlock(newBlock)

          val sent: List[Any] = networkPeerManager
            .receiveWhile(max = 5.seconds, idle = 1.second) {
              case NetworkPeerManagerActor.SendMessageCmd(message, _) => message.underlyingMsg
              case other                                              => other
            }
            .toList
          sent.collect { case NewBlock(block, totalDifficulty) => (block, totalDifficulty) }.toSet shouldBe
            Set(told -> BigInt(2222))
          // the side parent is announced by no message: neither in full nor by its hash
          sent.collect { case NewBlockHashes(hashes) => hashes.map(_.hash) }.flatten should
            not contain sideParent.hash.value
          // both blocks are canonical now: the pool drops its copies of the transactions of each. Told by their
          // transactions: goToTop's batches tell the pool of their own blocks too (B-448)
          val toThePool: Seq[Any] = pendingTransactionsManager.receiveWhile(max = 5.seconds, idle = 1.second) {
            case message => message
          }
          val removed: Seq[Seq[SignedTransaction]] = toThePool.collect {
            case com.chipprbots.ethereum.transactions.PendingTransactionsManager.RemoveTransactions(txs) => Seq(txs)
            case com.chipprbots.ethereum.transactions.PendingTransactionsManager.HeadReset(included, _)  => included
          }.flatten
          val ofTheseTwo: Seq[Seq[SignedTransaction]] = Seq(sideParent.body.transactionList, told.body.transactionList)
          removed.filter(ofTheseTwo.contains) should contain theSameElementsAs ofTheseTwo
          // a new-head event for the executed head, none for the side parent (core-geth's chain event); the events
          // before are goToTop's own imports
          // compared by equality: the message Topic.Publish builds has no public extractor
          val ofTheHead: Topic.Command[NewBlockImported] = Topic.Publish(NewBlockImported(told))
          val published: Seq[Topic.Command[NewBlockImported]] = newHeads.fishForMessage(5.seconds) { message =>
            if message == ofTheHead then FishingOutcome.Complete else FishingOutcome.Continue
          }
          published.last shouldBe ofTheHead
          published should not contain Topic.Publish(NewBlockImported(sideParent))
          newHeads.expectNoMessage(1.second)
      )
    }

    // B-448: a fetched batch is told as core-geth tells what its downloader inserts. New-head subscribers get each block
    // the import executed and the pool is updated, as for an announced block; no peer is told of it.
    "importing a fetched batch" should {
      "tell new-head subscribers and the pool of each block, and no peer" taggedAs (UnitTest, SyncTest) in sync(
        new BatchImportTells("batch-to-top"):
          val batch: List[Block] = testBlocks.take(3)
          answer.set(BlockImportedToTop(batch.map(weighed)))

          importBatch(batch)

          newHeads.receiveMessages(3, 5.seconds) shouldBe batch.map(b => Topic.Publish(NewBlockImported(b)))
          removedFromThePool() shouldBe batch.map(_.body.transactionList)
          takenBackByThePool() shouldBe empty
          noneTold()
      )

      "tell of a reorganisation: the blocks moved off back to the pool, new-head events for the executed only" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("batch-reorg"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          // a stored side block the branch stands on, and the two blocks of the batch executed by this import
          val sideParent: Block = testBlocks.head
          val executed: List[Block] = testBlocks.slice(1, 3)
          val onto: List[Block] = sideParent :: executed
          answer.set(ChainReorganised(List(movedOff), onto, onto.map(weighed).map(_.weight), branchBlocks = 2))

          importBatch(executed)

          newHeads.receiveMessages(2, 5.seconds) shouldBe executed.map(b => Topic.Publish(NewBlockImported(b)))
          removedFromThePool() shouldBe onto.map(_.body.transactionList)
          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          noneTold()
      )

      // B-253: go-ethereum's pool reset takes back `types.TxDifference(discarded, included)`, not every moved-off tx
      "take back none of the moved-off txs the new chain includes" taggedAs (UnitTest, SyncTest) in sync(
        new BatchImportTells("batch-reorg-shared"):
          val sideParent: Block = testBlocks.head
          val executed: List[Block] = testBlocks.slice(1, 3)
          val onto: List[Block] = sideParent :: executed
          val shared: SignedTransaction = executed.head.body.transactionList.head
          val ownBlock: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          val movedOff: Block =
            BlockHelpers.withTransactions(ownBlock, shared :: ownBlock.body.transactionList.toList)
          answer.set(ChainReorganised(List(movedOff), onto, onto.map(weighed).map(_.weight), branchBlocks = 2))

          importBatch(executed)

          newHeads.receiveMessages(2, 5.seconds) shouldBe executed.map(b => Topic.Publish(NewBlockImported(b)))
          removedFromThePool() shouldBe onto.map(_.body.transactionList)
          takenBackByThePool() shouldBe List(ownBlock.body.transactionList)
          noneTold()
      )

      // B-336: a fault of this node's own state after a prefix of the batch became the head. The prefix is told as
      // imported blocks are; the failing block and those after it are not on the chain and are not told.
      "tell of the imported prefix only when this node's state fails a later block of the batch" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("batch-prefix"):
          val prefix: Block = testBlocks.head
          val failing: Block = testBlocks(1)
          val localFault: String = BlockExecutionError.MPTError(new MPTException("Invalid Node")).toString
          answer.set(new BlockImportFailedAt(localFault, failing.hash.value, List(weighed(prefix))))

          importBatch(testBlocks.take(3))

          newHeads.receiveMessages(1, 5.seconds) shouldBe List(Topic.Publish(NewBlockImported(prefix)))
          removedFromThePool() shouldBe List(prefix.body.transactionList)
          noneTold()
      )

      // B-338: a batch whose block k failed on its own error after blocks 1..k-1 extended the head. core-geth's
      // downloader inserts the prefix (the pool resets on the new head) and drops the peer that served the failing
      // block. The prefix is told once, as any imported blocks are; the fetcher goes back to the failing block and
      // blames its peer, as for a batch whose first block failed; the blocks after it are not imported or evaluated.
      "tell of the prefix once and blame the failing block's peer when a later block of the batch is invalid" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("batch-partial-extension"):
          val batch: List[Block] = testBlocks.take(4)
          val prefix: List[Block] = batch.take(2)
          val failing: Block = batch(2)
          val invalid: String = BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString
          answer.set(ImportedPartially(BlockImportedToTop(prefix.map(weighed)), failing.hash.value, invalid))

          importBatch(batch)

          newHeads.receiveMessages(2, 5.seconds) shouldBe prefix.map(b => Topic.Publish(NewBlockImported(b)))
          val rewound: InvalidateBlocksFrom = fetcherRewound()
          rewound.fromBlock shouldBe failing.number.value
          withClue("the peer that served the failing block is blamed, as for a batch whose first block failed: ") {
            rewound.toBlacklist shouldBe Some(failing.number.value)
          }
          progressReported() shouldBe prefix.last.number.value
          removedFromThePool() shouldBe prefix.map(_.body.transactionList)
          takenBackByThePool() shouldBe empty
          resetsOfThePool() shouldBe 1
          withClue("the rest of the batch is dropped, not evaluated again: ")(evaluations.get shouldBe 1)
          noneTold()
      )

      // B-338: the same when the prefix outweighed the head and replaced it, then a block failed. The blocks moved off
      // are taken back by the pool as for a reorganisation that went through (B-253's reset), in the same one reset.
      "tell of the reorganisation once and blame the failing block's peer when a later block of the branch is invalid" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("batch-partial-reorg"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          // a stored side block the branch stands on, then the batch: two blocks executed, the failing one, one after
          val sideParent: Block = testBlocks.head
          val batch: List[Block] = testBlocks.slice(1, 5)
          val executed: List[Block] = batch.take(2)
          val failing: Block = batch(2)
          val onto: List[Block] = sideParent :: executed
          val invalid: String =
            s"${Consensus.ReorganiseFailurePrefix}${BlockExecutionError.ValidationAfterExecError("Block has invalid state root")}"
          answer.set(
            ImportedPartially(
              ChainReorganised(List(movedOff), onto, onto.map(weighed).map(_.weight), branchBlocks = 2),
              failing.hash.value,
              invalid
            )
          )

          importBatch(batch)

          newHeads.receiveMessages(2, 5.seconds) shouldBe executed.map(b => Topic.Publish(NewBlockImported(b)))
          val rewound: InvalidateBlocksFrom = fetcherRewound()
          rewound.fromBlock shouldBe failing.number.value
          rewound.toBlacklist shouldBe Some(failing.number.value)
          progressReported() shouldBe executed.last.number.value
          removedFromThePool() shouldBe onto.map(_.body.transactionList)
          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          resetsOfThePool() shouldBe 1
          evaluations.get shouldBe 1
          noneTold()
      )

      // beacon N2 (hive "Invalid P9", B-338): a gas-used mismatch at the batch's last block, after the blocks before it
      // extended the head, following a consensus layer. The gas-used arm reads the failing block from the partial
      // result and reports it, with its parent as the latest valid block, exactly once: the importer counts the
      // prefix as imported, so the failing block heads what was not, and nothing behind it is reported
      "report the block a gas-used mismatch failed on once, with its parent as latest valid, after a partial extension" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("batch-partial-gas-used", followsConsensusLayer = true, reporting = true):
          val batch: List[Block] = testBlocks.take(9)
          val p8: Block = batch(7)
          val p9: Block = batch(8)
          val gasUsed: String =
            BlockExecutionError
              .ValidationAfterExecError("Block has invalid gas used, expected 21000 but got 0")
              .toString
          answer.set(ImportedPartially(BlockImportedToTop(batch.take(8).map(weighed)), p9.hash.value, gasUsed))

          importBatch(batch)

          newHeads.receiveMessages(8, 5.seconds) shouldBe batch.take(8).map(b => Topic.Publish(NewBlockImported(b)))
          val rewound: InvalidateBlocksFrom = fetcherRewound()
          rewound.fromBlock shouldBe p9.number.value
          withClue("following a consensus layer, the peer that served the branch is not blamed (B-513): ") {
            rewound.toBlacklist shouldBe None
          }
          noneTold()
          reportedInvalid.toArray.toList shouldBe List(p9.hash.value -> p8.hash.value)
      )

      // B-513 (beacon W2): following a consensus layer, the branch is the one it named, which every peer serves alike:
      // the failing block's peer is not blamed; the fetcher still goes back to the failing block
      "blame no peer for a partial import when it follows a consensus layer" taggedAs (UnitTest, SyncTest) in sync(
        new BatchImportTells("batch-partial-follows-cl", followsConsensusLayer = true):
          val batch: List[Block] = testBlocks.take(4)
          val failing: Block = batch(2)
          val invalid: String = BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString
          answer.set(ImportedPartially(BlockImportedToTop(batch.take(2).map(weighed)), failing.hash.value, invalid))

          importBatch(batch)

          val rewound: InvalidateBlocksFrom = fetcherRewound()
          rewound.fromBlock shouldBe failing.number.value
          rewound.toBlacklist shouldBe None
          resetsOfThePool() shouldBe 1
      )

      // B-513: the same for a batch whose first block is invalid, for the strikes against it, and for an announced
      // block that is invalid. Following a consensus layer no peer is blacklisted or evicted; the fetcher is still
      // rewound to the failing block. On a proof-of-work chain (ETC) the peer is blamed at once and evicted at the
      // third strike, as before
      for follows <- Seq(false, true) do
        s"blame the peer of an invalid batch, and evict it at the third strike, only on a proof-of-work chain (follows a consensus layer: $follows)" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new BatchImportTells(s"batch-invalid-$follows", followsConsensusLayer = follows):
            val batch: List[Block] = testBlocks.take(2)
            val invalid: String = BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString
            answer.set(BlockImportFailed(invalid))
            val evictions = new java.util.concurrent.atomic.AtomicInteger(0)
            // the rewind, then the pick the importer asks for once the import is done: a batch handed to it before
            // that is deferred while it imports, following a consensus layer, and dropped with the rewind
            def rewoundOnce(): InvalidateBlocksFrom =
              var rewound: Option[InvalidateBlocksFrom] = None
              importerFetcher.fishForMessage(5.seconds) {
                case _: BlockFetcher.BlockImportFailed =>
                  evictions.incrementAndGet()
                  false
                case invalidated: InvalidateBlocksFrom =>
                  rewound = Some(invalidated)
                  false
                case _: BlockFetcher.PickBlocks => rewound.isDefined
                case _                          => false
              }
              rewound.get

            for strike <- 1 to 3 do
              importBatch(batch)
              val rewound = rewoundOnce()
              withClue(s"strike $strike: ") {
                rewound.fromBlock shouldBe batch.head.number.value
                rewound.toBlacklist shouldBe (if follows then None else Some(batch.head.number.value))
              }
            withClue("the peer evicted at the third strike: ")(evictions.get shouldBe (if follows then 0 else 1))
        )

        s"blame the peer of an invalid announced block only on a proof-of-work chain (follows a consensus layer: $follows)" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new BatchImportTells(s"announce-invalid-$follows", followsConsensusLayer = follows):
            val announced: Block = testBlocks.head
            answer.set(
              BlockImportFailed(BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString)
            )

            importer ! BlockImporter.ImportNewBlock(announced, PeerId("announcer"))

            importerFetcher.fishForMessage(5.seconds) {
              case BlockFetcher.BlockImportFailed(number, _) if !follows =>
                number shouldBe announced.number.value
                true
              case InvalidateBlocksFrom(number, _, toBlacklist) if follows =>
                number shouldBe announced.number.value
                toBlacklist shouldBe None
                true
              case _: BlockFetcher.BlockImportFailed | _: InvalidateBlocksFrom =>
                fail("the fetcher was told of the failure in the other chain's way")
              case _ => false
            }
        )

      // B-513 (beacon W3): an announced block on a queued branch whose block 2 failed after block 1 became the head.
      // The fetcher is told of the failing block, whose peer served it, not of the announced block
      for follows <- Seq(false, true) do
        s"tell the fetcher of the failing block, not the announced one, after a partial import (follows a consensus layer: $follows)" taggedAs (
          UnitTest,
          SyncTest
        ) in sync(
          new BatchImportTells(s"announce-partial-$follows", followsConsensusLayer = follows):
            val branch: List[Block] = testBlocks.take(4)
            val failing: Block = branch(1)
            val invalid: String = BlockExecutionError.ValidationAfterExecError("Block has invalid state root").toString
            answer.set(ImportedPartially(BlockImportedToTop(List(weighed(branch.head))), failing.hash.value, invalid))

            importer ! BlockImporter.ImportNewBlock(branch.last, PeerId("announcer"))

            importerFetcher.fishForMessage(5.seconds) {
              case BlockFetcher.BlockImportFailed(number, _) if !follows =>
                number shouldBe failing.number.value
                true
              case InvalidateBlocksFrom(number, _, toBlacklist) if follows =>
                number shouldBe failing.number.value
                toBlacklist shouldBe None
                true
              case _: BlockFetcher.BlockImportFailed | _: InvalidateBlocksFrom =>
                fail("the fetcher was told of the failure in the other chain's way")
              case _ => false
            }
            resetsOfThePool() shouldBe 1
        )

      // B-460: the branch resolution handed the predicted old branch's transactions to the pool before the import, on
      // the head as it was, even when the import then failed or kept the head. go-ethereum's pool is reset on the head
      // the import left (LegacyPool.reset): the blocks moved off are taken back once, after the move, and only if the
      // head moved off them. Each arm, with the resolution predicting `movedOff` as the old branch:
      "take back the moved-off txs once, after a reorganisation, none ahead of it" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("b460-full"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          predictedOldBranch.set(List(movedOff))
          val executed: List[Block] = testBlocks.take(2)
          answer.set(ChainReorganised(List(movedOff), executed, executed.map(weighed).map(_.weight), branchBlocks = 2))

          importBatch(executed)

          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          handedBackAheadOfAReset() shouldBe false
          resetsOfThePool() shouldBe 1
      )

      "take back the moved-off txs once after a partial reorganisation (B-338)" taggedAs (UnitTest, SyncTest) in sync(
        new BatchImportTells("b460-partial"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          predictedOldBranch.set(List(movedOff))
          val batch: List[Block] = testBlocks.take(3)
          val executed: List[Block] = batch.take(2)
          val invalid: String =
            s"${Consensus.ReorganiseFailurePrefix}${BlockExecutionError.ValidationAfterExecError("Block has invalid state root")}"
          answer.set(
            ImportedPartially(
              ChainReorganised(List(movedOff), executed, executed.map(weighed).map(_.weight), branchBlocks = 2),
              batch(2).hash.value,
              invalid
            )
          )

          importBatch(batch)

          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          handedBackAheadOfAReset() shouldBe false
          resetsOfThePool() shouldBe 1
      )

      "tell the pool nothing when the reorganisation failed and the head stayed" taggedAs (UnitTest, SyncTest) in sync(
        new BatchImportTells("b460-failed"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          predictedOldBranch.set(List(movedOff))
          val batch: List[Block] = testBlocks.take(3)
          val invalid: String =
            s"${Consensus.ReorganiseFailurePrefix}${BlockExecutionError.ValidationAfterExecError("Block has invalid state root")}"
          answer.set(new BlockImportFailedAt(invalid, batch(1).hash.value))

          importBatch(batch)

          // the import is done once the fetcher is sent back
          fetcherRewound()
          poolToldNothing()
      )

      "tell the pool nothing when the branch was not the heavier and the head was kept" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("b460-kept"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          predictedOldBranch.set(List(movedOff))
          // KeptCurrentBestBranch: the adapter queues the branch and answers it enqueued
          answer.set(BlockEnqueued)

          importBatch(testBlocks.take(2))

          importerFetcher.awaitCond(evaluations.get == 1, 5.seconds)
          poolToldNothing()
      )

      "take back the moved-off txs once when this node's state failed a block after a reorganisation's prefix became the head" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("b460-local-fault"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          predictedOldBranch.set(List(movedOff))
          val batch: List[Block] = testBlocks.take(3)
          val prefix: Block = batch.head
          val localFault: String =
            s"${Consensus.ReorganiseFailurePrefix}${BlockExecutionError.MPTError(new MPTException("Invalid Node"))}"
          answer.set(new BlockImportFailedAt(localFault, batch(1).hash.value, List(weighed(prefix)), List(movedOff)))

          importBatch(batch)

          removedFromThePool() shouldBe List(prefix.body.transactionList)
          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          handedBackAheadOfAReset() shouldBe false
          resetsOfThePool() shouldBe 1
      )

      "take back the moved-off txs once when this node's state could not take a block after a reorganisation's prefix" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("b460-state-fault"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          predictedOldBranch.set(List(movedOff))
          val batch: List[Block] = testBlocks.take(3)
          val prefix: Block = batch.head
          answer.set(
            new StateFaultAfterAMove(
              new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the head moved under the batch"),
              List(weighed(prefix)),
              List(movedOff)
            )
          )

          importBatch(batch)

          removedFromThePool() shouldBe List(prefix.body.transactionList)
          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          handedBackAheadOfAReset() shouldBe false
          resetsOfThePool() shouldBe 1
      )

      // B-514 (beacon W4): a move back that failed left the head on side blocks the branch stands on, off the chain it
      // left. The pool is reset onto them, as a move onto them would reset it: their txs leave it, those of the chain
      // left come back, in one reset; none of them is counted as imported or told to new-head subscribers
      "reset the pool onto the side blocks a move back that failed left the head on" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("b514-side-blocks"):
          val movedOff: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          val side: Block = BlockHelpers.generateBlock(BlockHelpers.genesis)
          answer.set(
            new StateFaultAfterAMove(
              new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the move back failed"),
              Nil,
              List(movedOff),
              List(side)
            )
          )

          importBatch(testBlocks.take(3))

          removedFromThePool() shouldBe List(side.body.transactionList)
          takenBackByThePool() shouldBe List(movedOff.body.transactionList)
          resetsOfThePool() shouldBe 1
          withClue("the head the pool was reset to, for the fork choice's next reset (B-515): ") {
            txPoolHead.current shouldBe Some(side.header)
          }
          noneTold()
      )

      // B-515 (beacon W1): the head the importer's move left is recorded as the pool's, in the record the fork choice
      // resets from, so that a forkchoiceUpdated after it resets the pool from there
      "record the head a batch moved to as the one the pool was reset to" taggedAs (UnitTest, SyncTest) in sync(
        new BatchImportTells("b515-pool-head"):
          val batch: List[Block] = testBlocks.take(2)
          answer.set(BlockImportedToTop(batch.map(weighed)))

          importBatch(batch)

          resetsOfThePool() shouldBe 1
          txPoolHead.current shouldBe Some(batch.last.header)
      )

      // B-515: a fork choice resets the pool between the import's commit and the importer's tell, onto a head above the
      // batch. The importer resets from the head the pool was last reset to, not from the batch's own branches: from
      // the fork choice's head onto the head the store holds, which is that one, so it tells nothing more, and the
      // pool and its recorded head end on the final head
      "reset nothing more when a fork choice reset the pool between the import's commit and its tell" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("b515-interleaved"):
          import com.chipprbots.ethereum.transactions.PendingTransactionsManager.HeadReset
          import com.chipprbots.ethereum.transactions.TxPoolReset
          val batch: List[Block] = testBlocks.take(2)
          val above: Block = testBlocks(2)
          // the pool stands on genesis, where the batch starts
          txPoolHead.told(Some(BlockHelpers.genesis.header), pool.ref, TxPoolReset.Reset.empty)
          duringImport.set { () =>
            // the import commits the batch, and the fork choice moves the head on to `above` and resets the pool
            (batch :+ above).foldLeft(ChainWeight.totalDifficultyOnly(0)) { (weight, block) =>
              val next = weight.increase(block.header)
              blockchainWriter.save(block, Nil, next, saveAsBestBlock = true)
              next
            }
            txPoolHead.resetOnto(above.header, pool.ref)(from =>
              TxPoolReset.between(from, above.header, blockchainReader)
            )
          }
          answer.set(BlockImportedToTop(batch.map(weighed)))

          importBatch(batch)

          // the fork choice's reset, of the three blocks, and none after it
          pool.expectMessage(5.seconds, HeadReset((batch :+ above).map(_.body.transactionList), Nil))
          newHeads.receiveMessages(2, 5.seconds)
          pool.expectNoMessage(500.millis)
          txPoolHead.current shouldBe Some(above.header)
      )

      // Spec 018 C4b: the same for a state fault, which carries the adopted prefix itself
      "tell of the adopted prefix only when this node's state could not take a later block" taggedAs (
        UnitTest,
        SyncTest
      ) in sync(
        new BatchImportTells("batch-state-fault"):
          val prefix: List[Block] = testBlocks.take(2)
          answer.set(
            BlockImportStateFault(
              new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the head moved under the batch"),
              prefix.map(weighed)
            )
          )

          importBatch(testBlocks.take(3))

          newHeads.receiveMessages(2, 5.seconds) shouldBe prefix.map(b => Topic.Publish(NewBlockImported(b)))
          removedFromThePool() shouldBe prefix.map(_.body.transactionList)
          noneTold()
      )
    }

    "broadcasting blocks" should {
      "send an ETH NewBlock message to broadcast newly imported blocks" in sync(
        new OnTopFixture:
          val peerWithETH63: (Peer, PeerInfo) =
            val id = peerId(handshakedPeers.size)
            val peer = getPeer(id)
            val peerInfo = getPeerInfo(peer, Capability.ETH63)
            (peer, peerInfo)

          networkPeerManager.setAutoPilot(
            new AutoPilot:
              def run(sender: ActorRef, msg: Any): AutoPilot = msg match
                case cmd: GetHandshakedPeersCmd =>
                  cmd.replyTo ! HandshakedPeers(Map(peerWithETH63._1 -> peerWithETH63._2))
                  this
                case _ => this
          )

          goToTop()

          sendNewBlock()
          awaitCond(importedNewBlock)

          networkPeerManager.fishForSpecificMessageMatching(max = 10.seconds) {
            case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
              message.underlyingMsg match
                case ETHPackets.NewBlock(`newBlock`, _) => true
                case _                                  => false
            case _ => false
          }
      )

    }

    "reporting progress" should {
      "return NotSyncing until fetching started" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- IO(regularSync ! SyncProtocol.Start)
          before <- getSyncStatus
          _ <- IO {
            val sub766 = peerEventBus.expectMsgType[SubscribeCmd]
            sub766.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )
          }
          after <- getSyncStatus
        yield
          assert(before === Status.NotSyncing)
          assert(after === Status.NotSyncing)
      }

      "return initial status after fetching first batch of data" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- testBlocks
            .take(5)
            .traverse(block =>
              IO(blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(10000), saveAsBestBlock = true))
            )
          _ <- IO {
            regularSync ! SyncProtocol.Start

            val sub796 = peerEventBus.expectMsgType[SubscribeCmd]
            sub796.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )

            peersClient.expectMsgEq(blockHeadersRequest(6)).replyTo ! PeersClient.Response(
              defaultPeer,
              BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
            )
          }
          status <- pollForStatus(_.syncing)
        yield
          val lastBlock = testBlocks.last.number.value
          assert(status === Status.Syncing(5, Progress(5, lastBlock), None))
      }

      "return initial status after fetching first batch of data when starting from genesis" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- IO {
            regularSync ! SyncProtocol.Start

            val sub824 = peerEventBus.expectMsgType[SubscribeCmd]
            sub824.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )

            peersClient.expectMsgEq(blockHeadersChunkRequest(0)).replyTo ! PeersClient.Response(
              defaultPeer,
              BlockHeaders(BigInt(0), testBlocksChunked.head.headers)
            )
          }
          status <- pollForStatus(_.syncing)
          lastBlock = testBlocks.last.number.value
        yield assert(status === Status.Syncing(0, Progress(0, lastBlock), None))
      }

      "return updated status after importing blocks" in testCaseT { fixture =>
        import fixture.*

        for
          _ <- IO {
            // BlockImporter imports a batch through evaluateBranch and reports progress for the blocks listed in
            // BlockImportedToTop's data, as ConsensusAdapter fills it from ExtendedCurrentBestBranch. An empty list
            // means nothing was imported, so the stub names each block it imports.
            testBlocks
              .take(5)
              .foreach(block =>
                setImportResult(
                  block,
                  IO(
                    BlockImportedToTop(List(BlockData(block, Nil, ChainWeight.totalDifficultyOnly(block.number.value))))
                  )
                )
              )

            peersClient.setAutoPilot(new PeersClientAutoPilot(testBlocks.take(5)))

            regularSync ! SyncProtocol.Start

            val sub854 = peerEventBus.expectMsgType[SubscribeCmd]
            sub854.subscriber ! MessageFromPeer(
              NewBlock(
                testBlocks.last,
                ChainWeight.totalDifficultyOnly(testBlocks.last.number.value).totalDifficulty.value
              ),
              defaultPeer.id
            )
          }
          _ <- fishForStatus {
            case s: Status.Syncing
                if s.blocksProgress.current >= 5 && s.blocksProgress.target == 20 && s.startingBlockNumber == 0 =>
              s
          }
        yield succeed
      }

      "return SyncDone when on top" in customTestCaseResourceM(onTopFixtureResource) { fixture =>
        import fixture.*

        for
          _ <- IO(goToTop())
          status <- getSyncStatus
        yield assert(status === Status.SyncDone)
      }
    }

    // RS-1: ProgressState.toStatus guard — bestKnownNetworkBlock=0 must not produce SyncDone
    "ProgressState.toStatus" should {
      import RegularSync.ProgressState
      import scala.concurrent.Future

      "return NotSyncing when not yet started" taggedAs (UnitTest, SyncTest) in {
        val state =
          ProgressState(startedFetching = false, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0)
        Future.successful(assert(state.toStatus === Status.NotSyncing))
      }

      "return NotSyncing when started but bestKnownNetworkBlock is 0 (no peers seen yet)" taggedAs (
        UnitTest,
        SyncTest
      ) in {
        // RS-1 regression: before the fix, startedFetching=true and currentBlock(0) >= bestKnownNetworkBlock(0)
        // incorrectly triggered SyncDone before any peer had announced a block.
        val state = ProgressState(startedFetching = true, initialBlock = 0, currentBlock = 0, bestKnownNetworkBlock = 0)
        Future.successful(assert(state.toStatus === Status.NotSyncing))
      }

      "return Syncing when started and behind chain head" taggedAs (UnitTest, SyncTest) in {
        val state =
          ProgressState(startedFetching = true, initialBlock = 5, currentBlock = 10, bestKnownNetworkBlock = 100)
        Future.successful(assert(state.toStatus === Status.Syncing(5, Progress(10, 100), None)))
      }

      "return SyncDone when started and caught up to chain head" taggedAs (UnitTest, SyncTest) in {
        val state =
          ProgressState(startedFetching = true, initialBlock = 0, currentBlock = 50, bestKnownNetworkBlock = 50)
        Future.successful(assert(state.toStatus === Status.SyncDone))
      }
    }
  }

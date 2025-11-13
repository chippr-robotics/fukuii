package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.pattern.ask
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestKitBase
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.Eq
import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits._

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.math.BigInt
import scala.reflect.ClassTag

import fs2.Stream
import fs2.concurrent.Topic
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync._
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.blocks.CheckpointBlockGenerator
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger._
import com.chipprbots.ethereum.network.EtcPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.EtcPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.Subscribe
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETC64.NewBlock
import com.chipprbots.ethereum.network.p2p.messages.ETH62._
import com.chipprbots.ethereum.network.p2p.messages.ETH63.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

// Fixture classes are wrapped in a trait due to problems with making mocks available inside of them
trait RegularSyncFixtures { self: Matchers with AsyncMockFactory =>
  class RegularSyncFixture(_system: ActorSystem)
      extends TestKitBase
      with EphemBlockchainTestSetup
      with TestSyncConfig
      with SecureRandomBuilder {
    implicit lazy val timeout: Timeout = remainingOrDefault
    implicit override lazy val system: ActorSystem = _system
    implicit override lazy val ioRuntime: IORuntime = IORuntime.global
    override lazy val syncConfig: SyncConfig =
      defaultSyncConfig.copy(blockHeadersPerRequest = 2, blockBodiesPerRequest = 2)
    val handshakedPeers: Map[Peer, PeerInfo] =
      (0 to 5).toList.map((peerId _).andThen(getPeer)).fproduct(getPeerInfo(_)).toMap
    val defaultPeer: Peer = peerByNumber(0)

    val etcPeerManager: TestProbe = TestProbe()
    val peerEventBus: TestProbe = TestProbe()
    val ommersPool: TestProbe = TestProbe()
    val pendingTransactionsManager: TestProbe = TestProbe()
    val checkpointBlockGenerator: CheckpointBlockGenerator = new CheckpointBlockGenerator()
    val peersClient: TestProbe = TestProbe()
    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)
    lazy val branchResolution = new BranchResolution(blockchainReader)

    val stateStorage: StateStorage = stub[StateStorage]

    lazy val regularSync: ActorRef = system.actorOf(
      RegularSync
        .props(
          peersClient.ref,
          etcPeerManager.ref,
          peerEventBus.ref,
          consensusAdapter,
          blockchainReader,
          stateStorage,
          branchResolution,
          validators.blockValidator,
          blacklist,
          syncConfig,
          ommersPool.ref,
          pendingTransactionsManager.ref,
          system.scheduler,
          this
        )
        .withDispatcher("pekko.actor.default-dispatcher")
    )

    val defaultTd = 12345

    val testBlocks: List[Block] = BlockHelpers.generateChain(20, BlockHelpers.genesis)
    val testBlocksChunked: List[List[Block]] = testBlocks.grouped(syncConfig.blockHeadersPerRequest).toList

    override lazy val consensusAdapter: ConsensusAdapter = {
      val adapter = stub[ConsensusAdapter]
      (adapter
        .evaluateBranchBlock(_: Block)(_: IORuntime, _: BlockchainConfig))
        .when(*, *, *)
        .onCall { case (block: Block, _, _) =>
          importedBlocksSet.add(block)
          results.getOrElse(block.header.hash, IO.pure(BlockEnqueued)).flatTap(_ => importedBlocksSubject.publish1(block).void)
        }
      adapter
    }

    blockchainWriter.save(
      block = BlockHelpers.genesis,
      receipts = Nil,
      weight = ChainWeight.totalDifficultyOnly(10000),
      saveAsBestBlock = true
    )
    // scalastyle:on magic.number

    def done(): Unit =
      regularSync ! PoisonPill

    def peerId(number: Int): PeerId = PeerId(s"peer_$number")

    def getPeer(id: PeerId): Peer =
      Peer(id, new InetSocketAddress("127.0.0.1", 0), TestProbe(id.value).ref, incomingConnection = false)

    def getPeerInfo(
        peer: Peer,
        capability: Capability = Capability.ETC64
    ): PeerInfo = {
      val status =
        RemoteStatus(
          capability,
          1,
          ChainWeight.totalDifficultyOnly(1),
          ByteString(s"${peer.id}_bestHash"),
          ByteString("unused")
        )
      PeerInfo(
        status,
        forkAccepted = true,
        chainWeight = status.chainWeight,
        maxBlockNumber = 0,
        bestBlockHash = status.bestHash
      )
    }

    def peerByNumber(number: Int): Peer = handshakedPeers.keys.toList.sortBy(_.id.value).apply(number)

    def blockHeadersChunkRequest(fromChunk: Int): PeersClient.Request[GetBlockHeaders] = {
      val block = testBlocksChunked(fromChunk).headNumberUnsafe
      blockHeadersRequest(block)
    }

    def blockHeadersRequest(fromBlock: BigInt): PeersClient.Request[GetBlockHeaders] = PeersClient.Request.create(
      GetBlockHeaders(
        Left(fromBlock),
        syncConfig.blockHeadersPerRequest,
        skip = 0,
        reverse = false
      ),
      PeersClient.BestPeer
    )

    def fishForBlacklistPeer(peer: Peer): PeersClient.BlacklistPeer =
      peersClient.fishForSpecificMessage() {
        case msg @ PeersClient.BlacklistPeer(id, _) if id == peer.id => msg
      }

    val getSyncStatus: IO[SyncProtocol.Status] =
      IO.fromFuture(IO((regularSync ? SyncProtocol.GetStatus).mapTo[SyncProtocol.Status]))

    def pollForStatus(predicate: SyncProtocol.Status => Boolean): IO[SyncProtocol.Status] = Stream
      .repeatEval(getSyncStatus.delayBy(10.millis))
      .takeThrough(predicate.andThen(!_))
      .compile
      .last
      .flatMap {
        case Some(status) => IO.pure(status)
        case None         => IO.raiseError(new RuntimeException("No status found"))
      }
      .timeout(remainingOrDefault)

    def fishForStatus[B](picker: PartialFunction[SyncProtocol.Status, B]): IO[B] = Stream
      .repeatEval(getSyncStatus.delayBy(10.millis))
      .collect(picker)
      .head
      .compile
      .lastOrError
      .timeout(remainingOrDefault)

    protected val results: mutable.Map[ByteString, IO[BlockImportResult]] =
      mutable.Map[ByteString, IO[BlockImportResult]]()
    protected val importedBlocksSet: mutable.Set[Block] = mutable.Set[Block]()
    private val importedBlocksTopicIO = Topic[IO, Block]
    private lazy val importedBlocksSubject = importedBlocksTopicIO.unsafeRunSync()
    val importedBlocks: Stream[IO, Block] = importedBlocksSubject.subscribe(100)

    def didTryToImportBlock(predicate: Block => Boolean): Boolean =
      importedBlocksSet.exists(predicate)

    def didTryToImportBlock(block: Block): Boolean =
      didTryToImportBlock(_.hash == block.hash)

    def bestBlock: Block = importedBlocksSet.maxBy(_.number)

    def setImportResult(block: Block, result: IO[BlockImportResult]): Unit =
      results(block.header.hash) = result

    class PeersClientAutoPilot(blocks: List[Block] = testBlocks) extends AutoPilot {

      def run(sender: ActorRef, msg: Any): AutoPilot =
        overrides(sender).orElse(defaultHandlers(sender)).apply(msg).getOrElse(defaultAutoPilot)

      def overrides(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = PartialFunction.empty

      def defaultHandlers(sender: ActorRef): PartialFunction[Any, Option[AutoPilot]] = {
        case PeersClient.Request(GetBlockHeaders(Left(minBlock), amount, _, _), _, _) =>
          val maxBlock = minBlock + amount
          val matchingHeaders = blocks
            .filter { b =>
              val nr = b.number
              minBlock <= nr && nr < maxBlock
            }
            .map(_.header)
            .sortBy(_.number)
          sender ! PeersClient.Response(defaultPeer, BlockHeaders(matchingHeaders))
          None
        case PeersClient.Request(GetBlockBodies(hashes), _, _) =>
          val matchingBodies = hashes.flatMap(hash => blocks.find(_.hash == hash)).map(_.body)

          sender ! PeersClient.Response(defaultPeer, BlockBodies(matchingBodies))
          None
        case PeersClient.Request(GetNodeData(hash :: Nil), _, _) =>
          sender ! PeersClient.Response(
            defaultPeer,
            NodeData(List(ByteString(blocks.byHashUnsafe(hash).header.toBytes: Array[Byte])))
          )
          None
        case _ => None
      }

      def defaultAutoPilot: AutoPilot = this
    }

    implicit class ListOps[T](list: List[T]) {

      def get(index: Int): Option[T] =
        if (list.isDefinedAt(index)) {
          Some(list(index))
        } else {
          None
        }
    }

    // TODO: consider extracting it somewhere closer to domain
    implicit class BlocksListOps(blocks: List[Block]) {
      def headNumberUnsafe: BigInt = blocks.head.number
      def headNumber: Option[BigInt] = blocks.headOption.map(_.number)
      def headers: List[BlockHeader] = blocks.map(_.header)
      def hashes: List[ByteString] = headers.map(_.hash)
      def bodies: List[BlockBody] = blocks.map(_.body)
      def numbers: List[BigInt] = blocks.map(_.number)
      def numberAt(index: Int): Option[BigInt] = blocks.get(index).map(_.number)
      def numberAtUnsafe(index: Int): BigInt = numberAt(index).get
      def byHash(hash: ByteString): Option[Block] = blocks.find(_.hash == hash)
      def byHashUnsafe(hash: ByteString): Block = byHash(hash).get
    }

    // TODO: consider extracting it into common test environment
    implicit class TestProbeOps(probe: TestProbe) {

      def expectMsgEq[T: Eq](msg: T): T = expectMsgEq(remainingOrDefault, msg)

      def expectMsgEq[T: Eq](max: FiniteDuration, msg: T): T = {
        val received = probe.expectMsgClass(max, msg.getClass)
        assert(Eq[T].eqv(received, msg), s"Expected ${msg}, got ${received}")
        received
      }

      def fishForSpecificMessageMatching[T](
          max: FiniteDuration = probe.remainingOrDefault
      )(predicate: Any => Boolean): T =
        probe.fishForSpecificMessage(max) {
          case msg if predicate(msg) => msg.asInstanceOf[T]
        }

      def fishForMsgEq[T: Eq: ClassTag](msg: T, max: FiniteDuration = probe.remainingOrDefault): T =
        probe.fishForSpecificMessageMatching[T](max)(x =>
          implicitly[ClassTag[T]].runtimeClass.isInstance(x) && Eq[T].eqv(msg, x.asInstanceOf[T])
        )

      def expectMsgAllOfEq[T1: Eq, T2: Eq](msg1: T1, msg2: T2): (T1, T2) =
        expectMsgAllOfEq(remainingOrDefault, msg1, msg2)

      def expectMsgAllOfEq[T1: Eq, T2: Eq](max: FiniteDuration, msg1: T1, msg2: T2): (T1, T2) = {
        val received = probe.receiveN(2, max)
        (
          received.find(m => Eq[T1].eqv(msg1, m.asInstanceOf[T1])).get.asInstanceOf[T1],
          received.find(m => Eq[T2].eqv(msg2, m.asInstanceOf[T2])).get.asInstanceOf[T2]
        )
      }
    }

    implicit def eqInstanceForPeersClientRequest[T <: Message]: Eq[PeersClient.Request[T]] =
      (x, y) => x.message == y.message && x.peerSelector == y.peerSelector

    def fakeEvaluateBlock(
        block: Block
    ): IO[BlockImportResult] = {
      val result: BlockImportResult = if (didTryToImportBlock(block)) {
        DuplicateBlock
      } else {
        if (importedBlocksSet.isEmpty || bestBlock.isParentOf(block) || importedBlocksSet.exists(_.isParentOf(block))) {
          importedBlocksSet.add(block)
          BlockImportedToTop(List(BlockData(block, Nil, ChainWeight.totalDifficultyOnly(block.header.difficulty))))
        } else if (block.number > bestBlock.number) {
          importedBlocksSet.add(block)
          BlockEnqueued
        } else {
          BlockImportFailed("foo")
        }
      }

      IO.pure(result)
    }

    class FakeBranchResolution extends BranchResolution(stub[BlockchainReader]) {
      override def resolveBranch(headers: NonEmptyList[BlockHeader]): BranchResolutionResult = {
        val importedHashes = importedBlocksSet.map(_.hash).toSet

        if (
          importedBlocksSet.isEmpty || (importedHashes.contains(
            headers.head.parentHash
          ) && headers.last.number > bestBlock.number)
        )
          NewBetterBranch(Nil)
        else
          UnknownBranch
      }
    }
  }

  class OnTopFixture(system: ActorSystem) extends RegularSyncFixture(system) {

    val newBlock: Block = BlockHelpers.generateBlock(testBlocks.last)

    override lazy val consensusAdapter: ConsensusAdapter = stub[ConsensusAdapter]

    var blockFetcher: ActorRef = _

    var importedNewBlock = false
    var importedLastTestBlock = false

    override lazy val branchResolution: BranchResolution = stub[BranchResolution]
    (branchResolution.resolveBranch _).when(*).returns(NewBetterBranch(Nil))

    (consensusAdapter
      .evaluateBranchBlock(_: Block)(_: IORuntime, _: BlockchainConfig))
      .when(*, *, *)
      .onCall { (block, _, _) =>
        if (block == newBlock) {
          importedNewBlock = true
          IO.pure(
            BlockImportedToTop(List(BlockData(newBlock, Nil, ChainWeight(0, newBlock.number))))
          )
        } else {
          if (block == testBlocks.last) {
            importedLastTestBlock = true
          }
          IO.pure(BlockImportedToTop(Nil))
        }
      }

    peersClient.setAutoPilot(new PeersClientAutoPilot)

    def waitForSubscription(): Unit = {
      peerEventBus.expectMsgClass(classOf[Subscribe])
      blockFetcher = peerEventBus.sender()
    }

    def sendLastTestBlockAsTop(): Unit = sendNewBlock(testBlocks.last)

    def sendNewBlock(block: Block = newBlock, peer: Peer = defaultPeer): Unit =
      blockFetcher ! MessageFromPeer(NewBlock(block, ChainWeight.totalDifficultyOnly(block.number)), peer.id)

    def goToTop(): Unit = {
      regularSync ! SyncProtocol.Start

      waitForSubscription()
      sendLastTestBlockAsTop()

      awaitCond(importedLastTestBlock)
    }
  }
}

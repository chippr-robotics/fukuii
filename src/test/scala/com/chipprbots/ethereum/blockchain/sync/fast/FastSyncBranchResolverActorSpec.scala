package com.chipprbots.ethereum.blockchain.sync.fast

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.implicits.*

import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpecLike

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.sync.*
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchHeavierBeyondReach
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolutionFailed
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolutionFailed.BranchNotHeavier
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolutionFailed.CommonBlockBeyondReach
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolutionFailed.NoCommonBlockFound
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BranchResolvedSuccessful
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.StartBranchResolver
import com.chipprbots.ethereum.blockchain.sync.regular.DeepForkWeight
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.ledger.NewBetterBranch
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.*
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders as ETHBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders as ETHGetBlockHeaders
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Logger

class FastSyncBranchResolverActorSpec
    extends ScalaTestWithActorTestKit()
    with AnyFreeSpecLike
    with ScalaFutures
    with NormalPatience:
  self =>
  implicit override val timeout: Timeout = Timeout(30.seconds)

  import FastSyncBranchResolverActorSpec.*

  "FastSyncBranchResolver" - {
    "fetch headers from the new master peer" - {
      "the chain is repaired from the first request to the new master pair and then the last two blocks are removed" taggedAs (
        UnitTest,
        SyncTest
      ) in new TestSetup:
        implicit override lazy val system: ActorSystem = self.system.classicSystem
        implicit override lazy val ioRuntime: IORuntime = IORuntime.global

        val sender: TestProbe = TestProbe("sender")

        val commonBlocks: List[Block] = BlockHelpers.generateChain(
          5,
          BlockHelpers.genesis,
          block => block
        )

        val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(
          1,
          commonBlocks.last,
          block => block
        )

        val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(
          2,
          commonBlocks.last,
          block => block
        )

        val firstBatchBlockHeaders: List[Block] =
          blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

        val blocksSentFromPeer: Map[Int, List[Block]] = Map(1 -> firstBatchBlockHeaders)

        saveBlocks(blocksSaved)
        val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
        val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

        val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
          case r @ BranchResolvedSuccessful(num, _) if num == BigInt(5) => r
        }

        val response: BranchResolvedSuccessful = (for
          _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
          response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
          _ <- IO(stopController(fastSyncBranchResolver))
        yield response).unsafeRunSync()
        assert(getBestPeers.contains(response.masterPeer))

      // Spec 018 C4e part 6: on the unified state layout the caller asks for the common block only. No block is taken
      // off this node's chain: its head stays, and the peer's branch is judged by weight when it arrives.
      "the common block is found and this node's blocks above it are left, when the caller keeps them" taggedAs (
        UnitTest,
        SyncTest
      ) in new TestSetup:
        implicit override lazy val system: ActorSystem = self.system.classicSystem
        implicit override lazy val ioRuntime: IORuntime = IORuntime.global

        val sender: TestProbe = TestProbe("sender")
        val commonBlocks: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis, block => block)
        val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(1, commonBlocks.last, block => block)
        val blocksSavedInPeer: List[Block] =
          commonBlocks :++ BlockHelpers.generateChain(2, commonBlocks.last, block => block)
        val firstBatchBlockHeaders: List[Block] =
          blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

        saveBlocks(blocksSaved)
        val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, Map(1 -> firstBatchBlockHeaders))
        val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          creatFastSyncBranchResolver(
            sender.ref,
            networkPeerManager,
            CacheBasedBlacklist.empty(BlacklistMaxElements),
            discardGuard = None
          )

        fastSyncBranchResolver ! StartBranchResolver
        sender.expectMsgPF(branchResolutionTimeout) {
          case r @ BranchResolvedSuccessful(num, _) if num == BigInt(5) => r
        }
        stopController(fastSyncBranchResolver)
        // the block above the common one is this node's still, and its head
        blockchainReader.getBestBlockNumber shouldBe blocksSaved.last.number.value
        blockchainReader.getBlockByHash(blocksSaved.last.hash) shouldBe Some(blocksSaved.last)
        blockchainReader.getBlockHeaderByNumber(blocksSaved.last.number.value).map(_.hash) shouldBe
          Some(blocksSaved.last.hash)

      "The chain is repaired doing binary searching with the new master peer and then remove the last invalid blocks" - {
        "highest common block is in the middle" taggedAs (UnitTest, SyncTest) in new TestSetup:
          implicit override lazy val system: ActorSystem = self.system.classicSystem
          implicit override lazy val ioRuntime: IORuntime = IORuntime.global

          val sender: TestProbe = TestProbe("sender")

          val commonBlocks: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
          val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(5, commonBlocks.last)
          val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(6, commonBlocks.last)

          val firstBatchBlockHeaders: List[Block] =
            blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

          val blocksSentFromPeer: Map[Int, List[Block]] = Map(
            1 -> firstBatchBlockHeaders,
            2 -> List(blocksSavedInPeer.get(5).get),
            3 -> List(blocksSavedInPeer.get(7).get),
            4 -> List(blocksSavedInPeer.get(5).get),
            5 -> List(blocksSavedInPeer.get(6).get)
          )

          saveBlocks(blocksSaved)
          val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
          val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
            creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

          val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
            case r @ BranchResolvedSuccessful(num, _) if num == BigInt(5) => r
          }

          val response: BranchResolvedSuccessful = (for
            _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
            response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
            _ <- IO(stopController(fastSyncBranchResolver))
          yield response).unsafeRunSync()
          assert(getBestPeers.contains(response.masterPeer))
        "highest common block is in the first half" taggedAs (UnitTest, SyncTest) in new TestSetup:
          implicit override lazy val system: ActorSystem = self.system.classicSystem
          implicit override lazy val ioRuntime: IORuntime = IORuntime.global

          val sender: TestProbe = TestProbe("sender")

          val commonBlocks: List[Block] = BlockHelpers.generateChain(3, BlockHelpers.genesis)
          val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(7, commonBlocks.last)
          val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(8, commonBlocks.last)

          val firstBatchBlockHeaders: List[Block] =
            blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

          val blocksSentFromPeer: Map[Int, List[Block]] = Map(
            1 -> firstBatchBlockHeaders,
            2 -> List(blocksSavedInPeer.get(5).get),
            3 -> List(blocksSavedInPeer.get(2).get),
            4 -> List(blocksSavedInPeer.get(3).get),
            5 -> List(blocksSavedInPeer.get(3).get),
            6 -> List(blocksSavedInPeer.get(4).get)
          )

          saveBlocks(blocksSaved)
          val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
          val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
            creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

          val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
            case r @ BranchResolvedSuccessful(num, _) if num == BigInt(3) => r
          }

          val response: BranchResolvedSuccessful = (for
            _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
            response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
            _ <- IO(stopController(fastSyncBranchResolver))
          yield response).unsafeRunSync()
          assert(getBestPeers.contains(response.masterPeer))

        "highest common block is in the second half" taggedAs (UnitTest, SyncTest) in new TestSetup:
          implicit override lazy val system: ActorSystem = self.system.classicSystem
          implicit override lazy val ioRuntime: IORuntime = IORuntime.global

          val sender: TestProbe = TestProbe("sender")

          val commonBlocks: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)
          val blocksSaved: List[Block] = commonBlocks :++ BlockHelpers.generateChain(4, commonBlocks.last)
          val blocksSavedInPeer: List[Block] = commonBlocks :++ BlockHelpers.generateChain(5, commonBlocks.last)

          val firstBatchBlockHeaders: List[Block] =
            blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

          val blocksSentFromPeer: Map[Int, List[Block]] = Map(
            1 -> firstBatchBlockHeaders,
            2 -> List(blocksSavedInPeer.get(5).get),
            3 -> List(blocksSavedInPeer.get(7).get),
            4 -> List(blocksSavedInPeer.get(5).get),
            5 -> List(blocksSavedInPeer.get(6).get)
          )

          saveBlocks(blocksSaved)
          val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
          val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
            creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

          val expectation: PartialFunction[Any, BranchResolvedSuccessful] = {
            case r @ BranchResolvedSuccessful(num, _) if num == BigInt(6) => r
          }

          val response: BranchResolvedSuccessful = (for
            _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
            response <- IO(sender.expectMsgPF(branchResolutionTimeout)(expectation))
            _ <- IO(stopController(fastSyncBranchResolver))
          yield response).unsafeRunSync()
          assert(getBestPeers.contains(response.masterPeer))
      }

      "No common block is found" taggedAs (UnitTest, SyncTest) in new TestSetup:
        implicit override lazy val system: ActorSystem = self.system.classicSystem
        implicit override lazy val ioRuntime: IORuntime = IORuntime.global

        val sender: TestProbe = TestProbe("sender")

        // same genesis block but no common blocks
        val blocksSaved: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val blocksSavedInPeer: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)

        val firstBatchBlockHeaders: List[Block] =
          blocksSavedInPeer.slice(blocksSavedInPeer.size - syncConfig.blockHeadersPerRequest, blocksSavedInPeer.size)

        val blocksSentFromPeer: Map[Int, List[Block]] = Map(
          1 -> firstBatchBlockHeaders,
          2 -> List(blocksSavedInPeer.get(3).get),
          3 -> List(blocksSavedInPeer.get(1).get),
          4 -> List(blocksSavedInPeer.get(1).get)
        )

        saveBlocks(blocksSaved)
        val networkPeerManager: ActorRef = createNetworkPeerManager(handshakedPeers, blocksSentFromPeer)
        val fastSyncBranchResolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          creatFastSyncBranchResolver(sender.ref, networkPeerManager, CacheBasedBlacklist.empty(BlacklistMaxElements))

        log.debug(s"*** peers: ${handshakedPeers.map(p => (p._1.id, p._2.maxBlockNumber))}")
        (for
          _ <- IO(fastSyncBranchResolver ! StartBranchResolver)
          response <- IO(sender.expectMsg(branchResolutionTimeout, BranchResolutionFailed(NoCommonBlockFound)))
          _ <- IO(stopController(fastSyncBranchResolver))
        yield response).unsafeRunSync()
    }
    // B-298: on the hash layout the resolver takes this node's blocks off the chain only for a common block no deeper
    // than the guard allows and a branch the import's fork choice would take, and it follows the heaviest peer
    "on the hash layout (B-298)" - {
      "a heavier branch: this node's blocks above the common block are discarded, as before" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(20, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(4, common.last)
        // as heavy as ours up to its height, and two blocks more
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(6, common.last)
        saveBlocks(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(onePeer -> theirs), guard(deepestCommonBlock = 10))
        val response: BranchResolvedSuccessful = sender.expectMsgPF(branchResolutionTimeout) {
          case r: BranchResolvedSuccessful => r
        }
        stopController(resolver)

        response.highestCommonBlockNumber shouldBe BigInt(20)
        response.discarded.map(_._1) shouldBe Seq(24, 23, 22, 21).map(BigInt(_))
        blockchainReader.getBestBlockNumber shouldBe BigInt(20)
        blacklist.isBlacklisted(onePeer.id) shouldBe false

      "a lighter branch, longer than ours: nothing is discarded and the peer is blacklisted" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(20, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(4, common.last)
        // ten blocks of a tenth of the difficulty: longer than ours, and lighter
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(
          10,
          common.last,
          block => BlockHelpers.updateHeader(block, _.copy(difficulty = Difficulty(100000)))
        )
        saveBlocks(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(onePeer -> theirs), guard(deepestCommonBlock = 10))
        sender.expectMsg(branchResolutionTimeout, BranchResolutionFailed(BranchNotHeavier(20)))
        stopController(resolver)

        blockchainReader.getBestBlockNumber shouldBe BigInt(24)
        blockchainReader.getBlockHeaderByNumber(24).map(_.hash) shouldBe Some(ours.last.hash)
        blacklist.isBlacklisted(onePeer.id) shouldBe true

      "a common block deeper than the guard allows: refused, nothing is discarded and the peer is blacklisted" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(25, common.last)
        // heavier, but it leaves this chain at block 5, below the floor at 20
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(30, common.last)
        saveBlocks(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(onePeer -> theirs), guard(deepestCommonBlock = 20))
        sender.expectMsg(branchResolutionTimeout, BranchResolutionFailed(CommonBlockBeyondReach(20)))
        stopController(resolver)

        blockchainReader.getBestBlockNumber shouldBe BigInt(30)
        blockchainReader.getBlockHeaderByNumber(30).map(_.hash) shouldBe Some(ours.last.hash)
        blacklist.isBlacklisted(onePeer.id) shouldBe true

      // B-346 (the user's decision of 2026-11-18): below the floor the common block is still searched for and the
      // peer's branch weighed from it, with headers only. The heavier is handed on for an escalation, never rewound to
      "below the floor, a heavier branch: answered for an escalation, nothing is discarded, the peer kept" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(25, common.last)
        // the same difficulty, five blocks more: heavier, and it leaves this chain at block 5, below the floor at 20
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(30, common.last)
        saveWeighted(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(onePeer -> theirs), deepGuard(deepestCommonBlock = 20))
        val response: BranchHeavierBeyondReach = sender.expectMsgPF(branchResolutionTimeout) {
          case r: BranchHeavierBeyondReach => r
        }
        stopController(resolver)

        response.commonBlock shouldBe BigInt(5)
        response.commonHash shouldBe common.last.hash.value
        response.masterPeer shouldBe onePeer
        // weighed until it outweighed ours: 26 of its blocks against our 25
        response.weighed should be >= 26
        blockchainReader.getBestBlockNumber shouldBe BigInt(30)
        blockchainReader.getBlockHeaderByNumber(30).map(_.hash) shouldBe Some(ours.last.hash)
        blockchainReader.getBlockHeaderByNumber(6).map(_.hash) shouldBe Some(ours(5).hash)
        blacklist.isBlacklisted(onePeer.id) shouldBe false

      "below the floor, a lighter branch: refused, nothing is discarded and the peer is blacklisted" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(25, common.last)
        // longer than ours, and lighter: a tenth of the difficulty
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(
          30,
          common.last,
          block => BlockHelpers.updateHeader(block, _.copy(difficulty = Difficulty(100000)))
        )
        saveWeighted(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(onePeer -> theirs), deepGuard(deepestCommonBlock = 20))
        sender.expectMsg(branchResolutionTimeout, BranchResolutionFailed(CommonBlockBeyondReach(20)))
        stopController(resolver)

        blockchainReader.getBestBlockNumber shouldBe BigInt(30)
        blockchainReader.getBlockHeaderByNumber(30).map(_.hash) shouldBe Some(ours.last.hash)
        blacklist.isBlacklisted(onePeer.id) shouldBe true

      "below the floor, a heavier branch with a header that is not valid: refused and the peer blacklisted" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(25, common.last)
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(30, common.last)
        saveWeighted(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(
            Map(onePeer -> theirs),
            deepGuard(deepestCommonBlock = 20, firstInvalid = headers => headers.lastOption.map(_ -> "a bad seal"))
          )
        sender.expectMsg(branchResolutionTimeout, BranchResolutionFailed(CommonBlockBeyondReach(20)))
        stopController(resolver)

        blockchainReader.getBestBlockNumber shouldBe BigInt(30)
        blacklist.isBlacklisted(onePeer.id) shouldBe true

      "below the floor, a fork escalated already: answered at once, not weighed again" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val common: List[Block] = BlockHelpers.generateChain(5, BlockHelpers.genesis)
        val ours: List[Block] = common :++ BlockHelpers.generateChain(25, common.last)
        val theirs: List[Block] = common :++ BlockHelpers.generateChain(30, common.last)
        saveWeighted(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(
            Map(onePeer -> theirs),
            deepGuard(
              deepestCommonBlock = 20,
              // a weighing that would refuse it: it is not asked
              firstInvalid = headers => headers.lastOption.map(_ -> "not to be asked"),
              escalatedAt = Some(common.last.hash.value)
            )
          )
        val response: BranchHeavierBeyondReach = sender.expectMsgPF(branchResolutionTimeout) {
          case r: BranchHeavierBeyondReach => r
        }
        stopController(resolver)

        response.commonBlock shouldBe BigInt(5)
        response.weighed shouldBe 0
        blockchainReader.getBestBlockNumber shouldBe BigInt(30)
        blacklist.isBlacklisted(onePeer.id) shouldBe false

      "the master peer is the one with the heaviest chain, not the highest block" taggedAs (
        UnitTest,
        SyncTest
      ) in new HashLayoutSetup:
        val ours: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)
        val theirs: List[Block] = ours :++ BlockHelpers.generateChain(2, ours.last)
        saveBlocks(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(heavyPeer -> theirs, tallPeer -> theirs), guard(deepestCommonBlock = 0))
        val response: BranchResolvedSuccessful = sender.expectMsgPF(branchResolutionTimeout) {
          case r: BranchResolvedSuccessful => r
        }
        stopController(resolver)
        response.masterPeer shouldBe heavyPeer

      "the unified layout keeps the peer with the highest block" taggedAs (UnitTest, SyncTest) in new HashLayoutSetup:
        val ours: List[Block] = BlockHelpers.generateChain(6, BlockHelpers.genesis)
        val theirs: List[Block] = ours :++ BlockHelpers.generateChain(2, ours.last)
        saveBlocks(ours)

        val resolver: TypedActorRef[FastSyncBranchResolverActor.Command] =
          start(Map(heavyPeer -> theirs, tallPeer -> theirs), discardGuard = None)
        val response: BranchResolvedSuccessful = sender.expectMsgPF(branchResolutionTimeout) {
          case r: BranchResolvedSuccessful => r
        }
        stopController(resolver)
        response.masterPeer shouldBe tallPeer
    }
  }

  /** Peers that serve the headers asked for from their own chain, by number, as a peer does. */
  trait HashLayoutSetup extends TestSetup:
    implicit override lazy val system: ActorSystem = self.system.classicSystem
    implicit override lazy val ioRuntime: IORuntime = IORuntime.global

    val sender: TestProbe = TestProbe("sender")
    val blacklist: Blacklist = CacheBasedBlacklist.empty(BlacklistMaxElements)

    def peerInfo(peer: Peer, weight: BigInt, maxBlock: BigInt): PeerInfo =
      getPeerInfo(peer).copy(chainWeight = ChainWeight.totalDifficultyOnly(weight), maxBlockNumber = maxBlock)

    val onePeer: Peer = getPeer(peerId(1))
    val heavyPeer: Peer = getPeer(peerId(2))
    val tallPeer: Peer = getPeer(peerId(3))
    private val infos: Map[Peer, PeerInfo] = Map(
      onePeer -> peerInfo(onePeer, weight = 10, maxBlock = 10),
      heavyPeer -> peerInfo(heavyPeer, weight = 20, maxBlock = 5),
      tallPeer -> peerInfo(tallPeer, weight = 10, maxBlock = 9)
    )

    def guard(deepestCommonBlock: BigInt): Option[FastSyncBranchResolverActor.DiscardGuard] =
      val resolution = new BranchResolution(blockchainReader)
      Some(
        FastSyncBranchResolverActor.DiscardGuard(
          deepestCommonBlock,
          heavier = Some(headers =>
            resolution.resolveBranch(headers) match
              case NewBetterBranch(_) => true
              case _                  => false
          )
        )
      )

    /** Saved as the best chain with each block's weight the sum of the difficulties up to it, as an import stores it.
      */
    def saveWeighted(blocks: List[Block]): Unit =
      blocks.foldLeft(ChainWeight.totalDifficultyOnly(0)) { (weight, block) =>
        val next = weight.increase(block.header)
        blockchainWriter.save(block, Nil, next, saveAsBestBlock = true)
        next
      }: Unit

    /** [[guard]], and below the floor the weighing of the hash layout (B-346): stored weights, no MESS here, and the
      * header validation given.
      */
    def deepGuard(
        deepestCommonBlock: BigInt,
        firstInvalid: List[BlockHeader] => Option[(BlockHeader, String)] = _ => None,
        escalatedAt: Option[ByteString] = None
    ): Option[FastSyncBranchResolverActor.DiscardGuard] =
      val weight = new DeepForkWeight(
        weightOf = blockchainReader.getChainWeightByHash,
        headerOf = blockchainReader.getBlockHeaderByHash,
        onTheChain = header => blockchainReader.getCanonicalHashByNumber(header.number.value).contains(header.hash),
        head = () =>
          blockchainReader.getBestBlockHeader.flatMap(h => blockchainReader.getChainWeightByHash(h.hash).map(h -> _)),
        messActiveAt = None,
        messRefuses = (_, _, _) => false,
        firstInvalidHeader = firstInvalid
      )
      guard(deepestCommonBlock).map(
        _.copy(beyondReach = Some(FastSyncBranchResolverActor.BeyondReach(64, weight.verdict, escalatedAt)))
      )

    def start(
        chains: Map[Peer, List[Block]],
        discardGuard: Option[FastSyncBranchResolverActor.DiscardGuard]
    ): TypedActorRef[FastSyncBranchResolverActor.Command] =
      val networkPeerManager = TestProbe("network_peer_manager")
      networkPeerManager.setAutoPilot(
        new ChainServingAutoPilot(
          infos.filter { case (peer, _) => chains.contains(peer) },
          chains.map { case (peer, blocks) => peer.id -> blocks.map(b => b.number.value -> b).toMap },
          peerEventBus
        )
      )
      val resolver = creatFastSyncBranchResolver(sender.ref, networkPeerManager.ref, blacklist, discardGuard)
      resolver ! StartBranchResolver
      resolver

  trait TestSetup extends EphemBlockchainTestSetup with TestSyncConfig with TestSyncPeers:

    // The whole resolution, not one request: a cold JVM's first resolution (class loading, the first logger, the
    // first IO runtime) can take most of 7 s (B-350). A longer wait only bounds a failing test.
    protected val branchResolutionTimeout: FiniteDuration = syncConfig.peerResponseTimeout + 25.seconds

    def peerId(number: Int): PeerId = PeerId(s"peer_$number")
    def getPeer(id: PeerId): Peer =
      Peer(id, new InetSocketAddress("127.0.0.1", 0), TestProbe(id.value).ref, incomingConnection = false)
    def getPeerInfo(peer: Peer): PeerInfo =
      val status =
        RemoteStatus(
          Capability.ETH68,
          1,
          ChainWeight.totalDifficultyOnly(1),
          ByteString(s"${peer.id}_bestHash"),
          ByteString("unused")
        )
      PeerInfo(
        status,
        forkAccepted = true,
        chainWeight = status.chainWeight,
        maxBlockNumber = Random.between(1, 10),
        bestBlockHash = status.bestHash
      )

    val handshakedPeers: Map[Peer, PeerInfo] =
      (0 to 5).toList.map(peerId.andThen(getPeer)).fproduct(getPeerInfo(_)).toMap

    // A real bus, not a probe: the resolver's typed PeerRequestHandler children subscribe to it for the reply, and the
    // fake network peer manager publishes each response to it, as PeerActor publishes a decoded wire message.
    lazy val peerEventBus: TypedActorRef[PeerEventBusActor.Command] =
      self.testKit.spawn(PeerEventBusActor.behavior())

    def saveBlocks(blocks: List[Block]): Unit =
      blocks.foreach(block =>
        blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(1), saveAsBestBlock = true)
      )

    def createNetworkPeerManager(peers: Map[Peer, PeerInfo], blocks: Map[Int, List[Block]]): ActorRef =
      val networkPeerManager = TestProbe("network_peer_manager")
      val autoPilot =
        new NetworkPeerManagerAutoPilot(
          peers,
          blocks,
          peerEventBus
        )
      networkPeerManager.setAutoPilot(autoPilot)
      networkPeerManager.ref

    def creatFastSyncBranchResolver(
        fastSync: ActorRef,
        networkPeerManager: ActorRef,
        blacklist: Blacklist,
        // the hash layout's guard, with no floor and no weighing: the search as it always was
        discardGuard: Option[FastSyncBranchResolverActor.DiscardGuard] = Some(
          FastSyncBranchResolverActor.DiscardGuard(deepestCommonBlock = 0, heavier = None)
        )
    ): TypedActorRef[FastSyncBranchResolverActor.Command] =
      self.testKit.spawn(
        FastSyncBranchResolverActor(
          replyTo = fastSync.toTyped[FastSyncBranchResolverActor.BranchResolverResponse],
          peerEventBus = peerEventBus,
          networkPeerManager = networkPeerManager,
          blockchain = blockchain,
          blockchainReader = blockchainReader,
          blacklist = blacklist,
          syncConfig = syncConfig,
          discardGuard = discardGuard
        ),
        s"fast-sync-branch-resolver-${java.util.UUID.randomUUID()}"
      )

    def stopController(actorRef: TypedActorRef[FastSyncBranchResolverActor.Command]): Unit =
      self.testKit.stop(actorRef)

    def getBestPeers: List[Peer] =
      val maxBlock = handshakedPeers.toList.map { case (_, peerInfo) => peerInfo.maxBlockNumber }.max
      handshakedPeers.toList.filter { case (_, peerInfo) => peerInfo.maxBlockNumber == maxBlock }.map(_._1)

object FastSyncBranchResolverActorSpec extends Logger:

  private val BlacklistMaxElements: Int = 100

  /** Answers a request for headers with those of the asking peer's chain from the number asked for, as a peer does. */
  class ChainServingAutoPilot(
      peers: Map[Peer, PeerInfo],
      chains: Map[PeerId, Map[BigInt, Block]],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command]
  ) extends AutoPilot:
    def run(sender: ActorRef, msg: Any): ChainServingAutoPilot =
      msg match
        case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
          replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers)
        case NetworkPeerManagerActor.SendMessageCmd(rawMsg, peerId) =>
          rawMsg.underlyingMsg match
            case ETHGetBlockHeaders(requestId, Left(from), amount, _, false) =>
              val chain = chains.getOrElse(peerId, Map.empty)
              val headers = (from until from + amount).toList.flatMap(n => chain.get(n).map(_.header))
              peerEventBus ! PeerEventBusActor.PublishCmd(MessageFromPeer(ETHBlockHeaders(requestId, headers), peerId))
            case other =>
              throw new RuntimeException(s"Unexpected message sent to ChainServingAutoPilot: $other")
        case _ => ()
      this

  class NetworkPeerManagerAutoPilot(
      peers: Map[Peer, PeerInfo],
      blocks: Map[Int, List[Block]],
      peerEventBus: TypedActorRef[PeerEventBusActor.Command]
  ) extends AutoPilot:

    var blockIndex = 0
    lazy val blocksSetSize = blocks.size

    def run(sender: ActorRef, msg: Any): NetworkPeerManagerAutoPilot =
      msg match
        case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
          // Nothing more: a TestProbe's autopilot runs on the sender's thread, here the resolver's. It also completed a
          // Deferred no test awaited, with unsafeRunSync, which on a cold JVM held the resolver's thread past the
          // test's whole wait (B-350).
          replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers)
        case NetworkPeerManagerActor.SendMessageCmd(rawMsg, peerId) =>
          val response = rawMsg.underlyingMsg match
            case req: ETHGetBlockHeaders if !req.reverse =>
              if blockIndex < blocksSetSize then blockIndex += 1
              ETHBlockHeaders(req.requestId, blocks.get(blockIndex).map(_.map(_.header)).getOrElse(Nil))
            case other =>
              throw new RuntimeException(s"Unexpected message sent to NetworkPeerManagerAutoPilot: $other")
          // A typed PeerRequestHandler sends SendMessageCmd with no sender and waits on the bus for the reply.
          peerEventBus ! PeerEventBusActor.PublishCmd(MessageFromPeer(response, peerId))
          if blockIndex == blocksSetSize then ()
      this

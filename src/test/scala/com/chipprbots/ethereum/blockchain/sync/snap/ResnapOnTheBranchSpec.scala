package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.compiletime.asMatchable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PivotOnBranch
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags.*

/** B-351 (forge): the SNAP re-sync a hash-layout node runs for a heavier chain that leaves its own below the fork
  * recovery's floor (B-346) lands on that chain. Built as the node has it when the importer escalates: this node's
  * chain 0..90 in the index, the heavier chain leaving it at block 20 and reaching block 75, and an earlier sync's
  * header download recorded at block 40, above the common block, on this node's chain. The re-SNAP's pivot is block 70
  * of the heavier chain.
  *
  * The controller holds the sync to the branch ([[AppStateStorage.holdTheSyncToTheBranch]], what `resnapNow` writes for
  * a stuck that names one); the real header download then runs against two peers, one on each chain, through the wire
  * protocol; and the hand-off clears the index above the pivot ([[PivotAnchor.clearIndexAbove]]). The index from the
  * common block to the pivot must then name the heavier chain's blocks, and nothing of this node's old chain may be
  * left in it.
  *
  * Driven with the parent-link-only check: a second chain of real Mordor seals cannot be mined in a test, and the parts
  * this exercises (the branch's gate, the cursor, the parent links and the index) do not depend on the check. What a
  * live Mordor run adds is listed in the B-351 report: the escalation itself, pivot selection and bootstrap with real
  * peers on both chains, and the seal-validated download.
  */
class ResnapOnTheBranchSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with TestSyncConfig:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  import PowHeaderFixtures.*

  private val genesis: BlockHeader = fromGenesis.head
  private val common: BigInt = 20
  private val pivot: BigInt = 70

  /** This node's chain: real Mordor headers 1..90. */
  private val ours: IndexedSeq[BlockHeader] = fromGenesis.slice(1, 91)

  /** The heavier chain: this node's up to the common block, then its own blocks 21..75. */
  private val theirs: IndexedSeq[BlockHeader] =
    val above = fromGenesis
      .slice(common.toInt + 1, 76)
      .scanLeft(fromGenesis(common.toInt)) { (parent, header) =>
        header.copy(parentHash = parent.hash, extraData = ByteString("the heavier chain"))
      }
      .tail
    fromGenesis.slice(1, common.toInt + 1) ++ above

  private val onOurs = PeerId("peer-on-the-chain-left")
  private val onTheirs = PeerId("peer-on-the-heavier-chain")

  private def chainOf(peer: PeerId): IndexedSeq[BlockHeader] = if peer == onOurs then ours else theirs

  private def hashAt(rig: Rig, height: BigInt): Option[ByteString] =
    rig.storage.blockchainReader.getCanonicalHashByNumber(height).map(_.value)

  final private class Rig(holdToTheBranch: Boolean):
    val storage: EphemBlockchainTestSetup = new EphemBlockchainTestSetup {}
    val appState: AppStateStorage = storage.storagesInstance.storages.appStateStorage
    private val networkPeerManager: TestProbe = TestProbe()
    private val peerEventBus: TestProbe = TestProbe()

    // The node before the re-SNAP: its chain 0..90 with weights, as regular sync imported it, and the cursor an earlier
    // sync's header download left at block 40, with its tip hash.
    storage.blockchainWriter.save(
      Block(genesis, BlockBody.empty),
      Nil,
      ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
      saveAsBestBlock = true
    )
    ours.foldLeft(genesis.difficulty.value) { (weight, header) =>
      val next = weight + header.difficulty.value
      storage.blockchainWriter
        .storeBlockHeader(header)
        .and(storage.blockchainWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(next)))
        .commit()
      next
    }: Unit
    appState.putBackfillBestHeader(40).and(appState.putBackfillBestHeaderHash(ours(39).hash.value)).commit()

    // What SyncController.resnapNow writes before the re-SNAP starts, for a stuck that names the branch (B-351).
    if holdToTheBranch then
      appState
        .holdTheSyncToTheBranch(
          PivotOnBranch(common + 1, theirs(common.toInt).hash.value),
          height => hashAt(this, height)
        )
        .commitSync()

    val downloader: TypedActorRef[ChainDownloader.Command] = testKit.spawn(
      ChainDownloader(
        blockchainReader = storage.blockchainReader,
        blockchainWriter = storage.blockchainWriter,
        appStateStorage = appState,
        networkPeerManager = networkPeerManager.ref,
        peerEventBus = peerEventBus.ref,
        syncConfig = defaultSyncConfig.copy(blockHeadersPerRequest = 10, blacklistDuration = 60.seconds),
        replyTo = TestProbe().ref,
        maxConcurrentRequests = 1,
        blacklist = CacheBasedBlacklist.empty(100),
        deferBodiesAndReceipts = true,
        headerChainCheck = HeaderChainCheck.LinkOnly,
        sharedBlacklist = None
      ),
      s"chain-downloader-resnap-on-the-branch-${System.nanoTime()}"
    )

    private def peer(id: PeerId): Peer =
      Peer(id, new InetSocketAddress("127.0.0.1", 0), TestProbe(id.value).ref, incomingConnection = false)
    private val status =
      RemoteStatus(Capability.ETH68, 63, ChainWeight.totalDifficultyOnly(1), ByteString("best"), genesis.hash.value)
    private val info = PeerInfo(status, status.chainWeight, forkAccepted = true, BigInt(10_000_000), status.bestHash)

    networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds).replyTo !
      NetworkPeerManagerActor.HandshakedPeers(Map(peer(onOurs) -> info, peer(onTheirs) -> info))
    (1 to 2).foreach(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    networkPeerManager.ignoreMsg { case _: NetworkPeerManagerActor.GetHandshakedPeersCmd => true }

    downloader ! ChainDownloader.Start(pivot)
    downloader ! ChainDownloader.BoostConcurrency(1)

    private def responder(peerId: PeerId): TypedActorRef[PeerEvent] =
      val sub = peerEventBus.fishForMessage(5.seconds) {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(p)), _) =>
          codes.contains(Codes.BlockHeadersCode) && p == peerId
        case _ => false
      }
      sub.asMatchable match
        case SubscribeCmd(_, ref) => ref
        case other                => fail(s"unexpected $other")

    /** Answers each header request with what the peer asked has of its own chain, until the download reaches the pivot
      * or `requests` have been answered.
      */
    def serve(requests: Int): Unit =
      def reached: Boolean = appState.getBackfillBestHeader() >= pivot
      var answered = 0
      while answered < requests && !reached do
        // one request in flight: the next comes once the last response was handled, and none once the pivot is reached
        networkPeerManager.awaitCond(reached || networkPeerManager.msgAvailable, 15.seconds)
        if !reached then
          val sent = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](15.seconds)
          sent.message.underlyingMsg match
            case ETHPackets.GetBlockHeaders(id, Left(from), max, _, _) =>
              val served = chainOf(sent.peerId).filter(h => h.number.value >= from && h.number.value < from + max)
              responder(sent.peerId) ! PeerEvent.MessageFromPeer(ETHPackets.BlockHeaders(id, served), sent.peerId)
            case other => fail(s"expected a header request, got $other")
          answered += 1

  "A hash-layout SNAP re-sync for a heavier chain below the fork recovery's floor" should "take the index from the common block to the pivot from the heavier chain, and keep nothing of the chain it left (B-351)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val rig = new Rig(holdToTheBranch = true)
    withClue("the download goes on from the common block, not from the cursor at 40 on the chain left: ") {
      rig.appState.getBackfillBestHeader() shouldBe common
      rig.appState.getBackfillBestHeaderHash() shouldBe Some(fromGenesis(common.toInt).hash.value)
    }
    rig.serve(requests = 40)
    rig.appState.getBackfillBestHeader() shouldBe pivot
    PivotAnchor.branchCheck(rig.appState, rig.storage.blockchainReader) shouldBe
      PivotAnchor.BranchCheck.OnTheBranch(PivotOnBranch(common + 1, theirs(common.toInt).hash.value))

    // the hand-off on the hash layout
    PivotAnchor.clearIndexAbove(
      rig.appState,
      rig.storage.blockchainReader,
      rig.storage.blockchainWriter,
      pivot
    ) shouldBe
      BigInt(90)

    (BigInt(1) to common).foreach(n => hashAt(rig, n) shouldBe Some(fromGenesis(n.toInt).hash.value))
    withClue("from the common block to the pivot, the heavier chain's blocks: ") {
      (common + 1 to pivot).foreach(n => hashAt(rig, n) shouldBe Some(theirs(n.toInt - 1).hash.value))
    }
    withClue("above the pivot, nothing: the chain left answered there up to its head at 90: ") {
      (pivot + 1 to BigInt(95)).foreach(n => hashAt(rig, n) shouldBe None)
    }
    testKit.stop(rig.downloader)
  }

  // What B-351 found: the header download alone follows whichever chain continues the tip it remembers. From the cursor
  // an earlier sync left above the common block, every header of the heavier chain is refused (its parent is not that
  // tip), and the chain this node leaves is downloaded to the pivot's height: the index keeps it, and the pivot is
  // anchored to it. The hold above is what puts the download on the branch.
  it should "without the hold, download the chain it leaves from the cursor an earlier sync left (what B-351 found)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val rig = new Rig(holdToTheBranch = false)
    rig.serve(requests = 40)
    rig.appState.getBackfillBestHeader() shouldBe pivot
    (common + 1 to pivot).foreach(n => hashAt(rig, n) shouldBe Some(ours(n.toInt - 1).hash.value))
    testKit.stop(rig.downloader)
  }

  "The hash layout's hand-off" should "delete nothing when the index ends at the pivot (B-351)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val storage = new EphemBlockchainTestSetup {}
    val appState = storage.storagesInstance.storages.appStateStorage
    fromGenesis.take(11).foreach(h => storage.blockchainWriter.storeBlockHeader(h).commit())
    PivotAnchor.clearIndexAbove(appState, storage.blockchainReader, storage.blockchainWriter, 10) shouldBe BigInt(10)
    storage.blockchainReader.getCanonicalHashByNumber(10).map(_.value) shouldBe Some(fromGenesis(10).hash.value)
  }

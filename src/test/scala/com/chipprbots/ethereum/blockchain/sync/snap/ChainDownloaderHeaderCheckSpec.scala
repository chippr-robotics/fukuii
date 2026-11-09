package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.compiletime.asMatchable
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.blockchain.sync.Blacklist
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistId
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.PeerDisconnectedClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags.*

/** The SNAP header download's checks on what it stores, driven through the wire protocol with real mined Mordor headers
  * from genesis: a validated chain stores a response only when every header passes the block-import rules against its
  * parent (seals sampled with an injected, fixed sequence); a response with an invalid header stores nothing, the peer
  * that served it is blacklisted and the same range is asked of another peer. The parent-link-only check (ETH) keeps
  * its behaviour.
  */
class ChainDownloaderHeaderCheckSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually
    with TestSyncConfig:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  // A response's validation includes an Ethash cache build on first use of an epoch (about a second).
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(50, Millis)))

  import PowHeaderFixtures.*

  private val genesis: BlockHeader = fromGenesis.head
  private val peerA = PeerId("header-peer-a")
  private val peerB = PeerId("header-peer-b")

  /** Validated as block import validates Mordor, seals sampled at a fixed position (the first of each 16). */
  private def mordorCheck: HeaderChainCheck =
    new HeaderChainCheck.Validated(PoWBlockHeaderValidator, mordorConfig, sealSampleWindow = 16, random = _ => 0)

  final private class RecordingBlacklist extends Blacklist:
    private val delegate = CacheBasedBlacklist.empty(100)
    @volatile var added: Vector[(BlacklistId, BlacklistReason)] = Vector.empty
    override def isBlacklisted(id: BlacklistId): Boolean = delegate.isBlacklisted(id)
    override def add(id: BlacklistId, duration: FiniteDuration, reason: BlacklistReason): Unit =
      added :+= (id -> reason)
      delegate.add(id, duration, reason)
    override def remove(id: BlacklistId): Unit = delegate.remove(id)
    override def keys: Set[BlacklistId] = delegate.keys

  final private class Rig(
      check: HeaderChainCheck,
      target: BigInt,
      perRequest: Int,
      deferred: Boolean,
      prepare: Rig => Unit
  ):
    val storage: EphemBlockchainTestSetup = new EphemBlockchainTestSetup {}
    val appState: AppStateStorage = storage.storagesInstance.storages.appStateStorage
    val blacklist = new RecordingBlacklist
    val networkPeerManager: TestProbe = TestProbe()
    val peerEventBus: TestProbe = TestProbe()

    // What SNAP's start leaves: the genesis block with its weight.
    storage.blockchainWriter.save(
      Block(genesis, BlockBody.empty),
      Nil,
      ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
      saveAsBestBlock = true
    )
    prepare(this)

    val downloader: TypedActorRef[ChainDownloader.Command] = testKit.spawn(
      ChainDownloader(
        blockchainReader = storage.blockchainReader,
        blockchainWriter = storage.blockchainWriter,
        appStateStorage = appState,
        networkPeerManager = networkPeerManager.ref,
        peerEventBus = peerEventBus.ref,
        syncConfig = defaultSyncConfig.copy(blockHeadersPerRequest = perRequest, blacklistDuration = 1.minute),
        replyTo = TestProbe().ref,
        maxConcurrentRequests = 1, // one request in flight at a time: each response decides the next request
        blacklist = blacklist,
        deferBodiesAndReceipts = deferred,
        headerChainCheck = check
      ),
      s"chain-downloader-header-check-${System.nanoTime()}"
    )

    private def peer(id: PeerId): Peer =
      Peer(id, new InetSocketAddress("127.0.0.1", 0), TestProbe(id.value).ref, incomingConnection = false)
    private val status =
      RemoteStatus(Capability.ETH68, 63, ChainWeight.totalDifficultyOnly(1), ByteString("best"), genesis.hash.value)
    private val info = PeerInfo(status, status.chainWeight, forkAccepted = true, BigInt(10_000_000), status.bestHash)

    networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds).replyTo !
      NetworkPeerManagerActor.HandshakedPeers(Map(peer(peerA) -> info, peer(peerB) -> info))
    (1 to 2).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds).to).toSet shouldBe Set(
      PeerDisconnectedClassifier(PeerSelector.WithId(peerA)),
      PeerDisconnectedClassifier(PeerSelector.WithId(peerB))
    )
    networkPeerManager.ignoreMsg { case _: NetworkPeerManagerActor.GetHandshakedPeersCmd => true }

    downloader ! ChainDownloader.Start(target)
    downloader ! ChainDownloader.BoostConcurrency(1) // dispatch now (the start timer waits 2 s)

    final case class HeaderRequest(peerId: PeerId, requestId: BigInt, from: BigInt, max: BigInt):
      def answer(headers: Seq[BlockHeader]): Unit =
        responder(peerId) ! PeerEvent.MessageFromPeer(ETHPackets.BlockHeaders(requestId, headers), peerId)

    /** The next header request the downloader sends. */
    def nextRequest(): HeaderRequest =
      val send = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](15.seconds)
      send.message.underlyingMsg match
        case ETHPackets.GetBlockHeaders(id, Left(from), max, _, _) => HeaderRequest(send.peerId, id, from, max)
        case other                                                 => fail(s"expected a header request, got $other")

    // The request handler's subscription for the header reply from `peerId` (it subscribes before it sends).
    private def responder(peerId: PeerId): TypedActorRef[PeerEvent] =
      val sub = peerEventBus.fishForMessage(5.seconds) {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(p)), _) =>
          codes.contains(Codes.BlockHeadersCode) && p == peerId
        case _ => false
      }
      sub.asMatchable match
        case SubscribeCmd(_, ref) => ref
        case other                => fail(s"unexpected $other")

    def cursor: BigInt = appState.getBackfillBestHeader()

  /** The run `from..from+max-1` of the fixture, with `edit` applied to the header at `at`. */
  private def run(from: BigInt, max: BigInt, at: Option[BigInt] = None, edit: BlockHeader => BlockHeader = identity) =
    fromGenesis.slice(from.toInt, (from + max).toInt).map(h => if at.contains(h.number.value) then edit(h) else h)

  private val otherRoot = TrieRoot(ByteString(Array.fill(32)(0x5a.toByte)))

  "ChainDownloader with a validated header chain" should "store a run of real mined Mordor headers from genesis" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val rig = new Rig(mordorCheck, target = BigInt(256), perRequest = 64, deferred = true, _ => ())
    (0 until 4).foreach { i =>
      val req = rig.nextRequest()
      req.from shouldBe BigInt(1 + 64 * i)
      req.answer(run(req.from, req.max))
      eventually(rig.cursor shouldBe req.from + req.max - 1)
    }
    rig.appState.getBackfillBestHeaderHash() shouldBe Some(fromGenesis(256).hash.value)
    rig.storage.blockchainReader.getBlockHeaderByNumber(256) shouldBe Some(fromGenesis(256))
    // Each stored weight is the parent's plus the header's difficulty, from genesis.
    rig.storage.blockchainReader.getChainWeightByHash(fromGenesis(256).hash).map(_.totalDifficulty.value) shouldBe
      Some(fromGenesis.map(_.difficulty.value).sum)
    rig.blacklist.added shouldBe empty
    testKit.stop(rig.downloader)
  }

  it should "reject a response with a difficulty-1 header, blacklist its peer and ask another peer for the range" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val rig = new Rig(mordorCheck, target = BigInt(256), perRequest = 64, deferred = true, _ => ())
    val first = rig.nextRequest()
    first.from shouldBe BigInt(1)
    first.answer(run(first.from, first.max, at = Some(BigInt(64)), _.copy(difficulty = Difficulty(BigInt(1)))))

    val again = rig.nextRequest()
    again.from shouldBe BigInt(1) // the same range again,
    again.peerId should not be first.peerId // from the other peer
    rig.blacklist.added shouldBe Vector(first.peerId -> BlacklistReason.BlockHeaderValidationFailed)
    rig.cursor shouldBe BigInt(0) // nothing of the rejected response was stored
    rig.storage.blockchainReader.getBlockHeaderByNumber(1) shouldBe None

    again.answer(run(again.from, again.max))
    eventually(rig.cursor shouldBe BigInt(64))
    rig.storage.blockchainReader.getBlockHeaderByNumber(64) shouldBe Some(fromGenesis(64))
    testKit.stop(rig.downloader)
  }

  it should "reject a header with a fabricated state root on its real parent at the pivot (its seal is checked)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val rig = new Rig(mordorCheck, target = BigInt(64), perRequest = 64, deferred = true, _ => ())
    val first = rig.nextRequest()
    val altered = run(first.from, first.max, at = Some(BigInt(64)), _.copy(stateRoot = otherRoot))
    altered.last.parentHash shouldBe fromGenesis(63).hash // a real parent; every rule but the seal holds
    first.answer(altered)

    val again = rig.nextRequest()
    again.from shouldBe BigInt(1)
    again.peerId should not be first.peerId
    rig.blacklist.added shouldBe Vector(first.peerId -> BlacklistReason.BlockHeaderValidationFailed)
    rig.cursor shouldBe BigInt(0)
    rig.storage.blockchainReader.getBlockHeaderByNumber(64) shouldBe None

    again.answer(run(again.from, again.max))
    eventually(rig.cursor shouldBe BigInt(64))
    rig.appState.getBackfillBestHeaderHash() shouldBe Some(fromGenesis(64).hash.value)
    testKit.stop(rig.downloader)
  }

  it should "reject a whole response whose parent links break, without storing its linked prefix" taggedAs UnitTest in {
    val rig = new Rig(mordorCheck, target = BigInt(256), perRequest = 64, deferred = true, _ => ())
    val first = rig.nextRequest()
    first.answer(run(first.from, first.max, at = Some(BigInt(33)), _.copy(parentHash = fromGenesis(10).hash)))

    val again = rig.nextRequest()
    again.from shouldBe BigInt(1)
    rig.blacklist.added shouldBe Vector(first.peerId -> BlacklistReason.ErrorInBlockHeaders)
    rig.cursor shouldBe BigInt(0)
    testKit.stop(rig.downloader)
  }

  it should "continue a resumed download from its recorded tip, by hash, whatever the number index says" taggedAs UnitTest in {
    val rig = new Rig(
      mordorCheck,
      target = BigInt(150),
      perRequest = 64,
      deferred = true,
      r =>
        // What an earlier run of the download left: blocks 1..100, their weights, the cursor and the tip hash...
        val weights = fromGenesis.take(101).scanLeft(BigInt(0))(_ + _.difficulty.value).tail
        fromGenesis.slice(1, 101).zip(weights.drop(1)).foreach { case (h, w) =>
          r.storage.blockchainWriter
            .storeBlockHeader(h)
            .and(r.storage.blockchainWriter.storeChainWeight(h.hash, ChainWeight.totalDifficultyOnly(w)))
            .commit()
        }
        r.appState
          .putBackfillBestHeader(100)
          .and(r.appState.putBackfillBestHeaderHash(fromGenesis(100).hash.value))
          .commit()
        // ...then another writer named another header at the tip's height in the number index.
        r.storage.blockchainWriter.storeBlockHeader(fromGenesis(100).copy(stateRoot = otherRoot)).commit()
    )
    val req = rig.nextRequest()
    req.from shouldBe BigInt(101)
    rig.storage.blockchainReader.getBlockHeaderByNumber(100) shouldBe Some(fromGenesis(100)) // the entry was put back
    req.answer(run(req.from, req.max))
    eventually(rig.cursor shouldBe BigInt(150))
    rig.blacklist.added shouldBe empty
    testKit.stop(rig.downloader)
  }

  it should "download again from genesis the headers a datadir stored without a recorded tip" taggedAs UnitTest in {
    val rig = new Rig(
      mordorCheck,
      target = BigInt(150),
      perRequest = 64,
      deferred = true,
      r =>
        fromGenesis.slice(1, 101).foreach(h => r.storage.blockchainWriter.storeBlockHeader(h).commit())
        r.appState.putBackfillBestHeader(100).commit() // no BackfillBestHeaderHash: stored before it was recorded
    )
    rig.nextRequest().from shouldBe BigInt(1)
    rig.cursor shouldBe BigInt(0)
    testKit.stop(rig.downloader)
  }

  "ChainDownloader with the parent-link-only check (ETH)" should "keep a datadir's stored headers without a recorded tip" taggedAs UnitTest in {
    val rig = new Rig(
      HeaderChainCheck.LinkOnly,
      target = BigInt(150),
      perRequest = 64,
      deferred = true,
      r =>
        fromGenesis.slice(1, 101).foreach(h => r.storage.blockchainWriter.storeBlockHeader(h).commit())
        r.appState.putBackfillBestHeader(100).commit()
    )
    rig.nextRequest().from shouldBe BigInt(101)
    testKit.stop(rig.downloader)
  }

  it should "store the linked prefix of a response with a broken link and accept any difficulty, as before" taggedAs UnitTest in {
    val rig = new Rig(HeaderChainCheck.LinkOnly, target = BigInt(256), perRequest = 64, deferred = true, _ => ())
    val first = rig.nextRequest()
    first.answer(run(first.from, first.max, at = Some(BigInt(20)), _.copy(difficulty = Difficulty(BigInt(1)))))

    // Blocks 1..19 link; the edited 20 has another hash, so 21 does not link to it and the run stops there.
    val again = rig.nextRequest()
    again.from shouldBe BigInt(21)
    rig.cursor shouldBe BigInt(20)
    rig.storage.blockchainReader.getBlockHeaderByNumber(20).map(_.difficulty.value) shouldBe Some(BigInt(1))
    rig.blacklist.added shouldBe Vector(first.peerId -> BlacklistReason.ErrorInBlockHeaders)
    testKit.stop(rig.downloader)
  }

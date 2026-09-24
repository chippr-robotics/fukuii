package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.LegacyReceipt
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SuccessOutcome
import com.chipprbots.ethereum.ledger
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
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.testing.Tags.*

/** Unit tests for the Pekko Typed `ChainDownloader` (Group S6 migration).
  *
  * The actor is a `Behavior[Any]`, so the previous white-box assertions via `TestActorRef#underlyingActor` are no
  * longer possible. Behaviour is exercised through the public protocol (`Start` / `UpdateTarget` / `YieldToRegularSync`
  * / `GetProgress`) and observable side effects on `AppStateStorage` (the persisted backfill target). `GetProgress` is
  * used as a synchronisation barrier and as a liveness probe: a reply proves the dispatch loop did not wedge.
  */
class ChainDownloaderSpec
    extends ScalaTestWithActorTestKit()
    with AnyFlatSpecLike
    with Matchers
    with Eventually
    with org.scalamock.scalatest.MockFactory
    with TestSyncConfig:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  // Regression for #1162: chain backfill keeps running in the background after SNAPSyncController
  // emits SnapSyncFinalized, but at lower priority. ChainDownloader needs a `YieldToRegularSync(n)`
  // message that mirrors `BoostConcurrency(n)` but downward — the SNAP controller sends it in
  // finalizeSnapSync() so backfill yields peer slots to forward sync.
  "ChainDownloader.YieldToRegularSync" should "carry the new concurrency budget" taggedAs UnitTest in {
    ChainDownloader.YieldToRegularSync(7).maxConcurrent shouldBe 7
  }

  it should "be a distinct message type from BoostConcurrency" taggedAs UnitTest in {
    val yielded: Any = ChainDownloader.YieldToRegularSync(7)
    val boosted: Any = ChainDownloader.BoostConcurrency(7)
    (yielded should not).equal(boosted)
  }

  // Behavioural test: the downloader's dispatch loop wedges if `maxConcurrentRequests` ever drops to
  // zero (the slot check `inFlightCount >= maxConcurrentRequests` would be true forever), stranding
  // the parent `SNAPSyncController` waiting for `ChainDownloader.Done` indefinitely. The handler clamps
  // to >=1. With no internal-state accessor on a Typed behavior, we assert the contract the clamp
  // protects: after YieldToRegularSync(0) the actor stays live and still answers GetProgress.
  "ChainDownloader" should "stay responsive after YieldToRegularSync(0) (clamp prevents dispatch wedge)" taggedAs UnitTest in {
    val (downloader, _, _) = newDownloader()
    downloader ! ChainDownloader.Start(BigInt(19_250_000))

    downloader ! ChainDownloader.YieldToRegularSync(0)
    expectProgress(downloader)

    downloader ! ChainDownloader.YieldToRegularSync(5)
    expectProgress(downloader)

    testKit.stop(downloader)
  }

  it should "stay responsive after BoostConcurrency then a downward YieldToRegularSync" taggedAs UnitTest in {
    val (downloader, _, _) = newDownloader()
    downloader ! ChainDownloader.Start(BigInt(19_250_000))

    downloader ! ChainDownloader.BoostConcurrency(16)
    expectProgress(downloader)

    downloader ! ChainDownloader.YieldToRegularSync(2)
    expectProgress(downloader)

    testKit.stop(downloader)
  }

  // Issue #1169: persist a BackfillTarget at Start so that a node killed mid-backfill can
  // resume standalone after restart.
  it should "persist BackfillTarget on Start so a restart can resume backfill" taggedAs UnitTest in {
    val (downloader, appStateStorage, _) = newDownloader()

    appStateStorage.getBackfillTarget() shouldBe BigInt(0)

    val target = BigInt(19_250_000)
    downloader ! ChainDownloader.Start(target)
    expectProgress(downloader) // barrier: Start has been processed

    appStateStorage.getBackfillTarget() shouldBe target

    testKit.stop(downloader)
  }

  // Issue #1169: an UpdateTarget message during the downloading phase persists the bumped
  // target so a restart resumes against the latest target rather than a stale one.
  it should "persist a bumped BackfillTarget on UpdateTarget" taggedAs UnitTest in {
    val (downloader, appStateStorage, _) = newDownloader()

    downloader ! ChainDownloader.Start(BigInt(1000))
    expectProgress(downloader)
    appStateStorage.getBackfillTarget() shouldBe BigInt(1000)

    downloader ! ChainDownloader.UpdateTarget(BigInt(1500))
    expectProgress(downloader)
    appStateStorage.getBackfillTarget() shouldBe BigInt(1500)

    // A target lower than the current one is ignored.
    downloader ! ChainDownloader.UpdateTarget(BigInt(1200))
    expectProgress(downloader)
    appStateStorage.getBackfillTarget() shouldBe BigInt(1500)

    testKit.stop(downloader)
  }

  // ── Defect 1: eth/69 receipt requests never completed ──────────────────────────────────────────────
  //
  // requestReceipts asked PeerRequestHandler for a Receipts68 response even when the peer negotiated ETH69, whose
  // MessageDecoder decodes the reply as Receipts69 (see ETH69MessageDecoder, network/p2p/MessageDecoders.scala).
  // PeerRequestHandler only forwards a reply matching its caller-supplied ResponseMsg type
  // (`case responseMsg: ResponseMsg` / `case _ => Behaviors.same` — PeerRequestHandler.scala); a Receipts69 never
  // matches Receipts68, so it was silently dropped and every eth/69 receipt request timed out. This drives the real
  // protocol end to end with real EphemDataSource-backed storage (EphemBlockchainTestSetup) rather than mocking
  // ChainDownloader's internals: real GetHandshakedPeers poll, real PeerRequestHandler spawn + PeerEventBus
  // subscriptions, a hand-built (but wire-accurate, via ETHPackets.ReceiptBloomFreeEnc) Receipts69 delivered through
  // the captured subscriber adapter, same as a real PeerEventBusActor would deliver it.
  it should "store receipts served by an eth/69 peer (Receipts69), not just eth/68's Receipts68" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage =
      storage.storagesInstance.storages.appStateStorage // same data source: a receipt write and its cursor commit as one batch
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    // One header stored beyond genesis with its body already present but no receipts. findBestStoredHeader's
    // queue-rebuild pass (triggered by Start) then seeds only receiptsQueue — with a single peer and bodies
    // taking dispatch priority over receipts, an empty bodiesQueue is what guarantees the one available
    // request slot goes to a receipts fetch instead.
    val header1: BlockHeader = BlockHelpers.generateBlock(BlockHelpers.genesis).header
    storage.blockchainWriter
      .storeBlockHeader(header1)
      .and(storage.blockchainWriter.storeBlockBody(header1.hash, BlockBody(Nil, Nil)))
      .commit()
    // Fast-skip cursor (#1169): tells findBestStoredHeader block 1 is our best header without needing genesis
    // itself in storage.
    appStateStorage.putBackfillBestHeader(BigInt(1)).commit()

    val peerId = PeerId("eth69-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH69,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("eth69-best-hash"),
      ByteString("eth69-genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(1),
      bestBlockHash = peerStatus.bestHash
    )

    val downloader: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = storage.blockchainReader,
          blockchainWriter = storage.blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager.ref,
          peerEventBus = peerEventBus.ref,
          syncConfig = defaultSyncConfig,
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-eth69-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer -> peerInfo))

    // Barrier: PeerListHelper.updatePeers sends this subscribe as the last step of processing HandshakedPeersMsg,
    // and `peers = updated` (the mutation that makes the peer visible to dispatchRequests) executes earlier in that
    // same synchronous call — so observing this proves the peer is registered before Start/BoostConcurrency below
    // can race it.
    val peerAddSub = peerEventBus.expectMsgType[SubscribeCmd](5.seconds)
    peerAddSub.to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peerId))

    downloader ! ChainDownloader.Start(BigInt(1))
    // BoostConcurrency's handler calls dispatchRequests() synchronously — forces an immediate dispatch instead of
    // waiting on the real 2-second Dispatch timer.
    downloader ! ChainDownloader.BoostConcurrency(4)

    // The spawned PeerRequestHandler subscribes twice with the same adapter (disconnect classifier + message
    // classifier); only it ever subscribes with a MessageClassifier, so that uniquely identifies its adapter.
    val prhSubs = (1 to 2).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    val prhAdapter: TypedActorRef[PeerEvent] = prhSubs
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
            if codes.contains(Codes.ReceiptsCode) && pid == peerId =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(ReceiptsCode) SubscribeCmd among: $prhSubs"))

    val sendCmd = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    sendCmd.peerId shouldBe peerId
    val requestId = sendCmd.message.underlyingMsg match
      case ETHPackets.GetReceipts(reqId, hashes) =>
        hashes shouldBe Seq(header1.hash.value)
        reqId
      case other => fail(s"expected a plain GetReceipts (same wire form as eth/68), got $other")

    val fixtureReceipt: Receipt =
      LegacyReceipt(SuccessOutcome, BigInt(21000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil)
    val blockReceiptsRlp = RLPList(ETHPackets.ReceiptBloomFreeEnc(fixtureReceipt).toRLPEncodable)
    val receipts69Msg = ETHPackets.Receipts69(requestId, RLPList(blockReceiptsRlp))

    // Deliver as the real PeerEventBusActor would: publish MessageFromPeer to the subscriber it captured.
    prhAdapter ! PeerEvent.MessageFromPeer(receipts69Msg, peerId)

    // The response travels test-thread -> PeerRequestHandler (child actor) -> downloader's prhResultAdapter
    // before handleReceipts69's store+commit runs — a GetProgress sent right after, straight to `downloader`
    // with no intermediate hop, could outrace it. Poll the observable storage effect instead of relying on a
    // single round-trip to win that race.
    eventually {
      storage.blockchainReader.getReceiptsByHash(header1.hash) shouldBe Some(Seq(fixtureReceipt))
    }

    // Once the store is observed, a fresh GetProgress is guaranteed to be processed after it (same target actor,
    // sent from this point on) — confirms the stats counter moved too, not just the storage side effect.
    val progress = expectProgress(downloader)
    progress.receiptsDownloaded shouldBe BigInt(1)

    testKit.stop(downloader)
  }

  // ── Defect 2: a failed request lost its batch ───────────────────────────────────────────────────────
  //
  // `PeerResult(RequestFailed(...))` removed the failed peer from bodyRequestPeers/receiptRequestPeers without
  // re-queuing the hashes it had in flight. bodiesQueue/receiptsQueue then permanently lost those hashes;
  // checkCompletion saw every queue empty and declared the backfill COMPLETE (clearing its cursors) while the
  // bodies/receipts were never actually fetched.
  it should "re-queue a peer's in-flight body hashes on RequestFailed instead of losing them" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage =
      storage.storagesInstance.storages.appStateStorage // same data source: a receipt write and its cursor commit as one batch
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    // Header stored with receipts already marked done (storing an empty Seq answers findBestStoredHeader's
    // getReceiptsByHash(...).isEmpty check with `false`) but no body — isolates dispatch to a pure bodies fetch on
    // a single peer, so the request that fails and must be re-queued is unambiguous.
    val header1: BlockHeader = BlockHelpers.generateBlock(BlockHelpers.genesis).header
    storage.blockchainWriter
      .storeBlockHeader(header1)
      .and(storage.blockchainWriter.storeReceipts(header1.hash, Seq.empty))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(1)).commit()

    val peerId = PeerId("failing-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("fail-best-hash"),
      ByteString("fail-genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(1),
      bestBlockHash = peerStatus.bestHash
    )

    // RequestFailed blacklists the peer (unchanged behaviour — "keep the blacklisting"), and with only one
    // handshaked peer the re-dispatch of the re-queued hash can only happen once that blacklist window expires.
    // Shrink it so the fixed-code path re-dispatches within a couple of Dispatch ticks instead of the real
    // 5-second default.
    val fastBlacklistSyncConfig = defaultSyncConfig.copy(blacklistDuration = 50.millis)

    val downloader: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = storage.blockchainReader,
          blockchainWriter = storage.blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager.ref,
          peerEventBus = peerEventBus.ref,
          syncConfig = fastBlacklistSyncConfig,
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-refail-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer -> peerInfo))

    val peerAddSub = peerEventBus.expectMsgType[SubscribeCmd](5.seconds)
    peerAddSub.to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peerId))

    downloader ! ChainDownloader.Start(BigInt(1))
    downloader ! ChainDownloader.BoostConcurrency(4)

    val prhSubs = (1 to 2).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    val prhAdapter: TypedActorRef[PeerEvent] = prhSubs
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
            if codes.contains(Codes.BlockBodiesCode) && pid == peerId =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(BlockBodiesCode) SubscribeCmd among: $prhSubs"))

    val firstSend = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    firstSend.peerId shouldBe peerId
    firstSend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(_, hashes) => hashes shouldBe Seq(header1.hash.value)
      case other                                => fail(s"expected GetBlockBodies, got $other")

    // Simulate the connection closing before a reply arrives. PeerRequestHandler turns this into
    // RequestFailed(_, peer, "connection closed"), delivered to ChainDownloader as PeerResult(RequestFailed(...)).
    prhAdapter ! PeerEvent.PeerDisconnected(peerId)

    // Fixed code: dispatchRequests() re-dispatches the re-queued hash to the same peer (ChainDownloader's own
    // handshaked-peer list is untouched — we never fed a disconnect to ITS OWN peerDisconnectedAdapter, only to
    // the PeerRequestHandler's). Unfixed code: the hash is gone from every queue, so no second SendMessageCmd
    // arrives and this times out.
    val secondSend = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    secondSend.peerId shouldBe peerId
    secondSend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(_, hashes) => hashes shouldBe Seq(header1.hash.value)
      case other                                => fail(s"expected a re-issued GetBlockBodies, got $other")

    // The backfill must not have declared itself complete off the back of the failed request.
    replyToProbe.expectNoMessage(300.millis)

    testKit.stop(downloader)
  }

  /** Sends `GetProgress` and waits for the `Progress` reply — both a synchronisation barrier (the actor has drained its
    * mailbox up to this point) and a liveness probe (a reply means the behavior did not wedge or stop).
    */
  private def expectProgress(
      downloader: TypedActorRef[ChainDownloader.Command]
  ): ChainDownloader.Progress =
    val probe = TestProbe()
    val typedProbe: TypedActorRef[ChainDownloader.Progress] = probe.ref.toTyped[ChainDownloader.Progress]
    downloader ! ChainDownloader.GetProgress(typedProbe)
    probe.expectMsgType[ChainDownloader.Progress](3.seconds)

  /** Spawn a Typed ChainDownloader (converted to a Classic ref for `!`). `findBestStoredHeader` probes block 1; mocking
    * it as missing makes the binary search return 0 immediately, leaving the actor in `downloading` with empty queues —
    * no real blockchain or peer infrastructure required. `peersScanInterval` is 1h in TestSyncConfig, so the periodic
    * peer scan never fires during a test (the immediate startup poll lands harmlessly on a fresh probe).
    */
  private def newDownloader(): (TypedActorRef[ChainDownloader.Command], AppStateStorage, TestProbe) =
    val blockchainReader = mock[BlockchainReader]
    val blockchainWriter = mock[BlockchainWriter]
    val appStateStorage = new AppStateStorage(EphemDataSource())
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    blockchainReader.getBlockHeaderByNumber
      .expects(BigInt(1))
      .returning(None)
      .anyNumberOfTimes()

    val downloader: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = blockchainReader,
          blockchainWriter = blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager.ref,
          peerEventBus = peerEventBus.ref,
          syncConfig = defaultSyncConfig,
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-${System.nanoTime()}"
      )

    (downloader, appStateStorage, networkPeerManager)

package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.LegacyReceipt
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SuccessOutcome
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.ledger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
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
import com.chipprbots.ethereum.rlp.rawDecode
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
    val req = receiptRequest(Capability.ETH69, receiptsRootOf(Seq(fixtureReceipt)), blacklistDuration = 5.seconds)

    // Deliver as the real PeerEventBusActor would: publish MessageFromPeer to the subscriber it captured.
    req.prhAdapter ! PeerEvent.MessageFromPeer(receipts69(req.requestId, fixtureReceipt), req.peerId)

    // The response travels test-thread -> PeerRequestHandler (child actor) -> downloader's prhResultAdapter
    // before handleReceipts69's store+commit runs — a GetProgress sent right after, straight to `downloader`
    // with no intermediate hop, could outrace it. Poll the observable storage effect instead of relying on a
    // single round-trip to win that race.
    eventually {
      req.storage.blockchainReader.getReceiptsByHash(req.header.hash) shouldBe Some(Seq(fixtureReceipt))
    }

    // Once the store is observed, a fresh GetProgress is guaranteed to be processed after it (same target actor,
    // sent from this point on) — confirms the stats counter moved too, not just the storage side effect.
    val progress = expectProgress(req.downloader)
    progress.receiptsDownloaded shouldBe BigInt(1)

    testKit.stop(req.downloader)
  }

  // A reply is matched to the request by position, and a fukuii server used to skip blocks it lacked, so a shifted
  // reply put later blocks' receipts under earlier hashes. Receipts must hash to their header's receiptsRoot before
  // they are stored; a reply that does not is dropped, the block re-queued, and the peer blacklisted.
  it should "not store receipts that do not hash to the header's receiptsRoot, and request them again" taggedAs UnitTest in {
    val req = receiptRequest(Capability.ETH69, receiptsRootOf(Seq(fixtureReceipt)), blacklistDuration = 50.millis)
    val wrongReceipt: Receipt =
      LegacyReceipt(SuccessOutcome, BigInt(42000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil)

    req.prhAdapter ! PeerEvent.MessageFromPeer(receipts69(req.requestId, wrongReceipt), req.peerId)

    // Once the peer's blacklist lapses, the block is requested again rather than marked done.
    val again = req.networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    again.message.underlyingMsg match
      case ETHPackets.GetReceipts(_, hashes) => hashes shouldBe Seq(req.header.hash.value)
      case other                             => fail(s"expected the block's receipts to be requested again, got $other")
    req.storage.blockchainReader.getReceiptsByHash(req.header.hash) shouldBe None
    expectProgress(req.downloader).receiptsDownloaded shouldBe BigInt(0)

    testKit.stop(req.downloader)
  }

  // The ETC path: core-geth serves eth/68, decoded inline in handleReceipts. go-ethereum's own eth/68 encoding of six
  // receipts (legacy, a failed type-2 with a log, type-1, a pre-Byzantium state root, types 3 and 4) must pass the
  // receiptsRoot check against go-ethereum's root for them (ReceiptWireFormatSpec) and be stored.
  it should "store go-ethereum's eth/68 receipts when they hash to the header's receiptsRoot" taggedAs UnitTest in {
    val gethRoot =
      TrieRoot(ByteString(Hex.decode("41dece3b55a3dc635cae82a36884aa29aa401ced1423803347ea54b7a2700940")))
    val req = receiptRequest(Capability.ETH68, gethRoot, blacklistDuration = 5.seconds)
    val gethEth68BlockList: Array[Byte] =
      val is = getClass.getResourceAsStream("/eth68-receipt-list-go-ethereum.rlp")
      try is.readAllBytes()
      finally is.close()

    req.prhAdapter ! PeerEvent.MessageFromPeer(
      ETHPackets.Receipts68(req.requestId, RLPList(rawDecode(gethEth68BlockList))),
      req.peerId
    )

    eventually {
      req.storage.blockchainReader.getReceiptsByHash(req.header.hash).map(_.size) shouldBe Some(6)
    }
    testKit.stop(req.downloader)
  }

  private val fixtureReceipt: Receipt =
    LegacyReceipt(SuccessOutcome, BigInt(21000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil)

  private def receipts69(requestId: BigInt, receipt: Receipt): ETHPackets.Receipts69 =
    ETHPackets.Receipts69(requestId, RLPList(RLPList(ETHPackets.ReceiptBloomFreeEnc(receipt).toRLPEncodable)))

  private def receipts70(
      requestId: BigInt,
      lastBlockIncomplete: Boolean,
      blocks: Seq[Seq[Receipt]]
  ): ETHPackets.Receipts70 =
    ETHPackets.Receipts70(
      requestId,
      lastBlockIncomplete,
      RLPList(blocks.map(receipts => RLPList(receipts.map(r => ETHPackets.ReceiptBloomFreeEnc(r).toRLPEncodable)*))*)
    )

  /** The receipts-trie root block validation checks (MptListValidator). */
  private def receiptsRootOf(receipts: Seq[Receipt]): TrieRoot =
    val trie = MerklePatriciaTrie[Int, Receipt](StateStorage.getReadOnlyStorage(EphemDataSource()))(
      MptListValidator.intByteArraySerializable,
      Receipt.byteArraySerializable
    )
    TrieRoot(ByteString(receipts.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash))

  /** The transactions-trie root StdBlockValidator.validateTransactionRoot checks. */
  private def transactionsRootOf(transactions: Seq[SignedTransaction]): TrieRoot =
    val trie = MerklePatriciaTrie[Int, SignedTransaction](StateStorage.getReadOnlyStorage(EphemDataSource()))(
      MptListValidator.intByteArraySerializable,
      SignedTransaction.byteArraySerializable
    )
    TrieRoot(ByteString(transactions.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash))

  // kec256(rlp([])) — the universal "empty list" hash. Every body used below has an empty ommers list, so this is
  // the ommersHash StdBlockValidator.validateOmmersHash always expects for them.
  private val EmptyOmmersHash: BlockHash =
    BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347")))

  /** A header whose transactionsRoot/ommersHash genuinely match `body` (StdBlockValidator.validateHeaderAndBody, the
    * check handleBodies now makes — #18/#C). BlockHelpers.generateBlock/generateChain deliberately do not keep a
    * block's header and body cryptographically consistent (they inherit the parent's roots unchanged and are reused
    * across the suite for tests where that property doesn't matter), so any header used to carry a body through
    * handleBodies in this file needs its roots computed for real. Every body here has an empty ommers list.
    */
  private def headerMatching(template: BlockHeader, body: BlockBody): BlockHeader =
    template.copy(transactionsRoot = transactionsRootOf(body.transactionList), ommersHash = EmptyOmmersHash)

  private def signedTx(payloadSeed: ByteString): SignedTransaction =
    SignedTransaction.sign(BlockHelpers.defaultTx.copy(payload = payloadSeed), BlockHelpers.keyPair, None)

  final private case class ReceiptRequest(
      storage: EphemBlockchainTestSetup,
      downloader: TypedActorRef[ChainDownloader.Command],
      networkPeerManager: TestProbe,
      prhAdapter: TypedActorRef[PeerEvent],
      requestId: BigInt,
      header: BlockHeader,
      peerId: PeerId
  )

  /** Drives the real protocol end to end with real EphemDataSource-backed storage (EphemBlockchainTestSetup) rather
    * than mocking ChainDownloader's internals: real GetHandshakedPeers poll, real PeerRequestHandler spawn and
    * PeerEventBus subscriptions. Leaves the downloader having sent one peer, at `capability`, a GetReceipts for one
    * stored block whose header carries `receiptsRoot`.
    */
  private def receiptRequest(
      capability: Capability,
      receiptsRoot: TrieRoot,
      blacklistDuration: FiniteDuration
  ): ReceiptRequest =
    val storage = new EphemBlockchainTestSetup {}
    // Same data source as the block store: a receipt write and its cursor commit as one batch.
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    // One header stored beyond genesis with its body already present but no receipts. findBestStoredHeader's
    // queue-rebuild pass (triggered by Start) then seeds only receiptsQueue — with a single peer and bodies
    // taking dispatch priority over receipts, an empty bodiesQueue is what guarantees the one available
    // request slot goes to a receipts fetch instead.
    val header: BlockHeader = BlockHelpers
      .generateBlock(BlockHelpers.genesis)
      .header
      .copy(receiptsRoot = receiptsRoot)
    storage.blockchainWriter
      .storeBlockHeader(header)
      .and(storage.blockchainWriter.storeBlockBody(header.hash, BlockBody(Nil, Nil)))
      .commit()
    // Fast-skip cursor (#1169): tells findBestStoredHeader block 1 is our best header without needing genesis
    // itself in storage.
    appStateStorage.putBackfillBestHeader(BigInt(1)).commit()

    val peerId = PeerId(s"$capability-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      capability,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("best-hash"),
      ByteString("genesis-hash")
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
          syncConfig = defaultSyncConfig.copy(blacklistDuration = blacklistDuration),
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
        hashes shouldBe Seq(header.hash.value)
        reqId
      case other => fail(s"expected a plain GetReceipts (same wire form as eth/68), got $other")

    ReceiptRequest(storage, downloader, networkPeerManager, prhAdapter, requestId, header, peerId)

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

  // ── Defect 3 (#32): eth/70 partial-receipt resume ordering ─────────────────────────────────────────
  //
  // requestReceipts only ever sends firstBlockResumeIdx for batch.head — GetReceipts70's wire format can only
  // resume ONE block per request (go-ethereum's serviceGetReceiptsQuery70 only reads index 0). handleReceipts70's
  // own re-queue used to push any hashes the peer never even attempted ("remaining") in FRONT of the hash it just
  // buffered a partial for, so the very next batch could have the buffered-partial hash anywhere but batch.head.
  // The peer then (correctly, per protocol) sends that block's receipts fresh from index 0, and the old code
  // merged them with the STALE buffered prefix — duplicating the first k receipts, failing the receiptsRoot
  // check, re-queuing the block a third time, and blacklisting the peer that answered honestly.
  it should "not duplicate a truncated eth/70 block's receipts when it is displaced from the next batch's head" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    // Ascending rescan order (#33 follow-up): header1 (the lower block number) is batch.head in round 1, so it
    // carries the 3-receipt set that gets truncated; header2 is the "remaining" (never-attempted) one with a
    // single receipt — the reverse pairing from before the rescan order flipped from descending to ascending.
    val r0 = LegacyReceipt(SuccessOutcome, BigInt(30000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil)
    val r1 = LegacyReceipt(SuccessOutcome, BigInt(31000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil)
    val r2 = LegacyReceipt(SuccessOutcome, BigInt(32000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil)
    val header1Receipts: Seq[Receipt] = Seq(r0, r1, r2)
    val header2Receipts: Seq[Receipt] =
      Seq(LegacyReceipt(SuccessOutcome, BigInt(21000), BloomFilter(ledger.BloomFilter.create(Nil)), Nil))

    val chain = BlockHelpers.generateChain(2, BlockHelpers.genesis)
    val header1: BlockHeader = chain(0).header.copy(receiptsRoot = receiptsRootOf(header1Receipts))
    val header2: BlockHeader = chain(1).header.copy(receiptsRoot = receiptsRootOf(header2Receipts))

    storage.blockchainWriter
      .storeBlockHeader(header1)
      .and(storage.blockchainWriter.storeBlockBody(header1.hash, BlockBody(Nil, Nil)))
      .and(storage.blockchainWriter.storeBlockHeader(header2))
      .and(storage.blockchainWriter.storeBlockBody(header2.hash, BlockBody(Nil, Nil)))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(2)).commit()

    val peerId = PeerId("eth70-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH70,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("best-hash"),
      ByteString("genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(2),
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
          syncConfig = defaultSyncConfig.copy(blacklistDuration = 5.seconds),
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-eth70-resume-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer -> peerInfo))
    val peerAddSub = peerEventBus.expectMsgType[SubscribeCmd](5.seconds)
    peerAddSub.to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peerId))

    downloader ! ChainDownloader.Start(BigInt(2))
    downloader ! ChainDownloader.BoostConcurrency(4)

    val prhSubs1 = (1 to 2).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    val prhAdapter: TypedActorRef[PeerEvent] = prhSubs1
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
            if codes.contains(Codes.ReceiptsCode) && pid == peerId =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(ReceiptsCode) SubscribeCmd among: $prhSubs1"))

    // Round 1: findBestStoredHeader's restart-rebuild scan now walks block numbers UP from the floor (#33
    // follow-up — ascending, so the lowest missing block is dispatched first), so the queue it seeds is
    // [header1.hash, header2.hash] — header1 is batch.head, and is the one truncated below.
    val firstSend = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    val firstReqId = firstSend.message.underlyingMsg match
      case ETHPackets.GetReceipts70(reqId, firstIdx, hashes) =>
        firstIdx shouldBe 0L
        hashes shouldBe Seq(header1.hash.value, header2.hash.value)
        reqId
      case other => fail(s"expected GetReceipts70, got $other")

    // Peer truncates mid-header1 — 2 of its 3 receipts, lastBlockIncomplete=true — and never even attempts
    // header2. This is what pushes header2 ("remaining") in front of header1's buffered partial in the old code.
    prhAdapter ! PeerEvent.MessageFromPeer(
      receipts70(firstReqId, lastBlockIncomplete = true, Seq(Seq(r0, r1))),
      peerId
    )

    // Round 2: whichever order the retry lands in, header2 must appear. This is a NEW GetReceipts70 —
    // requestReceipts spawns a fresh PeerRequestHandler per request, which is a one-shot actor that subscribes
    // (and stops) once — so round 2 needs its own freshly-captured adapter, not the round-1 one above.
    val secondSend = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    val (secondReqId, secondHashes) = secondSend.message.underlyingMsg match
      case ETHPackets.GetReceipts70(reqId, _, hashes) =>
        hashes.toSet shouldBe Set(header1.hash.value, header2.hash.value)
        (reqId, hashes)
      case other => fail(s"expected a second GetReceipts70, got $other")

    // Round 1's now-finished PeerRequestHandler unsubscribes (UnsubscribeAllCmd) before round 2's handler
    // subscribes, so the next 2 SubscribeCmds are no longer guaranteed to be the very next 2 messages on this
    // probe — collect messages until 2 SubscribeCmds are found, ignoring anything else interleaved.
    val prhSubs2 = collectSubscribes(peerEventBus, count = 2)
    val prhAdapter2: TypedActorRef[PeerEvent] = prhSubs2
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
            if codes.contains(Codes.ReceiptsCode) && pid == peerId =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(ReceiptsCode) SubscribeCmd among: $prhSubs2"))

    // Peer answers fully this time: header2's single receipt, and header1's receipts FRESH from index 0 (it was
    // never told to resume header1, wherever header1 landed in this batch) — all 3 receipts, not a resumed 1.
    val secondBlocks = secondHashes.map(h => if h == header1.hash.value then header1Receipts else header2Receipts)
    prhAdapter2 ! PeerEvent.MessageFromPeer(
      receipts70(secondReqId, lastBlockIncomplete = false, secondBlocks),
      peerId
    )

    // Fixed code: header1 stores exactly its real 3 receipts (no duplication), the receiptsRoot check passes, and
    // no third round is needed. Buggy code: the stale 2-receipt buffer is prepended to the fresh 3, the resulting
    // 5-receipt list fails the receiptsRoot check, header1 is re-queued, and the (blameless) peer is blacklisted —
    // this `eventually` never observes the store and times out.
    eventually(timeout(3.seconds), interval(50.millis)) {
      storage.blockchainReader.getReceiptsByHash(header1.hash) shouldBe Some(header1Receipts)
    }
    storage.blockchainReader.getReceiptsByHash(header2.hash) shouldBe Some(header2Receipts)

    testKit.stop(downloader)
  }

  // ── Defect 4 (#33): backfill body cursor could skip past a gap after a crash ───────────────────────
  //
  // putBackfillBestBody used to record the HIGHEST block number just stored, not "everything at or below this is
  // on disk". Bodies are fetched by several peers concurrently and a failed batch is re-queued (7b7a61708), so
  // batches complete out of order: a higher block's body can land before a lower one that is still in flight.
  // findBestStoredHeader's restart rescan trusts the cursor as an unconditional floor (`i > bodyFloor` is the
  // only condition that triggers a presence check), so if the process "crashes" before the lower block's retry
  // completes, that gap was never re-queued on the next startup — the body was lost for good.
  it should "not lose a lower block's body to a gap after a crash, when a higher block's body lands first" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    // handleBodies now checks a delivered body against its header (#18/#C) — headerMatching gives each header a
    // transactionsRoot/ommersHash that actually match the empty BlockBody used below, so this test still isolates
    // #33's cursor-contiguity concern rather than tripping the new body-mismatch rejection path.
    val chain = BlockHelpers.generateChain(2, BlockHelpers.genesis)
    val emptyBody = BlockBody(Nil, Nil)
    val header1: BlockHeader =
      headerMatching(chain(0).header, emptyBody) // its body is the one still "in flight" when the crash happens
    val header2: BlockHeader = headerMatching(chain(1).header, emptyBody) // its body lands first

    storage.blockchainWriter
      .storeBlockHeader(header1)
      .and(storage.blockchainWriter.storeBlockHeader(header2))
      .and(storage.blockchainWriter.storeReceipts(header1.hash, Seq.empty))
      .and(storage.blockchainWriter.storeReceipts(header2.hash, Seq.empty))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(2)).commit()

    def mkPeer(id: PeerId): Peer =
      Peer(id, new InetSocketAddress("127.0.0.1", 0), TestProbe(id.value).ref, incomingConnection = false)
    def mkInfo: PeerInfo =
      val status = RemoteStatus(
        Capability.ETH68,
        1,
        ChainWeight.totalDifficultyOnly(1),
        ByteString("best-hash"),
        ByteString("genesis-hash")
      )
      PeerInfo(
        status,
        forkAccepted = true,
        chainWeight = status.chainWeight,
        maxBlockNumber = BigInt(2),
        bestBlockHash = status.bestHash
      )

    val peer1Id = PeerId("body-peer-1")
    val peer2Id = PeerId("body-peer-2")
    val peer1 = mkPeer(peer1Id)
    val peer2 = mkPeer(peer2Id)

    // blockBodiesPerRequest=1 so each peer's batch is exactly one hash — this is what lets one block's body be
    // answered while the other is still outstanding, instead of both landing in a single reply.
    val splitSyncConfig = defaultSyncConfig.copy(blockBodiesPerRequest = 1)

    val downloader1: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = storage.blockchainReader,
          blockchainWriter = storage.blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager.ref,
          peerEventBus = peerEventBus.ref,
          syncConfig = splitSyncConfig,
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-gap-crash-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer1 -> mkInfo, peer2 -> mkInfo))

    val peerAddSubs = (1 to 2).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    peerAddSubs.map(_.to).toSet shouldBe Set(
      PeerDisconnectedClassifier(PeerSelector.WithId(peer1Id)),
      PeerDisconnectedClassifier(PeerSelector.WithId(peer2Id))
    )

    downloader1 ! ChainDownloader.Start(BigInt(2))
    downloader1 ! ChainDownloader.BoostConcurrency(4)

    // Two GetBlockBodies requests go out, one hash each, one per peer.
    val sends = (1 to 2).map(_ => networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds))
    val sendForHeader2 = sends
      .find(_.message.underlyingMsg match
        case ETHPackets.GetBlockBodies(_, hashes) => hashes == Seq(header2.hash.value)
        case _                                    => false
      )
      .getOrElse(fail(s"no GetBlockBodies for header2 among: $sends"))
    sends
      .find(_.message.underlyingMsg match
        case ETHPackets.GetBlockBodies(_, hashes) => hashes == Seq(header1.hash.value)
        case _                                    => false
      )
      .getOrElse(fail(s"no GetBlockBodies for header1 among: $sends"))

    val reqIdForHeader2 = sendForHeader2.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(reqId, _) => reqId
      case other                               => fail(s"expected GetBlockBodies, got $other")

    val prhSubs = (1 to 4).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    def messageAdapterFor(pid: PeerId): TypedActorRef[PeerEvent] = prhSubs
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(p)), ref)
            if codes.contains(Codes.BlockBodiesCode) && p == pid =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(BlockBodiesCode) SubscribeCmd for $pid among: $prhSubs"))

    // header2's body lands; header1's request is left unanswered — simulating it still being in flight when the
    // crash below happens.
    messageAdapterFor(sendForHeader2.peerId) ! PeerEvent.MessageFromPeer(
      ETHPackets.BlockBodies(reqIdForHeader2, Seq(BlockBody(Nil, Nil))),
      sendForHeader2.peerId
    )

    eventually(timeout(3.seconds), interval(50.millis)) {
      storage.blockchainReader.getBlockBodyByHash(header2.hash) shouldBe Some(BlockBody(Nil, Nil))
    }
    // The crux of #33: the cursor must still reflect the true contiguous prefix (nothing — header1 is still
    // missing), not the highest block a batch happened to store.
    appStateStorage.getBackfillBestBody() shouldBe BigInt(0)

    // "Crash": the in-memory downloader (and its outstanding header1 request) is gone; only storage and the
    // cursor survive, exactly as they would across a process restart.
    testKit.stop(downloader1)

    val networkPeerManager2 = TestProbe()
    val peerEventBus2 = TestProbe()
    val replyToProbe2 = TestProbe()
    val downloader2: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = storage.blockchainReader,
          blockchainWriter = storage.blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager2.ref,
          peerEventBus = peerEventBus2.ref,
          syncConfig = splitSyncConfig,
          replyTo = replyToProbe2.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-gap-crash-restart-${System.nanoTime()}"
      )

    val handshakeReq2 = networkPeerManager2.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq2.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer1 -> mkInfo))
    peerEventBus2.expectMsgType[SubscribeCmd](5.seconds) // peer registration

    downloader2 ! ChainDownloader.Start(BigInt(2))
    downloader2 ! ChainDownloader.BoostConcurrency(4)

    // Fixed code: the cursor stayed at 0, so findBestStoredHeader's rescan re-queues header1 and this request
    // goes out. Buggy code: the cursor was wrongly advanced to 2, bodyFloor excludes both blocks from the
    // rescan, bodiesQueue stays empty, and checkCompletion declares the backfill done instead — this never
    // arrives.
    val restartSend = networkPeerManager2.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    restartSend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(_, hashes) => hashes shouldBe Seq(header1.hash.value)
      case other                                => fail(s"expected a re-issued GetBlockBodies for header1, got $other")

    testKit.stop(downloader2)
  }

  // ── Defect 5 (#C): ChainDownloader.handleBodies stored a peer's body under a requested hash unconditionally ──
  //
  // Regular sync validates a fetched body against its header before it reaches storage
  // (BlockFetcherState.validateBodies -> blockValidator.validateHeaderAndBody, checking transactionsRoot and
  // ommersHash). The SNAP backfill path did not: handleBodies stored every (hash, body) pair from
  // requestedHashes.zip(bodies) via blockchainWriter.storeBlockBody unconditionally. A reply is matched to the
  // request by position, so a peer that sends a body for the wrong block (or a corrupted one) would land under
  // whatever hash it was requested against. Fixed the same way receipts were (e158fdf8b): each body is checked
  // with StdBlockValidator.validateHeaderAndBody (the same call regular sync makes) before it is stored; storing
  // stops at the first mismatch, and everything from there onward is re-queued and the peer blacklisted.

  it should "store a batch of bodies that all match their headers" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    val chain = BlockHelpers.generateChain(2, BlockHelpers.genesis)
    val body1 = BlockBody(Nil, Nil)
    val body2 = BlockBody(List(signedTx(ByteString("valid-batch-h2"))), Nil)
    val header1: BlockHeader = headerMatching(chain(0).header, body1)
    val header2: BlockHeader = headerMatching(chain(1).header, body2)

    storage.blockchainWriter
      .storeBlockHeader(header1)
      .and(storage.blockchainWriter.storeBlockHeader(header2))
      .and(storage.blockchainWriter.storeReceipts(header1.hash, Seq.empty))
      .and(storage.blockchainWriter.storeReceipts(header2.hash, Seq.empty))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(2)).commit()

    val peerId = PeerId("valid-bodies-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("best-hash"),
      ByteString("genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(2),
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
        s"chain-downloader-valid-bodies-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer -> peerInfo))
    val peerAddSub = peerEventBus.expectMsgType[SubscribeCmd](5.seconds)
    peerAddSub.to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peerId))

    downloader ! ChainDownloader.Start(BigInt(2))
    downloader ! ChainDownloader.BoostConcurrency(4)

    val prhSubs = (1 to 2).map(_ => peerEventBus.expectMsgType[SubscribeCmd](5.seconds))
    val prhAdapter: TypedActorRef[PeerEvent] = prhSubs
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
            if codes.contains(Codes.BlockBodiesCode) && pid == peerId =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(BlockBodiesCode) SubscribeCmd among: $prhSubs"))

    val sendCmd = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    val (reqId, hashes) = sendCmd.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(reqId, hashes) =>
        hashes.toSet shouldBe Set(header1.hash.value, header2.hash.value)
        (reqId, hashes)
      case other => fail(s"expected GetBlockBodies, got $other")

    val bodiesInOrder = hashes.map(h => if h == header1.hash.value then body1 else body2)
    prhAdapter ! PeerEvent.MessageFromPeer(ETHPackets.BlockBodies(reqId, bodiesInOrder), peerId)

    eventually(timeout(3.seconds), interval(50.millis)) {
      storage.blockchainReader.getBlockBodyByHash(header1.hash) shouldBe Some(body1)
    }
    storage.blockchainReader.getBlockBodyByHash(header2.hash) shouldBe Some(body2)
    // Both headers' bodies and (pre-stored) receipts are now present and bestHeaderNumber has reached target, so
    // checkCompletion declares the backfill done and clears the backfill cursors (#1169) — that's the correct,
    // existing behavior for a genuinely finished backfill, not something this test should fight. Completion,
    // observed via the Done reply, is the right proof that the batch was accepted without any reject/re-queue.
    replyToProbe.expectMsg(3.seconds, ChainDownloader.Done)

    testKit.stop(downloader)
  }

  it should "reject a body that does not match its header, re-queue it, and blacklist the peer" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    val emptyBody = BlockBody(Nil, Nil)
    val header: BlockHeader = headerMatching(BlockHelpers.generateBlock(BlockHelpers.genesis).header, emptyBody)

    storage.blockchainWriter
      .storeBlockHeader(header)
      .and(storage.blockchainWriter.storeReceipts(header.hash, Seq.empty))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(1)).commit()

    val peerId = PeerId("wrong-body-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("best-hash"),
      ByteString("genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(1),
      bestBlockHash = peerStatus.bestHash
    )

    // RequestFailed / rejection both blacklist the peer, and with only one handshaked peer the re-dispatch of the
    // re-queued hash can only happen once that blacklist window expires. Shrink it (matches "Defect 2"'s pattern).
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
        s"chain-downloader-wrong-body-${System.nanoTime()}"
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
    val reqId = firstSend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(reqId, hashes) =>
        hashes shouldBe Seq(header.hash.value)
        reqId
      case other => fail(s"expected GetBlockBodies, got $other")

    // Doesn't hash to header's (empty-body) transactionsRoot.
    val wrongBody = BlockBody(List(signedTx(ByteString("wrong"))), Nil)
    prhAdapter ! PeerEvent.MessageFromPeer(ETHPackets.BlockBodies(reqId, Seq(wrongBody)), peerId)

    // Once the peer's blacklist lapses, the block is requested again rather than marked done.
    val again = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    again.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(_, hashes) => hashes shouldBe Seq(header.hash.value)
      case other                                => fail(s"expected the block's body to be requested again, got $other")

    storage.blockchainReader.getBlockBodyByHash(header.hash) shouldBe None

    testKit.stop(downloader)
  }

  it should "store only the leading prefix of a batch when a later body does not match its header" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    val chain = BlockHelpers.generateChain(2, BlockHelpers.genesis)
    val correctBody1 = BlockBody(Nil, Nil)
    val correctBody2 = BlockBody(List(signedTx(ByteString("mid-batch-h2"))), Nil)
    val header1: BlockHeader = headerMatching(chain(0).header, correctBody1)
    val header2: BlockHeader = headerMatching(chain(1).header, correctBody2)

    storage.blockchainWriter
      .storeBlockHeader(header1)
      .and(storage.blockchainWriter.storeBlockHeader(header2))
      .and(storage.blockchainWriter.storeReceipts(header1.hash, Seq.empty))
      .and(storage.blockchainWriter.storeReceipts(header2.hash, Seq.empty))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(2)).commit()

    val peerId = PeerId("mid-batch-mismatch-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("best-hash"),
      ByteString("genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(2),
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
          // Short blacklist so the retry (the whole point of this test) lands well inside the 5-second
          // expectMsgType window below — matches the "Defect 2" / receipts-reject tests' pattern.
          syncConfig = defaultSyncConfig.copy(blacklistDuration = 50.millis),
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4
        ),
        s"chain-downloader-mid-batch-mismatch-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer -> peerInfo))
    val peerAddSub = peerEventBus.expectMsgType[SubscribeCmd](5.seconds)
    peerAddSub.to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peerId))

    downloader ! ChainDownloader.Start(BigInt(2))
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
    val (reqId, hashes) = firstSend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(reqId, hashes) =>
        hashes.toSet shouldBe Set(header1.hash.value, header2.hash.value)
        (reqId, hashes)
      case other => fail(s"expected GetBlockBodies, got $other")

    // Whichever hash lands first in this batch gets its real (matching) body and must be stored; whichever lands
    // second gets a body that does NOT match its header and must be rejected — this is what "stores only the
    // leading prefix" means, independent of which literal header the queue happens to put first (findBestStoredHeader's
    // rescan order is not this test's concern — see #33's follow-up on that ordering).
    val firstHash = hashes.head
    val secondHash = hashes(1)
    val bodyForFirst = if firstHash == header1.hash.value then correctBody1 else correctBody2
    val wrongBody = BlockBody(List(signedTx(ByteString("mid-batch-wrong"))), Nil)
    prhAdapter ! PeerEvent.MessageFromPeer(ETHPackets.BlockBodies(reqId, Seq(bodyForFirst, wrongBody)), peerId)

    eventually(timeout(3.seconds), interval(50.millis)) {
      storage.blockchainReader.getBlockBodyByHash(BlockHash(firstHash)) shouldBe Some(bodyForFirst)
    }
    storage.blockchainReader.getBlockBodyByHash(BlockHash(secondHash)) shouldBe None

    // Only the second hash is re-requested — the first already landed and must not be asked for again.
    val retrySend = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    retrySend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(_, retryHashes) => retryHashes shouldBe Seq(secondHash)
      case other => fail(s"expected a re-issued GetBlockBodies for the second hash only, got $other")

    testKit.stop(downloader)
  }

  // ── Defect 6 (#33 follow-up): a single advanceBodyCursor/advanceReceiptCursor call was unbounded ──────────
  //
  // Forge's ETC review of #33 (b4b2a50b8): findBestStoredHeader's rebuild queued missing blocks highest-first, so
  // the lowest missing block was dispatched LAST. Nothing could advance the cursor until it landed, and once it
  // did, a single advanceBodyCursor call could have an unboundedly long already-stored run to walk in one actor
  // message — on ETC mainnet (~25M blocks), estimated at ~75M reads, minutes warm / 1-2h cold after a restart deep
  // into a backfill. Fixed two ways: the rescan now queues ascending (proven by the reordered assertions in the
  // eth/70 test above), and each cursor-advance call is separately capped at `cursorScanCap` blocks (and never
  // past `targetBlock`) regardless of dispatch order, so a store can never trigger an unbounded synchronous scan.
  it should "advance the body cursor by at most cursorScanCap per call, reaching the end over repeated calls" taggedAs UnitTest in {
    val storage = new EphemBlockchainTestSetup {}
    val appStateStorage = storage.storagesInstance.storages.appStateStorage
    val networkPeerManager = TestProbe()
    val peerEventBus = TestProbe()
    val replyToProbe = TestProbe()

    val cap = 3L
    val emptyBody = BlockBody(Nil, Nil)
    val chain = BlockHelpers.generateChain(10, BlockHelpers.genesis)
    val headers = chain.map(b => headerMatching(b.header, emptyBody))

    // Headers 1-10 and receipts 1-10 (empty) are all already on disk; bodies 1-6 are too — only 7-10 are missing,
    // so the rescan queues just those four, ascending. The cursor itself is never touched here, so it starts at 0.
    headers
      .map(h => storage.blockchainWriter.storeBlockHeader(h))
      .reduce(_.and(_))
      .and(headers.map(h => storage.blockchainWriter.storeReceipts(h.hash, Seq.empty)).reduce(_.and(_)))
      .and(headers.take(6).map(h => storage.blockchainWriter.storeBlockBody(h.hash, emptyBody)).reduce(_.and(_)))
      .commit()
    appStateStorage.putBackfillBestHeader(BigInt(10)).commit()

    val peerId = PeerId("cap-peer")
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), TestProbe(peerId.value).ref, incomingConnection = false)
    val peerStatus = RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(1),
      ByteString("best-hash"),
      ByteString("genesis-hash")
    )
    val peerInfo = PeerInfo(
      peerStatus,
      forkAccepted = true,
      chainWeight = peerStatus.chainWeight,
      maxBlockNumber = BigInt(10),
      bestBlockHash = peerStatus.bestHash
    )

    // targetBlock (10) matches the highest stored header exactly, so bestHeaderNumber >= targetBlock already —
    // no GetBlockHeaders is ever dispatched, keeping this a single-peer, bodies-only flow. blockBodiesPerRequest=1
    // so each round below is exactly one hash, under this test's full control.
    val downloader: TypedActorRef[ChainDownloader.Command] = testKit
      .spawn(
        ChainDownloader(
          blockchainReader = storage.blockchainReader,
          blockchainWriter = storage.blockchainWriter,
          appStateStorage = appStateStorage,
          networkPeerManager = networkPeerManager.ref,
          peerEventBus = peerEventBus.ref,
          syncConfig = defaultSyncConfig.copy(blockBodiesPerRequest = 1),
          replyTo = replyToProbe.ref,
          maxConcurrentRequests = 4,
          cursorScanCap = cap
        ),
        s"chain-downloader-cursor-cap-${System.nanoTime()}"
      )

    val handshakeReq = networkPeerManager.expectMsgType[NetworkPeerManagerActor.GetHandshakedPeersCmd](5.seconds)
    handshakeReq.replyTo ! NetworkPeerManagerActor.HandshakedPeers(Map(peer -> peerInfo))
    val peerAddSub = peerEventBus.expectMsgType[SubscribeCmd](5.seconds)
    peerAddSub.to shouldBe PeerDisconnectedClassifier(PeerSelector.WithId(peerId))

    downloader ! ChainDownloader.Start(BigInt(10))
    downloader ! ChainDownloader.BoostConcurrency(4)

    // Delivers the response for the next expected single-hash GetBlockBodies request and returns the new cursor
    // value observed immediately after.
    def deliverNextBodyAndReadCursor(expectedHash: ByteString): BigInt =
      val send = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
      val reqId = send.message.underlyingMsg match
        case ETHPackets.GetBlockBodies(reqId, hashes) =>
          hashes shouldBe Seq(expectedHash)
          reqId
        case other => fail(s"expected a single-hash GetBlockBodies, got $other")
      val subs = collectSubscribes(peerEventBus, count = 2)
      val adapter: TypedActorRef[PeerEvent] = subs
        .collectFirst {
          case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
              if codes.contains(Codes.BlockBodiesCode) && pid == peerId =>
            ref
        }
        .getOrElse(fail(s"no MessageClassifier(BlockBodiesCode) SubscribeCmd among: $subs"))
      adapter ! PeerEvent.MessageFromPeer(ETHPackets.BlockBodies(reqId, Seq(emptyBody)), peerId)
      eventually(timeout(3.seconds), interval(50.millis)) {
        storage.blockchainReader.getBlockBodyByHash(BlockHash(expectedHash)) shouldBe Some(emptyBody)
      }
      appStateStorage.getBackfillBestBody()

    // Round 1: delivering block 7's body lets the scan start from cursor+1=1, but it stops at the cap (3) even
    // though blocks 1-6 are ALL already contiguously present — proving one call never advances by more than the cap.
    deliverNextBodyAndReadCursor(headers(6).hash.value) shouldBe BigInt(3)
    // Round 2: resumes from where round 1 stopped and is capped again.
    deliverNextBodyAndReadCursor(headers(7).hash.value) shouldBe BigInt(6)
    // Round 3: with only 2 blocks (9, 10) left below cursorScanCap's next window (6+3=9, but block 10 also lands
    // within it since block 9 is the only gap before it), delivering block 9's body lets the scan reach the true
    // end at block 10 too — receipts are already all stored, so once bodies reach target the backfill completes
    // and clears its cursors (#1169): proven here via the Done reply rather than reading a cursor value that
    // would already be cleared by the time this call returns, the same reasoning as the "valid batch" test above.
    deliverNextBodyAndReadCursor(headers(8).hash.value) shouldBe BigInt(9)
    val bodySend = networkPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd](5.seconds)
    val lastReqId = bodySend.message.underlyingMsg match
      case ETHPackets.GetBlockBodies(reqId, hashes) =>
        hashes shouldBe Seq(headers(9).hash.value)
        reqId
      case other => fail(s"expected the final single-hash GetBlockBodies, got $other")
    val lastSubs = collectSubscribes(peerEventBus, count = 2)
    val lastAdapter: TypedActorRef[PeerEvent] = lastSubs
      .collectFirst {
        case SubscribeCmd(MessageClassifier(codes, PeerSelector.WithId(pid)), ref)
            if codes.contains(Codes.BlockBodiesCode) && pid == peerId =>
          ref
      }
      .getOrElse(fail(s"no MessageClassifier(BlockBodiesCode) SubscribeCmd among: $lastSubs"))
    lastAdapter ! PeerEvent.MessageFromPeer(ETHPackets.BlockBodies(lastReqId, Seq(emptyBody)), peerId)
    replyToProbe.expectMsg(3.seconds, ChainDownloader.Done)

    testKit.stop(downloader)
  }

  /** Collects the next `count` `SubscribeCmd`s seen on `probe`, skipping over anything else interleaved (e.g. an
    * `UnsubscribeAllCmd` from a just-finished PeerRequestHandler unsubscribing before a new one subscribes). Bounded so
    * a genuine wedge fails loudly instead of hanging.
    */
  private def collectSubscribes(probe: TestProbe, count: Int, maxAttempts: Int = 10): Seq[SubscribeCmd] =
    val collected = scala.collection.mutable.Buffer.empty[SubscribeCmd]
    var attempts = 0
    while collected.size < count && attempts < maxAttempts do
      attempts += 1
      probe.receiveOne(5.seconds) match
        case s: SubscribeCmd => collected += s
        case _               => ()
    collected.toSeq

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

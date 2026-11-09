package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.snap.PosHeaderFixtures
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags.*

class PivotHeaderBootstrapSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  val targetBlock: BigInt = 1000
  val correctHeader: BlockHeader = Fixtures.Blocks.Block3125369.header.copy(number = BlockNumber(targetBlock))
  val wrongHeader: BlockHeader = Fixtures.Blocks.Block3125369.header.copy(number = BlockNumber(999))

  val ds: EphemDataSource = EphemDataSource()
  val noopBatch: DataSourceBatchUpdate = DataSourceBatchUpdate(ds, Array.empty)

  val noopWriter: BlockchainWriter = new BlockchainWriter(null, null, null, null, null, null, null, null):
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate = noopBatch
    override def storeBlockHeaderByHashOnly(blockHeader: BlockHeader): DataSourceBatchUpdate = noopBatch

  val throwingWriter: BlockchainWriter = new BlockchainWriter(null, null, null, null, null, null, null, null):
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate =
      throw new RuntimeException("storage error")

  val testPeer: Peer = Peer(PeerId("test-peer"), new InetSocketAddress("127.0.0.1", 9999), TestProbe().ref, false)
  val testPeer2: Peer = Peer(PeerId("test-peer-2"), new InetSocketAddress("127.0.0.1", 9998), TestProbe().ref, false)

  /** Spawns a (Typed) PivotHeaderBootstrap whose `replyTo` is `parentProbe`, so Completed/Failed land on the probe.
    * Returns the Classic-adapted ref so existing assertions still work.
    */
  def mkBootstrap(
      peersClientProbe: TestProbe,
      parentProbe: TestProbe,
      writer: BlockchainWriter = noopWriter,
      maxAttempts: Int = 1,
      retryDelay: FiniteDuration = 50.millis,
      waitForPeerDelay: FiniteDuration = 50.millis,
      preferSnapPeers: Boolean = false,
      headerCheck: BlockHeader => Either[BlockHeaderError, BlockHeaderValid] = PivotHeaderBootstrap.AcceptAll
  ): ActorRef =
    testKit
      .spawn(
        PivotHeaderBootstrap(
          peersClient = peersClientProbe.ref.toTyped[PeersClient.Command],
          blockchainWriter = writer,
          targetBlock = targetBlock,
          replyTo = parentProbe.ref,
          syncConfig = null,
          maxAttempts = maxAttempts,
          initialRetryDelay = retryDelay,
          maxRetryDelay = retryDelay,
          waitForPeerDelay = waitForPeerDelay,
          preferSnapPeers = preferSnapPeers,
          headerCheck = headerCheck
        )
      )
      .toClassic

  "PivotHeaderBootstrap" should "send Completed to parent when peer returns the correct header" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "send Failed after maxAttempts when peer returns a wrong-number header" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(wrongHeader)))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "not send Failed on empty headers (WaitForPeer issued, no attempt consumed)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, waitForPeerDelay = 50.millis)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq.empty))

    // Empty headers → WaitForPeer; no attempt consumed → Failed must NOT arrive yet
    parent.expectNoMessage(200.millis)
  }

  it should "send Failed after maxAttempts on RequestFailed" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .RequestFailed(testPeer, BlacklistReason.RegularSyncRequestFailed("timeout"))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "fall back to BestPeerWithMinBlock and complete when preferSnapPeers is set but no SNAP peer available" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, preferSnapPeers = true)

    // First request uses BestSnapPeerWithMinBlockExcluding(target, {}) — no SNAP peer available at all
    val req1 = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    req1.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set.empty)
    req1.replyTo ! PeersClient.NoSuitablePeer

    // Fallback request uses BestPeerWithMinBlockExcluding — peer responds with the correct header
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "exclude a SNAP peer that returned empty headers and fall through to the next peer (Besu ETH69 monopoly fix)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    // maxAttempts=3 so budget isn't consumed by the empty-header WaitForPeer path
    mkBootstrap(peersClient, parent, preferSnapPeers = true, maxAttempts = 3, waitForPeerDelay = 50.millis)

    // Attempt 1: BestSnapPeerWithMinBlockExcluding(target, {}) picks testPeer (e.g. Besu with synthetic high TD).
    // testPeer returns empty headers → testPeer added to triedPeers, WaitForPeer issued.
    val req1 = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    req1.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set.empty)
    req1.replyTo ! PeersClient.Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq.empty))

    // WaitForPeer fires → retry: BestSnapPeerWithMinBlockExcluding(target, {testPeer}) → no SNAP peers left → NoSuitablePeer.
    // flatMap catches NoSuitablePeer and issues fallback: BestPeerWithMinBlockExcluding(target, {testPeer}).
    val req2 = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    req2.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set(testPeer.id))
    req2.replyTo ! PeersClient.NoSuitablePeer

    // Fallback: testPeer2 (e.g. core-geth, non-SNAP or lower-TD SNAP) returns the correct header.
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "send Failed to parent when blockchainWriter throws during storeBlockHeader" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, writer = throwingWriter)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds)
  }

  it should "try a different peer on the second attempt after the first returns a wrong header" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, maxAttempts = 2)

    // Attempt 1 — testPeer returns wrong header (added to triedPeers)
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(wrongHeader)))

    // Attempt 2 — testPeer2 (testPeer excluded by BestPeerWithMinBlockExcluding) returns correct header
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "schedule a WaitForPeer delay without consuming an attempt when pool returns NoSuitablePeer" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    // maxAttempts=1: if WaitForPeer incorrectly consumed an attempt, Failed would arrive during the wait
    mkBootstrap(peersClient, parent, maxAttempts = 1, waitForPeerDelay = 50.millis)

    // First request returns NoSuitablePeer → WaitForPeer scheduled, NOT Failed
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    // Must not send Failed during the WaitForPeer window
    parent.expectNoMessage(200.millis)

    // WaitForPeer fires, bootstrap retries — a fresh peer is now available
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "not store a header its check rejects: blacklist the peer that served it and ask another peer" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    val rejected = correctHeader.copy(difficulty = com.chipprbots.ethereum.domain.Difficulty(BigInt(1)))
    mkBootstrap(
      peersClient,
      parent,
      maxAttempts = 3,
      preferSnapPeers = true,
      headerCheck = rejectDifficultyOne
    )

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(rejected)))
    peersClient.expectMsg(
      3.seconds,
      PeersClient.BlacklistPeer(testPeer.id, BlacklistReason.BlockHeaderValidationFailed)
    )

    val retry = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    retry.peerSelector shouldBe PeersClient.BestSnapPeerWithMinBlockExcluding(targetBlock, Set(testPeer.id))
    retry.replyTo ! PeersClient.Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), Seq(correctHeader)))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(targetBlock, correctHeader))
  }

  it should "give up, not wait for a peer forever, when no eligible peer is left after a rejected header" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    val rejected = correctHeader.copy(difficulty = com.chipprbots.ethereum.domain.Difficulty(BigInt(1)))
    mkBootstrap(peersClient, parent, maxAttempts = 3, preferSnapPeers = true, headerCheck = rejectDifficultyOne)

    // The only peer that claims the target's height serves a header that fails the check.
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), Seq(rejected)))
    peersClient.expectMsg(
      3.seconds,
      PeersClient.BlacklistPeer(testPeer.id, BlacklistReason.BlockHeaderValidationFailed)
    )

    // The other peers are below the target: neither the SNAP selector nor its fallback finds one.
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Failed(PivotHeaderBootstrap.ExhaustedAfterRejection))
  }

  it should "still wait for a fresh peer when no peer is eligible and no header was rejected" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkBootstrap(peersClient, parent, maxAttempts = 3, preferSnapPeers = true, headerCheck = rejectDifficultyOne)

    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    // WaitForPeer fires after waitForPeerDelay and asks again; nothing is reported to the parent.
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    parent.expectNoMessage(100.millis)
  }

  /** Rejects a header with difficulty 1, as the header-only check rejects a header it cannot validate. */
  private val rejectDifficultyOne: BlockHeader => Either[BlockHeaderError, BlockHeaderValid] = h =>
    if h.difficulty.value == 1 then Left(BlockHeaderError.HeaderUnexpectedError("difficulty 1"))
    else Right(BlockHeaderValid)

  // ── Ancestry mode: the pivot below a CL head, walked back from its hash (real Sepolia headers) ──────────────────

  private val walkRun: IndexedSeq[BlockHeader] = PosHeaderFixtures.sepoliaRun // 11,860,000..11,860,069, ascending
  private val clHead: BlockHeader = walkRun.last
  private val Depth: Int = 64
  private val Requested: Int = PivotHeaderBootstrap.MaxAncestryHeaders

  /** The reverse run a peer serves from `clHead`, down to the pivot (Depth + 1 headers): the head first. */
  private val ancestry: Seq[BlockHeader] = walkRun.takeRight(Depth + 1).reverse
  private val ancestryPivot: BlockHeader = walkRun(walkRun.size - 1 - Depth)

  /** The whole fixture, from the head down: the run goes on 5 headers below the pivot. */
  private val longAncestry: Seq[BlockHeader] = walkRun.reverse

  /** Records the headers stored by hash only and the headers stored with their index entry. */
  final private class RecordingWriter extends BlockchainWriter(null, null, null, null, null, null, null, null):
    @volatile var byHashOnly: Vector[BlockHeader] = Vector.empty
    @volatile var indexed: Vector[BlockHeader] = Vector.empty
    override def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate =
      indexed :+= blockHeader
      noopBatch
    override def storeBlockHeaderByHashOnly(blockHeader: BlockHeader): DataSourceBatchUpdate =
      byHashOnly :+= blockHeader
      noopBatch

  def mkAncestry(
      peersClientProbe: TestProbe,
      parentProbe: TestProbe,
      maxAttempts: Int = 3,
      writer: BlockchainWriter = noopWriter,
      depth: Int = Depth,
      askTimeout: FiniteDuration = 3.seconds
  ): ActorRef =
    testKit
      .spawn(
        PivotHeaderBootstrap.applyAncestry(
          peersClient = peersClientProbe.ref.toTyped[PeersClient.Command],
          blockchainWriter = writer,
          headHash = clHead.hash.value,
          depth = depth,
          replyTo = parentProbe.ref,
          syncConfig = null,
          askTimeout = askTimeout,
          maxAttempts = maxAttempts,
          initialRetryDelay = 50.millis,
          maxRetryDelay = 50.millis,
          waitForPeerDelay = 50.millis
        )
      )
      .toClassic

  private val otherRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x5a)))

  private def ancestryOf(depth: Int, headers: Seq[BlockHeader], requested: Int = Requested) =
    PivotHeaderBootstrap.ancestryRun(clHead.hash.value, depth, requested, headers)

  "PivotHeaderBootstrap.ancestryRun" should "return the header depth below the head of a run that is its ancestry" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    ancestryOf(Depth, ancestry) shouldBe Right(PivotHeaderBootstrap.AncestryRun(ancestryPivot, ancestry.toIndexedSeq))
    ancestryPivot.number.value shouldBe clHead.number.value - Depth
    ancestryOf(0, Seq(clHead)).map(_.pivot) shouldBe Right(clHead)
    // A run that goes on below the pivot: the same pivot, and the pivot's ancestors in the linked part.
    ancestryOf(Depth, longAncestry) shouldBe Right(
      PivotHeaderBootstrap.AncestryRun(ancestryPivot, longAncestry.toIndexedSeq)
    )
  }

  it should "keep the pivot of a run that leaves the head's ancestry below it, and only the linked part" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val leavesBelow = longAncestry.updated(67, longAncestry(67).copy(extraData = ByteString("not this one")))
    ancestryOf(Depth, leavesBelow) shouldBe
      Right(PivotHeaderBootstrap.AncestryRun(ancestryPivot, longAncestry.take(67).toIndexedSeq))
  }

  it should "call a run that is the head's ancestry but short incomplete, not a fault" taggedAs UnitTest in {
    ancestryOf(Depth, ancestry.take(10)) shouldBe Left(PivotHeaderBootstrap.AncestryProblem.Incomplete(10))
    ancestryOf(Depth, Nil) shouldBe Left(PivotHeaderBootstrap.AncestryProblem.Incomplete(0))
  }

  // Besu answers the first header of a GetBlockHeaders by hash and the rest by its canonical number: after a reorg of the
  // head's ancestors an honest Besu peer serves a run that leaves the head's ancestry. Nothing of it is used, and the
  // peer is not blamed.
  it should "call a run that leaves the head's ancestry above the pivot unlinked, not a fault" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val altered = ancestryPivot.copy(stateRoot = otherRoot)
    altered.parentHash shouldBe ancestryPivot.parentHash
    ancestryOf(Depth, ancestry.init :+ altered) shouldBe Left(PivotHeaderBootstrap.AncestryProblem.Unlinked(Depth))
    val brokenMidWalk = ancestry.updated(30, ancestry(30).copy(extraData = ByteString("not this one")))
    ancestryOf(Depth, brokenMidWalk) shouldBe Left(PivotHeaderBootstrap.AncestryProblem.Unlinked(30))
    // A run that skips a block: the header after the gap is not its predecessor's parent.
    val skipping = ancestry.take(20) ++ ancestry.drop(21)
    ancestryOf(Depth - 1, skipping) shouldBe Left(PivotHeaderBootstrap.AncestryProblem.Unlinked(20))
  }

  it should "reject a run not from the head, and a run longer than asked" taggedAs (UnitTest, ConsensusTest) in {
    // The real chain, from the block below the head.
    ancestryOf(Depth, walkRun.dropRight(1).takeRight(Depth + 1).reverse) match
      case Left(PivotHeaderBootstrap.AncestryProblem.Mismatch(_, reason)) => reason shouldBe "AncestryHead"
      case other                                                          => fail(s"expected a mismatch, got $other")
    ancestryOf(Depth, ancestry, requested = Depth) match
      case Left(PivotHeaderBootstrap.AncestryProblem.Mismatch(_, reason)) => reason shouldBe "AncestryLength"
      case other                                                          => fail(s"expected a mismatch, got $other")
  }

  "PivotHeaderBootstrap.storeAncestry" should "store the pivot with its index entry and the rest of the run by hash" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val writer = new RecordingWriter
    val linked = longAncestry.toIndexedSeq
    PivotHeaderBootstrap.storeAncestry(writer, PivotHeaderBootstrap.AncestryRun(ancestryPivot, linked))
    writer.indexed shouldBe Vector(ancestryPivot)
    writer.byHashOnly.toSet shouldBe linked.filterNot(_ == ancestryPivot).toSet
  }

  "PivotHeaderBootstrap in ancestry mode" should "ask for the head's ancestry in reverse, store it and reply with the pivot below it" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    val writer = new RecordingWriter
    mkAncestry(peersClient, parent, writer = writer)

    val request = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    request.message.block shouldBe Right(clHead.hash.value)
    request.message.maxHeaders shouldBe BigInt(PivotHeaderBootstrap.MaxAncestryHeaders)
    request.message.reverse shouldBe true
    request.peerSelector shouldBe PeersClient.ExcludingPeers(Set.empty)
    request.replyTo ! PeersClient.Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), longAncestry))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(ancestryPivot.number.value, ancestryPivot))
    writer.indexed shouldBe Vector(ancestryPivot)
    writer.byHashOnly.size shouldBe longAncestry.size - 1
  }

  it should "skip, without blame, a peer whose run leaves the head's ancestry above the pivot (Besu after a reorg)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent)
    val altered = ancestry.init :+ ancestryPivot.copy(stateRoot = otherRoot)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), altered))

    // No blacklisting: the next message is the request to another peer.
    val retry = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    retry.peerSelector shouldBe PeersClient.ExcludingPeers(Set(testPeer.id))
    retry.replyTo ! PeersClient.Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), ancestry))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(ancestryPivot.number.value, ancestryPivot))
  }

  it should "blacklist a peer whose run does not start at the head, and ask another" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent)
    val notFromTheHead = walkRun.dropRight(1).reverse

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), notFromTheHead))
    peersClient.expectMsg(
      3.seconds,
      PeersClient.BlacklistPeer(testPeer.id, BlacklistReason.BlockHeaderValidationFailed)
    )

    val retry = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    retry.peerSelector shouldBe PeersClient.ExcludingPeers(Set(testPeer.id))
    retry.replyTo ! PeersClient.Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), ancestry))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(ancestryPivot.number.value, ancestryPivot))
  }

  it should "skip, without blame, a peer that holds only part of the run" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), ancestry.take(10)))

    val retry = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    retry.peerSelector shouldBe PeersClient.ExcludingPeers(Set(testPeer.id))
    retry.replyTo ! PeersClient.Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), ancestry))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(ancestryPivot.number.value, ancestryPivot))
  }

  it should "not ask a peer again after its request failed (a timeout PeersClient reports names the peer)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .RequestFailed(testPeer, BlacklistReason.RegularSyncRequestFailed("timeout"))

    val retry = peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
    retry.peerSelector shouldBe PeersClient.ExcludingPeers(Set(testPeer.id))
    retry.replyTo ! PeersClient.Response(testPeer2, ETHPackets.BlockHeaders(BigInt(0), ancestry))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(ancestryPivot.number.value, ancestryPivot))
  }

  it should "ask again, not give up, when a request gets no answer in time" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent, askTimeout = 200.millis)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds) // never answered
    parent.expectNoMessage(300.millis)
    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), ancestry))

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Completed(ancestryPivot.number.value, ancestryPivot))
  }

  it should "give up, not wait for a fresh peer, when no eligible peer is left" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent)

    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Failed(PivotHeaderBootstrap.AncestryNotServed))
  }

  it should "give up as exhausted after a rejected run leaves no eligible peer" taggedAs (UnitTest, SyncTest) in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    mkAncestry(peersClient, parent)

    peersClient.expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds).replyTo ! PeersClient
      .Response(testPeer, ETHPackets.BlockHeaders(BigInt(0), walkRun.dropRight(1).reverse))
    peersClient.expectMsg(
      3.seconds,
      PeersClient.BlacklistPeer(testPeer.id, BlacklistReason.BlockHeaderValidationFailed)
    )
    peersClient
      .expectMsgType[PeersClient.Request[ETHPackets.GetBlockHeaders]](3.seconds)
      .replyTo ! PeersClient.NoSuitablePeer

    parent.expectMsg(3.seconds, PivotHeaderBootstrap.Failed(PivotHeaderBootstrap.ExhaustedAfterRejection))
  }

  it should "answer a depth one request cannot reach with Failed, asking no peer" taggedAs UnitTest in {
    val peersClient = TestProbe()
    val parent = TestProbe()
    PivotHeaderBootstrap.MaxAncestryDepth shouldBe 191
    mkAncestry(peersClient, parent, depth = PivotHeaderBootstrap.MaxAncestryDepth + 1)

    parent.expectMsgType[PivotHeaderBootstrap.Failed](3.seconds).reason should include("outside 0..191")
    peersClient.expectNoMessage(200.millis)
  }

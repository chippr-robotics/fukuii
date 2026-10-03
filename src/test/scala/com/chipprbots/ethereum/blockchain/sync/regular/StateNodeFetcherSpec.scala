package com.chipprbots.ethereum.blockchain.sync.regular
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.compiletime.uninitialized
import scala.concurrent.duration.*

import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeerExcluding
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchedStateNode
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*

/** Targeted tests for the Bug 30 StateNodeFetcher fixes:
  *
  *   - Bounded retry budget (MaxStateNodeFetchRetries = 10): exhaustion sends an empty FetchedStateNode to the
  *     supervisor instead of looping forever.
  *   - In-flight de-dup: a second FetchStateNode for the same hash updates replyTo only — no parallel SNAP request gets
  *     fired.
  *   - Bytecode path: FetchStateNode with isByteCode=true routes to SNAP GetByteCodes (BestSnapPeerExcluding), not
  *     GetNodeData. This unblocks contract bytecode recovery on ETH68-only peer sets where GetNodeData is unavailable.
  *   - Peer rotation: every SNAP request selects via BestSnapPeerExcluding(triedPeers); the first attempt excludes the
  *     empty set, and empty/wrong responses add the responding peer so each retry samples a different snap server.
  */
class StateNodeFetcherSpec
    extends ScalaTestWithActorTestKit()
    with AnyFreeSpecLike
    with Matchers
    with BeforeAndAfterEach
    with TestSyncConfig:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  // Each test gets its own typed test kit, shut down after the test.
  private var typedKit: ActorTestKit = uninitialized

  override def beforeEach(): Unit =
    typedKit = ActorTestKit("StateNodeFetcherTest-" + System.nanoTime())

  override def afterEach(): Unit =
    typedKit.shutdownTestKit()

  /** Fixture that wires up:
    *   - a classic TestProbe playing peersClient (catches outgoing Requests)
    *   - a classic TestProbe playing the originalSender / replyTo on FetchStateNode
    *   - a typed TestProbe playing the BlockFetcher supervisor
    *   - the StateNodeFetcher actor under test
    */
  private trait TestSetup:
    val peersClientProbe: TestProbe = TestProbe()
    val replyToProbe: TestProbe = TestProbe()
    val supervisorProbe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[FetchCommand] =
      typedKit.createTestProbe[FetchCommand]()

    val fetcher: ActorRef[StateNodeFetcher.StateNodeFetcherCommand] =
      typedKit.spawn(
        StateNodeFetcher(peersClientProbe.ref.toTyped[PeersClient.Command], syncConfig, supervisorProbe.ref),
        "state-node-fetcher"
      )

    val targetHash: ByteString = ByteString(Array.fill[Byte](32)(0xab.toByte))

  "StateNodeFetcher" - {

    "with isByteCode=true, routes the request to SNAP GetByteCodes via BestSnapPeerExcluding" taggedAs UnitTest in new TestSetup:
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        stateRoot = None,
        paths = None,
        networkHead = BigInt(0),
        isByteCode = true,
        fallbackStateRoot = None
      )

      // The peersClient receives a Request whose message is a GetByteCodes for our codeHash,
      // targeting the BestSnapPeer selector. Earlier, this same input went through
      // GetNodeData (BestNodeDataPeer) — which is unavailable on ETH68-only peer sets and
      // is the failure mode Bug 30's bytecode-recovery layer fixes.
      val req: Request[?] = peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])
      req.message shouldBe a[GetByteCodes]
      req.message.asInstanceOf[GetByteCodes].hashes shouldBe Seq(targetHash)
      // First attempt excludes nothing; on empty/wrong responses the responding peer is added to
      // triedPeers so the next retry rotates to a different snap server (no more single-peer hammer).
      req.peerSelector shouldBe BestSnapPeerExcluding(Set.empty)

    "with stateRoot + paths, routes the request to SNAP GetTrieNodes (not GetByteCodes)" taggedAs UnitTest in new TestSetup:
      val stateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x11.toByte))
      val paths: Seq[Seq[ByteString]] = Seq(Seq(ByteString(Array(0x01.toByte, 0x02.toByte))))

      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        stateRoot = Some(stateRoot),
        paths = Some(paths),
        isByteCode = false
      )

      val req: Request[?] = peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])
      req.message shouldBe a[GetTrieNodes]
      req.message.asInstanceOf[GetTrieNodes].rootHash shouldBe stateRoot
      req.peerSelector shouldBe BestSnapPeerExcluding(Set.empty)

    "de-duplicates a second FetchStateNode for the in-flight hash (no parallel request)" taggedAs UnitTest in new TestSetup:
      // First fetch — fires a request.
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])

      // Second fetch for the SAME hash from a different sender — must NOT fire another request.
      // BlockImporter's resolvingMissingNode 30s ReceiveTimeout retries on the same hash; without
      // de-dup, every retry spawns a parallel SNAP request and overwrites the requester.
      val secondReplyTo: TestProbe = TestProbe()
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = secondReplyTo.ref,
        isByteCode = true
      )

      peersClientProbe.expectNoMessage(500.millis)

    "fires a fresh request when the second FetchStateNode is for a DIFFERENT hash" taggedAs UnitTest in new TestSetup:
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])

      // Different hash — overwrites the in-flight requester (the previous one is abandoned in
      // favour of the new caller). This is the legitimate "give up old, start new" path,
      // distinct from the de-dup case above.
      val otherHash: ByteString = ByteString(Array.fill[Byte](32)(0xcd.toByte))
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = otherHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )

      val req: Request[?] = peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])
      req.message.asInstanceOf[GetByteCodes].hashes shouldBe Seq(otherHash)

    "exhausts after MaxStateNodeFetchRetries RetryStateNodeRequest events and signals BlockImporter" taggedAs UnitTest in new TestSetup:
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])

      // Drive the retry counter directly — each RetryStateNodeRequest resets the rotation set and
      // increments attempts via retryOrExhaust. The MaxStateNodeFetchRetries-th call hits the
      // exhaust branch and sends an empty FetchedStateNode to BlockImporter, triggering its 5-min
      // backoff handler.
      (1 to StateNodeFetcher.MaxStateNodeFetchRetries).foreach { _ =>
        fetcher ! StateNodeFetcher.RetryStateNodeRequest
      }

      replyToProbe.expectMsgPF(3.seconds) { case FetchedStateNode(NodeData(values)) =>
        values shouldBe empty
      }

    "before exhaustion, RetryStateNodeRequest does NOT signal BlockImporter" taggedAs UnitTest in new TestSetup:
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])

      // Send fewer than MaxStateNodeFetchRetries — BlockImporter must NOT see an empty
      // response yet, otherwise the 5-min backoff fires prematurely and progress stalls.
      (1 until StateNodeFetcher.MaxStateNodeFetchRetries).foreach { _ =>
        fetcher ! StateNodeFetcher.RetryStateNodeRequest
      }

      replyToProbe.expectNoMessage(500.millis)
    "does not switch to a fallback root when the wanted node IS the state root (it can only return the other root's node)" taggedAs UnitTest in new TestSetup:
      // Soak 2026-10-03: block 317603 needs the parent state root node (path []). A different root's path [] is a
      // different node, so a switch can never match and used to burn the whole retry budget on wrong-hash replies.
      val stateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x46.toByte))
      val fallbackRoot: ByteString = ByteString(Array.fill[Byte](32)(0xdb.toByte))
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = stateRoot,
        originalSender = replyToProbe.ref,
        stateRoot = Some(stateRoot),
        paths = Some(Seq(Seq(ByteString.empty))),
        fallbackStateRoot = Some(fallbackRoot)
      )
      peersClientProbe
        .expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])
        .message
        .asInstanceOf[GetTrieNodes]
        .rootHash shouldBe stateRoot

      // The peer answers with a node that is not the wanted hash.
      val peer = PeerTestHelpers.createTestPeer("p1", TestProbe().ref)
      fetcher ! StateNodeFetcher.AdaptedMessage(peer, TrieNodes(1, Seq(ByteString(Array.fill[Byte](40)(1)))))

      // Must rotate peers on the SAME root (blacklist, then a retry after the backoff), never re-ask under fallbackRoot.
      val msgs = peersClientProbe.receiveWhile(2.seconds) { case m => m }
      msgs
        .collect { case r: PeersClient.Request[?] => r.message }
        .collect { case g: GetTrieNodes => g.rootHash }
        .foreach(_ should not be fallbackRoot)
      // Bounded: exhaustion still signals BlockImporter after the retry budget.
      (1 to StateNodeFetcher.MaxStateNodeFetchRetries).foreach(_ => fetcher ! StateNodeFetcher.RetryStateNodeRequest)
      replyToProbe.expectMsgPF(3.seconds) { case FetchedStateNode(NodeData(values)) => values shouldBe empty }

    "still switches to the fallback root for a non-root node (existing behaviour preserved)" taggedAs UnitTest in new TestSetup:
      val stateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x46.toByte))
      val fallbackRoot: ByteString = ByteString(Array.fill[Byte](32)(0xdb.toByte))
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        stateRoot = Some(stateRoot),
        paths = Some(Seq(Seq(ByteString(Array(0x01.toByte, 0x02.toByte))))),
        fallbackStateRoot = Some(fallbackRoot)
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[?]])
      val peer = PeerTestHelpers.createTestPeer("p1", TestProbe().ref)
      fetcher ! StateNodeFetcher.AdaptedMessage(peer, TrieNodes(1, Seq(ByteString(Array.fill[Byte](40)(1)))))
      val second = peersClientProbe.fishForMessage(3.seconds) {
        case _: PeersClient.Request[?] => true; case _ => false
      }
      second.asInstanceOf[PeersClient.Request[?]].message.asInstanceOf[GetTrieNodes].rootHash shouldBe fallbackRoot
  }

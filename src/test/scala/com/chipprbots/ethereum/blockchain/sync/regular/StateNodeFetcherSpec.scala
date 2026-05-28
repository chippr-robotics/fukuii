package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeer
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchCommand
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchedStateNode
import com.chipprbots.ethereum.network.p2p.messages.ETH63.NodeData
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.testing.Tags._

/** Targeted tests for the Bug 30 StateNodeFetcher fixes:
  *
  *   - Bounded retry budget (MaxStateNodeFetchRetries = 10): exhaustion sends an empty FetchedStateNode to the
  *     supervisor instead of looping forever.
  *   - In-flight de-dup: a second FetchStateNode for the same hash updates replyTo only — no parallel SNAP request gets
  *     fired.
  *   - Bytecode path: FetchStateNode with isByteCode=true routes to SNAP GetByteCodes (BestSnapPeer), not GetNodeData.
  *     This unblocks contract bytecode recovery on ETH68-only peer sets where GetNodeData is unavailable.
  */
class StateNodeFetcherSpec
    extends TestKit(ActorSystem("StateNodeFetcherSpec"))
    with AnyFreeSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with TestSyncConfig {

  // Each test gets its own typed test kit, shut down after the test.
  private var typedKit: ActorTestKit = _

  override def beforeEach(): Unit =
    typedKit = ActorTestKit("StateNodeFetcherTest-" + System.nanoTime())

  override def afterEach(): Unit =
    typedKit.shutdownTestKit()

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system, verifySystemShutdown = false)

  /** Fixture that wires up:
    *   - a classic TestProbe playing peersClient (catches outgoing Requests)
    *   - a classic TestProbe playing the originalSender / replyTo on FetchStateNode
    *   - a typed TestProbe playing the BlockFetcher supervisor
    *   - the StateNodeFetcher actor under test
    */
  private trait TestSetup {
    val peersClientProbe: TestProbe = TestProbe()
    val replyToProbe: TestProbe = TestProbe()
    val supervisorProbe = typedKit.createTestProbe[FetchCommand]()

    val fetcher: ActorRef[StateNodeFetcher.StateNodeFetcherCommand] =
      typedKit.spawn(
        StateNodeFetcher(peersClientProbe.ref, syncConfig, supervisorProbe.ref),
        "state-node-fetcher"
      )

    val targetHash: ByteString = ByteString(Array.fill[Byte](32)(0xab.toByte))
  }

  "StateNodeFetcher" - {

    "with isByteCode=true, routes the request to SNAP GetByteCodes via BestSnapPeer" taggedAs UnitTest in new TestSetup {
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        stateRoot = None,
        paths = None,
        networkHead = BigInt(0),
        isByteCode = true,
        fallbackStateRoots = Seq.empty
      )

      // The peersClient receives a Request whose message is a GetByteCodes for our codeHash,
      // targeting the BestSnapPeer selector. Earlier, this same input went through
      // GetNodeData (BestNodeDataPeer) — which is unavailable on ETH68-only peer sets and
      // is the failure mode Bug 30's bytecode-recovery layer fixes.
      val req = peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])
      req.message shouldBe a[GetByteCodes]
      req.message.asInstanceOf[GetByteCodes].hashes shouldBe Seq(targetHash)
      req.peerSelector shouldBe BestSnapPeer
    }

    "with stateRoot + paths, routes the request to SNAP GetTrieNodes (not GetByteCodes)" taggedAs UnitTest in new TestSetup {
      val stateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x11.toByte))
      val paths: Seq[Seq[ByteString]] = Seq(Seq(ByteString(Array(0x01.toByte, 0x02.toByte))))

      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        stateRoot = Some(stateRoot),
        paths = Some(paths),
        isByteCode = false
      )

      val req = peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])
      req.message shouldBe a[GetTrieNodes]
      req.message.asInstanceOf[GetTrieNodes].rootHash shouldBe stateRoot
      req.peerSelector shouldBe BestSnapPeer
    }

    "de-duplicates a second FetchStateNode for the in-flight hash (no parallel request)" taggedAs UnitTest in new TestSetup {
      // First fetch — fires a request.
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])

      // Second fetch for the SAME hash from a different sender — must NOT fire another request.
      // BlockImporter's resolvingMissingNode 30s ReceiveTimeout retries on the same hash; without
      // de-dup, every retry spawns a parallel SNAP request and overwrites the requester.
      val secondReplyTo = TestProbe()
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = secondReplyTo.ref,
        isByteCode = true
      )

      peersClientProbe.expectNoMessage(500.millis)
    }

    "fires a fresh request when the second FetchStateNode is for a DIFFERENT hash" taggedAs UnitTest in new TestSetup {
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])

      // Different hash — overwrites the in-flight requester (the previous one is abandoned in
      // favour of the new caller). This is the legitimate "give up old, start new" path,
      // distinct from the de-dup case above.
      val otherHash = ByteString(Array.fill[Byte](32)(0xcd.toByte))
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = otherHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )

      val req = peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])
      req.message.asInstanceOf[GetByteCodes].hashes shouldBe Seq(otherHash)
    }

    "exhausts after MaxStateNodeFetchRetries RetryStateNodeRequest events and signals BlockImporter" taggedAs UnitTest in new TestSetup {
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])

      // Drive the retry counter directly — each RetryStateNodeRequest increments attempts via
      // retryOrExhaust. The 10th call (MaxStateNodeFetchRetries) hits the exhaust branch and
      // sends an empty FetchedStateNode to BlockImporter, triggering its 5-min backoff handler.
      (1 to StateNodeFetcher.MaxStateNodeFetchRetries).foreach { _ =>
        fetcher ! StateNodeFetcher.RetryStateNodeRequest
      }

      replyToProbe.expectMsgPF(3.seconds) { case FetchedStateNode(NodeData(values)) =>
        values shouldBe empty
      }
    }

    "before exhaustion, RetryStateNodeRequest does NOT signal BlockImporter" taggedAs UnitTest in new TestSetup {
      fetcher ! StateNodeFetcher.FetchStateNode(
        hash = targetHash,
        originalSender = replyToProbe.ref,
        isByteCode = true
      )
      peersClientProbe.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])

      // Send fewer than MaxStateNodeFetchRetries — BlockImporter must NOT see an empty
      // response yet, otherwise the 5-min backoff fires prematurely and progress stalls.
      (1 until StateNodeFetcher.MaxStateNodeFetchRetries).foreach { _ =>
        fetcher ! StateNodeFetcher.RetryStateNodeRequest
      }

      replyToProbe.expectNoMessage(500.millis)
    }
  }
}

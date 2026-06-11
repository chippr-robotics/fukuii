package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap.actors.Messages
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.PeerTestHelpers

/** A controllable fake SNAP peer for unit and integration tests.
  *
  * Modeled on core-geth `eth/downloader/skeleton_test.go` `skeletonTestPeer`:
  *   - Per-message-type handler hooks injected per-test
  *   - `served` and `dropped` atomics for post-test assertion
  *   - `drop()` to simulate peer disconnect (stops responding)
  *
  * Usage:
  * {{{
  * val fakePeer = SNAPFakePeer.normal(system, "test-peer-1", stateRoot)
  * coordinator ! Messages.PeerAvailable(fakePeer.peer)
  * fakePeer.served.get() shouldBe 1
  * }}}
  */
class SNAPFakePeer(
    val peer: Peer,
    accountRangeHandler: GetAccountRange => Option[AccountRange],
    storageRangesHandler: GetStorageRanges => Option[StorageRanges],
    byteCodesHandler: GetByteCodes => Option[ByteCodes],
    trieNodesHandler: GetTrieNodes => Option[TrieNodes]
)(implicit system: ActorSystem) {

  val served: AtomicLong = new AtomicLong(0)
  val dropped: AtomicBoolean = new AtomicBoolean(false)

  val probe: TestProbe = {
    val p = TestProbe(peer.id.value + "-network-peer-manager")
    p.setAutoPilot(new AutoPilot {
      def run(sender: ActorRef, msg: Any): AutoPilot = {
        if (dropped.get()) return this
        msg match {
          case NetworkPeerManagerActor.SendMessage(rawMsg, _) =>
            rawMsg.underlyingMsg match {
              case req: GetAccountRange =>
                accountRangeHandler(req).foreach { resp =>
                  sender ! Messages.AccountRangeResponseMsg(resp)
                  served.incrementAndGet()
                }
              case req: GetStorageRanges =>
                storageRangesHandler(req).foreach { resp =>
                  sender ! Messages.StorageRangesResponseMsg(resp)
                  served.incrementAndGet()
                }
              case req: GetByteCodes =>
                byteCodesHandler(req).foreach { resp =>
                  sender ! Messages.ByteCodesResponseMsg(resp)
                  served.incrementAndGet()
                }
              case req: GetTrieNodes =>
                trieNodesHandler(req).foreach { resp =>
                  sender ! Messages.TrieNodesResponseMsg(resp)
                  served.incrementAndGet()
                }
              case _ => // ignore unknown messages
            }
          case _ => // ignore non-SendMessage messages
        }
        this
      }
    })
    p
  }

  def ref: ActorRef = probe.ref

  /** Simulate peer disconnect — stop responding to all requests. */
  def drop(): Unit = dropped.set(true)

  /** Reset drop state — allow peer to respond again (simulates reconnect). */
  def reconnect(): Unit = dropped.set(false)
}

object SNAPFakePeer {

  /** Empty AccountRange with a boundary proof — indicates the range is complete (no accounts). */
  private val emptyBoundaryProof: Seq[ByteString] = Seq(ByteString(Array.fill(32)(0xab.toByte)))

  /** Create a normal peer that returns empty AccountRange with proof for any request. Simulates a peer that serves
    * requests successfully but has no accounts in range.
    */
  def empty(system: ActorSystem, id: String): SNAPFakePeer =
    new SNAPFakePeer(
      peer = makePeer(id, system),
      accountRangeHandler = req => Some(AccountRange(req.requestId, Seq.empty, emptyBoundaryProof)),
      storageRangesHandler = req => Some(StorageRanges(req.requestId, Seq.empty, Seq.empty)),
      byteCodesHandler = req => Some(ByteCodes(req.requestId, Seq.empty)),
      trieNodesHandler = req => Some(TrieNodes(req.requestId, Seq.empty))
    )(system)

  /** Create a non-responsive peer — never sends any response. Used to test timeout paths. */
  def nonResponsive(system: ActorSystem, id: String): SNAPFakePeer =
    new SNAPFakePeer(
      peer = makePeer(id, system),
      accountRangeHandler = _ => None,
      storageRangesHandler = _ => None,
      byteCodesHandler = _ => None,
      trieNodesHandler = _ => None
    )(system)

  /** Create a peer that returns AccountRange with no proof and no accounts. Simulates a peer that cannot serve the
    * current root (triggers stateless marking).
    */
  def proofless(system: ActorSystem, id: String): SNAPFakePeer =
    new SNAPFakePeer(
      peer = makePeer(id, system),
      accountRangeHandler = req => Some(AccountRange(req.requestId, Seq.empty, Seq.empty)),
      storageRangesHandler = req => Some(StorageRanges(req.requestId, Seq.empty, Seq.empty)),
      byteCodesHandler = req => Some(ByteCodes(req.requestId, Seq.empty)),
      trieNodesHandler = req => Some(TrieNodes(req.requestId, Seq.empty))
    )(system)

  /** Create a peer with fully custom per-type handlers. */
  def custom(
      system: ActorSystem,
      id: String,
      accountRangeHandler: GetAccountRange => Option[AccountRange] = _ => None,
      storageRangesHandler: GetStorageRanges => Option[StorageRanges] = _ => None,
      byteCodesHandler: GetByteCodes => Option[ByteCodes] = _ => None,
      trieNodesHandler: GetTrieNodes => Option[TrieNodes] = _ => None
  ): SNAPFakePeer =
    new SNAPFakePeer(
      peer = makePeer(id, system),
      accountRangeHandler = accountRangeHandler,
      storageRangesHandler = storageRangesHandler,
      byteCodesHandler = byteCodesHandler,
      trieNodesHandler = trieNodesHandler
    )(system)

  private def makePeer(id: String, system: ActorSystem): Peer = {
    val probe = TestProbe(s"$id-peer-ref")(system)
    PeerTestHelpers.createTestPeer(id, probe.ref)
  }
}

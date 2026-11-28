package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.testing.Tags.*

/** The pure target-selection used by the recovery recent-root roll: roll `margin` blocks back from the highest known
  * SNAP-capable peer head, so the target is inside peers' snapshot serve window; decline when no height is known.
  */
class SyncControllerRecentRootSpec extends AnyFunSuite with ParallelTestExecution:

  test("picks margin blocks back from the highest peer head", UnitTest) {
    assert(
      SyncController
        .recentRootTarget(Seq(BigInt(100), BigInt(200), BigInt(150)), margin = BigInt(64))
        .contains(BigInt(136))
    )
  }

  test("no peer height known → None (caller declines the roll)", UnitTest) {
    assert(SyncController.recentRootTarget(Seq.empty[BigInt], margin = BigInt(64)).isEmpty)
    assert(SyncController.recentRootTarget(Seq(BigInt(0), BigInt(0)), margin = BigInt(64)).isEmpty)
  }

  test("ignores zero-height (status-not-yet-arrived) peers", UnitTest) {
    assert(
      SyncController.recentRootTarget(Seq(BigInt(0), BigInt(500), BigInt(0)), margin = BigInt(64)).contains(BigInt(436))
    )
  }

  test("clamps to block 1 when the head is below the margin", UnitTest) {
    assert(SyncController.recentRootTarget(Seq(BigInt(10)), margin = BigInt(64)).contains(BigInt(1)))
  }

  // B-320 (forge): the healing serve-root's and the recent-root's targets are taken from SNAP peers on this node's
  // fork that are not held for another chain than the branch, as the SNAP child's pivot choice takes them
  test("takes the target heights from SNAP peers on this fork, not held for another chain", UnitTest) {
    import java.net.InetSocketAddress
    import org.apache.pekko.util.ByteString
    import com.chipprbots.ethereum.domain.ChainWeight
    import com.chipprbots.ethereum.network.NetworkPeerManagerActor.{PeerInfo, RemoteStatus}
    import com.chipprbots.ethereum.network.{Peer, PeerId}
    import com.chipprbots.ethereum.network.p2p.messages.Capability
    def peer(id: String): Peer = Peer(PeerId(id), new InetSocketAddress("127.0.0.1", 30303), null, false)
    def info(height: Int, snap: Boolean = true, fork: Boolean = true): PeerInfo =
      PeerInfo(
        RemoteStatus(
          capability = Capability.ETH68,
          networkId = 1L,
          chainWeight = ChainWeight.zero,
          bestHash = ByteString.empty,
          genesisHash = ByteString.empty,
          supportsSnap = snap,
          capabilities = List(Capability.ETH68),
          latestBlock = None,
          remoteClientId = "x"
        ),
        ChainWeight.zero,
        forkAccepted = fork,
        maxBlockNumber = BigInt(height),
        bestBlockHash = ByteString.empty
      )
    val peers = Map(
      peer("branch") -> info(100),
      peer("other-chain") -> info(900),
      peer("other-fork") -> info(800, fork = false),
      peer("no-snap") -> info(700, snap = false)
    )
    assert(SyncController.snapTargetHeights(peers, _ => false).toSet == Set(BigInt(100), BigInt(900)))
    assert(SyncController.snapTargetHeights(peers, _ == PeerId("other-chain")).toList == List(BigInt(100)))
  }

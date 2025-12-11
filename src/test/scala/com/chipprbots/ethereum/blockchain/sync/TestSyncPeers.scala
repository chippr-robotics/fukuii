package com.chipprbots.ethereum.blockchain.sync
import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability

trait TestSyncPeers { self: TestSyncConfig =>
  implicit def system: ActorSystem

  val peer1TestProbe: TestProbe = TestProbe("peer1")(system)
  val peer2TestProbe: TestProbe = TestProbe("peer2")(system)
  val peer3TestProbe: TestProbe = TestProbe("peer3")(system)

  val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 0), peer1TestProbe.ref, false)
  val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.2", 0), peer2TestProbe.ref, false)
  val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.3", 0), peer3TestProbe.ref, false)

  val peer1Status: RemoteStatus =
    RemoteStatus(
      Capability.ETH68,
      1,
      ChainWeight.totalDifficultyOnly(20),
      ByteString("peer1_bestHash"),
      ByteString("unused")
    )
  val peer2Status: RemoteStatus = peer1Status.copy(bestHash = ByteString("peer2_bestHash"))

  val bestBlock = 400000
  val expectedPivotBlock: Int = bestBlock - syncConfig.pivotBlockOffset

  val defaultPeer1Info: PeerInfo = PeerInfo(
    peer1Status,
    forkAccepted = true,
    chainWeight = peer1Status.chainWeight,
    maxBlockNumber = bestBlock,
    bestBlockHash = peer1Status.bestHash
  )

  val twoAcceptedPeers: Map[Peer, PeerInfo] = Map(
    peer1 -> PeerInfo(
      peer1Status,
      forkAccepted = true,
      chainWeight = peer1Status.chainWeight,
      maxBlockNumber = bestBlock,
      bestBlockHash = peer1Status.bestHash
    ),
    peer2 -> PeerInfo(
      peer2Status,
      forkAccepted = true,
      chainWeight = peer1Status.chainWeight,
      maxBlockNumber = bestBlock,
      bestBlockHash = peer2Status.bestHash
    )
  )

  val singlePeer: Map[Peer, PeerInfo] = Map(
    peer1 -> PeerInfo(
      peer1Status,
      forkAccepted = true,
      chainWeight = peer1Status.chainWeight,
      maxBlockNumber = bestBlock,
      bestBlockHash = peer1Status.bestHash
    )
  )
}

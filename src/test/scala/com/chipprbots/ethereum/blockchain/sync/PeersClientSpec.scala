package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableFor3
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags._

import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability

class PeersClientSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  import Peers._

  "PeerClient" should "determine the best peer based on total difficulty" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val table: TableFor3[Map[PeerId, PeerWithInfo], Option[Peer], String] =
      Table[Map[PeerId, PeerWithInfo], Option[Peer], String](
        ("PeerInfo map", "Expected best peer", "Scenario info (selected peer)"),
        (
          Map(),
          None,
          "No peers"
        ),
        (
          Map(peer1.id -> PeerWithInfo(peer1, peerInfo(100, fork = false))),
          None,
          "Single peer"
        ),
        (
          Map(
            peer1.id -> PeerWithInfo(peer1, peerInfo(100, fork = false)),
            peer2.id -> PeerWithInfo(peer2, peerInfo(50, fork = true))
          ),
          Some(peer2),
          "Peer2 with lower TD but following the ETC fork"
        ),
        (
          Map(peer1.id -> PeerWithInfo(peer1, peerInfo(100)), peer2.id -> PeerWithInfo(peer2, peerInfo(101))),
          Some(peer2),
          "Peer2 with higher TD"
        ),
        (
          Map(
            peer1.id -> PeerWithInfo(peer1, peerInfo(100)),
            peer2.id -> PeerWithInfo(peer2, peerInfo(101)),
            peer3.id -> PeerWithInfo(peer3, peerInfo(50))
          ),
          Some(peer2),
          "Peer2 with highest TD"
        ),
        (
          Map(
            peer1.id -> PeerWithInfo(peer1, peerInfo(100)),
            peer2.id -> PeerWithInfo(peer2, peerInfo(101)),
            peer3.id -> PeerWithInfo(peer3, peerInfo(50))
          ),
          Some(peer2),
          "Peer2 with higher TD among peers"
        )
      )
    forAll(table) { (peerInfoMap, expectedPeer, _) =>
      PeersClient.bestPeer(peerInfoMap) shouldEqual expectedPeer
    }
  }

  it should "exclude peers stuck at genesis even if their TD is higher" taggedAs (UnitTest, SyncTest) in {
    // Sepolia/ETC reproducer: bootnode at genesis with the original POW TD (131072)
    // and a real chain-head peer with a lower TD (e.g. ETH/69 block-number proxy).
    // Pre-fix #1201: the legacy `bestPeer` selected the genesis peer because
    // its TD was higher; PivotHeaderBootstrap then asked it for a chain-head
    // header and got `no header returned`, retrying until exhausted.
    val genesisOnly = peer1.id -> PeerWithInfo(peer1, peerInfoAtGenesis(td = 17_000_000_000_000_000L.toInt))
    val chainHead = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 100))
    PeersClient.bestPeer(Map(genesisOnly, chainHead)) shouldEqual Some(peer2)
  }

  it should "return None when every available peer is at genesis" taggedAs (UnitTest, SyncTest) in {
    val onlyGenesis = Map(
      peer1.id -> PeerWithInfo(peer1, peerInfoAtGenesis(td = 50)),
      peer2.id -> PeerWithInfo(peer2, peerInfoAtGenesis(td = 200))
    )
    PeersClient.bestPeer(onlyGenesis) shouldEqual None
  }

  it should "filter peers by maxBlockNumber for absolute-block requests" taggedAs (UnitTest, SyncTest) in {
    // Sepolia repro: PivotHeaderBootstrap asks for block 10789531. Among the
    // peer pool, only peers whose advertised maxBlockNumber is at least the
    // target should be selected. Peers reporting `latestBlock=9707885` (older
    // ETH/69 peer behind chain head) literally don't have block 10789531 and
    // would return an empty headers list — matching the failure in #1201.
    val peerBehind = peer1.id -> PeerWithInfo(peer1, peerInfo(td = 9707885).copy(maxBlockNumber = 9707885))
    val peerAhead = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 100).copy(maxBlockNumber = 10789600))
    val pool = Map(peerBehind, peerAhead)
    PeersClient.bestPeerWithMinBlock(pool, BigInt(10789531)) shouldEqual Some(peer2)
  }

  it should "fall back to maxBlockNumber=0 peers when no peer is known to be ahead" taggedAs (UnitTest, SyncTest) in {
    // ETH/64-68 peers post-merge have maxBlockNumber=0 (no STATUS field carries
    // the block number, no incoming block messages to update). They MIGHT have
    // the block; we just don't know. Better to try them than fail outright.
    val peerUnknown =
      peer1.id -> PeerWithInfo(peer1, peerInfo(td = 17_000_000_000_000_000L.toInt).copy(maxBlockNumber = 0))
    val peerBehind = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 50).copy(maxBlockNumber = 100))
    val pool = Map(peerUnknown, peerBehind)
    PeersClient.bestPeerWithMinBlock(pool, BigInt(10789531)) shouldEqual Some(peer1)
  }

  it should "exclude tried peers from BestPeerWithMinBlockExcluding" taggedAs (UnitTest, SyncTest) in {
    val peerAhead = peer1.id -> PeerWithInfo(peer1, peerInfo(td = 200).copy(maxBlockNumber = 10_789_600))
    val peerAlsoAhead = peer2.id -> PeerWithInfo(peer2, peerInfo(td = 100).copy(maxBlockNumber = 10_789_600))
    // peer1 excluded (already tried) → peer2 selected despite lower TD
    PeersClient.bestPeerWithMinBlockExcluding(
      Map(peerAhead, peerAlsoAhead),
      BigInt(10_789_531),
      exclude = Set(peer1.id)
    ) shouldEqual Some(peer2)
  }

  it should "return None from BestPeerWithMinBlockExcluding when all eligible peers are excluded" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val onlyPeer = peer1.id -> PeerWithInfo(peer1, peerInfo(td = 200).copy(maxBlockNumber = 10_789_600))
    PeersClient.bestPeerWithMinBlockExcluding(
      Map(onlyPeer),
      BigInt(10_789_531),
      exclude = Set(peer1.id)
    ) shouldEqual None
  }

  object Peers {
    implicit val system: ActorSystem = ActorSystem("PeersClient_System")

    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 1), TestProbe().ref, false)
    val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.1", 2), TestProbe().ref, false)
    val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.1", 3), TestProbe().ref, false)

    // Distinct bestHash and genesisHash so isAtGenesis returns false by default.
    // Tests that need a genesis-only peer can override via `peerInfoAtGenesis`.
    private val genesisHash = ByteString("genesis-hash")
    private val chainHeadHash = ByteString("chain-head-hash")

    private val peerStatus = RemoteStatus(
      capability = Capability.ETH63,
      networkId = 1,
      chainWeight = ChainWeight.zero,
      bestHash = chainHeadHash,
      genesisHash = genesisHash
    )

    def peerInfo(td: Int, fork: Boolean = true): PeerInfo =
      PeerInfo(
        peerStatus,
        ChainWeight(BigInt(td)),
        forkAccepted = fork,
        maxBlockNumber = 42,
        bestBlockHash = chainHeadHash
      )

    /** A peer that has handshaked but is stuck at genesis — bestHash == genesisHash. Common on Sepolia where bootnodes
      * are fresh-state and on ETC where some peers advertise SNAP/1 but never advance past genesis. They literally have
      * no chain data to serve.
      */
    def peerInfoAtGenesis(td: Int): PeerInfo =
      peerInfo(td).copy(
        remoteStatus = peerStatus.copy(bestHash = genesisHash),
        bestBlockHash = genesisHash
      )
  }
}

package com.chipprbots.ethereum.network

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.testing.Tags.*

/** B-415: the per-peer routine events are counted here, once, and drained into the one-a-minute summary. */
class PeerChurnStatsSpec extends AnyFlatSpec with Matchers:

  "PeerChurnStats" should "count connects, closes by reason, fork-id rejections and blacklistings" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val stats = new PeerChurnStats
    (1 to 3).foreach(_ => stats.recordConnected())
    (1 to 2).foreach(_ => stats.recordClosedByDisconnect(Disconnect.Reasons.TooManyPeers))
    stats.recordClosedByDisconnect(Disconnect.Reasons.UselessPeer)
    stats.recordClosed(PeerChurnStats.TcpClosed)
    (1 to 4).foreach(_ => stats.recordForkIdRejected())
    stats.recordBlacklisted()

    val snapshot = stats.drain()
    snapshot.connected shouldBe 3
    snapshot.closed shouldBe 4
    snapshot.closedByReason shouldBe Map("too-many-peers" -> 2L, "useless-peer" -> 1L, "tcp-closed" -> 1L)
    snapshot.forkIdRejected shouldBe 4
    snapshot.blacklisted shouldBe 1
  }

  it should "restart from zero after a drain, and `current` must not restart it" taggedAs (UnitTest, NetworkTest) in {
    val stats = new PeerChurnStats
    stats.recordConnected()
    stats.recordClosed("x")
    stats.current.connected shouldBe 1
    stats.current.closedByReason shouldBe Map("x" -> 1L)
    stats.drain().connected shouldBe 1
    stats.drain() shouldBe PeerChurnStats.Snapshot(0, Map.empty, 0, 0)
  }

  it should "describe a window as one line: reasons by count then name, nothing but zeros when quiet" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    PeerChurnStats.Snapshot(0, Map.empty, 0, 0).describe shouldBe
      "connected=0 closed=0 forkid-rejected=0 blacklisted=0"
    PeerChurnStats
      .Snapshot(5, Map("useless-peer" -> 7L, "too-many-peers" -> 7L, "timeout" -> 1L), 9, 2)
      .describe shouldBe
      "connected=5 closed=15[too-many-peers=7,useless-peer=7,timeout=1] forkid-rejected=9 blacklisted=2"
  }

  it should "map every devp2p disconnect reason to a distinct stable key" taggedAs (UnitTest, NetworkTest) in {
    import Disconnect.Reasons.*
    val codes = Seq(
      DisconnectRequested,
      TcpSubsystemError,
      BreachOfProtocol,
      UselessPeer,
      TooManyPeers,
      AlreadyConnected,
      IncompatibleP2pProtocolVersion,
      NullNodeIdentityReceived,
      ClientQuitting,
      UnexpectedIdentity,
      IdentityTheSame,
      TimeoutOnReceivingAMessage,
      Other
    )
    codes.map(c => PeerChurnStats.reasonKey(c.toLong)).distinct should have size codes.size.toLong
    PeerChurnStats.reasonKey(0x7f) shouldBe "unknown"
  }

  it should "call it all-rejecting only with no peer, no connect and enough rejections" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val n = PeerChurnStats.AllRejectingThreshold
    PeerChurnStats.Snapshot(0, Map.empty, n.toLong, 0).allRejecting(active = 0) shouldBe true
    PeerChurnStats.Snapshot(0, Map("useless-peer" -> n.toLong), 0, 0).allRejecting(active = 0) shouldBe true
    PeerChurnStats.Snapshot(0, Map.empty, n.toLong - 1, 0).allRejecting(active = 0) shouldBe false
    PeerChurnStats.Snapshot(1, Map.empty, n.toLong, 0).allRejecting(active = 0) shouldBe false
    PeerChurnStats.Snapshot(0, Map.empty, n.toLong, 0).allRejecting(active = 3) shouldBe false
  }

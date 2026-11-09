package com.chipprbots.ethereum.blockchain.sync

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.snap.HeaderChainCheck
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** A peer that served a block header failing validation: how long it stays blacklisted, that a maintained (static or
  * trusted) peer is blacklisted too, and the reason names the header check reports.
  */
class HeaderValidationBlacklistSpec extends AnyFlatSpec with Matchers:

  "A block header that failed validation" should "blacklist for 15 minutes, or for a longer configured duration" taggedAs UnitTest in {
    BlacklistReason.BlockHeaderValidationFailed.duration(60.seconds) shouldBe 15.minutes
    BlacklistReason.BlockHeaderValidationFailed.duration(0.seconds) shouldBe 15.minutes
    BlacklistReason.BlockHeaderValidationFailed.duration(2.hours) shouldBe 2.hours
  }

  "Any other reason" should "blacklist for the configured duration" taggedAs UnitTest in {
    BlacklistReason.ErrorInBlockHeaders.duration(60.seconds) shouldBe 60.seconds
    BlacklistReason.RegularSyncRequestFailed("timeout").duration(60.seconds) shouldBe 60.seconds
  }

  private val maintainedNodeId = ByteString(Array.fill(64)(0x5a.toByte))
  private val maintainedPeer = Peer(
    PeerId("maintained"),
    new InetSocketAddress("127.0.0.1", 30303),
    TestInbox[PeerActor.Command]().ref,
    incomingConnection = false,
    nodeId = Some(maintainedNodeId)
  )
  private val status =
    RemoteStatus(Capability.ETH68, 63, ChainWeight.totalDifficultyOnly(1), ByteString("best"), ByteString("genesis"))
  private val peerInfo = PeerInfo(status, status.chainWeight, forkAccepted = true, BigInt(100), status.bestHash)

  /** A peer list that knows `maintainedPeer` as maintained, as `PeersClient`'s does. */
  private def helperWithMaintainedPeer(blacklist: Blacklist): PeerListHelper =
    val helper = new PeerListHelper(
      TestInbox[PeerEventBusActor.Command]().ref,
      blacklist,
      TestInbox[PeerEventBusActor.PeerEvent]().ref,
      org.slf4j.LoggerFactory.getLogger(getClass)
    ):
      override protected def maintainedNodeIdHexes: Set[String] = Set(Hex.toHexString(maintainedNodeId.toArray))
    helper.handleHandshakedPeers(Map(maintainedPeer -> peerInfo))
    helper

  "A maintained peer" should "be blacklisted for a block header that failed validation" taggedAs UnitTest in {
    val blacklist = CacheBasedBlacklist.empty(10)
    val helper = helperWithMaintainedPeer(blacklist)
    val reason = BlacklistReason.BlockHeaderValidationFailed

    helper.blacklistIfHandshaked(maintainedPeer.id, reason.duration(60.seconds), reason)

    blacklist.isBlacklisted(maintainedPeer.id) shouldBe true
    helper.peersToDownloadFrom shouldBe empty
  }

  it should "still not be blacklisted for an error in a headers response" taggedAs UnitTest in {
    val blacklist = CacheBasedBlacklist.empty(10)
    val helper = helperWithMaintainedPeer(blacklist)
    val reason = BlacklistReason.ErrorInBlockHeaders

    helper.blacklistIfHandshaked(maintainedPeer.id, reason.duration(60.seconds), reason)

    blacklist.isBlacklisted(maintainedPeer.id) shouldBe false
    helper.peersToDownloadFrom.keySet shouldBe Set(maintainedPeer.id)
  }

  "HeaderChainCheck.rejectionReason" should "name the error's type, without the object suffix" taggedAs UnitTest in {
    HeaderChainCheck.rejectionReason(BlockHeaderError.HeaderPoWError) shouldBe "HeaderPoWError"
    HeaderChainCheck.rejectionReason(BlockHeaderError.HeaderUnexpectedError("x")) shouldBe "HeaderUnexpectedError"
  }

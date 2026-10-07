package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

/** Which handshaked peers the SNAP storage phase hands to its coordinator (#1356).
  *
  * The filter used to be `maxBlockNumber > 0`. An eth/68 STATUS carries no block number, so every eth/68 peer read as
  * block 0 until a header probe landed, and stayed out of the storage pool for up to the 5-minute re-probe interval. On
  * a 2-peer ETC pool that is the whole pool.
  */
class SnapStoragePeerEligibilitySpec extends AnyFlatSpec with Matchers:

  private val genesis = ByteString(Array.fill[Byte](32)(1))
  private val head = ByteString(Array.fill[Byte](32)(2))

  private def status(
      capability: Capability,
      bestHash: ByteString,
      snap: Boolean = true,
      latestBlock: Option[BigInt] = None
  ): RemoteStatus =
    RemoteStatus(
      capability,
      1L,
      ChainWeight.totalDifficultyOnly(1),
      bestHash,
      genesis,
      supportsSnap = snap,
      latestBlock = latestBlock
    )

  "servesSnapState" should "admit an eth/68 peer whose STATUS names a real head, before any header probe" taggedAs UnitTest in {
    val eth68 = PeerInfo(status(Capability.ETH68, head), forkAccepted = true)
    eth68.maxBlockNumber shouldBe BigInt(0) // what `maxBlockNumber > 0` tripped on
    SNAPSyncController.servesSnapState(eth68) shouldBe true
  }

  it should "admit an eth/69 peer at a real head" taggedAs UnitTest in {
    val eth69 = PeerInfo(status(Capability.ETH69, head, latestBlock = Some(BigInt(1000))), forkAccepted = true)
    SNAPSyncController.servesSnapState(eth69) shouldBe true
  }

  it should "exclude a peer sitting at its genesis block, whatever it speaks" taggedAs UnitTest in {
    SNAPSyncController.servesSnapState(PeerInfo(status(Capability.ETH68, genesis), forkAccepted = true)) shouldBe false
    SNAPSyncController.servesSnapState(
      PeerInfo(status(Capability.ETH69, genesis, latestBlock = Some(BigInt(0))), forkAccepted = true)
    ) shouldBe false
  }

  it should "admit a peer that has left its genesis block since the handshake" taggedAs UnitTest in {
    val startedAtGenesis = PeerInfo(status(Capability.ETH68, genesis), forkAccepted = true)
    SNAPSyncController.servesSnapState(startedAtGenesis.withBestBlockData(BigInt(100), head)) shouldBe true
  }

  it should "exclude a peer that is not on our fork, or does not speak snap" taggedAs UnitTest in {
    SNAPSyncController.servesSnapState(PeerInfo(status(Capability.ETH68, head), forkAccepted = false)) shouldBe false
    SNAPSyncController.servesSnapState(
      PeerInfo(status(Capability.ETH68, head, snap = false), forkAccepted = true)
    ) shouldBe false
  }

  // ── Exclusion classification (why a snap-capable peer did not reach the coordinators) ──────────────────────────

  "snapExclusionReason" should "not report a peer that does not speak snap (it never qualified)" taggedAs UnitTest in {
    SNAPSyncController.snapExclusionReason(
      PeerInfo(status(Capability.ETH69, genesis, snap = false), forkAccepted = false),
      Some("x")
    ) shouldBe None
  }

  it should "report nothing for a peer that is handed to the coordinators" taggedAs UnitTest in {
    val ok = PeerInfo(status(Capability.ETH69, head, latestBlock = Some(BigInt(1000))), forkAccepted = true)
    SNAPSyncController.servesSnapState(ok) shouldBe true
    SNAPSyncController.snapExclusionReason(ok, None) shouldBe None
  }

  it should "name the fork, blacklist and genesis clauses, in filter order" taggedAs UnitTest in {
    SNAPSyncController.snapExclusionReason(
      PeerInfo(status(Capability.ETH68, genesis), forkAccepted = false),
      Some("P2PDisconnect")
    ) shouldBe Some("fork-not-accepted")
    SNAPSyncController.snapExclusionReason(
      PeerInfo(status(Capability.ETH68, head), forkAccepted = true),
      Some("SnapRequestTimeout")
    ) shouldBe Some("blacklisted(SnapRequestTimeout)")
    SNAPSyncController
      .snapExclusionReason(PeerInfo(status(Capability.ETH68, genesis), forkAccepted = true), None)
      .exists(_.startsWith("best-block-is-genesis")) shouldBe true
  }

  // The genesis clause cannot misfire on eth/69+/70: their STATUS carries `latestBlockHash`, which seeds bestBlockHash
  // exactly as eth/68's `bestHash` does, and BlockRangeUpdate moves it (via withBestBlockData) like any other head
  // signal. Only a peer that itself reports genesis as its head is excluded.
  it should "admit eth/69 and eth/70 peers from their STATUS latestBlockHash, with no header probe" taggedAs UnitTest in {
    for cap <- Seq(Capability.ETH69, Capability.ETH70) do
      val info = PeerInfo(status(cap, head, latestBlock = Some(BigInt(5000))), forkAccepted = true)
      info.bestBlockHash shouldBe head
      info.maxBlockNumber shouldBe BigInt(5000)
      SNAPSyncController.snapExclusionReason(info, None) shouldBe None
  }

  it should "admit an eth/69 peer that announced genesis, once a BlockRangeUpdate moves its head" taggedAs UnitTest in {
    val atGenesis = PeerInfo(status(Capability.ETH69, genesis, latestBlock = Some(BigInt(0))), forkAccepted = true)
    SNAPSyncController.snapExclusionReason(atGenesis, None).isDefined shouldBe true
    // NetworkPeerManagerActor.updateMaxBlock applies a BlockRangeUpdate(latestBlock, latestBlockHash) like this:
    val moved = atGenesis.withBestBlockData(BigInt(7000), head)
    SNAPSyncController.snapExclusionReason(moved, None) shouldBe None
  }

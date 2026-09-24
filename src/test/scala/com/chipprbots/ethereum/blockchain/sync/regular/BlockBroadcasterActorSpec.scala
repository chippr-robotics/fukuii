package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.PeerListSupportNg.PeerWithInfo
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

/** Direct, actor-free coverage of `BlockBroadcasterActor.staleAmong` — the filter that fixed the hive devp2p `eth`
  * suite panic. `WrappedHandshakedPeers`'s newly-observed-peer catch-up used to re-announce the canonical head to EVERY
  * peer the periodic scan had not seen before, regardless of whether that peer's own STATUS handshake already reported
  * the current head. Once one post-merge `forkchoiceUpdated` had ever landed (`TestLargeTxRequest` is the first hive
  * `eth`-suite test to trigger one), every later test's fresh connection — whose own Status already reported the
  * post-FCU head correctly — was "newly observed" and got an unsolicited BlockRangeUpdate mid-test. hive's tool has no
  * tolerance for that: `Conn.ReadEth` panics on any message code it does not explicitly expect (`panic: unhandled eth
  * msg code 17`, conn.go:212).
  */
class BlockBroadcasterActorSpec extends AnyFlatSpec with Matchers:

  private val baseBlockHeader: BlockHeader = Fixtures.Blocks.Block3125369.header
  private val head: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1000))

  // staleAmong only reads PeerInfo.maxBlockNumber — the Peer's actor ref is never touched, so a null stand-in
  // (never dereferenced) is sufficient and avoids spinning up an ActorSystem/TestProbe for a pure-function test.
  private def peerWithMaxBlock(id: String, maxBlockNumber: BigInt, eth69: Boolean = true): (PeerId, PeerWithInfo) =
    val peerId = PeerId(id)
    val peer =
      Peer(peerId, new InetSocketAddress("127.0.0.1", 0), null.asInstanceOf[TypedActorRef[PeerActor.Command]], false)
    val status = RemoteStatus(
      capability = if eth69 then Capability.ETH69 else Capability.ETH68,
      networkId = 1,
      chainWeight = ChainWeight.totalDifficultyOnly(BigInt(1)),
      bestHash = baseBlockHeader.hash.value,
      genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
      latestBlock = if eth69 then Some(maxBlockNumber) else None
    )
    val info = PeerInfo(
      remoteStatus = status,
      chainWeight = status.chainWeight,
      forkAccepted = true,
      maxBlockNumber = maxBlockNumber,
      bestBlockHash = status.bestHash
    )
    peerId -> PeerWithInfo(peer, info)

  "staleAmong" should "exclude an ETH69+ peer whose own Status already reports it at or beyond the announced head" taggedAs (
    UnitTest
  ) in {
    val current = peerWithMaxBlock("current", head.number.value) // exactly caught up
    val ahead = peerWithMaxBlock("ahead", head.number.value + 50) // already past our head

    val stale = BlockBroadcasterActor.staleAmong(head, Map(current, ahead))

    stale shouldBe empty
  }

  it should "include an ETH69+ peer whose own Status reports it behind the announced head (the genesis-race case)" taggedAs (
    UnitTest
  ) in {
    val genesisPeer = peerWithMaxBlock("genesis", BigInt(0)) // handshook before the FCU landed
    val laggingPeer = peerWithMaxBlock("lagging", head.number.value - 1)

    val stale = BlockBroadcasterActor.staleAmong(head, Map(genesisPeer, laggingPeer))

    stale.keySet shouldBe Set(genesisPeer._1, laggingPeer._1)
  }

  it should "always include a pre-ETH69 peer (maxBlockNumber is always 0, unaffected by this filter)" taggedAs (
    UnitTest
  ) in {
    val legacyPeer = peerWithMaxBlock("legacy", BigInt(0), eth69 = false)

    val stale = BlockBroadcasterActor.staleAmong(head, Map(legacyPeer))

    stale.keySet shouldBe Set(legacyPeer._1)
  }

  it should "return an empty map when given an empty candidate map" taggedAs (UnitTest) in {
    BlockBroadcasterActor.staleAmong(head, Map.empty) shouldBe empty
  }

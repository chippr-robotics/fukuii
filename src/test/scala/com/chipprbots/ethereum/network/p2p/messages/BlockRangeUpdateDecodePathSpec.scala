package com.chipprbots.ethereum.network.p2p.messages

import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.Mocks.MockHandshakerAlwaysSucceeds
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.AdaptedMessageFromEventBus
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.KnownNodesManager
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerActor.ConnectTo
import com.chipprbots.ethereum.network.PeerActor.GetStatus
import com.chipprbots.ethereum.network.PeerActor.StatusResponse
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockRangeUpdate
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** Covers two previously untested paths in the ETH69 BlockRangeUpdate (BRU) decode pipeline:
  *
  *   - Case 1 (PeerActor): a semantically invalid BRU (earliestBlock > latestBlock, or 32-byte zero hash) must
  *     disconnect the peer with BreachOfProtocol immediately.
  *
  *   - Case 2 (BlockFetcher): a valid BRU must invoke `withPossibleNewTopAt(latestBlock)`, observable as
  *     `ProgressProtocol.GotNewBlock(latestBlock)` forwarded to the supervisor.
  *
  * See §ETH-T7-D in DEFERRED-BACKLOG.md (cleared by this spec).
  */
class BlockRangeUpdateDecodePathSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyWordSpecLike
    with Matchers:

  // ─── shared fixtures ────────────────────────────────────────────────────────

  private val testUri: URI = new URI(
    "enode://18a551bee469c2e02de660ab01dede06503c986f6b8520cb5a65ad122df88b17b285e3fef09a40a0d44f99e014f8616cf1ebc2e094f96c6e09e2f390f5d34857@47.90.36.129:30303"
  )

  private val validHash: ByteString = ByteString(Array.fill(32)(0xab.toByte))
  private val zeroHash32: ByteString = ByteString(new Array[Byte](32))

  private val dummyStatus: RemoteStatus = RemoteStatus(
    capability = Capability.ETH68,
    networkId = 1L,
    chainWeight = ChainWeight.totalDifficultyOnly(BigInt(1_000_000)),
    bestHash = validHash,
    genesisHash = ByteString(Array.fill(32)(0xcd.toByte))
  )

  // ─── Case 1: PeerActor — malformed BRU triggers BreachOfProtocol ────────────

  "PeerActor" should {

    "disconnect with BreachOfProtocol when BlockRangeUpdate has earliestBlock > latestBlock" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      val cs = testKit.system.classicSystem
      val rlpxProbe = TestProbe()(using cs)
      val eventBusProbe = TestProbe()(using cs)
      val knownProbe = TestProbe()(using cs)
      val peer: TestActorRef[Nothing] = TestActorRef(
        PropsAdapter(
          PeerActor.apply(
            new InetSocketAddress("127.0.0.1", 0),
            _ => rlpxProbe.ref.toTyped[RLPxConnectionHandler.Command],
            Config.Network.peer,
            eventBusProbe.ref.toTyped[PeerEventBusActor.Command],
            knownProbe.ref.toTyped[KnownNodesManager.Command],
            false,
            MockHandshakerAlwaysSucceeds(dummyStatus, BigInt(0), true)
          )
        )
      )(using cs)

      peer ! ConnectTo(testUri)
      rlpxProbe.expectMsg(RLPxConnectionHandler.ConnectTo(testUri))
      rlpxProbe.send(peer, RLPxConnectionHandler.ConnectionEstablished(ByteString.empty))

      // MockHandshakerAlwaysSucceeds → immediate HandshakeSuccess; expectMsg syncs on Handshaked state.
      val statusProbe = TestProbe()(using cs)
      peer ! GetStatus(statusProbe.ref.toTyped[StatusResponse])
      statusProbe.expectMsg(StatusResponse(PeerActor.Status.Handshaked))

      peer ! RLPxConnectionHandler.MessageReceived(
        BlockRangeUpdate(earliestBlock = BigInt(100), latestBlock = BigInt(0), latestBlockHash = validHash)
      )
      rlpxProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(Disconnect.Reasons.BreachOfProtocol)))
    }

    "disconnect with BreachOfProtocol when BlockRangeUpdate has all-zero latestBlockHash" taggedAs (
      UnitTest,
      NetworkTest
    ) in {
      val cs = testKit.system.classicSystem
      val rlpxProbe = TestProbe()(using cs)
      val eventBusProbe = TestProbe()(using cs)
      val knownProbe = TestProbe()(using cs)
      val peer: TestActorRef[Nothing] = TestActorRef(
        PropsAdapter(
          PeerActor.apply(
            new InetSocketAddress("127.0.0.1", 0),
            _ => rlpxProbe.ref.toTyped[RLPxConnectionHandler.Command],
            Config.Network.peer,
            eventBusProbe.ref.toTyped[PeerEventBusActor.Command],
            knownProbe.ref.toTyped[KnownNodesManager.Command],
            false,
            MockHandshakerAlwaysSucceeds(dummyStatus, BigInt(0), true)
          )
        )
      )(using cs)

      peer ! ConnectTo(testUri)
      rlpxProbe.expectMsg(RLPxConnectionHandler.ConnectTo(testUri))
      rlpxProbe.send(peer, RLPxConnectionHandler.ConnectionEstablished(ByteString.empty))

      val statusProbe = TestProbe()(using cs)
      peer ! GetStatus(statusProbe.ref.toTyped[StatusResponse])
      statusProbe.expectMsg(StatusResponse(PeerActor.Status.Handshaked))

      peer ! RLPxConnectionHandler.MessageReceived(
        BlockRangeUpdate(earliestBlock = BigInt(0), latestBlock = BigInt(100), latestBlockHash = zeroHash32)
      )
      rlpxProbe.expectMsg(RLPxConnectionHandler.SendMessage(Disconnect(Disconnect.Reasons.BreachOfProtocol)))
    }
  }

  // ─── Case 2: BlockFetcher — valid BRU calls withPossibleNewTopAt ─────────────

  "BlockFetcher" should {

    "forward GotNewBlock to supervisor with latestBlock when a valid BlockRangeUpdate raises knownTop" taggedAs (
      UnitTest,
      SyncTest
    ) in new FetcherSetup:
      startFetcher()
      fetcher ! AdaptedMessageFromEventBus(
        BlockRangeUpdate(earliestBlock = BigInt(0), latestBlock = BigInt(200), latestBlockHash = validHash),
        PeerId("test-peer")
      )
      // withPossibleNewTopAt(200) sets knownTop=200; GotNewBlock is the only supervisor message from the BRU handler.
      supervisor.expectMsg(SyncProtocol.ProgressProtocol.GotNewBlock(BigInt(200)))
  }

  // ─── FetcherSetup ────────────────────────────────────────────────────────────

  trait FetcherSetup extends TestSyncConfig:
    val peersClient: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val peerEventBus: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val supervisor: TestProbe = TestProbe()(using testKit.system.classicSystem)
    val importer: TestProbe = TestProbe()(using testKit.system.classicSystem)

    lazy val validators = new MockValidatorsAlwaysSucceed

    lazy val fetcher: ActorRef[BlockFetcher.FetchCommand] = testKit.spawn(
      BlockFetcher(
        peersClient.ref.toTyped[PeersClient.Command],
        peerEventBus.ref,
        supervisor.ref,
        syncConfig,
        validators.blockValidator
      ),
      s"bru-fetcher-${UUID.randomUUID()}"
    )

    def startFetcher(fromBlock: BigInt = 0): Unit =
      fetcher ! BlockFetcher.Start(importer.ref.toTyped[BlockImporter.Command], fromBlock)
      peerEventBus.expectMsgType[SubscribeCmd]

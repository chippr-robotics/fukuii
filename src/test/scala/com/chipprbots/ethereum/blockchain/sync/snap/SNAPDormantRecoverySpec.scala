package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.compiletime.asMatchable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.GetHandshakedPeersCmd
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*

/** When SNAP cannot proceed it goes dormant — keeping its downloaded state and sending nothing to the SyncController —
  * and the dormant wake-up restarts it on a fresh pivot once a snap-capable peer is connected. This is SNAP's own
  * recovery for the three cases that used to hand the node to fast sync: no snap-capable peer after the capability
  * grace period, bootstrap retries exhausted, and a missing genesis header. Also covered: a woken controller gets a
  * fresh bootstrap retry budget, and the proactive pivot roll moves an old pivot SNAP took before any peer connected.
  *
  * The parent probe stands in for the SyncController. The old fast-sync fallback messaged it at once; here it must stay
  * silent until the wake-up, whose fresh pivot shows up as a `StartRegularSyncBootstrap`. Only `dormantRetry` handles
  * `DormantWakeUp`, so that request also proves the controller was dormant.
  */
class SNAPDormantRecoverySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  private val PivotOffset = SNAPSyncConfig().pivotBlockOffset
  private val MaxBootstrapRetries = 10 // SNAPSyncController.MaxBootstrapRetries (private)

  "SNAPSyncController" should "go dormant, not fall back, when no snap-capable peer is left at the capability check" taggedAs UnitTest in new Fixture:
    storeGenesis()
    peers.set(peersAt(height = 10, snap = true)) // at or below the pivot offset: SNAP starts from genesis
    val snap = spawnController()
    awaitFirstPoll()
    snap ! SNAPSyncController.Start
    // The snap peer stops advertising snap/1 before the capability check.
    pollWith(snap, peersAt(height = 10, snap = false))
    snap ! SNAPSyncController.CheckSnapCapability
    parent.expectNoMessage(500.millis)

    wakeWithSnapPeersAt(snap, height = 1000)
    parent.expectMessage(SNAPSyncController.StartRegularSyncBootstrap(BigInt(1000 - PivotOffset)))

  it should "go dormant, not fall back, when bootstrap retries run out without a peer" taggedAs UnitTest in new Fixture:
    storeGenesis()
    peers.set(Map.empty)
    val snap = spawnController()
    awaitFirstPoll()
    snap ! SNAPSyncController.Start // no peer: network height unknown, retry scheduled (attempt 1)
    (2 to MaxBootstrapRetries).foreach(_ => snap ! SNAPSyncController.RetrySnapSyncStart)
    parent.expectNoMessage(500.millis)

    wakeWithSnapPeersAt(snap, height = 1000)
    parent.expectMessage(SNAPSyncController.StartRegularSyncBootstrap(BigInt(1000 - PivotOffset)))

  it should "give a woken controller a fresh bootstrap retry budget" taggedAs UnitTest in new Fixture:
    storeGenesis()
    peers.set(Map.empty)
    val snap = spawnController()
    awaitFirstPoll()
    snap ! SNAPSyncController.Start
    (2 to MaxBootstrapRetries).foreach(_ => snap ! SNAPSyncController.RetrySnapSyncStart) // budget spent: dormant

    // It wakes to a snap peer whose height is not known yet, so the first attempt has to retry. With the spent
    // budget carried over, that one retry sends it straight back to dormant.
    wakeWithSnapPeersAt(snap, height = 0)
    pollWith(snap, peersAt(height = 1000, snap = true))
    snap ! SNAPSyncController.RetrySnapSyncStart
    parent.expectMessage(10.seconds, SNAPSyncController.StartRegularSyncBootstrap(BigInt(1000 - PivotOffset)))

  // Forge's note on the stranded-fast-sync floor: with no peer connected at start, SNAP's pivot selection uses the local
  // best block, so the floor (best + 1) makes it commit a pivot from a header fast sync stored, however old. The proactive
  // pivot roll moves it to a fresh one as soon as a snap peer shows the real head.
  it should "roll off an old floor pivot it took from a local header before any peer connected" taggedAs UnitTest in new Fixture:
    storeGenesis()
    val strandedBest = BigInt(1000)
    Seq(strandedBest, strandedBest + 1).foreach { n =>
      blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(n))).commit()
    }
    storagesInstance.storages.appStateStorage.putBestBlockNumber(strandedBest).commit()
    peers.set(Map.empty)
    val snap = spawnController()
    awaitFirstPoll()

    snap ! SNAPSyncController.MinPivotBlock(strandedBest + 1)
    snap ! SNAPSyncController.Start
    eventually(storagesInstance.storages.appStateStorage.getSnapSyncPivotBlock() shouldBe Some(strandedBest + 1))

    // A snap peer shows the real head, the capability check starts the account download, and the next stagnation
    // check sees a pivot far outside the serve window.
    val head = 3000
    pollWith(snap, peersAt(height = head, snap = true))
    snap ! SNAPSyncController.CheckSnapCapability
    parent.expectNoMessage(500.millis) // the download starts on the old pivot; only the roll moves it
    snap ! SNAPSyncController.CheckDownloadStagnation
    parent.expectMessage(10.seconds, SNAPSyncController.StartRegularSyncBootstrap(BigInt(head - PivotOffset)))

  it should "go dormant, not fall back, when the genesis header is missing" taggedAs UnitTest in new Fixture:
    peers.set(peersAt(height = 10, snap = true)) // genesis pivot, but no genesis header stored
    val snap = spawnController()
    awaitFirstPoll()
    snap ! SNAPSyncController.Start
    parent.expectNoMessage(500.millis)

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPDormantRecoverySpec.this.system.classicSystem

    val peers: AtomicReference[Map[Peer, PeerInfo]] = new AtomicReference(Map.empty)
    private val pollsAnswered = new AtomicInteger(0)

    // Stands in for NetworkPeerManagerActor: answers each peer poll with the current `peers`. The reply is sent before
    // the counter moves, so a test that waits on the counter knows the reply is already in the controller's mailbox.
    private val networkPeerManager: TestProbe = TestProbe()
    networkPeerManager.setAutoPilot(
      new AutoPilot:
        override def run(sender: ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case GetHandshakedPeersCmd(replyTo) =>
              replyTo ! HandshakedPeers(peers.get())
              pollsAnswered.incrementAndGet()
            case _ => ()
          this
    )

    val parent: TypedTestProbe[SyncProtocol.SyncControllerReply] =
      testKit.createTestProbe[SyncProtocol.SyncControllerReply]()

    def storeGenesis(): Unit =
      val genesis = Fixtures.Blocks.Genesis.header
      blockchainWriter.save(
        Block(genesis, BlockBody.empty),
        Nil,
        ChainWeight.totalDifficultyOnly(genesis.difficulty.value),
        saveAsBestBlock = true
      )

    def spawnController(): TypedActorRef[SNAPSyncController.Command] =
      given ExecutionContext = system.executionContext
      testKit.spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.flatSlotStorage,
          networkPeerManager.ref.toTyped[NetworkPeerManagerActor.Command],
          testKit.spawn(PeerEventBusActor.behavior()),
          syncConfig,
          SNAPSyncConfig(),
          system.classicSystem.scheduler,
          CacheBasedBlacklist.empty(100),
          parent.ref
        )
      )

    /** Wait for `start()`'s immediate peer poll to be answered. */
    def awaitFirstPoll(): Unit = eventually(pollsAnswered.get() should be >= 1)

    /** Swap the peer set and poll, returning once the new set is in the controller's mailbox. */
    def pollWith(snap: TypedActorRef[SNAPSyncController.Command], newPeers: Map[Peer, PeerInfo]): Unit =
      peers.set(newPeers)
      val answered = pollsAnswered.get()
      snap ! SNAPSyncController.PollHandshakedPeers
      eventually(pollsAnswered.get() should be > answered)

    def wakeWithSnapPeersAt(snap: TypedActorRef[SNAPSyncController.Command], height: Int): Unit =
      pollWith(snap, peersAt(height, snap = true))
      snap ! SNAPSyncController.DormantWakeUp

    def peersAt(height: Int, snap: Boolean): Map[Peer, PeerInfo] =
      val status = RemoteStatus(
        Capability.ETH68,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(height),
        bestHash = ByteString(s"best-$height"),
        genesisHash = Fixtures.Blocks.Genesis.header.hash.value,
        supportsSnap = snap
      )
      val peer = Peer(
        PeerId("snap-peer"),
        new InetSocketAddress("127.0.0.1", 30303),
        TestProbe().ref.toTyped[PeerActor.Command],
        incomingConnection = false
      )
      Map(
        peer -> PeerInfo(
          remoteStatus = status,
          chainWeight = status.chainWeight,
          forkAccepted = true,
          maxBlockNumber = height,
          bestBlockHash = status.bestHash
        )
      )

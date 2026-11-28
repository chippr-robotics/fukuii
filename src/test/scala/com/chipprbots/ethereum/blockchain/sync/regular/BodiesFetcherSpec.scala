package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress
import java.util.UUID

import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Fixtures.Blocks as FixtureBlocks
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class BodiesFetcherSpec extends ScalaTestWithActorTestKit with AnyFreeSpecLike with Matchers:

  "BodiesFetcher" - {
    "should ask for the slice of a slice worker that died again, from a new worker (B-016)" taggedAs (
      UnitTest,
      SyncTest
    ) in new Setup(retries = 3):
      fetcher ! BodiesFetcher.FetchBodies(hashes)
      askedForBothSlices()
      // Only the fetcher's watch tells it the worker died: the worker reported nothing, and a request that never ends
      // is not a failure either.
      kill("bodies-slice-1")
      // both first requests are consumed, so the next request is the retry's
      hashesOf(nextRequest()) shouldBe firstSlice

    "should give the batch up as a failed fetch when the slice of a dead worker has no retry left (B-016)" taggedAs (
      UnitTest,
      SyncTest
    ) in new Setup(retries = 0):
      fetcher ! BodiesFetcher.FetchBodies(hashes)
      askedForBothSlices()
      kill("bodies-slice-2")
      supervisor.expectMessageType[BlockFetcher.RetryBodiesRequest](5.seconds)

    "should not count a slice worker that reported and then stopped as dead (B-530)" taggedAs (
      UnitTest,
      SyncTest
    ) in new Setup(retries = 3):
      fetcher ! BodiesFetcher.FetchBodies(hashes)
      val requests: Map[Seq[ByteString], Request[?]] = askedForBothSlices()
      val reporter: org.apache.pekko.actor.ActorRef = Await.result(
        system.classicSystem.actorSelection(fetcher.path / "bodies-slice-1").resolveOne(5.seconds),
        5.seconds
      )
      val deathWatch: TestProbe[Any] = createTestProbe[Any]()

      requests(firstSlice).replyTo ! PeersClient.Response(peer, ETHPackets.BlockBodies(BigInt(1), bodiesOf(firstSlice)))
      // the worker stops itself after it reported
      deathWatch.expectTerminated(reporter.toTyped[Any], 5.seconds)
      // The fetcher reads the death of a worker before the report that preceded it, and confirms through its own
      // mailbox. Two round trips through it, so the confirmation has been handled.
      roundTrip()
      roundTrip()

      // a worker counted as dead would have its slice asked again, or the batch given up
      peersClient.expectNoMessage(300.millis)
      supervisor.expectNoMessage(100.millis)

      // and the batch is still waiting for the other slice, and completes with it
      requests(secondSlice).replyTo ! PeersClient.Response(
        peer,
        ETHPackets.BlockBodies(BigInt(2), bodiesOf(secondSlice))
      )
      supervisor.expectMessageType[BlockFetcher.ReceivedBodies](5.seconds).bodies should have size 4L
  }

  class Setup(retries: Int) extends TestSyncConfig:
    val peersClient: TestProbe[PeersClient.Command] = createTestProbe[PeersClient.Command]()
    val supervisor: TestProbe[BlockFetcher.FetchCommand] = createTestProbe[BlockFetcher.FetchCommand]()
    val peerProbe: TestProbe[PeerActor.Command] = createTestProbe[PeerActor.Command]()
    val peer: Peer =
      Peer(PeerId("p1"), new InetSocketAddress("127.0.0.1", 0), peerProbe.ref, incomingConnection = false)

    // two workers: more hashes than one request holds, so the fetch is fanned out
    override lazy val syncConfig: Config.SyncConfig =
      defaultSyncConfig.copy(blockBodiesPerRequest = 2, bodiesFetchConcurrency = 2, maxBodyFetchRetries = retries)

    val chain: Seq[Block] = BlockHelpers.generateChain(4, FixtureBlocks.Genesis.block)
    val hashes: Seq[ByteString] = chain.map(_.hash.value)
    val firstSlice: Seq[ByteString] = hashes.take(2)
    val secondSlice: Seq[ByteString] = hashes.drop(2)

    val fetcher: ActorRef[BodiesFetcher.BodiesFetcherCommand] =
      spawn(BodiesFetcher(peersClient.ref, syncConfig, supervisor.ref), s"bodies-fetcher-${UUID.randomUUID()}")

    def bodiesOf(slice: Seq[ByteString]): Seq[BlockBody] =
      chain.filter(b => slice.contains(b.hash.value)).map(_.body)

    /** Receives the next two requests of the peers client, in whatever order the two workers got to send them: the
      * workers are separate actors, so which asks first is not fixed. Returns them by the slice they ask for.
      */
    def askedForBothSlices(): Map[Seq[ByteString], PeersClient.Request[?]] =
      val requests = peersClient.receiveMessages(2, 5.seconds).collect { case r: PeersClient.Request[?] => r }
      val bySlice = requests.map(r => hashesOf(r) -> r).toMap
      bySlice.keySet shouldBe Set(firstSlice, secondSlice)
      bySlice

    def nextRequest(): PeersClient.Request[?] = peersClient.expectMessageType[PeersClient.Request[?]](5.seconds)

    def hashesOf(request: PeersClient.Request[?]): Seq[ByteString] = request.message match
      case msg: ETHPackets.GetBlockBodies => msg.hashes
      case other                          => fail(s"unexpected request $other")

    /** A message through the fetcher's mailbox and back out to the supervisor, in order with what it was sent before.
      */
    def roundTrip(): Unit =
      fetcher ! BodiesFetcher.RetryBodiesRequest(None, Set.empty, 0)
      supervisor.expectMessageType[BlockFetcher.RetryBodiesRequest](5.seconds)
      ()

    def kill(worker: String): Unit =
      system.classicSystem.actorSelection(fetcher.path / worker) ! PoisonPill

package com.chipprbots.ethereum.blockchain.sync.regular

import java.util.UUID

import org.apache.pekko.actor.PoisonPill
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Fixtures.Blocks as FixtureBlocks
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
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
      asked(firstSlice)
      asked(secondSlice)
      // Only the fetcher's watch tells it the worker died: the worker reported nothing, and a request that never ends
      // is not a failure either.
      kill("bodies-slice-1")
      asked(firstSlice)

    "should give the batch up as a failed fetch when the slice of a dead worker has no retry left (B-016)" taggedAs (
      UnitTest,
      SyncTest
    ) in new Setup(retries = 0):
      fetcher ! BodiesFetcher.FetchBodies(hashes)
      asked(firstSlice)
      asked(secondSlice)
      kill("bodies-slice-2")
      supervisor.expectMessageType[BlockFetcher.RetryBodiesRequest](5.seconds)
  }

  class Setup(retries: Int) extends TestSyncConfig:
    val peersClient: TestProbe[PeersClient.Command] = createTestProbe[PeersClient.Command]()
    val supervisor: TestProbe[BlockFetcher.FetchCommand] = createTestProbe[BlockFetcher.FetchCommand]()

    // two workers: more hashes than one request holds, so the fetch is fanned out
    override lazy val syncConfig: Config.SyncConfig =
      defaultSyncConfig.copy(blockBodiesPerRequest = 2, bodiesFetchConcurrency = 2, maxBodyFetchRetries = retries)

    val hashes: Seq[ByteString] = BlockHelpers.generateChain(4, FixtureBlocks.Genesis.block).map(_.hash.value)
    val firstSlice: Seq[ByteString] = hashes.take(2)
    val secondSlice: Seq[ByteString] = hashes.drop(2)

    val fetcher: ActorRef[BodiesFetcher.BodiesFetcherCommand] =
      spawn(BodiesFetcher(peersClient.ref, syncConfig, supervisor.ref), s"bodies-fetcher-${UUID.randomUUID()}")

    /** Waits for a request of the peers client for exactly `expected`, passing over any other. */
    def asked(expected: Seq[ByteString]): Unit =
      peersClient.fishForMessage(5.seconds) {
        case PeersClient.Request(msg: ETHPackets.GetBlockBodies, _, _, _) if msg.hashes == expected =>
          FishingOutcomes.complete
        case _ => FishingOutcomes.continue
      }
      ()

    def kill(worker: String): Unit =
      system.classicSystem.actorSelection(fetcher.path / worker) ! PoisonPill

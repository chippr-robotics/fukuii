package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe

import scala.collection.immutable.Queue

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotMatchingReadyBlocks
import com.chipprbots.ethereum.network.PeerId

class BlockFetcherStateSpec
    extends TestKit(ActorSystem("BlockFetcherStateSpec_System"))
    with AnyWordSpecLike
    with WithActorSystemShutDown
    with Matchers {

  lazy val validators = new MockValidatorsAlwaysSucceed

  private val importer = TestProbe().ref

  private val blocks = BlockHelpers.generateChain(5, BlockHelpers.genesis)

  private val peer = PeerId("foo")

  "BlockFetcherState" when {
    "invalidating blocks" should {
      "not allow to go to negative block number" taggedAs (UnitTest, SyncTest) in {
        val (_, actual) =
          BlockFetcherState.initial(importer, validators.blockValidator, 10).invalidateBlocksFrom(-5, None)

        actual.lastBlock shouldBe 0
      }
    }

    "handling requested blocks" should {
      "clear headers queue if got empty list of blocks" taggedAs (UnitTest, SyncTest) in {
        val headers = blocks.map(_.header)

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .appendHeaders(headers)
          .map(_.handleRequestedBlocks(List(), peer))

        assert(result.map(_.waitingHeaders) === Right(Queue.empty))
      }

      "enqueue requested blocks" taggedAs (UnitTest, SyncTest) in {

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .appendHeaders(blocks.map(_.header))
          .map(_.handleRequestedBlocks(blocks, peer))

        assert(result.map(_.waitingHeaders) === Right(Queue.empty))
        blocks.foreach { block =>
          assert(result.map(_.blockProviders(block.number)) === Right(peer))
        }
        assert(result.map(_.knownTop) === Right(blocks.last.number))
      }

      "enqueue requested blocks fails when ready blocks is not forming a sequence with given headers" taggedAs (
        UnitTest,
        SyncTest
      ) in {

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .copy(readyBlocks = Queue(blocks.head))
          .appendHeaders(blocks.map(_.header))
          .map(_.handleRequestedBlocks(blocks, peer))

        assert(result.map(_.waitingHeaders) === Left(HeadersNotMatchingReadyBlocks))
      }
    }

    // Bug 31: stale-tip recovery — fast-sync→regular-sync handoff left the fetcher's
    // queue state unable to chain onto any peer's response. The fetcher blacklisted
    // every peer forever with zero self-heal. The counter below is the signal used by
    // BlockFetcher to trigger a rewind via InvalidateBlocksFrom.
    "tracking consecutive header rejections for stale-tip recovery (Bug 31)" should {
      "start the counter at zero on initial state" in {
        BlockFetcherState.initial(importer, validators.blockValidator, 100).consecutiveHeaderRejections shouldBe 0
      }

      "increment the counter via recordHeaderRejection" in {
        val initial = BlockFetcherState.initial(importer, validators.blockValidator, 100)
        initial
          .recordHeaderRejection()
          .recordHeaderRejection()
          .recordHeaderRejection()
          .consecutiveHeaderRejections shouldBe 3
      }

      "reset the counter to zero after a successful appendHeaders" in {
        val stale = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .recordHeaderRejection()
          .recordHeaderRejection()
        stale.consecutiveHeaderRejections shouldBe 2

        val Right(recovered) = stale.appendHeaders(blocks.map(_.header))
        recovered.consecutiveHeaderRejections shouldBe 0
      }

      "cross the rewind threshold when consecutive rejections reach the limit" in {
        val state = BlockFetcherState.initial(importer, validators.blockValidator, 100)
        state.shouldRewindOnRejections(3) shouldBe false
        state.recordHeaderRejection().shouldRewindOnRejections(3) shouldBe false
        state.recordHeaderRejection().recordHeaderRejection().shouldRewindOnRejections(3) shouldBe false
        state
          .recordHeaderRejection()
          .recordHeaderRejection()
          .recordHeaderRejection()
          .shouldRewindOnRejections(3) shouldBe true
      }
    }
  }
}

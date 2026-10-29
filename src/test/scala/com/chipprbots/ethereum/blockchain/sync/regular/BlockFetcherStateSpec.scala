package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherState.HeadersNotMatchingReadyBlocks
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags.*

class BlockFetcherStateSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  lazy val validators = new MockValidatorsAlwaysSucceed

  private val importer = testKit.createTestProbe[BlockImporter.Command]().ref

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

        assert(result.map(_.waitingHeaders) === Right(Vector.empty))
      }

      "enqueue requested blocks" taggedAs (UnitTest, SyncTest) in {

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .appendHeaders(blocks.map(_.header))
          .map(_.handleRequestedBlocks(blocks, peer))

        assert(result.map(_.waitingHeaders) === Right(Vector.empty))
        blocks.foreach { block =>
          assert(result.map(_.blockProviders(block.number.value)) === Right(peer))
        }
        assert(result.map(_.knownTop) === Right(blocks.last.number.value))
      }

      "enqueue requested blocks fails when ready blocks is not forming a sequence with given headers" taggedAs (
        UnitTest,
        SyncTest
      ) in {

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .copy(readyBlocks = Vector(blocks.head))
          .appendHeaders(blocks.map(_.header))
          .map(_.handleRequestedBlocks(blocks, peer))

        assert(result.map(_.waitingHeaders) === Left(HeadersNotMatchingReadyBlocks))
      }

      // #1675: the fetcher reads both queues' sizes and last elements on every step, which a Queue computes in O(n), and
      // matched bodies were appended to a List, copying it per block.
      "keep both queues and the matched blocks indexed, in request order" taggedAs (UnitTest, SyncTest) in {
        val Right(state) =
          BlockFetcherState
            .initial(importer, validators.blockValidator, 0)
            .appendHeaders(blocks.map(_.header)): @unchecked
        state.waitingHeaders shouldBe a[Vector[?]]

        val matched = state.validateBodies(blocks.map(_.body))
        matched shouldBe Right(blocks)
        matched.toOption.get shouldBe a[Vector[?]]

        val fetched = state.handleRequestedBlocks(blocks, peer)
        fetched.readyBlocks shouldBe a[Vector[?]]
        fetched.readyBlocks shouldBe blocks
        fetched.waitingHeaders shouldBe empty
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

        val Right(recovered) = stale.appendHeaders(blocks.map(_.header)): @unchecked
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

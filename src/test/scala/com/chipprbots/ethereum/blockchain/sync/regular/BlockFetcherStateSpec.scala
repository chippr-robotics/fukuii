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
      "not allow to go to negative block number" taggedAs(UnitTest, SyncTest) in {
        val (_, actual) =
          BlockFetcherState.initial(importer, validators.blockValidator, 10).invalidateBlocksFrom(-5, None)

        actual.lastBlock shouldBe 0
      }
    }

    "handling requested blocks" should {
      "clear headers queue if got empty list of blocks" taggedAs(UnitTest, SyncTest) in {
        val headers = blocks.map(_.header)

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .appendHeaders(headers)
          .map(_.handleRequestedBlocks(List(), peer))

        assert(result.map(_.waitingHeaders) === Right(Queue.empty))
      }

      "enqueue requested blocks" taggedAs(UnitTest, SyncTest) in {

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

      "enqueue requested blocks fails when ready blocks is not forming a sequence with given headers" taggedAs(UnitTest, SyncTest) in {

        val result = BlockFetcherState
          .initial(importer, validators.blockValidator, 0)
          .copy(readyBlocks = Queue(blocks.head))
          .appendHeaders(blocks.map(_.header))
          .map(_.handleRequestedBlocks(blocks, peer))

        assert(result.map(_.waitingHeaders) === Left(HeadersNotMatchingReadyBlocks))
      }
    }
  }
}

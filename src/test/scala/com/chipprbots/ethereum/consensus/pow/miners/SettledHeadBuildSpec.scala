package com.chipprbots.ethereum.consensus.pow.miners

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.testing.Tags.*

/** The build of a block on a settled head (spec 018 B-293), with the head and its generation in the test's hands. The
  * moves themselves, on a real position store, are driven in `TestServiceOnPositionsSpec` ("build a block on a settled
  * head only"), which builds through this.
  */
class SettledHeadBuildSpec extends AnyFlatSpec with Matchers:

  private def blockAt(n: Int): Block =
    Block(Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(n)), BlockBody.empty)

  /** A head and a generation that a build moves as it is told to. */
  private class Head(start: Block):
    val best = new AtomicReference[Block](start)
    val generation = new AtomicLong(0L)
    val reader: BlockchainReader = new BlockchainReader(null, null, null, null, null, null, null, null, None):
      override def getBestBlock: Option[Block] = Option(best.get)
      override def headGeneration: Long = generation.get
      override def awaitHeadSettled(maxMillis: Long): Option[Long] = Some(generation.get)

  "A build on a settled head" should "be made once, on the head, when nothing moves it (the hash layout's case)" taggedAs UnitTest in {
    val head = new Head(blockAt(7))
    val builds = new AtomicInteger(0)
    val built = new SettledHeadBuild(head.reader, 0L, 3)(parent => IO { builds.incrementAndGet(); parent.number })
    built.unsafeRunSync() shouldBe Right((blockAt(7), BigInt(7)))
    builds.get() shouldBe 1
  }

  // forge: an import that extends the head moves the disk without a new generation, and the build's view goes stale
  it should "build again when the build's view went stale because the head was moved on" taggedAs UnitTest in {
    val head = new Head(blockAt(7))
    val builds = new AtomicInteger(0)
    val built = new SettledHeadBuild(head.reader, 0L, 3)(parent =>
      IO {
        if builds.incrementAndGet() == 1 then
          head.best.set(blockAt(8))
          throw new StaleStateViewException(
            BlockRef(parent.hash.value, parent.number.value, parent.hash.value),
            1,
            2,
            "x"
          )
        else parent.number
      }
    )
    built.unsafeRunSync() shouldBe Right((blockAt(8), BigInt(8)))
    builds.get() shouldBe 2
  }

  it should "raise a stale view when the head did not move: the fault is the build's own" taggedAs UnitTest in {
    val head = new Head(blockAt(7))
    val stale = new StaleStateViewException(BlockRef(blockAt(7).hash.value, 7, blockAt(7).hash.value), 1, 2, "x")
    val built = new SettledHeadBuild(head.reader, 0L, 3)(_ => IO.raiseError[BigInt](stale))
    (the[StaleStateViewException] thrownBy built.unsafeRunSync() should be).theSameInstanceAs(stale)
  }

  it should "give a build up that a move overtook each time, and build nothing when the head stays moving" taggedAs UnitTest in {
    val head = new Head(blockAt(7))
    val builds = new AtomicInteger(0)
    val overtaken = new SettledHeadBuild(head.reader, 0L, 3)(parent =>
      IO {
        builds.incrementAndGet()
        head.generation.addAndGet(2)
        parent.number
      }
    )
    overtaken.unsafeRunSync().left.getOrElse("") should include("moved during each of 3 builds")
    builds.get() shouldBe 3

    val moving = new Head(blockAt(7)):
      override val reader: BlockchainReader =
        new BlockchainReader(null, null, null, null, null, null, null, null, None):
          override def awaitHeadSettled(maxMillis: Long): Option[Long] = None
    new SettledHeadBuild(moving.reader, 5L, 3)(_ => IO(fail("built while the head moves")))
      .unsafeRunSync()
      .left
      .getOrElse("") should include("still being moved after 5 ms")
  }

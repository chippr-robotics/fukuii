package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.ConcurrentLinkedQueue

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.testing.Tags.*

/** The anchor is told to the observer wherever it is set otherwise than by a commit or a move (B-328): at open, at a
  * genesis commit, at a clear; `db.state.anchor.number` reads it, and did not before the first block's commit.
  */
class AnchorObserverSpec extends AnyFlatSpec with Matchers:

  final private class Told extends PathStateStorage.CommitObserver:
    val anchors = new ConcurrentLinkedQueue[Option[BlockRef]]()
    override def anchorAt(anchor: Option[BlockRef]): Unit = anchors.add(anchor): Unit
    def seen: List[Option[BlockRef]] = anchors.asScala.toList

  "The anchor" should "be told at open, at a genesis commit and at a clear" taggedAs UnitTest in {
    val told = new Told
    val chain = new CanonicalChainOnPositions(PathStateStorage.CommitSettings(observer = told))
    val first = chain.refOf(chain.head)
    withClue("at open, the anchor the datadir holds: ")(told.seen shouldBe List(Some(first)))

    // the same datadir opened again: its anchor is told by the new store
    val reopened = new Told
    new PathStateStorage(
      new PathNodeStorage(chain.source),
      chain.appState,
      settings = PathStateStorage.CommitSettings(observer = reopened)
    ): Unit
    reopened.seen shouldBe List(Some(first))

    told.anchors.clear()
    chain.state.clearGenesisStateForSync(first, atMillis = 1L) shouldBe true
    withClue("a clear leaves no state: ")(told.seen shouldBe List(None))
  }

  it should "move the gauge, which read 0 until the first commit" taggedAs UnitTest in {
    val chain = new CanonicalChainOnPositions(PathStateStorage.CommitSettings(observer = StateMetrics))
    StateMetrics.anchorGauge shouldBe chain.head.number.value.toLong
    chain.state.clearGenesisStateForSync(chain.refOf(chain.head), atMillis = 1L) shouldBe true
    StateMetrics.anchorGauge shouldBe 0L
  }

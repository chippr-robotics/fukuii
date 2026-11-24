package com.chipprbots.ethereum.ledger

import scala.collection.mutable.ListBuffer
import scala.util.Using

import ch.qos.logback.classic.Level
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.snap.SnapLogCapture
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** B-510: what a move of the head makes canonical, as far as [[AdoptedChainWeights.MaxWalk]] below it, and the
  * re-derive of their weights however the move or the commits end.
  */
class AdoptedChainWeightsSpec extends AnyFlatSpec with Matchers:

  private val Genesis: BlockHeader = Fixtures.Blocks.Genesis.header
  private val By = "a test (B-510)"

  // the object's logger is named `...AdoptedChainWeights$`, which a capture of its own name does not take: the package
  private val Scope = "com.chipprbots.ethereum.ledger"

  /** This spec's WARN lines (other suites' may log in the package meanwhile). */
  private def warnings(capture: SnapLogCapture): List[String] =
    capture.events.collect { case (Level.WARN, line) if line.startsWith(By) => line }

  /** `length` headers above `parent`, each with a difficulty of its own. */
  private def headers(parent: BlockHeader, length: Int): Vector[BlockHeader] =
    (1 to length)
      .scanLeft(parent) { (prev, _) =>
        val n = prev.number.value + 1
        Genesis.copy(number = BlockNumber(n), parentHash = prev.hash, difficulty = Difficulty(BigInt(131072) + n * 7))
      }
      .tail
      .toVector

  private class Chain extends EphemBlockchainTestSetup:
    /** Genesis, named by the index with its weight; `above` stored by their hashes only, off the index. */
    def stage(above: Vector[BlockHeader]): Unit =
      blockchainWriter.storeBlockHeader(Genesis).commit()
      blockchainWriter
        .storeChainWeight(Genesis.hash, ChainWeight.totalDifficultyOnly(Genesis.difficulty.value))
        .commit()
      above.foreach(header => blockchainWriter.storeBlockHeaderByHashOnly(header).commit())

    /** `chain` named by the index, as a move of the head up it writes it. */
    def makeCanonical(chain: Seq[BlockHeader]): Unit =
      chain.foreach(header => blockchainWriter.storeBlockHeader(header).commit())

    def weightOf(header: BlockHeader): Option[ChainWeight] = blockchainReader.getChainWeightByHash(header.hash)

    /** Each of `chain`'s exact weight, standing on genesis. */
    def exact(chain: Seq[BlockHeader]): Seq[Option[ChainWeight]] =
      chain
        .scanLeft(ChainWeight.totalDifficultyOnly(Genesis.difficulty.value))(_.increase(_))
        .tail
        .map(Some(_))

    /** A weight that is not the exact one: as a block executed before its parent's was repaired keeps it. */
    def stale(chain: Seq[BlockHeader]): Unit =
      chain.foreach(header =>
        blockchainWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(BigInt(7))).commit()
      )

  "notCanonicalBelow" should "return the branch down to the index, lowest first, and log nothing at WARN" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    val branch: Vector[BlockHeader] = headers(Genesis, AdoptedChainWeights.MaxWalk - 1)
    stage(branch)
    Using.resource(new SnapLogCapture(Scope)) { capture =>
      AdoptedChainWeights.notCanonicalBelow(branch.last, blockchainReader, By) shouldBe branch.map(_.hash)
      warnings(capture) shouldBe empty
    }

  // B-510: at the limit nothing is returned, and the weights of the branch are left as they were executed: said at WARN,
  // naming who moved the head and how many blocks keep their weights
  it should "return nothing at MaxWalk blocks, and say at WARN that their weights are not re-derived" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    val branch: Vector[BlockHeader] = headers(Genesis, AdoptedChainWeights.MaxWalk)
    stage(branch)
    Using.resource(new SnapLogCapture(Scope)) { capture =>
      AdoptedChainWeights.notCanonicalBelow(branch.last, blockchainReader, By) shouldBe empty
      val warned = warnings(capture)
      warned should have size 1
      warned.head should include(s"block #${branch.last.number.value} ${branch.last.hashAsHexString}")
      warned.head should include(
        s"at least ${AdoptedChainWeights.MaxWalk} block(s) it makes canonical keep the weights"
      )
    }

  // B-510: a body that throws after it has made some of the branch canonical (a commit in place, then a failure out of
  // the next one) still has their weights re-derived, and its exception is what propagates
  "rederivingAfter" should "re-derive the weights of what the body made canonical before it threw" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    val branch: Vector[BlockHeader] = headers(Genesis, 5)
    stage(branch)
    stale(branch)
    val committed: ListBuffer[BlockHash] = ListBuffer.empty[BlockHash]
    val failure: IllegalStateException = intercept[IllegalStateException] {
      AdoptedChainWeights.rederivingAfter(committed.toList, blockchainReader, blockchainWriter, By) {
        branch.take(3).foreach { header =>
          makeCanonical(Seq(header))
          committed += header.hash
        }
        throw new IllegalStateException("the fourth commit fails")
      }
    }
    failure.getMessage shouldBe "the fourth commit fails"
    failure.getSuppressed shouldBe empty
    branch.take(3).map(weightOf) shouldBe exact(branch).take(3)
    branch.drop(3).map(weightOf) shouldBe Seq.fill(2)(Some(ChainWeight.totalDifficultyOnly(BigInt(7))))

  it should "re-derive them when the body returns, and read nothing after a body that threw before it made any canonical" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    val branch: Vector[BlockHeader] = headers(Genesis, 5)
    stage(branch)
    stale(branch)
    // thrown before the move wrote anything: the lowest is off the index, so the branch is left as it was
    var read = 0
    intercept[IllegalStateException] {
      AdoptedChainWeights.rederivingAfter(
        { read += 1; branch.map(_.hash) },
        blockchainReader,
        blockchainWriter,
        By
      )(throw new IllegalStateException("refused before any write"))
    }
    read shouldBe 1
    branch.map(weightOf) shouldBe Seq.fill(5)(Some(ChainWeight.totalDifficultyOnly(BigInt(7))))
    // returned: re-derived
    AdoptedChainWeights.rederivingAfter(branch.map(_.hash), blockchainReader, blockchainWriter, By) {
      makeCanonical(branch)
    }
    branch.map(weightOf) shouldBe exact(branch)

  it should "keep the body's exception, with the re-derive's added to it as suppressed" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Chain:
    val branch: Vector[BlockHeader] = headers(Genesis, 2)
    stage(branch)
    makeCanonical(branch)
    val failure: IllegalStateException = intercept[IllegalStateException] {
      AdoptedChainWeights.rederivingAfter(
        branch.map(_.hash) ++ Seq[BlockHash](throw new IllegalArgumentException("the re-derive fails")),
        blockchainReader,
        blockchainWriter,
        By
      )(throw new IllegalStateException("the commit fails"))
    }
    failure.getMessage shouldBe "the commit fails"
    failure.getSuppressed.map(_.getMessage).toSeq shouldBe Seq("the re-derive fails")

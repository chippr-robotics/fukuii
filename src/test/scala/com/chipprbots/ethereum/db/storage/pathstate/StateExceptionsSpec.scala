package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T036: the faults of the state storage are typed outside `MPTException` (data-model 6.3.2).
  *
  * Block execution turns every `MPTException` into an `MPTError`, which ends as a fetched node or an INVALID answer. A
  * stale view or a state this node does not hold is neither, so the type itself must keep it out of that path.
  */
class StateExceptionsSpec extends AnyFlatSpec with Matchers:

  private val block = PathStateTestKit.block(7, ByteString(Array.fill[Byte](32)(1)))

  private val faults: Seq[StateFault] = Seq(
    new StaleStateViewException(block, 4, 8, "the log of state moves no longer reaches back to it"),
    new StateUnavailable("no layer for that block"),
    new ReorgTooDeep(ReorgRefusal.Deep, "the fork point is 900 blocks below the head"),
    new BranchTooLarge(300L << 20, 256L << 20)
  )

  "A state fault" should "not be an MPTException, so that execution cannot turn it into a verdict on a block" taggedAs
    UnitTest in {
      faults.foreach { fault =>
        withClue(fault.getClass.getSimpleName) {
          (fault: Throwable) should not be an[MPTException]
          fault shouldBe a[RuntimeException]
          // The catch block execution has: an MPTException becomes an MPTError there. A fault must fall through it.
          val caughtAsTrieError =
            try throw fault
            catch
              case _: MPTException => true
              case _: StateFault   => false
          caughtAsTrieError shouldBe false
        }
      }
    }

  it should "say what it is about" taggedAs UnitTest in {
    val stale = new StaleStateViewException(block, 4, 8, "the log of state moves no longer reaches back to it")
    stale.block shouldBe block
    stale.createdAtEpoch shouldBe 4
    stale.epochNow shouldBe 8
    stale.getMessage should (include("block 7").and(include("epoch 4")).and(include("now 8")))

    new StateUnavailable("no layer for that block").reason shouldBe "no layer for that block"

    val refused = new ReorgTooDeep(ReorgRefusal.Incomplete, "block 12 has no complete record")
    refused.reason shouldBe ReorgRefusal.Incomplete
    refused.getMessage should (include("Incomplete").and(include("block 12")))

    val large = new BranchTooLarge(300, 256)
    (large.bytes, large.cap) shouldBe (300L, 256L)
  }

  "A refused head move" should "be refused for one of five reasons" taggedAs UnitTest in {
    ReorgRefusal.values.toSeq shouldBe Seq(
      ReorgRefusal.Deep,
      ReorgRefusal.Incomplete,
      ReorgRefusal.Pivot,
      ReorgRefusal.Finalized,
      ReorgRefusal.Corrupt
    )
  }

  "A corrupt stored node" should "stay a missing node, handled wherever a missing node is" taggedAs UnitTest in {
    val corrupt: Throwable =
      new CorruptNodeException(ByteString(Array.fill[Byte](32)(2)), "another node is stored there")
    corrupt shouldBe a[MissingNodeException]
    corrupt shouldBe an[MPTException]
    corrupt should not be a[StateFault]
  }

package com.chipprbots.ethereum.blockchain.sync.regular

import cats.data.NonEmptyList

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.fast.FastSyncBranchResolverActor.BeyondReachVerdict
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.testing.Tags.*

/** The weighing of a branch below the fork recovery's floor on the hash layout (B-346): heavier by stored weights, not
  * refused by MESS, and valid headers, or it is not escalated.
  */
class DeepForkWeightSpec extends AnyWordSpec with Matchers:

  // ten blocks shared, then ours: five more; theirs: six more of the same difficulty, so the heavier
  private val common: List[Block] = BlockHelpers.generateChain(10, BlockHelpers.genesis)
  private val ours: List[Block] = common :++ BlockHelpers.generateChain(5, common.last)
  private val theirs: NonEmptyList[BlockHeader] =
    NonEmptyList.fromListUnsafe(BlockHelpers.generateChain(6, common.last).map(_.header))
  private val lighter: NonEmptyList[BlockHeader] = NonEmptyList.fromListUnsafe(
    BlockHelpers
      .generateChain(6, common.last, b => BlockHelpers.updateHeader(b, _.copy(difficulty = Difficulty(1000))))
      .map(_.header)
  )

  private val weights: Map[BlockHash, ChainWeight] =
    ours
      .scanLeft(ChainWeight.totalDifficultyOnly(0) -> Option.empty[BlockHash]) { case ((weight, _), block) =>
        weight.increase(block.header) -> Some(block.hash)
      }
      .collect { case (weight, Some(hash)) => hash -> weight }
      .toMap
  private val headers: Map[BlockHash, BlockHeader] = ours.map(b => b.hash -> b.header).toMap
  private val canonical: Set[BlockHash] = ours.map(_.hash).toSet

  private def weigher(
      headExact: Boolean = true,
      onTheChain: BlockHeader => Boolean = h => canonical.contains(h.hash),
      messActiveAt: Option[BigInt => Boolean] = None,
      messRefuses: (Long, BigInt, BigInt) => Boolean = (_, _, _) => false,
      firstInvalid: List[BlockHeader] => Option[(BlockHeader, String)] = _ => None
  ): DeepForkWeight =
    new DeepForkWeight(
      weightOf = weights.get,
      headerOf = headers.get,
      onTheChain = onTheChain,
      head = () => Option.when(headExact)(ours.last.header -> weights(ours.last.hash)),
      messActiveAt = messActiveAt,
      messRefuses = messRefuses,
      firstInvalidHeader = firstInvalid
    )

  "DeepForkWeight.verdict" should {

    "say Heavier for a heavier branch with valid headers" taggedAs (UnitTest, SyncTest) in {
      weigher().verdict(theirs) shouldBe BeyondReachVerdict.Heavier
    }

    "say NotHeavier for a lighter branch, and for one as heavy as the head" taggedAs (UnitTest, SyncTest) in {
      weigher().verdict(lighter) shouldBe BeyondReachVerdict.NotHeavier
      weigher().verdict(NonEmptyList.fromListUnsafe(theirs.toList.take(5))) shouldBe BeyondReachVerdict.NotHeavier
    }

    "say NotHeavier where the head's weight is not exact" taggedAs (UnitTest, SyncTest) in {
      weigher(headExact = false).verdict(theirs) shouldBe BeyondReachVerdict.NotHeavier
    }

    "say NotHeavier where the block the branch stands on is not of this chain" taggedAs (UnitTest, SyncTest) in {
      weigher(onTheChain = _ => false).verdict(theirs) shouldBe BeyondReachVerdict.NotHeavier
    }

    "ask MESS with the time since the common block and both subchains' difficulty, and follow it" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      var asked: Option[(Long, BigInt, BigInt)] = None
      val refusing = weigher(
        messActiveAt = Some(_ => true),
        messRefuses = (dt, local, proposed) =>
          asked = Some((dt, local, proposed))
          true
      )
      refusing.verdict(theirs) shouldBe BeyondReachVerdict.NotHeavier
      val perBlock = common.last.header.difficulty.value
      asked shouldBe Some(
        (
          math.max(0L, ours.last.header.unixTimestamp - common.last.header.unixTimestamp),
          perBlock * 5,
          perBlock * 6
        )
      )
      // where MESS is not active at the head, it is not asked
      weigher(messActiveAt = Some(_ => false), messRefuses = (_, _, _) => true).verdict(theirs) shouldBe
        BeyondReachVerdict.Heavier
    }

    "say Invalid for a heavier branch with a header that is not valid, and not validate a lighter one" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      var validated = 0
      val rejecting = weigher(firstInvalid = hs =>
        validated += 1
        hs.lastOption.map(_ -> "bad seal")
      )
      rejecting.verdict(theirs) shouldBe a[BeyondReachVerdict.Invalid]
      rejecting.verdict(lighter) shouldBe BeyondReachVerdict.NotHeavier
      validated shouldBe 1
    }
  }

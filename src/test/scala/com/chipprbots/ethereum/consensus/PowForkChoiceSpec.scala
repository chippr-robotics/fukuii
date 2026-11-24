package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.testing.Tags.*

/** core-geth's `ForkChoice.ReorgNeeded` (core/forkchoice.go), the proof-of-work fork choice with its tie rule (B-284):
  * heavier wins; on a tie the lower block; at the same height this node's own block, else a coin `< 0.5`.
  */
class PowForkChoiceSpec extends AnyFlatSpec with Matchers:
  import PowForkChoiceSpec.*

  "PowForkChoice" should "take a strictly heavier block and keep the head over a strictly lighter one, whatever the heights, the miners and the coin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val choice = new PowForkChoice(_ => true, NoCoin)
    for (current, extern) <- Seq(at(10) -> at(9), at(10) -> at(10, "x"), at(10) -> at(11)) do
      choice.reorgNeeded(current, weight(1000), extern, weight(1001)) shouldBe true
      choice.reorgNeeded(current, weight(1001), extern, weight(1000)) shouldBe false
  }

  it should "take the lower block on a tie, and keep the head over a higher one, with no coin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val choice = new PowForkChoice(_ => false, NoCoin)
    choice.reorgNeeded(at(10), weight(1000), at(9), weight(1000)) shouldBe true
    choice.reorgNeeded(at(10), weight(1000), at(11), weight(1000)) shouldBe false
    // this node's own block does not change it: the heights decide before the miners are asked
    new PowForkChoice(_ => true, NoCoin).reorgNeeded(at(10), weight(1000), at(9), weight(1000)) shouldBe true
  }

  it should "toss the coin on a tie at the same height: below one half takes the block, from one half keeps the head" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    new PowForkChoice(_ => false, () => 0.0).reorgNeeded(at(10), weight(1000), at(10, "x"), weight(1000)) shouldBe true
    new PowForkChoice(_ => false, () => 0.4999).reorgNeeded(at(10), weight(1000), at(10, "x"), weight(1000)) shouldBe
      true
    new PowForkChoice(_ => false, () => 0.5).reorgNeeded(at(10), weight(1000), at(10, "x"), weight(1000)) shouldBe false
    new PowForkChoice(_ => false, () => 0.99)
      .reorgNeeded(at(10), weight(1000), at(10, "x"), weight(1000)) shouldBe false
  }

  it should "toss the coin once for a pair of blocks, and read its outcome for every later weighing of that pair" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val coin = new Coins(0.0, 0.99, 0.99)
    val choice = new PowForkChoice(_ => false, coin)
    val (head, other) = (at(10), at(10, "x"))
    choice.reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe true
    choice.reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe true
    coin.tossed shouldBe 1
    // another pair is another toss
    choice.reorgNeeded(other, weight(1000), head, weight(1000)) shouldBe false
    coin.tossed shouldBe 2
  }

  it should "toss again for a pair it no longer remembers" taggedAs (UnitTest, ConsensusTest) in {
    val coin = new Coins(0.0, 0.99, 0.99)
    val choice = new PowForkChoice(_ => false, coin, remembered = 1)
    val (head, other, third) = (at(10), at(10, "x"), at(10, "y"))
    choice.reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe true
    choice.reorgNeeded(head, weight(1000), third, weight(1000)) shouldBe false
    choice.reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe false
    coin.tossed shouldBe 3
  }

  it should "keep the head on a tie at its height when the head is this node's own, and take the other block when that is, with no coin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val (head, other) = (at(10), at(10, "x"))
    def own(blocks: BlockHeader*): BlockHeader => Boolean = header => blocks.exists(_.hash == header.hash)
    // `!currentPreserve && (externPreserve || coin)`: the head's own keeps it, even against the other's own
    new PowForkChoice(own(head), NoCoin).reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe false
    new PowForkChoice(own(head, other), NoCoin).reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe false
    new PowForkChoice(own(other), NoCoin).reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe true
  }

  it should "keep the head on a tie with a block of no difficulty, a block after a merge, with no coin" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val choice = new PowForkChoice(_ => false, NoCoin)
    val none = com.chipprbots.ethereum.domain.Difficulty.Zero
    choice.reorgNeeded(at(10), weight(1000), at(9).copy(difficulty = none), weight(1000)) shouldBe false
    choice.reorgNeeded(at(10).copy(difficulty = none), weight(1000), at(9), weight(1000)) shouldBe false
    choice.reorgNeeded(at(10), weight(1000), at(10, "x").copy(difficulty = none), weight(1000)) shouldBe false
    // heavier is heavier, whatever the difficulty of the tip
    choice.reorgNeeded(at(10), weight(1000), at(9).copy(difficulty = none), weight(1001)) shouldBe true
  }

  it should "keep the head against itself, weighed again, with no coin" taggedAs (UnitTest, ConsensusTest) in {
    new PowForkChoice(_ => false, NoCoin).reorgNeeded(at(10), weight(1000), at(10), weight(1000)) shouldBe false
  }

  "PowForkChoice.ofEtherbase" should "count a block as this node's own by its beneficiary, as the etherbase is when asked" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val mine = Address(0x6d696e)
    var etherbase = mine
    val choice = PowForkChoice.ofEtherbase(() => etherbase)
    val head = at(10).copy(beneficiary = mine.bytes)
    val other = at(10, "x").copy(beneficiary = Address(0x6f746872).bytes)
    choice.reorgNeeded(head, weight(1000), other, weight(1000)) shouldBe false
    choice.reorgNeeded(other, weight(1000), head, weight(1000)) shouldBe true
    // miner_setEtherbase: the other's miner is this node's now
    etherbase = Address(0x6f746872)
    choice.reorgNeeded(head.copy(extraData = ByteString("z")), weight(1000), other, weight(1000)) shouldBe true
  }

  "PowForkChoice.HeavierOnly" should "keep the head on every tie, as fukuii did before B-284" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val choice = PowForkChoice.HeavierOnly
    choice.reorgNeeded(at(10), weight(1000), at(9), weight(1000)) shouldBe false
    choice.reorgNeeded(at(10), weight(1000), at(10, "x"), weight(1000)) shouldBe false
    choice.reorgNeeded(at(10), weight(1000), at(11), weight(1001)) shouldBe true
  }

object PowForkChoiceSpec:

  def at(number: Int, tag: String = ""): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(number), extraData = ByteString(s"block $number $tag"))

  def weight(td: Int): ChainWeight = ChainWeight.totalDifficultyOnly(td)

  /** A coin that fails the test if it is tossed. */
  object NoCoin extends (() => Double):
    def apply(): Double = throw new AssertionError("the coin was tossed where the rule decides without it")

  /** A coin that lands on `outcomes`, in turn, and counts its tosses. */
  final class Coins(outcomes: Double*) extends (() => Double):
    @volatile var tossed = 0
    def apply(): Double =
      val outcome = outcomes(tossed)
      tossed += 1
      outcome

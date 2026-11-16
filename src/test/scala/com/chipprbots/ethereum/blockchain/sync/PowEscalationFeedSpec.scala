package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed.Refusal
import com.chipprbots.ethereum.db.storage.pathstate.AncestorNotStored
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
import com.chipprbots.ethereum.db.storage.pathstate.ReexecutionDiverged
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.testing.Tags.*

/** What the import of proof-of-work blocks tells the escalation's watch, and what it leaves out (spec 018 C5a part 6b):
  * only a branch that outweighs the head with headers that are valid is counted, and the run ends only when the head is
  * no lighter than what was refused.
  */
class PowEscalationFeedSpec extends AnyFlatSpec with Matchers:

  private val forkHash = BlockHash(ByteString(Array.fill[Byte](32)(7)))
  private val tooDeep: StateFault = new ReorgTooDeep(ReorgRefusal.Deep, "200 blocks below the head")
  private val corrupt: StateFault = new ReorgTooDeep(ReorgRefusal.Corrupt, "a record is not what it should be")

  /** `blocks` blocks from `from` on, each on the one before, the first on the fork block. */
  private def branch(from: Int, blocks: Int, difficulty: Int = 100): List[Block] =
    (0 until blocks)
      .foldLeft(List.empty[Block]) { (made, i) =>
        val header = Fixtures.Blocks.ValidBlock.header.copy(
          parentHash = made.headOption.fold(forkHash)(_.header.hash),
          number = BlockNumber(from + i),
          difficulty = Difficulty(difficulty)
        )
        Block(header, BlockBody.empty) :: made
      }
      .reverse

  private def refusedAt(rig: Rig): Option[BigInt] = rig.feed.refusedWeight.map(_.totalDifficulty.value)

  private class Rig(refusals: Int = 64):
    val escalated: mutable.ArrayBuffer[EscalationKind] = mutable.ArrayBuffer.empty
    val said: mutable.ArrayBuffer[(EscalationKind, String)] = mutable.ArrayBuffer.empty
    val left: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    var head: Option[ChainWeight] = Some(ChainWeight.totalDifficultyOnly(1050))
    var forkWeight: Option[ChainWeight] = Some(ChainWeight.totalDifficultyOnly(1000))
    var invalid: Option[BigInt] = None
    var validated = 0
    val feed = new PowEscalationFeed(
      new EscalationWatch(
        new EscalationTrigger(refusals, refusals * 12000L),
        () => 0L,
        escalated += _,
        (kind, why) => said += ((kind, why))
      ),
      hash => if hash == forkHash then forkWeight else None,
      () => head,
      note = left += _
    )
    def validation(blocks: List[Block]): Option[(BlockHeader, String)] =
      validated += 1
      blocks.collectFirst {
        case block if invalid.contains(block.number.value) => block.header -> "a seal no one mined"
      }
    def refused(blocks: List[Block], fault: StateFault): Unit = feed.notJudged(blocks, fault, validation)

  "The classification of a refused branch" should "be at once only for a fork the state does not reach" taggedAs UnitTest in {
    Seq(ReorgRefusal.Deep, ReorgRefusal.Incomplete, ReorgRefusal.Pivot).foreach { reason =>
      withClue(s"$reason: ")(PowEscalationFeed.classify(new ReorgTooDeep(reason, "x")) shouldBe Refusal.Unreachable)
    }
    Seq[StateFault](
      corrupt,
      new ReexecutionDiverged("block 5 fails now"),
      new StateUnavailable("held back after a write that failed"),
      new AncestorNotStored(6, 3, "bodies")
    ).foreach(fault => withClue(s"$fault: ")(PowEscalationFeed.classify(fault) shouldBe Refusal.Fault))
  }

  "The proof-of-work feed" should "say at once for a heavier branch, with valid headers, the state does not reach" taggedAs UnitTest in {
    val rig = new Rig
    rig.refused(branch(5, 3), tooDeep)
    rig.escalated.toList shouldBe List(EscalationKind.Unreachable)
    rig.said.map(_._1).toList shouldBe List(EscalationKind.Unreachable)
    rig.said.head._2 should (include("[5-7]").and(include("200 blocks below the head")))
    refusedAt(rig) shouldBe Some(BigInt(1300))
    // told each time, said once
    rig.refused(branch(5, 4), tooDeep)
    rig.escalated should have size 2
    rig.said should have size 1
  }

  it should "count nothing for a branch with a header that is not valid, however deep its fork" taggedAs UnitTest in {
    val rig = new Rig
    rig.invalid = Some(6)
    rig.refused(branch(5, 3), tooDeep)
    rig.refused(branch(5, 3), corrupt)
    rig.escalated shouldBe empty
    refusedAt(rig) shouldBe None
    rig.left.last should (include("block 6").and(include("a seal no one mined")))
  }

  it should "count nothing for a branch that does not outweigh the head, and not validate it" taggedAs UnitTest in {
    val rig = new Rig
    // 1000 + 50 = 1050: the head's own weight
    rig.refused(branch(5, 1, difficulty = 50), tooDeep)
    rig.escalated shouldBe empty
    rig.validated shouldBe 0
    rig.left.last should include("does not outweigh")
    // and with no weight to compare, none is assumed
    rig.forkWeight = None
    rig.refused(branch(5, 3), tooDeep)
    rig.head = None
    rig.forkWeight = Some(ChainWeight.totalDifficultyOnly(1000))
    rig.refused(branch(5, 3), tooDeep)
    rig.escalated shouldBe empty
    rig.validated shouldBe 0
  }

  it should "wait, for a fault of this node, until the refused branch has grown by the count" taggedAs UnitTest in {
    val rig = new Rig(refusals = 4)
    (1 to 4).foreach { blocks =>
      rig.refused(branch(5, blocks), corrupt)
      withClue(s"tip ${4 + blocks}: ")(rig.escalated shouldBe empty)
    }
    // the same tip again is no growth
    rig.refused(branch(5, 4), corrupt)
    rig.escalated shouldBe empty
    rig.refused(branch(5, 5), corrupt)
    rig.escalated.toList shouldBe List(EscalationKind.LocalFault)
    rig.said.head._2 should include("a record is not what it should be")
  }

  it should "keep the run when the head moves and is still lighter than the branch refused" taggedAs UnitTest in {
    val rig = new Rig(refusals = 4)
    rig.refused(branch(5, 1, difficulty = 400), corrupt) // 1400, tip 5
    val own = branch(9, 1).head
    // a block of the node's own chain: the head is at 1150, still behind
    rig.head = Some(ChainWeight.totalDifficultyOnly(1150))
    rig.feed.headMoved(own)
    refusedAt(rig) shouldBe Some(BigInt(1400))
    rig.refused(branch(5, 5, difficulty = 400), corrupt) // tip 9: four blocks of growth
    rig.escalated.toList shouldBe List(EscalationKind.LocalFault)
  }

  it should "end the run when the head is no lighter than the branch refused" taggedAs UnitTest in {
    val rig = new Rig(refusals = 4)
    rig.refused(branch(5, 1, difficulty = 400), corrupt)
    rig.head = Some(ChainWeight.totalDifficultyOnly(1400))
    rig.feed.headMoved(branch(9, 1).head)
    refusedAt(rig) shouldBe None
    // the count begins again at the next branch refused
    rig.refused(branch(5, 5, difficulty = 400), corrupt)
    rig.escalated shouldBe empty
  }

  it should "leave the blocks of a chain after its merge to the Engine API" taggedAs UnitTest in {
    val rig = new Rig(refusals = 4)
    rig.refused(branch(5, 1, difficulty = 400), corrupt)
    // a block with no difficulty that became the head does not end a proof-of-work run
    rig.head = Some(ChainWeight.totalDifficultyOnly(9999))
    rig.feed.headMoved(branch(9, 1, difficulty = 0).head)
    refusedAt(rig) shouldBe Some(BigInt(1400))
    // and one that was refused is not counted
    val merged = new Rig
    merged.refused(branch(5, 3, difficulty = 0), tooDeep)
    merged.escalated shouldBe empty
    merged.validated shouldBe 0
    merged.left shouldBe empty
  }

  // C5a part 7: the name of a branch, which the growth is counted under, and its blocks, which the loop guard knows it by
  "The first block of a branch above its fork" should "be the block itself when it stands on the chain, else the lowest side block under it" taggedAs UnitTest in {
    val side = branch(5, 4).map(_.header)
    val stored = side.map(header => header.hash.value -> header).toMap
    // the fork block is the chain's; the side blocks are not
    val chain = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(4))
    val headers: ByteString => Option[BlockHeader] =
      hash => stored.get(hash).orElse(Option.when(hash == forkHash.value)(chain))
    val onTheChain: BlockHeader => Boolean = _ eq chain
    PowEscalationFeed.firstAboveFork(side.head, headers, onTheChain) shouldBe side.head
    PowEscalationFeed.firstAboveFork(side.last, headers, onTheChain) shouldBe side.head
    // a header that is not stored ends the walk at the lowest block found
    PowEscalationFeed.firstAboveFork(side.last, headers.andThen(_.filterNot(_ eq side(1))), onTheChain) shouldBe side(2)
    // and so does the reach
    PowEscalationFeed.firstAboveFork(side.last, headers, onTheChain, reach = 1) shouldBe side(2)
  }

  "The blocks a branch is known by" should "be those refused, tip first, then the stored blocks under them" taggedAs UnitTest in {
    val side = branch(5, 4).map(_.header)
    val chain = Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(4))
    val stored = side.take(2).map(header => header.hash.value -> header).toMap + (forkHash.value -> chain)
    val reads = mutable.ArrayBuffer.empty[ByteString]
    val headers: ByteString => Option[BlockHeader] = hash =>
      reads += hash; stored.get(hash)
    // the two upper blocks were refused; the two under them are stored, and the fork block under those
    val walk = PowEscalationFeed.lineage(side.drop(2), headers)
    walk.take(2).map(_.number).toList shouldBe List[BigInt](8, 7)
    // nothing is read before it is asked for
    reads shouldBe empty
    walk.map(_.number).toList shouldBe List[BigInt](6, 5, 4)
    PowEscalationFeed.lineage(Nil, headers).toList shouldBe Nil
  }

  "The proof-of-work feed" should "count the growth of a branch under one name, whichever block a cut of it begins at" taggedAs UnitTest in {
    val escalated = mutable.ArrayBuffer.empty[EscalationKind]
    val requests = mutable.ArrayBuffer.empty[EscalationWatch.Request]
    val whole = branch(5, 9, difficulty = 400)
    val stored = whole.map(block => block.header.hash.value -> block.header).toMap
    val weights = (forkHash -> ChainWeight.totalDifficultyOnly(1000)) ::
      whole.zipWithIndex.map { case (block, i) => block.header.hash -> ChainWeight.totalDifficultyOnly(1400 + 400 * i) }
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(4), () => 0L, escalated += _, (_, _) => (), requests += _),
      weights.toMap.get,
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      // the blocks of the branch are stored, as side blocks; the block it stands on is the chain's and is not looked up
      stored.get,
      _ => false
    )
    feed.notJudged(whole.take(2), corrupt, _ => None) // tip 6
    // a cut that begins higher on the same branch, as a batch fetched again does
    feed.notJudged(whole.slice(3, 6), corrupt, _ => None) // tip 10: four blocks of growth
    escalated.toList shouldBe List(EscalationKind.LocalFault)
    val name = GivenUpBranch(5, whole.head.header.hash.value)
    requests.map(_.branch.map(_.first)).toList shouldBe List(Some(name))
    // and what it is known by is the cut, tip first, then what is stored under it
    requests.head.branch.get.lineage().map(_.number).toList shouldBe List[BigInt](10, 9, 8, 7, 6, 5)
  }

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
import com.chipprbots.ethereum.db.storage.pathstate.StateBusy
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
    def headersValidation(headers: List[BlockHeader]): Option[(BlockHeader, String)] =
      validated += 1
      headers.collectFirst {
        case header if invalid.contains(header.number.value) => header -> "a seal no one mined"
      }
    def refusedHeaders(headers: List[BlockHeader], fault: StateFault): Unit =
      feed.notJudgedHeaders(headers, fault, headersValidation)

  "The classification of a refused branch" should "be at once only for a fork the state does not reach" taggedAs UnitTest in {
    // Finalized too, which proof of work never meets
    Seq(
      ReorgRefusal.Deep,
      ReorgRefusal.Incomplete,
      ReorgRefusal.Pivot,
      ReorgRefusal.Finalized,
      ReorgRefusal.BeyondTheFetch
    ).foreach { reason =>
      withClue(s"$reason: ")(PowEscalationFeed.classify(new ReorgTooDeep(reason, "x")) shouldBe Refusal.Unreachable)
    }
    Seq[StateFault](
      corrupt,
      new ReexecutionDiverged("block 5 fails now"),
      new StateUnavailable("held back after a write that failed"),
      new AncestorNotStored(6, 3, "bodies")
    ).foreach(fault => withClue(s"$fault: ")(PowEscalationFeed.classify(fault) shouldBe Refusal.Fault))
  }

  // B-300 (g): a branch refused before any of its blocks was fetched is known by its headers alone
  "The proof-of-work feed, told of a branch by its headers" should "count it as it counts the same branch told by its blocks" taggedAs UnitTest in {
    Seq[(String, Rig => Unit, StateFault)](
      ("heavier, unreachable", _ => (), tooDeep),
      ("heavier, a fault of this node", _ => (), corrupt),
      ("lighter", rig => rig.head = Some(ChainWeight.totalDifficultyOnly(5000)), tooDeep),
      ("with a header not valid", rig => rig.invalid = Some(6), tooDeep),
      ("with no weight stored", rig => rig.forkWeight = None, tooDeep)
    ).foreach { case (what, set, fault) =>
      withClue(s"$what: ") {
        val byBlocks = new Rig(refusals = 2)
        val byHeaders = new Rig(refusals = 2)
        set(byBlocks)
        set(byHeaders)
        Seq(3, 5).foreach { blocks =>
          byBlocks.refused(branch(5, blocks), fault)
          byHeaders.refusedHeaders(branch(5, blocks).map(_.header), fault)
        }
        byHeaders.escalated.toList shouldBe byBlocks.escalated.toList
        byHeaders.said.toList shouldBe byBlocks.said.toList
        byHeaders.left.toList shouldBe byBlocks.left.toList
        refusedAt(byHeaders) shouldBe refusedAt(byBlocks)
        byHeaders.validated shouldBe byBlocks.validated
      }
    }
  }

  it should "count nothing for headers that do not outweigh the head, nor for one that is not valid" taggedAs UnitTest in {
    val rig = new Rig
    rig.refusedHeaders(branch(5, 1, difficulty = 50).map(_.header), tooDeep)
    rig.escalated shouldBe empty
    rig.validated shouldBe 0
    rig.left.last should include("does not outweigh")
    rig.invalid = Some(7)
    rig.refusedHeaders(branch(5, 3).map(_.header), tooDeep)
    rig.escalated shouldBe empty
    refusedAt(rig) shouldBe None
    rig.left.last should (include("block 7").and(include("a seal no one mined")))
    // and the same headers, all valid, are counted at once
    rig.invalid = None
    rig.refusedHeaders(branch(5, 3).map(_.header), tooDeep)
    rig.escalated.toList shouldBe List(EscalationKind.Unreachable)
    rig.said.head._2 should include("[5-7]")
  }

  it should "count nothing for headers whose reorganisation MESS refuses" taggedAs UnitTest in {
    val escalated = mutable.ArrayBuffer.empty[EscalationKind]
    val left = mutable.ArrayBuffer.empty[String]
    var refuses = true
    val fork = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(4), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L))
    val head = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(6), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1026L))
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(64), () => 0L, escalated += _, (_, _) => ()),
      hash => Option.when(hash == forkHash)(ChainWeight.totalDifficultyOnly(1000)),
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      hash => Option.when(hash == forkHash.value)(fork),
      _ => true,
      Some(PowEscalationFeed.Mess(_ => true, () => Some(head), (_, _, _) => refuses)),
      left += _
    )
    feed.notJudgedHeaders(branch(5, 3).map(_.header), tooDeep, _ => None)
    escalated shouldBe empty
    feed.refusedWeight shouldBe None
    left.last should include("MESS")
    refuses = false
    feed.notJudgedHeaders(branch(5, 3).map(_.header), tooDeep, _ => None)
    escalated.toList shouldBe List(EscalationKind.Unreachable)
  }

  it should "say whether headers outweigh the head by the weights it counts by, and count nothing for it" taggedAs UnitTest in {
    val rig = new Rig
    // 1000 + 50 is the head's own weight
    rig.feed.outweighsTheHead(branch(5, 1, difficulty = 50).map(_.header)) shouldBe false
    rig.feed.outweighsTheHead(branch(5, 1, difficulty = 51).map(_.header)) shouldBe true
    rig.feed.outweighsTheHead(Nil) shouldBe false
    rig.forkWeight = None
    rig.feed.outweighsTheHead(branch(5, 3).map(_.header)) shouldBe false
    rig.escalated shouldBe empty
    rig.validated shouldBe 0
    refusedAt(rig) shouldBe None
  }

  it should "say at once for a branch regular sync cannot fetch whole" taggedAs UnitTest in {
    PowEscalationFeed.classify(new ReorgTooDeep(ReorgRefusal.BeyondTheFetch, "600 blocks above the fork")) shouldBe
      Refusal.Unreachable
    val rig = new Rig
    rig.refusedHeaders(
      branch(5, 3).map(_.header),
      new ReorgTooDeep(ReorgRefusal.BeyondTheFetch, "600 blocks above the fork")
    )
    rig.escalated.toList shouldBe List(EscalationKind.Unreachable)
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

  // B-462 (forge, 2026-11-24): a branch refused because another thread's adoption holds the head is no fault of this
  // node: not counted, however far it grows, by its blocks or by its headers, and its weight is never taken as refused
  it should "count nothing, and take no weight as refused, for a branch refused because another adoption holds the head" taggedAs UnitTest in {
    val busy: StateFault = new StateBusy("the adoption on thread 'adopter'", "the commit of block 5 was not made")
    val rig = new Rig(refusals = 2)
    (1 to 8).foreach { blocks =>
      rig.refused(branch(5, blocks, difficulty = 400), busy)
      rig.refusedHeaders(branch(5, blocks, difficulty = 400).map(_.header), busy)
    }
    rig.escalated shouldBe empty
    rig.said shouldBe empty
    refusedAt(rig) shouldBe None
    // nothing is validated for it: it is left out before it is weighed
    rig.validated shouldBe 0
    rig.left.last should (include("[5-12]").and(include("another adoption holds the head")))
    // so a head that moves, lighter than that branch, ends the run
    val counted = new Rig(refusals = 4)
    counted.refused(branch(5, 1, difficulty = 400), corrupt)
    counted.refused(branch(5, 8, difficulty = 400), busy)
    refusedAt(counted) shouldBe Some(BigInt(1400))
    counted.head = Some(ChainWeight.totalDifficultyOnly(1400))
    counted.feed.headMoved(branch(9, 1).head)
    refusedAt(counted) shouldBe None
    counted.escalated shouldBe empty
    // and on proof of stake (no difficulty) the feed says nothing of it at all: that is the Engine API's (beacon)
    val pos = new Rig(refusals = 2)
    pos.refused(branch(5, 3, difficulty = 0), busy)
    pos.refusedHeaders(branch(5, 3, difficulty = 0).map(_.header), busy)
    pos.left shouldBe empty
    pos.escalated shouldBe empty
  }

  it should "still count a move that the state moved under between two of its batches, which asks again too" taggedAs UnitTest in {
    // the partial move (`StateUnavailable` asking again) and the replans that ran out are counted as before (forge)
    val askAgain: StateFault = new StateUnavailable("the move stopped between two batches", askAgain = true)
    val rig = new Rig(refusals = 2)
    rig.refused(branch(5, 1), askAgain)
    rig.refused(branch(5, 3), askAgain)
    rig.escalated.toList shouldBe List(EscalationKind.LocalFault)
    refusedAt(rig) shouldBe Some(BigInt(1300))
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

  // forge, B-306: a block a peer announces reaches consensus without the fetcher's MESS check
  "The proof-of-work feed, on a chain with MESS" should "not count a branch whose reorganisation MESS refuses" taggedAs UnitTest in {
    val escalated = mutable.ArrayBuffer.empty[EscalationKind]
    val left = mutable.ArrayBuffer.empty[String]
    val asked = mutable.ArrayBuffer.empty[(Long, BigInt, BigInt)]
    var active = true
    var refuses = true
    var forkStored = true
    val fork = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(4), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L))
    val head = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(6), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1026L))
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(64), () => 0L, escalated += _, (_, _) => ()),
      hash => Option.when(hash == forkHash)(ChainWeight.totalDifficultyOnly(1000)),
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      hash => Option.when(forkStored && hash == forkHash.value)(fork),
      _ => true,
      Some(
        PowEscalationFeed.Mess(
          _ => active,
          () => Some(head),
          (seconds, local, proposed) =>
            asked += ((seconds, local, proposed)); refuses
        )
      ),
      left += _
    )
    feed.notJudged(branch(5, 3), tooDeep, _ => None)
    escalated shouldBe empty
    feed.refusedWeight shouldBe None
    left.last should include("MESS")
    // what the rule weighed: the seconds from the shared block to the head, and each chain's weight above that block
    asked.toList shouldBe List((26L, BigInt(50), BigInt(300)))

    // in force, and what it weighs is not stored: nothing says the branch would pass
    forkStored = false
    feed.notJudged(branch(5, 3), tooDeep, _ => None)
    escalated shouldBe empty
    asked should have size 1

    // a reorganisation the rule lets through is counted, and so is every branch where the rule is not in force
    forkStored = true
    refuses = false
    feed.notJudged(branch(5, 3), tooDeep, _ => None)
    escalated.toList shouldBe List(EscalationKind.Unreachable)
    refuses = true
    active = false
    feed.notJudged(branch(5, 3), tooDeep, _ => None)
    escalated should have size 2
  }

  // B-383, after spec 018 C6 PR-B: where the header of this node's own block that a branch stands on is not stored,
  // MESS does not refuse the branch (BranchResolution.messRejects abstains) and consensus refuses it as a fault of this
  // node (StateUnavailable). The feed is to count it as that fault, by how far the branch grows, and not as a refusal
  // by MESS: else it is never counted, and a node with a hole in its own headers waits with no end
  it should "count a heavier branch whose fork header is missing as a fault of this node, not as a MESS refusal" taggedAs UnitTest in {
    val escalated = mutable.ArrayBuffer.empty[EscalationKind]
    val said = mutable.ArrayBuffer.empty[String]
    val left = mutable.ArrayBuffer.empty[String]
    val asked = mutable.ArrayBuffer.empty[(Long, BigInt, BigInt)]
    var forkIsOurs = true
    val head = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(6), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1026L))
    val unavailable: StateFault =
      new StateUnavailable("block 4, the block the branch leaves the chain at, is not stored")
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(4), () => 0L, escalated += _, (_, why) => said += why),
      hash => Option.when(hash == forkHash)(ChainWeight.totalDifficultyOnly(1000)),
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      // the fork's header is not stored
      _ => None,
      _ => true,
      Some(
        PowEscalationFeed.Mess(
          _ => true,
          () => Some(head),
          (seconds, local, proposed) =>
            asked += ((seconds, local, proposed)); true
        )
      ),
      left += _,
      // the hash index names the block the branch stands on as this node's, at its height
      number => Option.when(forkIsOurs && number == 4)(forkHash)
    )
    // the branch grows while it is refused: tip 7, then tip 11, four blocks of growth
    feed.notJudged(branch(5, 3), unavailable, _ => None)
    escalated shouldBe empty
    feed.refusedWeight.map(_.totalDifficulty.value) shouldBe Some(BigInt(1300))
    feed.notJudged(branch(5, 7), unavailable, _ => None)
    escalated.toList shouldBe List(EscalationKind.LocalFault)
    // MESS was not asked: it has nothing to weigh, and refuses nothing for it
    asked shouldBe empty
    left.exists(_.contains("MESS refuses")) shouldBe false
    left.last should (include("block 4").and(include("not stored")))
    said.last should include("[5-11]")

    // where the block under the branch is not this node's at its height, where it leaves the chain is not known
    escalated.clear()
    forkIsOurs = false
    val other = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(1), () => 0L, escalated += _, (_, _) => ()),
      hash => Option.when(hash == forkHash)(ChainWeight.totalDifficultyOnly(1000)),
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      _ => None,
      _ => true,
      Some(PowEscalationFeed.Mess(_ => true, () => Some(head), (_, _, _) => false)),
      left += _,
      number => Option.when(forkIsOurs && number == 4)(forkHash)
    )
    other.notJudged(branch(5, 3), unavailable, _ => None)
    other.notJudged(branch(5, 7), unavailable, _ => None)
    escalated shouldBe empty
    other.refusedWeight shouldBe None
    left.last should include("not this node's")
    (left.last should not).include("MESS refuses")
  }

  it should "still count nothing for a branch MESS refuses with the fork header stored, and count it once it passes" taggedAs UnitTest in {
    val escalated = mutable.ArrayBuffer.empty[EscalationKind]
    val left = mutable.ArrayBuffer.empty[String]
    var refuses = true
    val fork = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(4), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L))
    val head = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(6), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1026L))
    val unavailable: StateFault = new StateUnavailable("held back after a write that failed")
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(4), () => 0L, escalated += _, (_, _) => ()),
      hash => Option.when(hash == forkHash)(ChainWeight.totalDifficultyOnly(1000)),
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      hash => Option.when(hash == forkHash.value)(fork),
      _ => true,
      Some(PowEscalationFeed.Mess(_ => true, () => Some(head), (_, _, _) => refuses)),
      left += _,
      number => Option.when(number == 4)(forkHash)
    )
    feed.notJudged(branch(5, 3), unavailable, _ => None)
    feed.notJudged(branch(5, 7), unavailable, _ => None)
    escalated shouldBe empty
    feed.refusedWeight shouldBe None
    left.last should include("MESS refuses")
    refuses = false
    feed.notJudged(branch(5, 3), unavailable, _ => None)
    feed.notJudged(branch(5, 7), unavailable, _ => None)
    escalated.toList shouldBe List(EscalationKind.LocalFault)
  }

  // forge, after code: where the walk down the branch ends on a side block (its reach), the block under what it found
  // is not the block the two chains share, and what would be weighed from it is not what the rule weighs
  it should "not count a branch whose shared block it cannot name on the chain" taggedAs UnitTest in {
    val escalated = mutable.ArrayBuffer.empty[EscalationKind]
    val asked = mutable.ArrayBuffer.empty[(Long, BigInt, BigInt)]
    // side blocks under the branch, more of them than the walk goes down, every one stored and none of the chain
    val side = (0 until 1100).foldLeft(Vector.empty[BlockHeader]) { (made, i) =>
      made :+ Fixtures.Blocks.ValidBlock.header.copy(
        parentHash = made.lastOption.fold(BlockHash(ByteString(Array.fill[Byte](32)(3))))(_.hash),
        number = BlockNumber(5000 + i)
      )
    }
    val stored = side.map(header => header.hash.value -> header).toMap
    var chainEndsAt: Option[BlockHeader] = None
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(64), () => 0L, escalated += _, (_, _) => ()),
      _ => Some(ChainWeight.totalDifficultyOnly(1000)),
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      stored.get,
      header => chainEndsAt.exists(_ eq header),
      Some(
        PowEscalationFeed.Mess(
          _ => true,
          () => Some(Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(7000))),
          (seconds, local, proposed) =>
            asked += ((seconds, local, proposed)); false
        )
      )
    )
    val refusedBranch = (0 until 3)
      .foldLeft(List.empty[Block]) { (made, i) =>
        val header = Fixtures.Blocks.ValidBlock.header.copy(
          parentHash = made.headOption.fold(side.last.hash)(_.header.hash),
          number = BlockNumber(6100 + i),
          difficulty = Difficulty(100)
        )
        Block(header, BlockBody.empty) :: made
      }
      .reverse
    feed.notJudged(refusedBranch, tooDeep, _ => None)
    escalated shouldBe empty
    asked shouldBe empty
    // the same branch where the chain is met a few blocks under it: weighed, let through, and counted
    chainEndsAt = Some(side(1095))
    feed.notJudged(refusedBranch, tooDeep, _ => None)
    asked should have size 1
    escalated.toList shouldBe List(EscalationKind.Unreachable)
  }

  // forge: the feed and the fetcher's branch resolution are to refuse the same reorganisations
  it should "weigh a reorganisation as the rule itself does" taggedAs UnitTest in {
    import com.chipprbots.ethereum.consensus.mess.ArtificialFinality
    def counted(headSeconds: Long, branchDifficulty: Int): Boolean =
      val escalated = mutable.ArrayBuffer.empty[EscalationKind]
      val fork = Fixtures.Blocks.ValidBlock.header
        .copy(number = BlockNumber(4), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L))
      val head = Fixtures.Blocks.ValidBlock.header
        .copy(number = BlockNumber(900), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L + headSeconds))
      val feed = new PowEscalationFeed(
        new EscalationWatch(new EscalationTrigger(64), () => 0L, escalated += _, (_, _) => ()),
        hash => Option.when(hash == forkHash)(ChainWeight.totalDifficultyOnly(1000)),
        () => Some(ChainWeight.totalDifficultyOnly(2000)),
        hash => Option.when(hash == forkHash.value)(fork),
        _ => true,
        Some(PowEscalationFeed.Mess(_ => true, () => Some(head), ArtificialFinality.shouldRejectReorg))
      )
      feed.notJudged(branch(5, 3, difficulty = branchDifficulty), tooDeep, _ => None)
      escalated.nonEmpty
    // the node's chain weighs 1000 above the shared block; the branch 3 x its difficulty
    for
      seconds <- Seq(10L, 3600L, 30000L)
      difficulty <- Seq(340, 400, 4000, 12000)
    do
      val refused = ArtificialFinality.shouldRejectReorg(seconds, BigInt(1000), BigInt(3 * difficulty))
      withClue(s"$seconds s, branch ${3 * difficulty}: ")(counted(seconds, difficulty) shouldBe !refused)
    // and the rule does refuse some of these and let others through: the comparison is of both answers
    ArtificialFinality.shouldRejectReorg(30000L, BigInt(1000), BigInt(1020)) shouldBe true
    ArtificialFinality.shouldRejectReorg(10L, BigInt(1000), BigInt(1020)) shouldBe false
  }

  /** B-385: this node's chain above block 4, the block a branch stands on, whose chain weight is not stored. Blocks 5
    * and 6 of difficulty 25 each, 6 the head, weighing 1050: block 4 weighs 1000 by the head. The header of block 4 is
    * stored (the branch's headers are validated against it); its weight is not.
    */
  private class WeightNotStored(mess: Boolean, refuses: Boolean = false, stored: Option[ChainWeight] = None):
    val escalated: mutable.ArrayBuffer[EscalationKind] = mutable.ArrayBuffer.empty
    val said: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val left: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val asked: mutable.ArrayBuffer[(Long, BigInt, BigInt)] = mutable.ArrayBuffer.empty
    val fork: BlockHeader = Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(4), unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L))
    private def own(number: Int, parent: ByteString): BlockHeader =
      Fixtures.Blocks.ValidBlock.header.copy(
        parentHash = BlockHash(parent),
        number = BlockNumber(number),
        difficulty = Difficulty(25),
        unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(1000L + 13 * (number - 4)),
        extraData = ByteString("of this node's chain")
      )
    val own5: BlockHeader = own(5, forkHash.value)
    val own6: BlockHeader = own(6, own5.hash.value)
    var ownStored: Set[ByteString] = Set(own5.hash.value, own6.hash.value)
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(4), () => 0L, escalated += _, (_, why) => said += why),
      hash => if hash == forkHash then stored else None,
      () => Some(ChainWeight.totalDifficultyOnly(1050)),
      hash =>
        if hash == forkHash.value then Some(fork)
        else Seq(own5, own6).find(h => h.hash.value == hash && ownStored.contains(hash)),
      _ => true,
      Option.when(mess)(
        PowEscalationFeed.Mess(
          _ => true,
          () => Some(own6),
          (seconds, local, proposed) =>
            asked += ((seconds, local, proposed)); refuses
        )
      ),
      left += _,
      number => Option.when(number == 4)(forkHash),
      () => Some(own6)
    )

  private val noWeight: StateFault =
    new StateUnavailable("the chain weight of block 4, the block the branch stands on, is not stored")

  "The proof-of-work feed, where the weight of the block a branch stands on is not stored" should "count a heavier branch as a fault of this node, weighed from the head, and not as a MESS refusal" taggedAs UnitTest in {
    for mess <- Seq(true, false) do
      withClue(s"MESS ${if mess then "on" else "off"}: ") {
        val rig = new WeightNotStored(mess)
        // tip 7, then tip 11: four blocks of growth
        rig.feed.notJudgedHeaders(branch(5, 3).map(_.header), noWeight, _ => None)
        rig.escalated shouldBe empty
        // 1000 from the head, and the branch's 300 above it
        rig.feed.refusedWeight.map(_.totalDifficulty.value) shouldBe Some(BigInt(1300))
        rig.feed.notJudgedHeaders(branch(5, 7).map(_.header), noWeight, _ => None)
        rig.escalated.toList shouldBe List(EscalationKind.LocalFault)
        rig.said.last should (include("[5-11]").and(include("is not stored")))
        rig.left.exists(_.contains("is not counted")) shouldBe false
        // where MESS is on, it weighs what the rule weighs: this chain's 50 above block 4 and the branch's
        if mess then
          rig.asked.map(a => (a._2, a._3)).toList shouldBe List((BigInt(50), BigInt(300)), (BigInt(50), BigInt(700)))
        else rig.asked shouldBe empty
      }
  }

  it should "count nothing for a branch that does not outweigh the head, nor where the head's ancestry is not stored down to it" taggedAs UnitTest in {
    val lighter = new WeightNotStored(mess = true)
    // 1000 + 40 against the head's 1050
    lighter.feed.notJudgedHeaders(branch(5, 4, difficulty = 10).map(_.header), noWeight, _ => None)
    // and 1000 + 50, as heavy as the head: not heavier
    lighter.feed.notJudgedHeaders(branch(5, 5, difficulty = 10).map(_.header), noWeight, _ => None)
    lighter.escalated shouldBe empty
    lighter.feed.refusedWeight shouldBe None
    lighter.left.last should include("does not outweigh the head")
    lighter.asked shouldBe empty

    val holed = new WeightNotStored(mess = true)
    // the header of block 5 of this node's chain is not stored: nothing says what block 4 weighs
    holed.ownStored = Set(holed.own6.hash.value)
    holed.feed.notJudgedHeaders(branch(5, 3).map(_.header), noWeight, _ => None)
    holed.feed.notJudgedHeaders(branch(5, 7).map(_.header), noWeight, _ => None)
    holed.escalated shouldBe empty
    holed.left.last should include("is not stored")
    (holed.left.last should not).include("MESS refuses")
  }

  it should "count nothing for a branch MESS refuses, weighed from the head, as with the weight stored" taggedAs UnitTest in {
    val rig = new WeightNotStored(mess = true, refuses = true)
    rig.feed.notJudgedHeaders(branch(5, 3).map(_.header), noWeight, _ => None)
    rig.feed.notJudgedHeaders(branch(5, 7).map(_.header), noWeight, _ => None)
    rig.escalated shouldBe empty
    rig.left.last should include("MESS refuses")
  }

  it should "weigh from the stored weight wherever it is stored, as before" taggedAs UnitTest in {
    // stored at 1040: the branch of 3 x 100 is counted at 1340, not at the 1300 the head would give
    val rig = new WeightNotStored(mess = true, stored = Some(ChainWeight.totalDifficultyOnly(1040)))
    rig.feed.notJudgedHeaders(branch(5, 3).map(_.header), noWeight, _ => None)
    rig.feed.refusedWeight.map(_.totalDifficulty.value) shouldBe Some(BigInt(1340))
    rig.asked.map(a => (a._2, a._3)).toList shouldBe List((BigInt(10), BigInt(300)))
    rig.left.exists(_.contains("weighed from the head")) shouldBe false
  }

  "The difficulty of the head's blocks above a block" should "be summed by their parent hashes, and be none where the walk does not meet it" taggedAs UnitTest in {
    val rig = new WeightNotStored(mess = false)
    val headerOf: ByteString => Option[BlockHeader] = hash => Seq(rig.own5, rig.own6).find(_.hash.value == hash)
    PowEscalationFeed.difficultyAbove(rig.own6, forkHash, headerOf) shouldBe Some(BigInt(50))
    PowEscalationFeed.difficultyAbove(rig.own6, BlockHash(rig.own5.hash.value), headerOf) shouldBe Some(BigInt(25))
    PowEscalationFeed.difficultyAbove(rig.own6, BlockHash(rig.own6.hash.value), headerOf) shouldBe Some(BigInt(0))
    // beyond its reach, or a block that is not of the head's ancestry
    PowEscalationFeed.difficultyAbove(rig.own6, forkHash, headerOf, reach = 1) shouldBe None
    PowEscalationFeed.difficultyAbove(rig.own6, BlockHash(ByteString(Array.fill[Byte](32)(3))), headerOf) shouldBe None
  }

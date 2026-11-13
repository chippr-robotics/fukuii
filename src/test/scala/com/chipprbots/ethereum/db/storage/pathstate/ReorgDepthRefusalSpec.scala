package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4b part 1 (T143): the moves of the head that are refused before anything is read of a state
  * ([[HeadMovePlan]], [[ReorgRefusal]]). `Deep` is said by the records that are there, with a configured depth as
  * policy on top; `Pivot` by the block the state began at; `Incomplete` by a tombstone; `Finalized` by the finalized
  * block, and it is the one that is not answered by syncing the state again; `Corrupt` is never said as `Deep`.
  */
class ReorgDepthRefusalSpec extends AnyFlatSpec with Matchers with LayerChains with PlannedMoves:

  import HeadMovePlan.*
  import PathStateStorage.*
  import PathStateTestKit.*

  /** A chain of `length` recorded blocks on the genesis, and a sibling executed on each of them. */
  final private class Forks(length: Int):
    val model = new Model
    val ours: Vector[BlockRef] = model.chain(model.genesis, length, "a")(model.recorded)
    val head: BlockRef = ours.last

    /** The block `depth` blocks below the head. */
    def below(depth: Int): BlockRef = ours(length - 1 - depth)

    /** A block executed on the block `depth` below the head: a move to it reverses `depth` blocks. */
    def forkAt(depth: Int): BlockRef = model.layered(below(depth), s"fork at $depth")

    /** The records of the blocks more than `kept` below the head are gone, as retention leaves them. */
    def prunedBelow(kept: Int): Limits =
      ours.dropRight(kept).foreach(block => model.records.remove(block.hash))
      model.open.copy(prunedTo = Some(head.number - kept + 1))

  "A move deeper than the records reach" should "be refused as deep, and one that ends on the last block that keeps its record planned" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(40)
      import forks.*
      // the last eight blocks keep their record: the head and the seven below it
      val limits = prunedBelow(8)
      model.planned(head, forkAt(7), limits).depth shouldBe 7
      // the fork point is the block below the lowest record: its own record is not needed
      model.planned(head, forkAt(8), limits).depth shouldBe 8
      model.refused(head, forkAt(9), limits) shouldBe ReorgRefusal.Deep
      model.refused(head, forkAt(30), limits) shouldBe ReorgRefusal.Deep
      // a long branch that forks two below the head is no deeper for its length
      val long = model.chain(below(2), 300, "long")(model.layered)
      model.planned(head, long.last, limits).depth shouldBe 2
      // and a branch of three blocks that forks below the records is refused for all its shortness
      model.refused(head, model.chain(below(9), 3, "short")(model.layered).last, limits) shouldBe ReorgRefusal.Deep
    }

  it should "be decided by what is there and not by the count of what should be: more is reversed where more was kept" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(40)
      import forks.*
      // retention had deleted nothing yet below what its formula now says (the finalized block jumped, or the node
      // started again): the mark of what was pruned is where the records really end
      val lagging = model.open.copy(prunedTo = Some(head.number - 20 + 1))
      ours.dropRight(20).foreach(block => model.records.remove(block.hash))
      model.planned(head, forkAt(20), lagging).depth shouldBe 20
      model.refused(head, forkAt(21), lagging) shouldBe ReorgRefusal.Deep
      // where nothing was ever committed in place there is no mark, and a record that is not there is no corruption
      model.refused(head, forkAt(21), lagging.copy(prunedTo = None)) shouldBe ReorgRefusal.Deep
    }

  "A configured depth" should "refuse a move the records could make, and allow none they cannot" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(40)
      import forks.*
      val limits = prunedBelow(16).copy(maxReverse = 10)
      model.planned(head, forkAt(9), limits).depth shouldBe 9
      model.planned(head, forkAt(10), limits).depth shouldBe 10
      model.refused(head, forkAt(11), limits) shouldBe ReorgRefusal.Deep
      // a depth above what is kept changes nothing: no record reaches that far
      val generous = limits.copy(maxReverse = 5000)
      model.planned(head, forkAt(16), generous).depth shouldBe 16
      model.refused(head, forkAt(17), generous) shouldBe ReorgRefusal.Deep
      // none at all
      model.refused(head, forkAt(1), limits.copy(maxReverse = 0)) shouldBe ReorgRefusal.Deep
      model.planned(head, model.layered(head, "up"), limits.copy(maxReverse = 0)).depth shouldBe 0
    }

  "A record that is gone though it was not pruned" should "be this datadir's corruption, and never a deep reorganisation" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(20)
      import forks.*
      val limits = prunedBelow(12)
      val honest = model.records(below(3).hash)
      val target = forkAt(6)
      model.planned(head, target, limits).depth shouldBe 6

      model.records.remove(below(3).hash)
      model.refused(head, target, limits) shouldBe ReorgRefusal.Corrupt
      // a move that ends above it does not meet it
      model.planned(head, forkAt(3), limits).depth shouldBe 3
      // the lowest block that keeps its record is not below the mark: its record gone is corruption too
      val lowest = below(11)
      Some(lowest.number) shouldBe limits.prunedTo
      val kept = model.records(lowest.hash)
      val deepest = forkAt(12)
      model.records(below(3).hash) = honest
      model.planned(head, deepest, limits).depth shouldBe 12
      model.records.remove(lowest.hash)
      model.refused(head, deepest, limits) shouldBe ReorgRefusal.Corrupt
      model.records(lowest.hash) = kept
      model.records.remove(below(3).hash)

      val header = honest.toOption.get
      Seq(
        Left(BlockDiffCodec.Rejected(BlockDiffCodec.Rejection.BadCrc, "a bit flipped")),
        Right(header.copy(number = header.number - 1)),
        Right(header.copy(postStateRoot = hashOf("another state")))
      ).foreach { broken =>
        withClue(s"$broken: ") {
          model.records(below(3).hash) = broken
          model.refused(head, target, limits) shouldBe ReorgRefusal.Corrupt
        }
      }
      model.records(below(3).hash) = honest
      model.planned(head, target, limits).depth shouldBe 6
    }

  "A block that changed more than a record holds" should "not be reversed" taggedAs (UnitTest, ConsensusTest) in {
    val forks = new Forks(20)
    import forks.*
    val record = model.records(below(3).hash).toOption.get
    model.records(below(3).hash) = Right(record.copy(incomplete = true))
    model.refused(head, forkAt(4), model.open) shouldBe ReorgRefusal.Incomplete
    model.refused(head, below(10), model.open) shouldBe ReorgRefusal.Incomplete
    // the tombstone's own state can be moved to: the block is not reversed, and its record is not read
    model.planned(head, forkAt(3), model.open).depth shouldBe 3
    model.planned(head, below(3), model.open).depth shouldBe 3
  }

  "The block the state began at" should "be a fork point, and never be reversed" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(30)
      import forks.*
      // a state that a sync left at block 20: the blocks above it were committed in place and have records
      val pivot = ours(19)
      ours.take(20).foreach(block => model.records.remove(block.hash))
      val limits = model.open.copy(prunedTo = Some(pivot.number + 1), origin = Some(pivot))
      head.number - pivot.number shouldBe 10

      model.planned(head, forkAt(10), limits).fork shouldBe pivot
      model.planned(head, pivot, limits).depth shouldBe 10
      model.refused(head, forkAt(11), limits) shouldBe ReorgRefusal.Pivot
      model.refused(head, below(15), limits) shouldBe ReorgRefusal.Pivot
      // it is said before a configured depth is: nothing was ever below it, however shallow
      model.refused(head, forkAt(11), limits.copy(maxReverse = 10)) shouldBe ReorgRefusal.Pivot
      // a datadir that does not say where its state began (it was made before that was kept): the records end there
      model.refused(head, forkAt(11), limits.copy(origin = None)) shouldBe ReorgRefusal.Deep
    }

  "The finalized block" should "be a fork point, and never be reversed: the one refusal that syncing again does not answer" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(200)
      import forks.*
      // a consensus client's finalized block lies some ninety blocks behind its head
      val finalized = below(90)
      val limits = model.open.copy(
        finalized = Some(FinalizedBlock(finalized.number, finalized.hash)),
        maxReverse = 90 + 16
      )
      model.planned(head, forkAt(64), limits).depth shouldBe 64
      model.planned(head, forkAt(90), limits).fork shouldBe finalized
      model.planned(head, finalized, limits).depth shouldBe 90
      model.refused(head, forkAt(91), limits) shouldBe ReorgRefusal.Finalized
      model.refused(head, below(120), limits) shouldBe ReorgRefusal.Finalized

      // it is said first: before the block the state began at, before a depth, and whatever its record is
      val everything = limits.copy(maxReverse = 90, origin = Some(finalized), prunedTo = Some(finalized.number + 1))
      model.records.remove(finalized.hash)
      model.refused(head, forkAt(91), everything) shouldBe ReorgRefusal.Finalized
      model.refused(head, forkAt(91), everything.copy(prunedTo = Some(1))) shouldBe ReorgRefusal.Finalized
      // a depth that ends above the finalized block is said as that: the walk never came to it. (Whether a head
      // descends from the finalized block is asked of its headers before a move is planned: C4d.)
      model.refused(head, forkAt(91), limits.copy(maxReverse = 50)) shouldBe ReorgRefusal.Deep
    }

  it should "be told from a block of another branch at its height: the way back to the finalized chain is not refused" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(200)
      import forks.*
      // the block this node holds at the finalized height is not the one that was finalized: the node's head is on a
      // branch the consensus client left, and a move off it reverses that block
      val elsewhere = Some(FinalizedBlock(below(90).number, hashOf("the finalized block, on another branch")))
      val limits = model.open.copy(finalized = elsewhere)
      model.planned(head, forkAt(91), limits).depth shouldBe 91
      model.planned(head, below(120), limits).depth shouldBe 120
      // and a finalized block above the head forbids nothing below it
      val ahead = Some(FinalizedBlock(head.number + 30, hashOf("a block this node has not reached")))
      model.planned(head, forkAt(5), model.open.copy(finalized = ahead)).depth shouldBe 5
    }

  "How far above the head a target lies" should "not turn a move that is too deep into one that is not held" taggedAs
    (UnitTest, ConsensusTest) in {
      val forks = new Forks(40)
      import forks.*
      // a branch as long as the one it replaces: every header of it is walked beside a block reversed
      val limits = prunedBelow(8).copy(maxForward = 2)
      val level = model.chain(below(8), 8, "level")(model.layered)
      model.planned(head, level.last, limits).depth shouldBe 8
      val tooDeep = model.chain(below(12), 12, "deep")(model.layered)
      model.refused(head, tooDeep.last, limits) shouldBe ReorgRefusal.Deep
      // two above the head, and three
      val higher = model.chain(level.last, 3, "higher")(model.layered)
      model.planned(head, higher(1), limits).depth shouldBe 8
      model.notHeld(head, higher(2), limits) shouldBe None
    }

  "The store" should "refuse by what retention left, by the depth it is configured with and by the finalized block it is told" taggedAs
    (UnitTest, ConsensusTest) in {
      var finalized = Option.empty[FinalizedBlock]
      var maxDepth = Int.MaxValue
      var policyAsked = Vector.empty[(BigInt, Option[BigInt])]
      var finalizedAsked = 0
      val settings = CommitSettings(
        retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 6),
        finalized = () =>
          finalizedAsked += 1
          finalized
        ,
        maxReorgDepth = (anchor, finalizedNumber) =>
          policyAsked :+= (anchor, finalizedNumber)
          maxDepth
      )
      val chain = new Chain(settings)
      val links = mutable.Map.empty[ByteString, Link]
      links(chain.first.hash) = Link(chain.first, hashOf("block 0"))
      val canon = (1 to 14).foldLeft(Vector(chain.first)) { (done, n) =>
        val next = chain.on(done.last)(slots(alice, n -> (n + 1)))
        next.inPlace()
        links(next.ref.hash) = Link(next.ref, next.parent.hash)
        done :+ next.ref
      }
      val head = canon.last
      def below(depth: Int): BlockRef = canon(canon.size - 1 - depth)
      def plan(target: BlockRef): Outcome = chain.state.planMove(target, links.get)
      def depthOf(outcome: Outcome): Int = outcome match
        case Outcome.Planned(plan) => plan.depth
        case other                 => fail(s"not planned: $other")
      def reasonOf(outcome: Outcome): ReorgRefusal = outcome match
        case Outcome.Refused(reason, _) => reason
        case other                      => fail(s"not refused: $other")

      // six blocks keep their record, the head among them
      chain.appState.getChangesetPrunedTo() shouldBe Some(head.number - 6 + 1)
      depthOf(plan(below(5))) shouldBe 5
      depthOf(plan(below(6))) shouldBe 6
      reasonOf(plan(below(7))) shouldBe ReorgRefusal.Deep

      maxDepth = 3
      depthOf(plan(below(3))) shouldBe 3
      reasonOf(plan(below(4))) shouldBe ReorgRefusal.Deep
      maxDepth = Int.MaxValue

      finalized = Some(FinalizedBlock(below(4).number, below(4).hash))
      depthOf(plan(below(4))) shouldBe 4
      // the finalized block is asked for once for a plan, and the one answer is what the depth is taken from and what
      // is not reversed
      policyAsked = Vector.empty
      finalizedAsked = 0
      reasonOf(plan(below(5))) shouldBe ReorgRefusal.Finalized
      policyAsked shouldBe Vector((head.number, Some(below(4).number)))
      finalizedAsked shouldBe 1
      finalized = None

      // a record deleted behind retention's back
      chain.source.update(
        Seq(
          DataSourceUpdateOptimized(
            Namespaces.StateChangesetNamespace,
            toRemove = Seq(ChangesetStorage.recordKey(below(2).hash)),
            toUpsert = Nil
          )
        )
      )
      depthOf(plan(below(2))) shouldBe 2
      reasonOf(plan(below(3))) shouldBe ReorgRefusal.Corrupt
    }

  it should "refuse to go below the block its state began at, where the datadir says which that is" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = new Chain()
      val links = mutable.Map.empty[ByteString, Link]
      val zero = BlockRef(hashOf("block 0"), 0, hashOf("the state of block 0"))
      links(zero.hash) = Link(zero, ByteString(new Array[Byte](32)))
      links(chain.first.hash) = Link(chain.first, zero.hash)
      val next = chain.on(chain.first)(slots(alice, 1 -> 2))
      next.inPlace()
      links(next.ref.hash) = Link(next.ref, next.parent.hash)
      // another block on block 0: a move to it would reverse the block this datadir's state began at
      val sibling = BlockRef(hashOf("a sibling of block 1"), 1, hashOf("its state"))
      links(sibling.hash) = Link(sibling, zero.hash)
      def reason(): ReorgRefusal = chain.state.planMove(sibling, links.get) match
        case Outcome.Refused(reason, _) => reason
        case other                      => fail(s"not refused: $other")

      // this datadir does not say: the records end above that block, and that is all that is known
      chain.appState.getStateOrigin() shouldBe None
      reason() shouldBe ReorgRefusal.Deep
      chain.appState.putStateOrigin(chain.first).commit()
      reason() shouldBe ReorgRefusal.Pivot
    }

  it should "refuse to reverse a block whose record is a tombstone" taggedAs (UnitTest, ConsensusTest) in {
    // every record is above the bound, and is written with no entries
    val chain = new Chain(CommitSettings(maxRecordBytes = 1L))
    val links = mutable.Map.empty[ByteString, Link]
    links(chain.first.hash) = Link(chain.first, hashOf("block 0"))
    val next = chain.on(chain.first)(slots(alice, 1 -> 2))
    next.inPlace().tombstone should not be empty
    links(next.ref.hash) = Link(next.ref, next.parent.hash)
    chain.state.planMove(chain.first, links.get) match
      case Outcome.Refused(reason, _) => reason shouldBe ReorgRefusal.Incomplete
      case other                      => fail(s"not refused: $other")
  }

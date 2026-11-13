package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4b part 1 (T136): the plan of a move of the head ([[HeadMovePlan]]). The reverse set is the chain
  * of the anchor's records, the forward set the chain of the target's headers, and the two meet by hash. Nothing here
  * can ask the index of canonical blocks by number, or a chain weight: a plan is given neither.
  */
class HeadMovePlanSpec extends AnyFlatSpec with Matchers with LayerChains with PlannedMoves:

  import HeadMovePlan.*
  import PathStateTestKit.*

  "The plan of a move to the block the disk holds" should "move nothing, and ask for nothing" taggedAs UnitTest in {
    val model = new Model
    val head = model.chain(model.genesis, 3, "a")(model.recorded).last
    val plan = model.planned(head, head)
    plan.isNoMove shouldBe true
    plan.fork shouldBe head
    plan.chunks(256) shouldBe empty
    plan.newRecords shouldBe empty
    model.asked shouldBe empty
  }

  "The plan of a move up" should "apply the executed blocks kept on the anchor, oldest first, and write a record for each" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val head = model.chain(model.genesis, 3, "a")(model.recorded).last

      val one = model.layered(head, "b1")
      val byOne = model.planned(head, one)
      byOne.reverse shouldBe empty
      byOne.fork shouldBe head
      byOne.forward shouldBe Vector(Forwarded(one, head, Source.Layer(model.layers(one))))
      byOne.newRecords shouldBe Vector(one)

      val more = one +: model.chain(one, 4, "b")(model.layered)
      val byFive = model.planned(head, more.last)
      byFive.reverse shouldBe empty
      byFive.forward.map(_.block) shouldBe more
      byFive.forward.foreach { step =>
        step.source match
          case Source.Layer(layer) => (layer should be).theSameInstanceAs(model.layers(step.block))
          case other               => fail(s"block ${step.block.show} is applied from $other")
      }
      byFive.newRecords shouldBe more
      // the records of the anchor's own chain are not read to go up from it
      model.asked.collect { case ("record", hash) => hash }.toSet shouldBe more.map(_.hash).toSet
    }

  it should "say how far the blocks are held when one on the way is not, and that nothing leads there when the first is not" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val head = model.chain(model.genesis, 3, "a")(model.recorded).last
      val first = model.chain(head, 2, "b")(model.layered)
      val gap = model.header(first.last, "gap")
      val above = model.chain(gap, 2, "c")(model.layered)

      model.notHeld(head, above.last) shouldBe Some(first.last)
      model.notHeld(head, gap) shouldBe Some(first.last)
      // up to the block below the gap there is a plan
      model.planned(head, first.last).forward.map(_.block) shouldBe first

      // the first block above the anchor is the one that is missing: the held part is the anchor itself
      val unheld = model.header(head, "unheld")
      model.notHeld(head, model.layered(unheld, "on-unheld")) shouldBe Some(head)

      // a layer of that block that was executed on another parent is no layer of it on this chain
      val stray = model.header(first.last, "stray")
      model.layers(stray) = model.layerOf(head, stray)
      model.notHeld(head, stray) shouldBe Some(first.last)
    }

  "The plan of a move to a sibling" should "reverse down to the highest block the two chains share, by its hash, and apply from there" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val ours = model.chain(model.genesis, 6, "a")(model.recorded)
      val head = ours.last

      // one back, one up
      val sibling = model.layered(ours(4), "s")
      val short = model.planned(head, sibling)
      short.fork shouldBe ours(4)
      short.reverse shouldBe Vector(Reversed(head, ours(4)))
      short.forward shouldBe Vector(Forwarded(sibling, ours(4), Source.Layer(model.layers(sibling))))
      short.depth shouldBe 1

      // a fork two below the head with a branch of 300 blocks
      val long = model.chain(ours(3), 300, "l")(model.layered)
      val deep = model.planned(head, long.last)
      deep.fork shouldBe ours(3)
      deep.reverse.map(_.block) shouldBe Vector(ours(5), ours(4))
      deep.forward.map(_.block) shouldBe long
      deep.newRecords shouldBe long

      // a branch that is shorter than the one it replaces: more is reversed than applied
      val fewer = model.chain(ours(0), 2, "f")(model.layered)
      val shorter = model.planned(head, fewer.last)
      shorter.fork shouldBe ours(0)
      shorter.reverse.map(_.block) shouldBe ours.drop(1).reverse
      shorter.forward.map(_.block) shouldBe fewer
      fewer.last.number should be < head.number

      // a target below the anchor that is no ancestor of it
      val low = model.layered(ours(1), "low")
      val lower = model.planned(head, low)
      lower.fork shouldBe ours(1)
      lower.reverse.map(_.block) shouldBe ours.drop(2).reverse
      lower.forward.map(_.block) shouldBe Vector(low)

      // the fork's own record is not read, nor any below it
      model.asked.clear()
      model.planned(head, sibling)
      model.asked.collect { case ("record", hash) => hash }.toSet shouldBe Set(head.hash, sibling.hash)
    }

  it should "reach a fork at the genesis, and refuse a chain that shares no block with this node's" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val ours = model.chain(model.genesis, 3, "a")(model.recorded)
      val theirs = model.chain(model.genesis, 2, "t")(model.layered)
      val plan = model.planned(ours.last, theirs.last)
      plan.fork shouldBe model.genesis
      plan.reverse.map(_.block) shouldBe ours.reverse
      plan.forward.map(_.block) shouldBe theirs
      // the genesis has no record, and none is asked for
      model.asked.collect { case ("record", hash) => hash } should not contain model.genesis.hash

      // another genesis: the walk comes down to block 0 on both sides with no block shared
      val other = BlockRef(hashOf("another genesis"), 0, hashOf("another state"))
      model.links(other.hash) = Link(other, ByteString(new Array[Byte](32)))
      val foreign = model.chain(other, 3, "x")(model.layered)
      model.refused(ours.last, foreign.last) shouldBe ReorgRefusal.Pivot
      model.refused(model.genesis, foreign.last) shouldBe ReorgRefusal.Pivot
      // and on a datadir that does not say where its state began: nothing is below block 0
      model.refused(ours.last, foreign.last, model.open.copy(origin = None)) shouldBe ReorgRefusal.Pivot
    }

  "The plan of a move down" should "reverse to an ancestor and apply nothing" taggedAs (UnitTest, ConsensusTest) in {
    val model = new Model
    val ours = model.chain(model.genesis, 6, "a")(model.recorded)
    val plan = model.planned(ours.last, ours(2))
    plan.fork shouldBe ours(2)
    plan.forward shouldBe empty
    plan.reverse shouldBe Vector(
      Reversed(ours(5), ours(4)),
      Reversed(ours(4), ours(3)),
      Reversed(ours(3), ours(2))
    )
    plan.newRecords shouldBe empty
    // nothing of the target is asked for but its header
    model.asked.collect { case ("layer", hash) => hash } shouldBe empty
  }

  "A block that was applied before and reversed since" should "be applied from its record, which is preferred to a layer" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val base = model.chain(model.genesis, 2, "base")(model.recorded).last
      // two branches of which each was the head once: both have records at every height
      val a = model.chain(base, 3, "a")(model.recorded)
      val b = model.chain(base, 3, "b")(model.recorded)

      val back = model.planned(b.last, a.last)
      back.fork shouldBe base
      back.reverse.map(_.block) shouldBe b.reverse
      back.forward.map(_.block) shouldBe a
      bySource(back) shouldBe Vector("record", "record", "record")
      back.newRecords shouldBe empty

      // records, and then blocks executed on top of them since
      val onA = model.chain(a.last, 2, "a+")(model.layered)
      val mixed = model.planned(b.last, onA.last)
      bySource(mixed) shouldBe Vector("record", "record", "record", "layer", "layer")
      mixed.newRecords shouldBe onA

      // a block that has both: its record is exact, and needs no settling
      model.layers(a(1)) = model.layerOf(a(0), a(1))
      bySource(model.planned(b.last, a.last)) shouldBe Vector("record", "record", "record")
    }

  it should "be applied from its layer where its record is a tombstone, is not its own or does not lead from its parent, and not be held without one" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val base = model.chain(model.genesis, 2, "base")(model.recorded).last
      val b = model.chain(base, 2, "b")(model.recorded)
      val a1 = model.recorded(base, "a1")

      def with2nd(record: Option[Either[BlockDiffCodec.Rejected, BlockDiffCodec.Header]]): BlockRef =
        val a2 = model.header(a1, s"a2-${model.links.size}")
        record.foreach(model.records(a2.hash) = _)
        a2
      val honest = (ref: BlockRef) => model.recordOf(a1, ref)
      val cases: Seq[(String, BlockRef => Either[BlockDiffCodec.Rejected, BlockDiffCodec.Header])] = Seq(
        "a tombstone" -> (ref => Right(honest(ref).copy(incomplete = true))),
        "of another number" -> (ref => Right(honest(ref).copy(number = ref.number + 1))),
        "of another state root" -> (ref => Right(honest(ref).copy(postStateRoot = hashOf("other")))),
        "on another parent" -> (ref => Right(honest(ref).copy(parentHash = b(0).hash))),
        "on another state" -> (ref => Right(honest(ref).copy(preStateRoot = hashOf("other")))),
        "unreadable" -> (_ => Left(BlockDiffCodec.Rejected(BlockDiffCodec.Rejection.BadCrc, "a bit flipped")))
      )
      cases.foreach { (what, record) =>
        withClue(s"a record that is $what: ") {
          val a2 = with2nd(None)
          model.records(a2.hash) = record(a2)
          // no layer: the block is not held, and what is held ends below it
          model.notHeld(b.last, a2) shouldBe Some(a1)
          // with a layer it is applied from that, and a record is written for it
          model.layers(a2) = model.layerOf(a1, a2)
          val plan = model.planned(b.last, a2)
          bySource(plan) shouldBe Vector("record", "layer")
          plan.newRecords shouldBe Vector(a2)
        }
      }
    }

  "Two blocks at one height that both have a record" should "be told apart by their hash" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val base = model.chain(model.genesis, 2, "base")(model.recorded).last
      val left = model.recorded(base, "left")
      val right = model.recorded(base, "right")
      left.number shouldBe right.number
      val plan = model.planned(left, right)
      plan.reverse shouldBe Vector(Reversed(left, base))
      plan.forward shouldBe Vector(Forwarded(right, base, Source.Record))
      val there = model.planned(right, left)
      there.reverse shouldBe Vector(Reversed(right, base))
      there.forward shouldBe Vector(Forwarded(left, base, Source.Record))
    }

  "A plan" should "be the same for the same anchor, target and sources" taggedAs UnitTest in {
    val model = new Model
    val ours = model.chain(model.genesis, 5, "a")(model.recorded)
    val theirs = model.chain(ours(1), 4, "t")(model.layered)
    val first = model.planned(ours.last, theirs.last)
    val asks = model.asked.toVector
    model.asked.clear()
    model.planned(ours.last, theirs.last) shouldBe first
    model.asked.toVector shouldBe asks
  }

  "The target's chain" should "be followed by its headers alone, and not be held where one is missing, is not its block's, or is too far" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val ours = model.chain(model.genesis, 4, "a")(model.recorded)
      val theirs = model.chain(ours(1), 6, "t")(model.layered)
      model.planned(ours.last, theirs.last).forward should have size 6

      // the target's own header
      val unknown = BlockRef(hashOf("unknown"), 9, hashOf("unknown state"))
      model.notHeld(ours.last, unknown) shouldBe None
      // a header that says another state root than the one asked for
      model.notHeld(ours.last, theirs.last.copy(stateRoot = hashOf("other"))) shouldBe None
      model.notHeld(ours.last, theirs.last.copy(number = theirs.last.number + 1)) shouldBe None

      // a header missing on the way down
      val kept = model.links(theirs(2).hash)
      model.links.remove(theirs(2).hash)
      model.notHeld(ours.last, theirs.last) shouldBe None
      // one that is of another height than the block below its child
      model.links(theirs(2).hash) = kept.copy(block = kept.block.copy(number = kept.block.number + 1))
      model.notHeld(ours.last, theirs.last) shouldBe None
      // one stored under a hash that is not its own: a block of the right height on another branch, which leads to
      // this node's chain too
      val elsewhere = model.chain(ours(1), 3, "e")(model.layered)
      elsewhere(2).number shouldBe theirs(2).number
      model.links(theirs(2).hash) = model.links(elsewhere(2).hash)
      model.notHeld(ours.last, theirs.last) shouldBe None
      model.links(theirs(2).hash) = kept
      model.planned(ours.last, theirs.last).forward should have size 6

      // as far above the anchor as a target may lie, and one block further: then no header is asked for
      (theirs.last.number - ours.last.number) shouldBe 4
      model.planned(ours.last, theirs.last, model.open.copy(maxForward = 4)).forward should have size 6
      model.asked.clear()
      model.notHeld(ours.last, theirs.last, model.open.copy(maxForward = 3)) shouldBe None
      model.asked shouldBe empty
      // it bounds nothing at or below the anchor's height: there each header walked is a block reversed, and what
      // bounds a reverse bounds the walk
      val level = model.chain(ours(0), 3, "level")(model.layered)
      level.last.number shouldBe ours.last.number
      model.planned(ours.last, level.last, model.open.copy(maxForward = 0)).depth shouldBe 3
      model.planned(ours.last, ours(0), model.open.copy(maxForward = 0)).depth shouldBe 3
    }

  "The block the records lead to" should "be the block its header says, or the records are corrupt" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val ours = model.chain(model.genesis, 4, "a")(model.recorded)
      val sibling = model.layered(ours(2), "s")
      model.planned(ours.last, sibling).fork shouldBe ours(2)
      // the record above the fork names its parent's state root wrongly
      model.records(ours(3).hash) = Right(model.recordOf(ours(2), ours(3)).copy(preStateRoot = hashOf("other")))
      model.refused(ours.last, sibling) shouldBe ReorgRefusal.Corrupt
    }

  "A move in chunks" should "end each on a block of the way from the anchor down to the fork and up to the target" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val ours = model.chain(model.genesis, 5, "a")(model.recorded)
      val theirs = model.chain(ours(1), 4, "t")(model.layered)
      val plan = model.planned(ours.last, theirs.last)
      plan.reverse should have size 3
      plan.forward should have size 4
      val way = plan.reverse.map(_.parent) ++ plan.forward.map(_.block)

      (1 to 8).foreach { size =>
        withClue(s"chunks of $size: ") {
          val chunks = plan.chunks(size)
          chunks.map(c => c.reverse.size + c.forward.size).sum shouldBe 7
          chunks.foreach(c => (c.reverse.size + c.forward.size) should (be > 0).and(be <= size))
          chunks.flatMap(_.reverse) shouldBe plan.reverse
          chunks.flatMap(_.forward) shouldBe plan.forward
          chunks.head.from shouldBe ours.last
          chunks.last.head shouldBe theirs.last
          chunks.map(_.from) shouldBe (ours.last +: chunks.map(_.head)).dropRight(1)
          chunks.foreach(c => way should contain(c.head))
          chunks should have size ((7 + size - 1) / size)
        }
      }
      // a chunk that ends exactly at the fork, and one that spans it
      plan.chunks(3).head.head shouldBe plan.fork
      val spanning = plan.chunks(2)(1)
      (spanning.reverse.size, spanning.forward.size) shouldBe (1, 1)
      spanning.head shouldBe theirs(0)
      // one batch when it fits
      plan.chunks(7) shouldBe Vector(Chunk(ours.last, theirs.last, plan.reverse, plan.forward))
      an[IllegalArgumentException] should be thrownBy plan.chunks(0)
    }

  it should "be cut by what its blocks are worth as well, a block that is worth more than a batch going alone" taggedAs
    (UnitTest, ConsensusTest) in {
      val model = new Model
      val ours = model.chain(model.genesis, 5, "a")(model.recorded)
      val theirs = model.chain(ours(1), 4, "t")(model.layered)
      val plan = model.planned(ours.last, theirs.last)
      // the reversed blocks are worth 10 each, the applied ones 30, and the second applied one 500
      val worth: Either[Reversed, Forwarded] => Long = {
        case Left(_)                                => 10L
        case Right(step) if step.block == theirs(1) => 500L
        case Right(_)                               => 30L
      }
      val chunks = plan.chunks(256, 60, worth)
      chunks.map(c => (c.reverse.size, c.forward.size)) shouldBe Vector((3, 1), (0, 1), (0, 2))
      chunks.map(_.head) shouldBe Vector(theirs(0), theirs(1), theirs(3))
      chunks.flatMap(_.reverse) shouldBe plan.reverse
      chunks.flatMap(_.forward) shouldBe plan.forward
      // the count bounds as well
      plan.chunks(2, 60, worth).map(c => (c.reverse.size, c.forward.size)) shouldBe
        Vector((2, 0), (1, 1), (0, 1), (0, 2))
      // and a batch at the bound itself is not cut
      plan.chunks(256, 1000, worth) should have size 1
      plan.chunks(256, 619, worth) should have size 2
    }

  "The store's own plan" should "be of the anchor and the epoch it was made at, from the records and the layers it keeps" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = new Chain()
      val links = mutable.Map.empty[ByteString, Link]
      links(chain.first.hash) = Link(chain.first, hashOf("block 0"))
      def note(block: chain.Executed): chain.Executed =
        links(block.ref.hash) = Link(block.ref, block.parent.hash)
        block
      def plan(target: BlockRef): Outcome = chain.state.planMove(target, links.get)

      // three blocks in place, and two executed on the head
      val canon = (1 to 3).foldLeft(Vector(chain.first)) { (done, n) =>
        val next = note(chain.on(done.last)(slots(alice, n -> (n + 1))))
        next.inPlace()
        done :+ next.ref
      }
      val head = canon.last
      val l1 = note(chain.on(head)(account(dave, 1, 1)))
      val layer1 = l1.layered()
      val l2 = note(chain.on(l1.ref)(account(dave, 2, 2)))
      val layer2 = l2.layered()

      val epoch = chain.state.clock.epoch
      val written = chain.everything
      plan(l2.ref) match
        case Outcome.Planned(up) =>
          up.anchor shouldBe head
          up.epoch shouldBe epoch
          up.fork shouldBe head
          up.reverse shouldBe empty
          up.forward.map(_.block) shouldBe Vector(l1.ref, l2.ref)
          up.forward.map(_.source) match
            case Vector(Source.Layer(one), Source.Layer(two)) =>
              (one should be).theSameInstanceAs(layer1)
              (two should be).theSameInstanceAs(layer2)
            case other => fail(s"applied from $other")
        case other => fail(s"not planned: $other")

      // a sibling of the head, executed on the block behind it
      val sibling = note(chain.onAny(canon(2))(account(eve, 1, 1)))
      sibling.layered()
      plan(sibling.ref) match
        case Outcome.Planned(across) =>
          across.fork shouldBe canon(2)
          across.reverse shouldBe Vector(Reversed(head, canon(2)))
          across.forward.map(_.block) shouldBe Vector(sibling.ref)
        case other => fail(s"not planned: $other")

      // down to the first block this datadir committed on: every block above it has a record
      plan(chain.first) match
        case Outcome.Planned(down) =>
          down.reverse.map(_.block) shouldBe canon.drop(1).reverse
          down.forward shouldBe empty
        case other => fail(s"not planned: $other")

      // planning changed nothing
      chain.everything shouldBe written
      chain.state.anchor shouldBe Some(head)
      chain.state.clock.epoch shouldBe epoch
      chain.state.forwardLayer(l2.ref.hash) should not be empty
    }

  it should "make its plan again when a block was committed while it was made, and give none out that the state moved under" taggedAs
    (UnitTest, ConsensusTest) in {
      val chain = new Chain()
      val links = mutable.Map.empty[ByteString, Link]
      links(chain.first.hash) = Link(chain.first, hashOf("block 0"))
      def executed(parent: BlockRef, n: Int): chain.Executed =
        val block = chain.on(parent)(slots(alice, n -> (n + 1)))
        links(block.ref.hash) = Link(block.ref, block.parent.hash)
        block
      val second = executed(chain.first, 1)
      second.inPlace()
      val third = executed(second.ref, 2)

      // a commit lands between the walk and the plan's being given out: the plan down to the first block is then of
      // the block that was committed, and reverses it too
      var commits = List(third)
      val replanned = chain.state.planMoveAfter(
        chain.first,
        links.get,
        () =>
          commits.headOption.foreach(_.inPlace())
          commits = commits.drop(1)
      )
      replanned match
        case Outcome.Planned(plan) =>
          plan.anchor shouldBe third.ref
          plan.epoch shouldBe chain.state.clock.epoch
          plan.reverse.map(_.block) shouldBe Vector(third.ref, second.ref)
        case other => fail(s"not planned: $other")

      // a state that moves under every plan: none is given out, and nothing is said of the records
      var head = third.ref
      var n = 3
      var walks = 0
      val never = chain.state.planMoveAfter(
        chain.first,
        links.get,
        () =>
          walks += 1
          val next = executed(head, n)
          next.inPlace()
          head = next.ref
          n += 1
      )
      never match
        case Outcome.NotHeld(detail, None) => detail should include("moved under every plan")
        case other                         => fail(s"a plan the state moved under was given out: $other")
      walks shouldBe 4
    }

  it should "plan a move up whatever its bounds are set to: they are added without wrapping round" taggedAs UnitTest in {
    val chain = new Chain(PathStateStorage.CommitSettings(maxReverseHops = Int.MaxValue))
    val next = chain.on(chain.first)(slots(alice, 1 -> 2))
    next.layered()
    val links = Map(
      chain.first.hash -> Link(chain.first, hashOf("block 0")),
      next.ref.hash -> Link(next.ref, next.parent.hash)
    )
    chain.state.planMove(next.ref, links.get) match
      case Outcome.Planned(plan) => plan.forward.map(_.block) shouldBe Vector(next.ref)
      case other                 => fail(s"not planned: $other")
  }

  it should "plan nothing where there is no state" taggedAs UnitTest in {
    val source = EphemDataSource()
    val empty = new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source))
    val target = block(4, EmptyRoot)
    empty.planMove(target, _ => fail("no header is asked for")) match
      case Outcome.NotHeld(detail, None) => detail should include("no state anchor")
      case other                         => fail(s"planned without a state: $other")
  }

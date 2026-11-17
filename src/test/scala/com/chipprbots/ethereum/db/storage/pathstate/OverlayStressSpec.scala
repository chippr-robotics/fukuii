package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 3 (T127): readers, commits in place, promotions, blocks kept beside the disk and branches
  * under execution, ten thousand steps of them in an order a seed decides. '''Every read that succeeds is right, and
  * every failure is a fault of the state with a name''': a view that went stale, a state that is not held, a branch
  * that is too large. Never a wrong node, a missing one, or an inconsistency of the layers.
  *
  * One thread, no sleep, the same run for the same seed. What a second thread would do in the middle of a write is done
  * by the write itself: the data source stops before and after its batch, and readers read there.
  *
  * A hash store is given every block too, and says what each state is. An execution here runs on both and holds the two
  * state roots to each other, so a block executed through layers and records is checked as it is made.
  */
class OverlayStressSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*

  final private class Stepped extends EphemDataSource(Map.empty):
    var during: () => Unit = () => ()
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      during()
      super.update(dataSourceUpdates)
      during()

  final private class Run(seed: Long):
    val random = new Random(seed)
    val source = new Stepped
    val chain = new Chain(
      CommitSettings(
        // a small overlay, so that its bounds are met all the time
        maxForwardLayers = 6,
        maxForwardBytes = 40000L,
        maxLayerBytes = 5000L,
        pinnedMaxBytes = 20000L,
        // and few records, so that forks lose their fork point
        retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 12)
      ),
      maxViewAgeMoves = 4,
      source = source
    )

    /** The anchor's chain, oldest first, and the blocks executed lately that may be kept as layers. */
    var canonical: Vector[BlockRef] = Vector(chain.first)
    var executed: Vector[BlockRef] = Vector.empty
    val readers: mutable.ArrayBuffer[(BlockRef, StateView, MptStorage)] = mutable.ArrayBuffer.empty

    val counts: mutable.Map[String, Int] = mutable.Map.empty[String, Int].withDefaultValue(0)
    private def count(what: String): Unit = counts(what) += 1

    /** What went wrong inside a write, where a failure thrown would be taken for the write's own. */
    val insideAWrite: mutable.ArrayBuffer[Throwable] = mutable.ArrayBuffer.empty

    private def anchor: BlockRef = chain.state.anchor.get
    private def layers: Vector[BlockRef] = executed.filter(ref => chain.state.forwardLayer(ref.hash).isDefined)
    private def pick[A](from: Vector[A]): A = from(random.nextInt(from.size))

    /** `body`, or nothing when it ends in a fault of the state that has a name and is no inconsistency. */
    private def typed[A](what: String)(body: => A): Option[A] =
      try Some(body)
      catch
        case fault: StateFault if !fault.isInstanceOf[LayerInconsistent] =>
          count(s"$what: ${fault.getClass.getSimpleName}")
          None

    private val positionsByRoot = mutable.Map.empty[ByteString, Vector[(Option[ByteString], ByteString, ByteString)]]
    private def positionsOf(ref: BlockRef): Vector[(Option[ByteString], ByteString, ByteString)] =
      positionsByRoot.getOrElseUpdate(
        ref.stateRoot,
        RawTrieWalk
          .statePositions(ref.stateRoot.toArray, chain.hashed.nodes.load)
          .iterator
          .flatMap((trie, rows) => rows.iterator.map((hp, node) => (trie, hp, node)))
          .toVector
      )

    private val rowsByRoot = mutable.Map.empty[ByteString, Map[PositionKey, ByteString]]
    private def rowsOf(ref: BlockRef): Map[PositionKey, ByteString] =
      rowsByRoot.getOrElseUpdate(
        ref.stateRoot,
        positionsOf(ref).iterator.map((trie, hp, node) => PositionKey(trie, hp) -> node).toMap
      )

    /** A block's transactions: always something of its own, so that no two blocks are one. */
    private def transactions(step: Int): Seq[Tx] =
      account(eve, step % 100 + 1, step) +: (random.nextInt(5) match
        case 0 => Seq(slots(alice, random.nextInt(60) -> random.nextInt(1000)))
        case 1 => Seq(slots(bob, (1 to 3).map(_ => random.nextInt(60) -> random.nextInt(50))*))
        case 2 => Seq(delete(bob))
        case 3 => Seq(slots(carol, random.nextInt(80) -> random.nextInt(3)), delete(crowd(random.nextInt(crowd.size))))
        case _ => Seq(delete(bob), slots(bob, random.nextInt(60) -> (1 + random.nextInt(9))))
      )

    private def execute(parent: BlockRef, step: Int): Option[chain.Executed] =
      typed("an execution")(chain.onAny(parent)(transactions(step)*)).map { block =>
        executed = (executed :+ block.ref).takeRight(48)
        block
      }

    /** Some nodes of the state a reader is of: each is the node that state has there, or the read fails by name. And
      * some rows, at positions of its state and of another: a view holds at each what its state holds there, which is
      * nothing where another state has a row and its own has none.
      */
    private def read(index: Int, nodes: Int): Unit =
      val (ref, view, storage) = readers(index)
      val positions = positionsOf(ref)
      val read = typed("a read") {
        (1 to nodes).foreach { _ =>
          val (trie, hp, node) = pick(positions)
          val position = PositionKey(trie, hp)
          withClue(s"seed $seed, the state of ${ref.show}, ${position.show}: ") {
            ByteString(storage.forOwner(trie).getAt(position.path, kec256(node.toArray)).encode) shouldBe node
          }
          count("nodes read")
        }
        val other = positionsOf(pick(canonical.takeRight(8) ++ executed.takeRight(8)))
        (1 to nodes / 2).foreach { _ =>
          val (trie, hp, _) = pick(other)
          val position = PositionKey(trie, hp)
          withClue(s"seed $seed, the state of ${ref.show}, the row at ${position.show}: ") {
            view.readFrom(position)._1.map(ByteString(_)) shouldBe rowsOf(ref).get(position)
          }
          count("rows read")
        }
      }
      // a view that failed is of no more use: a caller makes another
      if read.isEmpty then readers.remove(index)

    private def readSome(): Unit = if readers.nonEmpty then read(random.nextInt(readers.size), nodes = 8)

    private def open(ref: BlockRef): Unit =
      typed("a view")(chain.state.viewOf(ref)).foreach { view =>
        if readers.size >= 16 then readers.remove(random.nextInt(readers.size))
        readers += ((ref, view, PathMptStorage.readOnly(view)))
        count("views made")
      }

    /** No layer is kept that nothing leads to: each has a view, while the disk is at rest. A layer that had none would
      * hold its bytes for nothing, and the blocks kept on it with it.
      */
    def everyLayerLeadsSomewhere(): Unit =
      layers.foreach { ref =>
        try chain.state.viewOf(ref)
        catch
          case fault: StateFault =>
            fail(
              s"seed $seed: block ${ref.show} is kept as a layer, and no view can be made of it: ${fault.getMessage}"
            )
      }

    /** A block committed in place on the anchor, with readers reading before and after its batch is written. */
    private def commitInPlace(block: chain.Executed, readersInside: Boolean): Unit =
      if readersInside then
        source.during = () =>
          try (1 to 3).foreach(_ => readSome())
          catch case failure: Throwable => insideAWrite += failure
      val outcome =
        try
          typed("a commit in place")(
            chain.state.commitCanonical(Some(block.staged), block.ref, chain.pointer(block.ref))
          )
        finally source.during = () => ()
      outcome.foreach {
        case CommitOutcome.Applied(_) =>
          canonical :+= block.ref
          count(if readersInside then "commits in place, read during" else "commits in place")
        case other => fail(s"seed $seed: block ${block.ref.show} on the anchor was not applied: $other")
      }

    private def keep(block: chain.Executed, pin: Option[BranchPin]): Unit =
      typed("a block kept")(chain.state.commitOverlay(block.staged, block.ref, pin)).foreach {
        case OverlayOutcome.Kept(_)    => count(if pin.isDefined then "kept, pinned" else "kept")
        case OverlayOutcome.NotKept(_) => count("not kept")
        case OverlayOutcome.AlreadyApplied | OverlayOutcome.BehindTheAnchor => count("kept: of the anchor's chain")
      }

    private def promoteOne(): Unit =
      layers.filter(ref => chain.state.forwardLayer(ref.hash).exists(_.parent == anchor)) match
        case onAnchor if onAnchor.nonEmpty =>
          val ref = pick(onAnchor)
          typed("a promotion")(chain.state.promote(ref, chain.pointer(ref))).foreach {
            case CommitOutcome.Applied(_) =>
              canonical :+= ref
              count("promotions")
            case other => fail(s"seed $seed: the layer of ${ref.show} on the anchor was not promoted: $other")
          }
        case _ => ()

    /** A block the anchor's records may lead back to, or one they no longer do. */
    private def behind: BlockRef = canonical(math.max(0, canonical.size - 1 - random.nextInt(16)))

    private def someParent: BlockRef =
      random.nextInt(10) match
        case n if n < 3 => anchor
        // the layer last kept, so that chains grow longer than the overlay keeps
        case n if n < 6 && layers.nonEmpty => layers.last
        case n if n < 8 && layers.nonEmpty => pick(layers)
        case _                             => behind

    def step(n: Int): Unit =
      random.nextInt(100) match
        case k if k < 12 =>
          execute(anchor, n).foreach(commitInPlace(_, readersInside = false))
        case k if k < 18 =>
          execute(anchor, n).foreach(commitInPlace(_, readersInside = true))
        case k if k < 36 =>
          execute(someParent, n).foreach(keep(_, None))
        case k if k < 41 =>
          // a branch under execution: some blocks in a row, each on the one before, and its first adopted or not
          typed("a branch")(chain.state.withPinnedBranch(s"branch at step $n") { pin =>
            val start = someParent
            var parent = start
            var going = true
            var held = Vector.empty[BlockRef]
            (1 to 2 + random.nextInt(3)).foreach { i =>
              if going then
                execute(parent, n * 10 + i) match
                  case Some(block) =>
                    keep(block, Some(pin))
                    going = chain.state.forwardLayer(block.ref.hash).isDefined
                    if going then held :+= block.ref
                    parent = block.ref
                    // and other blocks arrive meanwhile, held by nothing
                    if random.nextBoolean() then execute(someParent, n * 10 + 5 + i).foreach(keep(_, None))
                    // what the branch holds stays while it is under execution, whatever else is kept and evicted
                    held.foreach { ref =>
                      withClue(s"seed $seed, the pinned layer of ${ref.show}: ") {
                        chain.state.forwardLayer(ref.hash) should not be empty
                      }
                    }
                  case None => going = false
            }
            if random.nextBoolean() then promoteOne()
          })
          count("branches")
        case k if k < 49 => promoteOne()
        case k if k < 59 =>
          open(random.nextInt(4) match
            case 0 => anchor
            case 1 => behind
            case _ => if layers.nonEmpty then pick(layers) else anchor
          )
        case k if k < 62 =>
          if layers.nonEmpty then
            chain.state.dropLayer(pick(layers).hash)
            count("layers dropped")
        case _ => readSome()

  "The overlay" should "read right or fail by name, whatever order readers, commits, promotions, kept blocks and branches come in" taggedAs
    (UnitTest, ConsensusTest) in {
      val seed = 0x18c4a3L
      val run = new Run(seed)
      (1 to 10000).foreach { n =>
        run.step(n)
        if n % 25 == 0 then run.everyLayerLeadsSomewhere()
      }

      withClue(s"seed $seed: ") {
        run.insideAWrite shouldBe empty
        run.chain.state.lock.holder shouldBe None

        // what the run was made of: every kind of thing happened, and often
        val counts = run.counts.toMap.withDefaultValue(0)
        info(counts.toSeq.sorted.map((what, times) => s"$what: $times").mkString("; "))
        counts("nodes read") should be > 20000
        counts("rows read") should be > 10000
        Seq(
          "commits in place",
          "commits in place, read during",
          "promotions",
          "kept",
          "kept, pinned",
          "not kept",
          "branches",
          "views made",
          "layers dropped"
        ).foreach(what => withClue(s"$what: ")(counts(what) should be > 20))
        run.chain.state.overlayUsage.evictions should be > 100L
        // the faults that were met have these names and no other
        val faults = counts.keySet.filter(_.contains(": ")).map(_.split(": ").last)
        faults should contain("StaleStateViewException")
        faults should contain("StateUnavailable")
        faults should contain("BranchTooLarge")
        (faults -- Set("StaleStateViewException", "StateUnavailable", "BranchTooLarge")) shouldBe empty

        // and at the end the disk is the state of its anchor, row for row, and reads as it
        val anchor = run.chain.state.anchor.get
        anchor shouldBe run.canonical.last
        run.chain.disk shouldBe run.chain.rowsOf(anchor.stateRoot)
        run.chain.readThrough(run.chain.state.readOnly(anchor), anchor)
      }
    }

package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.locks.ReentrantReadWriteLock

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 3 (T125): the one ordering the isolation of a view rests on (data-model 6.3.3). A writer
  * says a move is in flight, with what every row it writes held before, '''before''' its batch is written, and says so
  * until the batch has returned. So a reader that meets changed bytes, and then reads the epoch, finds the move that
  * changed them.
  *
  * The Java memory model gives no happens-before across the native write: the argument rests on the store's own
  * synchronisation, which is documented and not proved. What is held here is the order a writer keeps, and what a
  * reader of every kind reads at each step of it. The steps are made by a data source that stops before and after its
  * write: one thread, no sleep, and the same interleaving every run.
  */
class EpochOrderingSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateTestKit.*

  /** An in-memory source that lets a test stand before and after each write. (A subclass and not a wrapper: the
    * storages ask a source whether it is the in-memory one.)
    */
  final private class Stepped extends EphemDataSource(Map.empty):
    var before: () => Unit = () => ()
    var after: () => Unit = () => ()
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      before()
      super.update(dataSourceUpdates)
      after()

  /** Three blocks in place, and what stands around the last of them: a block kept as a layer on it, a block kept on a
    * fork from the one before, and readers of every kind, made while the disk is at rest.
    */
  final private class Rig:
    val source = new Stepped
    val chain = new Chain(source = source)
    val refs: Vector[BlockRef] = (1 to 3).foldLeft(Vector(chain.first)) { (done, n) =>
      val next = chain.on(done.last)(slots(alice, n -> (n + 1)), account(dave, n, n))
      next.inPlace()
      done :+ next.ref
    }
    val head: BlockRef = refs.last
    // replaces a storage trie and writes another: rows are deleted, changed and made by the move
    val next: chain.Executed = chain.on(head)(delete(bob), slots(carol, 3 -> 0, 77 -> 7), account(dave, 9, 9))
    val sibling: chain.Executed = chain.on(head)(slots(bob, 1 -> 0, 50 -> 5))
    sibling.layered()
    val fork: chain.Executed = chain.onAny(refs(2))(slots(bob, 2 -> 0), account(dave, 8, 8))
    fork.layered()

    /** A reader of the state of each block, by the kind of its view. */
    val readers: Seq[(String, BlockRef, MptStorage)] = Seq(
      ("the anchor's view", head, PathMptStorage.readOnly(chain.state.view(head))),
      ("a view through a layer", sibling.ref, PathMptStorage.readOnly(chain.state.view(sibling.ref))),
      ("a view behind the anchor", refs(1), PathMptStorage.readOnly(chain.state.viewOf(refs(1)))),
      ("a view of a fork", fork.ref, PathMptStorage.readOnly(chain.state.viewOf(fork.ref)))
    )

    def allRead(step: String): Unit =
      readers.foreach((kind, ref, storage) => withClue(s"$step, $kind: ")(chain.readThrough(storage, ref)))

    /** Runs `move`, which writes one batch, standing before and after the write. */
    def stepped(move: => Any)(before: => Unit, after: => Unit): Unit =
      var (stoodBefore, stoodAfter) = (0, 0)
      source.before = () =>
        stoodBefore += 1
        before
      source.after = () =>
        stoodAfter += 1
        after
      try move
      finally
        source.before = () => ()
        source.after = () => ()
      (stoodBefore, stoodAfter) shouldBe (1, 1)

  private def movesOf(rig: Rig, from: Long, to: Long): Seq[StateMove] =
    rig.chain.state.clock.movesBetween(from, to).getOrElse(fail("the log of moves does not reach back"))

  /** The writer's half: `move` commits `block`. */
  private def writerKeepsTheOrder(rig: Rig, block: BlockRef)(move: => Any): Unit =
    import rig.*
    val clock = chain.state.clock
    val epoch = clock.epoch
    val rowsBefore = chain.disk
    epoch % 2 shouldBe 0

    stepped(move)(
      before =
        // the move is in flight, and in the log, and nothing is written
        clock.epoch shouldBe epoch + 1
        clock.atRest shouldBe None
        clock.anchor shouldBe Some(head)
        movesOf(rig, epoch, epoch + 1).map(_.newAnchor) shouldBe Seq(block)
        chain.disk shouldBe rowsBefore
      ,
      after =
        // the batch has returned, and the move is in flight still: a reader that looks now finds it
        clock.epoch shouldBe epoch + 1
        clock.atRest shouldBe None
        chain.disk should not be rowsBefore
        // it says of every row the batch wrote what that row held before, and of no other row a thing that is not so
        val pre = movesOf(rig, epoch, epoch + 1).head.pre
        val (accountsBefore, storageBefore) = rowsBefore
        val (accountsNow, storageNow) = chain.disk
        def changed(before: Map[ByteString, ByteString], now: Map[ByteString, ByteString]): Set[ByteString] =
          (before.keySet ++ now.keySet).filter(key => before.get(key) != now.get(key))
        val written =
          changed(accountsBefore, accountsNow).map(hp => PositionKey(None, hp)) ++
            changed(storageBefore, storageNow).map { key =>
              PositionKey(Some(key.take(PositionKey.OwnerLength)), key.drop(PositionKey.OwnerLength))
            }
        written should not be empty
        written.foreach { position =>
          val was = position.owner.fold(accountsBefore.get(position.hp))(o => storageBefore.get(o ++ position.hp))
          withClue(s"${position.show}: ")(pre.get(position) shouldBe Some(was))
        }
        pre.foreach { (position, held) =>
          val was = position.owner.fold(accountsBefore.get(position.hp))(o => storageBefore.get(o ++ position.hp))
          withClue(s"${position.show}: ")(held shouldBe was)
        }
    )
    clock.epoch shouldBe epoch + 2
    clock.atRest shouldBe Some((epoch + 2, Some(block)))

  "A writer" should "say a move is in flight, with what its rows held, before its batch is written and until it has returned" taggedAs
    (UnitTest, ConsensusTest) in {
      val committed = new Rig
      writerKeepsTheOrder(committed, committed.next.ref)(committed.next.inPlace())

      val promoted = new Rig
      promoted.next.layered()
      writerKeepsTheOrder(promoted, promoted.next.ref)(promoted.chain.promote(promoted.next.ref))
    }

  "A reader" should "read its state at every step of a move: before the batch, after it while the clock is not settled, and after" taggedAs
    (UnitTest, ConsensusTest) in {
      def acrossAMove(rig: Rig)(move: => Any): Unit =
        rig.allRead("before the move")
        rig.stepped(move)(
          before = rig.allRead("the move is said, the batch is not written"),
          after = rig.allRead("the batch is written, the clock is not settled")
        )
        rig.allRead("after the move")
        // and views made now read the new anchor's state, and the others' as before
        import rig.*
        chain.readThrough(chain.state.readOnly(next.ref), next.ref)
        chain.readThrough(PathMptStorage.readOnly(chain.state.viewOf(head)), head)
        chain.readThrough(PathMptStorage.readOnly(chain.state.viewOf(sibling.ref)), sibling.ref)
        chain.readThrough(PathMptStorage.readOnly(chain.state.viewOf(fork.ref)), fork.ref)

      val committed = new Rig
      acrossAMove(committed)(committed.next.inPlace())

      val promoted = new Rig
      promoted.next.layered()
      acrossAMove(promoted)(promoted.chain.promote(promoted.next.ref))
    }

  it should "not be made while a batch is being written: the disk is then no block's state" taggedAs UnitTest in {
    val rig = new Rig
    import rig.*
    stepped(next.inPlace())(
      before = chain.state.clock.atRest shouldBe None,
      after = chain.state.clock.atRest shouldBe None
    )
    // made after, it is of an even epoch, and the anchor's
    val view = chain.state.view(next.ref)
    view.createdAt % 2 shouldBe 0
    view.isAnchorViewNow shouldBe true
  }

  // The fallback of data-model 6.3.3: every in-place batch excludes every reader by a reader-writer lock, and no reader
  // ever sees a move. A view asks its clock three things and nothing else, so the fallback is another clock: one class.
  "The fallback" should "be another clock under the same view: a reader that excludes the writer reads the rows as they are" taggedAs
    UnitTest in {
      val chain = new Chain()
      val gate = new ReentrantReadWriteLock()

      /** The clock of a store whose writers exclude its readers: no move is ever seen in flight, or after the fact. */
      val excluded = new StateClock:
        def epoch: Long = 0L
        def anchor: Option[BlockRef] = chain.state.anchor
        def movesBetween(from: Long, to: Long): Option[Seq[StateMove]] = Some(Nil)

      def reading[A](body: => A): A =
        gate.readLock().lock()
        try body
        finally gate.readLock().unlock()

      reading {
        val view = new StateView(chain.first, excluded.epoch, excluded, chain.rows)
        chain.readThrough(PathMptStorage.readOnly(view), chain.first)
        view.isAnchorViewNow shouldBe true
        // and no writer comes in meanwhile
        gate.writeLock().tryLock() shouldBe false
      }
      gate.writeLock().tryLock() shouldBe true
      try
        val next = chain.on(chain.first)(slots(alice, 1 -> 5))
        next.inPlace()
        // a reader that starts after the writer left reads the new anchor's rows, through a view of its own
        val view = new StateView(next.ref, excluded.epoch, excluded, chain.rows)
        chain.readThrough(PathMptStorage.readOnly(view), next.ref)
      finally gate.writeLock().unlock()
    }

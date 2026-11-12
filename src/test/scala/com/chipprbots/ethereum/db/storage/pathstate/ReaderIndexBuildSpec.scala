package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.util.Try

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource.*
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 4: what a reader of a state behind the head may cost the node. A reader is anyone who can
  * reach it: a peer that is served a state, a caller of the JSON-RPC.
  *
  *   - The look at the blocks behind the anchor reads a record for each of them. A reader makes it with no lock held,
  *     and the view is made again when the disk moved meanwhile.
  *   - An index is built once for those who ask for it together.
  *   - Readers build few indexes at once, and a reader over that is told to ask again. An execution is not held to it.
  *   - What readers keep costs an execution none of its own.
  *
  * The threads here are stopped and let go by latches inside the data source's read: no sleep, and the same
  * interleaving every run.
  */
class ReaderIndexBuildSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*

  /** An in-memory source that tells of every read of a changeset record. (A subclass and not a wrapper: the storages
    * ask a source whether it is the in-memory one.)
    */
  final private class Watched extends EphemDataSource(Map.empty):
    @volatile var onRecordRead: () => Unit = () => ()
    override def get(namespace: Namespace, key: Key): Option[Value] =
      if namespace == Namespaces.StateChangesetNamespace then onRecordRead()
      super.get(namespace, key)

  /** What the store tells of its indexes and its views. */
  final private class Counting extends CommitObserver:
    val built = new AtomicInteger(0)
    val refused = new AtomicInteger(0)
    val retried = new AtomicInteger(0)
    override def indexBuilt(bytes: Long): Unit = built.incrementAndGet()
    override def indexRefused(): Unit = refused.incrementAndGet()
    override def viewRetried(): Unit = retried.incrementAndGet()

  /** `blocks` blocks committed in place and no layer kept: so no look at the blocks behind the anchor is made yet, as
    * after a start.
    */
  final private class Rig(blocks: Int = 8, settings: CommitSettings => CommitSettings = identity):
    val source = new Watched
    val told = new Counting
    val chain = new Chain(settings(CommitSettings(observer = told)), source = source)
    val refs: Vector[BlockRef] = (1 to blocks).foldLeft(Vector(chain.first)) { (done, n) =>
      val next = chain.on(done.last)(slots(alice, n -> (n + 1), (n + 40) -> n), account(dave, n, n))
      next.inPlace()
      done :+ next.ref
    }
    val head: BlockRef = refs.last

    /** The record reads `body` makes. */
    def recordReads(body: => Any): Int =
      val reads = new AtomicInteger(0)
      source.onRecordRead = () => reads.incrementAndGet()
      try body
      finally source.onRecordRead = () => ()
      reads.get

    /** `body` on a thread of its own, named `name`; what it gave, or threw, once it has ended. */
    def onThread[A](name: String)(body: => A): (Thread, AtomicReference[Option[Try[A]]]) =
      val result = new AtomicReference[Option[Try[A]]](None)
      val thread = new Thread(() => result.set(Some(Try(body))), name)
      thread.setDaemon(true)
      thread.start()
      (thread, result)

    def ended[A](run: (Thread, AtomicReference[Option[Try[A]]])): A =
      run._1.join(60000)
      run._1.isAlive shouldBe false
      run._2.get.getOrElse(fail(s"${run._1.getName} gave nothing")).get

  "The first reader of a state behind the anchor" should "look at the blocks behind the anchor with the state lock held by no one" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      var reads = 0
      // a writer that comes while the records are read gets the lock: this thread does not hold it (the lock refuses
      // its own holder), and neither does another
      source.onRecordRead = () =>
        chain.state.lock.holder shouldBe None
        chain.state.lock.locked("a writer that comes meanwhile")(reads += 1)
      val storage =
        try chain.state.readOnlyBehind(refs(3))
        finally source.onRecordRead = () => ()
      // the headers of the records behind the anchor, and the records between the anchor and the block
      reads should be >= refs.size - 1
      chain.readThrough(storage, refs(3))
      // the look is kept: a view of the anchor reads no record
      recordReads(chain.state.viewOf(head)) shouldBe 0
      told.retried.get shouldBe 0
    }

  it should "have its view made again when the disk moves while it looks, and read its block's state then" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      val next = chain.on(head)(slots(alice, 3 -> 0, 90 -> 9), delete(bob))
      var moved = false
      // the next block is committed in place inside the reader's first read of a record
      source.onRecordRead = () =>
        if !moved then
          moved = true
          next.inPlace()
      val storage =
        try chain.state.readOnlyBehind(refs(3))
        finally source.onRecordRead = () => ()
      moved shouldBe true
      chain.state.anchor shouldBe Some(next.ref)
      told.retried.get shouldBe 1
      // no index was built for the view that was not to be: one, for the view that was made
      told.built.get shouldBe 1
      // the view is of the new anchor's epoch, and reads the block's state through the records up to the new anchor
      chain.readThrough(storage, refs(3))
      // and the look that is kept is the new anchor's
      recordReads(chain.state.viewOf(next.ref)) shouldBe 0
      chain.readThrough(chain.state.readOnlyBehind(head), head)
    }

  "Readers that ask for the state of one block together" should "have one index built for them" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      // the look at the blocks behind the anchor is made, and one index
      chain.state.readOnlyBehind(refs(7))
      told.built.get shouldBe 1

      val inTheBuild = new CountDownLatch(1)
      val letGo = new CountDownLatch(1)
      val firstRead = new AtomicBoolean(true)
      source.onRecordRead = () =>
        if firstRead.compareAndSet(true, false) then
          inTheBuild.countDown()
          letGo.await()
      val one = onThread("the reader that builds")(chain.state.readOnlyBehind(refs(2)))
      inTheBuild.await()
      val other = onThread("the reader that waits")(chain.state.readOnlyBehind(refs(2)))
      // the second waits at the index that is being built: it does not build one, and it is not refused
      while other._1.getState != Thread.State.BLOCKED && other._1.isAlive do Thread.onSpinWait()
      other._1.isAlive shouldBe true
      told.built.get shouldBe 1
      chain.state.indexBuildsInFlight shouldBe 1
      letGo.countDown()
      val storages: Seq[MptStorage] = Seq(ended(one), ended(other))
      source.onRecordRead = () => ()

      told.built.get shouldBe 2
      told.refused.get shouldBe 0
      chain.state.indexBuildsInFlight shouldBe 0
      storages.foreach(chain.readThrough(_, refs(2)))
      // and who asks after reads no record
      recordReads(chain.readThrough(chain.state.readOnlyBehind(refs(2)), refs(2))) shouldBe 0
    }

  "Readers" should "build no more than two indexes at once: a third is told to ask again, and an execution is not held to it" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      chain.state.readOnlyBehind(refs(7))
      val inTheBuild = new CountDownLatch(2)
      val letGo = new CountDownLatch(1)
      source.onRecordRead = () =>
        if Thread.currentThread.getName.startsWith("held") then
          inTheBuild.countDown()
          letGo.await()
      val held = Seq(1, 2).map(n => onThread(s"held reader $n")(chain.state.readOnlyBehind(refs(n))))
      inTheBuild.await()
      chain.state.indexBuildsInFlight shouldBe 2

      // a third reader, of another block: refused, and told so in words a caller can act on
      val refusal = the[StateUnavailable] thrownBy chain.state.readOnlyBehind(refs(3))
      refusal.getMessage should include("Ask again")
      told.refused.get shouldBe 1
      // an execution on that block is not: what a peer or a caller asks for does not keep a block from being executed
      val built = told.built.get
      val executed = chain.onAny(refs(3))(slots(bob, 7 -> 7))
      executed.layered()
      told.built.get shouldBe built + 1
      told.refused.get shouldBe 1
      // and a reader of that block now finds the index there is: it builds none, so it is not refused
      chain.readThrough(chain.state.readOnlyBehind(refs(3)), refs(3))
      told.refused.get shouldBe 1
      // the same again of a block no one has an index of
      a[StateUnavailable] should be thrownBy chain.state.readOnlyBehind(refs(4))
      told.refused.get shouldBe 2

      letGo.countDown()
      held.zip(Seq(1, 2)).foreach((run, n) => chain.readThrough(ended(run), refs(n)))
      source.onRecordRead = () => ()
      // the two are done: a reader builds again
      chain.readThrough(chain.state.readOnlyBehind(refs(4)), refs(4))
      told.refused.get shouldBe 2
      // and nothing is left of the builds that were refused, or made
      chain.state.indexBuildsInFlight shouldBe 0
    }

  it should "be free to build again after a build that failed" taggedAs UnitTest in {
    // no index may hold a thing: every build fails
    val rig = new Rig(settings = _.copy(maxReverseIndexBytes = 1L))
    import rig.*
    (1 to 5).foreach { n =>
      val failure = the[StateUnavailable] thrownBy chain.state.readOnlyBehind(refs(n))
      (failure.getMessage should not).include("Ask again")
    }
    told.refused.get shouldBe 0
    told.built.get shouldBe 0
    chain.state.indexBuildsInFlight shouldBe 0
  }

  "What readers keep" should "cost an execution none of its indexes, however many blocks they read" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig(blocks = 10)
      import rig.*
      // a block is executed on a block behind the anchor: an index is built for it
      chain.onAny(refs(1))(slots(bob, 7 -> 7)).layered()
      told.built.get shouldBe 1
      // a sweep of reads over the blocks behind the head
      (2 to 9).foreach(n => chain.readThrough(chain.state.readOnlyBehind(refs(n)), refs(n)))
      told.built.get shouldBe 9
      // the readers keep four of theirs, and the execution's is there still: the next block of that branch builds none
      chain.state.overlayUsage.reverseIndexes shouldBe 5
      recordReads(chain.onAny(refs(1))(slots(bob, 8 -> 8))) shouldBe 0
      told.built.get shouldBe 9
      // and of the readers', the last four
      recordReads((6 to 9).foreach(n => chain.state.readOnlyBehind(refs(n)))) shouldBe 0
      recordReads(chain.state.readOnlyBehind(refs(5))) should be > 0
    }

  "How far behind the anchor a view reaches" should "be as far as the settings say, for a reader as for an execution" taggedAs
    UnitTest in {
      val rig = new Rig(settings = _.copy(maxReverseHops = 3))
      import rig.*
      chain.readThrough(chain.state.readOnlyBehind(refs(refs.size - 4)), refs(refs.size - 4))
      a[StateUnavailable] should be thrownBy chain.state.readOnlyBehind(refs(refs.size - 5))
      a[StateUnavailable] should be thrownBy chain.state.stageOf(refs(refs.size - 5))
    }

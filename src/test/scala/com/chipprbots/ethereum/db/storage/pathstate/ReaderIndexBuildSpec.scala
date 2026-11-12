package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 4: what a reader of a state behind the head may cost the node. A reader is anyone who can
  * reach it: a peer that is served a state, a caller of the JSON-RPC.
  *
  *   - The look at the blocks behind the anchor reads a record for each of them. A reader makes it with no lock held,
  *     and the view is made again when the disk moved meanwhile.
  *   - An index is built once for those who ask for it together: who waits is given it, or told why there is none.
  *   - The readers of one kind build few indexes at once, and a reader over that is told to ask again. Another kind of
  *     reader is not held to it, and neither is an execution.
  *   - What readers keep costs an execution none of its own.
  *
  * The threads here are stopped and let go by latches inside the data source's read: no sleep, and the same
  * interleaving every run.
  */
class ReaderIndexBuildSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*
  import RecordReads.onThread

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
    val source = new RecordReads.Watched
    val told = new Counting
    val chain = new Chain(settings(CommitSettings(observer = told)), source = source)
    val refs: Vector[BlockRef] = (1 to blocks).foldLeft(Vector(chain.first)) { (done, n) =>
      val next = chain.on(done.last)(slots(alice, n -> (n + 1), (n + 40) -> n), account(dave, n, n))
      next.inPlace()
      done :+ next.ref
    }
    val head: BlockRef = refs.last

    /** The state of `block`, behind the anchor, for a reader: a peer unless it is said to be another. */
    def behind(block: BlockRef, as: Reader = Reader.Peer): MptStorage = chain.state.readOnlyBehind(block, as)

    /** Stops the threads whose name starts with `named` inside their first read of a record, until they are let go. */
    final class Stop(named: String, threads: Int) extends RecordReads.Stopped(source, named, threads)

    /** Waits until `callers` wait for an index another is building, or `thread` has ended. */
    def awaited(callers: Int, thread: Thread): Unit =
      while chain.state.indexBuildsWaitedFor < callers && thread.isAlive do Thread.onSpinWait()
      chain.state.indexBuildsWaitedFor shouldBe callers

    /** The record reads `body` makes. */
    def recordReads(body: => Any): Int =
      val reads = new AtomicInteger(0)
      source.onRecordRead = () => reads.incrementAndGet()
      try body
      finally source.onRecordRead = () => ()
      reads.get

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
        try behind(refs(3))
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
        try behind(refs(3))
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
      chain.readThrough(behind(head), head)
    }

  "A reader whose index is built while the disk moves" should "have its view made again, and the index of the epoch that is past is not kept" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      // the look at the blocks behind the anchor is made, and one index
      behind(refs(7))
      val next = chain.on(head)(slots(alice, 3 -> 0, 90 -> 9), delete(bob))
      var moved = false
      // this time the first record that is read is read for the index
      source.onRecordRead = () =>
        if !moved then
          moved = true
          next.inPlace()
      val storage =
        try behind(refs(3))
        finally source.onRecordRead = () => ()
      chain.state.anchor shouldBe Some(next.ref)
      told.retried.get shouldBe 1
      // one index before, one built across the move and one for the view that was made
      told.built.get shouldBe 3
      chain.readThrough(storage, refs(3))
      // of the three the last is kept: the others are of an epoch no view is made of again
      chain.state.overlayUsage.reverseIndexes shouldBe 1
      recordReads(behind(refs(3))) shouldBe 0
    }

  "Readers that ask for the state of one block together" should "have one index built for them, kept or not" taggedAs
    (UnitTest, ConsensusTest) in {
      // with room for the index, and with none: who waited is given it all the same
      Seq[CommitSettings => CommitSettings](identity, _.copy(maxReversePoolBytes = 0L)).foreach { settings =>
        val rig = new Rig(settings = settings)
        import rig.*
        // the look at the blocks behind the anchor is made, and one index
        behind(refs(7))
        told.built.get shouldBe 1

        val stop = new Stop("the reader that builds", threads = 1)
        val one = onThread("the reader that builds")(behind(refs(2)))
        stop.allStopped()
        // a second reader, and then an execution on that block: they wait for the index that is being built, they
        // build none, and are not refused
        val other = onThread("the reader that waits")(behind(refs(2)))
        awaited(1, other.thread)
        val third = onThread("the execution that waits")(chain.state.viewOf(refs(2)))
        awaited(2, third.thread)
        told.built.get shouldBe 1
        chain.state.indexBuildsInFlight shouldBe 1
        stop.go()
        val storages: Seq[MptStorage] =
          Seq(one.ended.get, other.ended.get, PathMptStorage.readOnly(third.ended.get))
        stop.over()

        told.built.get shouldBe 2
        told.refused.get shouldBe 0
        chain.state.indexBuildsInFlight shouldBe 0
        chain.state.indexBuildsWaitedFor shouldBe 0
        // the two that waited read no record
        stop.readsOf("the reader that builds") should be > 0
        stop.readsOf("the reader that waits") shouldBe 0
        stop.readsOf("the execution that waits") shouldBe 0
        storages.foreach(chain.readThrough(_, refs(2)))
      }
    }

  it should "have an index a reader built be the execution's that waited for it: no reader pushes it out" taggedAs
    UnitTest in {
      val rig = new Rig(blocks = 10)
      import rig.*
      behind(refs(9))
      val stop = new Stop("the reader that builds", threads = 1)
      val one = onThread("the reader that builds")(behind(refs(1)))
      stop.allStopped()
      val execution = onThread("the execution that waits")(chain.state.viewOf(refs(1)))
      awaited(1, execution.thread)
      stop.go()
      one.ended.get
      execution.ended.get
      stop.over()
      // a sweep of reads over other blocks: the readers' few are all others now
      (2 to 8).foreach(n => behind(refs(n)))
      recordReads(chain.state.viewOf(refs(1))) shouldBe 0
    }

  it should "be told together why there is none, when the records do not give one" taggedAs UnitTest in {
    // no index may hold a thing: every build fails
    val rig = new Rig(settings = _.copy(maxReverseIndexBytes = 1L))
    import rig.*
    // the look at the blocks behind the anchor is made
    a[StateUnavailable] should be thrownBy behind(refs(7))
    val stop = new Stop("the reader that builds", threads = 1)
    val one = onThread("the reader that builds")(behind(refs(2)))
    stop.allStopped()
    val other = onThread("the reader that waits")(behind(refs(2)))
    awaited(1, other.thread)
    stop.go()
    val failures = Seq(one, other).map { run =>
      run.ended.failed.toOption.getOrElse(fail(s"${run.thread.getName} was given a state"))
    }
    stop.over()
    failures.foreach(_ shouldBe a[StateUnavailable])
    failures.map(_.getMessage).distinct.size shouldBe 1
    (failures.head.getMessage should not).include("Ask again")
    // the one that waited walked no record to be told so
    stop.readsOf("the reader that waits") shouldBe 0
    told.built.get shouldBe 0
    chain.state.indexBuildsInFlight shouldBe 0
  }

  "Readers of one kind" should "build no more than two indexes at once: a third is told to ask again, and neither another kind nor an execution is held to it" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      behind(refs(7))
      val stop = new Stop("held", threads = 2)
      val held = Seq(1, 2).map(n => onThread(s"held reader $n")(behind(refs(n))))
      stop.allStopped()
      chain.state.indexBuildsInFlight shouldBe 2

      // a third peer, of another block: refused, and told so in words a caller can act on
      val refusal = the[StateUnavailable] thrownBy behind(refs(3))
      refusal.getMessage should include("Ask again")
      refusal.askAgain shouldBe true
      told.refused.get shouldBe 1
      // one that asks for a block that is being built waits for it, and needs no leave: it is not refused
      val waits = onThread("the peer that waits")(behind(refs(1)))
      awaited(1, waits.thread)
      told.refused.get shouldBe 1
      // a caller of the JSON-RPC has its own two
      val built = told.built.get
      chain.readThrough(behind(refs(5), Reader.Rpc), refs(5))
      chain.readThrough(behind(refs(6), Reader.Rpc), refs(6))
      told.built.get shouldBe built + 2
      // and an execution is held to nothing: what a peer or a caller asks for does not keep a block from being executed
      val executed = chain.onAny(refs(3))(slots(bob, 7 -> 7))
      executed.layered()
      told.built.get shouldBe built + 3
      told.refused.get shouldBe 1
      // a peer that asks for that block now finds the index there is: it builds none, so it is not refused
      chain.readThrough(behind(refs(3)), refs(3))
      told.refused.get shouldBe 1
      // the same again of a block no one has an index of
      a[StateUnavailable] should be thrownBy behind(refs(4))
      told.refused.get shouldBe 2

      stop.go()
      held.zip(Seq(1, 2)).foreach((run, n) => chain.readThrough(run.ended.get, refs(n)))
      chain.readThrough(waits.ended.get, refs(1))
      stop.over()
      // the two are done: a peer builds again
      chain.readThrough(behind(refs(4)), refs(4))
      told.refused.get shouldBe 2
      // and nothing is left of the builds that were refused, or made
      chain.state.indexBuildsInFlight shouldBe 0
    }

  it should "be free to build again after a build that failed" taggedAs UnitTest in {
    // no index may hold a thing: every build fails
    val rig = new Rig(settings = _.copy(maxReverseIndexBytes = 1L))
    import rig.*
    (1 to 5).foreach { n =>
      val failure = the[StateUnavailable] thrownBy behind(refs(n))
      (failure.getMessage should not).include("Ask again")
      // asking again changes nothing
      failure.askAgain shouldBe false
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
      // a sweep of reads over the blocks behind the head, by readers of both kinds
      (2 to 9).foreach { n =>
        chain.readThrough(behind(refs(n), if n % 2 == 0 then Reader.Peer else Reader.Rpc), refs(n))
      }
      told.built.get shouldBe 9
      // the readers keep four of theirs, and the execution's is there still: the next block of that branch builds none
      chain.state.overlayUsage.reverseIndexes shouldBe 5
      recordReads(chain.onAny(refs(1))(slots(bob, 8 -> 8))) shouldBe 0
      told.built.get shouldBe 9
      // and of the readers', the last four
      recordReads((6 to 9).foreach(n => behind(refs(n)))) shouldBe 0
      recordReads(behind(refs(5))) should be > 0
    }

  "How far behind the anchor a view reaches" should "be as far as the settings say, for a reader as for an execution" taggedAs
    UnitTest in {
      val rig = new Rig(settings = _.copy(maxReverseHops = 3))
      import rig.*
      chain.readThrough(behind(refs(refs.size - 4)), refs(refs.size - 4))
      (the[StateUnavailable] thrownBy behind(refs(refs.size - 5))).askAgain shouldBe false
      a[StateUnavailable] should be thrownBy chain.state.stageOf(refs(refs.size - 5))
    }

  "A view" should "not be made by the holder of the state lock: it may wait for records to be read" taggedAs UnitTest in {
    val rig = new Rig
    import rig.*
    val refused = the[IllegalStateException] thrownBy chain.state.lock.locked("a commit")(chain.state.viewOf(refs(3)))
    refused.getMessage should include("a commit")
    an[IllegalStateException] should be thrownBy chain.state.lock.locked("a commit")(behind(refs(3)))
    told.built.get shouldBe 0
    chain.readThrough(behind(refs(3)), refs(3))
  }

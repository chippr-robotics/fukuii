package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 4: the reads of a state behind the head that are in flight are counted, and bounded.
  *
  * A state behind the block the disk holds is made from the changeset records, and what is made lives as long as
  * anything reads through it. So such a state is handed out only inside a [[ReadScope]], which says when its reader is
  * done, and to no more blocks' readers at once than the settings say. The state of the block the disk holds is handed
  * out as ever.
  */
class ReadScopeSpec extends AnyFlatSpec with Matchers with LayerChains:

  import PathStateStorage.*
  import PathStateTestKit.*
  import RecordReads.onThread

  final private class Rig(blocks: Int = 8, reads: Int = 2):
    val source = new RecordReads.Watched
    val refused = new AtomicInteger(0)
    private val observer = new CommitObserver:
      override def readRefused(): Unit = refused.incrementAndGet()
    val chain = new Chain(CommitSettings(observer = observer, maxReadsBehindHead = reads), source = source)
    val refs: Vector[BlockRef] = (1 to blocks).foldLeft(Vector(chain.first)) { (done, n) =>
      val next = chain.on(done.last)(slots(alice, n -> (n + 1), (n + 40) -> n), account(dave, n, n))
      next.inPlace()
      done :+ next.ref
    }
    val head: BlockRef = refs.last
    def state: PathStateStorage = chain.state
    def inFlight: Int = state.readsBehindInFlight

    /** The record reads `body` makes. */
    def recordReads(body: => Any): Int =
      val count = new AtomicInteger(0)
      source.onRecordRead = () => count.incrementAndGet()
      try body
      finally source.onRecordRead = () => ()
      count.get

  "The state of the block the disk holds" should "be handed out with no scope and no count, inside a scope or outside" taggedAs
    UnitTest in {
      val rig = new Rig
      import rig.*
      chain.readThrough(state.readOnlyInScope(head), head)
      chain.readThrough(state.throwawayInScope(head), head)
      ReadScope.run {
        chain.readThrough(state.readOnlyInScope(head), head)
        inFlight shouldBe 0
      }
      inFlight shouldBe 0
    }

  "The state of a block behind it" should "be handed out to no caller that is in no scope" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      ReadScope.isOpen shouldBe false
      Seq[BlockRef => Any](state.readOnlyInScope, state.throwawayInScope).foreach { door =>
        val refusal = the[StateUnavailable] thrownBy door(refs(3))
        // asking again changes nothing: this caller reads the head
        refusal.askAgain shouldBe false
      }
      // and it cost no walk of the records
      recordReads(a[StateUnavailable] should be thrownBy state.readOnlyInScope(refs(3))) shouldBe 0
      inFlight shouldBe 0
      refused.get shouldBe 0
    }

  it should "be read inside a scope, counted once for each block while the scope lasts, and no more when it ends" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      ReadScope.run {
        ReadScope.isOpen shouldBe true
        chain.readThrough(state.readOnlyInScope(refs(3)), refs(3))
        inFlight shouldBe 1
        // the same block again, by either door: the scope is counted for it already
        chain.readThrough(state.throwawayInScope(refs(3)), refs(3))
        chain.readThrough(state.readOnlyInScope(refs(3)), refs(3))
        inFlight shouldBe 1
        // another block is another count
        chain.readThrough(state.readOnlyInScope(refs(5)), refs(5))
        inFlight shouldBe 2
      }
      ReadScope.isOpen shouldBe false
      inFlight shouldBe 0
      // one after another, as many blocks as there are: each scope gives back what it took
      (1 to 7).foreach(n => ReadScope.run(chain.readThrough(state.readOnlyInScope(refs(n)), refs(n))))
      inFlight shouldBe 0
      refused.get shouldBe 0
    }

  it should "be given back when the scope ends by an exception, and with the outer scope when scopes are nested" taggedAs
    UnitTest in {
      val rig = new Rig
      import rig.*
      an[IllegalArgumentException] should be thrownBy ReadScope.run {
        state.readOnlyInScope(refs(3))
        inFlight shouldBe 1
        throw new IllegalArgumentException("the read failed")
      }
      inFlight shouldBe 0
      ReadScope.isOpen shouldBe false

      ReadScope.run {
        ReadScope.run {
          state.readOnlyInScope(refs(3))
          inFlight shouldBe 1
        }
        // the inner one was part of this one: what it took is held still, and the storage may be read through
        ReadScope.isOpen shouldBe true
        inFlight shouldBe 1
        state.readOnlyInScope(refs(3))
        inFlight shouldBe 1
      }
      inFlight shouldBe 0
    }

  it should "be refused to one reader more than may read at once, before any record is walked, and to be asked again" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig(reads = 2)
      import rig.*
      val reading = new CountDownLatch(2)
      val letGo = new CountDownLatch(1)
      val readers = Seq(2, 3).map { n =>
        onThread(s"a reader of block $n") {
          ReadScope.run {
            val storage = state.readOnlyInScope(refs(n))
            reading.countDown()
            letGo.await(60, TimeUnit.SECONDS)
            // read after the others came and went: through what this scope was handed
            chain.readThrough(storage, refs(n))
          }
        }
      }
      reading.await(60, TimeUnit.SECONDS) shouldBe true
      inFlight shouldBe 2

      ReadScope.run {
        val refusal = the[StateUnavailable] thrownBy state.readOnlyInScope(refs(4))
        refusal.askAgain shouldBe true
        refusal.getMessage should include("Ask again")
        recordReads(a[StateUnavailable] should be thrownBy state.throwawayInScope(refs(5))) shouldBe 0
        // the head's state is not a read behind it
        chain.readThrough(state.readOnlyInScope(head), head)
      }
      refused.get shouldBe 2
      inFlight shouldBe 2
      // a peer is not a caller: it is served, as an execution is executed
      chain.readThrough(state.readOnlyBehind(refs(4), Reader.Peer), refs(4))
      chain.onAny(refs(4))(slots(bob, 7 -> 7))

      letGo.countDown()
      readers.foreach(_.ended.get)
      inFlight shouldBe 0
      ReadScope.run(chain.readThrough(state.readOnlyInScope(refs(4)), refs(4)))
      refused.get shouldBe 2
    }

  it should "be counted when the disk moves on under a scope that read the block while the disk held it" taggedAs
    UnitTest in {
      val rig = new Rig
      import rig.*
      ReadScope.run {
        chain.readThrough(state.readOnlyInScope(head), head)
        inFlight shouldBe 0
        chain.on(head)(slots(alice, 3 -> 0)).inPlace()
        // the block is behind the disk now: a state of it is made from the records
        chain.readThrough(state.readOnlyInScope(head), head)
        inFlight shouldBe 1
      }
      inFlight shouldBe 0
    }

  "An execution that commits nothing, in a scope" should "run on the state of a block behind the head and write nothing" taggedAs
    (UnitTest, ConsensusTest) in {
      val rig = new Rig
      import rig.*
      val before = chain.everything
      val change: Tx = slots(alice, 3 -> 0, 77 -> 7).andThen(account(eve, 1, 1))
      val root = ReadScope.run {
        val world = worldOver(state.throwawayInScope(refs(2)), refs(2).stateRoot, chain.hashed.codes)
        InMemoryWorldStateProxy.persistState(change(world)).stateRootHash
      }
      root shouldBe chain.hashed.change(refs(2).stateRoot)(change)
      chain.everything shouldBe before
      state.overlayUsage.layers shouldBe 0
      // an executed block that is kept beside the chain is no block of it, in a scope or not
      val beside = chain.onAny(refs(2))(slots(bob, 7 -> 7))
      beside.layered()
      ReadScope.run {
        a[StateUnavailable] should be thrownBy state.readOnlyInScope(beside.ref)
        a[StateUnavailable] should be thrownBy state.throwawayInScope(beside.ref)
      }
      inFlight shouldBe 0
    }

  "A scope" should "be the thread's own: another thread is in none" taggedAs UnitTest in {
    val rig = new Rig
    import rig.*
    ReadScope.run {
      state.readOnlyInScope(refs(3))
      val other = onThread("another thread") {
        (ReadScope.isOpen, scala.util.Try(state.readOnlyInScope(refs(3))).isFailure)
      }
      other.ended.get shouldBe (false, true)
    }
  }

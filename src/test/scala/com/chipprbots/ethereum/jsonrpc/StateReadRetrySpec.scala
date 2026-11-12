package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b (T053): what a reader behind RPC does when the state moves under it, or is not held.
  *
  * A stale view is not an answer and not an error: the whole read runs again on a fresh view of the same block. A state
  * that is not held, and a read that stays stale, are the error a client already gets for a missing state node.
  */
class StateReadRetrySpec extends AnyFlatSpec with Matchers:

  private val block = BlockRef(ByteString(Array.fill[Byte](32)(7)), 9, ByteString(Array.fill[Byte](32)(1)))
  private def stale = new StaleStateViewException(block, createdAtEpoch = 2, epochNow = 6, "the disk moved on")

  /** States handed out one after another, and counted: each attempt has to get one of its own. */
  final private class States:
    var taken: Vector[MptStorage] = Vector.empty
    val next: () => MptStorage = () =>
      val state = StateStorage.getReadOnlyStorage(EphemDataSource())
      taken :+= state
      state

  "A read of a block's state behind RPC" should "run once on one state when nothing moves" taggedAs UnitTest in {
    val states = new States
    StateRead.retrying(states.next)(state => state eq states.taken.head) shouldBe Right(true)
    states.taken should have size 1
  }

  it should "run again, all of it, on a fresh state when the view it read went stale" taggedAs UnitTest in {
    val states = new States
    var runs = Vector.empty[MptStorage]
    val answer = StateRead.retrying(states.next) { state =>
      runs :+= state
      if runs.size < 3 then throw stale
      "answered on the third view"
    }
    answer shouldBe Right("answered on the third view")
    states.taken should have size 3
    states.taken.distinct should have size 3
    // each run read the state taken for it, and no other
    runs shouldBe states.taken
  }

  it should "give up after the views it is allowed, and say the state moved" taggedAs UnitTest in {
    val states = new States
    var runs = 0
    val answer = StateRead.retrying[String](states.next) { _ =>
      runs += 1
      throw stale
    }
    // the state moved under every view of it: an error that says to ask again, and no revert
    answer shouldBe Left(JsonRpcError.StateMoved)
    runs shouldBe StateRead.Attempts
    states.taken should have size StateRead.Attempts
  }

  it should "answer a state that is not held with that error at once, whether taking it or reading it says so" taggedAs
    UnitTest in {
      val states = new States
      var runs = 0
      StateRead.retrying[String](states.next) { _ =>
        runs += 1
        throw new StateUnavailable("block 9 is not the anchor")
      } shouldBe Left(JsonRpcError.StateNotHeld)
      runs shouldBe 1

      StateRead.retrying[String](() => throw new StateUnavailable("there is no state anchor"))(_ =>
        fail("there was no state to read")
      ) shouldBe Left(JsonRpcError.StateNotHeld)
    }

  it should "answer a state that is held and not handed out right now as one to ask for again, at once" taggedAs
    UnitTest in {
      val states = new States
      var runs = 0
      StateRead.retrying[String](states.next) { _ =>
        runs += 1
        throw new StateUnavailable("as many states behind the head as may be read at once are being read", true)
      } shouldBe Left(JsonRpcError.StateBusy)
      // not tried again here: the caller is told, and waits as long as it sees fit
      runs shouldBe 1
    }

  it should "tell a client three things apart, and none of them as an execution error" taggedAs UnitTest in {
    val errors = Seq(JsonRpcError.StateNotHeld, JsonRpcError.StateBusy, JsonRpcError.StateMoved)
    errors.distinct should have size 3
    // code 3 is what a client takes for a revert
    errors.map(_.code) shouldBe Seq(-32000, -32005, -32005)
    JsonRpcError.StateNotHeld.message should include("missing trie node")
    Seq(JsonRpcError.StateBusy, JsonRpcError.StateMoved).foreach(_.message should include("ask again"))
    (JsonRpcError.StateNotHeld.message should not).include("ask again")
  }

  it should "read inside a scope, which ends with the read" taggedAs UnitTest in {
    import com.chipprbots.ethereum.db.storage.pathstate.ReadScope
    val states = new States
    ReadScope.isOpen shouldBe false
    StateRead.retrying(states.next)(_ => ReadScope.isOpen) shouldBe Right(true)
    ReadScope.isOpen shouldBe false
    an[IllegalArgumentException] should be thrownBy StateRead.retrying[String](states.next) { _ =>
      throw new IllegalArgumentException("not about the state")
    }
    ReadScope.isOpen shouldBe false
    StateRead.scoped(ReadScope.isOpen) shouldBe true
  }

  // Each method keeps the answer it gives for a missing node today: some say NodeNotFound, one says LogicError.
  it should "leave a missing node, and any other failure, to the method that reads" taggedAs UnitTest in {
    val states = new States
    a[MissingNodeException] should be thrownBy StateRead.retrying[String](states.next) { _ =>
      throw new MissingNodeException(ByteString(1, 2, 3))
    }
    an[IllegalArgumentException] should be thrownBy StateRead.retrying[String](states.next) { _ =>
      throw new IllegalArgumentException("not about the state")
    }
    states.taken should have size 2
  }

  // eth_call, a simulation, a trace: a world is held across the whole execution, so the request is what runs again.
  "A request that executes on a block's state" should "run again from the start when the state moved under it" taggedAs
    UnitTest in {
      import cats.effect.unsafe.implicits.global
      var runs = 0
      val request = IO {
        runs += 1
        if runs < 3 then throw stale
        Right(s"answered on run $runs"): Either[JsonRpcError, String]
      }
      StateRead.request(request).unsafeRunSync() shouldBe Right("answered on run 3")
      runs shouldBe 3
    }

  it should "give up after the runs it is allowed, and answer a state that is not held at once" taggedAs UnitTest in {
    import cats.effect.unsafe.implicits.global
    var runs = 0
    val alwaysStale = IO[Either[JsonRpcError, String]] {
      runs += 1
      throw stale
    }
    StateRead.request(alwaysStale).unsafeRunSync() shouldBe Left(JsonRpcError.StateMoved)
    runs shouldBe StateRead.Attempts

    runs = 0
    val notHeld = IO[Either[JsonRpcError, String]] {
      runs += 1
      throw new StateUnavailable("block 9 is not the anchor")
    }
    StateRead.request(notHeld).unsafeRunSync() shouldBe Left(JsonRpcError.StateNotHeld)
    runs shouldBe 1
  }

  "The synchronous body of a method that executes on a state" should "be one scope, which no later stage of the IO is in" taggedAs
    UnitTest in {
      import cats.effect.unsafe.implicits.global
      import com.chipprbots.ethereum.db.storage.pathstate.ReadScope
      val stages =
        for
          inside <- StateRead.reading(ReadScope.isOpen)
          _ <- IO.cede
          after <- IO(ReadScope.isOpen)
        yield (inside, after)
      stages.unsafeRunSync() shouldBe (true, false)
      // and a body that throws leaves no scope on the thread it ran on
      StateRead
        .reading[Boolean](throw new IllegalArgumentException("not about the state"))
        .attempt
        .flatMap(_ => IO(ReadScope.isOpen))
        .unsafeRunSync() shouldBe false
    }

  it should "pass on its answer, an error it answers with, and any other failure, untouched and after one run" taggedAs
    UnitTest in {
      import cats.effect.unsafe.implicits.global
      var runs = 0
      def counted[A](answer: => Either[JsonRpcError, A]): IO[Either[JsonRpcError, A]] = IO { runs += 1; answer }

      StateRead.request(counted(Right(7))).unsafeRunSync() shouldBe Right(7)
      StateRead
        .request(counted(Left(JsonRpcError.InvalidParams("no"))))
        .unsafeRunSync() shouldBe Left(JsonRpcError.InvalidParams("no"))
      a[MissingNodeException] should be thrownBy StateRead
        .request(counted[Int](throw new MissingNodeException(ByteString(1, 2, 3))))
        .unsafeRunSync()
      runs shouldBe 3
    }

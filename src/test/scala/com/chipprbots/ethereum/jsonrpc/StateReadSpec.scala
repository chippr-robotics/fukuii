package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

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
class StateReadSpec extends AnyFlatSpec with Matchers:

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

  it should "give up after the views it is allowed, with the error for a missing state node" taggedAs UnitTest in {
    val states = new States
    var runs = 0
    val answer = StateRead.retrying[String](states.next) { _ =>
      runs += 1
      throw stale
    }
    answer shouldBe Left(JsonRpcError.NodeNotFound)
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
      } shouldBe Left(JsonRpcError.NodeNotFound)
      runs shouldBe 1

      StateRead.retrying[String](() => throw new StateUnavailable("there is no state anchor"))(_ =>
        fail("there was no state to read")
      ) shouldBe Left(JsonRpcError.NodeNotFound)
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

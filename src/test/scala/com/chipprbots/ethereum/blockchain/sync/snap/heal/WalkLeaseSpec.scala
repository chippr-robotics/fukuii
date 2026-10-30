package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.InMemoryBfsQueueStorage
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 slice 2 fix round 4 (hunter W1, forge W-r4-2, vault-r3 W1, beacon W1-r4): one walk at a time per BFS queue,
  * by a lease every coordinator over that queue shares.
  */
class WalkLeaseSpec extends AnyFlatSpec with Matchers:

  "WalkLease" should "be held by one walk at a time, and given back only by that walk's token" taggedAs UnitTest in {
    val lease = WalkLease.of(new InMemoryBfsQueueStorage())
    lease.held shouldBe false
    val first = lease.tryAcquire()
    first shouldBe defined
    lease.held shouldBe true
    lease.tryAcquire() shouldBe None // a second walk waits
    lease.release(first.get + 1) // not the holder's token: changes nothing
    lease.held shouldBe true
    lease.tryAcquire() shouldBe None
    lease.release(first.get)
    lease.held shouldBe false
    val second = lease.tryAcquire()
    second shouldBe defined
    second should not be first // a token is never reused
    lease.release(first.get) // a stale token, after the lease went to another walk
    lease.held shouldBe true
  }

  it should "be one lease per queue instance: the same for every coordinator over one queue, another for another queue" taggedAs UnitTest in {
    val queue = new InMemoryBfsQueueStorage()
    val other = new InMemoryBfsQueueStorage()
    (WalkLease.of(queue) eq WalkLease.of(queue)) shouldBe true
    (WalkLease.of(queue) eq WalkLease.of(other)) shouldBe false
    val walk = WalkLease.of(queue).tryAcquire()
    walk shouldBe defined
    WalkLease.of(queue).tryAcquire() shouldBe None
    WalkLease.of(other).tryAcquire() shouldBe defined // another queue's walks are not held up
    WalkLease.of(queue).release(walk.get)
  }

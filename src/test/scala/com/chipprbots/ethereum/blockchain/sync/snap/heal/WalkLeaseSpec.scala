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

  private def rocksShapedQueue(source: com.chipprbots.ethereum.db.dataSource.DataSource) =
    new com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage(
      source,
      com.chipprbots.ethereum.db.storage.Namespaces.BfsQueueNamespace
    )

  it should "be one lease per column family, process-wide: every queue wrapper over it, of any controller instance (T038a)" taggedAs UnitTest in {
    val source = com.chipprbots.ethereum.db.dataSource.EphemDataSource()
    val first = WalkLease.of(rocksShapedQueue(source))
    val second = WalkLease.of(rocksShapedQueue(source))
    (first eq second) shouldBe true
    val otherSource = com.chipprbots.ethereum.db.dataSource.EphemDataSource()
    (first eq WalkLease.of(rocksShapedQueue(otherSource))) shouldBe false
  }

  it should "wake those it refused when the walk gives it back, once, and not before (T038a)" taggedAs UnitTest in {
    val lease = WalkLease.of(new InMemoryBfsQueueStorage())
    val walk = lease.tryAcquire().get
    var woken = 0
    val wake: () => Unit = () => woken += 1
    lease.tryAcquire(Some(wake)) shouldBe None
    lease.tryAcquire(Some(wake)) shouldBe None // one waiter, however often it was refused
    woken shouldBe 0
    lease.release(walk + 1) // not the holder: nobody is woken
    woken shouldBe 0
    lease.release(walk)
    woken shouldBe 1
    lease.release(walk) // gone already
    woken shouldBe 1
    lease.tryAcquire(Some(wake)) shouldBe defined // a caller that gets the lease is not a waiter
    woken shouldBe 1
  }

  it should "let the holder alone ask a walk to stop (T038a)" taggedAs UnitTest in {
    val lease = WalkLease.of(new InMemoryBfsQueueStorage())
    val walk = lease.tryAcquire().get
    lease.isCancelled(walk) shouldBe false
    lease.cancel(walk + 1)
    lease.isCancelled(walk) shouldBe false
    lease.cancel(walk)
    lease.isCancelled(walk) shouldBe true
    lease.held shouldBe true // it holds the lease until it has stopped
    lease.release(walk)
    val next = lease.tryAcquire().get
    lease.cancel(walk) // no longer the holder: the next walk is not touched
    lease.isCancelled(next) shouldBe false
  }

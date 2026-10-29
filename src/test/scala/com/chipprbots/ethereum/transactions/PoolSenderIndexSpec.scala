package com.chipprbots.ethereum.transactions

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** #1675: the pool-state rules read each sender's pooled transactions for every transaction admitted. They used to copy
  * and scan the whole pool per transaction, O(batch x pool); [[PoolSenderIndex]] reads the pool once per batch.
  */
class PoolSenderIndexSpec extends AnyFlatSpec with Matchers:

  private def tx(nonce: Int): Transaction =
    LegacyTransaction(nonce, GasPrice(1), GasAmount(21000), Some(Address(42)), 0, ByteString.empty)

  private def hash(i: Int): ByteString = ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, i).array())

  private val alice = Address(1)
  private val bob = Address(2)

  /** A pool of `size` txs, alternating between alice and bob; tx i has hash(i) and nonce i. */
  private def pool(size: Int): Seq[(Address, ByteString, Transaction)] =
    (0 until size).map(i => (if i % 2 == 0 then alice else bob, hash(i), tx(i)))

  "PoolSenderIndex" should "read the pool once, however many lookups follow" taggedAs UnitTest in {
    val snapshot = pool(1000)
    var reads = 0
    val counted = snapshot.iterator.map { entry =>
      reads += 1
      entry
    }
    val index = PoolSenderIndex(counted, _ => true)
    (1 to 1000).foreach { i =>
      index.pooledFrom(if i % 2 == 0 then alice else bob, excluding = Some(hash(i)))
    }
    reads shouldBe 1000
  }

  it should "give a sender's pooled txs, in pool order, without the one being admitted" taggedAs UnitTest in {
    val index = PoolSenderIndex(pool(6), _ => true)
    index.pooledFrom(alice, excluding = Some(hash(2))) shouldBe Seq(tx(0), tx(4))
    index.pooledFrom(bob) shouldBe Seq(tx(1), tx(3), tx(5))
    index.pooledFrom(Address(3)) shouldBe empty
  }

  it should "see a tx the batch adds, and stop seeing one that leaves the pool" taggedAs UnitTest in {
    val inPool = mutable.Set.from((0 until 6).map(hash))
    val index = PoolSenderIndex(pool(6), inPool.contains)

    // An earlier tx of the batch is put in the pool: a later one from the same sender sees it.
    inPool += hash(100)
    index.add(alice, hash(100), tx(100))
    index.pooledFrom(alice) shouldBe Seq(tx(0), tx(2), tx(4), tx(100))

    // A later put size-evicts tx 2: it is no longer read.
    inPool -= hash(2)
    index.pooledFrom(alice) shouldBe Seq(tx(0), tx(4), tx(100))
  }

  it should "list a hash once, as the pool holds it once" taggedAs UnitTest in {
    val index = PoolSenderIndex(pool(2), _ => true)
    index.add(alice, hash(0), tx(0))
    index.pooledFrom(alice) shouldBe Seq(tx(0))
  }

  it should "not be built until a tx reaches the pool-state rules, and then once" taggedAs UnitTest in {
    // The live pool, by hash, in insertion order.
    val live = mutable.LinkedHashMap.from(pool(6).map((sender, h, t) => h -> (sender, t)))
    var builds = 0
    val deferred = PoolSenderIndex.Deferred { () =>
      builds += 1
      PoolSenderIndex(live.iterator.map { case (h, (sender, t)) => (sender, h, t) }, live.contains)
    }

    // admit puts a tx that no rule checked (its sender's state was not read), as it does on its post-put path.
    live += hash(100) -> (alice, tx(100))
    deferred.built.foreach(_.add(alice, hash(100), tx(100)))
    builds shouldBe 0 // a batch in which no tx reaches the rules builds no index

    // The first tx that reaches the rules builds it from the pool as it is then, so the earlier put is in it.
    deferred.get.pooledFrom(alice) shouldBe Seq(tx(0), tx(2), tx(4), tx(100))
    deferred.get
    builds shouldBe 1
  }

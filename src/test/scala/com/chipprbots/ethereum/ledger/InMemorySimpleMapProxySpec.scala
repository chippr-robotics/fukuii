package com.chipprbots.ethereum.ledger

import java.nio.ByteBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.common.SimpleMap
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*

class InMemorySimpleMapProxySpec extends AnyFlatSpec with Matchers:

  "InMemoryTrieProxy" should "not write inserts until commit" taggedAs (UnitTest, StateTest) in new TestSetup:
    val updatedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] =
      InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](mpt).put(1, 1).put(2, 2)

    assertContains(updatedProxy, 1, 1)
    assertContains(updatedProxy, 2, 2)

    assertNotContainsKey(mpt, 1)
    assertNotContainsKey(mpt, 2)

    val commitedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] = updatedProxy.persist()

    assertContains(commitedProxy.inner, 1, 1)
    assertContains(commitedProxy.inner, 2, 2)

  "InMemoryTrieProxy" should "not perform removals until commit" taggedAs (UnitTest, StateTest) in new TestSetup:
    val preloadedMpt: MerklePatriciaTrie[Int, Int] = mpt.put(1, 1)
    val proxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] =
      InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](preloadedMpt)

    assertContains(preloadedMpt, 1, 1)
    assertContains(proxy, 1, 1)

    val updatedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] = proxy.remove(1)
    assertNotContainsKey(updatedProxy, 1)
    assertContains(updatedProxy.inner, 1, 1)

    val commitedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] = updatedProxy.persist()
    assertNotContainsKey(commitedProxy, 1)
    assertNotContainsKey(commitedProxy.inner, 1)

  "InMemoryTrieProxy" should "not write updates until commit" taggedAs (UnitTest, StateTest) in new TestSetup:
    val preloadedMpt: MerklePatriciaTrie[Int, Int] = mpt.put(1, 1)
    val proxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] =
      InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](preloadedMpt)

    assertContains(preloadedMpt, 1, 1)
    assertContains(proxy, 1, 1)
    assertNotContains(preloadedMpt, 1, 2)
    assertNotContains(proxy, 1, 2)

    val updatedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] = proxy.put(1, 2)
    assertContains(updatedProxy, 1, 2)
    assertNotContains(updatedProxy.inner, 1, 2)

    val commitedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] = updatedProxy.persist()
    assertContains(commitedProxy, 1, 2)
    assertContains(commitedProxy.inner, 1, 2)

  "InMemoryTrieProxy" should "handle sequential operations" in new TestSetup:
    val updatedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] =
      InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](mpt).put(1, 1).remove(1).put(2, 2).put(2, 3)
    assertNotContainsKey(updatedProxy, 1)
    assertContains(updatedProxy, 2, 3)

  "InMemoryTrieProxy" should "handle batch operations" in new TestSetup:
    val updatedProxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] =
      InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](mpt).update(Seq(1), Seq((2, 2), (2, 3)))
    assertNotContainsKey(updatedProxy, 1)
    assertContains(updatedProxy, 2, 3)

  "InMemoryTrieProxy" should "not fail when deleting an inexistent value" in new TestSetup:
    assertNotContainsKey(InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](mpt).remove(1), 1)

  // #1675: `changes` runs on every persist() — once per touched storage trie, plus the account trie. It used to append
  // each entry with `:+` to a List, O(dirty²) per persist. It must list exactly what the old fold listed, in exactly
  // its order (the order the trie then applies removals and upserts in), as indexed collections built in one pass.
  "InMemoryTrieProxy" should "list its changes in the same order as the original fold, as Vectors" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    val proxy: InMemorySimpleMapProxy[Int, Int, MerklePatriciaTrie[Int, Int]] =
      (1 to 2000).foldLeft(InMemorySimpleMapProxy.wrap[Int, Int, MerklePatriciaTrie[Int, Int]](mpt)) { (p, i) =>
        if i % 3 == 0 then p.remove(i) else p.put(i, i * 7)
      }
    // The fold `changes` used before #1675, verbatim: the reference order.
    val reference: (Seq[Int], Seq[(Int, Int)]) =
      proxy.cache.foldLeft(Seq.empty[Int] -> Seq.empty[(Int, Int)]) { (acc, cachedItem) =>
        cachedItem match
          case (key, Some(value)) => (acc._1, acc._2 :+ key -> value)
          case (key, None)        => (acc._1 :+ key, acc._2)
      }
    val (removals, upserts) = proxy.changes
    removals shouldBe reference._1 // Seq equality is element-by-element, so this pins the order
    upserts shouldBe reference._2
    removals.size shouldBe 666
    upserts.size shouldBe 1334
    removals shouldBe a[Vector[?]]
    upserts shouldBe a[Vector[?]]

  def assertContains[I <: SimpleMap[Int, Int, I]](trie: I, key: Int, value: Int): Unit =
    assert(trie.get(key).isDefined && trie.get(key).get == value)

  def assertNotContains[I <: SimpleMap[Int, Int, I]](trie: I, key: Int, value: Int): Unit =
    assert(trie.get(key).isDefined && trie.get(key).get != value)

  def assertNotContainsKey[I <: SimpleMap[Int, Int, I]](trie: I, key: Int): Unit = assert(trie.get(key).isEmpty)

  trait TestSetup:
    implicit val intByteArraySerializable: ByteArraySerializable[Int] = new ByteArraySerializable[Int]:
      override def toBytes(input: Int): Array[Byte] =
        val b: ByteBuffer = ByteBuffer.allocate(4)
        b.putInt(input)
        b.array

      override def fromBytes(bytes: Array[Byte]): Int = ByteBuffer.wrap(bytes).getInt()

    val stateStorage: StateStorage = StateStorage.createTestStateStorage(EphemDataSource())._1
    val mpt: MerklePatriciaTrie[Int, Int] = MerklePatriciaTrie[Int, Int](stateStorage.getReadOnlyStorage)

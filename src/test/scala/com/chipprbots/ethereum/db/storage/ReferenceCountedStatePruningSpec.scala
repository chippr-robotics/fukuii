package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*

/** Reproduces the soak-node failure where saving (but never adopting) a non-canonical branch more than `history` blocks
  * past the canonical head pruned the death rows of blocks that replaced the head's state, deleting nodes the canonical
  * head still needs.
  */
class ReferenceCountedStatePruningSpec extends AnyFlatSpec with Matchers:

  private val history = 4
  private val keyCount = 200

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String, i: Int): ByteString = ByteString(s"$tag-$i")

  class Setup:
    val dataSource: EphemDataSource = EphemDataSource()
    val nodeStorage = new NodeStorage(dataSource)
    val stateStorage = new ReferenceCountedStateStorage(nodeStorage, history)

    /** Executes one block on top of `parentRoot`: rewrites `touched` keys, returns the new root. */
    def execute(bn: Int, parentRoot: ByteString, touched: Seq[Int], tag: String): ByteString =
      val storage = stateStorage.getBackingStorage(bn)
      val trie = touched.foldLeft(MerklePatriciaTrie[ByteString, ByteString](parentRoot.toArray, storage)) { (t, i) =>
        t.put(key(i), value(tag, i))
      }
      ByteString(trie.getRootHash)

    def genesis(): ByteString =
      val storage = stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value("g", i))
      }
      ByteString(trie.getRootHash)

    /** Reads every key of the state; throws MissingNodeException if any trie node is gone. */
    def readWholeState(root: ByteString): Unit =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, stateStorage.getReadOnlyStorage)
      (0 until keyCount).foreach(i => trie.get(key(i)))

    def dbKeys: Set[Seq[Byte]] = dataSource.storage.keySet.map(_.array().toSeq).toSet

  private def touchedBy(bn: Int, width: Int): Seq[Int] = (0 until width).map(j => (bn * 37 + j * 11) % keyCount)

  /** Canonical chain 1..head, where each save sees the previous block as best (single-block import). */
  private def buildCanonical(s: Setup, head: Int): Map[Int, ByteString] =
    var roots = Map(0 -> s.genesis())
    (1 to head).foreach { bn =>
      roots += bn -> s.execute(bn, roots(bn - 1), touchedBy(bn, 20), "c")
      s.stateStorage.onBlockSave(bn, bn - 1)(() => ())
    }
    roots

  "ReferenceCountedStateStorage" should "keep the canonical head state when a non-canonical branch is saved far past it" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val head = 10
    val roots = buildCanonical(this, head)
    (0 to head).filter(_ > head - history).foreach(bn => readWholeState(roots(bn)))

    // Non-canonical branch from the head, saved to head + history + 3 while the canonical head stays at `head`.
    var parent = roots(head)
    (head + 1 to head + history + 3).foreach { bn =>
      parent = execute(bn, parent, touchedBy(bn + 1000, 40), "x")
      stateStorage.onBlockSave(bn, head)(() => ())
    }

    // The head and the last (history - 1) canonical states must still be fully readable.
    (head - history + 1 to head).foreach(bn => noException should be thrownBy readWholeState(roots(bn)))

  it should "keep the canonical head state when the same block numbers are re-saved repeatedly" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val head = 10
    val roots = buildCanonical(this, head)

    (1 to 5).foreach { attempt =>
      var parent = roots(head)
      (head + 1 to head + history + 3).foreach { bn =>
        parent = execute(bn, parent, touchedBy(bn + attempt * 1000, 40), s"r$attempt")
        stateStorage.onBlockSave(bn, head)(() => ())
      }
    }

    (head - history + 1 to head).foreach(bn => noException should be thrownBy readWholeState(roots(bn)))

  it should "still prune on a linear chain exactly as before (single-block import)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val reference = new Setup // legacy schedule: prune(bn - history) on every save
    var roots = Map(0 -> genesis())
    var refRoots = Map(0 -> reference.genesis())
    (1 to 30).foreach { bn =>
      roots += bn -> execute(bn, roots(bn - 1), touchedBy(bn, 20), "c")
      refRoots += bn -> reference.execute(bn, refRoots(bn - 1), touchedBy(bn, 20), "c")
      stateStorage.onBlockSave(bn, bn - 1)(() => ())
      ReferenceCountNodeStorage.prune(bn - history, reference.nodeStorage, inMemory = false)
      dbKeys shouldBe reference.dbKeys
    }
    dbKeys should not be empty

  it should "converge to the same deleted-key set on a linear chain imported in batches larger than history" taggedAs (
    UnitTest,
    DatabaseTest
  ) in new Setup:
    val reference = new Setup
    var roots = Map(0 -> genesis())
    var refRoots = Map(0 -> reference.genesis())
    val batch = history + 3
    (0 until 5).foreach { b =>
      val bestBefore = b * batch
      ((bestBefore + 1) to (bestBefore + batch)).foreach { bn =>
        roots += bn -> execute(bn, roots(bn - 1), touchedBy(bn, 20), "c")
        refRoots += bn -> reference.execute(bn, refRoots(bn - 1), touchedBy(bn, 20), "c")
        stateStorage.onBlockSave(bn, bestBefore)(() => ()) // best only advances after the whole batch
        ReferenceCountNodeStorage.prune(bn - history, reference.nodeStorage, inMemory = false)
      }
    }
    val last = 5 * batch
    // the head advanced to `last`; the next save (re-)asserts it and the lagging prunes catch up
    stateStorage.onBlockSave(last, last)(() => ())
    dbKeys shouldBe reference.dbKeys

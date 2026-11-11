package com.chipprbots.ethereum.mpt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T023): the empty trie has no rows (data-model 2 and 3 rule 7), and a build that only inserts
  * empties no position (oracle 6 of `baselines/oracle-tests.md`).
  */
class PositionEmptyTrieSpec extends AnyFlatSpec with Matchers:

  "The empty trie" should "have no rows, and read as empty" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    twin.positions.rows shouldBe empty
    twin.root.toSeq shouldBe MerklePatriciaTrie.EmptyRootHash.toSeq
    twin.trie.get(Array[Byte](0x12)) shouldBe None
    twin.trie.getProof(Array[Byte](0x12)) shouldBe None
    twin.positions.reads shouldBe empty

    // removing from it reports nothing
    twin.remove(Array[Byte](0x12))
    twin.positions.calls shouldBe empty
  }

  it should "get its root row with its first key, at any size" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    twin.put(Array[Byte](0x12), Array[Byte](0x34))

    val call = twin.positions.lastCall
    call.replaced shouldBe empty
    call.absorbed shouldBe empty
    twin.positions.rows.keySet shouldBe Set(rowKey())
    twin.positions.rows(rowKey()).length should be < 32
    blockDiff(twin.positions.calls) shouldBe Map(rowKey() -> (None, twin.positions.rows.get(rowKey())))
  }

  it should "lose its root row with its last key" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    twin.put(Array[Byte](0x12), Array[Byte](0x34))
    val root = twin.positions.rows(rowKey())

    val callsBefore = twin.positions.calls.size
    twin.remove(Array[Byte](0x12))

    twin.positions.rows shouldBe empty
    twin.root.toSeq shouldBe MerklePatriciaTrie.EmptyRootHash.toSeq
    twin.positions.lastCall.replacedAt shouldBe Set(rowKey())
    // the root position of a trie that becomes empty is a position that held a node and holds none
    blockDiff(twin.positions.calls.drop(callsBefore)) shouldBe Map(rowKey() -> (Some(root), None))
  }

  it should "leave no trace in a block that fills and empties it" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    twin.put(Array[Byte](0x12), Array.fill[Byte](40)(1))
    twin.put(Array[Byte](0x13), Array.fill[Byte](40)(2))
    twin.remove(Array[Byte](0x12))
    twin.remove(Array[Byte](0x13))
    twin.positions.rows shouldBe empty
    blockDiff(twin.positions.calls) shouldBe empty
  }

  // Oracle 6. A genesis state is built by insertion alone (`GenesisDataLoader`), and its storage drops what the trie
  // reports as replaced (`AppendOnlyMptStorage`). That is exact only if an insertion never empties a position: every
  // row it replaces gets a new node at the same position.
  "A build by insertion alone" should "empty no position" taggedAs (UnitTest, MPTTest) in {
    Seq(Keys.Dense, Keys.Hashed, Keys.Varying).foreach { keys =>
      (1L to 12L).foreach { seed =>
        val twin = new TwinTrie
        val source = new OpSource(seed, keys)
        (1 to 150).foreach { step =>
          applyOp(twin, source.nextInsert())
          val call = twin.positions.lastCall
          withClue(s"$keys, seed $seed, insertion $step: ") {
            call.absorbed shouldBe empty
            (call.oldStored.keySet -- call.newStored.keySet) shouldBe empty
          }
        }
        val vanished = blockDiff(twin.positions.calls).collect { case (key, (Some(_), None)) => key }
        withClue(s"$keys, seed $seed: ")(vanished shouldBe empty)
      }
    }
  }

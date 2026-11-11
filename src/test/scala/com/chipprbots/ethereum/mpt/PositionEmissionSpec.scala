package com.chipprbots.ethereum.mpt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T019), oracle 1 of `baselines/oracle-tests.md`: what the trie reports change by change equals
  * what a comparison of the whole trie before and after finds.
  *
  * Every change in these runs goes through [[PositionTestKit.TwinTrie]], which checks after each one that the position
  * store holds exactly the stored positions a raw walk of the trie finds. A stored node that went unreported would stay
  * behind as a row, a live node that was reported would be missing, and a wrong position would fail the next read.
  */
class PositionEmissionSpec extends AnyFlatSpec with Matchers:

  private val seeds = 1L to 12L

  /** Runs `blocks` blocks of 1 to `maxOps` changes and holds each block's change set to the full comparison. */
  private def incrementalEqualsFull(keys: Keys, blocks: Int, maxOps: Int): Unit =
    seeds.foreach { seed =>
      val twin = new TwinTrie
      val source = new OpSource(seed, keys)
      val sizes = new scala.util.Random(seed * 31)
      (1 to blocks).foreach { block =>
        withClue(s"seed $seed, block $block: ") {
          val before = twin.positions.rows
          val callsBefore = twin.positions.calls.size
          (1 to 1 + sizes.nextInt(maxOps)).foreach(_ => applyOp(twin, source.next()))
          val reported = blockDiff(twin.positions.calls.drop(callsBefore))
          reported shouldBe fullDiff(before, twin.positions.rows)
        }
      }
      // every key reads back through the position store, each node found where the trie says it is
      source.contents.foreach { case (key, value) =>
        twin.trie.get(key).map(_.toSeq) shouldBe Some(value.toSeq)
      }
    }

  "The positions a trie reports" should "equal a full comparison, for keys with long shared prefixes" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    incrementalEqualsFull(Keys.Dense, blocks = 40, maxOps = 12)
  }

  it should "equal a full comparison, for the keys of a secure trie" taggedAs (UnitTest, MPTTest) in {
    incrementalEqualsFull(Keys.Hashed, blocks = 30, maxOps = 12)
  }

  it should "equal a full comparison, for keys that are prefixes of other keys" taggedAs (UnitTest, MPTTest) in {
    incrementalEqualsFull(Keys.Varying, blocks = 40, maxOps = 8)
  }

  // The cases the oracle names (oracle-tests.md row 1): they must occur in the runs above, or the runs prove less than
  // they claim.
  it should "have met every case the oracle names in those runs" taggedAs (UnitTest, MPTTest) in {
    var absorbedLeaves, absorbedExtensions, grewPast32, shrankBelow32, emptied, filled, unchangedPuts = 0
    var keptBranches, splitExtensions, splitLeaves, branchValues, emptyKeyLeaves = 0
    Seq(Keys.Dense, Keys.Hashed, Keys.Varying).foreach { keys =>
      seeds.foreach { seed =>
        val twin = new TwinTrie
        val source = new OpSource(seed, keys)
        (1 to 300).foreach { _ =>
          val before = twin.positions.rows
          val callsBefore = twin.positions.calls.size
          val op = source.next()
          twin.positions.clearReads()
          applyOp(twin, op)
          val after = twin.positions.rows
          twin.positions.calls.drop(callsBefore).foreach { call =>
            op match
              case _: Op.Remove =>
                // a node the removal read that is neither on its path nor merged away: the branch `fix` reads and keeps
                if (twin.positions.reads.toSet -- call.replacedAt -- call.absorbedAt).nonEmpty then keptBranches += 1
              case _: Op.Put =>
                // the path of an insertion ends at the node it splits (the first key of a trie has no path)
                call.replaced.lastOption.map(_._2) match
                  case Some(_: ExtensionNode)                        => splitExtensions += 1
                  case Some(_: LeafNode) if after.size > before.size => splitLeaves += 1
                  case _                                             => ()
            call.replaced.foreach {
              case (_, branch: BranchNode) if branch.terminator.isDefined => branchValues += 1
              case (_, leaf: LeafNode) if leaf.key.isEmpty                => emptyKeyLeaves += 1
              case _                                                      => ()
            }
            call.absorbed.foreach {
              case (_, _: LeafNode)      => absorbedLeaves += 1
              case (_, _: ExtensionNode) => absorbedExtensions += 1
              case (path, other)         => fail(s"absorbed at [${path.show}]: $other")
            }
            // a position that had no row and has one: a node that grew past 32 bytes, or a new one
            if call.replaced.exists { case (path, node) => !isStored(node.encode, path) && after.contains(path.key) }
            then grewPast32 += 1
            if call.replaced.exists { case (path, node) =>
                isStored(node.encode, path) && !after.contains(path.key) && !path.isEmpty
              }
            then shrankBelow32 += 1
            if call.oldStored.nonEmpty && blockDiff(Seq(call)).isEmpty then unchangedPuts += 1
          }
          if before.nonEmpty && after.isEmpty then emptied += 1
          if before.isEmpty && after.nonEmpty then filled += 1
        }
      }
    }
    withClue("a leaf merged into its parent: ")(absorbedLeaves should be > 0)
    withClue("an extension merged into its parent: ")(absorbedExtensions should be > 0)
    withClue("an embedded node replaced by a stored one: ")(grewPast32 should be > 0)
    withClue("a stored node replaced by an embedded one or by none: ")(shrankBelow32 should be > 0)
    withClue("a trie that became empty: ")(emptied should be > 0)
    withClue("an empty trie that got its first key: ")(filled should be > 0)
    withClue("a put of the value already there: ")(unchangedPuts should be > 0)
    withClue("a branch a removal reads and keeps under a new extension: ")(keptBranches should be > 0)
    withClue("an extension split by an insertion: ")(splitExtensions should be > 0)
    withClue("a leaf split by an insertion: ")(splitLeaves should be > 0)
    withClue("a branch that holds a value: ")(branchValues should be > 0)
    withClue("a leaf with an empty key: ")(emptyKeyLeaves should be > 0)
  }

  // Forge, C1 pre-code review (C-2): a put of the value a key already holds reports the nodes on its path, and each is
  // replaced by an identical node. The change set must come out empty (data-model 3 rule 2).
  it should "leave an empty change set for a put of the value already there" taggedAs (UnitTest, MPTTest) in {
    seeds.foreach { seed =>
      val twin = new TwinTrie
      val source = new OpSource(seed, Keys.Hashed)
      (1 to 60).foreach(_ => applyOp(twin, source.nextInsert()))
      val before = twin.positions.rows
      source.contents.foreach { case (key, value) =>
        val callsBefore = twin.positions.calls.size
        twin.put(key, value)
        val calls = twin.positions.calls.drop(callsBefore)
        calls should have size 1
        calls.head.replaced should not be empty
        blockDiff(calls) shouldBe empty
      }
      twin.positions.rows shouldBe before
    }
  }

  // The collapse with positions is the collapse without them: same root, same nodes, same order.
  "collapseTrieAt" should "return what collapseTrie returns, each node with its position" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    Seq(Keys.Dense, Keys.Hashed, Keys.Varying).foreach { keys =>
      seeds.foreach { seed =>
        val twin = new TwinTrie
        val source = new OpSource(seed, keys)
        (1 to 80).foreach(_ => applyOp(twin, source.next()))
        if twin.positions.rows.nonEmpty then
          // the whole trie in memory, so that the collapse walks all of it
          val whole = MptTraversals.parseTrieIntoMemory(HashNode(twin.root), twin.reference)
          val fresh = stripCachedEncodings(whole)
          val (plainRoot, plainNodes) = MptTraversals.collapseTrie(fresh)
          val (positionedRoot, positionedNodes) =
            MptTraversals.collapseTrieAt(stripCachedEncodings(whole), NibblePath.Empty)

          withClue(s"$keys, seed $seed: ") {
            positionedRoot.hashNode.toSeq shouldBe plainRoot.hashNode.toSeq
            positionedRoot.hashNode.toSeq shouldBe twin.root.toSeq
            positionedNodes.map { case (_, hash, encoded) => (hash, encoded.toSeq) } shouldBe
              plainNodes.map { case (hash, encoded) => (hash, encoded.toSeq) }
            // and each node is where a raw walk of the trie finds it
            positionedNodes.map { case (path, _, encoded) =>
              path.key -> org.apache.pekko.util.ByteString(encoded)
            }.toMap shouldBe twin.walk
            positionedNodes.head._1.isEmpty shouldBe true
          }
      }
    }
  }

  it should "start below the position it is given" taggedAs (UnitTest, MPTTest) in {
    val twin = new TwinTrie
    val source = new OpSource(7L, Keys.Dense)
    (1 to 40).foreach(_ => applyOp(twin, source.nextInsert()))
    val whole = MptTraversals.parseTrieIntoMemory(HashNode(twin.root), twin.reference)
    val base = NibblePath(0x3, 0xc)
    val (_, fromRoot) = MptTraversals.collapseTrieAt(stripCachedEncodings(whole), NibblePath.Empty)
    val (_, fromBase) = MptTraversals.collapseTrieAt(stripCachedEncodings(whole), base)
    fromBase.map(_._1.show) shouldBe fromRoot.map(entry => (base ++ entry._1).show)
  }

  /** The same trie with no cached encoding or hash anywhere, as a trie that was just built: a collapse walks all of it.
    */
  private def stripCachedEncodings(node: MptNode): MptNode = node match
    case leaf: LeafNode     => LeafNode(leaf.key, leaf.value)
    case ext: ExtensionNode => ExtensionNode(ext.sharedKey, stripCachedEncodings(ext.next))
    case branch: BranchNode => BranchNode(branch.children.map(stripCachedEncodings), branch.terminator)
    case other              => other

package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** The sample at the Path hand-off (spec 022 T048, T054, decision 11). The export keys what it writes by keccak, so a
  * check of that proves nothing, and a sample across many roots is no recompute of one: what this asks is whether
  * lookups from the state root reach what they should through the hash store, in the strata a plain sample misses. What
  * the larger strata find does not stop the hand-off. Evidence for SC-003 is this sample plus import.
  */
class PathToHashExportCheckSpec extends AnyFlatSpec with Matchers:

  /** The path store of a state, seeded by position, and the hash store the export goes to. */
  private class State:
    val paths = new PathNodeStorage(EphemDataSource())
    val target = new TestMptStorage

    def account(nibbles: Seq[Int], node: MptNode): MptNode =
      paths.writeAccountNode(nibbles.map(_.toByte).toArray, node.encode)
      node

    def storage(owner: ByteString, nibbles: Seq[Int], node: MptNode): MptNode =
      paths.writeStorageNode(owner, nibbles.map(_.toByte).toArray, node.encode)
      node

    def publish(root: ByteString, codePresent: ByteString => Boolean = _ => true): PathToHashExporter.Result =
      PathToHashExporter.publish(paths, target, stateRoot = Some(root), codePresent = codePresent)

  private def branch(slots: (Int, MptNode)*): BranchNode =
    val children = Array.fill[MptNode](16)(NullNode)
    slots.foreach { case (slot, child) => children(slot) = HashNode(child.hash) }
    BranchNode(children, None)

  private def nibbles(n: Int, nibble: Int): ByteString = ByteString(Array.fill[Byte](n)(nibble.toByte))

  private def owner(nibble: Int): ByteString =
    ByteString(HexPrefix.nibblesToBytes(nibbles(64, nibble).toArray))

  private def accountBytes(storageRoot: Option[ByteString] = None, code: Option[ByteString] = None): ByteString =
    ByteString(
      Account.accountSerializer.toBytes(
        Account(
          nonce = UInt256(1L),
          storageRoot = storageRoot.fold(Account.EmptyStorageRootHash)(TrieRoot(_)),
          codeHash = code.fold(Account.EmptyCodeHash)(CodeHash(_))
        )
      )
    )

  /** Account 1 under slot 1 of the root, over a storage trie of three leaves. An extension takes the trie down to a
    * depth where one of them is short enough to embed in its parent, as a deep leaf on a real chain is.
    */
  private def stateWithStorage(s: State, code: Option[ByteString] = None): (ByteString, LeafNode, LeafNode) =
    val stored = LeafNode(nibbles(63, 2), ByteString(Array.fill[Byte](40)(7)))
    val deep = LeafNode(nibbles(3, 8), ByteString(Array.fill[Byte](40)(8)))
    val embedded = LeafNode(nibbles(3, 6), ByteString(Array[Byte](1, 2)))
    embedded.encode.length should be < 32
    val toInner = Seq(1) ++ Seq.fill(59)(3)
    val inner = BranchNode(
      {
        val children = Array.fill[MptNode](16)(NullNode)
        children(5) = embedded
        children(7) = HashNode(deep.hash)
        children
      },
      None
    )
    inner.encode.length should be >= 32
    val extension = ExtensionNode(nibbles(59, 3), HashNode(inner.hash))
    s.storage(owner(1), toInner :+ 5, embedded)
    s.storage(owner(1), toInner :+ 7, deep)
    s.storage(owner(1), toInner, inner)
    s.storage(owner(1), Seq(1), extension)
    s.storage(owner(1), Seq(2), stored)
    val storageRoot = s.storage(owner(1), Seq(), branch(1 -> extension, 2 -> stored))
    val leaf = LeafNode(nibbles(63, 1), accountBytes(Some(ByteString(storageRoot.hash)), code))
    s.account(Seq(1), leaf)
    val root = s.account(Seq(), branch(1 -> leaf))
    (ByteString(root.hash), stored, embedded)

  "The hand-off's sample" should "look up storage leaves from the state root, the embedded one too, and give its time apart" taggedAs UnitTest in {
    val s = new State
    val (root, _, _) = stateWithStorage(s)

    val result = s.publish(root)

    result.sampledAccountLeaves shouldBe 1
    result.sampledStorageRoots shouldBe 1
    withClue("this trie's three leaves, in the largest-tries stratum: ")(result.sampledLargeTrieLeaves shouldBe 3)
    result.sampledEmbedded shouldBe 1
    result.sampledStale shouldBe 0
    result.sampledMissing shouldBe 0
    result.exportMillis should be >= 0L
    result.sampleMillis should be >= 0L
  }

  it should "write the same nodes it did before: nothing here changes what an export publishes" taggedAs UnitTest in {
    val s = new State
    val (root, stored, embedded) = stateWithStorage(s)
    val result = s.publish(root)
    result.storageNodes shouldBe 5L // all but the embedded leaf
    result.accountNodes shouldBe 2L
    scala.util.Try(s.target.get(stored.hash)).isSuccess shouldBe true
    withClue("an embedded node is its parent's: ") {
      scala.util.Try(s.target.get(embedded.hash)).isFailure shouldBe true
    }
  }

  it should "count a storage leaf the state root has no place for as stale, and not fail" taggedAs UnitTest in {
    val s = new State
    val (root, _, _) = stateWithStorage(s)
    // Another root's: account 5 is in no trie this root has.
    s.storage(owner(5), Seq(4), LeafNode(nibbles(63, 4), ByteString(Array.fill[Byte](40)(9))))

    val result = s.publish(root)

    result.sampledStale should be >= 1
    result.sampledMissing shouldBe 0
  }

  it should "count a node absent on the way to a storage leaf, and leave the hand-off to go on" taggedAs UnitTest in {
    val s = new State
    val leaf = LeafNode(nibbles(62, 2), ByteString(Array.fill[Byte](40)(7)))
    val absentRoot = ByteString(kec256(Array[Byte](9, 9)))
    s.storage(owner(1), Seq(1, 2), leaf)
    val accountLeaf = LeafNode(nibbles(63, 1), accountBytes(Some(absentRoot)))
    s.account(Seq(1), accountLeaf)
    val root = s.account(Seq(), branch(1 -> accountLeaf))

    val result = s.publish(ByteString(root.hash))

    result.sampledMissing shouldBe 1
    result.sampledAccountLeaves shouldBe 1
  }

  it should "look for the code of the accounts it sampled, each hash once, and report what is absent" taggedAs UnitTest in {
    val s = new State
    val code = ByteString(kec256(Array[Byte](1)))
    val other = ByteString(kec256(Array[Byte](2)))
    val first = LeafNode(nibbles(63, 1), accountBytes(code = Some(code)))
    val second = LeafNode(nibbles(63, 2), accountBytes(code = Some(code)))
    val third = LeafNode(nibbles(63, 3), accountBytes(code = Some(other)))
    s.account(Seq(1), first)
    s.account(Seq(2), second)
    s.account(Seq(3), third)
    val root = s.account(Seq(), branch(1 -> first, 2 -> second, 3 -> third))

    val result = s.publish(ByteString(root.hash), codePresent = _ == code)

    result.sampledCode shouldBe 2
    result.sampledCodeAbsent shouldBe 1
    result.sampledAccountLeaves shouldBe 3
  }

  it should "sample by first nibble, so a region can't be missed for being small" taggedAs UnitTest in {
    val s = new State
    val loneCode = ByteString(kec256(Array[Byte](1)))
    val lone = LeafNode(nibbles(63, 0), accountBytes(code = Some(loneCode)))
    s.account(Seq(0), lone)
    // Under slot 1, more nodes than one stratum holds.
    val middles = (0 until 16).map { a =>
      val leaves = (0 until 16).map { b =>
        val leaf = LeafNode(nibbles(61, 9), accountBytes())
        s.account(Seq(1, a, b), leaf)
        b -> leaf
      }
      val middle = branch(leaves*)
      s.account(Seq(1, a), middle)
      a -> middle
    }
    val crowd = branch(middles*)
    s.account(Seq(1), crowd)
    val root = s.account(Seq(), branch(0 -> lone, 1 -> crowd))
    var asked = Set.empty[ByteString]

    val result = s.publish(
      ByteString(root.hash),
      codePresent = hash =>
        asked += hash
        true
    )

    withClue("the one account in its region is always looked at: ")(asked should contain(loneCode))
    result.sampledAccountLeaves should be <= PathToHashExporter.AccountSamplePerNibble + 1
  }

  // A throw here is PathPublishFailed, HealingImpossible, and a SNAP restart, over what the storage strata call harmless.
  it should "count an account leaf the state root has no place for, and go on: a stale leaf must not restart SNAP" taggedAs UnitTest in {
    val s = new State
    val (root, _, _) = stateWithStorage(s)
    s.account(Seq(7), LeafNode(nibbles(63, 7), accountBytes()))
    val result = s.publish(root)
    result.sampledAccountLeaves shouldBe 2
    result.sampledAccountMisses shouldBe 1
  }

  it should "count a node absent on the way to an account leaf the same way" taggedAs UnitTest in {
    val s = new State
    val leaf = LeafNode(nibbles(63, 1), accountBytes())
    val elsewhere = LeafNode(nibbles(63, 2), accountBytes())
    val root = s.account(Seq(), branch(1 -> leaf, 2 -> elsewhere))
    // Its path holds another node now, so the hash store never gets the one the root names.
    s.account(Seq(1), LeafNode(nibbles(63, 1), accountBytes(code = Some(ByteString(kec256(Array[Byte](3)))))))
    s.account(Seq(2), elsewhere)

    val result = s.publish(ByteString(root.hash))

    result.sampledAccountLeaves shouldBe 2
    result.sampledAccountMisses shouldBe 1
  }

  /** `count` leaves in the path store at accounts no root has. */
  private def staleLeaves(s: State, count: Int): Unit =
    (0 until count).foreach { i =>
      s.account(Seq(7, i % 16, i / 16), LeafNode(nibbles(61, 7), accountBytes()))
    }

  it should "go on with 15 misses, however few leaves there are" taggedAs UnitTest in {
    val s = new State
    val (root, _, _) = stateWithStorage(s)
    staleLeaves(s, 15)
    s.publish(root).sampledAccountMisses shouldBe 15
  }

  it should "fail loudly at a rate that says the export is wrong" taggedAs UnitTest in {
    val s = new State
    val (root, _, _) = stateWithStorage(s)
    staleLeaves(s, 16)
    val thrown = the[IllegalStateException] thrownBy s.publish(root)
    thrown.getMessage should include("16 of 17")
  }

  "tooManyMisses" should "want more than 1% of the sample, and at least 16" taggedAs UnitTest in {
    PathToHashExporter.tooManyMisses(15, 16) shouldBe false
    PathToHashExporter.tooManyMisses(16, 16) shouldBe true
    withClue("exactly 1%: ")(PathToHashExporter.tooManyMisses(16, 1600) shouldBe false)
    PathToHashExporter.tooManyMisses(17, 1600) shouldBe true
    PathToHashExporter.tooManyMisses(16, 1599) shouldBe true
    PathToHashExporter.tooManyMisses(0, 0) shouldBe false
  }

  "The hand-off's sample of a root that is not there" should "fail loudly, whatever the rate" taggedAs UnitTest in {
    val s = new State
    stateWithStorage(s)
    an[IllegalStateException] should be thrownBy s.publish(ByteString(kec256(Array[Byte](1, 2, 3))))
  }

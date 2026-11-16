package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
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

/** The sample at the hand-off onto the position store (spec 018 T196, spec 022 decision 11): the cases of
  * `PathToHashExportCheckSpec`, on rows that are looked up where they lie and not through a copy. What it asks is
  * whether lookups from the state root reach what the rows hold, in the strata a plain sample misses, and that a row
  * that is not the node its parent names is found by the walk that passes it.
  */
class PathHandoffSampleSpec extends AnyFlatSpec with Matchers:

  /** The rows of a state, seeded by position. */
  private class State(source: EphemDataSource = EphemDataSource()):
    val paths = new PathNodeStorage(source)

    def account(nibbles: Seq[Int], node: MptNode): MptNode =
      paths.writeAccountNode(nibbles.map(_.toByte).toArray, node.encode)
      node

    def storage(owner: ByteString, nibbles: Seq[Int], node: MptNode): MptNode =
      paths.writeStorageNode(owner, nibbles.map(_.toByte).toArray, node.encode)
      node

    def sample(
        root: ByteString,
        codePresent: ByteString => Boolean = _ => true,
        seed: Long = 1L,
        accountsLeftOut: Boolean = false,
        largeTrieRowBound: Int = PathHandoffSample.LargeTrieRowBound,
        largeTries: Int = PathHandoffSample.LargeStorageTries
    ): PathHandoffSample.Result =
      PathHandoffSample.run(paths, root, codePresent, new Random(seed), accountsLeftOut, largeTrieRowBound, largeTries)

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

  private val toInner = Seq(1) ++ Seq.fill(59)(3)

  /** Account 1 under slot 1 of the root, over a storage trie of three leaves. An extension takes the trie down to a
    * depth where one of them is short enough to be held in its parent, as a deep leaf on a real chain is.
    */
  private def stateWithStorage(
      s: State,
      code: Option[ByteString] = None,
      others: Seq[(Int, MptNode)] = Nil
  ): (ByteString, BranchNode) =
    val stored = LeafNode(nibbles(63, 2), ByteString(Array.fill[Byte](40)(7)))
    val deep = LeafNode(nibbles(3, 8), ByteString(Array.fill[Byte](40)(8)))
    val embedded = LeafNode(nibbles(3, 6), ByteString(Array[Byte](1, 2)))
    embedded.encode.length should be < 32
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
    others.foreach((slot, node) => s.account(Seq(slot), node))
    val root = s.account(Seq(), branch(((1 -> leaf) +: others)*))
    (ByteString(root.hash), inner)

  "The hand-off's sample" should "look up storage leaves from the state root, the one held in its parent too" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)

    val result = s.sample(root)

    result.sampledAccountLeaves shouldBe 1
    result.sampledAccountMisses shouldBe 0
    result.sampledStorageRoots shouldBe 1
    result.sampledStorageRootsStale shouldBe 0
    result.sampledStorageRootsMissing shouldBe 0
    withClue("this trie's three leaves, as the largest trie sampled: ")(result.sampledLargeTrieLeaves shouldBe 3)
    result.sampledEmbedded shouldBe 1
    result.sampledStale shouldBe 0
    result.sampledMissing shouldBe 0
    withClue("an account with no code has none to look for: ")(result.sampledCode shouldBe 0)
    result.sampleMillis should be >= 0L
  }

  it should "find the same wherever its seeks land" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    val first = s.sample(root, seed = 1L).copy(sampleMillis = 0L)
    (2L to 20L).foreach(seed => s.sample(root, seed = seed).copy(sampleMillis = 0L) shouldBe first)
  }

  it should "write nothing" taggedAs UnitTest in {
    val writes = new java.util.concurrent.atomic.AtomicInteger
    val source = new EphemDataSource(Map()):
      override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
        writes.incrementAndGet()
        super.update(dataSourceUpdates)
    val s = new State(source)
    val (root, _) = stateWithStorage(s)
    val seeded = writes.get
    s.sample(root)
    writes.get shouldBe seeded
  }

  it should "sample a trie with more rows than are read to size it by seeks inside it" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)

    val result = s.sample(root, largeTrieRowBound = 4)

    result.sampledLargeTrieLeaves shouldBe 3
    result.sampledStale shouldBe 0
    result.sampledMissing shouldBe 0
  }

  it should "look up the leaves of the tries with the most rows" taggedAs UnitTest in {
    val s = new State
    val small = s.storage(owner(2), Seq(), LeafNode(nibbles(64, 4), ByteString(Array.fill[Byte](40)(4))))
    val (root, _) =
      stateWithStorage(s, others = Seq(2 -> LeafNode(nibbles(63, 2), accountBytes(Some(ByteString(small.hash))))))

    val result = s.sample(root, largeTries = 1)

    result.sampledStorageRoots shouldBe 2
    withClue("the three leaves of the larger trie, and not the one of the smaller: ") {
      result.sampledLargeTrieLeaves shouldBe 3
    }
  }

  it should "stop a hand-off over a storage trie whose account cannot be reached, whatever the sync left out" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    // The account's own row is another node: nothing of its storage can be reached from the root.
    s.account(Seq(1), LeafNode(nibbles(63, 1), accountBytes(code = Some(ByteString(kec256(Array[Byte](3)))))))

    Seq(false, true).foreach { leftOut =>
      val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(root, accountsLeftOut = leftOut)
      thrown.getMessage should include("a row of the account trie")
      thrown.reason shouldBe PathHandoffSample.StopReason.AccountHole
    }
  }

  // Only the look for the trie's root passes the hole: the account's row is gone, so no seek lands on it, and the
  // trie has no leaf row to look up.
  it should "stop a hand-off over an account that cannot be reached, met only on the way to its storage root" taggedAs UnitTest in {
    val s = new State
    val storageRoot = s.storage(owner(1), Seq(), branch(1 -> LeafNode(nibbles(63, 1), ByteString(Array[Byte](1)))))
    val leaf = LeafNode(nibbles(63, 1), accountBytes(Some(ByteString(storageRoot.hash))))
    val root = ByteString(s.account(Seq(), branch(1 -> leaf)).hash)

    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(root, accountsLeftOut = true)
    thrown.getMessage should include("1 lookups")
    thrown.getMessage should include("a row of the account trie")
    thrown.reason shouldBe PathHandoffSample.StopReason.AccountHole
  }

  it should "count rows left under an account that has no storage as stale: it names no root to look for" taggedAs UnitTest in {
    val s = new State
    s.storage(
      owner(1),
      Seq(),
      branch(1 -> s.storage(owner(1), Seq(1), LeafNode(nibbles(63, 1), ByteString(Array.fill[Byte](40)(1)))))
    )
    val leaf = s.account(Seq(1), LeafNode(nibbles(63, 1), accountBytes()))
    val root = ByteString(s.account(Seq(), branch(1 -> leaf)).hash)

    val result = s.sample(root)

    result.sampledStorageRoots shouldBe 1
    result.sampledStorageRootsStale shouldBe 1
    result.sampledStorageRootsMissing shouldBe 0
    result.sampledStale shouldBe 1
    result.sampledMissing shouldBe 0
  }

  it should "choose among tries of one size at the bound by lot, not by their owners' order" taggedAs UnitTest in {
    val s = new State
    def trieOf(owner: ByteString, leaves: Int): MptNode =
      val under = (0 until leaves).map { i =>
        i -> s.storage(owner, Seq(i), LeafNode(nibbles(63, i), ByteString(Array.fill[Byte](40)(i.toByte))))
      }
      s.storage(owner, Seq(), branch(under*))
    val three = trieOf(owner(1), 3)
    val four = trieOf(owner(2), 4)
    val first = LeafNode(nibbles(63, 1), accountBytes(Some(ByteString(three.hash))))
    val second = LeafNode(nibbles(63, 2), accountBytes(Some(ByteString(four.hash))))
    s.account(Seq(1), first)
    s.account(Seq(2), second)
    val root = ByteString(s.account(Seq(), branch(1 -> first, 2 -> second)).hash)

    // both have more rows than are read to size them: the one looked into is known by how many leaves it has
    val lookedInto = (1L to 20L).map(seed => s.sample(root, seed = seed, largeTrieRowBound = 4, largeTries = 1))
    lookedInto.map(_.sampledLargeTrieLeaves).toSet shouldBe Set(3, 4)
  }

  it should "reach every first nibble, at paths of an even length and of an odd one" taggedAs UnitTest in {
    val s = new State
    val tops = (0 until 16).map { i =>
      val even = LeafNode(nibbles(62, 5), accountBytes())
      val odd = LeafNode(nibbles(61, 5), accountBytes())
      val below = branch(0 -> odd)
      s.account(Seq(i, 0), even)
      s.account(Seq(i, 1, 0), odd)
      s.account(Seq(i, 1), below)
      i -> s.account(Seq(i), branch(0 -> even, 1 -> below))
    }
    val root = s.account(Seq(), branch(tops*))

    (1L to 10L).foreach { seed =>
      val result = s.sample(ByteString(root.hash), seed = seed)
      result.sampledAccountLeaves shouldBe 32
      result.sampledAccountMisses shouldBe 0
    }
  }

  // The last key of the account trie: above where any seek lands, so only a range that ends above it reads it.
  it should "reach the leaf at the highest path of an even length" taggedAs UnitTest in {
    val s = new State
    val leaf = s.account(Seq(15, 15, 15, 15), LeafNode(nibbles(60, 15), accountBytes()))
    val third = s.account(Seq(15, 15, 15), branch(15 -> leaf))
    val second = s.account(Seq(15, 15), branch(15 -> third))
    val root = s.account(Seq(), branch(15 -> s.account(Seq(15), branch(15 -> second))))

    (1L to 10L).foreach(seed => s.sample(ByteString(root.hash), seed = seed).sampledAccountLeaves shouldBe 1)
  }

  it should "take every leaf of a range it read whole, however many a seek gives" taggedAs UnitTest in {
    val s = new State
    val seconds = (0 until 2).map { a =>
      val thirds = (0 until 3).map { b =>
        val leaves = (0 until 4).map { c =>
          c -> s.account(Seq(1, a, b, c), LeafNode(nibbles(60, 9), accountBytes()))
        }
        b -> s.account(Seq(1, a, b), branch(leaves*))
      }
      a -> s.account(Seq(1, a), branch(thirds*))
    }
    val root = s.account(Seq(), branch(1 -> s.account(Seq(1), branch(seconds*))))

    (1L to 10L).foreach(seed => s.sample(ByteString(root.hash), seed = seed).sampledAccountLeaves shouldBe 24)
  }

  it should "count a storage leaf the state root has no place for as stale, and not fail" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    // Another root's: account 5 is in no trie this root has.
    s.storage(owner(5), Seq(4), LeafNode(nibbles(63, 4), ByteString(Array.fill[Byte](40)(9))))

    val result = s.sample(root)

    result.sampledStale shouldBe 1
    result.sampledMissing shouldBe 0
    result.sampledStorageRoots shouldBe 2
    result.sampledStorageRootsStale shouldBe 1
  }

  /** One account whose storage root is nowhere, over a leaf of the trie that root would be of. */
  private def stateOfATrieWithNoRoot(s: State): ByteString =
    val leaf = LeafNode(nibbles(62, 2), ByteString(Array.fill[Byte](40)(7)))
    val absentRoot = ByteString(kec256(Array[Byte](9, 9)))
    s.storage(owner(1), Seq(1, 2), leaf)
    val accountLeaf = LeafNode(nibbles(63, 1), accountBytes(Some(absentRoot)))
    s.account(Seq(1), accountLeaf)
    ByteString(s.account(Seq(), branch(1 -> accountLeaf)).hash)

  it should "stop a hand-off over a storage root that is not where its account names it" taggedAs UnitTest in {
    val s = new State
    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(stateOfATrieWithNoRoot(s))
    thrown.getMessage should include("1 of 1 sampled storage tries have no root")
    thrown.getMessage should include("left no account out")
    // B-304: what stopped it, and what was counted, go with it to the parent
    thrown.reason shouldBe PathHandoffSample.StopReason.StorageHole
    thrown.counts.sampledStorageRoots shouldBe 1
    thrown.counts.sampledStorageRootsMissing shouldBe 1
  }

  it should "count it, and let the hand-off go on, when the sync left accounts out" taggedAs UnitTest in {
    val s = new State

    val result = s.sample(stateOfATrieWithNoRoot(s), accountsLeftOut = true)

    result.sampledMissing shouldBe 1
    result.sampledStorageRootsMissing shouldBe 1
    result.sampledAccountLeaves shouldBe 1
    result.sampledAccountMisses shouldBe 0
  }

  // T196: the wrong row is found by the lookups that pass it, though no seek need land on it.
  it should "find a row inside a storage trie that is not the node its parent names, and stop the hand-off" taggedAs UnitTest in {
    val s = new State
    val (root, inner) = stateWithStorage(s)
    val wrong = BranchNode(inner.children.updated(9, HashNode(kec256(Array[Byte](4)))), None)
    wrong.hash should not be inner.hash
    s.storage(owner(1), toInner, wrong)

    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(root)
    thrown.getMessage should include("3 lookups of a storage leaf")
    thrown.reason shouldBe PathHandoffSample.StopReason.StorageHole
    thrown.counts.sampledMissing shouldBe 3

    val result = s.sample(root, accountsLeftOut = true)
    withClue("the two leaves under the wrong row, one of them twice: ")(result.sampledMissing shouldBe 3)
    withClue("the leaf that is not under it: ")(result.sampledLargeTrieLeaves shouldBe 1)
    result.sampledStorageRootsMissing shouldBe 0
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

    val result = s.sample(ByteString(root.hash), codePresent = _ == code)

    result.sampledCode shouldBe 2
    result.sampledCodeAbsent shouldBe 1
    result.sampledAccountLeaves shouldBe 3
  }

  it should "sample by first nibble, so a region can't be missed for being small" taggedAs UnitTest in {
    val s = new State
    val loneCode = ByteString(kec256(Array[Byte](1)))
    val lone = LeafNode(nibbles(63, 0), accountBytes(code = Some(loneCode)))
    s.account(Seq(0), lone)
    // Under slot 1, more leaves than one stratum holds.
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

    (1L to 10L).foreach { seed =>
      var asked = Set.empty[ByteString]
      val result = s.sample(
        ByteString(root.hash),
        codePresent = hash =>
          asked += hash
          true
        ,
        seed = seed
      )
      withClue("the one account in its region is always looked at: ")(asked should contain(loneCode))
      result.sampledAccountLeaves should be <= PathHandoffSample.AccountSamplePerNibble + 1
      withClue("the crowd is sampled, not read whole: ")(result.sampledAccountLeaves should be < 257)
      result.sampledAccountLeaves should be > 16
      result.sampledAccountMisses shouldBe 0
    }
  }

  it should "sample the paths of an even length and of an odd one" taggedAs UnitTest in {
    val s = new State
    val odd = LeafNode(nibbles(63, 1), accountBytes())
    val even = LeafNode(nibbles(62, 2), accountBytes())
    val middle = branch(2 -> even)
    s.account(Seq(1), odd)
    s.account(Seq(2, 2), even)
    s.account(Seq(2), middle)
    val root = s.account(Seq(), branch(1 -> odd, 2 -> middle))

    s.sample(ByteString(root.hash)).sampledAccountLeaves shouldBe 2
  }

  it should "look at an account trie that is one leaf, at the root's own row" taggedAs UnitTest in {
    val s = new State
    val root = s.account(Seq(), LeafNode(nibbles(64, 3), accountBytes()))
    val result = s.sample(ByteString(root.hash))
    result.sampledAccountLeaves shouldBe 1
    result.sampledAccountMisses shouldBe 0
  }

  // A throw here ends the sync's hand-off, over what the storage strata call harmless.
  it should "count an account leaf the state root has no place for, and go on: a stale leaf must not restart SNAP" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    s.account(Seq(7), LeafNode(nibbles(63, 7), accountBytes()))
    val result = s.sample(root)
    result.sampledAccountLeaves shouldBe 2
    result.sampledAccountMisses shouldBe 1
  }

  it should "pass over a leaf row whose path is no whole key: no lookup can be made of it" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    s.account(Seq(7), LeafNode(nibbles(62, 7), accountBytes()))
    s.storage(owner(1), Seq(9), LeafNode(nibbles(62, 9), ByteString(Array.fill[Byte](40)(9))))
    val result = s.sample(root)
    result.sampledAccountLeaves shouldBe 1
    result.sampledAccountMisses shouldBe 0
    result.sampledLargeTrieLeaves shouldBe 3
    result.sampledStale shouldBe 0
  }

  it should "stop a hand-off over one account's row that is not the node the root names" taggedAs UnitTest in {
    val s = new State
    val leaf = LeafNode(nibbles(63, 1), accountBytes())
    val elsewhere = LeafNode(nibbles(63, 2), accountBytes())
    val root = s.account(Seq(), branch(1 -> leaf, 2 -> elsewhere))
    // Its row holds another node: a lookup from the root meets it and stops.
    s.account(Seq(1), LeafNode(nibbles(63, 1), accountBytes(code = Some(ByteString(kec256(Array[Byte](3)))))))
    s.account(Seq(2), elsewhere)

    Seq(false, true).foreach { leftOut =>
      val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(ByteString(root.hash), accountsLeftOut = leftOut)
      thrown.getMessage should include("1 lookups")
      thrown.reason shouldBe PathHandoffSample.StopReason.AccountHole
      thrown.getMessage should include("a row of the account trie that is absent or is another node")
    }
  }

  it should "stop a hand-off over an account's row that is absent, met on the way to its storage" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    s.paths.deleteAccountNode(Array[Byte](1))

    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(root, accountsLeftOut = true)
    thrown.getMessage should include("a row of the account trie")
    thrown.reason shouldBe PathHandoffSample.StopReason.AccountHole
  }

  /** `count` leaves in the rows at accounts no root has. */
  private def staleLeaves(s: State, count: Int): Unit =
    (0 until count).foreach { i =>
      s.account(Seq(7, i % 16, i / 16), LeafNode(nibbles(61, 7), accountBytes()))
    }

  it should "go on with 15 misses, however few leaves there are" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    staleLeaves(s, 15)
    s.sample(root).sampledAccountMisses shouldBe 15
  }

  it should "fail loudly at a rate that says the rows are not this root's" taggedAs UnitTest in {
    val s = new State
    val (root, _) = stateWithStorage(s)
    staleLeaves(s, 16)
    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(root)
    thrown.getMessage should include("16 of 17")
    thrown.reason shouldBe PathHandoffSample.StopReason.AccountMisses
    thrown.counts.sampledAccountLeaves shouldBe 17
    thrown.counts.sampledAccountMisses shouldBe 16
  }

  /** `count` accounts at the root, each naming a storage root that its trie's root row is not. */
  private def stateOfBrokenTries(s: State, count: Int): ByteString =
    val leaves = (0 until count).map { i =>
      s.storage(owner(i), Seq(), LeafNode(nibbles(64, 5), ByteString(Array.fill[Byte](40)(i.toByte))))
      val leaf = LeafNode(nibbles(63, i), accountBytes(Some(ByteString(kec256(Array[Byte](7, i.toByte))))))
      s.account(Seq(i), leaf)
      i -> leaf
    }
    ByteString(s.account(Seq(), branch(leaves*)).hash)

  it should "go on with 15 storage tries whose root is not the one their account names, on a sync that left accounts out" taggedAs UnitTest in {
    val s = new State
    val result = s.sample(stateOfBrokenTries(s, 15), accountsLeftOut = true)
    result.sampledStorageRoots shouldBe 15
    result.sampledStorageRootsMissing shouldBe 15
  }

  it should "fail loudly when 16 are, and more than 1% of those sampled: more than a sync leaves out" taggedAs UnitTest in {
    val s = new State
    val root = stateOfBrokenTries(s, 16)
    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(root, accountsLeftOut = true)
    thrown.getMessage should include("16 of 16 sampled storage tries")
    thrown.reason shouldBe PathHandoffSample.StopReason.StorageRootMisses
    thrown.counts.sampledStorageRootsMissing shouldBe 16
    thrown.getMessage should include("more than the accounts a sync leaves out")
  }

  "tooManyMisses" should "want more than 1% of the sample, and at least 16" taggedAs UnitTest in {
    PathHandoffSample.tooManyMisses(15, 16) shouldBe false
    PathHandoffSample.tooManyMisses(16, 16) shouldBe true
    withClue("exactly 1%: ")(PathHandoffSample.tooManyMisses(16, 1600) shouldBe false)
    PathHandoffSample.tooManyMisses(17, 1600) shouldBe true
    PathHandoffSample.tooManyMisses(16, 1599) shouldBe true
    PathHandoffSample.tooManyMisses(0, 0) shouldBe false
  }

  "The hand-off's sample of a root that is not there" should "fail loudly, whatever the rate" taggedAs UnitTest in {
    val s = new State
    stateWithStorage(s)
    val thrown = the[PathHandoffSample.Stopped] thrownBy s.sample(ByteString(kec256(Array[Byte](1, 2, 3))))
    thrown.reason shouldBe PathHandoffSample.StopReason.RootAbsent
  }

  "The hand-off's sample of rows that are not there at all" should "fail on the root, and not find an empty state" taggedAs UnitTest in {
    val s = new State
    an[IllegalStateException] should be thrownBy s.sample(ByteString(kec256(Array[Byte](1))))
  }

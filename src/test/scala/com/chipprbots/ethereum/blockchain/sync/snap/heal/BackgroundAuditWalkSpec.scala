package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState.*
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.*

/** The audit's walk, on its own (spec 022 T053, decisions 3, 4, 5, 10). What a position holds is answered from a real
  * basic-pruning store, against whichever root is best when the question is asked.
  */
class BackgroundAuditWalkSpec extends AnyFlatSpec with Matchers:

  private def accountAt(nibbles: Int*): AuditPosition =
    AuditPosition(None, ByteString(nibbles.map(_.toByte).toArray))

  private def storageAt(owner: Int, nibbles: Int*): AuditPosition =
    AuditPosition(Some(accountHash(owner)), ByteString(nibbles.map(_.toByte).toArray))

  private val absentHash: ByteString = ByteString(kec256(Array[Byte](9, 9, 9)))

  private def oneBatch(
      s: AuditTestState,
      frames: Seq[AuditFrame],
      head: AuditHead,
      filter: AuditCodeFilter = new AuditCodeFilter(100)
  ): AuditWalk.Step =
    AuditWalk.walkBatch(frames, head, s.reader, filter)

  /** A whole walk from `start`, the way the actor stacks it, and everything it found along the way. */
  private case class Walked(nodes: Long, accounts: Long, moved: Long, holes: Vector[AuditHole], unfollowable: Int)

  private def walkAll(
      s: AuditTestState,
      start: Vector[AuditFrame],
      head: AuditHead,
      batch: Int = AuditWalk.MaxBatch
  ): Walked =
    var stack = start
    var result = Walked(0, 0, 0, Vector.empty, 0)
    val filter = new AuditCodeFilter(1000)
    while stack.nonEmpty do
      val (frames, rest) = stack.splitAt(batch)
      val step = oneBatch(s, frames, head, filter = filter)
      step.codeRead.foreach(filter.markIfNew) // as the actor does, applying a step
      step.rootAbsent shouldBe false
      stack = step.pushes ++ rest
      result = result.copy(
        result.nodes + step.nodes,
        result.accounts + step.accounts,
        result.moved + step.moved,
        result.holes ++ step.holes,
        result.unfollowable + step.unfollowable.size
      )
    result

  private def fromRoot: Vector[AuditFrame] = Vector(AuditFrame(AuditPosition.AccountRoot, None))

  // ---- the account trie's arms ---------------------------------------------------------------------------------------

  /** An absent frame at [3] of an account trie, under the root `node` builds. */
  private def frameAt3(s: AuditTestState, head: AuditHead, frameHash: ByteString = absentHash): AuditWalk.Step =
    oneBatch(s, Seq(AuditFrame(accountAt(3), Some(frameHash))), head)

  "A position that moved" should "roll forward: a different node present there is pushed in its place" taggedAs UnitTest in {
    val s = new AuditTestState
    val present = s.put(storageLeaf(1))
    val head = s.setHead(10, s.put(branch(3 -> present)))

    val step = frameAt3(s, head)

    step.pushes shouldBe Vector(AuditFrame(accountAt(3), Some(present)))
    step.moved shouldBe 1
    step.holes shouldBe empty
  }

  "A position that is gone" should "be dropped at a null child" taggedAs UnitTest in {
    val s = new AuditTestState
    val head = s.setHead(10, s.put(branch(4 -> s.put(storageLeaf(1)))))
    val step = frameAt3(s, head)
    step.pushes shouldBe empty
    step.holes shouldBe empty
    step.moved shouldBe 1
  }

  it should "be dropped at an embedded child, which has nothing below it" taggedAs UnitTest in {
    val s = new AuditTestState
    val leaf = s.put(storageLeaf(1))
    val children = Array.fill[MptNode](16)(NullNode)
    children(3) = LeafNode(ByteString(Array[Byte](1)), ByteString(Array[Byte](7))) // short: embedded in its parent
    children(4) = com.chipprbots.ethereum.mpt.HashNode(leaf.toArray)
    val head = s.setHead(10, s.put(BranchNode(children, None)))

    val embedded = frameAt3(s, head)
    embedded.holes shouldBe empty
    embedded.pushes shouldBe empty
    embedded.moved shouldBe 1
  }

  "A real hole" should "be the same hash, absent, at the position" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = s.put(branch(3 -> absentHash))
    val head = s.setHead(10, root)
    val step = frameAt3(s, head)
    step.holes shouldBe Vector(AuditHole(absentHash, accountAt(3), root, 10, NodeFault.Absent))
    step.pushes shouldBe empty
    step.moved shouldBe 0
  }

  it should "be a different hash, absent, at the position: the current trie needs that one" taggedAs UnitTest in {
    val s = new AuditTestState
    val other = ByteString(kec256(Array[Byte](1, 1)))
    val root = s.put(branch(3 -> other))
    val step = frameAt3(s, s.setHead(10, root))
    step.holes shouldBe Vector(AuditHole(other, accountAt(3), root, 10, NodeFault.Absent))
  }

  it should "be the intermediate node that is absent on the way, with that node's own position" taggedAs UnitTest in {
    val s = new AuditTestState
    val intermediate = ByteString(kec256(Array[Byte](2, 2)))
    val root = s.put(branch(3 -> intermediate))
    val step = oneBatch(s, Seq(AuditFrame(accountAt(3, 7), Some(absentHash))), s.setHead(10, root))
    step.holes shouldBe Vector(AuditHole(intermediate, accountAt(3), root, 10, NodeFault.Absent))
  }

  "A node that arrives between the read and the re-resolve" should "go back on the stack, and is no move" taggedAs UnitTest in {
    val s = new AuditTestState
    val leaf = s.put(storageLeaf(1))
    val head = s.setHead(10, s.put(branch(3 -> leaf)))
    val firstReadMisses = new AuditReader:
      private var missed = false
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
        if !missed && hashes == Seq(leaf) then
          missed = true
          Seq(RawNodeRead.Absent)
        else s.reader.nodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = Nil
      def head(): Option[AuditHead] = s.reader.head()

    val step = AuditWalk.walkBatch(
      Seq(AuditFrame(accountAt(3), Some(leaf))),
      head,
      firstReadMisses,
      new AuditCodeFilter(10)
    )

    step.pushes shouldBe Vector(AuditFrame(accountAt(3), Some(leaf)))
    step.moved shouldBe 0
    step.holes shouldBe empty
  }

  // ---- the storage trie's arms ---------------------------------------------------------------------------------------

  /** Account 1 (key 64 ones), its leaf under slot 1 of the root, over the storage root given. */
  private def withAccount(s: AuditTestState, storageRoot: Option[ByteString]): ByteString =
    s.put(branch(1 -> s.put(accountLeaf(1, accountBytes(storageRoot)))))

  "A storage position" should "roll forward to what the account's current storage trie holds there" taggedAs UnitTest in {
    val s = new AuditTestState
    val present = s.put(storageLeaf(1))
    val head = s.setHead(10, withAccount(s, Some(s.put(branch(2 -> present)))))

    val step = oneBatch(s, Seq(AuditFrame(storageAt(1, 2), Some(absentHash))), head)

    step.pushes shouldBe Vector(AuditFrame(storageAt(1, 2), Some(present)))
    step.moved shouldBe 1
    step.holes shouldBe empty
  }

  it should "roll a storage root forward to the account's new root" taggedAs UnitTest in {
    val s = new AuditTestState
    val newRoot = s.put(branch(2 -> s.put(storageLeaf(1))))
    val head = s.setHead(10, withAccount(s, Some(newRoot)))

    val step = oneBatch(s, Seq(AuditFrame(AuditPosition.storageRootOf(accountHash(1)), Some(absentHash))), head)

    step.pushes shouldBe Vector(AuditFrame(AuditPosition.storageRootOf(accountHash(1)), Some(newRoot)))
    step.moved shouldBe 1
  }

  it should "be dropped when the account is gone: a null child, or a leaf for another key" taggedAs UnitTest in {
    val s = new AuditTestState
    val head = s.setHead(10, withAccount(s, Some(s.put(branch(2 -> s.put(storageLeaf(1)))))))
    Seq(storageAt(5, 2), storageAt(1, 2).copy(account = Some(ByteString(Array.fill[Byte](32)(0x10))))).foreach { at =>
      val step = oneBatch(s, Seq(AuditFrame(at, Some(absentHash))), head)
      step.pushes shouldBe empty
      step.holes shouldBe empty
      step.moved shouldBe 1
    }
  }

  it should "be dropped when the account's storage is empty now" taggedAs UnitTest in {
    val s = new AuditTestState
    val head = s.setHead(10, withAccount(s, None))
    val step = oneBatch(s, Seq(AuditFrame(storageAt(1, 2), Some(absentHash))), head)
    step.pushes shouldBe empty
    step.holes shouldBe empty
    step.moved shouldBe 1
  }

  it should "be a hole when its node is absent, with the pathset GetTrieNodes needs" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = withAccount(s, Some(s.put(branch(2 -> absentHash))))
    val step = oneBatch(s, Seq(AuditFrame(storageAt(1, 2), Some(absentHash))), s.setHead(10, root))
    step.holes shouldBe Vector(AuditHole(absentHash, storageAt(1, 2), root, 10, NodeFault.Absent))
    step.holes.head.position.pathset shouldBe
      Seq(accountHash(1), ByteString(HexPrefix.encode(Array[Byte](2), isLeaf = false)))
  }

  it should "be a hole at the storage trie's intermediate node" taggedAs UnitTest in {
    val s = new AuditTestState
    val intermediate = ByteString(kec256(Array[Byte](3, 3)))
    val root = withAccount(s, Some(s.put(branch(2 -> intermediate))))
    val step = oneBatch(s, Seq(AuditFrame(storageAt(1, 2, 5), Some(absentHash))), s.setHead(10, root))
    step.holes shouldBe Vector(AuditHole(intermediate, storageAt(1, 2), root, 10, NodeFault.Absent))
  }

  it should "be a hole at the storage root when that is what is absent" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = withAccount(s, Some(absentHash))
    val step = oneBatch(s, Seq(AuditFrame(storageAt(1, 2), None)), s.setHead(10, root))
    step.holes shouldBe Vector(
      AuditHole(absentHash, AuditPosition.storageRootOf(accountHash(1)), root, 10, NodeFault.Absent)
    )
  }

  it should "be a hole in the account trie when that is where the way is broken" taggedAs UnitTest in {
    val s = new AuditTestState
    val intermediate = ByteString(kec256(Array[Byte](4, 4)))
    val root = s.put(branch(1 -> intermediate))
    val step = oneBatch(s, Seq(AuditFrame(storageAt(1, 2), Some(absentHash))), s.setHead(10, root))
    step.holes shouldBe Vector(AuditHole(intermediate, accountAt(1), root, 10, NodeFault.Absent))
  }

  // ---- the root, and the horizon --------------------------------------------------------------------------------------

  "An absent head root" should "be no hole: the walk waits, with the frame kept" taggedAs UnitTest in {
    val s = new AuditTestState
    val head = s.setHead(10, absentHash)
    val frame = AuditFrame(accountAt(3), Some(ByteString(kec256(Array[Byte](5)))))
    val step = oneBatch(s, Seq(frame), head)
    step.rootAbsent shouldBe true
    step.holes shouldBe empty
    step.pushes shouldBe Vector(frame)
    val onStorage = AuditFrame(storageAt(1, 2), None)
    oneBatch(s, Seq(onStorage), head).pushes shouldBe Vector(onStorage)
  }

  "An absent verdict" should "be asked again from the new best when the best has moved on" taggedAs UnitTest in {
    val s = new AuditTestState
    val present = s.put(storageLeaf(1))
    val old = s.setHead(10, s.put(branch(3 -> absentHash)))
    val moved = s.setHead(11, s.put(branch(3 -> present)))

    // The batch's head is the old one; a read after the verdict finds another root, however close.
    val step = oneBatch(s, Seq(AuditFrame(accountAt(3), Some(absentHash))), old)

    moved.number shouldBe 11
    step.holes shouldBe empty
    step.pushes shouldBe Vector(AuditFrame(accountAt(3), Some(present)))
  }

  it should "be asked again after a rewind" taggedAs UnitTest in {
    val s = new AuditTestState
    val present = s.put(storageLeaf(1))
    val old = s.setHead(10, s.put(branch(3 -> absentHash)))
    s.setHead(9, s.put(branch(3 -> present)))
    val step = oneBatch(s, Seq(AuditFrame(accountAt(3), Some(absentHash))), old)
    step.holes shouldBe empty
    step.pushes shouldBe Vector(AuditFrame(accountAt(3), Some(present)))
  }

  // ---- bad nodes -----------------------------------------------------------------------------------------------------

  /** A branch whose child the walk must not believe, stored under `hash` in place of the node that hashes to it. */
  private def badChildBearing(s: AuditTestState): (ByteString, ByteString, ByteString) =
    val hash = s.put(storageLeaf(1))
    val neverRead = ByteString(kec256(Array[Byte](6, 6)))
    val root = s.put(branch(3 -> hash))
    (hash, neverRead, root)

  "A node that fails its hash" should "be a candidate whose children are not believed" taggedAs UnitTest in {
    val s = new AuditTestState
    val (hash, neverRead, root) = badChildBearing(s)
    s.corrupt(hash, branch(0 -> neverRead).encode)

    val step = oneBatch(s, Seq(AuditFrame(accountAt(3), Some(hash))), s.setHead(10, root))

    step.holes shouldBe Vector(AuditHole(hash, accountAt(3), root, 10, NodeFault.BadKeccak))
    step.pushes shouldBe empty
    step.nodes shouldBe 0
  }

  "An entry that does not unwrap" should "be a candidate, and leave the rest of the batch alone" taggedAs UnitTest in {
    val s = new AuditTestState
    val (hash, _, root) = badChildBearing(s)
    val good = s.put(branch(1 -> absentHash))
    val rootWithBoth = s.put(branch(3 -> hash, 4 -> good))
    s.garble(hash)

    val step = oneBatch(
      s,
      Seq(AuditFrame(accountAt(3), Some(hash)), AuditFrame(accountAt(4), Some(good))),
      s.setHead(10, rootWithBoth)
    )

    step.holes shouldBe Vector(AuditHole(hash, accountAt(3), rootWithBoth, 10, NodeFault.Undecodable))
    step.nodes shouldBe 1
    step.pushes shouldBe Vector(AuditFrame(accountAt(4, 1), Some(absentHash)))
    root should not be empty
  }

  "Bytes that hash to the key and decode to nothing" should "be undecodable" taggedAs UnitTest in {
    val s = new AuditTestState
    val notANode = Array[Byte](0xc1.toByte, 0x80.toByte) // an RLP list of one empty string
    val hash = ByteString(kec256(notANode))
    s.nodes.put(hash, com.chipprbots.ethereum.db.storage.StoredNodeTestAccess.wrapped(notANode, 1))
    val root = s.put(branch(3 -> hash))
    val step = oneBatch(s, Seq(AuditFrame(accountAt(3), Some(hash))), s.setHead(10, root))
    step.holes shouldBe Vector(AuditHole(hash, accountAt(3), root, 10, NodeFault.Undecodable))
  }

  "A bad root" should "be a fetchable candidate, unlike an absent one" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = s.put(branch(3 -> absentHash))
    s.corrupt(root, branch(4 -> absentHash).encode)
    val step = oneBatch(s, fromRoot, s.setHead(10, root))
    step.rootAbsent shouldBe false
    step.holes shouldBe Vector(AuditHole(root, AuditPosition.AccountRoot, root, 10, NodeFault.BadKeccak))
  }

  // ---- account leaves ------------------------------------------------------------------------------------------------

  "An account leaf" should "push its storage root at the account's position" taggedAs UnitTest in {
    val s = new AuditTestState
    val storageRoot = s.put(storageLeaf(1))
    val leaf = s.put(accountLeaf(1, accountBytes(Some(storageRoot))))
    val step = oneBatch(s, Seq(AuditFrame(accountAt(1), Some(leaf))), s.setHead(10, s.put(branch(1 -> leaf))))
    step.accounts shouldBe 1
    step.pushes shouldBe Vector(AuditFrame(AuditPosition.storageRootOf(accountHash(1)), Some(storageRoot)))
    step.holes shouldBe empty
  }

  it should "have nothing below it with an empty storage root and no code" taggedAs UnitTest in {
    val s = new AuditTestState
    val leaf = s.put(accountLeaf(1, accountBytes()))
    val step = oneBatch(s, Seq(AuditFrame(accountAt(1), Some(leaf))), s.setHead(10, s.put(branch(1 -> leaf))))
    step.accounts shouldBe 1
    step.pushes shouldBe empty
    step.holes shouldBe empty
  }

  // decision 5: a storage root deduplicated by hash, then rolled forward at one account, loses the other's coverage.
  it should "be walked for each account, when two share a storage root" taggedAs UnitTest in {
    val s = new AuditTestState
    val shared = s.put(storageLeaf(1))
    val first = s.put(accountLeaf(1, accountBytes(Some(shared))))
    val second = s.put(accountLeaf(2, accountBytes(Some(shared))))
    val root = s.put(branch(1 -> first, 2 -> second))
    val walked = walkAll(s, fromRoot, s.setHead(10, root))
    walked.nodes shouldBe 5 // the root, two leaves, and the shared root twice
    walked.holes shouldBe empty
  }

  it should "report absent code by hash, at the account's position, and see it once across accounts" taggedAs UnitTest in {
    val s = new AuditTestState
    val absentCode = ByteString(kec256(Array[Byte](7, 7)))
    val first = s.put(accountLeaf(1, accountBytes(codeHash = Some(absentCode))))
    val second = s.put(accountLeaf(2, accountBytes(codeHash = Some(absentCode))))
    val root = s.put(branch(1 -> first, 2 -> second))
    val walked = walkAll(s, fromRoot, s.setHead(10, root))
    walked.holes shouldBe Vector(
      AuditHole(absentCode, AuditPosition(None, nibbles(64, 1)), root, 10, NodeFault.Absent, isCode = true)
    )
  }

  it should "report code that fails its hash, and pass code that does not" taggedAs UnitTest in {
    val s = new AuditTestState
    val goodBytes = ByteString(Array.fill[Byte](100)(1))
    val goodHash = ByteString(kec256(goodBytes.toArray))
    s.code.put(goodHash, goodBytes).commit()
    val badHash = ByteString(kec256(Array[Byte](8, 8)))
    s.code.put(badHash, goodBytes).commit()
    val first = s.put(accountLeaf(1, accountBytes(codeHash = Some(goodHash))))
    val second = s.put(accountLeaf(2, accountBytes(codeHash = Some(badHash))))
    val root = s.put(branch(1 -> first, 2 -> second))
    val walked = walkAll(s, fromRoot, s.setHead(10, root))
    walked.holes.map(h => (h.hash, h.fault, h.isCode)) shouldBe Vector((badHash, NodeFault.BadKeccak, true))
  }

  it should "say so when it cannot be followed, and not stop the walk" taggedAs UnitTest in {
    val s = new AuditTestState
    val junk = s.put(accountLeaf(1, Array.fill[Byte](40)(1))) // hashes right, and is no account
    val shortPath = s.put(LeafNode(nibbles(10, 2), ByteString(accountBytes()))) // at no account's position
    val fine = s.put(accountLeaf(3, accountBytes()))
    val root = s.put(branch(1 -> junk, 2 -> shortPath, 3 -> fine))
    val walked = walkAll(s, fromRoot, s.setHead(10, root))
    walked.unfollowable shouldBe 2
    walked.accounts shouldBe 1
    walked.holes shouldBe empty
  }

  // ---- whole walks ---------------------------------------------------------------------------------------------------

  /** Forty accounts, one in five with a storage trie, with a shared prefix so extensions turn up. */
  private def populated(s: AuditTestState): ByteString =
    val puts = (0 until 40).map { i =>
      val storage = Option.when(i % 5 == 0) {
        val slots = (0 until 30).map(j => trieKey(1000 * (i + 1) + j) -> longValue(j))
        s.update(None, 0, slots)
      }
      trieKey(i) -> accountBytes(storage)
    }
    s.update(None, 0, puts)

  "A walk of a whole state" should "read every stored node once, in batches of at most 256, and find nothing" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = populated(s)
    val expected = s.allNodes(root)

    val walked = walkAll(s, fromRoot, s.setHead(10, root), batch = 7)

    walked.nodes shouldBe expected.size
    walked.accounts shouldBe 40
    walked.holes shouldBe empty
    walked.moved shouldBe 0
    s.batchSizes.max should be <= AuditWalk.MaxBatch
  }

  it should "find a hole in a storage trie deep in the state, and only that" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = populated(s)
    val (position, hash) = s.allNodes(root).filter(_._1.isStorage).filter(_._1.nibbles.nonEmpty).last
    s.delete(hash)

    val walked = walkAll(s, fromRoot, s.setHead(10, root))

    walked.holes shouldBe Vector(AuditHole(hash, position, root, 10, NodeFault.Absent))
  }

  // Under basic pruning the walk's root is gone about 64 blocks on, and a pass takes days. So most "absent" is pruned.
  "A store pruned under the walk" should "give no false hole, and cover what the state rolled forward to" taggedAs UnitTest in {
    val s = new AuditTestState(history = 1)
    val rootA = populated(s)
    val headA = s.setHead(1, rootA)

    // Part of a pass, leaving a stack of positions read from the first root.
    val first = oneBatch(s, fromRoot, headA)
    val second = oneBatch(s, first.pushes, headA)
    val staleStack = second.pushes
    staleStack.size should be > 1

    // Then a few blocks change every account, each saved and pruned as import does. The old nodes go.
    val changed = (2 to 5).foldLeft(rootA) { (root, bn) =>
      val next = s.update(Some(root), bn, (0 until 40).map(i => trieKey(i) -> accountBytes(nonce = bn.toLong)))
      s.state.onBlockSave(bn, bn)(() => ())
      next
    }
    val headB = s.setHead(5, changed)
    withClue("this test needs the walk's old nodes gone: ") {
      staleStack.count(f => s.reader.nodes(f.hash.toSeq).head == RawNodeRead.Absent) should be > 0
    }

    val walked = walkAll(s, staleStack, headB)

    walked.holes shouldBe empty
    walked.moved should be > 0L
    // Everything the new root reaches below the two levels already read is covered.
    val belowFirstTwoLevels = s.allNodes(changed).filter(_._1.nibbles.length >= 2)
    walked.nodes should be >= belowFirstTwoLevels.size.toLong
    walked.accounts shouldBe 40
  }

  // ---- what a restart reads back --------------------------------------------------------------------------------------

  "A cursor with no hash" should "resolve from the current root: present, gone, or a hole" taggedAs UnitTest in {
    val s = new AuditTestState
    val leaf = s.put(storageLeaf(1))
    val root = s.put(branch(3 -> leaf, 4 -> absentHash))
    val step = oneBatch(
      s,
      Seq(AuditFrame(accountAt(3), None), AuditFrame(accountAt(5), None), AuditFrame(accountAt(4), None)),
      s.setHead(10, root)
    )
    step.pushes shouldBe Vector(AuditFrame(accountAt(3), Some(leaf)))
    step.moved shouldBe 1
    step.holes shouldBe Vector(AuditHole(absentHash, accountAt(4), root, 10, NodeFault.Absent))
  }

  "AuditCursors" should "read back what it wrote, odd and even paths, account and storage" taggedAs UnitTest in {
    val saved = AuditCursors.Saved(
      "12345",
      42L,
      AuditCounters(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
      Vector(
        AuditPosition.AccountRoot,
        accountAt(1),
        accountAt(0, 15, 3),
        storageAt(1),
        storageAt(2, 15, 0, 7, 9),
        AuditPosition(None, nibbles(64, 9))
      )
    )
    AuditCursors.decode(AuditCursors.encode(saved)) shouldBe Some(saved)
  }

  it should "take anything else for nothing: garbage, a version it does not know, or a short record" taggedAs UnitTest in {
    val bytes = AuditCursors.encode(AuditCursors.Saved("a", 1L, AuditCounters(), Vector(accountAt(1))))
    AuditCursors.decode(Array.empty) shouldBe None
    AuditCursors.decode(Array[Byte](1, 2, 3)) shouldBe None
    AuditCursors.decode(bytes.updated(0, 9.toByte)) shouldBe None
    AuditCursors.decode(bytes.dropRight(1)) shouldBe None
    AuditCursors.decode(bytes :+ 0.toByte) shouldBe None
  }

  // ---- the numbers ---------------------------------------------------------------------------------------------------

  "AuditThrottle" should "pause over 60 s behind and resume under 36 s" taggedAs UnitTest in {
    AuditThrottle.paused(wasPaused = false, 60) shouldBe false
    AuditThrottle.paused(wasPaused = false, 61) shouldBe true
    AuditThrottle.paused(wasPaused = true, 36) shouldBe true
    AuditThrottle.paused(wasPaused = true, 35) shouldBe false
  }

  it should "sleep three times the read for a duty cycle of 25%" taggedAs UnitTest in {
    AuditThrottle.delayAfter(10.millis.toNanos, 3) shouldBe 30.millis
  }

  "AuditCodeFilter" should "see a hash once until it falls out" taggedAs UnitTest in {
    val filter = new AuditCodeFilter(2)
    val hashes = (0 until 3).map(trieKey)
    filter.markIfNew(hashes(0)) shouldBe true
    filter.markIfNew(hashes(0)) shouldBe false
    filter.markIfNew(hashes(1)) shouldBe true
    filter.markIfNew(hashes(2)) shouldBe true // the first falls out
    filter.markIfNew(hashes(0)) shouldBe true
  }

  it should "be left as it was by a batch, which only asks: the actor marks what a step read" taggedAs UnitTest in {
    val s = new AuditTestState
    val code = ByteString(kec256(Array[Byte](7, 7)))
    val leaf = s.put(accountLeaf(1, accountBytes(codeHash = Some(code))))
    val filter = new AuditCodeFilter(10)
    val head = s.setHead(10, s.put(branch(1 -> leaf)))
    val step = oneBatch(s, Seq(AuditFrame(accountAt(1), Some(leaf))), head, filter = filter)
    step.codeRead shouldBe Vector(code)
    filter.contains(code) shouldBe false
  }

  "AuditCounters" should "call a pass clean only when it found nothing, whatever it moved past" taggedAs UnitTest in {
    AuditCounters(nodes = 5, moved = 9).clean shouldBe true
    AuditCounters(holes = 1, repaired = 1).clean shouldBe false
    AuditCounters(badNodes = 1).clean shouldBe false
    AuditCounters(unresolved = 1).clean shouldBe false
    AuditCounters(droppedAtCap = 1).clean shouldBe false
    AuditCounters(unfollowable = 1).clean shouldBe false
  }

  // ---- decision 15: what a delete rests on ----------------------------------------------------------------------------

  private def read(s: AuditTestState): ByteString => RawNodeRead = hash => s.reader.nodes(Seq(hash)).head

  "stillNeeds" should "be true for a bad entry the root reaches at that position" taggedAs UnitTest in {
    val s = new AuditTestState
    val hash = s.put(storageLeaf(1))
    val root = s.put(branch(3 -> hash))
    s.corrupt(hash, storageLeaf(2).encode)
    val hole = AuditHole(hash, accountAt(3), root, 10, NodeFault.BadKeccak)
    AuditWalk.stillNeeds(hole, root, read(s)) shouldBe true
  }

  it should "be false once the entry reads as good, or the root holds another hash there, or none" taggedAs UnitTest in {
    val s = new AuditTestState
    val hash = s.put(storageLeaf(1))
    val root = s.put(branch(3 -> hash))
    val hole = AuditHole(hash, accountAt(3), root, 10, NodeFault.BadKeccak)
    withClue("it is good here: ")(AuditWalk.stillNeeds(hole, root, read(s)) shouldBe false)

    s.corrupt(hash, storageLeaf(2).encode)
    val elsewhere = s.put(branch(4 -> hash))
    withClue("the position is gone: ")(AuditWalk.stillNeeds(hole, elsewhere, read(s)) shouldBe false)
    val other = s.put(branch(3 -> s.put(storageLeaf(3))))
    withClue("another node sits there: ")(AuditWalk.stillNeeds(hole, other, read(s)) shouldBe false)
  }

  it should "ask of code whether the account at that position still has that code hash" taggedAs UnitTest in {
    val s = new AuditTestState
    val codeHash = ByteString(kec256(Array[Byte](7, 7)))
    val root = s.put(branch(1 -> s.put(accountLeaf(1, accountBytes(codeHash = Some(codeHash))))))
    val hole = AuditHole(codeHash, AuditPosition(None, nibbles(64, 1)), root, 10, NodeFault.BadKeccak, isCode = true)
    AuditWalk.stillNeeds(hole, root, read(s)) shouldBe true
    val changed = s.put(branch(1 -> s.put(accountLeaf(1, accountBytes()))))
    AuditWalk.stillNeeds(hole, changed, read(s)) shouldBe false
    val gone = s.put(branch(2 -> s.put(accountLeaf(2, accountBytes(codeHash = Some(codeHash))))))
    AuditWalk.stillNeeds(hole, gone, read(s)) shouldBe false
  }

  private val backoff = 3600000L

  "AuditBadEntries" should "count a hash once per pass, and only after the backoff" taggedAs UnitTest in {
    val hash = trieKey(1)
    val (one, first) = AuditBadEntries().exhausted(hash, pass = 1, nowMillis = 0, backoff)
    first shouldBe Some(1)
    val (samePass, again) = one.exhausted(hash, pass = 1, nowMillis = 2 * backoff, backoff)
    again shouldBe Some(1)
    samePass shouldBe one
    val (tooSoon, early) = one.exhausted(hash, pass = 2, nowMillis = backoff - 1, backoff)
    withClue("a restart in the backoff starts the next pass at once: ")(early shouldBe Some(1))
    tooSoon shouldBe one
    val (_, second) = one.exhausted(hash, pass = 2, nowMillis = backoff, backoff)
    second shouldBe Some(2)
  }

  it should "keep a new hash out past the bound, and still count those it holds" taggedAs UnitTest in {
    val full = (0 until AuditBadEntries.MaxEntries).foldLeft(AuditBadEntries()) { (book, i) =>
      book.exhausted(trieKey(i), 1, 0, backoff)._1
    }
    full.entries.size shouldBe AuditBadEntries.MaxEntries
    full.exhausted(trieKey(AuditBadEntries.MaxEntries), 1, 0, backoff) shouldBe ((full, None))
    full.exhausted(trieKey(0), 2, backoff, backoff)._2 shouldBe Some(2)
    full.cleared(trieKey(0)).exhausted(trieKey(AuditBadEntries.MaxEntries), 1, 0, backoff)._2 shouldBe Some(1)
  }

  it should "read back what it wrote, and take anything else for nothing" taggedAs UnitTest in {
    val book = AuditBadEntries(
      7,
      Map(trieKey(1) -> AuditBadEntries.Entry(2, 6, 123L), trieKey(2) -> AuditBadEntries.Entry(1, 7, 456L))
    )
    AuditBadEntries.decode(book.encode) shouldBe Some(book)
    AuditBadEntries.decode(AuditBadEntries().encode) shouldBe Some(AuditBadEntries())
    AuditBadEntries.decode(book.encode.dropRight(1)) shouldBe None
    AuditBadEntries.decode(Array[Byte](9, 9)) shouldBe None
  }

  // ---- the review round: what hangs below a position that moved -----------------------------------------------------------

  "A position inside a new node" should "push an extension at its own position, whichever way the path leaves it" taggedAs UnitTest in {
    val s = new AuditTestState
    val under = s.put(branch(1 -> s.put(storageLeaf(1))))
    val inner = s.put(extension(Seq(3, 5), under))
    val root = s.put(branch(2 -> inner))
    val head = s.setHead(10, root)
    Seq(accountAt(2, 3), accountAt(2, 3, 6, 1), accountAt(2, 4)).foreach { at =>
      val step = oneBatch(s, Seq(AuditFrame(at, Some(absentHash))), head)
      withClue(at.describe) {
        step.pushes shouldBe Vector(AuditFrame(accountAt(2), Some(inner)))
        step.holes shouldBe empty
        step.moved shouldBe 1
      }
    }
  }

  it should "push an account leaf at its own position, so its storage trie and code are walked" taggedAs UnitTest in {
    val s = new AuditTestState
    val storageRoot = s.put(branch(2 -> s.put(storageLeaf(1))))
    val code = ByteString(kec256(Array[Byte](4, 4)))
    val leaf = s.put(accountLeaf(1, accountBytes(Some(storageRoot), Some(code))))
    val root = s.put(branch(1 -> leaf))
    val head = s.setHead(10, root)

    val stale = AuditFrame(accountAt(1, 1), Some(absentHash))
    oneBatch(s, Seq(stale), head).pushes shouldBe Vector(AuditFrame(accountAt(1), Some(leaf)))

    val walked = walkAll(s, Vector(stale), head)
    walked.accounts shouldBe 1
    walked.nodes shouldBe 3 // the leaf, and both nodes of its storage trie
    walked.holes.map(_.hash) shouldBe Vector(code)
  }

  it should "do the same in a storage trie, at the leaf and at the extension" taggedAs UnitTest in {
    val s = new AuditTestState
    val leaf = s.put(storageLeaf(1))
    val under = s.put(branch(1 -> s.put(storageLeaf(2))))
    val inner = s.put(extension(Seq(3, 5), under))
    val head = s.setHead(10, withAccount(s, Some(s.put(branch(2 -> leaf, 4 -> inner)))))

    val pastLeaf = oneBatch(s, Seq(AuditFrame(storageAt(1, 2, 9), Some(absentHash))), head)
    pastLeaf.pushes shouldBe Vector(AuditFrame(storageAt(1, 2), Some(leaf)))
    pastLeaf.moved shouldBe 1

    val insideExtension = oneBatch(s, Seq(AuditFrame(storageAt(1, 4, 3), Some(absentHash))), head)
    insideExtension.pushes shouldBe Vector(AuditFrame(storageAt(1, 4), Some(inner)))
    insideExtension.moved shouldBe 1
  }

  it should "push the node once, when several positions of a batch land on it" taggedAs UnitTest in {
    val s = new AuditTestState
    val inner = s.put(extension(Seq(3, 5), s.put(branch(1 -> s.put(storageLeaf(1))))))
    val head = s.setHead(10, s.put(branch(2 -> inner)))
    val step = oneBatch(
      s,
      Seq(accountAt(2, 3), accountAt(2, 4), accountAt(2, 5)).map(AuditFrame(_, Some(absentHash))),
      head
    )
    step.pushes shouldBe Vector(AuditFrame(accountAt(2), Some(inner)))
    step.moved shouldBe 3
  }

  // Only a null child is gone there. A storage position's way through the account trie is another matter: a leaf for
  // another key says this account is gone, and the other account's leaf has its own frame on the stack, or was read.
  "A branch that collapsed under a walk" should "lose nothing: an account's own frame reaches its storage, and a gone account's frames add nothing" taggedAs UnitTest in {
    val s = new AuditTestState
    val storageRoot = s.put(branch(4 -> s.put(storageLeaf(1))))
    val leaf = s.put(LeafNode(nibbles(63, 2), ByteString(accountBytes(Some(storageRoot)))))
    val head = s.setHead(10, s.put(branch(1 -> leaf)))
    val goneAccount = ByteString(HexPrefix.nibblesToBytes((ByteString(Array[Byte](1, 1)) ++ nibbles(62, 1)).toArray))
    val frames = Vector(
      AuditFrame(AuditPosition.storageRootOf(goneAccount), Some(absentHash)),
      AuditFrame(accountAt(1, 2), Some(absentHash))
    )

    val walked = walkAll(s, frames, head)

    walked.holes shouldBe empty
    walked.accounts shouldBe 1
    walked.nodes shouldBe 3 // once: the leaf, and its storage trie's two nodes
    walked.moved shouldBe 2
  }

  // ---- the review round: a head that changed under a verdict ----------------------------------------------------------------

  /** A reader whose `head()` answers from `heads`, the last again once they run out. */
  private def headsThen(s: AuditTestState, heads: AuditHead*): AuditReader =
    new AuditReader:
      private var left = heads.toList
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = s.reader.nodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = Nil
      def head(): Option[AuditHead] =
        val now = left.head
        if left.tail.nonEmpty then left = left.tail
        Some(now)

  "A hole verdict" should "not stand when the head changed at the same height: a reorg is no hole" taggedAs UnitTest in {
    val s = new AuditTestState
    val present = s.put(storageLeaf(1))
    val rootA = s.put(branch(3 -> absentHash))
    val rootB = s.put(branch(3 -> present))
    val step = AuditWalk.walkBatch(
      Seq(AuditFrame(accountAt(3), Some(absentHash))),
      AuditHead(10, rootA, 0),
      headsThen(s, AuditHead(10, rootB, 0)),
      new AuditCodeFilter(10)
    )
    step.holes shouldBe empty
    step.pushes shouldBe Vector(AuditFrame(accountAt(3), Some(present)))
  }

  it should "be pinned to the head it was finally read from" taggedAs UnitTest in {
    val s = new AuditTestState
    val rootA = s.put(branch(3 -> absentHash))
    val rootB = s.put(branch(3 -> absentHash, 4 -> s.put(storageLeaf(1))))
    val step = AuditWalk.walkBatch(
      Seq(AuditFrame(accountAt(3), Some(absentHash))),
      AuditHead(10, rootA, 0),
      headsThen(s, AuditHead(10, rootB, 0)),
      new AuditCodeFilter(10)
    )
    step.holes shouldBe Vector(AuditHole(absentHash, accountAt(3), rootB, 10, NodeFault.Absent))
  }

  it should "go back on the stack when the head changes on every try" taggedAs UnitTest in {
    val s = new AuditTestState
    val roots = (0 until 8).map(i => s.put(branch(3 -> absentHash, 4 -> s.put(storageLeaf(i)))))
    val frame = AuditFrame(accountAt(3), Some(absentHash))
    val step = AuditWalk.walkBatch(
      Seq(frame),
      AuditHead(10, roots(0), 0),
      headsThen(s, roots.tail.map(AuditHead(10, _, 0))*),
      new AuditCodeFilter(10)
    )
    step.holes shouldBe empty
    step.pushes shouldBe Vector(frame)
  }

  it should "go back on the stack when the head cannot be read" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = s.put(branch(3 -> absentHash))
    s.bestHead = None
    val frame = AuditFrame(accountAt(3), Some(absentHash))
    val step = oneBatch(s, Seq(frame), AuditHead(10, root, 0))
    step.holes shouldBe empty
    step.pushes shouldBe Vector(frame)
  }

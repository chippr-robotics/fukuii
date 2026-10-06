package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.testing.Tags.*

/** Proves the boundary argument behind resuming an account range from a mid-range cursor (hash scheme).
  *
  * A range is built in two StackTrie runs: a prefix run that is `suspend()`ed (emitted nodes kept, open spine dropped)
  * and a fresh suffix run started at the cursor, possibly against a DIFFERENT root (pivot change). The on-disk node set
  * is then healed against the target trie the way state healing does it: walk from the root, fetch a node only when it
  * is missing, never descend into a node that is present.
  *
  * The two properties that make that walk correct:
  *   1. present ⇒ complete: every target-trie node that is on disk has its whole target subtree on disk (so stopping at
  *      present nodes never leaves a hole); 2. after the walk, every node of the target trie is on disk, and only a
  *      handful (the seam) had to be fetched.
  */
class SnapResumeBoundarySpec extends AnyFlatSpec with Matchers:

  private def key(i: Int): Array[Byte] = kec256(BigInt(i).toByteArray)
  private def value(i: Int, salt: Int): Array[Byte] = kec256(BigInt(i * 7 + salt).toByteArray) ++ kec256(
    BigInt(i).toByteArray
  )

  private def sorted(keys: Seq[Array[Byte]]): Seq[Array[Byte]] =
    keys.sortWith((a, b) => java.util.Arrays.compareUnsigned(a, b) < 0)

  private def build(
      pairs: Seq[(Array[Byte], Array[Byte])],
      into: mutable.Map[ByteString, Array[Byte]],
      finish: SnapHashTrie => Unit
  ): Unit =
    // Small batch threshold so intermediate flushes happen too.
    val t = new SnapHashTrie(batch => batch.foreach { case (h, b) => into.update(h, b) }, batchSizeThreshold = 4096)
    pairs.foreach { case (k, v) => t.update(k, v) }
    finish(t)

  /** Hash references of a node (descending through inline children). */
  private def childRefs(blob: Array[Byte]): Seq[ByteString] =
    def refs(n: MptNode): Seq[ByteString] = n match
      case HashNode(h)      => Seq(ByteString(h))
      case b: BranchNode    => b.children.toSeq.flatMap(refs)
      case e: ExtensionNode => refs(e.next)
      case _                => Seq.empty
    MptTraversals.decodeNode(blob) match
      case b: BranchNode    => b.children.toSeq.flatMap(refs)
      case e: ExtensionNode => refs(e.next)
      case _                => Seq.empty

  private def descendants(target: Map[ByteString, Array[Byte]], h: ByteString): Set[ByteString] =
    val out = mutable.Set.empty[ByteString]
    val stack = mutable.Stack(h)
    while stack.nonEmpty do
      val cur = stack.pop()
      target.get(cur).foreach(blob => childRefs(blob).foreach(c => if out.add(c) then stack.push(c)))
    out.toSet

  private def checkSeamHealing(prefixSalt: Int, targetSalt: Int, modifiedPrefixKeys: Set[Int]): Int =
    val n = 3000
    val keys = sorted((0 until n).map(key))
    val cursorIdx = 1234
    // Target trie (the final pivot root): the prefix accounts in `modifiedPrefixKeys` changed since the prefix run.
    def targetValue(i: Int): Array[Byte] = if modifiedPrefixKeys.contains(i) then value(i, targetSalt) else value(i, 0)
    val target = mutable.Map.empty[ByteString, Array[Byte]]
    var targetRoot = ByteString.empty
    build(keys.indices.map(i => keys(i) -> targetValue(i)), target, t => targetRoot = t.commit())

    // Disk: prefix run (old root values) suspended at the cursor; suffix run (target values) from the cursor.
    val disk = mutable.Map.empty[ByteString, Array[Byte]]
    def prefixValue(i: Int): Array[Byte] = if modifiedPrefixKeys.contains(i) then value(i, prefixSalt) else value(i, 0)
    build((0 until cursorIdx).map(i => keys(i) -> prefixValue(i)), disk, _.suspend())
    build((cursorIdx until n).map(i => keys(i) -> targetValue(i)), disk, _.commit())

    // Property 1: present ⇒ complete.
    val presentTargetNodes = disk.keySet.intersect(target.keySet)
    presentTargetNodes.foreach { h =>
      val missing = descendants(target.toMap, h) -- disk.keySet
      withClue(s"present node ${h.take(4)} has missing descendants: ")(missing shouldBe empty)
    }

    // Property 2: heal like TrieNodeHealing — fetch only missing nodes, never descend into present ones.
    var fetched = 0
    val queue = mutable.Queue(targetRoot)
    while queue.nonEmpty do
      val h = queue.dequeue()
      if !disk.contains(h) then
        val blob = target(h)
        disk.update(h, blob)
        fetched += 1
        queue.enqueueAll(childRefs(blob))
    (target.keySet -- disk.keySet) shouldBe empty
    fetched

  // ---- Path scheme: one node per path; healing checks keccak(node at path) == expected hash ----

  /** (childPath, childHash) pairs of a node stored at `path`. Only hashed children are stored separately. */
  private def childPaths(path: ByteString, blob: Array[Byte]): Seq[(ByteString, ByteString)] =
    MptTraversals.decodeNode(blob) match
      case b: BranchNode =>
        b.children.toSeq.zipWithIndex.collect { case (HashNode(h), i) => (path ++ ByteString(i.toByte), ByteString(h)) }
      case e: ExtensionNode =>
        e.next match
          case HashNode(h) => Seq((path ++ e.sharedKey, ByteString(h)))
          case _           => Seq.empty
      case _ => Seq.empty

  private def buildPath(
      pairs: Seq[(Array[Byte], Array[Byte])],
      into: mutable.Map[ByteString, Array[Byte]],
      skipLeft: Boolean,
      finish: SnapPathTrie => Unit
  ): Unit =
    val t = new SnapPathTrie(
      owner = ByteString.empty,
      skipLeftBoundary = skipLeft,
      writePath = (p, _, b) => into.update(ByteString(p), b),
      deleteExact = p => into.remove(ByteString(p))
    )
    pairs.foreach { case (k, v) => t.update(k, v) }
    finish(t)

  private def checkPathSeamHealing(
      modifiedPrefixKeys: Set[Int],
      cursorIdx: Int = 1234,
      staleOlderAttempt: Boolean = true
  ): Int =
    val n = 3000
    val keys = sorted((0 until n).map(key))
    def targetValue(i: Int): Array[Byte] = if modifiedPrefixKeys.contains(i) then value(i, 1) else value(i, 0)
    def prefixValue(i: Int): Array[Byte] = if modifiedPrefixKeys.contains(i) then value(i, 99) else value(i, 0)

    val target = mutable.Map.empty[ByteString, Array[Byte]]
    var targetRoot = ByteString.empty
    buildPath(keys.indices.map(i => keys(i) -> targetValue(i)), target, skipLeft = false, t => targetRoot = t.commit())

    // Disk starts with a complete trie from an even older attempt (stale nodes at many paths), then the prefix run
    // (suspended at the cursor) and the resumed suffix run (left boundary skipped) write over it.
    val disk = mutable.Map.empty[ByteString, Array[Byte]]
    if staleOlderAttempt then
      buildPath(keys.indices.map(i => keys(i) -> value(i, 5)), disk, skipLeft = false, _.commit())
    if cursorIdx == 0 then
      // No resume: a single run over the whole range (baseline).
      buildPath(keys.indices.map(i => keys(i) -> targetValue(i)), disk, skipLeft = false, _.commit())
    else
      buildPath((0 until cursorIdx).map(i => keys(i) -> prefixValue(i)), disk, skipLeft = false, _.suspend())
      buildPath((cursorIdx until n).map(i => keys(i) -> targetValue(i)), disk, skipLeft = true, _.commit())

    def presentAt(path: ByteString, hash: ByteString): Boolean =
      disk.get(path).exists(b => ByteString(kec256(b)) == hash)

    // Heal: fetch a node only when the node at its path is absent or has the wrong hash; never descend into a match.
    var fetched = 0
    val queue = mutable.Queue((ByteString.empty, targetRoot))
    while queue.nonEmpty do
      val (p, h) = queue.dequeue()
      if !presentAt(p, h) then
        val blob = target(p)
        disk.update(p, blob)
        fetched += 1
        queue.enqueueAll(childPaths(p, blob))

    // Every node of the target trie is now at its path with the right hash: walk the target from the root.
    val walk = mutable.Stack((ByteString.empty, targetRoot))
    var checked = 0
    while walk.nonEmpty do
      val (p, h) = walk.pop()
      withClue(s"path ${p.map(_.toInt).mkString(",")}: ")(presentAt(p, h) shouldBe true)
      checked += 1
      walk.pushAll(childPaths(p, disk(p)))
    checked shouldBe target.size
    fetched

  "A Path-scheme range resumed from a mid-range cursor" should "heal correctly over stale nodes from an older attempt" taggedAs UnitTest in {
    val fetched = checkPathSeamHealing(modifiedPrefixKeys = Set(3, 400, 800, 1000, 1233))
    fetched should be > 0
    fetched should be < 120
  }

  it should "heal correctly with an unchanged prefix and an empty disk (left boundary must stay skipped)" taggedAs UnitTest in {
    // Regression: SnapPathTrie used to disarm its left-boundary filter at the first non-boundary node, then wrote an
    // ancestor of the skipped first node whose content verified — a verified node with a missing child (hole).
    checkPathSeamHealing(Set.empty, staleOlderAttempt = false) should be > 0
  }

  it should "heal correctly with a changed prefix and an empty disk" taggedAs UnitTest in {
    checkPathSeamHealing(Set(3, 400, 800, 1000, 1233), staleOlderAttempt = false) should be > 0
  }

  "A Path-scheme range downloaded in one run" should "heal correctly over stale nodes from an older attempt (baseline)" taggedAs UnitTest in {
    checkPathSeamHealing(Set(3, 400, 800, 1000, 1233), cursorIdx = 0) should be >= 0
  }

  "A range resumed from a mid-range cursor" should "heal to the target root by fetching only the seam (same root)" taggedAs UnitTest in {
    val fetched = checkSeamHealing(prefixSalt = 0, targetSalt = 0, modifiedPrefixKeys = Set.empty)
    // The seam is the spine above the cursor: a few nodes, not the 3000-leaf range.
    fetched should be > 0
    fetched should be < 20
  }

  it should "heal correctly when the prefix was downloaded against an older root (pivot change)" taggedAs UnitTest in {
    // Five prefix accounts changed between the prefix run's root and the target root.
    val fetched = checkSeamHealing(prefixSalt = 99, targetSalt = 1, modifiedPrefixKeys = Set(3, 400, 800, 1000, 1233))
    // Healing re-fetches the seam plus the paths to the five changed leaves — still a tiny fraction of the range.
    fetched should be > 5
    fetched should be < 120
  }

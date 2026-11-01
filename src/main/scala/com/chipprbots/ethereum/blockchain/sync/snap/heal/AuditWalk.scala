package com.chipprbots.ethereum.blockchain.sync.snap.heal

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals

/** Where a trie node sits: in the account trie, or in the storage trie of `account` (its hash), `nibbles` from that
  * trie's root, one nibble per byte. The audit's coverage goes by position, so a node that changed under the walk is
  * looked up again from the current root and not lost.
  */
final case class AuditPosition(account: Option[ByteString], nibbles: ByteString):
  def isStorage: Boolean = account.isDefined

  def child(slot: Int): AuditPosition =
    val extended = new Array[Byte](nibbles.length + 1)
    nibbles.copyToArray(extended)
    extended(nibbles.length) = slot.toByte
    copy(nibbles = ByteString.fromArrayUnsafe(extended))

  def child(path: ByteString): AuditPosition =
    val extended = new Array[Byte](nibbles.length + path.length)
    nibbles.copyToArray(extended)
    path.copyToArray(extended, nibbles.length)
    copy(nibbles = ByteString.fromArrayUnsafe(extended))

  /** What `GetTrieNodes` asks for: `[path]`, or `[account hash, path]` in a storage trie, hex-prefix encoded. The same
    * arithmetic as `FrontierRebuildBfs`, which a test pins.
    */
  def pathset: Seq[ByteString] =
    val compact = ByteString(HexPrefix.encode(nibbles.toArray, isLeaf = false))
    account.fold(Seq(compact))(hash => Seq(hash, compact))

  /** For the logs: FR-013 wants each hole with its position. */
  def describe: String =
    val path = if nibbles.isEmpty then "<root>" else nibbles.map(n => Character.forDigit(n & 0xf, 16)).mkString
    account.fold(s"account trie path $path")(hash => s"storage trie of account ${AuditWalk.hexOf(hash)} path $path")

object AuditPosition:
  val AccountRoot: AuditPosition = AuditPosition(None, ByteString.empty)
  def storageRootOf(account: ByteString): AuditPosition = AuditPosition(Some(account), ByteString.empty)

/** One entry of the walk's stack: a position, and the hash it held when it was pushed. `None` is a position with no
  * hash yet, at the start of a pass or read back at a resume, resolved from the current root when it comes up.
  */
final case class AuditFrame(position: AuditPosition, hash: Option[ByteString])

/** What is wrong with what a store holds under a hash. */
enum NodeFault:
  case Absent

  /** The bytes do not hash to their key. */
  case BadKeccak

  /** The entry or the node in it does not decode. */
  case Undecodable

  def isBad: Boolean = this != Absent

  def describe: String = this match
    case Absent      => "is absent"
    case BadKeccak   => "fails its hash"
    case Undecodable => "does not decode"

/** The best block as the audit sees it. */
final case class AuditHead(number: BigInt, stateRoot: ByteString, timestampSeconds: Long)

/** A node or a code the state at `root` needs and this store cannot give. A trie node is asked for at this `root`, the
  * one it was resolved under, by this position. Code goes by hash; its position is its account's leaf.
  */
final case class AuditHole(
    hash: ByteString,
    position: AuditPosition,
    root: ByteString,
    rootNumber: BigInt,
    fault: NodeFault,
    isCode: Boolean = false
)

/** What the walk reads. None of it writes, and a store's [[nodes]] gives one result per entry whatever the others hold.
  */
trait AuditReader:
  def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead]
  def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]]
  def head(): Option[AuditHead]

/** A pass's counts. A pass that found anything is not clean, even if it fixed all of it: another pass has to look at
  * what the fix let it reach. Moved positions are not faults: that is the state moving under a walk that takes days.
  */
final case class AuditCounters(
    nodes: Long = 0,
    accounts: Long = 0,
    moved: Long = 0,
    holes: Long = 0,
    repaired: Long = 0,
    unresolved: Long = 0,
    badNodes: Long = 0,
    droppedAtCap: Long = 0,
    unfollowable: Long = 0,
    deleted: Long = 0,
    badOverBound: Long = 0,
    elapsedMillis: Long = 0
):
  def clean: Boolean = holes == 0 && badNodes == 0 && unresolved == 0 && droppedAtCap == 0 && unfollowable == 0

  def summary: String =
    s"nodes=$nodes accounts=$accounts moved=$moved holes=$holes repaired=$repaired unresolved=$unresolved " +
      s"badNodes=$badNodes deleted=$deleted droppedAtCap=$droppedAtCap unfollowable=$unfollowable " +
      s"elapsed=${elapsedMillis / 1000}s"

/** Code hashes a pass has looked at, oldest out first: a popular contract is read once rather than once per account,
  * and a hash that fell out is only read again. Code only: a storage root or a node can't be deduplicated, since a root
  * one account holds may roll away from it, and the position of the account skipped for it then goes unseen. One batch
  * runs at a time, and each hand-off goes through a Future, so this has one user at a time.
  *
  * A batch only asks. What it looked at is marked when its [[AuditWalk.Step]] is applied: a batch that fails goes round
  * again, and a mark it left would have that retry skip code nothing has read.
  */
final class AuditCodeFilter(capacity: Int):
  private val seen = mutable.LinkedHashSet.empty[ByteString]

  def contains(hash: ByteString): Boolean = seen.contains(hash)

  /** True the first time `hash` is seen, or the first time since it fell out. */
  def markIfNew(hash: ByteString): Boolean =
    val isNew = seen.add(hash)
    if isNew && seen.size > capacity then seen.remove(seen.head)
    isNew

/** How long the audit waits between batches and when it holds off import (spec 022 decision 6). */
object AuditThrottle:

  /** Paused while now minus the best header's timestamp is over this: import is catching up, and has the disk. */
  val PauseAboveSeconds: Long = 60L

  /** A paused audit resumes under this, so a chain's ordinary block-time wobble around the mark doesn't flip it. */
  val ResumeBelowSeconds: Long = 36L

  def paused(wasPaused: Boolean, bestAgeSeconds: Long): Boolean =
    if wasPaused then bestAgeSeconds >= ResumeBelowSeconds else bestAgeSeconds > PauseAboveSeconds

  /** A duty cycle that tunes itself to the disk: sleep `factor` times what the batch took (3 is 25%). */
  def delayAfter(readNanos: Long, factor: Int): FiniteDuration = (readNanos * factor).nanos

/** The background audit's walk (spec 022 T053, decision 4), with nothing in it but the reads: a batched depth-first
  * walk from a root, each node's keccak checked before its children are believed, and the re-resolve that tells a hole
  * from the state moving on. `BackgroundAudit` runs it and does all the writing. `FrontierRebuildBfs` is not reused
  * (decision 13): it emits to the coordinator, leases the BFS column family, and its queue writes over 10 GB at scale.
  */
object AuditWalk:

  /** `multiGetOptimizedNoFill` holds the read lock for a batch, and a queued writer stalls behind it. */
  val MaxBatch: Int = 256

  private[heal] val MaxResolveAttempts: Int = 3

  /** A hash for the logs, whole: FR-013's position is for finding the node again. */
  def hexOf(hash: ByteString): String = hash.toArray.map("%02x".format(_)).mkString

  // ---- one node ------------------------------------------------------------------------------------------------------

  /** A store's entry for `hash` as a node, keccak first: bad bytes are never decoded, and their children never
    * believed.
    */
  private[heal] def check(hash: ByteString, raw: RawNodeRead): Either[NodeFault, MptNode] = raw match
    case RawNodeRead.Absent      => Left(NodeFault.Absent)
    case RawNodeRead.Undecodable => Left(NodeFault.Undecodable)
    case RawNodeRead.Bytes(encoded) =>
      if !java.util.Arrays.equals(kec256(encoded), hash.toArray) then Left(NodeFault.BadKeccak)
      else
        try Right(MptTraversals.decodeNode(encoded))
        catch case NonFatal(_) => Left(NodeFault.Undecodable)

  /** What a good node at `position` points at. */
  final case class Expansion(
      children: Vector[AuditFrame] = Vector.empty,
      isAccount: Boolean = false,
      code: Option[(ByteString, AuditPosition)] = None,
      unfollowable: Option[String] = None
  )

  /** The nodes a hash reference names: an embedded child holds no hash (a 32-byte hash cannot fit in under 32 bytes),
    * so it has nothing below it to read. Children in slot order, which keeps the stack in key order.
    */
  def expand(node: MptNode, position: AuditPosition): Expansion = node match
    case branch: BranchNode =>
      val children = Vector.newBuilder[AuditFrame]
      var slot = 0
      while slot < BranchNode.numberOfChildren do
        branch.children(slot) match
          case HashNode(hash) => children += AuditFrame(position.child(slot), Some(ByteString(hash)))
          case _              =>
        slot += 1
      Expansion(children.result())
    case extension: ExtensionNode =>
      extension.next match
        case HashNode(hash) =>
          Expansion(Vector(AuditFrame(position.child(extension.sharedKey), Some(ByteString(hash)))))
        case _ => Expansion()
    case leaf: LeafNode if !position.isStorage => expandAccount(leaf, position)
    case _                                     => Expansion() // a storage value, or nothing

  private def expandAccount(leaf: LeafNode, position: AuditPosition): Expansion =
    val leafPosition = position.child(leaf.key)
    if leafPosition.nibbles.length != 64 then
      Expansion(unfollowable = Some(s"an account leaf at ${leafPosition.describe}, which is no account's position"))
    else
      Account(leaf.value) match
        case Failure(e) =>
          Expansion(unfollowable =
            Some(s"the account leaf at ${leafPosition.describe} does not decode: ${e.getMessage}")
          )
        case Success(account) =>
          val owner = ByteString(HexPrefix.nibblesToBytes(leafPosition.nibbles.toArray))
          val storage =
            if account.storageRoot == Account.EmptyStorageRootHash then Vector.empty
            else Vector(AuditFrame(AuditPosition.storageRootOf(owner), Some(account.storageRoot.value)))
          val code =
            if account.codeHash == Account.EmptyCodeHash then None else Some((account.codeHash.value, leafPosition))
          Expansion(storage, isAccount = true, code = code)

  // ---- re-resolve ----------------------------------------------------------------------------------------------------

  /** What a position holds in the trie at one root. */
  enum Resolved:

    /** A good node. */
    case Present(hash: ByteString)

    /** The position lies inside or past a leaf or an extension, as it does when a branch collapses. That trie has no
      * such position, and it has this node above it, whose storage trie, code or child the walk may never have read: so
      * this node is what to look at, at its own position.
      */
    case Moved(hash: ByteString, position: AuditPosition)

    /** A node that root needs, the position's or one on the way to it, is absent or bad. */
    case Hole(hash: ByteString, position: AuditPosition, fault: NodeFault)

    /** Nothing here: a null child, an embedded one (which has nothing below it), an account gone, or storage emptied.
      * An account gone says nothing of a leaf for another key found in its place: that account has its own frame on the
      * stack, or was read.
      */
    case Gone

    /** The root itself is not here. Not a hole: fork recovery rewinds. */
    case RootAbsent

  /** `position` from `root`: account trie, then the account's leaf, then its storage root, then the storage trie. */
  def resolve(position: AuditPosition, root: ByteString, read: ByteString => RawNodeRead): Resolved =
    check(root, read(root)) match
      case Left(NodeFault.Absent) => Resolved.RootAbsent
      case Left(fault)            => Resolved.Hole(root, AuditPosition.AccountRoot, fault)
      case Right(rootNode) =>
        position.account match
          case None => descend(root, rootNode, AuditPosition.AccountRoot, position.nibbles, read)
          case Some(account) =>
            val accountNibbles = ByteString.fromArrayUnsafe(HexPrefix.bytesToNibbles(account.toArray))
            descendToLeaf(rootNode, AuditPosition.AccountRoot, accountNibbles, read) match
              case Left(resolved) => resolved
              case Right(leaf) =>
                Account(leaf.value) match
                  case Failure(_)                                                          => Resolved.Gone
                  case Success(found) if found.storageRoot == Account.EmptyStorageRootHash => Resolved.Gone
                  case Success(found) =>
                    val storageRoot = found.storageRoot.value
                    val rootPosition = AuditPosition.storageRootOf(account)
                    check(storageRoot, read(storageRoot)) match
                      case Left(fault) => Resolved.Hole(storageRoot, rootPosition, fault)
                      case Right(node) => descend(storageRoot, node, rootPosition, position.nibbles, read)

  @tailrec
  private def descend(
      hash: ByteString,
      node: MptNode,
      at: AuditPosition,
      remaining: ByteString,
      read: ByteString => RawNodeRead
  ): Resolved =
    if remaining.isEmpty then Resolved.Present(hash)
    else
      step(node, at, remaining) match
        case None =>
          node match
            case _: LeafNode | _: ExtensionNode => Resolved.Moved(hash, at)
            case _                              => Resolved.Gone
        case Some((next, nextAt, nextRemaining)) =>
          check(next, read(next)) match
            case Left(fault)     => Resolved.Hole(next, nextAt, fault)
            case Right(nextNode) => descend(next, nextNode, nextAt, nextRemaining, read)

  /** On to the leaf that holds the whole of `remaining`. `Left` when there is none: gone, or a hole on the way. */
  @tailrec
  private def descendToLeaf(
      node: MptNode,
      at: AuditPosition,
      remaining: ByteString,
      read: ByteString => RawNodeRead
  ): Either[Resolved, LeafNode] =
    node match
      case leaf: LeafNode => if leaf.key == remaining then Right(leaf) else Left(Resolved.Gone)
      case _ =>
        step(node, at, remaining) match
          case None => Left(Resolved.Gone)
          case Some((next, nextAt, nextRemaining)) =>
            check(next, read(next)) match
              case Left(fault)     => Left(Resolved.Hole(next, nextAt, fault))
              case Right(nextNode) => descendToLeaf(nextNode, nextAt, nextRemaining, read)

  /** One hash reference on the way along `remaining`, if `node` has one there. */
  private def step(
      node: MptNode,
      at: AuditPosition,
      remaining: ByteString
  ): Option[(ByteString, AuditPosition, ByteString)] = node match
    case branch: BranchNode if remaining.nonEmpty =>
      val slot = remaining.head & 0xf
      branch.children(slot) match
        case HashNode(hash) => Some((ByteString(hash), at.child(slot), remaining.drop(1)))
        case _              => None // null or embedded: no stored node sits at or below this position
    case extension: ExtensionNode if remaining.startsWith(extension.sharedKey) =>
      extension.next match
        case HashNode(hash) =>
          Some((ByteString(hash), at.child(extension.sharedKey), remaining.drop(extension.sharedKey.length)))
        case _ => None
    case _ => None // a leaf, or an extension the path leaves, or a position inside one

  /** What the audit does with a position it could not read, or has no hash for. */
  enum Settled:
    case Present(hash: ByteString)
    case Moved(hash: ByteString, position: AuditPosition)
    case Hole(hole: AuditHole)
    case Gone
    case RootAbsent

    /** The head keeps changing under the resolve, or cannot be read. Nothing is known: it goes back on the stack. */
    case Retry

  /** Decision 4's arms, from the current best root. A hole or an absent root is believed only if the head's state root
    * is the same after the resolve as before it: then no block was saved under it and nothing it reaches was pruned. A
    * head that changed, however far, at the same height in a reorg too, means the answer comes from the new head, up to
    * [[MaxResolveAttempts]] tries, and then nothing is known and the frame goes back on the stack. So is it if the head
    * cannot be read.
    */
  def settle(
      position: AuditPosition,
      from: AuditHead,
      read: ByteString => RawNodeRead,
      latestHead: () => Option[AuditHead]
  ): Settled =
    @tailrec def attempt(head: AuditHead, triesLeft: Int): Settled =
      resolve(position, head.stateRoot, read) match
        case Resolved.Present(hash)         => Settled.Present(hash)
        case Resolved.Moved(hash, position) => Settled.Moved(hash, position)
        case Resolved.Gone                  => Settled.Gone
        case unsure =>
          latestHead() match
            case None => Settled.Retry
            case Some(latest) if latest.stateRoot == head.stateRoot =>
              unsure match
                case Resolved.Hole(hash, at, fault) =>
                  Settled.Hole(AuditHole(hash, at, head.stateRoot, head.number, fault))
                case _ => Settled.RootAbsent
            case Some(latest) => if triesLeft > 1 then attempt(latest, triesLeft - 1) else Settled.Retry
    attempt(from, MaxResolveAttempts)

  /** Decision 15's (b): does the trie at `root` still need `hole.hash` where `hole` says, and is it still bad there? */
  def stillNeeds(hole: AuditHole, root: ByteString, read: ByteString => RawNodeRead): Boolean =
    if hole.isCode then
      check(root, read(root)).toOption
        .flatMap(rootNode => descendToLeaf(rootNode, AuditPosition.AccountRoot, hole.position.nibbles, read).toOption)
        .flatMap(leaf => Account(leaf.value).toOption)
        .exists(_.codeHash.value == hole.hash)
    else
      resolve(hole.position, root, read) match
        case Resolved.Hole(hash, position, fault) => hash == hole.hash && position == hole.position && fault.isBad
        case _                                    => false

  // ---- one batch -----------------------------------------------------------------------------------------------------

  /** What one batch found. `pushes` go back on the stack in walk order, first to be read first. `codeRead` is for the
    * code filter, once this step is applied.
    */
  final case class Step(
      pushes: Vector[AuditFrame] = Vector.empty,
      holes: Vector[AuditHole] = Vector.empty,
      unfollowable: Vector[String] = Vector.empty,
      codeRead: Vector[ByteString] = Vector.empty,
      nodes: Long = 0,
      accounts: Long = 0,
      moved: Long = 0,
      rootAbsent: Boolean = false
  )

  /** One batch of at most [[MaxBatch]] frames, against the store and the head: every node keccak-checked and decoded in
    * its own right, children and storage roots pushed, code looked at, and whatever could not be read re-resolved.
    * Reads only. One frame's fault touches no other frame's.
    */
  def walkBatch(
      frames: Seq[AuditFrame],
      head: AuditHead,
      reader: AuditReader,
      codeFilter: AuditCodeFilter
  ): Step =
    val hashes = frames.flatMap(_.hash).distinct
    val reads = if hashes.isEmpty then Map.empty[ByteString, RawNodeRead] else hashes.zip(reader.nodes(hashes)).toMap
    // A resolve walks from the root, and a batch's resolves share the top of the trie: good nodes are read once. Only
    // good ones: an absent verdict has to be read again from a new root.
    val known = mutable.HashMap.empty[ByteString, RawNodeRead]
    def readOnce(hash: ByteString): RawNodeRead =
      known.getOrElse(
        hash, {
          val raw = reader.nodes(Seq(hash)).head
          if check(hash, raw).isRight then known(hash) = raw
          raw
        }
      )

    val pushes = Vector.newBuilder[AuditFrame]
    val holes = Vector.newBuilder[AuditHole]
    val unfollowable = Vector.newBuilder[String]
    val wantedCode = mutable.LinkedHashMap.empty[ByteString, AuditPosition]
    var nodes = 0L
    var accounts = 0L
    var moved = 0L
    var rootAbsent = false
    // Siblings under a branch that collapsed land on one node, which is one walk of what is below it.
    val movedHere = mutable.Set.empty[AuditFrame]

    def settleFrame(frame: AuditFrame): Unit =
      settle(frame.position, head, readOnce, () => reader.head()) match
        case Settled.Present(hash) =>
          if frame.hash.exists(_ != hash) then moved += 1
          pushes += AuditFrame(frame.position, Some(hash))
        case Settled.Moved(hash, position) =>
          moved += 1
          val found = AuditFrame(position, Some(hash))
          if movedHere.add(found) then pushes += found
        case Settled.Hole(hole) => holes += hole
        case Settled.Gone       => moved += 1
        case Settled.RootAbsent =>
          rootAbsent = true
          pushes += frame
        case Settled.Retry => pushes += frame

    frames.foreach { frame =>
      frame.hash match
        case None => settleFrame(frame)
        case Some(hash) =>
          check(hash, reads(hash)) match
            case Left(_) => settleFrame(frame)
            case Right(node) =>
              nodes += 1
              val found = expand(node, frame.position)
              if found.isAccount then accounts += 1
              pushes ++= found.children
              found.unfollowable.foreach(unfollowable += _)
              found.code.foreach { case (codeHash, at) =>
                if !codeFilter.contains(codeHash) && !wantedCode.contains(codeHash) then wantedCode(codeHash) = at
              }
    }

    if wantedCode.nonEmpty then
      val codeHashes = wantedCode.keys.toSeq
      codeHashes.zip(reader.codes(codeHashes)).foreach { case (codeHash, code) =>
        val fault = code match
          case None => Some(NodeFault.Absent)
          case Some(bytes) =>
            if java.util.Arrays.equals(kec256(bytes.toArray), codeHash.toArray) then None else Some(NodeFault.BadKeccak)
        fault.foreach { f =>
          holes += AuditHole(codeHash, wantedCode(codeHash), head.stateRoot, head.number, f, isCode = true)
        }
      }

    Step(
      pushes.result(),
      holes.result(),
      unfollowable.result(),
      wantedCode.keys.toVector,
      nodes,
      accounts,
      moved,
      rootAbsent
    )

/** Where a pass had got to, for a restart (spec 022 decision 8): positions with no hashes, each re-resolved from the
  * current root when it comes up, so a stale one is as good as a fresh one, and the pass's counts, so a pass that found
  * a hole before a restart does not end clean after it. The anchor is the pivot the pass was for: a later SNAP cycle's
  * pivot starts a pass of its own.
  */
object AuditCursors:

  final case class Saved(anchor: String, pass: Long, counters: AuditCounters, positions: Vector[AuditPosition])

  private val Version: Byte = 1
  private val StorageFlag: Byte = 1
  private val CounterFields: Int = 12

  def encode(saved: Saved): Array[Byte] =
    val anchor = saved.anchor.getBytes(StandardCharsets.UTF_8)
    val size = 1 + 4 + anchor.length + 8 + CounterFields * 8 + 4 +
      saved.positions.map(p => 2 + p.account.fold(0)(_.length) + packedLength(p.nibbles.length)).sum
    val out = ByteBuffer.allocate(size)
    out.put(Version).putInt(anchor.length).put(anchor).putLong(saved.pass)
    val c = saved.counters
    Seq(
      c.nodes,
      c.accounts,
      c.moved,
      c.holes,
      c.repaired,
      c.unresolved,
      c.badNodes,
      c.droppedAtCap,
      c.unfollowable,
      c.deleted,
      c.badOverBound,
      c.elapsedMillis
    )
      .foreach(v => out.putLong(v))
    out.putInt(saved.positions.size)
    saved.positions.foreach { p =>
      out.put(if p.isStorage then StorageFlag else 0.toByte)
      p.account.foreach(a => out.put(a.toArray))
      out.put(p.nibbles.length.toByte)
      out.put(pack(p.nibbles))
    }
    out.array()

  /** `None` for anything but a whole record of this version: the pass starts over, which costs time and nothing else.
    */
  def decode(bytes: Array[Byte]): Option[Saved] =
    try
      val in = ByteBuffer.wrap(bytes)
      if in.get() != Version then None
      else
        val anchor = new Array[Byte](in.getInt())
        in.get(anchor)
        val pass = in.getLong()
        val v = Vector.fill(CounterFields)(in.getLong())
        val counters = AuditCounters(v(0), v(1), v(2), v(3), v(4), v(5), v(6), v(7), v(8), v(9), v(10), v(11))
        val positions = Vector.fill(in.getInt()) {
          val isStorage = in.get() == StorageFlag
          val account = Option.when(isStorage) {
            val hash = new Array[Byte](32)
            in.get(hash)
            ByteString.fromArrayUnsafe(hash)
          }
          val count = in.get() & 0xff
          val packed = new Array[Byte](packedLength(count))
          in.get(packed)
          AuditPosition(account, unpack(packed, count))
        }
        Option.when(!in.hasRemaining)(Saved(new String(anchor, StandardCharsets.UTF_8), pass, counters, positions))
    catch case NonFatal(_) => None

  private def packedLength(nibbles: Int): Int = (nibbles + 1) / 2

  private def pack(nibbles: ByteString): Array[Byte] =
    val out = new Array[Byte](packedLength(nibbles.length))
    var i = 0
    while i < nibbles.length do
      val shift = if i % 2 == 0 then 4 else 0
      out(i / 2) = (out(i / 2) | ((nibbles(i) & 0xf) << shift)).toByte
      i += 1
    out

  private def unpack(packed: Array[Byte], count: Int): ByteString =
    val out = new Array[Byte](count)
    var i = 0
    while i < count do
      val shift = if i % 2 == 0 then 4 else 0
      out(i) = ((packed(i / 2) >> shift) & 0xf).toByte
      i += 1
    ByteString.fromArrayUnsafe(out)

/** Which bad entries peers could not serve, and in how many passes (spec 022 decision 15), so a delete can wait for
  * three and a restart does not start the count again. It outlives a pass, and it is bounded: an entry past the bound
  * is counted and never deleted. It also holds the last pass's number, which is what makes two exhausts separate
  * passes.
  */
final case class AuditBadEntries(lastPass: Long = 0L, entries: Map[ByteString, AuditBadEntries.Entry] = Map.empty):
  import AuditBadEntries.*

  /** A fetch of `hash` exhausted in `pass`, at `nowMillis`. It counts once per pass, and only once the backoff has
    * passed since the last one that counted: a restart in a backoff starts the next pass at once. `None` when the bound
    * keeps it out.
    */
  def exhausted(hash: ByteString, pass: Long, nowMillis: Long, backoffMillis: Long): (AuditBadEntries, Option[Int]) =
    entries.get(hash) match
      case Some(e) if e.lastPass == pass || nowMillis - e.lastMillis < backoffMillis => (this, Some(e.passes))
      case Some(e) =>
        val counted = e.copy(passes = e.passes + 1, lastPass = pass, lastMillis = nowMillis)
        (copy(entries = entries + (hash -> counted)), Some(counted.passes))
      case None if entries.size >= MaxEntries => (this, None)
      case None =>
        (copy(entries = entries + (hash -> Entry(1, pass, nowMillis))), Some(1))

  def cleared(hash: ByteString): AuditBadEntries = copy(entries = entries - hash)

  def encode: Array[Byte] =
    val out = ByteBuffer.allocate(1 + 8 + 4 + entries.size * (32 + 4 + 8 + 8))
    out.put(Version).putLong(lastPass).putInt(entries.size)
    entries.foreach { case (hash, e) =>
      out.put(hash.toArray).putInt(e.passes).putLong(e.lastPass).putLong(e.lastMillis)
    }
    out.array()

object AuditBadEntries:

  /** How many separate passes must exhaust a fetch before the entry goes. */
  val DeleteAfterPasses: Int = 3

  val MaxEntries: Int = 1024

  final case class Entry(passes: Int, lastPass: Long, lastMillis: Long)

  private val Version: Byte = 1

  /** `None` for anything but a whole record of this version: the counts start over, which only delays a delete. */
  def decode(bytes: Array[Byte]): Option[AuditBadEntries] =
    try
      val in = ByteBuffer.wrap(bytes)
      if in.get() != Version then None
      else
        val lastPass = in.getLong()
        val entries = Vector.fill(in.getInt()) {
          val hash = new Array[Byte](32)
          in.get(hash)
          ByteString.fromArrayUnsafe(hash) -> Entry(in.getInt(), in.getLong(), in.getLong())
        }
        Option.when(!in.hasRemaining)(AuditBadEntries(lastPass, entries.toMap))
    catch case NonFatal(_) => None

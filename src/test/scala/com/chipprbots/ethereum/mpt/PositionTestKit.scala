package com.chipprbots.ethereum.mpt

import java.util.Arrays

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Random

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.MptStorageDecorator
import com.chipprbots.ethereum.db.storage.MptStorageDecorator.Removals
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** The oracles of spec 018 Track C slice C1 (`specs/018-storage-engine-consolidation/baselines/oracle-tests.md`), as
  * test fixtures: a miniature position store, a walker over raw RLP that owes nothing to the code under test, and the
  * rules a block's change set follows.
  */
object PositionTestKit:

  /** A store key: `HexPrefix(path)`. A `ByteString`, so that maps of rows compare by content. */
  type RowKey = ByteString

  type Rows = Map[RowKey, ByteString]

  /** One position's change over a block: what the parent held there and what the block left there. */
  type Diff = Map[RowKey, (Option[ByteString], Option[ByteString])]

  def rowKey(nibbles: Int*): RowKey = ByteString(HexPrefix.encode(nibbles.map(_.toByte).toArray, isLeaf = false))

  /** A node has a row of its own when its encoding is at least 32 bytes, and the root has one at any size (spec 018
    * data-model 2). A smaller node is embedded in its parent.
    */
  def isStored(encoded: Array[Byte], path: NibblePath): Boolean = encoded.length >= 32 || path.isEmpty

  /** What one `updateNodesAt` call reported. */
  final case class Call(
      replaced: Seq[(NibblePath, MptNode)],
      absorbed: Seq[(NibblePath, MptNode)],
      oldStored: Rows,
      newStored: Rows
  ):
    def replacedAt: Set[RowKey] = replaced.map(_._1.key).toSet
    def absorbedAt: Set[RowKey] = absorbed.map(_._1.key).toSet

  /** A position-keyed trie store in miniature: one row per stored position of ONE trie, as CF `t` holds the account
    * trie.
    *
    * It is deliberately strict, so that a trie which gets a position wrong cannot pass:
    *   - a node is found only at the position asked for, and must hash to the hash asked for;
    *   - a node named by hash alone is never found (`get`), and an update without positions is refused;
    *   - a node reported as replaced or absorbed must be the row at the position it is reported at;
    *   - a row is overwritten only when the node it held was reported.
    *
    * `upsertOnly` lifts the last rule, for a writer that reports nothing as replaced and relies on each new node
    * overwriting the row it takes the place of: the genesis build (`AppendOnlyMptStorage`). Its rows are then right
    * only if the build never empties a position, which is what a test over such a store shows.
    */
  final class PositionStore(upsertOnly: Boolean = false) extends MptStorage:
    private val rowsByKey = mutable.HashMap.empty[RowKey, Array[Byte]]
    private val readPositions = mutable.ArrayBuffer.empty[RowKey]
    private val reported = mutable.ArrayBuffer.empty[Call]

    def rows: Rows = rowsByKey.view.mapValues(ByteString(_)).toMap

    /** Every `updateNodesAt` call so far, oldest first. */
    def calls: Seq[Call] = reported.toSeq

    def lastCall: Call = reported.last

    /** The positions read with `getAt` since [[clearReads]]. */
    def reads: Seq[RowKey] = readPositions.toSeq

    def clearReads(): Unit = readPositions.clear()

    /** Drops every row: the whole trie is being replaced. */
    def dropRows(): Unit = rowsByKey.clear()

    override def get(nodeId: Array[Byte]): MptNode =
      throw new IllegalStateException("the trie read a node by hash alone: a position store cannot find it")

    override def getAt(path: NibblePath, hash: Array[Byte]): MptNode =
      readPositions += path.key
      rowsByKey.get(path.key) match
        case None => throw new MissingNodeException(ByteString(hash))
        case Some(encoded) =>
          if !Arrays.equals(kec256(encoded), hash) then
            throw new IllegalStateException(s"the row at [${path.show}] is not the node the trie asked for there")
          MptStorage.decodeNode(encoded, hash)

    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      throw new IllegalStateException("the trie updated its storage without positions")

    override def updateNodesAt(
        newRoot: Option[MptNode],
        replaced: Seq[(NibblePath, MptNode)],
        absorbed: Seq[(NibblePath, MptNode)]
    ): Option[MptNode] =
      val oldStored = mutable.LinkedHashMap.empty[RowKey, Array[Byte]]
      (replaced ++ absorbed).foreach { case (path, node) =>
        val encoded = node.encode
        if isStored(encoded, path) then
          val row = rowsByKey.getOrElse(
            path.key,
            throw new IllegalStateException(s"a node was reported at [${path.show}], where no row is")
          )
          if !Arrays.equals(row, encoded) then
            throw new IllegalStateException(s"the node reported at [${path.show}] is not the node stored there")
          if oldStored.put(path.key, encoded).isDefined then
            throw new IllegalStateException(s"position [${path.show}] was reported twice in one update")
      }
      absorbed.foreach { case (path, node) =>
        if !isStored(node.encode, path) then
          throw new IllegalStateException(s"an embedded node was reported as absorbed at [${path.show}]")
      }

      val (collapsed, created) = newRoot match
        case None => (None, Seq.empty)
        case Some(root) =>
          val (hashNode, nodes) = MptTraversals.collapseTrieAt(root, NibblePath.Empty)
          (Some(hashNode), nodes)

      val newStored = mutable.LinkedHashMap.empty[RowKey, Array[Byte]]
      created.foreach { case (path, hash, encoded) =>
        if !isStored(encoded, path) then
          throw new IllegalStateException(s"the collapse returned an embedded node at [${path.show}]")
        if !Arrays.equals(kec256(encoded), hash.toArray) then
          throw new IllegalStateException(s"the collapse returned a wrong hash at [${path.show}]")
        if newStored.put(path.key, encoded).isDefined then
          throw new IllegalStateException(s"the collapse returned position [${path.show}] twice")
        if !upsertOnly && rowsByKey.contains(path.key) && !oldStored.contains(path.key) then
          throw new IllegalStateException(s"a node was written at [${path.show}] over a row that was not reported")
      }

      oldStored.keysIterator.foreach(rowsByKey.remove)
      newStored.foreach { case (key, encoded) => rowsByKey.put(key, encoded) }
      reported += Call(
        replaced,
        absorbed,
        oldStored.view.mapValues(ByteString(_)).toMap,
        newStored.view.mapValues(ByteString(_)).toMap
      )
      collapsed

    override def persist(): Unit = ()

  /** The position-keyed rows of a whole state: the account trie under no owner, and each account's storage trie under
    * its owner, `keccak256(address)`, as CF `t` and CF `u` hold them (spec 018 data-model 2).
    *
    * A trie's paths start at its own root, so this store cannot serve a trie that does not say which one it is: every
    * read and update made on the store itself is refused, and a trie must be built over [[forOwner]]. Each owner's view
    * is a [[PositionStore]], with its strict rules.
    */
  final class OwnerPositionStore(upsertOnly: Boolean = false) extends MptStorage:
    private val tries = mutable.LinkedHashMap.empty[Option[ByteString], PositionStore]
    private val resetOwners = mutable.ArrayBuffer.empty[ByteString]

    def trie(owner: Option[ByteString]): PositionStore = tries.getOrElseUpdate(owner, new PositionStore(upsertOnly))

    /** The tries that hold at least one row. */
    def owners: Set[Option[ByteString]] = tries.collect { case (owner, trie) if trie.rows.nonEmpty => owner }.toSet

    def rowsOf(owner: Option[ByteString]): Rows = tries.get(owner).fold[Rows](Map.empty)(_.rows)

    /** Every owner [[resetOwner]] was called for, in order. */
    def resets: Seq[ByteString] = resetOwners.toSeq

    override def forOwner(owner: Option[ByteString]): MptStorage = trie(owner)

    /** A replaced storage trie loses its rows (data-model 3 rule 6). */
    override def resetOwner(owner: ByteString): Unit =
      resetOwners += owner
      tries.get(Some(owner)).foreach(_.dropRows())

    private def unscoped(what: String): Nothing =
      throw new IllegalStateException(
        s"$what on a state's storage with no owner: the trie did not say which trie it is"
      )

    override def get(nodeId: Array[Byte]): MptNode = unscoped("a read by hash")
    override def getAt(path: NibblePath, hash: Array[Byte]): MptNode = unscoped(s"a read at [${path.show}]")
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      unscoped("an update")
    override def updateNodesAt(
        newRoot: Option[MptNode],
        replaced: Seq[(NibblePath, MptNode)],
        absorbed: Seq[(NibblePath, MptNode)]
    ): Option[MptNode] = unscoped("an update")
    override def persist(): Unit = ()

  /** A block's change set, built from the calls of the updates in it by the rules of spec 018 data-model 3:
    *   - rule 1: the prior of a position is fixed by the first update that touches it in the block, and is absent when
    *     that update reports no stored node there; what later updates report there are intermediates;
    *   - rules 4, 5 and 7: only stored nodes count, and a position that held one and holds none is listed;
    *   - rule 2: a position whose prior equals its next is left out.
    */
  def blockDiff(calls: Seq[Call]): Diff =
    val changes = mutable.LinkedHashMap.empty[RowKey, (Option[ByteString], Option[ByteString])]
    calls.foreach { call =>
      (call.oldStored.keySet ++ call.newStored.keySet).foreach { key =>
        val next = call.newStored.get(key)
        changes.get(key) match
          case None             => changes.put(key, (call.oldStored.get(key), next))
          case Some((prior, _)) => changes.put(key, (prior, next))
      }
    }
    changes.filter { case (_, (prior, next)) => prior != next }.toMap

  /** What a full comparison of two position maps says changed: the oracle [[blockDiff]] is held to. */
  def fullDiff(before: Rows, after: Rows): Diff =
    (before.keySet ++ after.keySet).iterator
      .map(key => key -> (before.get(key), after.get(key)))
      .filter { case (_, (prior, next)) => prior != next }
      .toMap

  def applyForward(rows: Rows, diff: Diff): Rows =
    diff.foldLeft(rows) { case (acc, (key, (_, next))) => next.fold(acc - key)(acc.updated(key, _)) }

  def applyReverse(rows: Rows, diff: Diff): Rows =
    diff.foldLeft(rows) { case (acc, (key, (prior, _))) => prior.fold(acc - key)(acc.updated(key, _)) }

  /** A walk of a trie over its raw RLP. It decides what is a branch, an extension or a leaf, what is embedded and what
    * is stored, from the encoding alone: nothing of `MerklePatriciaTrie`, `MptNode` or the collapse is used.
    */
  object RawTrieWalk:

    /** Every stored position reachable from `rootHash`, with the node stored there. `load` finds a node by hash. */
    def storedPositions(rootHash: Array[Byte], load: Array[Byte] => Array[Byte]): Rows =
      if Arrays.equals(rootHash, MerklePatriciaTrie.EmptyRootHash) then Map.empty
      else
        val out = Map.newBuilder[RowKey, ByteString]
        walkStored(load(rootHash), Array.emptyByteArray, load, out)
        out.result()

    /** The nodes met looking `key` up from the root, root first, as encoded: the nodes on the path of a change to
      * `key`. This is the list a trie has always handed its storage as replaced.
      */
    def nodesOnPath(rootHash: Array[Byte], key: Array[Byte], load: Array[Byte] => Array[Byte]): List[ByteString] =
      if Arrays.equals(rootHash, MerklePatriciaTrie.EmptyRootHash) then Nil
      else onPath(rlp.rawDecode(load(rootHash)), HexPrefix.bytesToNibbles(key), load)

    def contains(rootHash: Array[Byte], key: Array[Byte], load: Array[Byte] => Array[Byte]): Boolean =
      if Arrays.equals(rootHash, MerklePatriciaTrie.EmptyRootHash) then false
      else holds(rlp.rawDecode(load(rootHash)), HexPrefix.bytesToNibbles(key), load)

    /** Every key the trie holds, as its nibbles, with its value. */
    def leaves(rootHash: Array[Byte], load: Array[Byte] => Array[Byte]): Seq[(Array[Byte], Array[Byte])] =
      if Arrays.equals(rootHash, MerklePatriciaTrie.EmptyRootHash) then Nil
      else
        val out = Seq.newBuilder[(Array[Byte], Array[Byte])]
        collectLeaves(rlp.rawDecode(load(rootHash)), Array.emptyByteArray, load, out)
        out.result()

    private def collectLeaves(
        node: RLPEncodeable,
        nibbles: Array[Byte],
        load: Array[Byte] => Array[Byte],
        out: mutable.Builder[(Array[Byte], Array[Byte]), Seq[(Array[Byte], Array[Byte])]]
    ): Unit = node match
      case RLPList(items*) if items.size == 17 =>
        (0 until 16).foreach { i =>
          resolve(items(i), load).foreach(collectLeaves(_, nibbles :+ i.toByte, load, out))
        }
        items(16) match
          case RLPValue(value) if value.nonEmpty => out += nibbles -> value
          case _                                 => ()
      case RLPList(RLPValue(compactKey), next) =>
        val (keyNibbles, isLeaf) = HexPrefix.decode(compactKey)
        if isLeaf then
          next match
            case RLPValue(value) => out += (nibbles ++ keyNibbles) -> value
            case other           => throw new IllegalStateException(s"not a leaf value: $other")
        else resolve(next, load).foreach(collectLeaves(_, nibbles ++ keyNibbles, load, out))
      case other => throw new IllegalStateException(s"not a trie node: $other")

    private def walkStored(
        encoded: Array[Byte],
        nibbles: Array[Byte],
        load: Array[Byte] => Array[Byte],
        out: mutable.Builder[(RowKey, ByteString), Rows]
    ): Unit =
      out += ByteString(HexPrefix.encode(nibbles, isLeaf = false)) -> ByteString(encoded)
      walkChildren(rlp.rawDecode(encoded), nibbles, load, out)

    private def walkChildren(
        node: RLPEncodeable,
        nibbles: Array[Byte],
        load: Array[Byte] => Array[Byte],
        out: mutable.Builder[(RowKey, ByteString), Rows]
    ): Unit = node match
      case RLPList(items*) if items.size == 17 =>
        (0 until 16).foreach(i => walkReference(items(i), nibbles :+ i.toByte, load, out))
      case RLPList(RLPValue(compactKey), next) =>
        val (keyNibbles, isLeaf) = HexPrefix.decode(compactKey)
        if !isLeaf then walkReference(next, nibbles ++ keyNibbles, load, out)
      case other => throw new IllegalStateException(s"not a trie node: $other")

    private def walkReference(
        reference: RLPEncodeable,
        nibbles: Array[Byte],
        load: Array[Byte] => Array[Byte],
        out: mutable.Builder[(RowKey, ByteString), Rows]
    ): Unit = reference match
      case RLPValue(bytes) if bytes.isEmpty      => ()
      case RLPValue(bytes) if bytes.length == 32 => walkStored(load(bytes), nibbles, load, out)
      case embedded: RLPList =>
        require(rlp.encode(embedded).length < 32, "a node of 32 bytes or more is never embedded")
        walkChildren(embedded, nibbles, load, out)
      case other => throw new IllegalStateException(s"not a node reference: $other")

    private def resolve(reference: RLPEncodeable, load: Array[Byte] => Array[Byte]): Option[RLPEncodeable] =
      reference match
        case RLPValue(bytes) if bytes.isEmpty      => None
        case RLPValue(bytes) if bytes.length == 32 => Some(rlp.rawDecode(load(bytes)))
        case embedded: RLPList                     => Some(embedded)
        case other                                 => throw new IllegalStateException(s"not a node reference: $other")

    private def onPath(node: RLPEncodeable, key: Array[Byte], load: Array[Byte] => Array[Byte]): List[ByteString] =
      val here = ByteString(rlp.encode(node))
      node match
        case RLPList(items*) if items.size == 17 =>
          if key.isEmpty then List(here)
          else here :: resolve(items(key(0)), load).fold(List.empty[ByteString])(onPath(_, key.drop(1), load))
        case RLPList(RLPValue(compactKey), next) =>
          val (keyNibbles, isLeaf) = HexPrefix.decode(compactKey)
          if isLeaf || !key.startsWith(keyNibbles) then List(here)
          else here :: resolve(next, load).fold(List.empty[ByteString])(onPath(_, key.drop(keyNibbles.length), load))
        case other => throw new IllegalStateException(s"not a trie node: $other")

    private def holds(node: RLPEncodeable, key: Array[Byte], load: Array[Byte] => Array[Byte]): Boolean =
      node match
        case RLPList(items*) if items.size == 17 =>
          if key.isEmpty then
            items(16) match
              case RLPValue(bytes) => bytes.nonEmpty
              case _               => false
          else resolve(items(key(0)), load).exists(holds(_, key.drop(1), load))
        case RLPList(RLPValue(compactKey), next) =>
          val (keyNibbles, isLeaf) = HexPrefix.decode(compactKey)
          if isLeaf then keyNibbles.sameElements(key)
          else key.startsWith(keyNibbles) && resolve(next, load).exists(holds(_, key.drop(keyNibbles.length), load))
        case other => throw new IllegalStateException(s"not a trie node: $other")

  /** The positions of a whole state, kept beside the hash-keyed store that real block execution runs on: every change
    * the tries report is applied here, and every read they make is checked against it (spec 018 Track C, T026).
    *
    * It follows the staged layer of data-model 6.2 for one unit of changes (a block, or the genesis load). A read at
    * `(owner, path)` for `hash` is right when
    *   1. the unit under way wrote a node with that hash at that owner and that position (the access-list builder reads
    *      an older world of the block after a newer one was persisted, so every version the block wrote there counts),
    *      or
    *   1. the row at that owner and position when the unit began hashes to it.
    *
    * Anything else is a violation: a position store would not have found that node there. A hash the block wrote
    * somewhere else does not count, and neither does the row as it is now when it is neither of the two above.
    *
    * A unit's changes are kept only when it is accepted ([[ended]]). Violations are collected, not thrown: a throw
    * inside block execution would read as the block's own failure. Not thread-safe: for a replay on one thread.
    */
  final class PositionMirror:
    private type Position = (Option[ByteString], RowKey)

    /** The rows of the state after the last accepted unit. */
    private var committed = Map.empty[Position, ByteString]

    /** The rows as the unit under way has them so far. */
    private var working = Map.empty[Position, ByteString]
    private val written = mutable.HashSet.empty[(Option[ByteString], RowKey, ByteString)]
    private var upsertOnly = false
    private val problems = mutable.ArrayBuffer.empty[String]

    private val resetInUnit = mutable.HashSet.empty[ByteString]

    /** Reads answered by rule 2, reads answered by rule 1, updates applied, and storage tries reset. */
    var readsOfStartRows = 0L
    var readsOfOwnWrites = 0L
    var updates = 0L
    var resets = 0L

    /** Storage tries reset while they held rows: the resets that had something to drop. */
    var resetsOfStoredTries = 0L

    /** Reads answered by rule 2 for a storage trie the unit had already reset: a read of the trie as it was before the
      * account was removed. The access-list builder of an Amsterdam block makes them (it reads the world before each
      * transaction after the world following it was persisted). Nothing else does, so before Amsterdam any such read is
      * a trie that outlived its account (forge, C2a review).
      */
    var readsOfResetTries = 0L

    def violations: Seq[String] = problems.toSeq

    private def violation(what: String): Unit = if problems.size < 40 then problems += what

    /** A unit of changes begins. `insertsOnly` for the genesis load, which reports no node as replaced and relies on
      * each new node taking the row of the one it replaces.
      */
    def starting(insertsOnly: Boolean = false): Unit =
      working = committed
      written.clear()
      resetInUnit.clear()
      upsertOnly = insertsOnly

    /** `committed` is then the state of the last accepted unit, which the next unit is taken to build on: a replay of
      * one chain. A unit on a side branch would be checked against the wrong parent, and say so loudly.
      */
    def ended(accepted: Boolean): Unit =
      if accepted then committed = working
      working = committed
      written.clear()
      resetInUnit.clear()
      upsertOnly = false

    def rowsOf(owner: Option[ByteString]): Rows = committed.collect { case ((`owner`, key), row) => key -> row }

    def owners: Set[Option[ByteString]] = committed.keysIterator.map(_._1).toSet

    /** The storage a world is built over: this mirror in front of `storage`. */
    def over(storage: MptStorage): MptStorage = new View(storage, None)

    /** What a walk of the state at `stateRoot` says the rows should be, against what they are: the account trie, the
      * storage trie of every account that has one, and no rows for anything else. Empty when they agree.
      */
    def divergencesFrom(stateRoot: ByteString, load: Array[Byte] => Array[Byte]): Seq[String] =
      val out = Seq.newBuilder[String]
      def compare(owner: Option[ByteString], root: Array[Byte]): Unit =
        val expected = RawTrieWalk.storedPositions(root, load)
        val actual = rowsOf(owner)
        if expected != actual then
          val missing = expected.keySet -- actual.keySet
          val stale = actual.keySet -- expected.keySet
          val other = expected.keySet.intersect(actual.keySet).filter(key => expected(key) != actual(key))
          out += s"the trie of ${describe(owner)}: ${missing.size} positions missing, ${stale.size} stale, " +
            s"${other.size} holding another node"
      compare(None, stateRoot.toArray)
      val withStorage = RawTrieWalk.leaves(stateRoot.toArray, load).flatMap { case (nibbles, account) =>
        rlp.rawDecode(account) match
          case RLPList(_, _, RLPValue(storageRoot), _) =>
            Option.when(!Arrays.equals(storageRoot, MerklePatriciaTrie.EmptyRootHash))(
              ByteString(HexPrefix.nibblesToBytes(nibbles)) -> storageRoot
            )
          case other => throw new IllegalStateException(s"not an account: $other")
      }
      withStorage.foreach((owner, root) => compare(Some(owner), root))
      val expectedOwners = withStorage.map((owner, _) => Option(owner)).toSet + None
      (owners -- expectedOwners).foreach(owner => out += s"rows are kept for ${describe(owner)}, which has no trie")
      out.result()

    private def describe(owner: Option[ByteString]): String = owner.fold("the accounts")(o => s"account ${hex(o)}")

    private def checkRead(owner: Option[ByteString], path: NibblePath, hash: ByteString): Unit =
      if written.contains((owner, path.key, hash)) then readsOfOwnWrites += 1
      else
        val atStart = committed.get((owner, path.key))
        if atStart.exists(row => ByteString(kec256(row.toArray)) == hash) then
          readsOfStartRows += 1
          if owner.exists(resetInUnit.contains) then readsOfResetTries += 1
        else
          val there = atStart.fold("no row was there")(_ => "another node was there")
          violation(
            s"a read at [${path.show}] in the trie of ${describe(owner)} for ${hex(hash)}: $there when the unit " +
              "began, and the unit wrote no such node there"
          )

    private def applyUpdate(
        owner: Option[ByteString],
        newRoot: Option[MptNode],
        replaced: Seq[(NibblePath, MptNode)],
        absorbed: Seq[(NibblePath, MptNode)]
    ): Unit =
      val where = s"in the trie of ${describe(owner)}"
      val gone = mutable.HashSet.empty[RowKey]
      (replaced ++ absorbed).foreach { case (path, node) =>
        val encoded = node.encode
        if isStored(encoded, path) then
          working.get((owner, path.key)) match
            case None => violation(s"a node was reported at [${path.show}] $where, where no row is")
            case Some(row) if row != ByteString(encoded) =>
              violation(s"the node reported at [${path.show}] $where is not the node stored there")
            case _ => ()
          if !gone.add(path.key) then violation(s"position [${path.show}] $where was reported twice in one update")
      }
      val created = newRoot.fold(Seq.empty[(NibblePath, ByteString, Array[Byte])])(root =>
        MptTraversals.collapseTrieAt(root, NibblePath.Empty)._2
      )
      created.foreach { case (path, _, _) =>
        if !upsertOnly && working.contains((owner, path.key)) && !gone.contains(path.key) then
          violation(s"a node was written at [${path.show}] $where over a row that was not reported")
      }
      working = working -- gone.iterator.map(key => (owner, key))
      created.foreach { case (path, hash, encoded) =>
        working = working.updated((owner, path.key), ByteString(encoded))
        written += ((owner, path.key, hash))
      }

    /** `scope` is `None` until a trie says which trie it is, then that trie's owner. */
    final private class View(protected val underlying: MptStorage, scope: Option[Option[ByteString]])
        extends MptStorageDecorator:

      override protected def decorate(scoped: MptStorage): MptStorage = new View(scoped, scope)

      // A hash-keyed store answers `forOwner` with itself, and the base would then keep this view as it is.
      override def forOwner(owner: Option[ByteString]): MptStorage = new View(underlying.forOwner(owner), Some(owner))

      /** The storage trie loses its rows as the unit has them. What it held when the unit began stays readable. */
      override def resetOwner(owner: ByteString): Unit =
        resets += 1
        resetInUnit += owner
        val kept = working.filterNot { case ((rowOwner, _), _) => rowOwner.contains(owner) }
        if kept.size != working.size then resetsOfStoredTries += 1
        working = kept
        underlying.resetOwner(owner)

      override protected def read(hash: Array[Byte], path: NibblePath | Null): MptNode =
        scope match
          case None => violation("a read on the state's storage with no owner")
          case Some(owner) =>
            if path == null then violation(s"a read by hash alone in the trie of ${describe(owner)}")
            else checkRead(owner, path.nn, ByteString(hash))
        super.read(hash, path)

      override protected def update(newRoot: Option[MptNode], removals: Removals): Option[MptNode] =
        updates += 1
        (scope, removals) match
          case (None, _) => violation("an update on the state's storage with no owner")
          case (Some(owner), _: Removals.Unpositioned) =>
            violation(s"an update without positions in the trie of ${describe(owner)}")
          case (Some(owner), Removals.Positioned(replaced, absorbed)) => applyUpdate(owner, newRoot, replaced, absorbed)
        super.update(newRoot, removals)

  type Trie = MerklePatriciaTrie[Array[Byte], Array[Byte]]

  /** A hash-keyed store that keeps every node ever written and notes what each update was told to remove: the store a
    * trie has always had, and the one [[RawTrieWalk]] reads.
    */
  final class ReferenceStore extends MptStorage:
    private val nodes = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    private val removals = mutable.ArrayBuffer.empty[List[ByteString]]

    /** For each update so far, the encodings of the nodes it was told to remove, in the order given. */
    def removed: Seq[List[ByteString]] = removals.toSeq

    def load(hash: Array[Byte]): Array[Byte] = nodes.get(hash).encode

    override def get(nodeId: Array[Byte]): MptNode = nodes.get(nodeId)

    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      removals += toRemove.map(node => ByteString(node.encode)).toList
      nodes.updateNodesInStorage(newRoot, toRemove)

    override def persist(): Unit = nodes.persist()

  /** One trie kept twice, over a [[PositionStore]] and over a [[ReferenceStore]], and checked after every change:
    *   - both have the same root (consensus is unchanged by where nodes are kept);
    *   - the position store's rows are exactly the stored positions a raw walk of the reference trie finds: nothing
    *     missing, nothing left behind, every node where the walk says it is;
    *   - the hash-keyed store was told to remove exactly the nodes on the path of the change, root first.
    */
  final class TwinTrie:
    val positions = new PositionStore
    val reference = new ReferenceStore
    private var positioned: Trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](positions)
    private var hashed: Trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](reference)

    def root: Array[Byte] = positioned.getRootHash

    def trie: Trie = positioned

    def walk: Rows = RawTrieWalk.storedPositions(hashed.getRootHash, reference.load)

    def put(key: Array[Byte], value: Array[Byte]): Unit =
      val expectedRemovals = RawTrieWalk.nodesOnPath(hashed.getRootHash, key, reference.load)
      val updatesBefore = reference.removed.size
      positioned = positioned.put(key, value)
      hashed = hashed.put(key, value)
      check(s"put ${hex(key)}")
      require(reference.removed.size == updatesBefore + 1, s"put ${hex(key)}: one storage update expected")
      require(
        reference.removed.last == expectedRemovals,
        s"put ${hex(key)}: the hash-keyed store was not told to remove the nodes on the path of the change"
      )

    def remove(key: Array[Byte]): Unit =
      val present = RawTrieWalk.contains(hashed.getRootHash, key, reference.load)
      val expectedRemovals = RawTrieWalk.nodesOnPath(hashed.getRootHash, key, reference.load)
      val updatesBefore = reference.removed.size
      val callsBefore = positions.calls.size
      positioned = positioned.remove(key)
      hashed = hashed.remove(key)
      check(s"remove ${hex(key)}")
      if present then
        require(reference.removed.size == updatesBefore + 1, s"remove ${hex(key)}: one storage update expected")
        require(
          reference.removed.last == expectedRemovals,
          s"remove ${hex(key)}: the hash-keyed store was not told to remove the nodes on the path of the change"
        )
      else
        require(reference.removed.size == updatesBefore, s"remove ${hex(key)}: an absent key updated the store")
        require(positions.calls.size == callsBefore, s"remove ${hex(key)}: an absent key updated the position store")

    private def check(what: String): Unit =
      require(Arrays.equals(positioned.getRootHash, hashed.getRootHash), s"$what: the two tries have different roots")
      val expected = walk
      val actual = positions.rows
      require(
        actual == expected,
        s"$what: the position store is not the trie's stored positions. " +
          s"Missing: ${(expected.keySet -- actual.keySet).map(hex)}; " +
          s"left behind: ${(actual.keySet -- expected.keySet).map(hex)}; " +
          s"different: ${expected.keySet.intersect(actual.keySet).filter(k => expected(k) != actual(k)).map(hex)}"
      )

  /** What a hash-keyed store is told to remove when a trie runs on it through `wrap`: one list per update, over
    * insertions, a new value for a key, and a removal that makes a branch collapse onto its last child. Also the root
    * the trie ends with, and the root the same changes give on the store itself.
    */
  def removalsSeenThrough(wrap: MptStorage => MptStorage): (Seq[List[ByteString]], ByteString, ByteString) =
    def changes(start: Trie): Trie =
      val value = Array.fill[Byte](40)(7)
      val grown = (0 until 40).foldLeft(start)((trie, i) => trie.put(kec256(Array[Byte](i.toByte)), value))
      grown
        .put(kec256(Array[Byte](3)), Array.fill[Byte](40)(9))
        .remove(kec256(Array[Byte](5)))
        .put(Array[Byte](0x00), value)
        .put(Array[Byte](0x10), value)
        .remove(Array[Byte](0x10))
    val store = new ReferenceStore
    val wrapped = changes(MerklePatriciaTrie[Array[Byte], Array[Byte]](wrap(store)))
    val plain = changes(MerklePatriciaTrie[Array[Byte], Array[Byte]](new ReferenceStore))
    (store.removed, ByteString(wrapped.getRootHash), ByteString(plain.getRootHash))

  /** How many nodes each update names as replaced or absorbed when it reaches a storage that keeps tries apart, for a
    * trie built over `wrap(storage).forOwner(...)`: one count per update, over insertions, a new value for a key, and a
    * removal that makes a branch collapse.
    */
  def removalsSeenThroughOwnerView(wrap: MptStorage => MptStorage): Seq[Int] =
    val seen = mutable.ArrayBuffer.empty[Int]
    // A hash-keyed store underneath, behind a view per owner that counts what each update names.
    final class Scoping(nodes: ReferenceStore) extends MptStorage:
      override def get(nodeId: Array[Byte]): MptNode = nodes.get(nodeId)
      override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
        seen += toRemove.size
        nodes.updateNodesInStorage(newRoot, Nil)
      override def updateNodesAt(
          newRoot: Option[MptNode],
          replaced: Seq[(NibblePath, MptNode)],
          absorbed: Seq[(NibblePath, MptNode)]
      ): Option[MptNode] =
        seen += replaced.size + absorbed.size
        nodes.updateNodesInStorage(newRoot, Nil)
      override def persist(): Unit = ()
      override def forOwner(owner: Option[ByteString]): MptStorage = new Scoping(nodes)
    val view = wrap(new Scoping(new ReferenceStore)).forOwner(Some(ByteString(Array.fill[Byte](32)(1))))
    val value = Array.fill[Byte](40)(7)
    MerklePatriciaTrie[Array[Byte], Array[Byte]](view)
      .put(Array[Byte](0x00), value)
      .put(Array[Byte](0x10), value)
      .put(Array[Byte](0x00), Array.fill[Byte](40)(9))
      .remove(Array[Byte](0x10))
    seen.toSeq

  def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  def hex(bytes: ByteString): String = hex(bytes.toArray)

  /** A change to a trie. */
  enum Op:
    case Put(key: Array[Byte], value: Array[Byte])
    case Remove(key: Array[Byte])

  /** How the keys of a random run are drawn. */
  enum Keys:
    /** Four-byte keys whose nibbles come from three values: long shared prefixes, so extensions split and merge and
      * branches collapse all the time. Fixed length, so `StackTrie` can rebuild the same trie.
      */
    case Dense

    /** 32-byte keys as a secure trie has them (`keccak256` of something). */
    case Hashed

    /** Keys of one to four bytes, some a prefix of others: branches that hold a value, and leaves with an empty key.
      * Only tries with keys of varying length have them (`ArbitraryIntegerMpt`, not a secure trie and not a list trie,
      * whose RLP keys are prefix-free).
      */
    case Varying

  /** A source of random changes with a fixed seed: the same run every time. */
  final class OpSource(seed: Long, keys: Keys):
    private val random = new Random(seed)
    private val live = mutable.LinkedHashMap.empty[ByteString, Array[Byte]]

    /** The keys the trie holds, with their values, after the changes handed out so far. */
    def contents: Seq[(Array[Byte], Array[Byte])] = live.toSeq.map { case (key, value) => key.toArray -> value }

    private val valueLengths = Array(1, 2, 8, 30, 31, 32, 33, 40, 80)

    private def freshValue(): Array[Byte] =
      val value = new Array[Byte](valueLengths(random.nextInt(valueLengths.length)))
      random.nextBytes(value)
      value

    private def freshKey(): Array[Byte] = keys match
      case Keys.Dense =>
        val nibbles = Array.fill[Byte](8)(Array[Byte](0x0, 0x7, 0xf)(random.nextInt(3)))
        HexPrefix.nibblesToBytes(nibbles)
      case Keys.Hashed =>
        val preimage = new Array[Byte](8)
        random.nextBytes(preimage)
        kec256(preimage)
      case Keys.Varying =>
        Array.fill(1 + random.nextInt(4))(Array[Byte](0x11, 0x1f, 0xf1.toByte, 0xff.toByte)(random.nextInt(4)))

    private def someLiveKey(): Array[Byte] = live.keysIterator.drop(random.nextInt(live.size)).next().toArray

    private def put(key: Array[Byte], value: Array[Byte]): Op =
      live.put(ByteString(key), value)
      Op.Put(key, value)

    def next(): Op =
      val roll = random.nextInt(100)
      if live.isEmpty || roll < 50 then put(freshKey(), freshValue())
      else if roll < 62 then put(someLiveKey(), freshValue()) // a new value, often of another size
      else if roll < 68 then
        val key = someLiveKey()
        put(key, live(ByteString(key))) // the same value again: nothing changes
      else if roll < 95 then
        val key = someLiveKey()
        live.remove(ByteString(key))
        Op.Remove(key)
      else
        val key = freshKey() // most often a key the trie does not hold
        live.remove(ByteString(key))
        Op.Remove(key)

    /** Only insertions of keys the trie does not hold, as a genesis build makes. */
    def nextInsert(): Op =
      var key = freshKey()
      while live.contains(ByteString(key)) do key = freshKey()
      put(key, freshValue())

  def applyOp(twin: TwinTrie, op: Op): Unit = op match
    case Op.Put(key, value) => twin.put(key, value)
    case Op.Remove(key)     => twin.remove(key)

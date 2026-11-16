package com.chipprbots.ethereum.db.storage

import java.util.Arrays

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.mpt.byteStringSerializer

/** The sample at the hand-off of a SNAP sync onto the position store (spec 018 data-model G, spec 022 decision 11).
  *
  * On the unified layout nothing is copied at the hand-off: the rows SNAP wrote are the state a block is executed on.
  * So there is no scan to sample from, as [[com.chipprbots.ethereum.blockchain.sync.snap.PathToHashExporter]] has, and
  * no second store to look things up in. The rows are sampled by seeks at random keys, and each leaf found is looked up
  * from the state root, through the rows, with every node on the way checked against the hash its parent holds: what
  * the execution of a block does.
  *
  * It answers whether lookups from the root reach what the rows hold. It is not a recompute of the root and says
  * nothing of a row it did not land on: the audit looks at all of it later.
  *
  * '''The strata''', so that a size cannot hide a thin region:
  *   - account leaves by the first nibble of their path. A key is the hex-prefix form of the path, which is not in the
  *     order of the paths: the paths of an even length and those of an odd one are two ranges for each nibble, and both
  *     are sampled;
  *   - storage tries by the first nibble of their owner: a seek lands on a row, and the row names its owner. The root
  *     of each is looked for where its account says;
  *   - the largest of those tries, by their rows up to a bound. A seek at a random key finds an owner by the gap before
  *     it and not by its size, so these are the largest of the tries sampled, never the largest of the chain;
  *   - leaves short enough to be held inside their parent, met on the way. No row of theirs is ever read by a lookup:
  *     only the parent answers for them. A sync writes no such row (a node under 32 bytes is not asked for by hash, and
  *     a leaf that short lies deeper than a trie of hashed keys goes), so on a chain this stratum is empty, and is no
  *     coverage: it is here for a store that has them.
  *
  * '''What stops a hand-off.''' A state root that is not there. '''Any hole''': a lookup that meets a row that is
  * absent, or is another node than its parent names. That is what the execution of a block would meet, and it cannot be
  * what another pivot left: the walk comes down from this root, and the row it wants is not there. In a storage trie a
  * hole is let by, and counted, only when the sync says it left accounts out (spec 022 D5: their storage is fetched
  * when a block asks for it), and then missing roots still stop it past [[tooManyMisses]]. And leaves that are not
  * found past [[tooManyMisses]]: a leaf the root has no place for is what another pivot left behind, and is harmless, a
  * read never reaches it, but too many say the rows are not this root's.
  *
  * A hand-off that is stopped goes back to healing, which finds the hole from the root. A hole that is let by stops the
  * import of the first block that reads it.
  *
  * '''What it costs.''' Some 6,000 lookups of about ten point reads, and the rows read to size the tries sampled: at
  * most [[LargeTrieRowBound]] of each of 256, of which the leaves to look up are kept and the rest dropped as they are
  * read. A seek into a region of deleted rows (a heal's deletes) passes over them one by one; it is once, at the
  * hand-off.
  *
  * It reads and writes nothing. It is run on a blocking thread, with nothing writing the rows.
  */
object PathHandoffSample:

  /** @param sampledAccountLeaves
    *   account leaves looked up from the state root
    * @param sampledAccountMisses
    *   of those, the ones the state root has no place for, or holds another value for: leaves of another root. Past
    *   [[tooManyMisses]] the sample threw
    * @param sampledStorageRoots
    *   storage tries whose root was looked for, by way of the owner's account at the state root
    * @param sampledStorageRootsStale
    *   of those, tries of an owner the state root has no account for, or an account with no storage: rows left behind
    * @param sampledStorageRootsMissing
    *   of those, tries whose account names a root that is absent or is another node. Not zero only where the sync left
    *   accounts out; past [[tooManyMisses]] the sample threw
    * @param sampledLargeTrieLeaves
    *   leaves of the largest tries found by a lookup from the state root
    * @param sampledEmbedded
    *   the same for leaves held inside their parent
    * @param sampledStale
    *   of those two, leaves the state root has no place for
    * @param sampledMissing
    *   of those two, lookups that met a row of the storage trie that is absent or is another node: a hole. Not zero
    *   only where the sync left accounts out
    * @param sampledCode
    *   code hashes of the account leaves found, each once
    * @param sampledCodeAbsent
    *   of those, absent. Code can be fetched when it is asked for (spec 022 D4), so this is news and no fault
    */
  final case class Result(
      sampledAccountLeaves: Int = 0,
      sampledAccountMisses: Int = 0,
      sampledStorageRoots: Int = 0,
      sampledStorageRootsStale: Int = 0,
      sampledStorageRootsMissing: Int = 0,
      sampledLargeTrieLeaves: Int = 0,
      sampledEmbedded: Int = 0,
      sampledStale: Int = 0,
      sampledMissing: Int = 0,
      sampledCode: Int = 0,
      sampledCodeAbsent: Int = 0,
      sampleMillis: Long = 0L
  ):
    /** Lookups that met a hole in a storage trie: let by only where the sync left accounts out. */
    def storageHoles: Int = sampledStorageRootsMissing + sampledMissing

    /** What was looked at and what was found, for a log. */
    def describe: String =
      s"$sampledAccountLeaves account leaves looked up from the root ($sampledAccountMisses of another root); " +
        s"$sampledStorageRoots storage tries ($sampledStorageRootsStale left behind, $sampledStorageRootsMissing " +
        s"with no root); $sampledLargeTrieLeaves leaves of the largest of them and $sampledEmbedded held in their " +
        s"parent ($sampledStale left behind, $sampledMissing at a hole); $sampledCodeAbsent of $sampledCode code " +
        s"hashes absent; in $sampleMillis ms"

  /** The sample stopped the hand-off: the rows are not the whole of the state at the root it was given. It is the
    * sample's own verdict, and nothing else it throws is: a store that fails throws what it throws, and that is not a
    * reason to heal again.
    */
  final class Stopped(message: String, val reason: StopReason, val counts: Result, cause: Throwable = null)
      extends IllegalStateException(message, cause)

  /** Which rule of the sample stopped the hand-off: the tag of `db.state.handoff.sample.stopped` (B-304). */
  enum StopReason(val text: String):
    /** The state root is not in the rows. */
    case RootAbsent extends StopReason("root-absent")

    /** A lookup met a row of the account trie that is absent or is another node than its parent names. */
    case AccountHole extends StopReason("account-hole")

    /** Too many sampled account leaves the root has no place for. */
    case AccountMisses extends StopReason("account-misses")

    /** A hole in a storage trie, on a sync that left no account out. */
    case StorageHole extends StopReason("storage-hole")

    /** Too many sampled storage tries with no root where their account names it. */
    case StorageRootMisses extends StopReason("storage-root-misses")

  /** Whether lookups missed too often for that to be what another pivot left: more than 1% of them, and at least 16. */
  def tooManyMisses(misses: Int, sampled: Int): Boolean =
    misses >= 16 && misses * 100L > sampled

  // The sizes of spec 022 T054, which were measured against the copy's scan. Some 6,000 lookups of about ten reads.
  val AccountSamplePerNibble = 256
  val StorageRootSamplePerNibble = 16
  val EmbeddedSamplePerNibble = 16
  val LargeStorageTries = 16
  val LargeStorageSamplePerTrie = 64

  /** How many leaves one seek gives, and how many rows it reads for them: rows next to each other are one subtree, so a
    * stratum is many short runs and not one long one.
    */
  val LeavesPerSeek = 16
  val RowsPerSeek = 64

  /** The rows of a storage trie that are read to size it. A trie with fewer is known whole. */
  val LargeTrieRowBound = 1024

  private type Row = (Array[Byte], Array[Byte])

  /** A key above every row of the storage tries: an owner of `0xff` bytes is followed by a path's first byte, which is
    * never `0xff`.
    */
  private val AboveEveryStorageRow: Array[Byte] = Array.fill(PositionKey.OwnerLength + 1)(0xff.toByte)

  /** The rows as a trie reads them: by position, each checked against the hash that names it. */
  final private class Rows(paths: PathNodeStorage, owner: Option[ByteString]) extends MptStorage:
    override def forOwner(owner: Option[ByteString]): MptStorage = new Rows(paths, owner)
    override def nodesSharedAcrossTries: Boolean = false

    override def getAt(path: NibblePath, hash: Array[Byte]): MptNode =
      val position = PositionKey.at(owner, path)
      paths.read(position) match
        case None => throw new MissingNodeException(ByteString(hash)).withLocation(position.hp)
        case Some(encoded) if !Arrays.equals(kec256(encoded), hash) =>
          throw new CorruptNodeException(
            ByteString(hash),
            s"the row of ${position.show} is another node",
            location = Some(position.hp)
          )
        case Some(encoded) =>
          try MptStorage.decodeNode(encoded, hash)
          catch
            case NonFatal(e) =>
              throw new CorruptNodeException(
                ByteString(hash),
                s"the row of ${position.show} does not decode: ${e.getClass.getSimpleName}",
                location = Some(position.hp),
                cause = Some(e)
              )

    override def get(nodeId: Array[Byte]): MptNode =
      throw new IllegalStateException("A node asked for by its hash alone: the rows are found by position")
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      throw new UnsupportedOperationException("The hand-off's sample writes nothing")
    override def persist(): Unit = ()

  /** @param stateRoot
    *   the root of the pivot's state
    * @param codePresent
    *   whether a code hash is in the code store
    * @param random
    *   where the seeks land
    * @param accountsLeftOut
    *   whether the sync left accounts out, whose storage is fetched when a block asks for it: a hole in a storage trie
    *   is counted then, and stops nothing
    * @param largeTrieRowBound
    *   [[LargeTrieRowBound]], lower in a test
    * @param largeTries
    *   [[LargeStorageTries]], lower in a test
    */
  def run(
      paths: PathNodeStorage,
      stateRoot: ByteString,
      codePresent: ByteString => Boolean = _ => true,
      random: Random = new Random(),
      accountsLeftOut: Boolean = false,
      largeTrieRowBound: Int = LargeTrieRowBound,
      largeTries: Int = LargeStorageTries
  ): Result =
    val started = System.nanoTime()
    val rows = new Rows(paths, None)
    requireRoot(stateRoot, rows)
    val accounts = MerklePatriciaTrie[ByteString, Array[Byte]](stateRoot.toArray, rows)

    val leaves = lookUpAccountLeaves(sampleAccountLeaves(paths, random), accounts)

    val owners = (0 until 16).flatMap(sampleOwners(paths, random, _))
    val sized = owners.map(sizeOf(paths, random, _, largeTrieRowBound))
    val roots = lookUpStorageRoots(owners, accounts, rows)

    val embeddedRows = (0 until 16).flatMap { nibble =>
      sized.iterator
        .filter(trie => stratumOf(trie.owner) == nibble)
        .flatMap(_.embedded)
        .take(EmbeddedSamplePerNibble)
        .toVector
    }
    // shuffled first: the tries that reach the bound are of one size, and a sort that keeps their order would always
    // take those of the lowest owners
    val largeRows = random
      .shuffle(sized)
      .sortBy(trie => -trie.rows)
      .take(largeTries)
      .flatMap(trie => trie.leaves.getOrElse(seekLeavesOf(paths, random, trie.owner)))

    val large = lookUpStorageLeaves(largeRows, accounts, rows)
    val embedded = lookUpStorageLeaves(embeddedRows, accounts, rows)

    val distinctCode = leaves.codeHashes.distinct
    // What was looked at and found, for a sample that passes and for one that stops the hand-off alike (B-304)
    val counts = Result(
      leaves.sampled,
      leaves.misses,
      roots.sampled,
      roots.stale,
      roots.missing,
      large.found,
      embedded.found,
      large.stale + embedded.stale,
      large.missing + embedded.missing,
      distinctCode.size,
      distinctCode.count(hash => !codePresent(hash)),
      (System.nanoTime() - started) / 1000000L
    )
    val at = s"at the state root ${hexOf(stateRoot.take(4).toArray)}"
    val accountHoles = leaves.holes + roots.unreachable + large.unreachable + embedded.unreachable
    if accountHoles > 0 then
      throw new Stopped(
        s"The hand-off's sample failed: $accountHoles lookups $at met a row of the account trie that is absent or is " +
          "another node than its parent names",
        StopReason.AccountHole,
        counts
      )
    if tooManyMisses(leaves.misses, leaves.sampled) then
      throw new Stopped(
        s"The hand-off's sample failed: ${leaves.misses} of ${leaves.sampled} sampled account leaves not found (or " +
          s"different) $at",
        StopReason.AccountMisses,
        counts
      )
    val storageHoles = roots.missing + large.missing + embedded.missing
    if storageHoles > 0 && !accountsLeftOut then
      throw new Stopped(
        s"The hand-off's sample failed: ${roots.missing} of ${roots.sampled} sampled storage tries have no root " +
          s"where their account $at names it, and ${large.missing + embedded.missing} lookups of a storage leaf met " +
          "a row that is absent or is another node, on a sync that left no account out",
        StopReason.StorageHole,
        counts
      )
    if tooManyMisses(roots.missing, roots.sampled) then
      throw new Stopped(
        s"The hand-off's sample failed: ${roots.missing} of ${roots.sampled} sampled storage tries have no root " +
          s"where their account $at names it: more than the accounts a sync leaves out",
        StopReason.StorageRootMisses,
        counts
      )
    counts

  private def hexOf(bytes: Array[Byte]): String = bytes.map("%02x".format(_)).mkString

  private def endOf(owner: ByteString): Array[Byte] = owner.toArray :+ 0xff.toByte

  private def stratumOf(owner: ByteString): Int = (owner(0) >> 4) & 0xf

  private def randomBytes(random: Random, n: Int): Array[Byte] =
    val bytes = new Array[Byte](n)
    random.nextBytes(bytes)
    bytes

  private def leafOf(rlp: Array[Byte]): Option[LeafNode] =
    try
      MptTraversals.decodeNode(rlp) match
        case leaf: LeafNode => Some(leaf)
        case _              => None
    catch
      case NonFatal(_) => None // a row that does not decode is no leaf to look up; the lookups find it if it is reached

  private def isLeaf(row: Row): Boolean = leafOf(row._2).isDefined

  /** A leaf short enough to be held in its parent. A trie's root is never one: a leaf at the root carries a whole key
    * of 32 bytes.
    */
  private def isEmbeddedLeaf(row: Row): Boolean = row._2.length < 32 && isLeaf(row)

  /** What is kept of one storage trie that was sized: never its rows. `leaves` is a sample of all its leaves when the
    * rows read were all it has, and `None` when it has more: it is then sampled by seeks, if it is among the largest.
    */
  final private case class Sized(owner: ByteString, rows: Int, embedded: Vector[Row], leaves: Option[Vector[Row]])

  private def sizeOf(paths: PathNodeStorage, random: Random, owner: ByteString, rowBound: Int): Sized =
    val first = paths.storageRowsBetween(owner.toArray, endOf(owner), rowBound)
    Sized(
      owner,
      first.size,
      first.filter(isEmbeddedLeaf).take(EmbeddedSamplePerNibble),
      Option.when(first.size < rowBound)(random.shuffle(first.filter(isLeaf)).take(LargeStorageSamplePerTrie))
    )

  /** Leaves near `seeks` random keys of the range `[from, until)`, added to `into` until it holds `capacity`.
    *
    * A seek reads [[RowsPerSeek]] rows on from its key, and from the start of the range when the range ends first. A
    * range with fewer rows than that is read whole by its first seek, and every leaf of it is taken.
    */
  private def seekLeaves(
      scan: (Array[Byte], Array[Byte], Int) => Vector[Row],
      from: Array[Byte],
      until: Array[Byte],
      seekKey: Int => Array[Byte],
      seeks: Int,
      into: mutable.LinkedHashMap[ByteString, Array[Byte]],
      capacity: Int
  ): Unit =
    var whole = false
    var seek = 0
    while seek < seeks && !whole && into.size < capacity do
      val at = seekKey(seek)
      val ahead = scan(at, until, RowsPerSeek)
      val behind = if ahead.size < RowsPerSeek then scan(from, at, RowsPerSeek - ahead.size) else Vector.empty
      whole = ahead.size + behind.size < RowsPerSeek
      val found = (ahead ++ behind).iterator.filter(isLeaf)
      (if whole then found else found.take(LeavesPerSeek)).foreach { (key, rlp) =>
        if into.size < capacity then into.getOrElseUpdate(ByteString(key), rlp)
      }
      seek += 1

  /** Account leaves, by the first nibble of their path. The key of a path of an even length is `0x00` and the path's
    * bytes; of an odd length, `0x1N` for its first nibble `N` and the bytes of the rest.
    */
  private def sampleAccountLeaves(paths: PathNodeStorage, random: Random): Seq[Row] =
    val seeksPerRange = AccountSamplePerNibble / 2 / LeavesPerSeek
    (0 until 16).flatMap { nibble =>
      val into = mutable.LinkedHashMap.empty[ByteString, Array[Byte]]
      // the root, a path of no nibbles, is the key 0x00 alone: it sorts first, and is read with nibble 0
      val evenFrom = if nibble == 0 then Array[Byte](0) else Array[Byte](0, (nibble << 4).toByte)
      val evenUntil = if nibble == 15 then Array[Byte](1) else Array[Byte](0, ((nibble + 1) << 4).toByte)
      seekLeaves(
        paths.accountRowsBetween,
        evenFrom,
        evenUntil,
        _ => Array[Byte](0, ((nibble << 4) | random.nextInt(16)).toByte) ++ randomBytes(random, 7),
        seeksPerRange,
        into,
        AccountSamplePerNibble
      )
      seekLeaves(
        paths.accountRowsBetween,
        Array((0x10 | nibble).toByte),
        Array(((0x10 | nibble) + 1).toByte),
        _ => (0x10 | nibble).toByte +: randomBytes(random, 8),
        seeksPerRange,
        into,
        AccountSamplePerNibble
      )
      into.iterator.map((key, rlp) => (key.toArray, rlp)).toVector
    }

  /** The owners of the storage tries that seeks at random keys land on, among the owners that begin with `nibble`. */
  private def sampleOwners(paths: PathNodeStorage, random: Random, nibble: Int): Seq[ByteString] =
    val from = Array((nibble << 4).toByte)
    val until = if nibble == 15 then AboveEveryStorageRow else Array(((nibble + 1) << 4).toByte)
    val owners = mutable.LinkedHashSet.empty[ByteString]
    var seek = 0
    var none = false
    while seek < StorageRootSamplePerNibble && !none do
      val at = ((nibble << 4) | random.nextInt(16)).toByte +: randomBytes(random, PositionKey.OwnerLength - 1)
      val landed =
        paths.storageRowsBetween(at, until, 1).headOption.orElse(paths.storageRowsBetween(from, at, 1).headOption)
      landed match
        case Some((key, _)) if key.length > PositionKey.OwnerLength =>
          owners += ByteString(key.take(PositionKey.OwnerLength))
        case Some(_) => () // no row of a storage trie is that short
        case None    => none = true
      seek += 1
    owners.toVector

  /** Up to [[LargeStorageSamplePerTrie]] leaves of a trie with more rows than were read to size it, by seeks inside it.
    */
  private def seekLeavesOf(paths: PathNodeStorage, random: Random, owner: ByteString): Seq[Row] =
    val into = mutable.LinkedHashMap.empty[ByteString, Array[Byte]]
    seekLeaves(
      paths.storageRowsBetween,
      owner.toArray,
      endOf(owner),
      // the paths of an even length and of an odd one, in turn
      seek =>
        owner.toArray ++ (if seek % 2 == 0 then Array[Byte](0) else Array((0x10 | random.nextInt(16)).toByte)) ++
          randomBytes(random, 8),
      LargeStorageSamplePerTrie / LeavesPerSeek,
      into,
      LargeStorageSamplePerTrie
    )
    into.iterator.map((key, rlp) => (key.toArray, rlp)).toVector

  /** A root that is absent is no stale leaf. Nothing under it can be read, so it throws whatever a rate would say. */
  private def requireRoot(root: ByteString, rows: MptStorage): Unit =
    if !Arrays.equals(root.toArray, MerklePatriciaTrie.EmptyRootHash) then
      try rows.getAt(NibblePath.Empty, root.toArray)
      catch
        case e: MPTException =>
          throw new Stopped(
            s"The hand-off's sample failed: the state root ${hexOf(root.take(4).toArray)} is not in the rows",
            StopReason.RootAbsent,
            Result(),
            e
          )

  final private case class AccountLeaves(sampled: Int, misses: Int, holes: Int, codeHashes: Seq[ByteString])

  /** Each sampled account leaf looked up from the state root. A lookup that met a hole is a hole and not a miss. */
  private def lookUpAccountLeaves(
      sample: Seq[Row],
      accounts: MerklePatriciaTrie[ByteString, Array[Byte]]
  ): AccountLeaves =
    var sampled = 0
    var misses = 0
    var holes = 0
    val codeHashes = Seq.newBuilder[ByteString]
    sample.foreach { (hpKey, rlp) =>
      leafOf(rlp).foreach { leaf =>
        val fullNibbles = HexPrefix.decode(hpKey)._1 ++ leaf.key.toArray
        // Only a whole-byte path is a key; a real account leaf is always 64 nibbles.
        if fullNibbles.length % 2 == 0 then
          sampled += 1
          try
            val found = accounts.get(ByteString(HexPrefix.nibblesToBytes(fullNibbles)))
            if found.exists(Arrays.equals(_, leaf.value.toArray)) then
              Account(leaf.value).toOption
                .map(_.codeHash)
                .filter(_ != Account.EmptyCodeHash)
                .foreach(code => codeHashes += code.value)
            else misses += 1
          catch case _: MPTException => holes += 1
      }
    }
    AccountLeaves(sampled, misses, holes, codeHashes.result())

  /** The account of `owner` at the state root when it has storage, `Right(None)` when the root has no such account or
    * it has none, and `Left` when the lookup met a hole.
    */
  private def accountWithStorage(
      accounts: MerklePatriciaTrie[ByteString, Array[Byte]],
      owner: ByteString
  ): Either[MPTException, Option[Account]] =
    try
      Right(
        accounts
          .get(owner)
          .flatMap(bytes => Account(ByteString(bytes)).toOption)
          .filter(_.storageRoot != Account.EmptyStorageRootHash)
      )
    catch case e: MPTException => Left(e)

  /** `unreachable`: the lookup of the owner's account met a hole in the account trie. `missing`: its account names a
    * root that is absent or is another node, a hole in the storage trie.
    */
  final private case class Roots(sampled: Int, stale: Int, missing: Int, unreachable: Int)

  private def lookUpStorageRoots(
      owners: Seq[ByteString],
      accounts: MerklePatriciaTrie[ByteString, Array[Byte]],
      rows: MptStorage
  ): Roots =
    var stale = 0
    var missing = 0
    var unreachable = 0
    owners.foreach { owner =>
      accountWithStorage(accounts, owner) match
        case Left(_)     => unreachable += 1
        case Right(None) => stale += 1
        case Right(Some(account)) =>
          try rows.forOwner(Some(owner)).getAt(NibblePath.Empty, account.storageRoot.value.toArray)
          catch case _: MPTException => missing += 1
    }
    Roots(owners.size, stale, missing, unreachable)

  final private case class Looked(found: Int = 0, stale: Int = 0, missing: Int = 0, unreachable: Int = 0)

  /** Each sampled storage leaf looked up from the state root, by way of its account's leaf. */
  private def lookUpStorageLeaves(
      sample: Seq[Row],
      accounts: MerklePatriciaTrie[ByteString, Array[Byte]],
      rows: MptStorage
  ): Looked =
    sample.foldLeft(Looked()) { case (seen, (key, rlp)) =>
      leafOf(rlp) match
        case None => seen
        case Some(leaf) =>
          val owner = ByteString(key.take(PositionKey.OwnerLength))
          val fullNibbles = HexPrefix.decode(key.drop(PositionKey.OwnerLength))._1 ++ leaf.key.toArray
          if fullNibbles.length != 64 then seen
          else
            val slot = ByteString(HexPrefix.nibblesToBytes(fullNibbles))
            accountWithStorage(accounts, owner) match
              case Left(_) => seen.copy(unreachable = seen.unreachable + 1)
              case Right(account) =>
                try
                  val value = account.flatMap { held =>
                    MerklePatriciaTrie[ByteString, Array[Byte]](
                      held.storageRoot.value.toArray,
                      rows.forOwner(Some(owner))
                    ).get(slot)
                  }
                  if value.exists(Arrays.equals(_, leaf.value.toArray)) then seen.copy(found = seen.found + 1)
                  else seen.copy(stale = seen.stale + 1)
                catch case _: MPTException => seen.copy(missing = seen.missing + 1)
    }

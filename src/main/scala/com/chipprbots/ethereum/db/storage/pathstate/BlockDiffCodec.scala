package com.chipprbots.ethereum.db.storage.pathstate

import java.io.ByteArrayOutputStream
import java.util.Arrays
import java.util.zip.CRC32C

import org.apache.pekko.util.ByteString

/** The changeset record (spec 018 data-model 4.2): a [[BlockDiff]] as the bytes kept in CF `v` under `blockHash ++
  * kind`. Kind 1, version 1. '''The layout is frozen''': golden vectors pin it byte for byte, and any change to it is a
  * new layout marker and a re-sync, never a second reader (data-model 4.4).
  *
  * {{{
  * version(1) = 01 | kind(1) = 01 | flags(1) | blockNumber(8) | blockHash(32) | parentHash(32)
  *   | preStateRoot(32) | postStateRoot(32) | entryCount(uvarint) | entries | crc32c(4)
  * entry: tag(1) | keyLength(uvarint) | key | prior | next
  * prior, next: present(1); if 1: length(uvarint) | rlp
  * }}}
  *
  * Integers are big-endian. `uvarint` is LEB128 in at most 9 bytes. `tag` is 1 for the account trie and 2 for a storage
  * trie, and `key` is the row's key in its column family (`HP(path)`, after the 32-byte owner for a storage trie), so
  * that applying an entry is one put or one delete with nothing encoded again. Entries are sorted by `(tag, key)`,
  * bytes compared unsigned, and unique. The CRC is CRC32C (Castagnoli) of every byte before it.
  *
  * The codec checks '''structure only''': what [[decode]] rejects is listed in [[BlockDiffCodec.Rejection]]. It does
  * not ask whether a node is large enough to be stored or whether a prior differs from its next: those are the writer's
  * rules ([[BlockDiff.validate]]). What it accepts it re-encodes to the same bytes, which is why a length written in
  * more bytes than it needs is refused.
  */
object BlockDiffCodec:

  val Version: Byte = 0x01

  /** The local part of a block's record: the undo of its trie nodes. Kind 2 is reserved for the exportable write set
    * (spec 025) under its own key, and is not written.
    */
  val Kind: Byte = 0x01

  /** Reserved (entries compressed): a record with it set is refused. */
  val FlagCompressed: Int = 0x01

  /** The record is a tombstone: it has no entries, and no move may cross it. */
  val FlagIncomplete: Int = 0x02

  val TagAccountTrie: Byte = 0x01
  val TagStorageTrie: Byte = 0x02

  private val HashLength = BlockDiff.HashLength
  private val HeaderLength = 3 + 8 + 4 * HashLength
  private val CrcLength = 4
  private val MaxVarintBytes = 9

  /** Why a record was refused. */
  enum Rejection:
    case UnknownVersion, UnknownKind, UnknownFlags

    /** The record's kind or block hash is not the one its key names: a key and value were mixed up. */
    case KeyMismatch
    case Truncated, TrailingBytes, BadCrc

    /** A length written in more bytes than it needs, or in more than nine. */
    case BadVarint
    case UnknownTag

    /** A key that is no row key of its column family: not a hex-prefix path, or too short to hold an owner. */
    case BadKey
    case BadPresence, BothAbsent, Unsorted, Duplicate, TombstoneWithEntries

  final case class Rejected(reason: Rejection, detail: String)

  /** The key of `blockHash`'s record in CF `v`. */
  def key(blockHash: ByteString): Array[Byte] =
    require(blockHash.length == HashLength, "a block hash is 32 bytes")
    (blockHash :+ Kind).toArray

  def crc32c(bytes: Array[Byte], offset: Int, length: Int): Int =
    val crc = new CRC32C
    crc.update(bytes, offset, length)
    crc.getValue.toInt

  /** The record of `diff`. A diff with no structure to write (a hash that is not 32 bytes, a number past 8 bytes, a
    * tombstone with entries, an entry with neither side) is a bug in its writer and throws.
    */
  def encode(diff: BlockDiff): Array[Byte] =
    require(
      Seq(diff.blockHash, diff.parentHash, diff.preStateRoot, diff.postStateRoot).forall(_.length == HashLength),
      "a block hash or state root is not 32 bytes"
    )
    require(diff.number >= 0 && diff.number <= BlockDiff.MaxNumber, s"block number ${diff.number} does not fit 8 bytes")
    require(!diff.incomplete || diff.changes.isEmpty, "a tombstone has no entries")

    val out = new ByteArrayOutputStream(HeaderLength + 16 + diff.changes.size * 96)
    out.write(Version)
    out.write(Kind)
    out.write(if diff.incomplete then FlagIncomplete else 0)
    writeLong(out, diff.number.toLong)
    Seq(diff.blockHash, diff.parentHash, diff.preStateRoot, diff.postStateRoot).foreach(hash => out.write(hash.toArray))

    val entries = diff.changes.toSeq
      .map { case (position, change) =>
        (if position.owner.isEmpty then TagAccountTrie else TagStorageTrie, position.rowKey, change, position)
      }
      .sortWith { case ((tagA, keyA, _, _), (tagB, keyB, _, _)) =>
        tagA < tagB || (tagA == tagB && Arrays.compareUnsigned(keyA, keyB) < 0)
      }
    writeVarint(out, entries.size.toLong)
    entries.foreach { case (tag, key, change, position) =>
      require(change.prior.isDefined || change.next.isDefined, s"${position.show}: neither a prior nor a next")
      out.write(tag)
      writeVarint(out, key.length.toLong)
      out.write(key)
      writeSide(out, change.prior)
      writeSide(out, change.next)
    }

    val body = out.toByteArray
    val crc = crc32c(body, 0, body.length)
    val record = Arrays.copyOf(body, body.length + CrcLength)
    record(body.length) = (crc >>> 24).toByte
    record(body.length + 1) = (crc >>> 16).toByte
    record(body.length + 2) = (crc >>> 8).toByte
    record(body.length + 3) = crc.toByte
    record

  /** How many bytes [[encode]] makes of `diff`, without making them: what a writer holds against its bound on a
    * record's size before it decides to write the record at all.
    */
  def encodedLength(diff: BlockDiff): Long =
    def side(node: Option[ByteString]): Long = node.fold(1L)(rlp => 1L + varintLength(rlp.length.toLong) + rlp.length)
    diff.changes.foldLeft(HeaderLength.toLong + varintLength(diff.changes.size.toLong) + CrcLength) {
      case (total, (position, change)) =>
        val keyLength = position.owner.fold(0)(_.length) + position.hp.length
        total + 1 + varintLength(keyLength.toLong) + keyLength + side(change.prior) + side(change.next)
    }

  private def varintLength(value: Long): Int =
    var rest = value
    var bytes = 1
    while (rest & ~0x7fL) != 0 do
      bytes += 1
      rest >>>= 7
    bytes

  private def writeLong(out: ByteArrayOutputStream, value: Long): Unit =
    var shift = 56
    while shift >= 0 do
      out.write((value >>> shift).toInt & 0xff)
      shift -= 8

  private def writeVarint(out: ByteArrayOutputStream, value: Long): Unit =
    var rest = value
    while (rest & ~0x7fL) != 0 do
      out.write(((rest & 0x7f) | 0x80).toInt)
      rest >>>= 7
    out.write(rest.toInt)

  private def writeSide(out: ByteArrayOutputStream, side: Option[ByteString]): Unit = side match
    case None => out.write(0)
    case Some(rlp) =>
      out.write(1)
      writeVarint(out, rlp.length.toLong)
      out.write(rlp.toArray)

  /** The diff a record holds, read as the value stored under `key`, or why it is refused. */
  def decode(key: Array[Byte], record: Array[Byte]): Either[Rejected, BlockDiff] =
    try Right(new Reader(key, record).read())
    catch case Refuse(rejected) => Left(rejected)

  /** What a record says of its block before its entries: the block, its parent, the two state roots, and whether it is
    * a tombstone. The record is held to its key and its checksum as [[decode]] holds it; the entries are not parsed.
    */
  final case class Header(
      blockHash: ByteString,
      number: BigInt,
      parentHash: ByteString,
      preStateRoot: ByteString,
      postStateRoot: ByteString,
      incomplete: Boolean
  )

  def decodeHeader(key: Array[Byte], record: Array[Byte]): Either[Rejected, Header] =
    try Right(new Reader(key, record).header())
    catch case Refuse(rejected) => Left(rejected)

  final private case class Refuse(rejected: Rejected) extends RuntimeException(null, null, false, false)

  private def refuse(reason: Rejection, detail: String): Nothing = throw Refuse(Rejected(reason, detail))

  final private class Reader(key: Array[Byte], record: Array[Byte]):
    private val end = record.length - CrcLength
    private var at = 0

    def read(): BlockDiff =
      val of = header()
      val count = varint("the entry count")
      if of.incomplete && count != 0 then refuse(Rejection.TombstoneWithEntries, s"a tombstone with $count entries")
      entries(of, count)

    def header(): Header =
      if key.length != HashLength + 1 then refuse(Rejection.KeyMismatch, s"a record key is 33 bytes, not ${key.length}")
      if record.isEmpty then refuse(Rejection.Truncated, "an empty record")
      // The version comes first, alone: another version may lay out everything after it differently.
      if record(0) != Version then refuse(Rejection.UnknownVersion, s"version ${record(0) & 0xff}")
      if record.length < HeaderLength + 1 + CrcLength then
        refuse(Rejection.Truncated, s"${record.length} bytes cannot hold a record")
      if record(1) != Kind then refuse(Rejection.UnknownKind, s"kind ${record(1) & 0xff}")
      if record(1) != key(HashLength) then
        refuse(Rejection.KeyMismatch, s"kind ${record(1) & 0xff} under a key of kind ${key(HashLength) & 0xff}")
      val flags = record(2) & 0xff
      if (flags & ~FlagIncomplete) != 0 then refuse(Rejection.UnknownFlags, f"flags $flags%02x")

      val stored =
        ((record(end) & 0xff) << 24) | ((record(end + 1) & 0xff) << 16) | ((record(end + 2) & 0xff) << 8) |
          (record(end + 3) & 0xff)
      val computed = crc32c(record, 0, end)
      if stored != computed then refuse(Rejection.BadCrc, f"crc32c $stored%08x, computed $computed%08x")

      at = 3
      val number = BigInt(1, take(8, "the block number"))
      val blockHash = ByteString(take(HashLength, "the block hash"))
      if !Arrays.equals(blockHash.toArray, 0, HashLength, key, 0, HashLength) then
        refuse(Rejection.KeyMismatch, "the record's block hash is not its key's")
      val parentHash = ByteString(take(HashLength, "the parent hash"))
      val preStateRoot = ByteString(take(HashLength, "the pre-state root"))
      val postStateRoot = ByteString(take(HashLength, "the post-state root"))
      Header(blockHash, number, parentHash, preStateRoot, postStateRoot, (flags & FlagIncomplete) != 0)

    private def entries(of: Header, count: Long): BlockDiff =
      val changes = Map.newBuilder[PositionKey, Change]
      var previousTag: Byte = 0
      var previousKey: Array[Byte] = Array.emptyByteArray
      var read = 0L
      while read < count do
        val tag = take(1, "an entry's tag")(0)
        if tag != TagAccountTrie && tag != TagStorageTrie then refuse(Rejection.UnknownTag, s"tag ${tag & 0xff}")
        val entryKey = take(length("a key"), "a key")
        if read > 0 then
          val order =
            if tag != previousTag then java.lang.Byte.compare(tag, previousTag)
            else Arrays.compareUnsigned(entryKey, previousKey)
          if order == 0 then refuse(Rejection.Duplicate, s"entry $read repeats the key before it")
          if order < 0 then refuse(Rejection.Unsorted, s"entry $read sorts before the one before it")
        val position = positionOf(tag, entryKey)
        val prior = side("a prior")
        val next = side("a next")
        if prior.isEmpty && next.isEmpty then
          refuse(Rejection.BothAbsent, s"entry $read has neither a prior nor a next")
        changes += position -> Change(prior, next)
        previousTag = tag
        previousKey = entryKey
        read += 1

      if at != end then refuse(Rejection.TrailingBytes, s"${end - at} bytes after the last entry")
      BlockDiff(
        of.blockHash,
        of.number,
        of.parentHash,
        of.preStateRoot,
        of.postStateRoot,
        changes.result(),
        of.incomplete
      )

    private def take(length: Int, what: String): Array[Byte] =
      if length < 0 || length > end - at then refuse(Rejection.Truncated, s"the record ends inside $what")
      val bytes = Arrays.copyOfRange(record, at, at + length)
      at += length
      bytes

    /** LEB128, at most nine bytes, and in as few bytes as the value needs. */
    private def varint(what: String): Long =
      var value = 0L
      var shift = 0
      var bytes = 0
      var more = true
      while more do
        if at >= end then refuse(Rejection.Truncated, s"the record ends inside $what")
        if bytes == MaxVarintBytes then refuse(Rejection.BadVarint, s"$what is longer than $MaxVarintBytes bytes")
        val b = record(at) & 0xff
        at += 1
        bytes += 1
        value |= (b & 0x7fL) << shift
        shift += 7
        more = (b & 0x80) != 0
        if !more && b == 0 && bytes > 1 then
          refuse(Rejection.BadVarint, s"$what is written in more bytes than it needs")
      value

    private def length(what: String): Int =
      val value = varint(s"the length of $what")
      if value > end - at then refuse(Rejection.Truncated, s"the record ends inside $what")
      value.toInt

    private def side(what: String): Option[ByteString] =
      take(1, s"$what's presence")(0) match
        case 0     => None
        case 1     => Some(ByteString(take(length(what), what)))
        case other => refuse(Rejection.BadPresence, s"presence ${other & 0xff} for $what")

    private def positionOf(tag: Byte, entryKey: Array[Byte]): PositionKey =
      val ownerLength = if tag == TagStorageTrie then PositionKey.OwnerLength else 0
      if entryKey.length <= ownerLength then
        refuse(Rejection.BadKey, s"a key of ${entryKey.length} bytes holds no path for tag ${tag & 0xff}")
      // A hex-prefix path that is not a leaf's: its first nibble is 0 (even length, then a zero nibble) or 1 (odd).
      val first = entryKey(ownerLength) & 0xff
      if (first >> 4) > 1 || ((first >> 4) == 0 && (first & 0x0f) != 0) then
        refuse(Rejection.BadKey, f"a path that begins $first%02x is not a hex-prefix path")
      val (owner, hp) = ByteString(entryKey).splitAt(ownerLength)
      PositionKey(Option.when(ownerLength > 0)(owner), hp)

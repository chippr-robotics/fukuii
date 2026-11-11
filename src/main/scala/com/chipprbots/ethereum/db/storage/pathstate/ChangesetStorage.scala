package com.chipprbots.ethereum.db.storage.pathstate

import java.nio.ByteBuffer

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.storage.Namespaces

/** The changeset records of CF `v` (spec 018 data-model 4.1, 4.5): for each block whose state change is kept, its
  * [[BlockDiff]] under `blockHash ++ kind`, and a height index that says which blocks have a record at each height.
  *
  * {{{
  * blockHash(32) ++ 0x01     -> the record (BlockDiffCodec)            33-byte key
  * 0x00 ++ number(8, BE)     -> the hashes with a record at that height  9-byte key, 32 bytes per hash
  * }}}
  *
  * The two kinds of key differ in length, so a point read never takes one for the other. '''A range scan would''': a
  * record key whose block hash begins with a zero byte sorts among the height keys or before them, and on a
  * proof-of-work chain that is every block (an ETC mainnet hash begins with about six). A scan for the lowest height
  * would walk every such record first, values included. So this storage has no scan: it prunes by point reads of the
  * heights it is given, and its caller keeps the height it has pruned up to.
  *
  * Every write is offered as updates for the caller's batch: a record belongs in the same atomic write as the rows it
  * describes (data-model 7.1), which is the staged commit's to build (C3b). Building them reads the index and writes it
  * back, which is only right under the state lock, so each asks for [[StateLockHeld]]. Nothing reads or writes this
  * storage until then.
  */
final class ChangesetStorage(val dataSource: DataSource):
  import ChangesetStorage.*

  private val namespace: IndexedSeq[Byte] = Namespaces.StateChangesetNamespace

  /** The updates that record `diff`: its record, and its hash in the index of its height. Recording a block a second
    * time writes the same record and leaves the index as it is.
    */
  def recordUpdates(diff: BlockDiff)(using StateLockHeld): Seq[DataSourceUpdateOptimized] =
    val record = recordKey(diff.blockHash) -> BlockDiffCodec.encode(diff)
    val known = hashesAt(diff.number)
    val index =
      Option.when(!known.contains(diff.blockHash))(
        heightKey(diff.number) -> (known :+ diff.blockHash).foldLeft(ByteString.empty)(_ ++ _).toArray
      )
    Seq(DataSourceUpdateOptimized(namespace, toRemove = Nil, toUpsert = record +: index.toSeq))

  /** [[recordUpdates]], written as a batch of its own: for tests of this package. A node writes a record in the batch
    * that writes the rows it describes, and nowhere else.
    */
  private[pathstate] def put(diff: BlockDiff)(using StateLockHeld): Unit = dataSource.update(recordUpdates(diff))

  /** The record of `blockHash`: `None` when there is none, and why it was refused when what is stored is not one. */
  def get(blockHash: ByteString): Option[Either[BlockDiffCodec.Rejected, BlockDiff]] =
    val key = recordKey(blockHash)
    dataSource.getOptimized(namespace, key).map(BlockDiffCodec.decode(key, _))

  /** The hashes of the blocks that have a record at height `number`, in the order they were recorded. */
  def hashesAt(number: BigInt): Seq[ByteString] =
    dataSource.getOptimized(namespace, heightKey(number)).fold(Seq.empty[ByteString]) { value =>
      if value.length % HashLength != 0 then throw new CorruptIndex(number, value.length)
      ByteString(value).grouped(HashLength).toSeq
    }

  /** The updates that delete the records of heights `from` (inclusive) to `floor` (exclusive), at most `maxHeights`
    * heights of them, with their index entries; and the height the next call starts from. A height with no record costs
    * one read. Deleting what is already gone changes nothing, so a batch that was lost to a crash is simply built again
    * (data-model 4.5).
    */
  def pruneUpdates(
      from: BigInt,
      floor: BigInt,
      maxHeights: Int = DefaultPruneHeights
  )(using StateLockHeld): (Seq[DataSourceUpdateOptimized], BigInt) =
    val until = floor.min(from + maxHeights)
    if until <= from then (Nil, from)
    else
      val heights = Iterator.iterate(from)(_ + 1).takeWhile(_ < until).toSeq
      val keys = heights.flatMap { height =>
        val hashes = hashesAt(height)
        if hashes.isEmpty then Nil else heightKey(height) +: hashes.map(recordKey)
      }
      (if keys.isEmpty then Nil else Seq(DataSourceUpdateOptimized(namespace, toRemove = keys, toUpsert = Nil)), until)

  /** [[pruneUpdates]], written as a batch of its own, for tests of this package. Returns the height the next call
    * starts from.
    */
  private[pathstate] def prune(from: BigInt, floor: BigInt, maxHeights: Int = DefaultPruneHeights)(using
      StateLockHeld
  ): BigInt =
    val (updates, next) = pruneUpdates(from, floor, maxHeights)
    if updates.nonEmpty then dataSource.update(updates)
    next

object ChangesetStorage:
  private val HashLength = BlockDiff.HashLength

  /** The index of a height is not a list of hashes: the datadir's corruption, and no fault of any block. */
  final class CorruptIndex(val number: BigInt, val length: Int)
      extends IllegalStateException(
        s"the index of changeset records at height $number is $length bytes, which is not a list of 32-byte hashes"
      )

  /** The first byte of a height-index key. */
  val HeightPrefix: Byte = 0x00

  val HeightKeyLength: Int = 9

  /** How many heights one commit's batch prunes: enough to follow the head, and to catch up when the floor moved. */
  val DefaultPruneHeights: Int = 16

  /** The key of `blockHash`'s record: its hash, then the kind of record. */
  def recordKey(blockHash: ByteString): Array[Byte] = BlockDiffCodec.key(blockHash)

  /** The key of the index of height `number`. */
  def heightKey(number: BigInt): Array[Byte] =
    require(number >= 0 && number <= BlockDiff.MaxNumber, s"block number $number does not fit 8 bytes")
    ByteBuffer.allocate(HeightKeyLength).put(HeightPrefix).putLong(number.toLong).array()

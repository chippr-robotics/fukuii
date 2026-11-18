package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.RepairOutcome

/** What the audit and the importer's on-demand fetch ask of the position store (spec 018 data-model 7.5): reads of the
  * rows that bypass every cache, and the two writes that are scoped to a position and judged under the state lock
  * against the anchor's root. [[PathStateStorage]] is the one implementation; a narrower trait so that the audit does
  * not depend on the whole store, and a spec can stand in for it.
  */
trait PositionRepairs:

  /** The block whose state the rows hold, if any: the root every judgement here is made under. */
  def anchor: Option[BlockRef]

  /** The rows at the positions asked for, one answer each in the order asked, read directly from CF `t` and `u` and
    * filling no cache. An answer is what the row holds: `Absent`, or its bytes (judged by the caller against the hash
    * it expects there). A read is not made under the state lock, so it may straddle a move: a caller that finds a
    * mismatch looks the position up again from the anchor's root before it believes it.
    */
  def rawRowsAt(requests: Seq[(PositionKey, ByteString)]): Seq[RawNodeRead]

  /** Writes `bytes` at `position` if `keccak(bytes) == expectedHash`, the anchor's root names `expectedHash` there, and
    * the row is absent or an undecodable entry: one point write, under the state lock, with the anchor compare-and-set
    * (data-model 7.0, 7.5). Never lowers anything, and never overwrites a node that decodes and is another than the one
    * expected ([[RepairOutcome.DifferentNode]]). With no anchor, or one that does not reach `expectedHash` there, it
    * declines ([[RepairOutcome.NotReached]]).
    */
  def repairNodeAt(position: PositionKey, expectedHash: ByteString, bytes: Array[Byte]): RepairOutcome

  /** Deletes the row at `position` if it is provably bad for `expectedHash` and the anchor's root still needs
    * `expectedHash` there: judged under the state lock from the row read there and then, so a stale verdict of the
    * caller's deletes nothing. A node that decodes and is not `expectedHash` is never deleted
    * ([[BadEntryDelete.DifferentNode]]); a row that hashes to `expectedHash`, or is gone, is
    * [[BadEntryDelete.NoLongerBad]].
    */
  def deleteBadNodeAt(position: PositionKey, expectedHash: ByteString): BadEntryDelete

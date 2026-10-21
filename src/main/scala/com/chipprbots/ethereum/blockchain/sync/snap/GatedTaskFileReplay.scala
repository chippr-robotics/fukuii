package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Path

/** Bounded, gated reader over a fixed-size-entry task file (spec 014).
  *
  * The accounts-complete recovery used to read the whole persisted storage-task file (12M entries on Sepolia) in one
  * Future and push every entry into the coordinator's mailbox. This reader hands out at most `chunkEntries` entries per
  * step, and only while `gate` reports no reason to wait; the caller re-schedules a paused step instead of blocking a
  * thread. Every entry in `[0, endEntry)` is emitted exactly once, in file order.
  *
  * Not thread-safe: drive it from one sequential chain (an actor, or a Future chain whose steps happen-before each
  * other).
  *
  * @param gate
  *   `Some(reason)` while new work must wait (the intake gate); `None` to proceed
  */
final class GatedTaskFileReplay(
    path: Path,
    entrySize: Int,
    endEntry: Long,
    chunkEntries: Int,
    gate: () => Option[String]
):
  require(entrySize > 0 && chunkEntries > 0, s"entrySize=$entrySize chunkEntries=$chunkEntries must be positive")

  private var offset: Long = 0L

  /** Entries emitted so far. */
  def position: Long = offset

  def done: Boolean = offset >= endEntry

  /** Emit the next chunk (copies of its entries) if the gate is open. The offset advances before `emit` runs: an
    * exception from `emit` fails the replay loudly rather than re-emitting a chunk that may have been half-sent.
    */
  def step(emit: Vector[Array[Byte]] => Unit): GatedTaskFileReplay.Step =
    if done then GatedTaskFileReplay.Step.Done
    else
      gate() match
        case Some(reason) => GatedTaskFileReplay.Step.Paused(reason)
        case None =>
          val until = math.min(endEntry, offset + chunkEntries)
          val chunk = Vector.newBuilder[Array[Byte]]
          StorageTaskFile.foreachEntry(path, entrySize, offset, until)(entry => chunk += entry.clone())
          offset = until
          val entries = chunk.result()
          emit(entries)
          GatedTaskFileReplay.Step.Read(entries.size)

object GatedTaskFileReplay:

  /** Matches AccountRangeCoordinator's carried-replay chunk: 4096 × 64 B = 256 KiB of file per step. */
  val DefaultChunkEntries: Int = 4096

  enum Step:
    case Read(entries: Int)
    case Paused(reason: String)
    case Done

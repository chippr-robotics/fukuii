package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 014: the gated, chunked task-file replay that replaces the accounts-complete recovery's whole-file stream.
  * Driven step by step on the test thread; no actors, no sleeps.
  */
class GatedTaskFileReplaySpec extends AnyFlatSpec with Matchers:

  private val EntrySize = 8

  /** A file of `n` entries; entry `i` holds `i` big-endian in all 8 bytes, so order and identity are checkable. */
  private def withTaskFile(n: Int)(test: Path => Unit): Unit =
    val path = Files.createTempFile("gated-replay-", ".bin")
    try
      val bytes = new Array[Byte](n * EntrySize)
      (0 until n).foreach { i =>
        java.nio.ByteBuffer.wrap(bytes, i * EntrySize, EntrySize).putLong(i.toLong)
      }
      Files.write(path, bytes)
      test(path)
    finally
      val _ = Files.deleteIfExists(path)

  private def entryIndex(entry: Array[Byte]): Long = java.nio.ByteBuffer.wrap(entry).getLong

  "GatedTaskFileReplay" should "emit every entry exactly once, in order, in bounded chunks" taggedAs UnitTest in {
    withTaskFile(10) { path =>
      val replay = new GatedTaskFileReplay(path, EntrySize, endEntry = 10L, chunkEntries = 4, gate = () => None)
      val chunks = mutable.ArrayBuffer.empty[Vector[Long]]
      val steps = Iterator
        .continually(replay.step(chunk => chunks += chunk.map(entryIndex)))
        .takeWhile(_ != GatedTaskFileReplay.Step.Done)
        .toList
      steps shouldBe List(
        GatedTaskFileReplay.Step.Read(4),
        GatedTaskFileReplay.Step.Read(4),
        GatedTaskFileReplay.Step.Read(2)
      )
      chunks.toList shouldBe List(Vector(0L, 1L, 2L, 3L), Vector(4L, 5L, 6L, 7L), Vector(8L, 9L))
      replay.done shouldBe true
      replay.position shouldBe 10L
    }
  }

  it should "read nothing and keep its position while the gate is closed" taggedAs UnitTest in {
    withTaskFile(6) { path =>
      var closed: Option[String] = Some("storage tasks pending 100 >= ceiling 100")
      val replay = new GatedTaskFileReplay(path, EntrySize, endEntry = 6L, chunkEntries = 4, gate = () => closed)
      val emitted = mutable.ArrayBuffer.empty[Long]
      replay.step(c => emitted ++= c.map(entryIndex)) shouldBe GatedTaskFileReplay.Step.Paused(closed.get)
      replay.step(c => emitted ++= c.map(entryIndex)) shouldBe GatedTaskFileReplay.Step.Paused(closed.get)
      emitted shouldBe empty
      replay.position shouldBe 0L

      closed = None
      replay.step(c => emitted ++= c.map(entryIndex)) shouldBe GatedTaskFileReplay.Step.Read(4)
      closed = Some("heap pressure")
      replay.step(c => emitted ++= c.map(entryIndex)) shouldBe GatedTaskFileReplay.Step.Paused("heap pressure")
      closed = None
      replay.step(c => emitted ++= c.map(entryIndex)) shouldBe GatedTaskFileReplay.Step.Read(2)
      replay.step(c => emitted ++= c.map(entryIndex)) shouldBe GatedTaskFileReplay.Step.Done
      emitted.toList shouldBe (0L until 6L).toList
    }
  }

  it should "only cover entries below endEntry (a file may be longer than its count)" taggedAs UnitTest in {
    withTaskFile(10) { path =>
      val replay = new GatedTaskFileReplay(path, EntrySize, endEntry = 5L, chunkEntries = 3, gate = () => None)
      val emitted = mutable.ArrayBuffer.empty[Long]
      while !replay.done do replay.step(c => emitted ++= c.map(entryIndex))
      emitted.toList shouldBe (0L until 5L).toList
    }
  }

  it should "hand out copies, not the reader's reused buffer" taggedAs UnitTest in {
    withTaskFile(3) { path =>
      val replay = new GatedTaskFileReplay(path, EntrySize, endEntry = 3L, chunkEntries = 3, gate = () => None)
      var kept = Vector.empty[Array[Byte]]
      replay.step(c => kept = c)
      kept.map(entryIndex) shouldBe Vector(0L, 1L, 2L)
    }
  }

  it should "fail loudly on a file shorter than its entry count" taggedAs UnitTest in {
    withTaskFile(2) { path =>
      val replay = new GatedTaskFileReplay(path, EntrySize, endEntry = 4L, chunkEntries = 4, gate = () => None)
      an[java.io.IOException] should be thrownBy replay.step(_ => ())
    }
  }

  it should "hold the pending storage work at the intake ceiling while a slow consumer drains" taggedAs UnitTest in {
    // End to end with the real gate: the producer reserves what it sends, the consumer acknowledges and drains slowly.
    // However far the file runs ahead of the consumer, pending work never exceeds ceiling + one chunk.
    withTaskFile(1000) { path =>
      val ceiling = 100L
      val chunk = 30
      val budget = new SnapIntakeBudget(maxPendingStorageTasks = ceiling, maxPendingByteCodeHashes = 1000L)
      val replay = new GatedTaskFileReplay(path, EntrySize, 1000L, chunk, () => budget.intakeBlockedReason())
      val consumerQueue = mutable.Queue.empty[Long]
      val inMailbox = mutable.Queue.empty[Vector[Long]]
      var maxPending = 0L
      var drained = 0L
      var paused = 0

      while !replay.done || inMailbox.nonEmpty || consumerQueue.nonEmpty do
        replay.step { entries =>
          budget.reserve(entries.size, 0)
          inMailbox.enqueue(entries.map(entryIndex))
        } match
          case GatedTaskFileReplay.Step.Paused(_) => paused += 1
          case _                                  => ()
        maxPending = math.max(maxPending, budget.pendingStorageTasks)
        // The consumer processes one message and dispatches 7 tasks per round — slower than the producer.
        if inMailbox.nonEmpty then
          val msg = inMailbox.dequeue()
          consumerQueue.enqueueAll(msg)
          budget.storageReceived(msg.size, consumerQueue.size.toLong)
        (1 to 7).foreach { _ =>
          if consumerQueue.nonEmpty then
            val _ = consumerQueue.dequeue()
            drained += 1
        }
        budget.storageQueueDepth(consumerQueue.size.toLong)

      drained shouldBe 1000L
      paused should be > 0
      maxPending should be <= (ceiling + chunk)
    }
  }

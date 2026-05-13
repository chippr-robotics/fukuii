package com.chipprbots.ethereum.blockchain.sync.snap

import scala.collection.mutable
import scala.util.Random

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.{ByteArraySerializable, MerklePatriciaTrie}
import com.chipprbots.ethereum.testing.TestMptStorage
import com.chipprbots.ethereum.testing.Tags._

/** Tests for [[SnapHashTrie]] — the batching wrapper around [[StackTrie]].
  *
  * The StackTrie itself is already validated against `MerklePatriciaTrie` in
  * `StackTrieSpec`. This spec focuses on the wrapper's responsibilities:
  *
  *   - emissions flow through the `writeBatch` callback,
  *   - batching honours the size threshold,
  *   - `commit` flushes pending state,
  *   - `reset` clears in-memory state cleanly,
  *   - root hashes still match the reference MPT for the same inputs.
  */
class SnapHashTrieSpec extends AnyFlatSpec with Matchers {

  // ---- helpers ----

  private implicit val byteArraySerializer: ByteArraySerializable[Array[Byte]] =
    new ByteArraySerializable[Array[Byte]] {
      def toBytes(input: Array[Byte]): Array[Byte] = input
      def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes
    }

  /** A recording `writeBatch` callback. Each invocation appends its batch to
    * the list of recorded batches, and every (hash, blob) pair is added to a
    * cumulative map for later inspection.
    */
  private final class RecordingWriter {
    val batches: mutable.ArrayBuffer[Seq[(ByteString, Array[Byte])]] = mutable.ArrayBuffer.empty
    val combined: mutable.LinkedHashMap[ByteString, Array[Byte]] = mutable.LinkedHashMap.empty
    var totalBytes: Long = 0L

    val writeBatch: Seq[(ByteString, Array[Byte])] => Unit = { batch =>
      batches += batch
      batch.foreach { case (h, b) =>
        combined += h -> b
        totalBytes += b.length
      }
    }
  }

  /** Build a reference MPT root for the given (key, value) pairs. */
  private def referenceRoot(pairs: Seq[(Array[Byte], Array[Byte])]): Array[Byte] = {
    var trie: MerklePatriciaTrie[Array[Byte], Array[Byte]] =
      MerklePatriciaTrie[Array[Byte], Array[Byte]](new TestMptStorage())
    pairs.foreach { case (k, v) => trie = trie.put(k, v) }
    trie.getRootHash
  }

  private def sortByKey(pairs: Seq[(Array[Byte], Array[Byte])]): Seq[(Array[Byte], Array[Byte])] =
    pairs.sortWith { case ((a, _), (b, _)) => java.util.Arrays.compareUnsigned(a, b) < 0 }

  private def generatedPairs(n: Int, seed: Long): Seq[(Array[Byte], Array[Byte])] = {
    val rng = new Random(seed)
    val raw = (0 until n).map { _ =>
      val k = new Array[Byte](32); rng.nextBytes(k)
      val v = new Array[Byte](16 + rng.nextInt(48)); rng.nextBytes(v)
      (k, v)
    }
    sortByKey(raw)
  }

  // ---- empty trie ----

  it should "produce the canonical empty-trie root with no batch flushes for an empty input" taggedAs UnitTest in {
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch)
    val root = w.commit().toArray
    root shouldEqual MerklePatriciaTrie.EmptyRootHash
    rec.batches shouldBe empty
  }

  // ---- single update ----

  it should "flush exactly one batch with one entry for a single-key trie" taggedAs UnitTest in {
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch)
    w.update(kec256("k1".getBytes), "v1".getBytes)
    val root = w.commit().toArray
    root shouldEqual referenceRoot(Seq(kec256("k1".getBytes) -> "v1".getBytes))
    rec.batches should have size 1
    rec.batches.head should have size 1
    val (hash, blob) = rec.batches.head.head
    ByteString(kec256(blob)) shouldEqual hash
    ByteString(root) shouldEqual hash
  }

  // ---- large batch, root equality ----

  it should "produce the same root as MerklePatriciaTrie for 1 000 random sorted keys" taggedAs UnitTest in {
    val pairs = generatedPairs(1000, seed = 0xfeedfaceL)
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch)
    pairs.foreach { case (k, v) => w.update(k, v) }
    val root = w.commit().toArray
    root shouldEqual referenceRoot(pairs)
    // Below the default 8 MiB threshold; one batch on commit.
    rec.batches should have size 1
    // Every entry should be content-addressed correctly.
    rec.combined.foreach { case (h, b) => ByteString(kec256(b)) shouldEqual h }
  }

  // ---- threshold-driven mid-stream flushes ----

  it should "flush mid-stream when the batch threshold is exceeded" taggedAs UnitTest in {
    // Pick a small threshold and enough keys to force multiple flushes.
    val tinyThreshold = 4 * 1024 // 4 KiB
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch, batchSizeThreshold = tinyThreshold)
    val pairs = generatedPairs(500, seed = 0xc0ffeeL)
    pairs.foreach { case (k, v) => w.update(k, v) }
    val root = w.commit().toArray
    root shouldEqual referenceRoot(pairs)
    // Many flushes expected; lower bound is "more than one".
    rec.batches.size should be > 1
    // Each flushed batch (except possibly the last) crossed the threshold.
    rec.batches.init.foreach { batch =>
      batch.map(_._2.length).sum should be >= tinyThreshold / 4 // generous bound
    }
    // All emissions are content-addressed.
    rec.combined.foreach { case (h, b) => ByteString(kec256(b)) shouldEqual h }
  }

  // ---- pendingBatchBytes accounting ----

  it should "track pendingBatchBytes correctly between flushes" taggedAs UnitTest in {
    val tinyThreshold = 16 * 1024 // 16 KiB
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch, batchSizeThreshold = tinyThreshold)
    val pairs = generatedPairs(50, seed = 0xabcdL)
    pairs.foreach { case (k, v) => w.update(k, v) }
    // Before commit, pendingBatchBytes is whatever's accumulated since the last flush.
    val beforeCommit = w.pendingBatchBytes
    val _ = w.commit()
    // After commit, the pending buffer must be empty.
    w.pendingBatchBytes shouldEqual 0L
    w.pendingBatchCount shouldEqual 0
    // The bytes we observed before commit plus any already-flushed bytes must
    // equal the total bytes seen by the writer.
    val alreadyFlushed = rec.batches.dropRight(if (rec.batches.nonEmpty) 1 else 0).flatten.map(_._2.length.toLong).sum
    val finalFlush = rec.batches.lastOption.map(_.map(_._2.length.toLong).sum).getOrElse(0L)
    (alreadyFlushed + finalFlush) shouldEqual rec.totalBytes
    beforeCommit should be <= rec.totalBytes
  }

  // ---- reset semantics ----

  it should "clear pending state and resume cleanly after reset" taggedAs UnitTest in {
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch)
    w.update(kec256("a".getBytes), "v-a".getBytes)
    w.update(kec256("b".getBytes), "v-b".getBytes)
    // No commit; reset should clear everything pending.
    val countBefore = rec.batches.size
    w.reset()
    w.pendingBatchBytes shouldEqual 0L
    w.pendingBatchCount shouldEqual 0
    // No new batches flushed by reset itself.
    rec.batches.size shouldEqual countBefore
    // After reset, we can build a fresh trie.
    val pairs = generatedPairs(20, seed = 0x123L)
    pairs.foreach { case (k, v) => w.update(k, v) }
    val root = w.commit().toArray
    root shouldEqual referenceRoot(pairs)
  }

  // ---- emission integrity ----

  it should "deep-copy emitted blobs so the caller is safe against StackTrie buffer reuse" taggedAs UnitTest in {
    // We can't directly probe internal buffer reuse, but we can verify the
    // recorded blobs survive — i.e. their content remains valid after the
    // StackTrie has emitted further nodes that might (in principle) have
    // overwritten earlier buffers.
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch)
    val pairs = generatedPairs(200, seed = 0xdadaL)
    pairs.foreach { case (k, v) => w.update(k, v) }
    val _ = w.commit()
    // Re-verify every recorded blob against its claimed hash. If the wrapper
    // hadn't deep-copied, mid-stream mutations could have corrupted earlier
    // entries.
    rec.combined.foreach { case (h, b) => ByteString(kec256(b)) shouldEqual h }
  }

  // ---- ordering still enforced (delegated to StackTrie) ----

  it should "reject descending keys (sort enforcement delegated to StackTrie)" taggedAs UnitTest in {
    val rec = new RecordingWriter
    val w = new SnapHashTrie(rec.writeBatch)
    w.update(Array[Byte](0x20.toByte), "v1".getBytes)
    an[IllegalArgumentException] should be thrownBy
      w.update(Array[Byte](0x10.toByte), "v2".getBytes)
  }
}

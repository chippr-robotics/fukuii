package com.chipprbots.ethereum.crypto

import java.util.concurrent.Executors

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 007 US1 — keccak-256 ThreadLocal digest reuse parity (FR-001..FR-005, INV-1..4).
  *
  * Proves the per-thread reused `KeccakDigest(256)` (reset-on-entry) is byte-for-byte identical to a per-call `new
  * KeccakDigest(256)`:
  *   - fixed golden vectors (independently verified against bcprov-jdk18on 1.84),
  *   - every public `kec256` overload agrees with an inline per-call oracle,
  *   - reset-after-abort: a hash that throws between `update` and `doFinal` on a thread does NOT corrupt the next hash
  *     on that SAME thread (the load-bearing INV-1/FR-002 guarantee),
  *   - concurrency: N threads hashing independent inputs all agree with the single-thread oracle,
  *   - bounded footprint: exactly one digest instance per thread, none shared (FR-009/SC-006 anchor).
  */
class Keccak256Spec extends AnyFlatSpec with Matchers:

  /** Independent oracle: a fresh `new KeccakDigest(256)` per call — exactly the pre-change impl. */
  private def oracle(inputs: Array[Byte]*): Array[Byte] =
    val d = new KeccakDigest(256)
    val out = Array.ofDim[Byte](d.getDigestSize)
    inputs.foreach(i => d.update(i, 0, i.length))
    d.doFinal(out, 0)
    out

  private def hex(bytes: Array[Byte]): String = Hex.toHexString(bytes)

  // Fixed-fill deterministic inputs (mirrors the bcprov oracle used to derive the golden vectors).
  private val emptyInput: Array[Byte] = Array.emptyByteArray
  private val abcInput: Array[Byte] = "abc".getBytes("US-ASCII")
  private val oneByteInput: Array[Byte] = Array(0x42.toByte)
  private val thirtyTwoByteInput: Array[Byte] = Array.tabulate(32)(i => i.toByte)
  private val emptyTrieRlp: Array[Byte] = Array(0x80.toByte)
  // ~532 bytes: a full branch-node-sized blob (max trie node size), deterministic fill.
  private val maxNodeInput: Array[Byte] = Array.tabulate(532)(i => ((i * 31 + 7) & 0xff).toByte)

  "kec256" should "match fixed golden vectors (verified against bcprov-jdk18on 1.84)" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    hex(kec256(emptyInput)) shouldBe "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
    hex(kec256(abcInput)) shouldBe "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
    hex(kec256(oneByteInput)) shouldBe "1f675bff07515f5df96737194ea945c36c41e7b4fcef307b7cd4d0e602a69111"
    hex(kec256(thirtyTwoByteInput)) shouldBe "8ae1aa597fa146ebd3aa2ceddf360668dea5e526567e92b0321816a4e895bd2d"
    hex(kec256(maxNodeInput)) shouldBe "574528f02548ffbf687fcdd37753bd14b234801dc480c575fb113d7101a701b7"
    // Canonical empty-trie root: keccak256(RLP("")) == keccak256(0x80).
    hex(kec256(emptyTrieRlp)) shouldBe "56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"
  }

  it should "produce the same 32-byte length and value as a per-call oracle for all inputs" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val corpus =
      Seq(emptyInput, abcInput, oneByteInput, thirtyTwoByteInput, emptyTrieRlp, maxNodeInput)
    corpus.foreach { in =>
      val got = kec256(in)
      got.length shouldBe 32
      got shouldBe oracle(in)
    }
  }

  it should "agree with the oracle for the (Array, start, length) overload over sub-ranges" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val buf = Array.tabulate(100)(i => (i * 7).toByte)
    // Hashing buf[10, 10+32) must equal hashing the isolated 32-byte slice.
    val viaRange = kec256(buf, 10, 32)
    val viaSlice = oracle(buf.slice(10, 42))
    viaRange shouldBe viaSlice
    // Whole-buffer range equals the single-arg form.
    kec256(buf, 0, buf.length) shouldBe kec256(buf)
  }

  it should "preserve update ordering / associativity for the varargs overload" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val a = Array.tabulate(13)(i => i.toByte)
    val b = Array.tabulate(29)(i => (i + 100).toByte)
    val c = Array.tabulate(7)(i => (i + 200).toByte)
    // update(a);update(b);update(c) == update(a ++ b ++ c)  (absorb is associative over concat).
    kec256(a, b, c) shouldBe oracle(a, b, c)
    kec256(a, b, c) shouldBe kec256(a ++ b ++ c)
    // Ordering is significant: swapping args changes the hash (sanity, not an equality).
    kec256(a, b) should not be kec256(b, a)
  }

  it should "delegate the ByteString overload to the Array form (byte-identical)" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val corpus = Seq(emptyInput, abcInput, thirtyTwoByteInput, maxNodeInput)
    corpus.foreach { in =>
      val bs = ByteString(in)
      kec256(bs) shouldBe ByteString(oracle(in))
      kec256(bs).toArray shouldBe kec256(in)
    }
  }

  "kec256PoW" should "equal hashing header ++ nonce via the oracle" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val header = Array.tabulate(40)(i => (i * 3).toByte)
    val nonce = Array.tabulate(8)(i => (255 - i).toByte)
    kec256PoW(header, nonce) shouldBe oracle(header, nonce)
    kec256PoW(header, nonce) shouldBe kec256(header ++ nonce)
  }

  // ---- T006: reset-after-abort (INV-1 / FR-002) — the load-bearing parity mechanism. ----

  "kec256 reset-on-entry" should "recover after a prior hash aborted between update and doFinal on the same thread" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    // Run the abort and the follow-up hash on ONE dedicated thread so the SAME ThreadLocal digest is
    // reused — a fresh thread would get a clean digest and would NOT catch a missing reset-on-entry.
    val pool = Executors.newSingleThreadExecutor()
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      // Step 1: dirty THIS thread's reused digest via update() and throw BEFORE doFinal — exactly the
      // aborted-mid-update window doFinal's trailing reset never covers (uses the production digest
      // through a test-only hook; the digest reference never escapes).
      val dirtied: Future[Unit] = Future(dirtyThreadDigestThenThrow())
      Await.ready(dirtied, 10.seconds)
      dirtied.value.get.isFailure shouldBe true // confirm it really threw

      // Step 2: the NEXT kec256 on the SAME thread must be correct (reset-on-entry discarded the
      // stale 5 bytes). Without reset-on-entry this would fold [1,2,3,4,5] ++ input -> wrong hash.
      val afterAbort: Future[Array[Byte]] = Future(kec256(abcInput))
      val result = Await.result(afterAbort, 10.seconds)
      result shouldBe oracle(abcInput)
      hex(result) shouldBe "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
    finally pool.shutdownNow()
  }

  // ---- T007: concurrency — no cross-thread bleed (FR-003 / FR-007). ----

  "kec256" should "produce oracle-correct hashes concurrently across many threads with no cross-thread bleed" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val nThreads = 16
    val perThreadHashes = 2000
    val pool = Executors.newFixedThreadPool(nThreads)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      // Each thread hashes a distinct, deterministic stream of inputs; every result is checked
      // against the single-thread oracle. Interleaved hashing on shared dispatcher threads must never
      // let one thread's absorbed bytes leak into another's result.
      val futures: Seq[Future[Boolean]] = (0 until nThreads).map { t =>
        Future {
          (0 until perThreadHashes).forall { i =>
            val input = Array.tabulate(((t * 31 + i) % 200) + 1)(j => ((t << 4) ^ (i + j)).toByte)
            java.util.Arrays.equals(kec256(input), oracle(input))
          }
        }
      }
      val results = Await.result(Future.sequence(futures), 60.seconds)
      results.foreach(_ shouldBe true)
    finally pool.shutdownNow()
  }

  // ---- T009b: bounded footprint — exactly one digest instance per thread, none shared. ----

  "kec256Digest ThreadLocal" should "hold exactly one digest instance per thread and share none across threads" taggedAs (
    UnitTest,
    CryptoTest
  ) in {
    val nThreads = 8
    val pool = Executors.newFixedThreadPool(nThreads)
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      // On each worker thread: hash a few times, capturing the digest IDENTITY (an Int, never the
      // reference — INV-2) before and after each hash. All samples on one thread must be identical
      // (one instance reused), and no identity may appear on two different threads (no sharing).
      val perThread: Seq[Future[(Long, Set[Int])]] = (0 until nThreads).map { _ =>
        Future {
          val ids = scala.collection.mutable.Set.empty[Int]
          (0 until 50).foreach { i =>
            ids += kec256DigestIdentityForCurrentThread()
            kec256(Array.tabulate(i + 1)(_.toByte))
            ids += kec256DigestIdentityForCurrentThread()
          }
          (Thread.currentThread().threadId(), ids.toSet)
        }
      }
      val samples = Await.result(Future.sequence(perThread), 30.seconds)

      // Exactly one digest identity observed per thread (instance reused, never reallocated).
      samples.foreach { case (_, ids) => ids.size shouldBe 1 }

      // No digest identity shared across distinct threads (thread-confinement holds). Because the
      // fixed pool reuses worker threads, collapse by threadId first so we compare the K distinct
      // platform threads that actually ran, then assert their digest identities are pairwise disjoint.
      val byThread: Map[Long, Set[Int]] =
        samples.groupBy(_._1).map { case (tid, grp) => tid -> grp.flatMap(_._2).toSet }
      // One digest identity per distinct thread, even after grouping (no reallocation across reuse).
      byThread.foreach { case (_, ids) => ids.size shouldBe 1 }
      val allIds = byThread.values.flatten.toSeq
      allIds.distinct.size shouldBe allIds.size // every distinct thread has a unique digest instance
    finally pool.shutdownNow()
  }

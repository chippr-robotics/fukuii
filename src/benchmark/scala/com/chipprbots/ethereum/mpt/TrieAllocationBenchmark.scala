package com.chipprbots.ethereum.mpt

import java.lang.management.ManagementFactory

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.testing.Tags.*

/** Bytes allocated per trie operation on a hash-keyed store, the store every chain runs on until spec 018 Track C
  * switches it. It is the comparison spec 018 calls M7, in the small: run it on a commit and on its parent
  * (`sbt "Benchmark / testOnly *TrieAllocationBenchmark"`) and compare the `[TRIE-ALLOC]` lines of the test report.
  *
  * It uses nothing but the trie's public API, so the same file compiles on either side of a change to the trie.
  * Allocation is counted for the calling thread (`ThreadMXBean.getThreadAllocatedBytes`, the idiom of spec 007), and
  * the workload is fixed, so the figures repeat to within a few bytes per operation.
  */
class TrieAllocationBenchmark extends AnyFunSuite:

  private val threads = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]

  private def allocatedBy(body: => Unit): Long =
    val thread = Thread.currentThread().threadId()
    val before = threads.getThreadAllocatedBytes(thread)
    body
    threads.getThreadAllocatedBytes(thread) - before

  private def key(i: Int): Array[Byte] =
    crypto.kec256(Array[Byte]((i >> 24).toByte, (i >> 16).toByte, (i >> 8).toByte, i.toByte))

  /** An account-sized value. */
  private def value(i: Int): Array[Byte] = Array.tabulate(70)(j => (i + j).toByte)

  test("bytes allocated per put, overwrite, get and remove on a hash-keyed store", BenchmarkTest) {
    val Size = 50000
    val Ops = 5000
    type Trie = MerklePatriciaTrie[Array[Byte], Array[Byte]]

    def build(): Trie =
      val empty: Trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](
        new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
      )
      (0 until Size).foldLeft(empty)((trie, i) => trie.put(key(i), value(i)))

    // Keys and values are made before the measured loops, so that only the trie's own allocation is counted.
    val newKeys = (Size until Size + Ops).map(key).toArray
    val oldKeys = (0 until Ops).map(i => key(i * 7)).toArray
    val values = (0 until Ops).map(i => value(i + 1)).toArray

    def round(report: Boolean): Unit =
      var trie = build()
      val puts = allocatedBy { var i = 0; while i < Ops do { trie = trie.put(newKeys(i), values(i)); i += 1 } }
      val overwrites = allocatedBy { var i = 0; while i < Ops do { trie = trie.put(oldKeys(i), values(i)); i += 1 } }
      val gets = allocatedBy { var i = 0; while i < Ops do { trie.get(oldKeys(i)); i += 1 } }
      val removes = allocatedBy { var i = 0; while i < Ops do { trie = trie.remove(newKeys(i)); i += 1 } }
      if report then
        info(
          s"[TRIE-ALLOC] size=$Size ops=$Ops bytes/op: put=${puts / Ops} overwrite=${overwrites / Ops} " +
            s"get=${gets / Ops} remove=${removes / Ops}"
        )

    round(report = false) // warm up: the JIT's escape analysis changes what is allocated
    round(report = false)
    round(report = true)
    round(report = true)
  }

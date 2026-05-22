package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.funsuite.AnyFunSuite

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.FlatAccountStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.testing.Tags._

/** Benchmark: 8MB flat account batch write.
  *
  * Verifies that writing one SNAP-sync flat-account flush cycle (the 8MB incremental-flush threshold)
  * to an in-memory store completes within 500ms. This guards against regressions in the
  * AccountRangeCoordinator flush path introduced by write-path hardening work (PR #1285).
  *
  * Run with: sbt "benchmark:testOnly *FlatAccountFlushBenchmark"
  */
class FlatAccountFlushBenchmark extends AnyFunSuite {

  test("write 8MB flat account batch (≈70K entries) completes within 500ms", BenchmarkTest) {
    val ds = EphemDataSource()
    val flatStorage = new FlatAccountStorage(ds)

    val emptyAcct = Account(
      nonce = 0,
      balance = com.chipprbots.ethereum.domain.UInt256.Zero,
      storageRoot = Account.EmptyStorageRootHash,
      codeHash = Account.EmptyCodeHash
    )
    val rlp = Account.accountSerializer.toBytes(emptyAcct)
    val rlpBS = ByteString.fromArrayUnsafe(rlp)

    // Each entry: 32 bytes (hash) + rlp.length bytes.
    // Target: 8MB total ≈ 8 * 1024 * 1024 / (32 + rlp.length) entries.
    val entryBytes = 32 + rlp.length
    val targetBytes = 8 * 1024 * 1024
    val entryCount = targetBytes / entryBytes

    val entries: Seq[(ByteString, ByteString)] = (0 until entryCount).map { i =>
      val hash = kec256(ByteString(s"bench-acct-$i"))
      hash -> rlpBS
    }

    assert(entries.size >= 50_000, s"Expected ≥50K entries for 8MB batch, got ${entries.size}")

    val startMs = System.currentTimeMillis()
    flatStorage.putAccountsBatch(entries).commit()
    val elapsedMs = System.currentTimeMillis() - startMs

    println(
      s"[FlatAccountFlushBenchmark] ${entries.size} entries (${targetBytes / 1024 / 1024}MB) " +
        s"written in ${elapsedMs}ms"
    )

    assert(
      elapsedMs < 500,
      s"Flat account flush took ${elapsedMs}ms — expected < 500ms. " +
        s"Possible regression in the AccountRangeCoordinator write path."
    )
  }
}

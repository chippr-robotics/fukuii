package com.chipprbots.ethereum.db.storage

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.testing.Tags.*

/** spec 005 (Subtree-Complete Heal Verification) — direct unit coverage of the NEW subtree-complete record API on
  * [[HealingFrontierStorage]] (CF `'g'`), covering test contract C1 and the storage half of T-1/T-5/T-6.
  *
  * Mirrors [[HealingFrontierStorageSpec]] exactly: standalone `AnyFlatSpec` (no actor), a real temp-dir RocksDB (NOT
  * `EphemDataSource` — the resume/`loadAll` path relies on RocksDB column families returning BARE keys; an ephemeral
  * source namespace-prefixes them), `UnitTest`-tagged, deterministic (no `Thread.sleep`).
  *
  * Records are presence-only: the value is the RLP-wrapped single byte `0x01`. These tests NEVER compare value bytes —
  * they assert on `isSubtreeComplete` / `multiIsSubtreeComplete` / key presence, exactly as the production oracle reads
  * them.
  */
class HealingFrontierStorageSubtreeSpec extends AnyFlatSpec with Matchers:

  private def hash(i: Int): ByteString = ByteString(Array.fill(32)(i.toByte))

  /** Open a fresh temp-dir RocksDB-backed store, run the body, destroy. */
  private def withStorage(test: HealingFrontierStorage => Unit): Unit =
    val dbPath = Files.createTempDirectory("healing-frontier-subtree-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(rocksDbConfig(dbPath), Namespaces.nsSeq)
    try test(new HealingFrontierStorage(dataSource))
    finally
      dataSource.destroy()
      val dir = new File(dbPath)
      !dir.exists() || dir.delete()

  /** Open a store at a CALLER-OWNED path so the same on-disk data can be re-opened in a second store instance — used to
    * prove records are durable / additive across a re-open (D1).
    */
  private def withStorageAt(dbPath: String)(test: HealingFrontierStorage => Unit): Unit =
    val dataSource = RocksDbDataSource(rocksDbConfig(dbPath), Namespaces.nsSeq)
    try test(new HealingFrontierStorage(dataSource))
    finally dataSource.close()

  private def rocksDbConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  // ── C1: markSubtreeComplete / isSubtreeComplete round-trip ─────────────────────────────────────

  "HealingFrontierStorage subtree-complete records" should
    "report isSubtreeComplete == true after markSubtreeComplete and false for an absent hash" taggedAs UnitTest in
    withStorage { storage =>
      val present = hash(1)
      val absent = hash(2)
      storage.isSubtreeComplete(present) shouldBe false
      storage.markSubtreeComplete(present)
      storage.isSubtreeComplete(present) shouldBe true
      // A hash that was never recorded must NOT be considered complete — this is the never-false-prune floor at the
      // storage layer (absent record ⇒ the verification descends).
      storage.isSubtreeComplete(absent) shouldBe false
    }

  // ── C1: multiIsSubtreeComplete returns exactly the recorded subset ─────────────────────────────

  it should "return exactly the recorded subset from multiIsSubtreeComplete (recorded present, unrecorded absent)" taggedAs UnitTest in
    withStorage { storage =>
      val recorded = (0 until 6).map(hash)
      val unrecorded = (6 until 12).map(hash)
      recorded.foreach(storage.markSubtreeComplete)

      val queried = recorded ++ unrecorded
      val result = storage.multiIsSubtreeComplete(queried)
      // Exactly the recorded subset — no false positives (unrecorded never appears) and no false negatives
      // (every recorded hash appears). The batch read MUST agree with the point read.
      result shouldBe recorded.toSet
      queried.foreach(h => result.contains(h) shouldBe storage.isSubtreeComplete(h))
    }

  it should "return an empty set from multiIsSubtreeComplete on no input and on no records" taggedAs UnitTest in
    withStorage { storage =>
      storage.multiIsSubtreeComplete(Seq.empty) shouldBe Set.empty
      storage.multiIsSubtreeComplete((0 until 4).map(hash)) shouldBe Set.empty
    }

  // ── C1: a 33-byte record key never collides with a 32-byte node hash or the 21-byte marker ─────

  it should "key records on 33 bytes that cannot collide with a 32-byte node hash or the 21-byte CompleteMarkerKey" taggedAs UnitTest in
    withStorage { storage =>
      val h = hash(3)
      // The record key is `SubtreeCompletePrefix ++ hash` = 33 bytes, leading 0x01.
      val recordKey = HealingFrontierStorage.subtreeCompleteKey(h)
      recordKey.length shouldBe 33
      recordKey.head shouldBe HealingFrontierStorage.SubtreeCompletePrefix
      recordKey.tail shouldBe h // the bare 32-byte hash is preserved verbatim after the prefix

      HealingFrontierStorage.isSubtreeCompleteKey(recordKey) shouldBe true
      // A bare 32-byte node hash is NOT a record key (wrong length) ⇒ no collision with stored trie nodes.
      HealingFrontierStorage.isSubtreeCompleteKey(h) shouldBe false
      h.length shouldBe 32
      // The 21-byte completeness sentinel is NOT a record key (wrong length) ⇒ no collision with the marker.
      HealingFrontierStorage.isSubtreeCompleteKey(HealingFrontierStorage.CompleteMarkerKey) shouldBe false
      HealingFrontierStorage.CompleteMarkerKey.length shouldBe 21

      // Recording a subtree must not flip the completeness marker, and vice-versa — disjoint key spaces.
      storage.markSubtreeComplete(h)
      storage.isComplete shouldBe false
      storage.markComplete()
      storage.isComplete shouldBe true
      storage.isSubtreeComplete(h) shouldBe true
    }

  it should "not let a subtree record for hash(X) read back as the frontier-entry get(X) (disjoint key prefix)" taggedAs UnitTest in
    withStorage { storage =>
      val h = hash(7)
      storage.markSubtreeComplete(h)
      // `get(h)` keys on the BARE hash (frontier entry); the record keys on `0x01 ++ h`. They must not alias.
      storage.get(h) shouldBe None
      storage.isSubtreeComplete(h) shouldBe true
    }

  // ── C1: loadAll EXCLUDES subtree-complete records and the completeness marker ───────────────────

  it should "exclude subtree-complete records AND the completeness marker from the loadAll frontier" taggedAs UnitTest in
    withStorage { storage =>
      // A genuine frontier entry (bare 32-byte hash -> pathset) — this MUST survive loadAll.
      val frontierHash = hash(20)
      val frontierPathset = Seq(ByteString(Array[Byte](0x20, 0x01)))
      storage.put(frontierHash, frontierPathset).commit()

      // Subtree-complete records + the terminal marker share CF 'g' but are NOT frontier entries.
      val recorded = (30 until 36).map(hash)
      recorded.foreach(storage.markSubtreeComplete)
      storage.markComplete()

      val loaded = storage.loadAll().toMap
      // Only the real frontier entry is reconstructed; records and the marker are filtered out so they can never
      // be re-issued as GetTrieNodes targets on resume.
      loaded.keySet shouldBe Set(frontierHash)
      loaded(frontierHash) shouldBe frontierPathset
      // Sanity: the records are still individually present (just not in the frontier view).
      recorded.foreach(h => storage.isSubtreeComplete(h) shouldBe true)
      storage.isComplete shouldBe true
    }

  it should "filter out a record whose 32-byte payload equals a real frontier hash, without dropping that frontier entry" taggedAs UnitTest in
    withStorage { storage =>
      // Adversarial overlap: the SAME 32-byte value is both a frontier entry key (bare) and the payload of a
      // record key (0x01 ++ value). loadAll must keep the bare frontier entry and exclude only the 33-byte record.
      val shared = hash(40)
      val frontierPathset = Seq(ByteString(Array[Byte](0x20, 0x05)))
      storage.put(shared, frontierPathset).commit()
      storage.markSubtreeComplete(shared)

      val loaded = storage.loadAll().toMap
      loaded.keySet shouldBe Set(shared)
      loaded(shared) shouldBe frontierPathset
      storage.isSubtreeComplete(shared) shouldBe true
    }

  // ── D1: records are additive / durable across a re-open (never cleared) ─────────────────────────

  it should "survive a re-open: recorded subtrees remain complete after the store is closed and re-opened" taggedAs UnitTest in {
    val dbPath = Files.createTempDirectory("healing-frontier-subtree-reopen-rocksdb").toAbsolutePath.toString
    val recorded = (50 until 55).map(hash)
    val neverRecorded = hash(60)
    try
      // First open: write records, then close (datasource.close, NOT destroy — keep the on-disk bytes).
      withStorageAt(dbPath) { storage =>
        recorded.foreach(storage.markSubtreeComplete)
        recorded.foreach(h => storage.isSubtreeComplete(h) shouldBe true)
      }
      // Second open at the SAME path: every record is still readable (durable, additive, never cleared, D1).
      withStorageAt(dbPath) { storage =>
        recorded.foreach(h => storage.isSubtreeComplete(h) shouldBe true)
        storage.multiIsSubtreeComplete(recorded ++ Seq(neverRecorded)) shouldBe recorded.toSet
        // A hash that was never recorded is still absent after the re-open.
        storage.isSubtreeComplete(neverRecorded) shouldBe false
      }
    finally deleteRecursively(new File(dbPath))
  }

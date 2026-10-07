package com.chipprbots.ethereum.blockchain.sync.snap

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.*
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.byteStringSerializer
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

/** A Path-scheme SNAP-completed trie must be readable through the hash-keyed storage block execution uses.
  *
  * Soak 2026-10-03: SNAP completed under Path (nodes written only to the path-keyed column families), then the first
  * imported block failed with MissingAccountNodeException for the pivot state root itself. The "before publish"
  * assertions below pin that failure; the "after publish" assertions are the fix.
  */
class PathToHashExporterSpec extends AnyFlatSpec with Matchers:

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

  private def withPathStorage[A](body: PathNodeStorage => A): A =
    val dbPath = Files.createTempDirectory("path-export-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(rocksDbConfig(dbPath), Namespaces.nsSeq)
    try body(new PathNodeStorage(dataSource))
    finally
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  private def present(storage: TestMptStorage, hash: ByteString): Boolean =
    scala.util.Try(storage.get(hash.toArray)).isSuccess

  "PathToHashExporter" should "make every node of a Path-written account trie readable by hash" taggedAs UnitTest in {
    withPathStorage { pns =>
      val trie = accountTrieNew()
      seedPath(pns, trie.nodes)
      val target = new TestMptStorage

      // Before: the hash-keyed store is empty, so even the root is missing (the soak failure).
      present(target, trie.root.hash) shouldBe false

      val result = PathToHashExporter.publish(pns, target)

      result.accountNodes shouldBe trie.nodes.size.toLong
      result.storageNodes shouldBe 0L
      trie.nodes.foreach(n => withClue(n.label)(present(target, n.hash) shouldBe true))
    }
  }

  it should "make account state AND its storage trie readable at the root (executing against the completed state)" taggedAs UnitTest in {
    withPathStorage { pns =>
      val trie = storageTrieNew()
      seedPath(pns, trie.nodes)
      val target = new TestMptStorage

      scala.util
        .Try(MerklePatriciaTrie[ByteString, Account](trie.root.hash.toArray, target).get(accountHash))
        .isFailure shouldBe true

      val result = PathToHashExporter.publish(pns, target)
      result.accountNodes shouldBe 1L
      result.storageNodes shouldBe 3L

      val account = MerklePatriciaTrie[ByteString, Account](trie.root.hash.toArray, target).get(accountHash)
      account.map(_.nonce.toLong) shouldBe Some(1L)
      trie.nodes.foreach(n => withClue(n.label)(present(target, n.hash) shouldBe true))
    }
  }

  it should "store a storage root shared by two accounts under both account scopes without losing either" taggedAs UnitTest in {
    withPathStorage { pns =>
      val trie = twinStorageTrie()
      seedPath(pns, trie.nodes)
      val target = new TestMptStorage
      PathToHashExporter.publish(pns, target).storageNodes shouldBe 2L
      present(target, trie("s0").hash) shouldBe true
      trie.nodes.foreach(n => present(target, n.hash) shouldBe true)
    }
  }

  it should "be idempotent" taggedAs UnitTest in {
    withPathStorage { pns =>
      val trie = accountTrieOld()
      seedPath(pns, trie.nodes)
      val target = new TestMptStorage
      PathToHashExporter.publish(pns, target)
      PathToHashExporter.publish(pns, target).accountNodes shouldBe trie.nodes.size.toLong
      trie.nodes.foreach(n => present(target, n.hash) shouldBe true)
    }
  }

  it should "re-read sampled account leaves and storage roots through the trie when given the state root" taggedAs UnitTest in {
    withPathStorage { pns =>
      val trie = storageTrieNew()
      seedPath(pns, trie.nodes)
      val result = PathToHashExporter.publish(pns, new TestMptStorage, stateRoot = Some(trie.root.hash))
      result.sampledAccountLeaves shouldBe 1
      result.sampledStorageRoots shouldBe 1
    }
  }

  it should "fail loudly when a sampled leaf is not reachable from the claimed state root" taggedAs UnitTest in {
    withPathStorage { pns =>
      val trie = storageTrieNew()
      seedPath(pns, trie.nodes)
      val wrongRoot = storageTrieOld().root.hash
      an[IllegalStateException] should be thrownBy
        PathToHashExporter.publish(pns, new TestMptStorage, stateRoot = Some(wrongRoot))
    }
  }

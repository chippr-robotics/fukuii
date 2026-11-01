package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Paths

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCodeFilter
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditFrame
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditPosition
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState.*
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditWalk
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The tool that makes a hole for a live audit run: what it refuses, and that the hole is the one it says. */
class BackgroundAuditHoleInjectionSpec extends AnyFlatSpec with Matchers:

  "HoleInjectionTool" should "name the directory it is limited to" taggedAs UnitTest in {
    HoleInjectionTool.Allowed shouldBe Paths.get("/home/dontpanic/mordor-scarcity")
  }

  it should "refuse a datadir outside it, or next to it, or that a symlink takes outside" taggedAs UnitTest in {
    val base = Files.createTempDirectory("hole-tool")
    val allowed = Files.createDirectory(base.resolve("allowed"))
    val inside = Files.createDirectory(allowed.resolve("node"))
    val outside = Files.createDirectory(base.resolve("elsewhere"))
    val sibling = Files.createDirectory(base.resolve("allowed-2"))
    val escaping = Files.createSymbolicLink(allowed.resolve("escape"), outside)
    val climbing = allowed.resolve("node/../../elsewhere")

    HoleInjectionTool.checked(inside.toString, allowed) shouldBe Right(inside.toRealPath())
    HoleInjectionTool.checked(allowed.toString, allowed) shouldBe Right(allowed.toRealPath())
    Seq(outside, sibling, escaping, climbing).foreach { dir =>
      withClue(s"$dir: ")(HoleInjectionTool.checked(dir.toString, allowed).isLeft shouldBe true)
    }
    HoleInjectionTool.checked(base.resolve("nowhere").toString, allowed).isLeft shouldBe true
    withClue("the default, by default: ")(HoleInjectionTool.checked(outside.toString).isLeft shouldBe true)
  }

  it should "find RocksDB where db.conf puts it, or refuse" taggedAs UnitTest in {
    val dir = Files.createTempDirectory("hole-tool")
    HoleInjectionTool.rocksDbUnder(dir).isLeft shouldBe true
    val rocks = Files.createDirectory(dir.resolve("rocksdb"))
    Files.createFile(rocks.resolve("CURRENT"))
    HoleInjectionTool.rocksDbUnder(dir) shouldBe Right(rocks)
  }

  /** Some accounts, one with a storage trie, under a stored best block. */
  private def stateWithBest(s: AuditTestState): ByteString =
    val root = s.update(None, 0, (0 until 40).map(i => trieKey(i) -> accountBytes()))
    val header = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(77), stateRoot = TrieRoot(root))
    new BlockHeadersStorage(s.source).put(header.hash.value, header).commit()
    s.app.putBestBlockInfo(BlockInfo(header.hash.value, 77)).commit()
    root

  it should "delete the one node it says, at the depth asked for, and the audit's walk finds exactly that" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = stateWithBest(s)
    val before = s.allNodes(root)

    val hole = HoleInjectionTool.inject(s.source, 2).toOption.get

    hole.block shouldBe BigInt(77)
    hole.root shouldBe root
    hole.position.nibbles.length should be >= 2
    before should contain((hole.position, hole.hash))
    s.nodes.get(hole.hash) shouldBe None

    val head = s.setHead(77, root)
    val walk = Iterator
      .iterate(AuditWalk.Step(pushes = Vector(AuditFrame(AuditPosition.AccountRoot, None)))) { step =>
        val next = AuditWalk.walkBatch(step.pushes, head, s.reader, None, new AuditCodeFilter(10))
        next.copy(holes = step.holes ++ next.holes)
      }
      .dropWhile(_.pushes.nonEmpty)
      .next()
    walk.holes.map(h => (h.position, h.hash)) shouldBe Vector((hole.position, hole.hash))
  }

  it should "refuse the root, and a depth the trie does not have" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = stateWithBest(s)
    val before = s.allNodes(root)
    HoleInjectionTool.inject(s.source, 0).isLeft shouldBe true
    HoleInjectionTool.inject(s.source, 99).isLeft shouldBe true
    withClue("nothing was deleted on the way: ")(s.allNodes(root) shouldBe before)
  }

  it should "open a datadir that exists, and create none" taggedAs UnitTest in {
    val dir = Files.createTempDirectory("hole-tool").resolve("rocksdb")
    val config = HoleInjectionTool.rocksConfig(dir)
    an[Exception] should be thrownBy RocksDbDataSource(config, Namespaces.nsSeq)
    Files.exists(dir.resolve("CURRENT")) shouldBe false

    val creating = new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = config.paranoidChecks
      override val path: String = config.path
      override val maxThreads: Int = config.maxThreads
      override val maxOpenFiles: Int = config.maxOpenFiles
      override val verifyChecksums: Boolean = config.verifyChecksums
      override val levelCompaction: Boolean = config.levelCompaction
      override val blockSize: Long = config.blockSize
      override val blockCacheSize: Long = config.blockCacheSize
    RocksDbDataSource(creating, Namespaces.nsSeq).close()

    val reopened = RocksDbDataSource(config, Namespaces.nsSeq)
    reopened.destroy()
  }

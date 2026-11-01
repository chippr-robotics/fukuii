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
import com.chipprbots.ethereum.blockchain.sync.snap.heal.NodeFault
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.StoredNodeTestAccess
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

  private def bestOn(s: AuditTestState, root: ByteString): Unit =
    val header = Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(77), stateRoot = TrieRoot(root))
    new BlockHeadersStorage(s.source).put(header.hash.value, header).commit()
    s.app.putBestBlockInfo(BlockInfo(header.hash.value, 77)).commit()

  /** Some accounts, one with a storage trie, under a stored best block. */
  private def stateWithBest(s: AuditTestState): ByteString =
    val root = s.update(None, 0, (0 until 40).map(i => trieKey(i) -> accountBytes()))
    bestOn(s, root)
    root

  /** What the walk finds from `root`, to the end. */
  private def walkFrom(
      s: AuditTestState,
      root: ByteString
  ): Vector[com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditHole] =
    val head = s.setHead(77, root)
    Iterator
      .iterate(AuditWalk.Step(pushes = Vector(AuditFrame(AuditPosition.AccountRoot, None)))) { step =>
        val next = AuditWalk.walkBatch(step.pushes, head, s.reader, new AuditCodeFilter(10))
        next.copy(holes = step.holes ++ next.holes)
      }
      .dropWhile(_.pushes.nonEmpty)
      .next()
      .holes

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

    walkFrom(s, root).map(h => (h.position, h.hash)) shouldBe Vector((hole.position, hole.hash))
  }

  it should "in corrupt mode write junk under the key, the wrapper valid and its count kept, and the walk finds a bad node" taggedAs UnitTest in {
    val s = new AuditTestState
    val root = stateWithBest(s)
    val before = s.allNodes(root)
    val hash = before.filter(_._1.nibbles.length >= 2).head._2
    s.nodes.put(hash, StoredNodeTestAccess.wrapped(StoredNodeTestAccess.unwrapped(s.nodes.get(hash).get), 7))

    val hole = HoleInjectionTool.inject(s.source, 2, HoleInjectionTool.Mode.Corrupt).toOption.get

    before should contain((hole.position, hole.hash))
    hole.hash shouldBe hash
    val stored = s.nodes.get(hole.hash).get
    StoredNodeTestAccess.references(stored) shouldBe 7
    s.reader.nodes(Seq(hole.hash)).head should not be RawNodeRead.Undecodable
    walkFrom(s, root).map(h => (h.position, h.hash, h.fault)) shouldBe
      Vector((hole.position, hole.hash, NodeFault.BadKeccak))
  }

  it should "in corrupt mode write bare junk on archive, which has no wrapper" taggedAs UnitTest in {
    val s = new AuditTestState
    val leaf = storageLeaf(1)
    val leafHash = ByteString(kec256(leaf.encode))
    s.nodes.put(leafHash, leaf.encode)
    val rootNode = branch(1 -> leafHash)
    val root = ByteString(kec256(rootNode.encode))
    s.nodes.put(root, rootNode.encode)
    bestOn(s, root)

    val hole = HoleInjectionTool.inject(s.source, 1, HoleInjectionTool.Mode.Corrupt).toOption.get

    hole.hash shouldBe leafHash
    val junk = s.nodes.get(leafHash).get
    ByteString(kec256(junk)) should not be leafHash
    junk.toSeq should not be leaf.encode.toSeq
  }

  it should "guard the datadir the same way in corrupt mode, and take no other mode" taggedAs UnitTest in {
    val outside = Files.createTempDirectory("hole-tool")
    val refused = the[IllegalArgumentException] thrownBy
      HoleInjectionTool.main(Array(outside.toString, "2", "corrupt"))
    refused.getMessage should include("Refusing")
    val wrong = the[IllegalArgumentException] thrownBy
      HoleInjectionTool.main(Array(outside.toString, "2", "shred"))
    wrong.getMessage should include("Wrong arguments")
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

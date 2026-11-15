package com.chipprbots.ethereum.db.storage

import java.nio.file.Files

import org.apache.pekko.util.ByteString

import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.*

/** The hand-off's sample on a real RocksDB (spec 018 C5a part 4): that the ranges it seeks in are ranges of RocksDB's
  * own order of keys, where a byte of `0xf0` and above sorts last and not first. The in-memory store of the unit tests
  * compares keys itself.
  */
class PathHandoffSampleItSpec extends AnyFlatSpec with Matchers:

  private def branch(slots: (Int, MptNode)*): BranchNode =
    val children = Array.fill[MptNode](16)(NullNode)
    slots.foreach { case (slot, child) => children(slot) = HashNode(child.hash) }
    BranchNode(children, None)

  private def nibbles(n: Int, nibble: Int): ByteString = ByteString(Array.fill[Byte](n)(nibble.toByte))

  private def owner(nibble: Int): ByteString = ByteString(HexPrefix.nibblesToBytes(nibbles(64, nibble).toArray))

  private def accountBytes(storageRoot: Option[ByteString]): ByteString =
    ByteString(
      Account.accountSerializer.toBytes(
        Account(nonce = UInt256(1L), storageRoot = storageRoot.fold(Account.EmptyStorageRootHash)(TrieRoot(_)))
      )
    )

  "The hand-off's sample on RocksDB" should "reach every first nibble of the accounts and of the owners, the last ones too" taggedAs (
    IntegrationTest,
    DatabaseTest
  ) in {
    val dir = Files.createTempDirectory("handoff-sample")
    val source = RocksDbDataSource(rocksConfig(dir.toAbsolutePath.toString), Namespaces.nsSeq)
    try
      val paths = new PathNodeStorage(source)
      def account(at: Seq[Int], node: MptNode): MptNode =
        paths.writeAccountNode(at.map(_.toByte).toArray, node.encode)
        node
      def storage(of: ByteString, at: Seq[Int], node: MptNode): MptNode =
        paths.writeStorageNode(of, at.map(_.toByte).toArray, node.encode)
        node

      // Under each first nibble i: the account whose hash is that nibble sixty-four times, with a storage trie of two
      // leaves; an account at a path of an odd length (i, a, 0); and one at the highest path of an even length under
      // it (i, 15, 15, 15), which for the last nibble is the account with storage, and the last key of the family.
      val tops = (0 until 16).map { i =>
        val slots = (0 until 2).map { j =>
          j -> storage(owner(i), Seq(j), LeafNode(nibbles(63, j), ByteString(Array.fill[Byte](40)((i + 1).toByte))))
        }
        val withStorage = accountBytes(Some(ByteString(storage(owner(i), Seq(), branch(slots*)).hash)))
        val a = if i == 0 then 1 else 0
        val odd = account(Seq(i, a, 0), LeafNode(nibbles(61, 5), accountBytes(None)))
        val oddBranch = account(Seq(i, a), branch(0 -> odd))
        val last = account(
          Seq(i, 15, 15, 15),
          if i == 15 then LeafNode(nibbles(60, 15), withStorage) else LeafNode(nibbles(60, 5), accountBytes(None))
        )
        val highBranch = account(Seq(i, 15), branch(15 -> account(Seq(i, 15, 15), branch(15 -> last))))
        i -> account(
          Seq(i),
          if i == 15 then branch(a -> oddBranch, 15 -> highBranch)
          else branch(a -> oddBranch, i -> account(Seq(i, i), LeafNode(nibbles(62, i), withStorage)), 15 -> highBranch)
        )
      }
      val root = ByteString(account(Seq(), branch(tops*)).hash)

      (1L to 5L).foreach { seed =>
        val result = PathHandoffSample.run(paths, root, random = new Random(seed))
        withClue(s"seed $seed: ") {
          result.sampledAccountLeaves shouldBe 47
          result.sampledAccountMisses shouldBe 0
          result.sampledStorageRoots shouldBe 16
          result.sampledStorageRootsStale shouldBe 0
          result.sampledStorageRootsMissing shouldBe 0
          result.sampledLargeTrieLeaves shouldBe 32
          result.sampledStale shouldBe 0
          result.sampledMissing shouldBe 0
        }
      }
    finally source.close()
  }

  private def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

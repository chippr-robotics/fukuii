package com.chipprbots.ethereum.blockchain.checkpoint

import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.RefCountTrust
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** #1692, the checkpoint path. The exporter writes each node once however many positions use it, so the importer counts
  * a storage trie shared by two contracts once. The first block to rewrite a slot of one of them sends the nodes on its
  * path to zero, and pruning, `history` blocks later, deleted what the other contract still read.
  *
  * Contract A and contract B have the same storage root. The archive is exported from a source store and imported into
  * a target that prunes, then block `tip + 1` rewrites a slot of A.
  */
class CheckpointSharedNodePruneSpec extends AnyWordSpec with Matchers with EitherValues:

  private val history = 4
  private val chainId = 1337L
  private val tip = 100

  "A checkpoint import with a storage trie shared by two contracts" should {

    "keep the other contract readable after a block rewrites a slot of one, with pruning past the block" taggedAs UnitTest in new Setup:
      val app = targetStorages.storages.appStateStorage
      app.markDatadirCreated(countsExact = true).commitSync() // a fresh datadir: its genesis counted every position
      RefCountTrust.nodesMayBeDeleted(app) shouldBe true
      importArchive()
      withClue("the importer took the stamp away, before its first node: ") {
        app.hasRefCountsExact() shouldBe false
        RefCountTrust.nodesMayBeDeleted(app) shouldBe false
      }

      readSharedStorage() // before the block
      rewriteSlotOfA()
      pruneThrough(tip + 1 + history + 1)

      noException should be thrownBy readSharedStorage()

    "lose it when the stamp is put back, which is the failure this guards against" taggedAs UnitTest in new Setup:
      val app = targetStorages.storages.appStateStorage
      app.markDatadirCreated(countsExact = true).commitSync()
      importArchive()
      // What a store with no such rule did: the importer counted the shared nodes once, and pruning believed it.
      app.markRefCountsExact().commit()
      RefCountTrust.nodesMayBeDeleted(app) shouldBe true

      readSharedStorage()
      rewriteSlotOfA()
      pruneThrough(tip + 1 + history + 1)

      an[MPTException] should be thrownBy readSharedStorage()
  }

  private def difficultyAt(i: Int): BigInt = BigInt(131072) + BigInt(i) * 1000

  private def chainTd(n: Int): BigInt = (0 to n).map(difficultyAt).sum

  /** A parentHash-linked header chain 0..`tipNumber`, stored, whose tip carries `stateRoot`. */
  private def storeHeaderChain(writer: BlockchainWriter, tipNumber: Int, stateRoot: ByteString): Seq[BlockHeader] =
    val template = Fixtures.Blocks.Block3125369.header
    val chain = (0 to tipNumber).foldLeft(Vector.empty[BlockHeader]) { (acc, i) =>
      acc :+ template.copy(
        parentHash = acc.lastOption.map(_.hash).getOrElse(BlockHash(ByteString(new Array[Byte](32)))),
        number = BlockNumber(i),
        difficulty = Difficulty(difficultyAt(i)),
        stateRoot = if i == tipNumber then TrieRoot(stateRoot) else template.stateRoot
      )
    }
    chain.foreach(h => writer.storeBlockHeader(h).commit())
    chain

  private trait Setup extends EphemBlockchainTestSetup:
    import MerklePatriciaTrie.defaultByteArraySerializable

    /** A pruning target. It deletes by count only while its stamp stands, on either scheme; a fixture that skips the
      * genesis load has none, so a test stamps it as the load would.
      */
    trait TargetPruning extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
      override val pruningMode: PruningMode = BasicPruning(history)

    val sourceStorages = getNewStorages
    val targetStorages: EphemDataSourceComponent & TargetPruning & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with TargetPruning
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider

    val sourceWriter: BlockchainWriter = BlockchainWriter(sourceStorages.storages)
    val targetWriter: BlockchainWriter = BlockchainWriter(targetStorages.storages)
    val sourceReader: BlockchainReader = BlockchainReader(sourceStorages.storages)

    private val slotKeys: Seq[Array[Byte]] = (1 to 6).map(i => crypto.kec256(Hex.decode("00" * 31 + f"$i%02x")))
    private val addrA: Array[Byte] = Hex.decode("11" * 20)
    private val addrB: Array[Byte] = Hex.decode("22" * 20)
    private val addrC: Array[Byte] = Hex.decode("33" * 20)

    private def account(storageRoot: ByteString, nonce: Int): Account =
      Account(nonce = UInt256(nonce), balance = UInt256(1), storageRoot = TrieRoot(storageRoot))

    /** One storage trie, and two contracts that both have it. */
    val sharedStorageRoot: ByteString =
      val storageTrie = slotKeys.foldLeft(
        MerklePatriciaTrie[Array[Byte], Array[Byte]](sourceStorages.storages.stateStorage.getBackingStorage(0))
      )((t, k) => t.put(k, Hex.decode("aa")))
      ByteString(storageTrie.getRootHash)

    val stateRoot: ByteString =
      val accounts = MerklePatriciaTrie[Array[Byte], Account](sourceStorages.storages.stateStorage.getBackingStorage(0))
        .put(crypto.kec256(addrA), account(sharedStorageRoot, 0))
        .put(crypto.kec256(addrB), account(sharedStorageRoot, 0))
        .put(crypto.kec256(addrC), Account(nonce = UInt256(1), balance = UInt256(5)))
      ByteString(accounts.getRootHash)

    /** Exports the source at block `tip` and imports it into the target. */
    def importArchive(): Unit =
      val header = storeHeaderChain(sourceWriter, tip, stateRoot).last
      sourceWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(chainTd(tip))).commit()
      val archive: Path = Files.createTempFile("checkpoint-shared-node", ".checkpoint")
      try
        val exporter = new CheckpointExporter(
          sourceStorages.storages.evmCodeStorage,
          sourceReader,
          chainId = chainId
        )
        val _ = exporter.exportArchive(header.number.value, archive).value
        val importer = new CheckpointImporter(
          targetWriter,
          targetStorages.storages.stateStorage,
          targetStorages.storages.evmCodeStorage,
          targetStorages.storages.appStateStorage
        )
        val _ = importer.importFromFile(archive, Some(chainId)).value
      finally Files.deleteIfExists(archive)

    /** Block `tip + 1`: contract A rewrites one slot. B is not touched, and still has the old storage root. */
    def rewriteSlotOfA(): Unit =
      val storage = targetStorages.storages.stateStorage.getBackingStorage(tip + 1)
      val newStorageRoot = ByteString(
        MerklePatriciaTrie[Array[Byte], Array[Byte]](sharedStorageRoot.toArray, storage)
          .put(slotKeys.head, Hex.decode("bb"))
          .getRootHash
      )
      val _ = MerklePatriciaTrie[Array[Byte], Account](stateRoot.toArray, storage)
        .put(crypto.kec256(addrA), account(newStorageRoot, 1))

    /** Saves blocks up to `last`, as the importer does after each block, so the death row of `tip + 1` is pruned. */
    def pruneThrough(last: Int): Unit =
      (tip + 1 to last).foreach(n => targetStorages.storages.stateStorage.onBlockSave(n, n - 1)(() => ()))

    /** Reads the whole storage trie contract B still has; throws if a node of it is gone. */
    def readSharedStorage(): Unit =
      val trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](
        sharedStorageRoot.toArray,
        targetStorages.storages.stateStorage.getReadOnlyStorage
      )
      slotKeys.foreach(k => trie.get(k))

package com.chipprbots.ethereum.blockchain.checkpoint

import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import org.scalatest.BeforeAndAfterEach
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointExporterSpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterEach {

  "CheckpointExporter" should {

    "round-trip a small state through export → import yielding identical trie nodes" taggedAs UnitTest in new Setup {
      // Bytecodes referenced by accounts
      val codeA: ByteString = ByteString("contract-A-bytecode")
      val codeB: ByteString = ByteString("contract-B-bytecode-longer-for-variety")
      val codeAHash: ByteString = crypto.kec256(codeA)
      val codeBHash: ByteString = crypto.kec256(codeB)
      sourceStorages.storages.evmCodeStorage.put(codeAHash, codeA).commit()
      sourceStorages.storages.evmCodeStorage.put(codeBHash, codeB).commit()

      // Account 2 has a storage trie with two slots.
      import MerklePatriciaTrie.defaultByteArraySerializable
      val storageTrie = MerklePatriciaTrie[Array[Byte], Array[Byte]](
        sourceStorages.storages.stateStorage.getBackingStorage(0)
      )
        .put(
          crypto.kec256(Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")),
          Hex.decode("aa")
        )
        .put(
          crypto.kec256(Hex.decode("0000000000000000000000000000000000000000000000000000000000000002")),
          Hex.decode("bb")
        )
      val storageRoot = ByteString(storageTrie.getRootHash)

      // Main account trie with three accounts.
      val addr1 = Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")
      val addr2 = Hex.decode("11111111111111111111111111111111111111aa")
      val addr3 = Hex.decode("22222222222222222222222222222222222222bb")
      val accountTrie = MerklePatriciaTrie[Array[Byte], Account](
        sourceStorages.storages.stateStorage.getBackingStorage(0)
      )
        .put(
          crypto.kec256(addr1),
          Account(nonce = UInt256(0), balance = UInt256(100), codeHash = codeAHash)
        )
        .put(
          crypto.kec256(addr2),
          Account(
            nonce = UInt256(0),
            balance = UInt256(0),
            storageRoot = storageRoot,
            codeHash = codeBHash
          )
        )
        .put(
          crypto.kec256(addr3),
          Account(nonce = UInt256(1), balance = UInt256(1)) // empty code + storage
        )
      val stateRoot = ByteString(accountTrie.getRootHash)

      // Header with the constructed stateRoot — use a fixture for the bulk and override stateRoot.
      val header = Fixtures.Blocks.Block3125369.header.copy(stateRoot = stateRoot, number = 100)
      val weight = ChainWeight(BigInt(42))
      sourceWriter.storeBlockHeader(header).and(sourceWriter.storeChainWeight(header.hash, weight)).commit()

      // Export
      val outputPath = tmpRoot.resolve("export.checkpoint")
      val exporter = new CheckpointExporter(
        sourceStorages.storages.nodeStorage,
        sourceStorages.storages.evmCodeStorage,
        sourceReader,
        chainId = 1337L
      )
      val exportResult = exporter.exportArchive(header.number, outputPath).value
      exportResult.nodesExported should be > 0L
      exportResult.bytecodesExported shouldBe 2L

      // Import into a fresh storage stack
      val importer = new CheckpointImporter(
        targetWriter,
        targetStorages.storages.stateStorage,
        targetStorages.storages.evmCodeStorage,
        targetStorages.storages.appStateStorage
      )
      val importResult = importer.importFromFile(outputPath, Some(1337L)).value
      importResult.blockNumber shouldBe header.number
      importResult.nodesImported shouldBe exportResult.nodesExported
      importResult.bytecodesImported shouldBe exportResult.bytecodesExported

      // Verify the imported state can re-derive the same stateRoot via MerklePatriciaTrie traversal.
      // Round-tripped trie nodes must be byte-identical because trie nodes are content-addressed.
      val importedTrie = MerklePatriciaTrie[Array[Byte], Account](
        stateRoot.toArray,
        targetStorages.storages.stateStorage.getBackingStorage(0)
      )
      importedTrie.get(crypto.kec256(addr1)).value.balance shouldBe UInt256(100)
      importedTrie.get(crypto.kec256(addr2)).value.codeHash shouldBe codeBHash
      importedTrie.get(crypto.kec256(addr3)).value.nonce shouldBe UInt256(1)

      // Storage trie reachable from account 2
      val importedStorageTrie = MerklePatriciaTrie[Array[Byte], Array[Byte]](
        storageRoot.toArray,
        targetStorages.storages.stateStorage.getBackingStorage(0)
      )
      importedStorageTrie
        .get(crypto.kec256(Hex.decode("0000000000000000000000000000000000000000000000000000000000000001")))
        .map(_.toSeq) shouldBe Some(Hex.decode("aa").toSeq)

      // Bytecodes
      targetStorages.storages.evmCodeStorage.get(codeAHash).map(_.toArray.toSeq) shouldBe Some(codeA.toArray.toSeq)
      targetStorages.storages.evmCodeStorage.get(codeBHash).map(_.toArray.toSeq) shouldBe Some(codeB.toArray.toSeq)

      // Header + best-block + chain weight + done-markers
      targetReader.getBlockHeaderByNumber(header.number).value shouldBe header
      targetReader.getChainWeightByHash(header.hash).value shouldBe weight
      targetStorages.storages.appStateStorage.getBestBlockNumber() shouldBe header.number
      targetStorages.storages.appStateStorage.isSnapSyncDone() shouldBe true
    }

    "fail cleanly when the requested block is missing" taggedAs UnitTest in new Setup {
      val exporter = new CheckpointExporter(
        sourceStorages.storages.nodeStorage,
        sourceStorages.storages.evmCodeStorage,
        sourceReader,
        chainId = 1L
      )
      val r = exporter.exportArchive(blockNumber = 9999, output = tmpRoot.resolve("nope.checkpoint"))
      r shouldBe Left(CheckpointExporter.NoSuchBlock(9999))
    }
  }

  private var tmpRoot: java.nio.file.Path = _

  override def beforeEach(): Unit =
    tmpRoot = Files.createTempDirectory("checkpoint-exporter-spec")

  override def afterEach(): Unit = {
    import scala.jdk.CollectionConverters._
    val walk = Files.walk(tmpRoot)
    try walk.iterator.asScala.toSeq.reverse.foreach(p => Files.deleteIfExists(p))
    finally walk.close()
  }

  private trait Setup extends EphemBlockchainTestSetup {
    val sourceStorages = getNewStorages
    val targetStorages = getNewStorages
    val sourceWriter: BlockchainWriter = BlockchainWriter(sourceStorages.storages)
    val targetWriter: BlockchainWriter = BlockchainWriter(targetStorages.storages)
    val sourceReader: BlockchainReader = BlockchainReader(sourceStorages.storages)
    val targetReader: BlockchainReader = BlockchainReader(targetStorages.storages)
  }
}

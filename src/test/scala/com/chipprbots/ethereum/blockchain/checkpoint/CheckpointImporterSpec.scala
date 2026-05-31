package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointImporterSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues {

  "CheckpointImporter" should {

    "import a single-account archive end-to-end" taggedAs UnitTest in new Setup {
      val header = checkpointHeader
      val nodes = Seq(
        (hash("rootNode"), Array.fill[Byte](48)(0xab.toByte)),
        (hash("storageRoot"), Array.fill[Byte](64)(0xcd.toByte))
      )
      val bytecodes = Seq(
        (hash("code1"), "bytecode1".getBytes("UTF-8")),
        (hash("code2"), "bytecode2".getBytes("UTF-8"))
      )
      val bytes = encodeArchive(header, nodes, bytecodes)

      val importer = new CheckpointImporter(
        writer,
        freshStorage.storages.stateStorage,
        freshStorage.storages.evmCodeStorage,
        freshStorage.storages.appStateStorage
      )
      val result =
        importer.importFromStream(new ByteArrayInputStream(bytes), Some(checkpointChainId)).value

      result.blockNumber shouldBe header.blockHeader.number
      result.nodesImported shouldBe nodes.length
      result.bytecodesImported shouldBe bytecodes.length

      // Best-block pointers
      val best = freshStorage.storages.appStateStorage.getBestBlockInfo()
      best.number shouldBe header.blockHeader.number
      best.hash shouldBe header.blockHeader.hash

      // Phase flags set so SNAP isn't re-entered
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe true
      freshStorage.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
      freshStorage.storages.appStateStorage.isStorageRecoveryDone() shouldBe true

      // Header retrievable
      blockReader.getBlockHeaderByNumber(header.blockHeader.number).value shouldBe header.blockHeader

      // SerializingMptStorage.get decodes RLP, so it can't verify our random-byte fixtures.
      // Verify via the underlying NodeStorage directly.
      nodes.foreach { case (h, rlp) =>
        freshStorage.storages.nodeStorage.get(h).map(_.toSeq) shouldBe Some(rlp.toSeq)
      }

      // Bytecodes retrievable
      bytecodes.foreach { case (h, code) =>
        freshStorage.storages.evmCodeStorage.get(h).map(_.toArray.toSeq) shouldBe Some(code.toSeq)
      }
    }

    "reject an archive with a mismatched chainId" taggedAs UnitTest in new Setup {
      val header = checkpointHeader
      val bytes = encodeArchive(header, Nil, Nil)

      val importer = new CheckpointImporter(
        writer,
        freshStorage.storages.stateStorage,
        freshStorage.storages.evmCodeStorage,
        freshStorage.storages.appStateStorage
      )
      // expect chainId 9999; archive declares 61
      val result = importer.importFromStream(new ByteArrayInputStream(bytes), Some(9999L))
      result shouldBe Left(CheckpointImporter.ChainIdMismatch(9999L, checkpointChainId))

      // Best block must remain at 0 on rejection
      freshStorage.storages.appStateStorage.getBestBlockNumber() shouldBe 0
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe false
    }

    "reject a corrupted archive without committing state" taggedAs UnitTest in new Setup {
      val header = checkpointHeader
      val nodes = Seq((hash("n1"), Array.fill[Byte](32)(0x11)))
      val bytes = encodeArchive(header, nodes, Nil)
      // Flip the CRC trailer to ensure a CRC error
      bytes(bytes.length - 1) = (bytes(bytes.length - 1) ^ 0xff).toByte

      val importer = new CheckpointImporter(
        writer,
        freshStorage.storages.stateStorage,
        freshStorage.storages.evmCodeStorage,
        freshStorage.storages.appStateStorage
      )
      val result = importer.importFromStream(new ByteArrayInputStream(bytes), None)
      result.isLeft shouldBe true

      // Phase flags must remain unset on failure (we want the next start to be able to retry)
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe false
      freshStorage.storages.appStateStorage.getBestBlockNumber() shouldBe 0
    }

    // Regression for Bug 35 — operator-supplied output path without `.gz` extension
    // shouldn't break the import. importFromFile sniffs the gzip magic bytes.
    "import a gzipped archive even when the file path lacks .gz extension" taggedAs UnitTest in new Setup {
      import java.nio.file.Files
      import java.util.zip.GZIPOutputStream

      val header = checkpointHeader
      val nodes = Seq((hash("rootNode"), Array.fill[Byte](32)(0xaa.toByte)))
      val bytes = encodeArchive(header, nodes, Nil)

      // Write the archive to a temp file WITH gzip compression but WITHOUT .gz suffix
      val tmp = Files.createTempFile("checkpoint-bug35", ".checkpoint")
      try {
        val out = new GZIPOutputStream(Files.newOutputStream(tmp))
        try out.write(bytes)
        finally out.close()

        val importer = new CheckpointImporter(
          writer,
          freshStorage.storages.stateStorage,
          freshStorage.storages.evmCodeStorage,
          freshStorage.storages.appStateStorage
        )
        val result = importer.importFromFile(tmp, Some(checkpointChainId)).value
        result.blockNumber shouldBe header.blockHeader.number
        result.nodesImported shouldBe nodes.length
      } finally Files.deleteIfExists(tmp)
    }
  }

  private trait Setup extends EphemBlockchainTestSetup {
    val checkpointChainId: Long = 61L
    val freshStorage = getNewStorages
    val writer: BlockchainWriter = BlockchainWriter(freshStorage.storages)
    val blockReader =
      com.chipprbots.ethereum.domain.BlockchainReader(freshStorage.storages)

    val checkpointHeader: CheckpointArchive.Header =
      CheckpointArchive.Header(
        chainId = checkpointChainId,
        blockHeader = Fixtures.Blocks.Block3125369.header,
        chainWeight = ChainWeight(BigInt("987654321"))
      )

    def hash(s: String): ByteString = ByteString(s.padTo(32, '_').getBytes("UTF-8"))

    def encodeArchive(
        header: CheckpointArchive.Header,
        nodes: Seq[(ByteString, Array[Byte])],
        bytecodes: Seq[(ByteString, Array[Byte])]
    ): Array[Byte] = {
      val baos = new ByteArrayOutputStream()
      val w = new CheckpointArchive.Writer(baos)
      w.writeHeader(header)
      nodes.foreach { case (h, n) => w.writeNode(h, n) }
      bytecodes.foreach { case (h, b) => w.writeBytecode(h, b) }
      w.finish()
      baos.toByteArray
    }

  }
}

package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportError
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportResult
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointImporterSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:

  "CheckpointImporter" should {

    "import a single-account archive end-to-end" taggedAs UnitTest in new Setup:
      val header = checkpointHeader
      val nodes: Seq[(ByteString, Array[Byte])] = Seq(
        (hash("rootNode"), Array.fill[Byte](48)(0xab.toByte)),
        (hash("storageRoot"), Array.fill[Byte](64)(0xcd.toByte))
      )
      val bytecodes: Seq[(ByteString, Array[Byte])] = Seq(
        (hash("code1"), "bytecode1".getBytes("UTF-8")),
        (hash("code2"), "bytecode2".getBytes("UTF-8"))
      )
      val bytes: Array[Byte] = encodeArchive(header, nodes, bytecodes)

      val importer = new CheckpointImporter(
        writer,
        freshStorage.storages.stateStorage,
        freshStorage.storages.evmCodeStorage,
        freshStorage.storages.appStateStorage
      )
      val result: ImportResult =
        importer.importFromStream(new ByteArrayInputStream(bytes), Some(checkpointChainId)).value

      result.blockNumber shouldBe header.blockHeader.number.value
      result.nodesImported shouldBe nodes.length
      result.bytecodesImported shouldBe bytecodes.length

      // Best-block pointers
      val best: BlockInfo = freshStorage.storages.appStateStorage.getBestBlockInfo()
      best.number shouldBe header.blockHeader.number.value
      best.hash shouldBe header.blockHeader.hash.value

      // Phase flags set so SNAP isn't re-entered
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe true
      freshStorage.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
      freshStorage.storages.appStateStorage.isStorageRecoveryDone() shouldBe true

      // Header retrievable
      blockReader.getBlockHeaderByNumber(header.blockHeader.number.value).value shouldBe header.blockHeader

      // SerializingMptStorage.get decodes RLP, so it can't verify our random-byte fixtures.
      // Verify via the underlying NodeStorage directly.
      nodes.foreach { case (h, rlp) =>
        freshStorage.storages.nodeStorage.get(h).map(_.toSeq) shouldBe Some(rlp.toSeq)
      }

      // Bytecodes retrievable
      bytecodes.foreach { case (h, code) =>
        freshStorage.storages.evmCodeStorage.get(h).map(_.toArray.toSeq) shouldBe Some(code.toSeq)
      }

    "write the imported nodes to the data source before the completion flags under inmemory pruning (#1581)" taggedAs UnitTest in new Setup:
      val header = checkpointHeader
      val rootHash = hash("rootNode")
      val nodes: Seq[(ByteString, Array[Byte])] = Seq((rootHash, Array.fill[Byte](48)(0xab.toByte)))
      val bytes: Array[Byte] = encodeArchive(header, nodes, Nil)

      // Records, at the synced (flags) write, whether the node had already reached the data source.
      val ds = new FlagsProbeDataSource(rootHash)
      val inMemory = new EphemDataSourceComponent
        with CheckpointImporterSpec.FixedDataSource(ds)
        with LocalPruningConfigBuilder
        with CheckpointImporterSpec.FixedPruningMode(InMemoryPruning(10))
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider
      val importer = new CheckpointImporter(
        BlockchainWriter(inMemory.storages),
        inMemory.storages.stateStorage,
        inMemory.storages.evmCodeStorage,
        inMemory.storages.appStateStorage
      )

      importer.importFromStream(new ByteArrayInputStream(bytes), Some(checkpointChainId)).value
      inMemory.storages.appStateStorage.isSnapSyncDone() shouldBe true
      ds.nodeDurableAtFlags shouldBe Some(true)

    "reject an archive with a mismatched chainId" taggedAs UnitTest in new Setup:
      val header = checkpointHeader
      val bytes: Array[Byte] = encodeArchive(header, Nil, Nil)

      val importer = new CheckpointImporter(
        writer,
        freshStorage.storages.stateStorage,
        freshStorage.storages.evmCodeStorage,
        freshStorage.storages.appStateStorage
      )
      // expect chainId 9999; archive declares 61
      val result: Either[ImportError, ImportResult] =
        importer.importFromStream(new ByteArrayInputStream(bytes), Some(9999L))
      result shouldBe Left(CheckpointImporter.ChainIdMismatch(9999L, checkpointChainId))

      // Best block must remain at 0 on rejection
      freshStorage.storages.appStateStorage.getBestBlockNumber() shouldBe 0
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe false

    "reject a corrupted archive without committing state" taggedAs UnitTest in new Setup:
      val header = checkpointHeader
      val nodes: Seq[(ByteString, Array[Byte])] = Seq((hash("n1"), Array.fill[Byte](32)(0x11)))
      val bytes: Array[Byte] = encodeArchive(header, nodes, Nil)
      // Flip the CRC trailer to ensure a CRC error
      bytes(bytes.length - 1) = (bytes(bytes.length - 1) ^ 0xff).toByte

      val importer = new CheckpointImporter(
        writer,
        freshStorage.storages.stateStorage,
        freshStorage.storages.evmCodeStorage,
        freshStorage.storages.appStateStorage
      )
      val result: Either[ImportError, ImportResult] = importer.importFromStream(new ByteArrayInputStream(bytes), None)
      result.isLeft shouldBe true

      // Phase flags must remain unset on failure (we want the next start to be able to retry)
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe false
      freshStorage.storages.appStateStorage.getBestBlockNumber() shouldBe 0

    // Regression for Bug 35 — operator-supplied output path without `.gz` extension
    // shouldn't break the import. importFromFile sniffs the gzip magic bytes.
    "import a gzipped archive even when the file path lacks .gz extension" taggedAs UnitTest in new Setup:
      import java.nio.file.Files
      import java.util.zip.GZIPOutputStream

      val header = checkpointHeader
      val nodes: Seq[(ByteString, Array[Byte])] = Seq((hash("rootNode"), Array.fill[Byte](32)(0xaa.toByte)))
      val bytes: Array[Byte] = encodeArchive(header, nodes, Nil)

      // Write the archive to a temp file WITH gzip compression but WITHOUT .gz suffix
      val tmp: Path = Files.createTempFile("checkpoint-bug35", ".checkpoint")
      try
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
        result.blockNumber shouldBe header.blockHeader.number.value
        result.nodesImported shouldBe nodes.length
      finally Files.deleteIfExists(tmp)
  }

  private trait Setup extends EphemBlockchainTestSetup:
    val checkpointChainId: Long = 61L
    val freshStorage = getNewStorages
    val writer: BlockchainWriter = BlockchainWriter(freshStorage.storages)
    val blockReader: BlockchainReader =
      com.chipprbots.ethereum.domain.BlockchainReader(freshStorage.storages)

    val checkpointHeader: CheckpointArchive.Header =
      CheckpointArchive.Header(
        chainId = checkpointChainId,
        blockHeader = Fixtures.Blocks.Block3125369.header,
        chainWeight = ChainWeight.totalDifficultyOnly(BigInt("987654321"))
      )

    def hash(s: String): ByteString = ByteString(s.padTo(32, '_').getBytes("UTF-8"))

    def encodeArchive(
        header: CheckpointArchive.Header,
        nodes: Seq[(ByteString, Array[Byte])],
        bytecodes: Seq[(ByteString, Array[Byte])]
    ): Array[Byte] =
      val baos = new ByteArrayOutputStream()
      val w = new CheckpointArchive.Writer(baos)
      w.writeHeader(header)
      nodes.foreach { case (h, n) => w.writeNode(h, n) }
      bytecodes.foreach { case (h, b) => w.writeBytecode(h, b) }
      w.finish()
      baos.toByteArray

object CheckpointImporterSpec:
  /** Puts `ds` under every store; must come before `Storages.DefaultStorages` in the mix-in order. */
  trait FixedDataSource(ds: EphemDataSource) extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = ds

  /** Sets the pruning mode before `Storages.DefaultStorages` reads it (its `storages` val is built at construction). */
  trait FixedPruningMode(mode: PruningMode) extends Storages.PruningModeComponent:
    override val pruningMode: PruningMode = mode

/** Records, at the synced (flags) write, whether `probe` had already reached the data source. */
final class FlagsProbeDataSource(probe: ByteString) extends EphemDataSource(Map.empty):
  var nodeDurableAtFlags: Option[Boolean] = None
  override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
    nodeDurableAtFlags = Some(new NodeStorage(this).get(probe).isDefined)
    super.update(dataSourceUpdates)

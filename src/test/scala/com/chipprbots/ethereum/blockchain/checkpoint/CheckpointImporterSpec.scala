package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportError
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportResult
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporterSpec.FixedDataSource
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporterSpec.FixedPruningMode
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.components.Storages.DefaultStorages
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointImporterSpec extends AnyWordSpec with Matchers with EitherValues with OptionValues:
  import CheckpointV2ImportKit.*

  "CheckpointImporter" should {

    "import a two-account archive end-to-end" taggedAs UnitTest in new Setup:
      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(bytecodes), checkpointChainId)
      val result: ImportResult = importer(freshStorage, trust).importFromStream(new ByteArrayInputStream(bytes)).value
      val checkpoint: BlockHeader =
        trust.expectedBlockHash.flatMap(h => blockReader.getBlockHeaderByHash(BlockHash(h))).value

      result.blockNumber shouldBe checkpoint.number.value
      result.nodesImported shouldBe nodes.length
      result.bytecodesImported shouldBe bytecodes.length

      // Best-block pointers
      val best: BlockInfo = freshStorage.storages.appStateStorage.getBestBlockInfo()
      best.number shouldBe checkpoint.number.value
      best.hash shouldBe checkpoint.hash.value

      // Phase flags set so SNAP isn't re-entered
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe true
      freshStorage.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
      freshStorage.storages.appStateStorage.isStorageRecoveryDone() shouldBe true

      // Header retrievable
      blockReader.getBlockHeaderByNumber(checkpoint.number.value).value shouldBe checkpoint

      // Each node under keccak of its bytes, on the hash layout.
      nodes.foreach { rlp =>
        freshStorage.storages.nodeStorage.get(ByteString(crypto.kec256(rlp))).map(_.toSeq) shouldBe Some(rlp.toSeq)
      }

      // Bytecodes retrievable
      bytecodes.foreach { code =>
        freshStorage.storages.evmCodeStorage.get(ByteString(crypto.kec256(code))).map(_.toArray.toSeq) shouldBe
          Some(code.toSeq)
      }

    "write the imported nodes to the data source before the completion flags under inmemory pruning (#1581)" taggedAs UnitTest in new Setup:
      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(bytecodes), checkpointChainId)

      // Records, at the synced (flags) write, whether the node had already reached the data source.
      val ds = new FlagsProbeDataSource(root)
      val inMemory: FixedDataSource & LocalPruningConfigBuilder & (FixedPruningMode & DefaultStorages) =
        new EphemDataSourceComponent
          with CheckpointImporterSpec.FixedDataSource(ds)
          with LocalPruningConfigBuilder
          with CheckpointImporterSpec.FixedPruningMode(InMemoryPruning(10))
          with Storages.DefaultStorages
          with com.chipprbots.ethereum.TestInstanceConfigProvider

      importer(inMemory, trust).importFromStream(new ByteArrayInputStream(bytes)).value
      inMemory.storages.appStateStorage.isSnapSyncDone() shouldBe true
      ds.nodeDurableAtFlags shouldBe Some(true)

    "reject an archive of another chain id, before any write" taggedAs UnitTest in new Setup:
      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(bytecodes), checkpointChainId)

      val result: Either[ImportError, ImportResult] =
        importer(freshStorage, trust.copy(chainId = 9999)).importFromStream(new ByteArrayInputStream(bytes))
      result shouldBe Left(CheckpointImporter.BadFormat(CheckpointImporter.WrongChainId(9999, checkpointChainId)))

      // Best block must remain at 0 on rejection
      freshStorage.storages.appStateStorage.getBestBlockNumber() shouldBe 0
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe false
      freshStorage.storages.appStateStorage.getCheckpointImportInProgress() shouldBe None

    "reject a corrupted archive without committing state" taggedAs UnitTest in new Setup:
      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(bytecodes), checkpointChainId)
      // Flip the CRC trailer to ensure a CRC error
      bytes(bytes.length - 1) = (bytes(bytes.length - 1) ^ 0xff).toByte

      val result: Either[ImportError, ImportResult] =
        importer(freshStorage, trust).importFromStream(new ByteArrayInputStream(bytes))
      result shouldBe Left(CheckpointImporter.BadFormat(CheckpointImporter.Decode(CheckpointArchive.BadCrc)))

      // Phase flags must remain unset on failure (we want the next start to be able to retry)
      freshStorage.storages.appStateStorage.isSnapSyncDone() shouldBe false
      freshStorage.storages.appStateStorage.getBestBlockNumber() shouldBe 0

    // Regression for Bug 35 — operator-supplied output path without `.gz` extension
    // shouldn't break the import. importFromFile sniffs the gzip magic bytes.
    "import a gzipped archive even when the file path lacks .gz extension" taggedAs UnitTest in new Setup:
      import java.nio.file.Files
      import java.util.zip.GZIPOutputStream

      val (bytes, trust) = crafted(root, nodes, CheckpointV2TestKit.sortedCodes(bytecodes), checkpointChainId)

      // Write the archive to a temp file WITH gzip compression but WITHOUT .gz suffix
      val tmp: Path = Files.createTempFile("checkpoint-bug35", ".checkpoint")
      try
        val out = new GZIPOutputStream(Files.newOutputStream(tmp))
        try out.write(bytes)
        finally out.close()

        val result = importer(freshStorage, trust).importFromFile(tmp).value
        result.blockNumber shouldBe 3
        result.nodesImported shouldBe nodes.length
      finally Files.deleteIfExists(tmp)
  }

  private trait Setup extends EphemBlockchainTestSetup:
    val checkpointChainId: Long = 61L
    val freshStorage = getNewStorages
    val blockReader: BlockchainReader =
      com.chipprbots.ethereum.domain.BlockchainReader(freshStorage.storages)

    val bytecodes: Seq[Array[Byte]] = Seq("bytecode1".getBytes("UTF-8"), "bytecode2".getBytes("UTF-8"))

    /** Two accounts, each with one of the codes: a branch over two account leaves. */
    val (root, nodes) = Nodes.twoAccounts(
      "a" -> Nodes.account(code = Some(bytecodes(0))),
      "c" -> Nodes.account(code = Some(bytecodes(1)))
    )

    def importer(s: Storages.DefaultStorages, trust: CheckpointImporter.Trust): CheckpointImporter =
      new CheckpointImporter(
        BlockchainWriter(s.storages),
        s.storages.stateStorage,
        s.storages.evmCodeStorage,
        s.storages.appStateStorage,
        trust
      )

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

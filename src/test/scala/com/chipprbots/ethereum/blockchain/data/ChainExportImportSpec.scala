package com.chipprbots.ethereum.blockchain.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.pow.validators.MockedPowBlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.jsonrpc.AdminService
import com.chipprbots.ethereum.jsonrpc.conformance.RpcCompatNode
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Chain export and import round-trip in go-ethereum's chain format (CQ-020-01).
  *
  * The fixture is execution-apis' `tests/chain.rlp`: 60 blocks written by go-ethereum, covering legacy, access-list,
  * dynamic-fee, blob and set-code transactions, withdrawals, EIP-7685 requests and Amsterdam (23-field) headers. It is
  * imported through the strict importer — full pre-execution validation, execution, and every post-execution check —
  * exported back, and the exported bytes are compared with geth's. Blocks 1-35 carry fake Ethash seals, so the header
  * validator mocks the seal check and nothing else (see [[RpcCompatNode]]).
  */
class ChainExportImportSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  private given ActorSystem = system.toClassic
  private given ActorTestKit = testKit

  private val fixtureBytes: Array[Byte] =
    val in = getClass.getClassLoader.getResourceAsStream(RpcCompatNode.Resources + "chain.rlp")
    try in.readAllBytes()
    finally in.close()

  private lazy val fixtureBlocks: Vector[Block] = readAll(fixtureBytes)

  private def readAll(bytes: Array[Byte]): Vector[Block] =
    val reader = new ChainFile.Reader(new ByteArrayInputStream(bytes), "test")
    Iterator
      .continually(reader.next())
      .takeWhile(_ != Right(None))
      .map(_.fold(err => fail(err.describe), _.get))
      .toVector

  private def tempDir(): Path = Files.createTempDirectory("chain-io")

  private def write(bytes: Array[Byte]): Path =
    val file = tempDir().resolve("chain.rlp")
    Files.write(file, bytes)
    file

  private def concat(blocks: Seq[Block]): Array[Byte] = blocks.flatMap(b => ChainFile.encode(b).toSeq).toArray

  /** The pre-merge PoW seal check mocked (fake fixture seals); every other header rule as on a live Engine API node. */
  private object MockedSealHeaderValidator extends BlockHeaderValidator:
    override def validate(header: BlockHeader, getBlockHeaderByHash: GetBlockHeaderByHash)(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockHeaderError, BlockHeaderValid] =
      if header.difficulty == Difficulty.Zero then PoSBlockHeaderValidator.validate(header, getBlockHeaderByHash)
      else MockedPowBlockHeaderValidator.validate(header, getBlockHeaderByHash)

    override def validateHeaderOnly(header: BlockHeader)(implicit
        blockchainConfig: BlockchainConfig
    ): Either[BlockHeaderError, BlockHeaderValid] =
      if header.difficulty == Difficulty.Zero then PoSBlockHeaderValidator.validateHeaderOnly(header)
      else MockedPowBlockHeaderValidator.validateHeaderOnly(header)

  /** An in-memory data source that can be told to fail its next write, as a process killed mid-commit would. */
  private class ArmedDataSource extends EphemDataSource(Map()):
    /** Fail the next write that stores a block header — the block's commit — before any of it lands. */
    @volatile var failNextCommit = false

    /** The namespaces of the write that was failed. */
    @volatile var failedWrite: Set[Char] = Set.empty
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      val storesHeader = dataSourceUpdates.exists {
        case DataSourceUpdate(ns, _, upserts) => ns == Namespaces.HeaderNamespace && upserts.nonEmpty
        case DataSourceUpdateOptimized(ns, _, upserts) =>
          ns.toIndexedSeq == Namespaces.HeaderNamespace && upserts.nonEmpty
      }
      if failNextCommit && storesHeader then
        failNextCommit = false
        failedWrite = dataSourceUpdates.map {
          case DataSourceUpdate(ns, _, _)          => ns.head.toChar
          case DataSourceUpdateOptimized(ns, _, _) => ns.head.toChar
        }.toSet
        throw new java.io.IOException("simulated crash during the block commit")
      super.update(dataSourceUpdates)

  private trait ArmedDataSourceComponent extends EphemDataSourceComponent:
    override val dataSource: EphemDataSource = new ArmedDataSource

  private trait BasicPruningConfig extends PruningConfigBuilder with TestInstanceConfigProvider:
    override val pruningMode: PruningMode = BasicPruning(64)

  /** A fresh in-memory node holding only the fixture's genesis; archive pruning unless `basicPruning`. */
  private class Node(basicPruning: Boolean = false) extends RpcCompatNode:
    override lazy val validators: Validators = ValidatorsExecutor(MockedSealHeaderValidator)

    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      if basicPruning then
        new ArmedDataSourceComponent
          with LocalPruningConfigBuilder
          with BasicPruningConfig
          with Storages.DefaultStorages
          with TestInstanceConfigProvider
      else
        new ArmedDataSourceComponent
          with LocalPruningConfigBuilder
          with Storages.DefaultStorages
          with TestInstanceConfigProvider

    def armed: ArmedDataSource = storagesInstance.dataSource.asInstanceOf[ArmedDataSource]

    /** Every key in the database, hex-encoded (ByteBuffers print as their position, not their bytes), except contract
      * code: execution writes new bytecode straight to the code store, keyed by its hash, on every import path (sync
      * and the Engine API as well) — an unreferenced code entry is inert, unlike a trie node with a reference count.
      */
    def keys: Set[String] = armed.storage.keySet
      .filterNot(_.get(0) == Namespaces.CodeNamespace.head)
      .map(b => org.bouncycastle.util.encoders.Hex.toHexString(b.array()))

    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    ).loadGenesisData()

    /** Run once, after the importer's extends-head check and before the block executes and commits: stands in for a
      * head writer outside the chain insert lock (a consensus client's forkchoiceUpdated).
      */
    @volatile var beforeExecution: Option[() => Unit] = None

    lazy val importer: ChainImporter =
      val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
      new ChainImporter(
        blockchainReader,
        blockchainWriter,
        new BlockExecution(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.evmCodeStorage,
          mining.blockPreparator,
          blockValidation
        ):
          override def executeAndAdoptBlockIf(
              block: Block,
              parentWeight: ChainWeight,
              beforeCommit: () => Option[BlockExecutionError]
          )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockData] =
            val hook = beforeExecution
            beforeExecution = None
            hook.foreach(_())
            super.executeAndAdoptBlockIf(block, parentWeight, beforeCommit)
        ,
        blockValidation
      )

    def importStrict(file: Path): Either[ChainImporter.ImportFailure, ChainImporter.ImportSummary] =
      importer.importChainFileStrict(file.toString)

    def head: BlockHeader = blockchainReader.getBestBlockHeader.get

  /** A node with the whole fixture imported, shared by the read-only tests. */
  private lazy val source: Node =
    val node = new Node
    node.importStrict(write(fixtureBytes)) match
      case Right(summary) => summary.imported shouldBe 60
      case Left(failure)  => fail(failure.describe)
    node

  "ChainFile.Reader" should "read go-ethereum's chain file and re-encode it byte for byte" taggedAs UnitTest in {
    fixtureBlocks.map(_.number.value) shouldBe (1 to 60).map(BigInt(_))
    concat(fixtureBlocks) shouldBe fixtureBytes
  }

  it should "read fukuii's old length-prefixed framing" taggedAs UnitTest in {
    val legacy = new ByteArrayOutputStream()
    fixtureBlocks.foreach { b =>
      val bytes = ChainFile.encode(b)
      legacy.write(ByteBuffer.allocate(4).putInt(bytes.length).array())
      legacy.write(bytes)
    }
    val reader = new ChainFile.Reader(new ByteArrayInputStream(legacy.toByteArray), "legacy")
    val blocks = Iterator.continually(reader.next()).takeWhile(_ != Right(None)).map(_.toOption.get.get).toVector
    reader.isLegacyLengthPrefixed shouldBe true
    blocks.map(_.hash) shouldBe fixtureBlocks.map(_.hash)
  }

  it should "report a truncated file with the block it stopped at" taggedAs UnitTest in {
    val reader = new ChainFile.Reader(new ByteArrayInputStream(fixtureBytes.dropRight(10)), "truncated")
    val results = Iterator.continually(reader.next()).takeWhile(_ != Right(None)).take(61).toVector
    results.init.foreach(_.isRight shouldBe true)
    val err = results.last.left.toOption.get
    err.index shouldBe 59
    err.describe should include("truncated")
  }

  it should "reject bytes that are not an RLP list" taggedAs UnitTest in {
    val corrupt = ChainFile.encode(fixtureBlocks(0)) ++ Array[Byte](0x05, 0x01, 0x02)
    val reader = new ChainFile.Reader(new ByteArrayInputStream(corrupt), "corrupt")
    reader.next().map(_.map(_.hash)) shouldBe Right(Some(fixtureBlocks(0).hash))
    reader.next().left.toOption.get.describe should include("expected an RLP list")
  }

  "importChainFileStrict" should "import go-ethereum's chain through full validation" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    source.head.hash shouldBe fixtureBlocks.last.hash
    source.head.stateRoot shouldBe fixtureBlocks.last.header.stateRoot
  }

  "ChainExporter" should "write the imported chain back exactly as go-ethereum wrote it" taggedAs UnitTest in {
    val file = tempDir().resolve("out.rlp")
    val summary = ChainExporter.exportChain(source.blockchainReader, file, 1, 60).toOption.get
    summary.blocks shouldBe 60
    Files.readAllBytes(file) shouldBe fixtureBytes
    // The temporary file is gone once the export is published: only the target is left.
    Files.list(file.getParent).toArray.toList shouldBe List(file)
  }

  it should "round-trip through a gzipped file into a fresh node: same hashes, roots, receipts and access lists" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val file = tempDir().resolve("chain.rlp.gz")
    // From genesis, as `geth export` writes it: the importer must recognise and skip it.
    ChainExporter.exportChain(source.blockchainReader, file, 0, 60) shouldBe a[Right[?, ?]]
    val gunzipped = new GZIPInputStream(Files.newInputStream(file)).readAllBytes()
    val genesis = source.blockchainReader.getBlockByHash(source.blockchainReader.genesisHeader.hash).get
    gunzipped shouldBe ChainFile.encode(genesis) ++ fixtureBytes

    val target = new Node
    val summary = target.importStrict(file).fold(f => fail(f.describe), identity)
    summary.imported shouldBe 60
    for n <- 1 to 60 do
      val expected = source.blockchainReader.getBlockHeaderByNumber(n).get
      val actual = target.blockchainReader.getBlockHeaderByNumber(n).get
      actual.hash shouldBe expected.hash
      actual.stateRoot shouldBe expected.stateRoot
      actual.receiptsRoot shouldBe expected.receiptsRoot
      target.blockchainReader.getReceiptsByHash(actual.hash) shouldBe source.blockchainReader.getReceiptsByHash(
        expected.hash
      )
      target.blockchainReader.getBlockAccessListByHash(actual.hash) shouldBe source.blockchainReader
        .getBlockAccessListByHash(expected.hash)
    source.blockchainReader.getBlockAccessListByHash(source.head.hash) shouldBe defined

    // Importing the same file again changes nothing.
    val again = target.importStrict(file).fold(f => fail(f.describe), identity)
    (again.imported, again.skipped) shouldBe (0L, 60L)
    target.head.hash shouldBe source.head.hash
  }

  it should "fail on a block past the head, leaving no file" taggedAs UnitTest in {
    val dir = tempDir()
    val file = dir.resolve("out.rlp")
    val result = ChainExporter.exportChain(source.blockchainReader, file, 50, 63)
    result.left.toOption.get should include("export failed on #61: not found")
    Files.list(dir).count() shouldBe 0
  }

  it should "fail on a missing body instead of skipping the block" taggedAs UnitTest in {
    val node = new Node
    node.importStrict(write(fixtureBytes)) shouldBe a[Right[?, ?]]
    val hash = node.blockchainReader.getBlockHeaderByNumber(10).get.hash
    node.storagesInstance.storages.blockBodiesStorage.remove(hash.value).commit()
    val result = ChainExporter.exportChain(node.blockchainReader, tempDir().resolve("out.rlp"), 1, 60)
    result.left.toOption.get should include("export failed on #10: not found")
  }

  it should "fail on a stored body that does not match its header" taggedAs UnitTest in {
    // Seen on a live Ephemery node: the SNAP pivot block's body was stored without its withdrawals. The block still
    // encodes under the right hash, but no client can import it.
    val node = new Node
    node.importStrict(write(fixtureBytes)) shouldBe a[Right[?, ?]]
    val withWithdrawals = fixtureBlocks.find(_.body.withdrawals.exists(_.nonEmpty)).get
    node.storagesInstance.storages.blockBodiesStorage
      .put(withWithdrawals.hash.value, withWithdrawals.body.copy(withdrawals = None))
      .commit()
    val n = withWithdrawals.number.value
    val dir = tempDir()
    val result = ChainExporter.exportChain(node.blockchainReader, dir.resolve("out.rlp"), 1, 60)
    result.left.toOption.get should include(s"export failed on #$n: the stored body does not match its header")
    Files.list(dir).count() shouldBe 0
  }

  it should "refuse to overwrite an existing file" taggedAs UnitTest in {
    val file = write(Array[Byte](1, 2, 3))
    ChainExporter.exportChain(source.blockchainReader, file, 1, 2).left.toOption.get should include("already exists")
    Files.readAllBytes(file) shouldBe Array[Byte](1, 2, 3)
  }

  "importChainFileStrict" should "stop at a truncated file, keeping the blocks before it" taggedAs UnitTest in {
    val node = new Node
    val failure = node.importStrict(write(fixtureBytes.dropRight(10))).left.toOption.get
    failure.describe should include("truncated")
    failure.progress.imported shouldBe 59
    node.head.number.value shouldBe 59
  }

  it should "stop at an invalid block and name it" taggedAs UnitTest in {
    val bad = fixtureBlocks(20)
    val tampered = bad.copy(header = bad.header.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x11)))))
    val node = new Node
    val failure = node.importStrict(write(concat(fixtureBlocks.take(20) :+ tampered))).left.toOption.get
    failure.reason should include("block #21")
    failure.reason should include("invalid")
    failure.progress.imported shouldBe 20
    node.head.hash shouldBe fixtureBlocks(19).hash
  }

  it should "leave nothing behind for a block that fails after execution (basic pruning)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val node = new Node(basicPruning = true)
    node.storagesInstance.pruningMode shouldBe BasicPruning(64)
    node.importStrict(write(concat(fixtureBlocks.take(20)))).map(_.imported) shouldBe Right(20L)
    val before = node.keys
    val bad = fixtureBlocks(20)
    val tampered = bad.copy(header = bad.header.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x11)))))
    val failure = node.importStrict(write(concat(fixtureBlocks.take(20) :+ tampered))).left.toOption.get
    failure.reason should include("block #21")
    // Not a trie node, reference count, receipt or block record more than before the attempt.
    val after = node.keys
    withClue(s"added=${after -- before} removed=${before -- after}") {
      after shouldBe before
    }
    node.blockchainReader.getBlockHeaderByHash(tampered.hash) shouldBe None
    node.head.hash shouldBe fixtureBlocks(19).hash
    // And the valid block 21 then imports cleanly on top: no skewed reference counts in the way.
    node.importStrict(write(fixtureBytes)).map(_.imported) shouldBe Right(40L)
    node.head.hash shouldBe fixtureBlocks.last.hash
  }

  it should "leave nothing half-written when the commit itself fails, and resume afterwards" taggedAs UnitTest in {
    val node = new Node(basicPruning = true)
    node.importStrict(write(concat(fixtureBlocks.take(20)))).map(_.imported) shouldBe Right(20L)
    val before = node.keys
    node.armed.failNextCommit = true
    val file = write(fixtureBytes)
    val failure = node.importStrict(file).left.toOption.get
    failure.reason should include("simulated crash")
    // The write that failed is the block's single atomic commit: the block record, its receipts and weight, the head
    // pointer and the block's trie nodes all in one batch — not some earlier write.
    (node.armed.failedWrite should contain).allOf('h', 'b', 'r', 'w', 's', 'n')
    failure.progress.skipped shouldBe 20
    failure.progress.imported shouldBe 0
    val after = node.keys
    withClue(s"added=${after -- before} removed=${before -- after}") {
      after shouldBe before
    }
    node.blockchainReader.getBlockHeaderByHash(fixtureBlocks(20).hash) shouldBe None
    node.head.hash shouldBe fixtureBlocks(19).hash
    // Re-running the import picks up exactly where it stopped.
    node.importStrict(file).map(s => (s.imported, s.skipped)) shouldBe Right((40L, 20L))
    node.head.hash shouldBe fixtureBlocks.last.hash
  }

  it should "not overwrite a head that moved while the block executed" taggedAs UnitTest in {
    val node = new Node(basicPruning = true) // archive mode writes trie nodes straight through, by design
    node.importStrict(write(concat(fixtureBlocks.take(20)))).map(_.imported) shouldBe Right(20L)
    // A consensus client re-points the head to block 19 after the importer checked that block 21 extends block 20.
    val block19 = fixtureBlocks(18)
    node.beforeExecution = Some(() =>
      node.storagesInstance.storages.appStateStorage
        .putBestBlockInfo(BlockInfo(block19.hash.value, block19.number.value))
        .commit()
    )
    val keysBefore = node.keys
    val failure = node.importStrict(write(concat(fixtureBlocks.take(21)))).left.toOption.get
    failure.reason should include("block #21")
    failure.reason should include("head moved during import")
    failure.progress.imported shouldBe 0
    node.blockchainReader.getBlockHeaderByHash(fixtureBlocks(20).hash) shouldBe None
    node.head.hash shouldBe block19.hash
    // Only the moved head pointer changed; block 21's staged state was discarded, not committed.
    val changed = (node.keys -- keysBefore) ++ (keysBefore -- node.keys)
    withClue(s"changed=$changed")(changed.forall(_.startsWith("73")) shouldBe true)
  }

  it should "refuse a file with a gap" taggedAs UnitTest in {
    val node = new Node
    val failure = node.importStrict(write(concat(fixtureBlocks.patch(4, Nil, 1)))).left.toOption.get
    failure.reason should include("does not follow the previous block in the file")
    node.head.number.value shouldBe 4
  }

  it should "refuse a file that does not continue the local chain" taggedAs UnitTest in {
    val node = new Node
    val failure = node.importStrict(write(concat(fixtureBlocks.drop(10)))).left.toOption.get
    failure.reason should include("is not in the local chain")
    failure.progress.imported shouldBe 0
    node.head.number.value shouldBe 0
  }

  it should "import fukuii's old length-prefixed export" taggedAs UnitTest in {
    val legacy = new ByteArrayOutputStream()
    fixtureBlocks.foreach { b =>
      val bytes = ChainFile.encode(b)
      legacy.write(ByteBuffer.allocate(4).putInt(bytes.length).array())
      legacy.write(bytes)
    }
    val node = new Node
    node.importStrict(write(legacy.toByteArray)).map(_.imported) shouldBe Right(60L)
    node.head.hash shouldBe fixtureBlocks.last.hash
  }

  "importChainFile (hive mode)" should "still import every valid block and continue past an invalid one" taggedAs UnitTest in {
    val bad = fixtureBlocks(59)
    val tampered = bad.copy(header = bad.header.copy(gasUsed = GasAmount(1)))
    val node = new Node
    given BlockchainConfig = node.blockchainConfig
    node.importer.importChainFile(write(concat(fixtureBlocks.init :+ tampered :+ bad)).toString) shouldBe (60, 0, 1)
    node.head.hash shouldBe bad.hash
  }

  "admin_exportChain / admin_importChain" should "round-trip through the RPC service" taggedAs (UnitTest, RPCTest) in {
    var syncStatus: SyncProtocol.Status = SyncProtocol.Status.NotSyncing
    val target = new Node
    def service(node: Node) = new AdminService(
      null,
      null,
      node.blockchainReader,
      node.blockchainConfig,
      scala.concurrent.duration.DurationInt(5).seconds,
      "/tmp",
      new BlockedIPRegistry(Set.empty),
      node.importer,
      () => cats.effect.IO.pure(syncStatus)
    )(using system.scheduler)
    val file = tempDir().resolve("rpc.rlp")
    import cats.effect.unsafe.implicits.global
    service(source)
      .exportChain(AdminService.AdminExportChainRequest(file.toString, Some(1), None))
      .unsafeRunSync() shouldBe Right(AdminService.AdminExportChainResponse(true))
    Files.readAllBytes(file) shouldBe fixtureBytes
    // Refused while the node syncs: the file must continue a head that sync is moving.
    syncStatus = SyncProtocol.Status.Syncing(0, SyncProtocol.Status.Progress(10, 100), None)
    val refused = service(target).importChain(AdminService.AdminImportChainRequest(file.toString)).unsafeRunSync()
    refused.left.toOption.get.message should include("the node is syncing")
    target.head.number.value shouldBe 0

    syncStatus = SyncProtocol.Status.SyncDone
    service(target)
      .importChain(AdminService.AdminImportChainRequest(file.toString))
      .unsafeRunSync() shouldBe Right(AdminService.AdminImportChainResponse(true))
    target.head.hash shouldBe source.head.hash

    val error = service(source)
      .exportChain(AdminService.AdminExportChainRequest(file.toString, Some(1), Some(2)))
      .unsafeRunSync()
    error.left.toOption.get.message should include("already exists")
  }

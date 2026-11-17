package com.chipprbots.ethereum.blockchain.checkpoint
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import scala.compiletime.uninitialized

import org.bouncycastle.util.encoders.Hex
import org.scalatest.BeforeAndAfterEach
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointExporter.ExportError
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointExporter.ExportResult
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportResult
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.ChosenState
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.UnitTest

class CheckpointExporterSpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with OptionValues
    with BeforeAndAfterEach:

  "CheckpointExporter" should {

    // Spec 018 Track C, C2b (T059): the export reads every node at its position. The archive is format v1 still, a list
    // of nodes by hash, so on a hash store its bytes are what they were: frozen here from the code before the change,
    // for a state with extensions 4 and 62 nibbles long, nodes embedded in their parents, and one storage trie that two
    // accounts hold.
    "write, for a state with long extensions, embedded nodes and a shared storage trie, the bytes it wrote before it read by position" taggedAs UnitTest in new Setup:
      chosenAtBlock100()
      val archive: Path = tmpRoot.resolve("chosen.checkpoint")
      val result: ExportResult = sourceExporter.exportArchive(BigInt(100), archive).value
      result.bytecodesExported shouldBe 3
      (result.nodesExported, sha256(Files.readAllBytes(archive))) shouldBe FrozenChosenArchive

    // The same state as rows of the position store, where each account has a storage trie of its own. The archive is
    // of nodes by hash, so it is the same file: each distinct node once, the shared trie read under one account.
    "write the same bytes from a position store" taggedAs UnitTest in new Setup:
      val chosen: ChosenState = chosenAtBlock100()
      val archive: Path = tmpRoot.resolve("chosen-positions.checkpoint")
      val result: ExportResult = exporterOnPositions(chosen).exportArchive(BigInt(100), archive).value
      (result.nodesExported, sha256(Files.readAllBytes(archive))) shouldBe FrozenChosenArchive

    // The position store holds the state of one block. Another block's is not a hole to report node by node: the
    // export is refused, before a file is opened.
    "refuse a block whose state the position store does not hold, and leave no file" taggedAs UnitTest in new Setup:
      val chosen: ChosenState = chosenAtBlock100()
      val archive: Path = tmpRoot.resolve("not-held.checkpoint")
      // block 99 of the same chain: its header and its weight are stored, and the disk holds block 100's state
      val header99: BlockHeader = sourceReader.getBlockHeaderByNumber(BigInt(99)).value
      sourceWriter.storeChainWeight(header99.hash, ChainWeight.totalDifficultyOnly(chainTd(99))).commit()
      exporterOnPositions(chosen)
        .exportArchive(BigInt(99), archive)
        .left
        .value shouldBe a[CheckpointExporter.StateNotHeld]
      Files.exists(archive) shouldBe false

    // An account's storage trie is found, on a position store, by the hash the account is kept under: 32 bytes. An
    // account with storage under any other key names no trie. The export says the account trie is corrupt, where the
    // store would have thrown.
    "say the account trie is corrupt when an account with storage is kept under a key that is not 32 bytes, on a position store" taggedAs UnitTest in new Setup:
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      val chosen: ChosenState = chosenAtBlock100()
      val short: ByteString = com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.chosenKey("ab", 1).take(31)
      val root: ByteString = ByteString(
        MerklePatriciaTrie[ByteString, Account](sourceStorages.storages.stateStorage.getBackingStorage(0))
          .put(short, chosen.accounts.toMap.apply(chosen.holder))
          .getRootHash
      )
      // block 101 carries that state; the position store holds its account trie
      val header101: BlockHeader = storeHeaderChain(sourceWriter, 101, root).last
      sourceWriter.storeChainWeight(header101.hash, ChainWeight.totalDifficultyOnly(chainTd(101))).commit()
      val onPositions: BlockchainReader = readerOnRows(root, chosen.load, BigInt(101), accountTrieOnly = true, identity)
      val refused: ExportError =
        new CheckpointExporter(sourceStorages.storages.evmCodeStorage, onPositions, chainId = 1L)
          .exportArchive(BigInt(101), tmpRoot.resolve("short-key.checkpoint"))
          .left
          .value
      refused shouldBe CheckpointExporter.CorruptAccountKey(short)
      // a hash store reads the trie by its root, as it always did
      sourceExporter
        .exportArchive(BigInt(101), tmpRoot.resolve("short-key-hashes.checkpoint"))
        .value shouldBe a[ExportResult]

    // An export holds one view across every read. When import moves the disk on under it, a read says so. That is not
    // a trie node that is missing, which is what a damaged state would be reported as.
    "say the state moved, and not that a node is missing, wherever the disk moves under an export" taggedAs UnitTest in new Setup:
      import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.StaleAfter
      val chosen: ChosenState = chosenAtBlock100()
      def exportWith(healthy: Int, name: String): (Either[ExportError, ExportResult], Int) =
        var handed: Option[StaleAfter] = None
        val reader = readerOnPositions(
          chosen,
          state =>
            val stale = new StaleAfter(state, healthy)
            handed = Some(stale)
            stale
        )
        val result = new CheckpointExporter(sourceStorages.storages.evmCodeStorage, reader, chainId = 1L)
          .exportArchive(BigInt(100), tmpRoot.resolve(name))
        (result, handed.map(_.readsAsked).getOrElse(0))

      val (whole, reads) = exportWith(Int.MaxValue, "whole.checkpoint")
      whole.value.nodesExported shouldBe FrozenChosenArchive._1
      // one read for each node written: a node is read once, and a trie another account also holds is not read again
      reads shouldBe FrozenChosenArchive._1.toInt
      (0 until reads).foreach { healthy =>
        withClue(s"the state moved after $healthy of $reads reads: ") {
          exportWith(healthy, s"moved-$healthy.checkpoint")._1.left.value shouldBe a[CheckpointExporter.StateNotHeld]
        }
      }
      exportWith(reads, "moved-after.checkpoint")._1.value shouldBe a[ExportResult]

    "round-trip a small state through export → import yielding identical trie nodes" taggedAs UnitTest in new Setup:
      // Bytecodes referenced by accounts
      val codeA: ByteString = ByteString("contract-A-bytecode")
      val codeB: ByteString = ByteString("contract-B-bytecode-longer-for-variety")
      val codeAHash: ByteString = crypto.kec256(codeA)
      val codeBHash: ByteString = crypto.kec256(codeB)
      sourceStorages.storages.evmCodeStorage.put(codeAHash, codeA).commit()
      sourceStorages.storages.evmCodeStorage.put(codeBHash, codeB).commit()

      // Account 2 has a storage trie with two slots.
      import MerklePatriciaTrie.defaultByteArraySerializable
      val storageTrie: MerklePatriciaTrie[Array[Byte], Array[Byte]] = MerklePatriciaTrie[Array[Byte], Array[Byte]](
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
      val storageRoot: ByteString = ByteString(storageTrie.getRootHash)

      // Main account trie with three accounts.
      val addr1: Array[Byte] = Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")
      val addr2: Array[Byte] = Hex.decode("11111111111111111111111111111111111111aa")
      val addr3: Array[Byte] = Hex.decode("22222222222222222222222222222222222222bb")
      val accountTrie: MerklePatriciaTrie[Array[Byte], Account] = MerklePatriciaTrie[Array[Byte], Account](
        sourceStorages.storages.stateStorage.getBackingStorage(0)
      )
        .put(
          crypto.kec256(addr1),
          Account(nonce = UInt256(0), balance = UInt256(100), codeHash = CodeHash(codeAHash))
        )
        .put(
          crypto.kec256(addr2),
          Account(
            nonce = UInt256(0),
            balance = UInt256(0),
            storageRoot = TrieRoot(storageRoot),
            codeHash = CodeHash(codeBHash)
          )
        )
        .put(
          crypto.kec256(addr3),
          Account(nonce = UInt256(1), balance = UInt256(1)) // empty code + storage
        )
      val stateRoot: ByteString = ByteString(accountTrie.getRootHash)

      // A stored header chain 0..100 whose tip carries the constructed stateRoot, stored with its exact weight.
      val header: BlockHeader = storeHeaderChain(sourceWriter, 100, stateRoot).last
      val weight: ChainWeight = ChainWeight.totalDifficultyOnly(chainTd(100))
      sourceWriter.storeChainWeight(header.hash, weight).commit()

      // Export
      val outputPath: Path = tmpRoot.resolve("export.checkpoint")
      val exporter = new CheckpointExporter(
        sourceStorages.storages.evmCodeStorage,
        sourceReader,
        chainId = 1337L
      )
      val exportResult: ExportResult = exporter.exportArchive(header.number.value, outputPath).value
      exportResult.nodesExported should be > 0L
      exportResult.bytecodesExported shouldBe 2L

      // Import into a fresh storage stack
      val importer = new CheckpointImporter(
        targetWriter,
        targetStorages.storages.stateStorage,
        targetStorages.storages.evmCodeStorage,
        targetStorages.storages.appStateStorage
      )
      val importResult: ImportResult = importer.importFromFile(outputPath, Some(1337L)).value
      importResult.blockNumber shouldBe header.number.value
      importResult.nodesImported shouldBe exportResult.nodesExported
      importResult.bytecodesImported shouldBe exportResult.bytecodesExported

      // Verify the imported state can re-derive the same stateRoot via MerklePatriciaTrie traversal.
      // Round-tripped trie nodes must be byte-identical because trie nodes are content-addressed.
      val importedTrie: MerklePatriciaTrie[Array[Byte], Account] = MerklePatriciaTrie[Array[Byte], Account](
        stateRoot.toArray,
        targetStorages.storages.stateStorage.getBackingStorage(0)
      )
      importedTrie.get(crypto.kec256(addr1)).value.balance shouldBe UInt256(100)
      importedTrie.get(crypto.kec256(addr2)).value.codeHash shouldBe CodeHash(codeBHash)
      importedTrie.get(crypto.kec256(addr3)).value.nonce shouldBe UInt256(1)

      // Storage trie reachable from account 2
      val importedStorageTrie: MerklePatriciaTrie[Array[Byte], Array[Byte]] =
        MerklePatriciaTrie[Array[Byte], Array[Byte]](
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
      targetReader.getBlockHeaderByNumber(header.number.value).value shouldBe header
      targetReader.getChainWeightByHash(header.hash).value shouldBe weight
      targetStorages.storages.appStateStorage.getBestBlockNumber() shouldBe header.number.value
      targetStorages.storages.appStateStorage.isSnapSyncDone() shouldBe true

    "fail cleanly when the requested block is missing" taggedAs UnitTest in new Setup:
      val exporter = new CheckpointExporter(
        sourceStorages.storages.evmCodeStorage,
        sourceReader,
        chainId = 1L
      )
      val r: Either[ExportError, ExportResult] =
        exporter.exportArchive(blockNumber = 9999, output = tmpRoot.resolve("nope.checkpoint"))
      r shouldBe Left(CheckpointExporter.NoSuchBlock(9999))

    // Regression for Bug 33 — exporter must unwrap ReferenceCountNodeStorage wrapper bytes.
    // Reading raw nodeStorage bytes on a BasicPruning chain surfaces the ref-count metadata,
    // which fails to decode as an MPT node. Exporter now goes through StateStorage so the
    // wrapper is transparent.
    "round-trip state stored under BasicPruning (ref-counted) without MPT decode failure" taggedAs UnitTest in new BasicPruningSetup:
      val codeA: ByteString = ByteString("contract-A-bytecode")
      val codeAHash: ByteString = crypto.kec256(codeA)
      sourceStorages.storages.evmCodeStorage.put(codeAHash, codeA).commit()

      import MerklePatriciaTrie.defaultByteArraySerializable
      val addr1: Array[Byte] = Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")
      val addr2: Array[Byte] = Hex.decode("11111111111111111111111111111111111111aa")
      // Use a non-zero block number so ReferenceCountNodeStorage tags writes properly.
      val sourceBackingStorage: MptStorage = sourceStorages.storages.stateStorage.getBackingStorage(100)
      val accountTrie: MerklePatriciaTrie[Array[Byte], Account] =
        MerklePatriciaTrie[Array[Byte], Account](sourceBackingStorage)
          .put(
            crypto.kec256(addr1),
            Account(nonce = UInt256(0), balance = UInt256(100), codeHash = CodeHash(codeAHash))
          )
          .put(crypto.kec256(addr2), Account(nonce = UInt256(1), balance = UInt256(1)))
      val stateRoot: ByteString = ByteString(accountTrie.getRootHash)

      val header: BlockHeader = storeHeaderChain(sourceWriter, 100, stateRoot).last
      sourceWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(chainTd(100))).commit()

      // Export — must NOT throw MPTException(Invalid Node) when nodes are wrapped.
      val outputPath: Path = tmpRoot.resolve("export.checkpoint")
      val exporter = new CheckpointExporter(
        sourceStorages.storages.evmCodeStorage,
        sourceReader,
        chainId = 1L
      )
      val exportResult: ExportResult = exporter.exportArchive(header.number.value, outputPath).value
      exportResult.nodesExported should be > 0L

      // Import into a fresh (ArchivePruning) storage and re-derive the same trie.
      val importer = new CheckpointImporter(
        targetWriter,
        targetStorages.storages.stateStorage,
        targetStorages.storages.evmCodeStorage,
        targetStorages.storages.appStateStorage
      )
      val importResult: ImportResult = importer.importFromFile(outputPath, Some(1L)).value
      importResult.blockNumber shouldBe header.number.value

      val importedTrie: MerklePatriciaTrie[Array[Byte], Account] = MerklePatriciaTrie[Array[Byte], Account](
        stateRoot.toArray,
        targetStorages.storages.stateStorage.getBackingStorage(100)
      )
      importedTrie.get(crypto.kec256(addr1)).value.balance shouldBe UInt256(100)
      importedTrie.get(crypto.kec256(addr2)).value.nonce shouldBe UInt256(1)
      targetStorages.storages.evmCodeStorage.get(codeAHash).map(_.toArray.toSeq) shouldBe Some(codeA.toArray.toSeq)

    // The total difficulty in the archive is summed from genesis, not copied from storage. A SNAP-synced node stores
    // a peer-interpolated estimate at its pivot (Mordor smoke test: 114,098,267 below the chain's at 17,207,611), and
    // an archive would hand that error to every node that imports it.
    "write the exact total difficulty when the stored weight is an estimate" taggedAs UnitTest in new Setup:
      val header: BlockHeader = storeHeaderChain(sourceWriter, 20, oneAccountStateRoot).last
      val estimated: ChainWeight = ChainWeight.totalDifficultyOnly(chainTd(20) - 114098267)
      sourceWriter.storeChainWeight(header.hash, estimated).commit()

      val outputPath: Path = tmpRoot.resolve("exact-td.checkpoint")
      sourceExporter.exportArchive(20, outputPath).value

      readArchiveWeight(outputPath) shouldBe ChainWeight.totalDifficultyOnly(chainTd(20))
      // The exporter is read-only: the stored estimate is left as it was.
      sourceReader.getChainWeightByHash(header.hash).value shouldBe estimated

    "sum the genesis difficulty when exporting the genesis block" taggedAs UnitTest in new Setup:
      val genesis: BlockHeader = storeHeaderChain(sourceWriter, 0, oneAccountStateRoot).head
      sourceWriter.storeChainWeight(genesis.hash, ChainWeight.totalDifficultyOnly(1)).commit()

      sourceExporter.exactTotalDifficulty(genesis).value shouldBe chainTd(0)

    "refuse, writing no file, when an ancestor header is missing" taggedAs UnitTest in new Setup:
      val chain: IndexedSeq[BlockHeader] = storeHeaderChain(sourceWriter, 20, oneAccountStateRoot, skip = Set(7))
      val header = chain.last
      sourceWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(chainTd(20))).commit()

      val outputPath: Path = tmpRoot.resolve("refused.checkpoint")
      sourceExporter.exportArchive(20, outputPath) shouldBe
        Left(CheckpointExporter.IncompleteHeaderChain(7, chain(8).parentHash.value))
      Files.exists(outputPath) shouldBe false

    "export the stored weight when an ancestor is missing and allowEstimatedTd is set" taggedAs UnitTest in new Setup:
      val header: BlockHeader = storeHeaderChain(sourceWriter, 20, oneAccountStateRoot, skip = Set(7)).last
      val stored: ChainWeight = ChainWeight.totalDifficultyOnly(chainTd(20) - 1)
      sourceWriter.storeChainWeight(header.hash, stored).commit()

      val outputPath: Path = tmpRoot.resolve("estimated.checkpoint")
      sourceExporter.exportArchive(20, outputPath, allowEstimatedTd = true).value

      readArchiveWeight(outputPath) shouldBe stored

    "refuse when the parentHash walk reaches a block 0 that is not this chain's genesis" taggedAs UnitTest in new Setup:
      val canonical: IndexedSeq[BlockHeader] = storeHeaderChain(sourceWriter, 3, oneAccountStateRoot)
      // A side header at height 1 whose parent is a block 0 stored by hash only, i.e. not the indexed genesis.
      val otherGenesis: BlockHeader = canonical.head.copy(extraData = ByteString("not-genesis"))
      val sideHeader: BlockHeader = canonical(1).copy(parentHash = otherGenesis.hash)
      sourceWriter.storeBlockByHashOnly(Block(otherGenesis, BlockBody.empty)).commit()
      sourceWriter.storeBlockByHashOnly(Block(sideHeader, BlockBody.empty)).commit()

      sourceExporter.exactTotalDifficulty(sideHeader) shouldBe
        Left(CheckpointExporter.GenesisMismatch(otherGenesis.hash.value))
  }

  /** Difficulty of block `i` in the chains [[storeHeaderChain]] builds: distinct per block so a skipped or doubled term
    * changes the sum.
    */
  private def difficultyAt(i: Int): BigInt = BigInt(131072) + BigInt(i) * 1000

  /** Exact total difficulty of block `n` in those chains: genesis difficulty plus every block's up to `n`. */
  private def chainTd(n: Int): BigInt = (0 to n).map(difficultyAt).sum

  /** Stores a parentHash-linked header chain 0..`tipNumber` (number→hash indexed, block 0 is the genesis) and returns
    * all of it, including the headers in `skip`, which are built but NOT stored. The tip carries `stateRoot`.
    */
  private def storeHeaderChain(
      writer: BlockchainWriter,
      tipNumber: Int,
      stateRoot: ByteString,
      skip: Set[Int] = Set.empty
  ): IndexedSeq[BlockHeader] =
    val template = Fixtures.Blocks.Block3125369.header
    val chain = (0 to tipNumber).foldLeft(Vector.empty[BlockHeader]) { (acc, i) =>
      acc :+ template.copy(
        parentHash = acc.lastOption.map(_.hash).getOrElse(BlockHash(ByteString(new Array[Byte](32)))),
        number = BlockNumber(i),
        difficulty = Difficulty(difficultyAt(i)),
        stateRoot = if i == tipNumber then TrieRoot(stateRoot) else template.stateRoot
      )
    }
    chain.filterNot(h => skip.contains(h.number.value.toInt)).foreach(h => writer.storeBlockHeader(h).commit())
    chain

  private def readArchiveWeight(path: Path): ChainWeight =
    val in = Files.newInputStream(path)
    try new CheckpointArchive.Reader(in).readHeader().value.chainWeight
    finally in.close()

  private var tmpRoot: java.nio.file.Path = uninitialized

  override def beforeEach(): Unit =
    tmpRoot = Files.createTempDirectory("checkpoint-exporter-spec")

  override def afterEach(): Unit =
    import scala.jdk.CollectionConverters.*
    val walk = Files.walk(tmpRoot)
    try walk.iterator.asScala.toSeq.reverse.foreach(p => Files.deleteIfExists(p))
    finally walk.close()

  private def sha256(bytes: Array[Byte]): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  /** The nodes exported and the SHA-256 of the archive, for `ChosenState` at block 100 of the spec's header chain: from
    * the exporter as it was before it read by position (#1797, 2026-11-11).
    */
  private val FrozenChosenArchive: (Long, String) =
    (36L, "d44625066f0491215d261d756ef310b54e1af7e826e96ae0fa56b828ab8c7c0b")

  private trait Setup extends EphemBlockchainTestSetup:
    val sourceStorages = getNewStorages
    val targetStorages = getNewStorages
    val sourceWriter: BlockchainWriter = BlockchainWriter(sourceStorages.storages)
    val targetWriter: BlockchainWriter = BlockchainWriter(targetStorages.storages)
    val sourceReader: BlockchainReader = BlockchainReader(sourceStorages.storages)
    val targetReader: BlockchainReader = BlockchainReader(targetStorages.storages)

    /** State root of a one-account trie stored in the source, so an export has something to walk. */
    lazy val oneAccountStateRoot: ByteString =
      import MerklePatriciaTrie.defaultByteArraySerializable
      val trie = MerklePatriciaTrie[Array[Byte], Account](sourceStorages.storages.stateStorage.getBackingStorage(0))
        .put(crypto.kec256(Hex.decode("33" * 20)), Account(nonce = UInt256(1), balance = UInt256(1)))
      ByteString(trie.getRootHash)
    lazy val sourceExporter: CheckpointExporter = new CheckpointExporter(
      sourceStorages.storages.evmCodeStorage,
      sourceReader,
      chainId = 1L
    )

    /** `ChosenState` on the source's hash store, as the state of block 100 of a stored header chain. */
    def chosenAtBlock100(): ChosenState =
      val chosen = new ChosenState(
        sourceStorages.storages.stateStorage.getBackingStorage(0),
        sourceStorages.storages.evmCodeStorage
      )
      val header: BlockHeader = storeHeaderChain(sourceWriter, 100, chosen.root).last
      sourceWriter.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(chainTd(100))).commit()
      chosen

    /** The source's chain, read by a reader whose state is a position store that holds block 100's: the rows of
      * `chosen`, copied from the hash store. `handing` stands between the reader and whoever takes a state from it.
      */
    def readerOnPositions(chosen: ChosenState, handing: MptStorage => MptStorage = identity): BlockchainReader =
      readerOnRows(chosen.root, chosen.load, BigInt(100), accountTrieOnly = false, handing)

    /** The same for any state on the source's hash store, as the state of block `number`. With `accountTrieOnly` the
      * storage tries are left out: for a state one of whose accounts has no owner to be written under.
      */
    def readerOnRows(
        root: ByteString,
        load: Array[Byte] => Array[Byte],
        number: BigInt,
        accountTrieOnly: Boolean,
        handing: MptStorage => MptStorage
    ): BlockchainReader =
      import com.chipprbots.ethereum.db.dataSource.EphemDataSource
      import com.chipprbots.ethereum.db.storage.AppStateStorage
      import com.chipprbots.ethereum.db.storage.PathNodeStorage
      import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
      import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
      import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
      import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
      val header = sourceReader.getBlockHeaderByNumber(number).value
      val rows = EphemDataSource()
      PathStateTestKit.seedPositions(
        rows,
        if accountTrieOnly then Map(Option.empty[ByteString] -> RawTrieWalk.storedPositions(root.toArray, load))
        else RawTrieWalk.statePositions(root.toArray, load),
        BlockRef(header.hash.value, header.number.value, header.stateRoot.value)
      )
      val s = sourceStorages.storages
      new BlockchainReader(
        s.blockHeadersStorage,
        s.blockBodiesStorage,
        s.blockNumberMappingStorage,
        s.stateStorage,
        s.receiptStorage,
        s.appStateStorage,
        s.chainWeightStorage,
        s.blockAccessListStorage,
        Some(new PathStateStorage(new PathNodeStorage(rows), new AppStateStorage(rows)))
      ):
        override def stateAt(of: BlockHeader): MptStorage = handing(super.stateAt(of))

    def exporterOnPositions(chosen: ChosenState): CheckpointExporter =
      new CheckpointExporter(sourceStorages.storages.evmCodeStorage, readerOnPositions(chosen), chainId = 1L)

  /** Source uses BasicPruning so trie nodes go through ReferenceCountNodeStorage's ref-count wrapping — the path that
    * exposed Bug 33. Target stays ArchivePruning (matches the default `Setup`).
    */
  private trait BasicPruningSetup extends EphemBlockchainTestSetup:
    import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
    import com.chipprbots.ethereum.db.components.Storages
    import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
    import com.chipprbots.ethereum.db.storage.pruning.PruningMode
    import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder

    trait BasicPruningConfigBuilder
        extends PruningConfigBuilder
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
      override val pruningMode: PruningMode = BasicPruning(history = 1000)

    val sourceStorages: EphemDataSourceComponent & BasicPruningConfigBuilder & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with BasicPruningConfigBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider

    val targetStorages = getNewStorages
    val sourceWriter: BlockchainWriter = BlockchainWriter(sourceStorages.storages)
    val targetWriter: BlockchainWriter = BlockchainWriter(targetStorages.storages)
    val sourceReader: BlockchainReader = BlockchainReader(sourceStorages.storages)
    val targetReader: BlockchainReader = BlockchainReader(targetStorages.storages)

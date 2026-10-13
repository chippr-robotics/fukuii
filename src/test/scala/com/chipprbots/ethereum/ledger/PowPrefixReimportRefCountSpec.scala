package com.chipprbots.ethereum.ledger

import java.nio.file.Files

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.DataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.ReferenceCountNodeStorage
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1592: a PoW batch that RE-PRESENTS our own canonical blocks, against a real RocksDB under basic pruning with the
  * production history of 64.
  *
  * After an `UnknownBranch` rewind the regular-sync fetcher re-serves from below the head, so the importer hands
  * consensus canonical N-k..N followed by the head's extension. On PoW `BranchResolution` answers
  * `NewBetterBranch(Nil)` and the whole batch reaches `ConsensusImpl.reorganise`, whose `oldCanonical` is exactly the
  * re-presented prefix. The prefix re-executes harmlessly (its second state commit is dropped, #1481) — but
  * `settleHead` then abandoned `oldCanonical` wholesale, undoing the reference counts of blocks that are still
  * canonical. Their nodes reached zero, sat on their death rows, and were deleted 64 blocks later: a later import read
  * a MissingNode (Mordor, 17178965).
  *
  * The batch is driven straight into `ConsensusImpl.evaluateBranch`, exactly as `BlockImporter` hands it over on a PoW
  * chain, so these cases pin the settle-side guarantee whatever the importer trims.
  */
class PowPrefixReimportRefCountSpec extends AnyFlatSpec with Matchers:

  private val history = 64
  private val keyCount = 40

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String): ByteString = ByteString(s"$tag-padding-to-force-a-hashed-leaf-node-xxxxxxxx")

  trait RocksDataSourceComponent extends DataSourceComponent:
    private val dbPath: String = Files.createTempDirectory("pow-prefix-reimport-rocksdb").toAbsolutePath.toString
    override lazy val dataSource: RocksDbDataSource = RocksDbDataSource(
      new RocksDbConfig:
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      ,
      Namespaces.nsSeq
    )

  class Setup extends EphemBlockchainTestSetup:
    trait BasicPruningBuilder extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
      override val pruningMode: PruningMode = BasicPruning(history)

    val comp: RocksDataSourceComponent & BasicPruningBuilder & Storages.DefaultStorages =
      new RocksDataSourceComponent
        with BasicPruningBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider
    val bs = comp.storages
    val reader: BlockchainReader = BlockchainReader(bs)
    val writer: BlockchainWriter = BlockchainWriter(bs)

    val roots: mutable.Map[ByteString, ByteString] = mutable.Map.empty
    val plans: mutable.Map[ByteString, Seq[(Int, String)]] = mutable.Map.empty
    var failing: Set[ByteString] = Set.empty

    class SimExecution(chain: BlockchainImpl)
        extends BlockExecution(chain, reader, writer, bs.evmCodeStorage, null, null):
      override protected[ledger] def executeAndValidateStaged(
          block: Block,
          alreadyValidated: Boolean,
          staged: StagedBlockState
      )(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
        if failing.contains(block.hash.value) then Left(ValidationAfterExecError("simulated failure"))
        else
          var root = roots(block.header.parentHash.value)
          plans(block.hash.value).foreach { case (slot, tag) =>
            // one transaction: persist the trie, straight into the (staged) storage
            root = ByteString(
              MerklePatriciaTrie[ByteString, ByteString](root.toArray, staged.storage)
                .put(key(slot), value(tag))
                .getRootHash
            )
          }
          roots(block.hash.value) = root
          Right((Nil, Nil, None))

    val chain: BlockchainImpl = new BlockchainImpl(
      bs.blockHeadersStorage,
      bs.blockBodiesStorage,
      bs.blockNumberMappingStorage,
      bs.receiptStorage,
      bs.chainWeightStorage,
      bs.transactionMappingStorage,
      bs.appStateStorage,
      new ReferenceCountedStateStorage(bs.nodeStorage, history),
      reader
    )
    val powConsensus = new ConsensusImpl(reader, writer, new SimExecution(chain), reorgState = chain)

    def importBatch(blocks: List[Block]): ConsensusResult =
      powConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    def importEach(blocks: List[Block]): Unit = blocks.foreach { b =>
      importBatch(List(b)) shouldBe a[ExtendedCurrentBestBranch]
    }

    def applied(block: Block): Boolean = ReferenceCountNodeStorage.isBlockApplied(block.hash.value, bs.nodeStorage)

    def initGenesis(): Unit =
      val storage = bs.stateStorage.getBackingStorage(0)
      val trie = (0 until keyCount).foldLeft(MerklePatriciaTrie[ByteString, ByteString](storage)) { (t, i) =>
        t.put(key(i), value(s"g$i"))
      }
      roots(BlockHelpers.genesis.hash.value) = ByteString(trie.getRootHash)
      writer.save(
        BlockHelpers.genesis,
        Nil,
        ChainWeight.zero.increase(BlockHelpers.genesis.header),
        saveAsBestBlock = true
      )

    def readWholeState(root: ByteString): Map[Int, Option[ByteString]] =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, bs.stateStorage.getReadOnlyStorage)
      (0 until keyCount).map(i => i -> trie.get(key(i))).toMap

    // Canonical 1..10 imported one at a time (block n writes slot n), plus block 11 ready to extend the head.
    initGenesis()
    val canonical: List[Block] = BlockHelpers.generateChain(11, BlockHelpers.genesis)
    canonical.foreach(b => plans(b.hash.value) = Seq(b.number.value.toInt -> s"a${b.number}"))
    val head: Block = canonical(9) // block 10
    val extension: Block = canonical(10) // block 11
    importEach(canonical.take(10))
    reader.getBestBlockNumber shouldBe BigInt(10)

    /** The re-presented canonical prefix 7..10: what a rewound fetcher re-serves below the head. */
    val prefix: List[Block] = canonical.slice(6, 10)

    /** A quiet tail on top of `parent`, long enough that the prune cursor passes every height above (target = bn - 64,
      * so dr<10> goes at block 74). It writes only slots 20..34, never the ones the cases assert on.
      */
    def extendPastPruning(parent: Block): Block =
      val tail = BlockHelpers.generateChain(80 - parent.number.value.toInt, parent)
      tail.foreach(b => plans(b.hash.value) = Seq((20 + b.number.value.toInt % 15) -> s"q${b.number}"))
      importEach(tail)
      reader.getBestBlockNumber shouldBe BigInt(80)
      tail.last

  "A PoW batch re-presenting the canonical prefix 7..10 and extending the head with 11" should
    "leave the prefix's undo records applied" taggedAs (UnitTest, DatabaseTest, ConsensusTest) in new Setup:
      importBatch(prefix :+ extension) shouldBe a[SelectedNewBestBranch]
      reader.getBestBlockNumber shouldBe BigInt(11)

      // Unfixed: settleHead abandoned oldCanonical = 7..10, i.e. these very blocks, and every one reads false.
      prefix.foreach(b => withClue(s"block ${b.number}: ")(applied(b) shouldBe true))
      applied(extension) shouldBe true

  it should "keep every node of the head state once pruning has passed the prefix" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in new Setup:
    importBatch(prefix :+ extension) shouldBe a[SelectedNewBestBranch]
    val tip = extendPastPruning(extension)

    // Unfixed: the leaves the prefix wrote went to zero references on dr<7..10> and were deleted at blocks 71..74.
    val state = readWholeState(roots(tip.hash.value))
    (1 to 11).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"a$i"))))

  "A PoW batch re-presenting the prefix whose extension FAILS (REORG-PARTIAL)" should
    "leave the re-executed prefix applied and the head state whole" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in new Setup:
      // The executed 7..10 only match the head's weight, so the old head is kept and the index restored. Unfixed, that
      // arm abandoned every executed block as a side block, the canonical 7..10 among them.
      failing = Set(extension.hash.value)
      importBatch(prefix :+ extension) shouldBe a[BranchExecutionFailure]
      reader.getBestBlockNumber shouldBe BigInt(10)
      reader.getBestBlock.map(_.hash) shouldBe Some(head.hash)
      prefix.foreach(b => withClue(s"block ${b.number}: ")(applied(b) shouldBe true))

      failing = Set.empty
      importEach(List(extension))
      val tip = extendPastPruning(extension)
      val state = readWholeState(roots(tip.hash.value))
      (1 to 11).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"a$i"))))

  "A PoW batch carrying the canonical prefix 7..9 and then a heavier sibling of the head" should
    "still undo the block it really displaces, and only that one" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in new Setup:
      // Control: the filter keeps re-presented blocks, never a displaced one. b10 replaces block 10 (which wrote slot
      // 10) and writes slot 35 instead, so slot 10 must read its genesis value again on the new chain.
      val heavier: Block => Block = b => b.copy(header = b.header.copy(difficulty = Difficulty(BigInt(10).pow(12))))
      val b10 = BlockHelpers.generateChain(1, canonical(8), heavier).head
      plans(b10.hash.value) = Seq(35 -> "b10")
      importBatch(canonical.slice(6, 9) :+ b10) shouldBe a[SelectedNewBestBranch]
      reader.getBestBlock.map(_.hash) shouldBe Some(b10.hash)

      canonical.slice(6, 9).foreach(b => withClue(s"block ${b.number}: ")(applied(b) shouldBe true))
      applied(head) shouldBe false
      applied(b10) shouldBe true

      val tip = extendPastPruning(b10)
      val state = readWholeState(roots(tip.hash.value))
      (1 to 9).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"a$i"))))
      state(10) shouldBe Some(value("g10"))
      state(35) shouldBe Some(value("b10"))

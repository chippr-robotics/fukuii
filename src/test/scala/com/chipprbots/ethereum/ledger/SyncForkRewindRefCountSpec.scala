package com.chipprbots.ethereum.ledger

import java.nio.file.Files

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporterRewindAccess
import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.DataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The SYNC-FORK rewind (`BlockImporter.handleForkRecovery` -> `setCanonicalChainHead`) against a REAL RocksDB with
  * basic (reference-counted) pruning.
  *
  * The rewind deletes the canonical index entries of the blocks it drops but, on its own, leaves their applied
  * reference-count records in place. A node only a dropped block replaced is then at zero references on dr<h>, is
  * pruned `history` blocks later, and the replacement branch (which still uses it) reads a MissingNode. The rewind now
  * undoes those blocks through the `ReorgStateHandler`; the NoOp control pins that the bug is real in this setup.
  */
class SyncForkRewindRefCountSpec extends AnyFlatSpec with Matchers:

  private val history = 2
  private val keyCount = 40

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String): ByteString = ByteString(s"$tag-padding-to-force-a-hashed-leaf-node-xxxxxxxx")

  trait RocksDataSourceComponent extends DataSourceComponent:
    private val dbPath: String = Files.createTempDirectory("sync-fork-rewind-rocksdb").toAbsolutePath.toString
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

    class SimExecution(chain: BlockchainImpl)
        extends BlockExecution(chain, reader, writer, bs.evmCodeStorage, null, null):
      override protected[ledger] def executeAndValidateStaged(
          block: Block,
          alreadyValidated: Boolean,
          staged: StagedBlockState
      )(implicit
          blockchainConfig: BlockchainConfig
      ): Either[BlockExecutionError, (Seq[Receipt], Seq[ByteString], Option[BlockAccessList])] =
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
    val reorgConsensus = new ConsensusImpl(reader, writer, new SimExecution(chain), reorgState = chain)

    def importBatch(blocks: List[Block]): ConsensusResult =
      reorgConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    def importEach(blocks: List[Block]): Unit = blocks.foreach { b =>
      importBatch(List(b)) shouldBe a[ExtendedCurrentBestBranch]
    }

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

    /** a1 a2 imported; the SYNC-FORK rewind to a1 with `handler`; b2 (a sibling of a2) and a quiet tail executed on the
      * rewound chain, well past the point where dr2 is pruned. Returns the state root at the tail.
      */
    def rewindAndReplace(handler: ReorgStateHandler): ByteString =
      initGenesis()
      val Seq(a1, a2) = BlockHelpers.generateChain(2, BlockHelpers.genesis)
      // a2 replaces the leaf of slot 3; b2 (same parent) does not touch it, so slot 3's old leaf is live in b2's state
      plans(a1.hash.value) = Seq(1 -> "a1")
      plans(a2.hash.value) = Seq(3 -> "aOnly")
      importEach(List(a1, a2))
      reader.getBestBlockNumber shouldBe BigInt(2)

      BlockImporterRewindAccess.rewind(reader, writer, handler, 1, a1.hash, 2)
      reader.getBestBlockNumber shouldBe BigInt(1)
      reader.getCanonicalHashByNumber(2) shouldBe None

      val b2 = BlockHelpers
        .generateChain(1, a1, b => b.copy(header = b.header.copy(difficulty = Difficulty(BigInt(10).pow(12)))))
        .head
      plans(b2.hash.value) = Seq(7 -> "bOnly")
      importEach(List(b2))
      val tail = BlockHelpers.generateChain(5, b2)
      tail.foreach(b => plans(b.hash.value) = Seq((20 + b.number.value.toInt) -> s"q${b.number}"))
      importEach(tail)
      roots(tail.last.hash.value)

  "The SYNC-FORK rewind on RocksDB under basic pruning" should
    "keep the node only the dropped block replaced, when the rewind undoes it" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in new Setup:
      val tipRoot = rewindAndReplace(chain)
      val finalState = readWholeState(tipRoot)
      finalState(3) shouldBe Some(value("g3"))
      finalState(7) shouldBe Some(value("bOnly"))
      finalState(1) shouldBe Some(value("a1"))

  it should "lose that node when nothing undoes the dropped block (NoOp control: the bug this fixes)" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in new Setup:
    val tipRoot = rewindAndReplace(ReorgStateHandler.NoOp)
    a[MissingNodeException] should be thrownBy readWholeState(tipRoot)

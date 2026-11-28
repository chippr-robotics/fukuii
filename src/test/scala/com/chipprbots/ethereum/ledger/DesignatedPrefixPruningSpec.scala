package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.collection.mutable

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.Consensus.*
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
import com.chipprbots.ethereum.consensus.engine.InvalidChainReporter
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
import com.chipprbots.ethereum.ledger.BlockExecutionError.MPTError
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TempDirs
import com.chipprbots.ethereum.utils.BlockchainConfig

/** B-511 (b): the consensus layer's branch on the hash layout, under basic pruning with the production history of 64,
  * when what of it was executed is kept beside the head (B-505) and a branch is built on it later.
  *
  * A prefix kept beside the head has its reference-count changes taken out (`settleHead` abandons its blocks), so the
  * nodes only it wrote sit on the death rows of its own heights. Within the history window they are still there: a
  * branch built on the prefix puts the counts back (`readoptBlockStates`) and the head's state is whole after pruning
  * has passed it. Past the window they are gone, and so is the state of the block the prefix forked from, which the
  * canonical chain has rewritten since: a reorganisation that deep is past what basic pruning keeps, whatever was kept
  * beside the head. The branch is then answered as a missing node, with nothing moved and nothing judged, which the
  * importer fetches (`BlockImporter`, `BlockImportFailedDueToMissingNode`), as for any reorganisation past the window.
  *
  * Executed with a stand-in for the VM that writes trie keys (as [[PowPrefixReimportRefCountSpec]]), against a real
  * RocksDB, through `ConsensusImpl.evaluateBranch` as `BlockImporter` hands a branch over.
  */
class DesignatedPrefixPruningSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val tempDirs = new TempDirs
  override protected def afterAll(): Unit = tempDirs.deleteAll()
  private def tracked(ds: RocksDbDataSource): RocksDbDataSource =
    tempDirs.closeBeforeDelete(() => ds.close())
    ds

  private val history = 64
  private val keyCount = 40

  private def key(i: Int): ByteString = kec256(ByteString(s"k$i"))
  private def value(tag: String): ByteString = ByteString(s"$tag-padding-to-force-a-hashed-leaf-node-xxxxxxxx")

  /** Post-merge: every block of difficulty 0, so no branch outweighs the head and the consensus layer's head decides.
    */
  private val merged: Block => Block = b => b.copy(header = b.header.copy(difficulty = Difficulty(BigInt(0))))

  trait RocksDataSourceComponent extends DataSourceComponent:
    private val dbPath: String = tempDirs.create("designated-prefix-pruning-rocksdb").toAbsolutePath.toString
    override lazy val dataSource: RocksDbDataSource = tracked(
      RocksDbDataSource(
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
    bs.appStateStorage.markRefCountsExact().commit()
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
          // a trie read that fails is the block's MPTError, as `BlockExecution.executeBlock` answers one
          try
            var root = roots(block.header.parentHash.value)
            plans(block.hash.value).foreach { case (slot, tag) =>
              root = ByteString(
                MerklePatriciaTrie[ByteString, ByteString](root.toArray, staged.storage)
                  .put(key(slot), value(tag))
                  .getRootHash
              )
            }
            roots(block.hash.value) = root
            Right((Nil, Nil, None))
          catch case e: MPTException => Left(MPTError(e))

    val chain: BlockchainImpl = new BlockchainImpl(
      bs.blockHeadersStorage,
      bs.blockBodiesStorage,
      bs.blockNumberMappingStorage,
      bs.receiptStorage,
      bs.chainWeightStorage,
      bs.transactionMappingStorage,
      bs.appStateStorage,
      new ReferenceCountedStateStorage(bs.nodeStorage, history, nodesMayBeDeleted = () => true),
      reader
    )

    @volatile var named: Option[ByteString] = None
    var reported: List[(ByteString, ByteString)] = Nil
    val designatedConsensus = new ConsensusImpl(
      reader,
      writer,
      new SimExecution(chain),
      Some(
        new InvalidChainReporter:
          override def reportInvalid(blockHash: ByteString, latestValidHash: ByteString): Unit =
            reported :+= (blockHash -> latestValidHash)
      ),
      Some(DesignatedHead(() => named)),
      chain
    )

    def evaluate(blocks: List[Block]): ConsensusResult =
      designatedConsensus.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    def importEach(blocks: List[Block]): Unit = blocks.foreach { b =>
      evaluate(List(b)) shouldBe a[ExtendedCurrentBestBranch]
    }

    def applied(block: Block): Boolean = ReferenceCountNodeStorage.isBlockApplied(block.hash.value, bs.nodeStorage)

    def readWholeState(root: ByteString): Map[Int, Option[ByteString]] =
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, bs.stateStorage.getReadOnlyStorage)
      (0 until keyCount).map(i => i -> trie.get(key(i))).toMap

    // the genesis state: every slot written once
    locally {
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
    }

    // Canonical 1..10 (block n writes slot n). The consensus layer's branch leaves it after block 8: s9..s11 write
    // slots 36..38, which no other block writes, and s12 after them is invalid.
    val canonical: List[Block] = BlockHelpers.generateChain(10, BlockHelpers.genesis, merged)
    canonical.foreach(b => plans(b.hash.value) = Seq(b.number.value.toInt -> s"a${b.number}"))
    importEach(canonical)
    val head: Block = canonical.last
    val prefix: List[Block] = BlockHelpers.generateChain(3, canonical(7), merged)
    prefix.foreach(b => plans(b.hash.value) = Seq((27 + b.number.value.toInt) -> s"s${b.number}"))
    val invalid: Block = BlockHelpers.generateChain(1, prefix.last, merged).head
    plans(invalid.hash.value) = Nil
    failing = Set(invalid.hash.value)
    // a valid block on the kept prefix, which the consensus layer names later
    val onThePrefix: Block = BlockHelpers.generateChain(1, prefix.last, merged).head
    plans(onThePrefix.hash.value) = Seq(39 -> "t12")

    /** The prefix executed, and kept beside the head because the block after it is invalid (B-505). */
    def keepThePrefixBeside(): Unit =
      named = Some(invalid.hash.value)
      evaluate(prefix :+ invalid) match
        case BranchExecutionFailure(_, failingHash, _) => failingHash shouldBe invalid.hash.value
        case other                                     => fail(s"not a failure of the branch: $other")
      reader.getBestBlock.map(_.hash) shouldBe Some(head.hash)
      prefix.foreach(b => withClue(s"block ${b.number}: ")(applied(b) shouldBe false))
      reported = Nil

    /** A quiet tail on `parent` up to block 80, past the prune of every height above 16 (dr<n> goes at block n + 64).
      * It writes slots 20..26 only.
      */
    def extendPastPruning(parent: Block): Block =
      val tail = BlockHelpers.generateChain(80 - parent.number.value.toInt, parent, merged)
      tail.foreach(b => plans(b.hash.value) = Seq((20 + b.number.value.toInt % 7) -> s"q${b.number}"))
      importEach(tail)
      reader.getBestBlockNumber shouldBe BigInt(80)
      tail.last

  "A branch built on the consensus layer's prefix kept beside the head (the hash layout, basic pruning)" should
    "put the prefix's reference counts back when it is adopted within the history window, and keep its state whole" taggedAs (
      UnitTest,
      DatabaseTest,
      ConsensusTest
    ) in (new Setup:
      keepThePrefixBeside()
      named = Some(onThePrefix.hash.value)
      evaluate(List(onThePrefix)) match
        case SelectedNewBestBranch(oldBranch, newBranch, _, _) =>
          oldBranch.map(_.hash) shouldBe canonical.drop(8).map(_.hash)
          newBranch.map(_.hash) shouldBe List(onThePrefix.hash)
        case other => fail(s"not adopted: $other")
      reader.getBestBlock.map(_.hash) shouldBe Some(onThePrefix.hash)
      (9 to 11).foreach(n => reader.getBlockHeaderByNumber(n).map(_.hash) shouldBe Some(prefix(n - 9).hash))
      prefix.foreach(b => withClue(s"block ${b.number}: ")(applied(b) shouldBe true))
      canonical.drop(8).foreach(b => withClue(s"block ${b.number}: ")(applied(b) shouldBe false))

      // pruning passes every height of the prefix: the nodes only it wrote are still referenced by the head's state
      val tip: Block = extendPastPruning(onThePrefix)
      val state: Map[Int, Option[ByteString]] = readWholeState(roots(tip.hash.value))
      (1 to 8).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"a$i"))))
      (9 to 10).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"g$i"))))
      (36 to 38).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"s${i - 27}"))))
      state(39) shouldBe Some(value("t12"))
      reported shouldBe Nil
    )

  it should "be answered as a missing node past the history window, with nothing moved and no block judged" taggedAs (
    UnitTest,
    DatabaseTest,
    ConsensusTest
  ) in (new Setup:
    keepThePrefixBeside()
    val tip: Block = extendPastPruning(head)
    named = Some(onThePrefix.hash.value)
    evaluate(List(onThePrefix)) match
      // the importer fetches the node (BlockImportFailedDueToMissingNode) and tries the branch again: nothing is
      // queued here as imported or as left, and the block is not reported invalid
      case ConsensusErrorDueToMissingNode(toEnqueue, reason, imported) =>
        reason shouldBe a[MissingNodeException]
        toEnqueue shouldBe Nil
        imported shouldBe Nil
      case other => fail(s"not a missing node: $other")
    reported shouldBe Nil
    reader.getBestBlock.map(_.hash) shouldBe Some(tip.hash)
    (9 to 10).foreach(n => reader.getBlockHeaderByNumber(n).map(_.hash) shouldBe Some(canonical(n - 1).hash))
    applied(onThePrefix) shouldBe false
    // and the head's state is whole: keeping the prefix beside it took nothing of it
    val state: Map[Int, Option[ByteString]] = readWholeState(roots(tip.hash.value))
    (1 to 10).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"a$i"))))
    (36 to 38).foreach(i => withClue(s"slot $i: ")(state(i) shouldBe Some(value(s"g$i"))))
  )

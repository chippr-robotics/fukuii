package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** core-geth parity for BLOCKHASH and for the canonical index across reorganisations (ETC).
  *
  * core-geth resolves BLOCKHASH with `GetHashFn` (core/evm.go), which walks `ParentHash` back from the executing block
  * and never consults the canonical number→hash index. Its `writeBlockAndSetHead` moves the head only when
  * `forker.ReorgNeeded` says the new block is heavier, and `reorg()` keeps the canonical index exactly the head's
  * ancestry — rewriting it down to the common ancestor and deleting every entry above the new head.
  *
  * Every scenario here drives the REAL import path: `ConsensusAdapter` -> `ConsensusImpl` -> `BlockExecution` with the
  * real VM and the real after-execution check of gasUsed and stateRoot (`StdValidators.validateBlockAfterExecution`).
  * Only pre-execution header rules (PoW, difficulty, gas-limit bounds, ommers, receipts root) are stubbed, so that the
  * test can choose difficulties freely.
  *
  * Blocks are sealed by an independent ORACLE node whose canonical index holds exactly one chain, so on the oracle the
  * index lookup and the ancestry walk give the same answer — the core-geth answer. The node under test then has to
  * reproduce the oracle's state roots byte for byte.
  */
class ReorgBlockhashParitySpec extends AnyFlatSpec with Matchers with ScalaFutures with NormalPatience:
  import ReorgBlockhashParitySpec.*

  // The reorganisation on the hash layout, and on the unified state layout, where a branch is executed on pinned layers
  // and adopted by one move of the head (spec 018 C4e): the same answers, block for block.
  // And on the unified layout with a branch that holds more than the pinned layers may: the head is moved first and the
  // blocks are committed in place (C4e part 3), wholly (`1`: no block is kept under the pin) or from where the cap is
  // met (`MidCap`: the first blocks of a branch are kept, the rest are not).
  for (layout, guard, cap) <- Seq(
      ("the hash layout", false, None),
      ("the unified layout", true, None),
      ("the unified layout, nothing kept under the pin", true, Some(1L)),
      ("the unified layout, the cap met within the branch", true, Some(MidCap))
    )
  do
    (s"A TD-winning reorg that fails mid-branch ($layout)" should
      "keep the heavier old head and index, so the next canonical block's BLOCKHASH matches core-geth").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard, cap):
      // Canonical h1..h15 at difficulty 1000. Side branch s6..s13 forks after h5: s6..s12 at 1000, s13 at 5000 and
      // consensus-invalid (tampered stateRoot). Whole branch: 12000 > 10000 for h6..h15, so it is selected and executed;
      // the executed prefix s6..s12 is 7000 < 10000, so core-geth's head never leaves h15.
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 15, difficulty = 1000)
      val h5: Block = oldChain(4)
      val h15: Block = oldChain(14)
      val h16: Block = oracle.extendCanonical(h15, 1, difficulty = 1000, readBlockhashOf = Some(8)).head
      val sidePrefix: List[Block] = oracle.extendSide(h5, 7, difficulty = 1000, tag = 0x51)
      val s13Invalid: Block = oracle.invalidChild(sidePrefix.last, difficulty = 5000)

      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe h15.hash

      // Branch selected by weight, prefix executed, s13 rejected on its state root.
      node.importAll(sidePrefix :+ s13Invalid) shouldBe a[BlockImportFailed]

      // core-geth: head stays h15 (TD(s12) < TD(h15)) and the canonical index is still h's chain.
      node.bestHash shouldBe h15.hash
      (6 to 12).foreach(n => node.indexed(n) shouldBe Some(oldChain(n - 1).hash))

      // The next canonical block arrives by NewBlock. It stores BLOCKHASH(8) at slot 16; the oracle sealed it with
      // h8's hash. A side-branch hash here is a state-root mismatch and a valid canonical block rejected.
      node.importOne(h16) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe h16.hash
      recorded(h16, slot = 16, oldChain(7).hash.value)
    )

    it should "move the head to the executed prefix when it is heavier, and drop the old index above it" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Fixture(guard, cap):
      // Canonical h1..h15 at 1000. Side s6..s8 at 5000 each (15000 > 10000 for h6..h15), s9 invalid.
      val oldChain: List[Block] = oracle.extendCanonical(oracle.genesis, 15, difficulty = 1000)
      val sidePrefix: List[Block] = oracle.extendSide(oldChain(4), 3, difficulty = 5000, tag = 0x52)
      val s9Invalid: Block = oracle.invalidChild(sidePrefix.last, difficulty = 5000)

      node.importAll(oldChain) shouldBe a[BlockImportedToTop]
      node.importAll(sidePrefix :+ s9Invalid) shouldBe a[BlockImportFailed]

      // core-geth: writeBlockAndSetHead reorgs as soon as a side block outweighs the head, so the head is s8 and reorg()
      // has deleted the canonical hashes of h9..h15.
      node.bestHash shouldBe sidePrefix.last.hash
      (6 to 8).foreach(n => node.indexed(n) shouldBe Some(sidePrefix(n - 6).hash))
      (9 to 15).foreach(n => node.indexed(n) shouldBe None)
      (1 to 5).foreach(n => node.indexed(n) shouldBe Some(oldChain(n - 1).hash))

    (s"A reorg back onto a previously orphaned block ($layout)" should
      "rewrite the canonical index below the branch parent, so BLOCKHASH and the old branch match core-geth").taggedAs(
      UnitTest,
      ConsensusTest
    ) in (new Fixture(guard, cap):
      // The ordinary ETC uncle race, across a restart. Canonical a1..a10. b10, a heavier sibling of a10, arrives and
      // wins. The node restarts, losing the in-memory block queue that held the orphaned a10. Then a11 (child of a10)
      // arrives by NewBlock and outweighs b10. a10 is on disk with a chain weight, so the queue roots a11 directly on
      // it and ConsensusImpl reorganises with the one-block branch [a11]. core-geth's reorg() walks a11's ancestry
      // back to the common ancestor a9 and rewrites height 10 to a10.
      //
      // a11 and a12 both store BLOCKHASH(10). a11 is EXECUTED while the index still names b10 at height 10 (the index is
      // only settled after execution), so it needs the ancestry walk; a12 executes after the index is settled.
      val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 10, difficulty = 1000)
      val a9: Block = aChain(8)
      val a10: Block = aChain(9)
      val a11: Block = oracle.extendCanonical(a10, 1, difficulty = 1000, readBlockhashOf = Some(10)).head
      val a12: Block = oracle.extendCanonical(a11, 1, difficulty = 1000, readBlockhashOf = Some(10)).head
      val b10: Block = oracle.extendSide(a9, 1, difficulty = 1500, tag = 0x53).head

      node.importAll(aChain) shouldBe a[BlockImportedToTop]
      node.importOne(b10) shouldBe a[ChainReorganised]
      node.bestHash shouldBe b10.hash
      node.blockQueue.clear() // restart: the queue is in memory only

      node.importOne(a11) match
        case ChainReorganised(oldBranch, newBranch, _) =>
          // Exactly the block that left the canonical chain — not the whole chain down to genesis.
          oldBranch.map(_.hash) shouldBe List(b10.hash)
          newBranch.map(_.hash) shouldBe List(a11.hash)
        case other => fail(s"expected ChainReorganised, got $other")
      node.bestHash shouldBe a11.hash
      node.indexed(10) shouldBe Some(a10.hash)
      recorded(a11, slot = 11, a10.hash.value)

      node.importOne(a12) shouldBe a[BlockImportedToTop]
      node.bestHash shouldBe a12.hash
      recorded(a12, slot = 12, a10.hash.value)
    )

  "Chain-file import (hive consensus simulator path)" should
    "apply TD fork choice instead of making the last block in the file the head" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new Fixture:
      // ethereum/tests lotsOfLeafs / sideChainWithMoreTransactions / uncleBlockAtBlock3afterBlock4 shape: the file
      // lists the canonical chain, then a lighter side block, then (second file) a heavier side branch. core-geth's
      // `import` (InsertChain -> writeBlockAndSetHead) keeps the heaviest head; ChainImporter used to save every
      // block as best, so the file's last block won whatever its TD.
      val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 4, difficulty = 1000)
      val lighterSide: List[Block] = oracle.extendSide(aChain(1), 1, difficulty = 500, tag = 0x61) // b3 < a3
      val heavierSide: List[Block] = oracle.extendSide(aChain(1), 3, difficulty = 1000, tag = 0x62) // c3..c5 > a3..a4

      node.importFile(aChain ++ lighterSide)
      node.bestHash shouldBe aChain.last.hash
      node.indexed(3) shouldBe Some(aChain(2).hash)

      node.importFile(heavierSide)
      node.bestHash shouldBe heavierSide.last.hash
      (3 to 5).foreach(n => node.indexed(n) shouldBe Some(heavierSide(n - 3).hash))
    )

  it should "make the last valid block the head once the terminal total difficulty is reached (core-geth)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new PostMergeFixture:
    // ethereum/tests bcMultiChainTest/UncleFromSideChain_{Cancun,Prague} shape. Post-merge every header has
    // difficulty 0, so two chains always weigh the same and a TD rule can never choose. core-geth's
    // ForkChoice.ReorgNeeded returns true for any block at or past the TTD: on import, each valid block becomes the
    // head. The TD fork choice above kept the FIRST chain in the file instead (hive consensus 1143/0 -> 1143/2).
    val aChain: List[Block] = oracle.extendCanonical(oracle.genesis, 3, difficulty = 0)
    val bChain: List[Block] = oracle.extendSide(oracle.genesis, 3, difficulty = 0, tag = 0x63)

    node.importFile(aChain ++ bChain)
    node.bestHash shouldBe bChain.last.hash
    (1 to 3).foreach(n => node.indexed(n) shouldBe Some(bChain(n - 1).hash))

  class Fixture(guard: Boolean = false, cap: Option[Long] = None):
    val oracle = new Oracle
    val node = new Node(guard, cap)

    /** The block hash `block`'s execution recorded at `slot`, on the node under test: read from its state on the hash
      * layout; on the unified layout the recorder's storage is the oracle's, root for root (the state root the block
      * was accepted with commits to it either way).
      */
    def recorded(block: Block, slot: BigInt, expected: ByteString): Unit =
      if !guard then node.storedBlockhash(block, slot) shouldBe expected
      else
        node.blockchainReader.accountAt(block.header, BlockhashRecorder).map(_.storageRoot) shouldBe
          oracle.blockchainReader.accountAt(block.header, BlockhashRecorder).map(_.storageRoot)

  /** Both hosts on a chain whose terminal total difficulty is 0, as hive configures post-merge fixtures. */
  class PostMergeFixture:
    val oracle: Oracle = new Oracle:
      implicit override lazy val blockchainConfig: BlockchainConfig =
        super.blockchainConfig.copy(terminalTotalDifficulty = Some(BigInt(0)))
    val node: Node = new Node:
      implicit override lazy val blockchainConfig: BlockchainConfig =
        super.blockchainConfig.copy(terminalTotalDifficulty = Some(BigInt(0)))

  /** Seals blocks. Canonical blocks are indexed (so its BLOCKHASH answers are the single-chain, core-geth answers);
    * side blocks are stored by hash only and carry no transactions.
    */
  class Oracle extends ChainHost:
    private var senderNonce: BigInt = 0

    def extendCanonical(
        parent: Block,
        count: Int,
        difficulty: BigInt,
        readBlockhashOf: Option[BigInt] = None
    ): List[Block] =
      (1 to count).toList.foldLeft(List.empty[Block]) { (acc, _) =>
        val p = acc.lastOption.getOrElse(parent)
        val txs = readBlockhashOf.toList.map(target => blockhashCall(target))
        val made = seal(p, txs, difficulty, tag = 0x00)
        blockchainWriter.storeBlock(made).commit()
        acc :+ made
      }

    def extendSide(parent: Block, count: Int, difficulty: BigInt, tag: Int): List[Block] =
      (1 to count).toList.foldLeft(List.empty[Block]) { (acc, _) =>
        val made = seal(acc.lastOption.getOrElse(parent), Nil, difficulty, tag)
        blockchainWriter.storeBlockByHashOnly(made).commit()
        acc :+ made
      }

    /** A correctly-linked child whose header commits to a state root no execution produces. */
    def invalidChild(parent: Block, difficulty: BigInt): Block =
      val valid = seal(parent, Nil, difficulty, tag = 0x7f)
      valid.copy(header = valid.header.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x11)))))

    private def blockhashCall(target: BigInt): SignedTransaction =
      val tx = LegacyTransaction(
        nonce = senderNonce,
        gasPrice = GasPrice(1),
        gasLimit = GasAmount(100000),
        receivingAddress = BlockhashRecorder,
        value = 0,
        payload = UInt256(target).bytes
      )
      senderNonce += 1
      SignedTransaction.sign(tx, SenderKeyPair, None)

    private def seal(parent: Block, txs: Seq[SignedTransaction], difficulty: BigInt, tag: Int): Block =
      val template = parent.header.copy(
        parentHash = parent.hash,
        number = parent.number + 1,
        difficulty = Difficulty(difficulty),
        unixTimestamp = Timestamp(parent.header.unixTimestamp.toLong + 15),
        extraData = ByteString(Array[Byte](tag.toByte)),
        beneficiary = Miner.bytes,
        gasLimit = GasAmount(1000000),
        gasUsed = GasAmount.Zero
      )
      val block = Block(template, BlockBody(txs, Nil))
      blockExecution.executeBlockNoValidation(block) match
        case Right((_, gasUsed, stateRoot)) =>
          block.copy(header = template.copy(stateRoot = TrieRoot(stateRoot), gasUsed = GasAmount(gasUsed)))
        case Left(error) => throw new IllegalStateException(s"oracle could not seal block ${template.number}: $error")

  /** The node under test. */
  class Node(guard: Boolean = false, cap: Option[Long] = None) extends ChainHost(guard, cap):
    def importAll(blocks: List[Block]): BlockImportResult =
      consensusAdapter.evaluateBranch(NonEmptyList.fromListUnsafe(blocks)).unsafeRunSync()

    def importFile(blocks: List[Block]): (Int, Int, Int) =
      val file = java.nio.file.Files.createTempFile("chain", ".rlp")
      try
        java.nio.file.Files.write(file, blocks.flatMap(b => Block.BlockEnc(b).toBytes.toSeq).toArray)
        chainImporter.importChainFile(file.toString)
      finally java.nio.file.Files.delete(file)

    def importOne(block: Block): BlockImportResult =
      consensusAdapter.evaluateBranchBlock(block).unsafeRunSync()

    def bestHash: BlockHash = BlockHash(storagesInstance.storages.appStateStorage.getBestBlockInfo().hash)

    def indexed(number: Int): Option[BlockHash] = blockchainReader.getBlockHeaderByNumber(number).map(_.hash)

    def storedBlockhash(block: Block, slot: BigInt): ByteString =
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(block.number.value),
        (_: BigInt) => None,
        UInt256.Zero,
        block.header.stateRoot.value,
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      UInt256(world.getStorage(BlockhashRecorder).load(slot)).bytes

  /** One ephemeral database with the shared genesis state installed. Real VM, real BlockExecution, real ConsensusImpl.
    */
  abstract class ChainHost(guard: Boolean = false, cap: Option[Long] = None) extends EphemBlockchainTestSetup:
    implicit val runtime: IORuntime = IORuntime.global

    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with LocalPruningConfigBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard
        override protected def stateConfig: StateConfig =
          val config = StateConfig(com.chipprbots.ethereum.utils.Config.config)
          cap.fold(config)(bytes => config.copy(overlay = config.overlay.copy(pinnedMaxBytes = bytes)))

    // one genesis for every host, loaded as the node loads one: as rows on the unified layout
    val genesis: Block =
      GenesisDataLoader
        .over(blockchainReader, blockchainWriter, storagesInstance.storages)
        .loadGenesisData(
          GenesisData(
            nonce = ByteString(new Array[Byte](8)),
            mixHash = None,
            difficulty = "0x3e8",
            extraData = ByteString.empty,
            gasLimit = "0xf4240",
            coinbase = ByteString(new Array[Byte](20)),
            timestamp = "0xf4240",
            alloc = Map(
              Hex.toHexString(SenderAddress.bytes.toArray) ->
                GenesisAccount(None, UInt256(BigInt(10).pow(20)), None, None, None),
              Hex.toHexString(BlockhashRecorder.bytes.toArray) ->
                GenesisAccount(None, UInt256.Zero, Some(BlockhashRecorderCode), None, None)
            )
          )
        )
        .get
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, 0).get

object ReorgBlockhashParitySpec extends SecureRandomBuilder:
  /** A cap on the pinned layers that a branch of these blocks meets after its first blocks. */
  val MidCap: Long = 3500L

  val SenderKeyPair: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
  val SenderAddress: Address = Address(SenderKeyPair)
  val Miner: Address = Address(0x6d696e)

  /** `PUSH1 0; CALLDATALOAD; BLOCKHASH; NUMBER; SSTORE; STOP` — storage[NUMBER] := BLOCKHASH(calldata[0..32]). */
  val BlockhashRecorder: Address = Address(0xb10c4a54L)
  val BlockhashRecorderCode: ByteString = ByteString(Hex.decode("600035404355" + "00"))

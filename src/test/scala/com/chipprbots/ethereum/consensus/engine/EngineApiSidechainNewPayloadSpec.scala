package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig

/** engine_newPayload must not move the canonical chain: only engine_forkchoiceUpdated does.
  *
  * hive `GetPayloadBodiesByRange (Sidechain) (Paris)` sends, with no forkchoiceUpdated, a sibling of the canonical head
  * (same parent, withdrawals shuffled) and a child of that sibling, then asks for the bodies of the canonical range. We
  * answered with the SIBLING's body ("withdrawal 1 not equal"): newPayload wrote the number->hash entry of any block
  * whose parent was canonical, so the sibling silently replaced the canonical block at its height.
  */
// scalastyle:off magic.number
class EngineApiSidechainNewPayloadSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private trait Setup extends EphemBlockchainTestSetup:

    implicit override def blockchainConfig: BlockchainConfig =
      initBlockchainConfig.withUpdatedForkBlocks(
        _.copy(
          olympiaBlockNumber = BigInt(1),
          olympiaGasLimitElasticity = Some(BaseFeeCalculator.ElasticityMultiplier)
        )
      )

    override lazy val vm: VMImpl = new VMImpl
    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
    override lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)

    lazy val blockExec = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )
    lazy val forkChoiceManager = new ForkChoiceManager(blockchainReader, blockchainWriter)

    val poolContents: AtomicReference[Seq[SignedTransaction]] = new AtomicReference(Nil)

    lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
      classicSystem.spawn(
        Behaviors.receiveMessage[PendingTransactionsManager.Command] {
          case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            val entries = poolContents.get().flatMap { stx =>
              SignedTransactionWithSender.getSignedTransactions(Seq(stx)).map { withSender =>
                PendingTransactionsManager.PendingTransaction(withSender, 0L)
              }
            }
            replyTo ! PendingTransactionsManager.PendingTransactionsResponse(entries)
            Behaviors.same
          case _ => Behaviors.same
        },
        s"ptm-sidechain-${java.util.UUID.randomUUID()}"
      )

    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExec,
      forkChoiceManager,
      Some(pendingTxManager)
    )(blockchainConfig, typedScheduler)

    private def transfer(nonce: BigInt): SignedTransaction =
      SignedTransaction.sign(
        LegacyTransaction(
          nonce = nonce,
          gasPrice = GasPrice(BigInt("30000000000")),
          gasLimit = GasAmount(21000),
          receivingAddress = Some(Address(ByteString(Array.fill(20)(0x16.toByte)))),
          value = 1,
          payload = ByteString.empty
        ),
        BlockHelpers.keyPair,
        Some(blockchainConfig.chainId.value)
      )

    val tx0: SignedTransaction = transfer(0)
    val tx1: SignedTransaction = transfer(1)
    val sender: Address = SignedTransaction.getSender(tx0).get

    private val genesisStateRoot =
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(0),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        UInt256.Zero,
        ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      InMemoryWorldStateProxy
        .persistState(world.saveAccount(sender, Account(balance = UInt256(BigInt("1000000000000000000")))))
        .stateRootHash

    val genesisHeader: BlockHeader = BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(genesisStateRoot),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(0),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(1000),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
    )

    blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
    blockchainWriter.storeReceipts(genesisHeader.hash, Nil).commit()
    blockchainWriter.storeChainWeight(genesisHeader.hash, ChainWeight.zero).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

    private val zero32 = ByteString(new Array[Byte](32))

    def attrs(parent: BlockHeader, randao: Byte): PayloadAttributes =
      PayloadAttributes(
        timestamp = parent.unixTimestamp.toLong + 1,
        prevRandao = ByteString(Array.fill(32)(randao)),
        suggestedFeeRecipient = Address(ByteString(new Array[Byte](20)))
      )

    /** A payload on `parent` from exactly `txs`, built without touching the head (what a CL gets from another node). */
    def payloadOn(parent: Block, txs: Seq[SignedTransaction], randao: Byte): Block =
      engineApi
        .buildBlockOnParent(
          parent,
          attrs(parent.header, randao),
          txs,
          ByteString.empty,
          parent.header.gasLimit,
          strict = true
        )
        .getOrElse(fail("build failed"))
        .block

    def newPayload(block: Block): PayloadStatusV1 =
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
      import com.chipprbots.ethereum.rlp.encode as rlpEncode
      engineApi
        .newPayload(
          ExecutionPayload(
            parentHash = block.header.parentHash.value,
            feeRecipient = Address(block.header.beneficiary),
            stateRoot = block.header.stateRoot.value,
            receiptsRoot = block.header.receiptsRoot.value,
            logsBloom = block.header.logsBloom.value,
            prevRandao = block.header.mixHash.value,
            blockNumber = block.header.number.value,
            gasLimit = block.header.gasLimit.value,
            gasUsed = block.header.gasUsed.value,
            timestamp = block.header.unixTimestamp.toLong,
            extraData = block.header.extraData,
            baseFeePerGas = block.header.baseFee.getOrElse(BigInt(0)),
            blockHash = block.header.hash.value,
            transactions =
              block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable)))
          )
        )
        .unsafeRunSync()

    def forkchoice(head: Block): Unit =
      engineApi
        .forkchoiceUpdated(ForkChoiceState(head.hash.value, zero32, zero32), None)
        .unsafeRunSync()
        .getOrElse(fail("forkchoiceUpdated failed"))
        .payloadStatus
        .status shouldBe Valid

    def canonicalAt(number: Int): Option[BlockHash] = blockchainReader.getBlockHeaderByNumber(number).map(_.hash)

    /** Canonical genesis -> block1 -> block2 (block2 carries tx1), made head through forkchoiceUpdated. */
    lazy val canonicalChain: (Block, Block) =
      val b1 = payloadOn(Block(genesisHeader, BlockBody(Nil, Nil)), Seq(tx0), randao = 0x01)
      newPayload(b1).status shouldBe Valid
      forkchoice(b1)
      val b2 = payloadOn(b1, Seq(tx1), randao = 0x02)
      newPayload(b2).status shouldBe Valid
      forkchoice(b2)
      (b1, b2)
    def block1: Block = canonicalChain._1
    def block2: Block = canonicalChain._2

  "engine_newPayload" should {

    "keep the canonical block at its height when a VALID sibling arrives without a forkchoiceUpdated" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val sibling = payloadOn(block1, Nil, randao = 0x0b) // same parent and height as block2, empty body
      sibling.header.number shouldBe block2.header.number
      newPayload(sibling).status shouldBe Valid

      canonicalAt(2) shouldBe Some(block2.hash)
      blockchainReader.getBestBlockNumber shouldBe BigInt(2)
      // engine_getPayloadBodiesByRangeV1 reads the canonical index: it must still see block2's transaction.
      engineApi.getPayloadBodyByNumber(2).map(_._1.size) shouldBe Some(1)
      // Still a known, executed side-chain block, reachable by hash.
      engineApi.getPayloadBodyByHash(sibling.hash.value).map(_._1.size) shouldBe Some(0)

    "keep a child of that sibling off the canonical index too" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val sibling = payloadOn(block1, Nil, randao = 0x0b)
      newPayload(sibling).status shouldBe Valid
      val child = payloadOn(sibling, Seq(tx1), randao = 0x0c)
      newPayload(child).status shouldBe Valid

      canonicalAt(2) shouldBe Some(block2.hash)
      canonicalAt(3) shouldBe None

    "let forkchoiceUpdated move the index to the side chain, and back" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        val sibling = payloadOn(block1, Nil, randao = 0x0b)
        newPayload(sibling).status shouldBe Valid
        val child = payloadOn(sibling, Seq(tx1), randao = 0x0c)
        newPayload(child).status shouldBe Valid

        forkchoice(child)
        canonicalAt(2) shouldBe Some(sibling.hash)
        canonicalAt(3) shouldBe Some(child.hash)

        forkchoice(block2)
        canonicalAt(2) shouldBe Some(block2.hash)

    "still write the canonical entry of a payload that extends the head" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        val block3 = payloadOn(block2, Nil, randao = 0x03)
        newPayload(block3).status shouldBe Valid
        canonicalAt(3) shouldBe Some(block3.hash)
  }

  /** After engine_forkchoiceUpdated the canonical index is exactly the head's ancestry: every height up to the head
    * names the head's ancestor and no height above it has an entry. go-ethereum's `SetCanonical` (core/blockchain.go
    * `reorg`: "Delete all hash markers that are not part of the new canonical chain", then `writeHeadBlock`).
    *
    * The defect: a head that moved DOWN left the old chain's entries above it, and the branch walk that promotes a new
    * head stopped at the first entry naming the walked block, wherever it was. So a later forkchoiceUpdated back onto
    * the old chain stopped at a leftover entry, and the heights below it kept the other branch.
    */
  "engine_forkchoiceUpdated to a lower head" should {

    "delete the index entries above an ancestor it rewinds to" taggedAs (UnitTest, ConsensusTest) in new Setup:
      block2 // canonical genesis <- block1 <- block2, head block2
      forkchoice(block1)

      blockchainReader.getBestBlockNumber shouldBe BigInt(1)
      canonicalAt(1) shouldBe Some(block1.hash)
      canonicalAt(2) shouldBe None

      forkchoice(block2)
      canonicalAt(2) shouldBe Some(block2.hash)

    "delete the index entries above a shorter side chain it reorganises to" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        block2
        val side1 = payloadOn(Block(genesisHeader, BlockBody(Nil, Nil)), Nil, randao = 0x0a) // sibling of block1
        newPayload(side1).status shouldBe Valid
        forkchoice(side1)

        blockchainReader.getBestBlockNumber shouldBe BigInt(1)
        canonicalAt(1) shouldBe Some(side1.hash)
        canonicalAt(2) shouldBe None

    "leave the index exactly the ancestry of a head reached back across a shorter side chain" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      // hive `Re-org to Previously Validated Sidechain Payload`: forkchoiceUpdated to a side block below the head, then
      // the CL carries on from the old chain. The leftover entry at height 2 made block3's newPayload write height 3,
      // and the forkchoiceUpdated to block3 then stopped its walk at height 3 — height 1 still named side1.
      block2
      val side1 = payloadOn(Block(genesisHeader, BlockBody(Nil, Nil)), Nil, randao = 0x0a)
      newPayload(side1).status shouldBe Valid
      forkchoice(side1)

      val block3 = payloadOn(block2, Nil, randao = 0x03)
      newPayload(block3).status shouldBe Valid
      forkchoice(block3)

      canonicalAt(1) shouldBe Some(block1.hash)
      canonicalAt(2) shouldBe Some(block2.hash)
      canonicalAt(3) shouldBe Some(block3.hash)
      // What eth_getBlockByNumber serves for height 1 while block3 is the head.
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, 1).map(_.hash) shouldBe Some(block1.hash)
      // block1's transaction is back on block1.
      engineApi.getPayloadBodyByNumber(1).map(_._1.size) shouldBe Some(1)

    "not stop its walk at an entry above the best block that another writer left" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        // The p2p import path's designated-head arm (ConsensusImpl.settleHead, `!selectedByWeight`) moves the best
        // block exactly like this: BlockExecution saves the executed block with its number->hash entry, then the best
        // block moves to the branch tip. Nothing clears what lies above the tip, so height 2 keeps block2.
        block2
        val side1 = payloadOn(Block(genesisHeader, BlockBody(Nil, Nil)), Nil, randao = 0x0a)
        newPayload(side1).status shouldBe Valid
        blockchainWriter.save(side1, Nil, ChainWeight.zero, saveAsBestBlock = false)
        blockchainWriter.saveBestKnownBlocks(side1.hash, side1.header.number.value)
        canonicalAt(2) shouldBe Some(block2.hash)

        forkchoice(block2)

        blockchainReader.getBestBlockNumber shouldBe BigInt(2)
        canonicalAt(1) shouldBe Some(block1.hash)
        canonicalAt(2) shouldBe Some(block2.hash)
  }

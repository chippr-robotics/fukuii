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

    /** Reads of the pool. Every payload build begins with one. */
    val poolQueries = new java.util.concurrent.atomic.AtomicInteger(0)

    lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[PendingTransactionsManager.Command] =
      classicSystem.spawn(
        Behaviors.receiveMessage[PendingTransactionsManager.Command] {
          case PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            poolQueries.incrementAndGet()
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

    /** engine_forkchoiceUpdated(head), with payload attributes for a child of `head` when asked; the answer. */
    def fcu(head: Block, withAttributes: Boolean): ForkchoiceUpdatedResponse =
      engineApi
        .forkchoiceUpdated(
          ForkChoiceState(head.hash.value, zero32, zero32),
          Option.when(withAttributes)(attrs(head.header, randao = 0x0d))
        )
        .unsafeRunSync()
        .getOrElse(fail("forkchoiceUpdated answered an error"))

    /** The canonical-head write itself — what forkchoiceUpdated calls once it adopts a head — for heads the engine API
      * does not move to (an ancestor of the canonical head).
      */
    def makeHead(head: Block): Unit =
      forkChoiceManager.applyForkChoiceState(ForkChoiceState(head.hash.value, zero32, zero32)) shouldBe Right(())

    def canonicalAt(number: Int): Option[BlockHash] = blockchainReader.getBlockHeaderByNumber(number).map(_.hash)

    /** eth_getTransactionByHash, as the JSON-RPC server answers it, over the same storage and the same (stub) pool. */
    lazy val ethTx = new com.chipprbots.ethereum.jsonrpc.EthTxService(
      blockchain,
      blockchainReader,
      mining,
      pendingTxManager,
      scala.concurrent.duration.FiniteDuration(3, java.util.concurrent.TimeUnit.SECONDS),
      storagesInstance.storages.transactionMappingStorage,
      typedScheduler
    )

    /** The block eth_getTransactionByHash reports `stx` as included in; None when it reports no such transaction. */
    def reportedBlockOf(stx: SignedTransaction): Option[ByteString] =
      ethTx
        .getTransactionByHash(com.chipprbots.ethereum.jsonrpc.EthTxService.GetTransactionByHashRequest(stx.hash.value))
        .unsafeRunSync()
        .toOption
        .flatMap(_.txResponse)
        .flatMap(_.blockHash)

    /** The transaction-lookup entry itself: which block the node records `stx` as included in. */
    def lookupOf(stx: SignedTransaction): Option[ByteString] =
      storagesInstance.storages.transactionMappingStorage.get(stx.hash.value).map(_.blockHash)

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

    "delete the index entries above an ancestor made the head" taggedAs (UnitTest, ConsensusTest) in new Setup:
      // The canonical-head write itself. engine_forkchoiceUpdated no longer moves the head to an ancestor (see
      // "engine_forkchoiceUpdated to an ancestor of the canonical head"), but the write it calls must still leave the
      // index exactly the head's ancestry when the head it is given lies below the old one.
      block2 // canonical genesis <- block1 <- block2, head block2
      makeHead(block1)

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

  /** The transaction lookup follows the canonical chain too. eth_getTransactionByHash (EthTxService
    * `getTransactionDataByHash`) reads the lookup, then the block BY HASH, and never asks whether that block is still
    * canonical — so a transaction that only a dropped block carried was still reported as included in it, with that
    * block's hash, number and index. go-ethereum's `reorg` deletes the lookups of the dropped blocks' transactions that
    * the new chain does not re-include (`types.HashDifference(deletedTxs, rebirthTxs)`, core/blockchain.go).
    *
    * The stub pool is empty, so "not included" answers null here rather than as a pending transaction.
    */
  "engine_forkchoiceUpdated and the transaction lookup" should {

    "stop reporting a transaction as included once the only block carrying it leaves the chain" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val head = block2 // builds the chain first: the lookup below must see block2 already imported
      reportedBlockOf(tx1) shouldBe Some(head.hash.value)

      // Rewind: block2, the only block carrying tx1, is dropped. Through the canonical-head write itself, as
      // engine_forkchoiceUpdated no longer rewinds to an ancestor; a reorganisation to a sibling drops it the same way
      // (next case).
      makeHead(block1)

      lookupOf(tx1) shouldBe None
      reportedBlockOf(tx1) shouldBe None
      reportedBlockOf(tx0) shouldBe Some(block1.hash.value)

      forkchoice(block2) // and back: tx1 is included again
      reportedBlockOf(tx1) shouldBe Some(block2.hash.value)

    "drop it on a reorganisation to a sibling that leaves the transaction out" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        val emptySibling = payloadOn(block1, Nil, randao = 0x0b)
        newPayload(emptySibling).status shouldBe Valid

        forkchoice(emptySibling)

        lookupOf(tx1) shouldBe None
        reportedBlockOf(tx1) shouldBe None

    "point it at the new block when the new branch re-includes the transaction" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        val sameTxSibling = payloadOn(block1, Seq(tx1), randao = 0x0c)
        sameTxSibling.hash should not be block2.hash
        newPayload(sameTxSibling).status shouldBe Valid

        forkchoice(sameTxSibling)

        reportedBlockOf(tx1) shouldBe Some(sameTxSibling.hash.value)
  }

  /** execution-apis paris.md engine_forkchoiceUpdatedV1 point 2, carried into V2/V3: "Client software MAY skip an
    * update of the forkchoice state and MUST NOT begin a payload build process if forkchoiceState.headBlockHash
    * references a VALID ancestor of the head of canonical chain … In the case of such an event, client software MUST
    * return {payloadStatus: {status: VALID, latestValidHash: forkchoiceState.headBlockHash, validationError: null},
    * payloadId: null}."
    *
    * go-ethereum v1.16 — the version hive's engine simulator is built on — takes the skip ("Ignoring beacon update to
    * old head"): it answers before the head, safe/finalized or payload building are touched.
    *
    * The defect: we rewound the head to the ancestor, deleting the index and the transaction lookups above it, and,
    * given payload attributes, built a payload on it and returned its id.
    */
  "engine_forkchoiceUpdated to an ancestor of the canonical head" should {

    "answer VALID for it with no payloadId, and leave the head, the index and the lookups alone" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      block2 // canonical genesis <- block1 <- block2, head block2
      val response = fcu(block1, withAttributes = false)

      response.payloadStatus shouldBe PayloadStatusV1(Valid, latestValidHash = Some(block1.hash.value))
      response.payloadId shouldBe None
      blockchainReader.getBestBlockNumber shouldBe BigInt(2)
      canonicalAt(2) shouldBe Some(block2.hash)
      lookupOf(tx1) shouldBe Some(block2.hash.value)

    "not begin a payload build for it, even with payload attributes" taggedAs (UnitTest, ConsensusTest) in new Setup:
      block2
      val poolReadsBefore = poolQueries.get
      val response = fcu(block1, withAttributes = true)

      response.payloadStatus shouldBe PayloadStatusV1(Valid, latestValidHash = Some(block1.hash.value))
      response.payloadId shouldBe None
      withClue("a payload build begins by reading the pool: ")(poolQueries.get shouldBe poolReadsBefore)
      blockchainReader.getBestBlockNumber shouldBe BigInt(2)

    "treat genesis as such an ancestor too" taggedAs (UnitTest, ConsensusTest) in new Setup:
      block2
      val response = fcu(Block(genesisHeader, BlockBody(Nil, Nil)), withAttributes = true)

      response.payloadStatus shouldBe PayloadStatusV1(Valid, latestValidHash = Some(genesisHeader.hash.value))
      response.payloadId shouldBe None
      blockchainReader.getBestBlockNumber shouldBe BigInt(2)

    "still build on the canonical head itself" taggedAs (UnitTest, ConsensusTest) in new Setup:
      block2
      fcu(block2, withAttributes = true).payloadId should not be empty
  }

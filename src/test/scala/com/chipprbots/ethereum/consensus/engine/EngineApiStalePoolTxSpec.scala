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

/** A payload we build must never carry a transaction the parent chain already includes.
  *
  * Measured on hive `engine` 13c1e5686 — `Invalid Missing Ancestor Syncing ReOrg, GasLimit/ReceiptsRoot/Timestamp,
  * EmptyTxs=False, CanonicalReOrg=True, Invalid P8` (Paris and Cancun, 6 tests): after the invalid side chain is
  * rejected and the CL moves back to canonical 15 and 16, fukuii is selected to produce block 17 and its own payload
  * fails, `NONCE_MISMATCH_TOO_LOW: Got tx nonce 0 but sender in mpt is: 1` — the transaction is canonical block 14's,
  * re-added to the pool during branch resolution and never pruned. The engine build is lenient, so a failed transaction
  * does not abort it; it produces a payload no client can validate, and hive fails with "No clients validated the
  * payload".
  *
  * This drives the whole engine producer path — forkchoiceUpdated with attributes, getPayload, newPayload — with a pool
  * that still holds an already-included transaction, which is exactly the state the hive run reached.
  */
// scalastyle:off magic.number
class EngineApiStalePoolTxSpec extends AnyWordSpec with Matchers:

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

    /** What the pool answers. Mutable because the point of the test is a pool that is NOT pruned when a block lands. */
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
          // RemoveTransactions and everything else deliberately ignored: this pool never prunes.
          case _ => Behaviors.same
        },
        s"ptm-stale-pool-${java.util.UUID.randomUUID()}"
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

    /** hive's shape: a 1-wei transfer at nonce 0 that goes into a canonical block, and the same sender's next one. */
    val includedTx: SignedTransaction = transfer(0)
    val nextTx: SignedTransaction = transfer(1)
    val sender: Address = SignedTransaction.getSender(includedTx).get

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

    /** The engine producer path end to end: forkchoiceUpdated(head, attrs) -> getPayload. */
    def buildOn(head: BlockHeader): Block =
      val attrs = PayloadAttributes(
        timestamp = head.unixTimestamp.toLong + 1,
        prevRandao = ByteString(Array.fill(32)(0x42.toByte)),
        suggestedFeeRecipient = Address(ByteString(new Array[Byte](20)))
        // Paris-shaped (V1), like the hive Paris case: the test config activates Shanghai/Cancun far in the future.
      )
      val response =
        engineApi.forkchoiceUpdated(ForkChoiceState(head.hash.value, zero32, zero32), Some(attrs)).unsafeRunSync()
      val payloadId = response
        .getOrElse(fail(s"forkchoiceUpdated failed: $response"))
        .payloadId
        .getOrElse(fail("forkchoiceUpdated returned no payloadId"))
      engineApi.getPayload(payloadId).unsafeRunSync().getOrElse(fail("getPayload failed"))

    /** What a CL does with a built payload: hand it back through newPayload, then make it the head. */
    def importAsCanonical(block: Block): PayloadStatusV1 =
      val status = engineApi.newPayload(toPayload(block)).unsafeRunSync()
      if status.status == Valid then
        engineApi.forkchoiceUpdated(ForkChoiceState(block.hash.value, zero32, zero32), None).unsafeRunSync()
      status

    def toPayload(block: Block): ExecutionPayload =
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
      import com.chipprbots.ethereum.rlp.encode as rlpEncode
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
          block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))),
        withdrawals = block.header.withdrawalsRoot.flatMap(_ => block.body.withdrawals),
        // Whatever fork the test config activates at these timestamps, carry the header fields the envelope needs to
        // hash back to the same block (the builder sets them; newPayload rebuilds the header from them).
        blobGasUsed = block.header.blobGasUsed,
        excessBlobGas = block.header.excessBlobGas,
        parentBeaconBlockRoot = block.header.parentBeaconBlockRoot.map(_.value),
        executionRequests = block.header.requestsHash.map(_ => Nil)
      )

    /** Block 1, canonical, carrying `includedTx` — built by us, validated by us, made head. */
    def canonicalBlock1(): Block =
      poolContents.set(Seq(includedTx))
      val block1 = buildOn(genesisHeader)
      block1.body.transactionList shouldBe Seq(includedTx)
      val status = importAsCanonical(block1)
      withClue(s"block 1 validationError=${status.validationError}: ")(status.status shouldBe Valid)
      block1

  "The engine payload builder" should {

    "exclude a pool transaction the parent chain already includes, and produce a VALID payload" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val block1 = canonicalBlock1()

      // The hive state: the pool still holds block 1's transaction (never pruned), next to a genuinely new one.
      poolContents.set(Seq(includedTx, nextTx))
      val block2 = buildOn(block1.header)

      block2.body.transactionList shouldBe Seq(nextTx)
      val status = engineApi.newPayload(toPayload(block2)).unsafeRunSync()
      withClue(s"validationError=${status.validationError}: ")(status.status shouldBe Valid)

    "produce a VALID empty payload when the pool holds ONLY already-included transactions" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      // The exact hive block-17 shape: one stale transaction and nothing else.
      val block1 = canonicalBlock1()
      poolContents.set(Seq(includedTx))
      val block2 = buildOn(block1.header)

      block2.body.transactionList shouldBe empty
      val status = engineApi.newPayload(toPayload(block2)).unsafeRunSync()
      withClue(s"validationError=${status.validationError}: ")(status.status shouldBe Valid)

    "exclude a transaction that would leave a nonce gap" taggedAs (UnitTest, ConsensusTest) in new Setup:
      // nonce 1 with no nonce 0 before it on genesis: NONCE_MISMATCH_TOO_HIGH if included.
      poolContents.set(Seq(nextTx))
      buildOn(genesisHeader).body.transactionList shouldBe empty

    "keep every executable transaction — the guard removes nothing else" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        poolContents.set(Seq(includedTx, nextTx))
        val block1 = buildOn(genesisHeader)
        block1.body.transactionList shouldBe Seq(includedTx, nextTx)
        val status = importAsCanonical(block1)
        withClue(s"validationError=${status.validationError}: ")(status.status shouldBe Valid)
  }

  "executableAtParent" should {

    "return the list unchanged when the parent state cannot be read" taggedAs (UnitTest, ConsensusTest) in
      new Setup:
        // Cannot judge -> pre-existing behaviour, never a silent drop.
        val unreadable = genesisHeader.copy(stateRoot = TrieRoot(BlockHelpers.randomHash()))
        engineApi.executableAtParent(unreadable, Seq(includedTx, nextTx)) shouldBe Seq(includedTx, nextTx)
  }

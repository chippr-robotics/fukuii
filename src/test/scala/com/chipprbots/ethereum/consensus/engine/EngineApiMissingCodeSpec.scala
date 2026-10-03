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
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig

/** A block that calls a contract whose bytecode this node does not hold is a MISSING-DATA case, not an invalid block.
  *
  * Devnet-8 block 318074: SNAP healing delivered a contract with a codeHash and no bytecode, execution ran the call as
  * a call to an EOA, the block was reported INVALID, and the node then answered `parent block was previously
  * invalidated` for every descendant until restarted. `getCode` now throws `MissingCodeException`, which must reach the
  * CL as ACCEPTED/SYNCING with NOTHING recorded in the invalid-block registry, and the same block must execute and come
  * back VALID once the code is present.
  */
// scalastyle:off magic.number
class EngineApiMissingCodeSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val code = ByteString(Array[Byte](0x00)) // STOP
  private val codeHash = CodeHash(kec256(code))
  private val contract = Address(ByteString(Array.fill[Byte](20)(0x16)))

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
              SignedTransactionWithSender.getSignedTransactions(Seq(stx), Timestamp.Zero).map { withSender =>
                PendingTransactionsManager.PendingTransaction(withSender, 0L)
              }
            }
            replyTo ! PendingTransactionsManager.PendingTransactionsResponse(entries)
            Behaviors.same
          case _ => Behaviors.same
        },
        s"ptm-missing-code-${java.util.UUID.randomUUID()}"
      )

    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExec,
      forkChoiceManager,
      Some(pendingTxManager)
    )(blockchainConfig, typedScheduler)

    val callTx: SignedTransaction =
      SignedTransaction.sign(
        LegacyTransaction(
          nonce = 0,
          gasPrice = GasPrice(BigInt("30000000000")),
          gasLimit = GasAmount(21000),
          receivingAddress = Some(contract),
          value = 0,
          payload = ByteString.empty
        ),
        BlockHelpers.keyPair,
        Some(blockchainConfig.chainId.value)
      )
    val sender: Address = SignedTransaction.getSender(callTx).get

    private val evmCodes = storagesInstance.storages.evmCodeStorage
    // The code is present while genesis and the block are built...
    evmCodes.put(codeHash.value, code).commit()

    private val genesisStateRoot =
      val world = InMemoryWorldStateProxy(
        evmCodes,
        blockchain.getBackingMptStorage(0),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        UInt256.Zero,
        ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = true
      )
      InMemoryWorldStateProxy
        .persistState(
          world
            .saveAccount(sender, Account(balance = UInt256(BigInt("1000000000000000000"))))
            .saveAccount(contract, Account(nonce = UInt256(1), codeHash = codeHash))
        )
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

    /** Block 1 carrying `callTx`, built by the engine producer while the code is present. */
    def buildBlock1(): Block =
      poolContents.set(Seq(callTx))
      val attrs = PayloadAttributes(
        timestamp = genesisHeader.unixTimestamp.toLong + 1,
        prevRandao = ByteString(Array.fill(32)(0x42.toByte)),
        suggestedFeeRecipient = Address(ByteString(new Array[Byte](20)))
      )
      val response = engineApi
        .forkchoiceUpdated(ForkChoiceState(genesisHeader.hash.value, zero32, zero32), Some(attrs))
        .unsafeRunSync()
      val payloadId =
        response.getOrElse(fail(s"forkchoiceUpdated failed: $response")).payloadId.getOrElse(fail("no id"))
      val block = engineApi.getPayload(payloadId).unsafeRunSync().getOrElse(fail("getPayload failed"))
      block.body.transactionList shouldBe Seq(callTx)
      block

    def dropCode(): Unit = evmCodes.remove(codeHash.value).commit()
    def restoreCode(): Unit = evmCodes.put(codeHash.value, code).commit()

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
        blobGasUsed = block.header.blobGasUsed,
        excessBlobGas = block.header.excessBlobGas,
        parentBeaconBlockRoot = block.header.parentBeaconBlockRoot.map(_.value),
        executionRequests = block.header.requestsHash.map(_ => Nil)
      )

  "engine_newPayload for a block that calls a contract whose bytecode is missing" should {

    "answer ACCEPTED/SYNCING, never INVALID, and record nothing in the invalid-block registry" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val block1 = buildBlock1()
      dropCode()

      val status = engineApi.newPayload(toPayload(block1)).unsafeRunSync()

      withClue(s"status=${status.status} validationError=${status.validationError}: ") {
        (status.status == Accepted || status.status == Syncing) shouldBe true
      }
      status.latestValidHash shouldBe None
      engineApi.invalidBlocksSnapshot shouldBe empty

    "execute the same block and answer VALID once the bytecode is present (no verdict was cached)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val block1 = buildBlock1()
      dropCode()
      engineApi.newPayload(toPayload(block1)).unsafeRunSync().status should not be Invalid

      restoreCode()
      val status = engineApi.newPayload(toPayload(block1)).unsafeRunSync()
      withClue(s"validationError=${status.validationError}: ")(status.status shouldBe Valid)
      engineApi.invalidBlocksSnapshot shouldBe empty

    "not poison a descendant: forkchoiceUpdated on the block is not answered 'previously invalidated'" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val block1 = buildBlock1()
      dropCode()
      engineApi.newPayload(toPayload(block1)).unsafeRunSync()

      val fcu = engineApi
        .forkchoiceUpdated(
          ForkChoiceState(block1.hash.value, ByteString(new Array[Byte](32)), ByteString(new Array[Byte](32))),
          None
        )
        .unsafeRunSync()
      fcu.toOption.map(_.payloadStatus.status) should not be Some(Invalid)
  }

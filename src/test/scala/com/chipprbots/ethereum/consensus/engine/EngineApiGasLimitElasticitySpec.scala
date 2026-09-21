package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.consensus.engine.PayloadStatus.*
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Engine-path twin of `OlympiaGasLimitElasticitySpec`.
  *
  * `EngineApiService.newPayload` does NOT route through `BlockHeaderValidatorSkeleton` — it carries its own inline
  * pre-execution gas-limit check. That duplicate must apply the same EIP-1559 one-shot elasticity scaling at the
  * fork-activation block, or the engine path rejects exactly the payloads the block-import path accepts.
  *
  * Producer/validator asymmetry on this rule is the failure mode that caused the original removal, so the payload
  * builder is covered by the same field (`ForkBlockNumbers.olympiaGasLimitElasticity`).
  */
// scalastyle:off magic.number
class EngineApiGasLimitElasticitySpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val GenesisGasLimit: BigInt = 3_000_000
  private val DoubledGasLimit: BigInt = GenesisGasLimit * 2
  private val GasLimitErrorFragment = "invalid gas limit change"

  /** Harness modelled on EngineApiServiceSpec.EngineApiTestSetup: real VM, real validators, ephemeral storage, genesis
    * at block 0 and a single executed block 1. Olympia (EIP-1559) activates at block 1, so block 1 IS the activation
    * block and its parent (genesis) is the last pre-fork block — the exact crossing the scaling rule governs.
    */
  private trait Setup extends EphemBlockchainTestSetup:

    /** Some(2) = ETH/London regime, None = ETC regime. */
    def elasticity: Option[Int]

    override implicit def blockchainConfig: BlockchainConfig =
      initBlockchainConfig.withUpdatedForkBlocks(
        _.copy(olympiaBlockNumber = BigInt(1), olympiaGasLimitElasticity = elasticity)
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
    // Replies immediately with an empty tx pool. Behaviors.ignore would work but costs a 3s ask
    // timeout per payload build, which is wall-clock noise in a consensus suite.
    lazy val pendingTxManager: org.apache.pekko.actor.typed.ActorRef[
      com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
    ] = classicSystem.spawn(
      org.apache.pekko.actor.typed.scaladsl.Behaviors
        .receiveMessage[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command] {
          case com.chipprbots.ethereum.transactions.PendingTransactionsManager.GetPendingTransactionsReq(replyTo) =>
            replyTo ! com.chipprbots.ethereum.transactions.PendingTransactionsManager
              .PendingTransactionsResponse(Nil)
            org.apache.pekko.actor.typed.scaladsl.Behaviors.same
          case _ => org.apache.pekko.actor.typed.scaladsl.Behaviors.same
        },
      s"ptm-stub-gaslimit-elasticity-${java.util.UUID.randomUUID()}"
    )
    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = classicSystem.toTyped.scheduler

    lazy val engineApi = new EngineApiService(
      blockchainReader,
      blockchainWriter,
      blockExec,
      forkChoiceManager,
      Some(pendingTxManager)
    )(blockchainConfig, typedScheduler)

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
      val funded = world.saveAccount(
        Address(ByteString(Array.fill(20)(0x01.toByte))),
        Account(balance = UInt256(BigInt("1000000000000000000")))
      )
      InMemoryWorldStateProxy.persistState(funded).stateRootHash

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
      gasLimit = GasAmount(GenesisGasLimit),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(1000),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
    )

    blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil))).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

    /** Build a fully valid, executed block 1 with the requested gas limit. */
    def buildBlock1(gasLimit: BigInt): Block =
      val template = BlockHeader(
        parentHash = genesisHeader.hash,
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(ByteString.empty),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(1),
        gasLimit = GasAmount(gasLimit),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(1001),
        extraData = ByteString("fukuii".getBytes),
        mixHash = BlockHash(ByteString(Array.fill(32)(0x42.toByte))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostShanghai(
          baseFee = BaseFeeCalculator.InitialBaseFee,
          withdrawalsRoot = BlockHeader.EmptyMpt
        )
      )
      val block = Block(template, BlockBody(Nil, Nil, withdrawals = Some(Nil)))
      blockExec.executeBlockNoValidation(block)(blockchainConfig) match
        case Right((_, gasUsed, computedStateRoot)) =>
          Block(
            template.copy(stateRoot = TrieRoot(computedStateRoot), gasUsed = GasAmount(gasUsed)),
            block.body
          )
        case Left(error) => throw new RuntimeException(s"Failed to execute block: ${error.describe}")

    def blockToPayload(block: Block): ExecutionPayload =
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
        transactions = block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))),
        withdrawals = block.body.withdrawals
      )

    def newPayloadStatus(gasLimit: BigInt): PayloadStatusV1 =
      engineApi.newPayload(blockToPayload(buildBlock1(gasLimit))).unsafeRunSync()

    /** Drive the PRODUCER side: forkchoiceUpdated(head = genesis, attrs) then getPayload.
      * Returns the gas limit the payload builder chose for the activation block.
      */
    def producedActivationGasLimit(): BigInt =
      val fcs = ForkChoiceState(
        headBlockHash = genesisHeader.hash.value,
        safeBlockHash = genesisHeader.hash.value,
        finalizedBlockHash = genesisHeader.hash.value
      )
      val attrs = PayloadAttributes(
        timestamp = 1001L,
        prevRandao = ByteString(Array.fill(32)(0x42.toByte)),
        suggestedFeeRecipient = Address(ByteString(new Array[Byte](20))),
        withdrawals = Some(Nil)
      )
      val response = engineApi.forkchoiceUpdated(fcs, Some(attrs)).unsafeRunSync()
      val payloadId = response
        .getOrElse(throw new RuntimeException(s"forkchoiceUpdated failed: $response"))
        .payloadId
        .getOrElse(throw new RuntimeException("forkchoiceUpdated returned no payloadId"))
      engineApi
        .getPayload(payloadId)
        .unsafeRunSync()
        .getOrElse(throw new RuntimeException("getPayload failed"))
        .header
        .gasLimit
        .value

  private trait EthSetup extends Setup:
    override def elasticity: Option[Int] = Some(BaseFeeCalculator.ElasticityMultiplier)

  private trait EtcSetup extends Setup:
    override def elasticity: Option[Int] = None

  "EngineApiService.newPayload pre-execution gas limit check" should {

    "accept a doubled gas limit at the EIP-1559 activation block when elasticity is Some(2)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EthSetup:
      val status: PayloadStatusV1 = newPayloadStatus(DoubledGasLimit)
      withClue(s"validationError=${status.validationError}: ") {
        status.validationError.getOrElse("") should not include GasLimitErrorFragment
      }
      status.status shouldBe Valid

    "reject a doubled gas limit at the Olympia activation block when elasticity is None (ETC)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EtcSetup:
      val status: PayloadStatusV1 = newPayloadStatus(DoubledGasLimit)
      status.status shouldBe Invalid
      status.validationError.getOrElse("") should include(GasLimitErrorFragment)

    // KNOWN DIVERGENCE, pinned deliberately — do not "fix" without reading the note.
    //
    // Under London, go-ethereum REJECTS an activation block that keeps the parent's gas limit:
    // 3_000_000 is 3_000_000 away from the scaled parent (6_000_000), far outside the 5_859
    // bound. BlockHeaderValidatorSkeleton.validateGasLimit does reject it — see
    // OlympiaGasLimitElasticitySpec, "reject an unchanged gas limit at the activation block".
    //
    // The engine path does NOT, because of the non-geth `&& block.header.gasLimit !=
    // parent.gasLimit` escape clause in EngineApiService's inline check. That clause exists as a
    // guard for the bound == 0 case and predates this change; removing it is a separate,
    // separately-reviewed change (it would alter acceptance on every block, not just this one).
    //
    // The consequence is an engine-vs-import asymmetry on exactly one header shape. This test
    // pins the CURRENT behaviour so the follow-up commit that drops the clause has to flip an
    // assertion consciously rather than by accident.
    "currently ACCEPT an unchanged gas limit at the activation block under Some(2)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EthSetup:
      val status: PayloadStatusV1 = newPayloadStatus(GenesisGasLimit)
      status.status shouldBe Valid

    "accept an unchanged gas limit at the Olympia activation block when elasticity is None (ETC)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EtcSetup:
      val status: PayloadStatusV1 = newPayloadStatus(GenesisGasLimit)
      withClue(s"validationError=${status.validationError}: ") {
        status.validationError.getOrElse("") should not include GasLimitErrorFragment
      }
      status.status shouldBe Valid

    "take BOTH the diff and the divisor from the scaled parent under Some(2)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EthSetup:
      //   raw-parent bound = 3_000_000 / 1024 = 2_929
      //   scaled bound     = 6_000_000 / 1024 = 5_859
      // 2P + 4_000 sits inside 5_859 but outside 2_929, so it only passes when the DIVISOR
      // is taken from the scaled parent too — not just the diff.
      val status: PayloadStatusV1 = newPayloadStatus(DoubledGasLimit + 4_000)
      withClue(s"validationError=${status.validationError}: ") {
        status.validationError.getOrElse("") should not include GasLimitErrorFragment
      }
      status.status shouldBe Valid
  }

  // The producer half. Shipping the validator half alone is the asymmetry that caused the
  // original removal: a builder that kept the raw parent gas limit at the activation block
  // would emit payloads its own newPayload round-trip rejects.
  "EngineApiService payload builder" should {

    "double the parent gas limit at the EIP-1559 activation block when elasticity is Some(2)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EthSetup:
      producedActivationGasLimit() shouldBe DoubledGasLimit

    "keep the parent gas limit at the Olympia activation block when elasticity is None (ETC)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EtcSetup:
      producedActivationGasLimit() shouldBe GenesisGasLimit

    "produce a payload its own newPayload accepts (producer/validator round-trip)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new EthSetup:
      val produced: BigInt = producedActivationGasLimit()
      val status: PayloadStatusV1 = newPayloadStatus(produced)
      withClue(s"validationError=${status.validationError}: ") {
        status.validationError.getOrElse("") should not include GasLimitErrorFragment
      }
      status.status shouldBe Valid
  }
// scalastyle:on magic.number

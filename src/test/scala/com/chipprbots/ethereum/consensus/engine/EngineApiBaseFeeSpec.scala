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

/** EIP-1559 baseFeePerGas on the Engine API path.
  *
  * WHY THIS SPEC EXISTS. On 33b730e75 hive consume-engine failed execution-spec-tests v5.4.0
  * `tests/london/validation/test_header.py::test_invalid_header[fork_Cancun|fork_Prague-blockchain_test_engine-
  * field_base_fee_per_gas-invalid_value_1-exception_BlockException.INVALID_BASEFEE_PER_GAS]` with "want INVALID, got
  * VALID". `newPayload` runs `executeAndValidateBlockFull(alreadyValidated = true)`, so
  * `BlockHeaderValidatorSkeleton.validateBaseFee` never runs on this path, and the inline pre-execution checks had no
  * base-fee rule. The fixture's payload is an EMPTY block, so nothing in execution depends on the base fee and the
  * payload executed to its own state root.
  *
  * The numbers are the fixture's: parent gasLimit 120,000,000, gasUsed 0, baseFee 7; the payload claims baseFee 1. The
  * correct child base fee is 7, not 6: go-ethereum's decrease step is 7 x 60M / 60M / 8 = 0 with no min-1 floor, which
  * is why 6 is also asserted INVALID here.
  */
// scalastyle:off magic.number
class EngineApiBaseFeeSpec extends AnyWordSpec with Matchers:

  implicit val ioRuntime: IORuntime = IORuntime.global

  private val ParentGasLimit: BigInt = 120_000_000
  private val ParentBaseFee: BigInt = 7
  private val BaseFeeErrorFragment = "INVALID_BASEFEE_PER_GAS"

  /** Same harness as EngineApiGasLimitElasticitySpec (real VM, real validators, ephemeral storage), except EIP-1559 is
    * active from genesis so the parent's own baseFee drives the expectation, as in the fixture.
    */
  private trait Setup extends EphemBlockchainTestSetup:

    implicit override def blockchainConfig: BlockchainConfig =
      initBlockchainConfig.withUpdatedForkBlocks(
        _.copy(olympiaBlockNumber = BigInt(0), olympiaGasLimitElasticity = Some(BaseFeeCalculator.ElasticityMultiplier))
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
      s"ptm-stub-basefee-${java.util.UUID.randomUUID()}"
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
      gasLimit = GasAmount(ParentGasLimit),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(1000),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostShanghai(baseFee = ParentBaseFee, withdrawalsRoot = BlockHeader.EmptyMpt)
    )

    blockchainWriter.storeBlock(Block(genesisHeader, BlockBody(Nil, Nil, withdrawals = Some(Nil)))).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(0).commit()

    /** A fully executed, otherwise valid, empty block 1 carrying the requested base fee. `paris = true` builds the
      * pre-Shanghai shape (HefPostOlympia, no withdrawals), which `payloadToBlock` maps a V1 payload to.
      */
    def buildBlock1(baseFee: BigInt, paris: Boolean = false): Block =
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
        gasLimit = GasAmount(ParentGasLimit),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(1012),
        extraData = ByteString.empty,
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields =
          if paris then HefPostOlympia(baseFee)
          else HefPostShanghai(baseFee = baseFee, withdrawalsRoot = BlockHeader.EmptyMpt)
      )
      val block = Block(template, BlockBody(Nil, Nil, withdrawals = if paris then None else Some(Nil)))
      blockExec.executeBlockNoValidation(block)(blockchainConfig) match
        case Right((_, gasUsed, computedStateRoot)) =>
          Block(template.copy(stateRoot = TrieRoot(computedStateRoot), gasUsed = GasAmount(gasUsed)), block.body)
        case Left(error) => throw new RuntimeException(s"Failed to execute block: ${error.describe}")

    def blockToPayload(block: Block): ExecutionPayload =
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
        transactions = Nil,
        withdrawals = block.body.withdrawals
      )

    def newPayloadStatus(baseFee: BigInt, paris: Boolean = false): PayloadStatusV1 =
      engineApi.newPayload(blockToPayload(buildBlock1(baseFee, paris))).unsafeRunSync()

  "EngineApiService.newPayload pre-execution base fee check" should {

    "compute 7 as the child base fee of the fixture's parent (no min-1 floor on the decrease)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      BaseFeeCalculator.calcBaseFee(genesisHeader, blockchainConfig) shouldBe BigInt(7)

    "accept the payload whose base fee equals the EIP-1559 value" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val status = newPayloadStatus(ParentBaseFee)
      withClue(s"validationError=${status.validationError}: ") {
        status.status shouldBe Valid
      }

    "reject the fixture's payload claiming base fee 1 (INVALID_BASEFEE_PER_GAS)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val status = newPayloadStatus(BigInt(1))
      status.status shouldBe Invalid
      status.latestValidHash shouldBe Some(genesisHeader.hash.value)
      status.validationError.getOrElse("") should include(BaseFeeErrorFragment)

    "reject base fee 6, the value a min-1 floor on the decrease would produce" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val status = newPayloadStatus(BigInt(6))
      status.status shouldBe Invalid
      status.validationError.getOrElse("") should include(BaseFeeErrorFragment)

    "reject base fee 8, one above the EIP-1559 value" taggedAs (UnitTest, ConsensusTest) in new Setup:
      val status = newPayloadStatus(BigInt(8))
      status.status shouldBe Invalid
      status.validationError.getOrElse("") should include(BaseFeeErrorFragment)

    // hive consume-engine also failed the fork_Paris and fork_Shanghai variants: the missing rule was
    // fork-independent, so the Paris payload shape (no withdrawals) is pinned as well.
    "reject the fixture's base fee 1 on a Paris-shaped payload (no withdrawals)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val status = newPayloadStatus(BigInt(1), paris = true)
      status.status shouldBe Invalid
      status.validationError.getOrElse("") should include(BaseFeeErrorFragment)

    "accept the correct base fee on a Paris-shaped payload (no withdrawals)" taggedAs (
      UnitTest,
      ConsensusTest
    ) in new Setup:
      val status = newPayloadStatus(ParentBaseFee, paris = true)
      withClue(s"validationError=${status.validationError}: ") {
        status.status shouldBe Valid
      }
  }

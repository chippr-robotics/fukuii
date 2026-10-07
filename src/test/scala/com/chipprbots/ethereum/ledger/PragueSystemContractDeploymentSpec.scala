package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.ledger.BlockExecution.ConsolidationQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryServeWindow
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageCode
import com.chipprbots.ethereum.ledger.BlockExecution.WithdrawalQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecutionError.ValidationAfterExecError
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** ETH Prague system contracts that are deployed IN-CHAIN rather than in genesis.
  *
  * WHY THIS SPEC EXISTS. On 33b730e75 hive consume-rlp-07 failed 8 EEST v5.4.0 cases of
  * `test_system_contract_deployment[fork_CancunToPragueAtTime15k-...]`:
  *
  *   - EIP-2935 deploy_after_fork / deploy_on_fork_block (4): fukuii REJECTED the first Prague block. `applyEip2935`
  *     self-deployed the history contract (nonce 1 + code + a slot) when it had no code, where EIP-2935 says the call
  *     "must fail silently" and go-ethereum's zero-value SYSTEM_ADDRESS call to a non-existent account creates nothing.
  *     Same defect, same fix, as the one already made for EIP-4788 in applyEip4788.
  *   - EIP-7002 / EIP-7251 deploy_after_fork (4): fukuii ACCEPTED a block the fixture marks SYSTEM_CONTRACT_EMPTY.
  *     EIP-7002/7251: "If there is no code at <PREDEPLOY_ADDRESS>, the corresponding block MUST be marked invalid";
  *     fukuii skipped the system call silently.
  *
  * ETC Olympia keeps its client-side deployment of the history contract (ECIP-1112); BlockHashHistorySpec pins it.
  */
class PragueSystemContractDeploymentSpec extends AnyFlatSpec with Matchers:

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new Mocks.MockVM()

    /** ETH, Prague active from timestamp 0; the ETC Olympia block is far away so its 2935 deployment cannot fire. */
    implicit override lazy val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
      .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BigInt("1000000000000000000")))
      .copy(
        networkType = NetworkType.ETH,
        forkTimestamps = ForkTimestamps(pragueTimestamp = Some(0L))
      )

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

    override lazy val blockValidation = new BlockValidation(
      mining.withValidators(Mocks.MockValidatorsAlwaysSucceed),
      blockchainReader,
      blockQueue
    )

    lazy val exec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )

    val emptyWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    def withCode(world: InMemoryWorldStateProxy, addr: Address, code: ByteString): InMemoryWorldStateProxy =
      world
        .saveAccount(addr, Account(nonce = UInt256(1), codeHash = CodeHash(kec256(code))))
        .saveCode(addr, code)

    /** Stand-in bytecode: presence is all the check reads. */
    val someCode: ByteString = ByteString(0x60, 0x00, 0x00)

    val block: Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(5),
        parentHash = BlockHash(ByteString(Array.fill(32)(0xab.toByte))),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = HefPostOlympia(BigInt(0))
      ),
      body = BlockBody(Nil, Nil)
    )

    def runBlock(world: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
      exec.executeBlockTransactions(block, world).toOption.get.worldState

  "EIP-2935 on ETH Prague" should "leave the world untouched when the history contract has no code" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    blockchainConfig.isPragueTimestamp(block.header.unixTimestamp) shouldBe true
    val world = runBlock(emptyWorld)
    world.getAccount(HistoryStorageAddress) shouldBe None
    world.getCode(HistoryStorageAddress) shouldBe ByteString.empty

  it should "write the parent hash at (number - 1) % 8191 once the contract has been deployed in-chain" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world = runBlock(withCode(emptyWorld, HistoryStorageAddress, HistoryStorageCode))
    val slot = (block.header.number.value - 1) % HistoryServeWindow
    world.getStorage(HistoryStorageAddress).load(slot) shouldBe UInt256(block.header.parentHash.value).toBigInt
    world.getAccount(HistoryStorageAddress).map(_.nonce) shouldBe Some(UInt256(1))

  "EIP-7002 / EIP-7251 predeploy presence" should "reject a Prague block when the withdrawal queue has no code" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world = withCode(emptyWorld, ConsolidationQueueAddress, someCode)
    exec.requireRequestPredeploysPresent(block, world) match
      case Left(ValidationAfterExecError(reason)) =>
        reason should include("SYSTEM_CONTRACT_EMPTY")
        reason should include(WithdrawalQueueAddress.toString)
      case other => fail(s"expected SYSTEM_CONTRACT_EMPTY, got $other")

  it should "reject a Prague block when the consolidation queue has no code" taggedAs (UnitTest, ConsensusTest) in
    new TestSetup:
      val world = withCode(emptyWorld, WithdrawalQueueAddress, someCode)
      exec.requireRequestPredeploysPresent(block, world) match
        case Left(ValidationAfterExecError(reason)) =>
          reason should include("SYSTEM_CONTRACT_EMPTY")
          reason should include(ConsolidationQueueAddress.toString)
        case other => fail(s"expected SYSTEM_CONTRACT_EMPTY, got $other")

  it should "accept a Prague block when both predeploys have code (e.g. deployed by this block's own txs)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world = withCode(withCode(emptyWorld, WithdrawalQueueAddress, someCode), ConsolidationQueueAddress, someCode)
    exec.requireRequestPredeploysPresent(block, world) shouldBe Right(())

  it should "not apply before Prague (ETC, and ETH before the fork)" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in new TestSetup:
    val prePrague: BlockchainConfig = blockchainConfig.copy(forkTimestamps = ForkTimestamps())
    exec.requireRequestPredeploysPresent(block, emptyWorld)(using prePrague) shouldBe Right(())
    val etc: BlockchainConfig = Config.blockchains.blockchainConfig
    exec.requireRequestPredeploysPresent(block, emptyWorld)(using etc) shouldBe Right(())

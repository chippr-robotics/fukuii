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
import com.chipprbots.ethereum.utils.ByteStringUtils
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
    val world: InMemoryWorldStateProxy = runBlock(emptyWorld)
    world.getAccount(HistoryStorageAddress) shouldBe None
    world.getCode(HistoryStorageAddress) shouldBe ByteString.empty

  it should "write the parent hash at (number - 1) % 8191 once the contract has been deployed in-chain" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy = runBlock(withCode(emptyWorld, HistoryStorageAddress, HistoryStorageCode))
    val slot: BigInt = (block.header.number.value - 1) % HistoryServeWindow
    world.getStorage(HistoryStorageAddress).load(slot) shouldBe UInt256(block.header.parentHash.value).toBigInt
    world.getAccount(HistoryStorageAddress).map(_.nonce) shouldBe Some(UInt256(1))

  "EIP-7002 / EIP-7251 predeploy presence" should "reject a Prague block when the withdrawal queue has no code" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy = withCode(emptyWorld, ConsolidationQueueAddress, someCode)
    exec.requireRequestPredeploysPresent(block, world) match
      case Left(ValidationAfterExecError(reason)) =>
        reason should include("SYSTEM_CONTRACT_EMPTY")
        reason should include(WithdrawalQueueAddress.toString)
      case other => fail(s"expected SYSTEM_CONTRACT_EMPTY, got $other")

  it should "reject a Prague block when the consolidation queue has no code" taggedAs (UnitTest, ConsensusTest) in
    (new TestSetup:
      val world: InMemoryWorldStateProxy = withCode(emptyWorld, WithdrawalQueueAddress, someCode)
      exec.requireRequestPredeploysPresent(block, world) match
        case Left(ValidationAfterExecError(reason)) =>
          reason should include("SYSTEM_CONTRACT_EMPTY")
          reason should include(ConsolidationQueueAddress.toString)
        case other => fail(s"expected SYSTEM_CONTRACT_EMPTY, got $other")
    )

  it should "accept a Prague block when both predeploys have code (e.g. deployed by this block's own txs)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy =
      withCode(withCode(emptyWorld, WithdrawalQueueAddress, someCode), ConsolidationQueueAddress, someCode)
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

  // ── B-517: the direct write is the canonical contract's effect; other code is run ─────────

  // The direct write stands in for go-ethereum's ProcessParentBlockHash (a SYSTEM_ADDRESS call, 30,000,000 gas) only
  // because it is what the canonical contract does: running that contract as the call gives import's state root.
  "EIP-2935 on ETH Prague (B-517)" should "give the state root that running the canonical contract as a SYSTEM_ADDRESS call gives" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    // A Prague chain as every real one has it, Shanghai and Cancun first: the contract's code uses PUSH0 (EIP-3855).
    val prague: BlockchainConfig = blockchainConfig.copy(forkTimestamps =
      ForkTimestamps(shanghaiTimestamp = Some(0L), cancunTimestamp = Some(0L), pragueTimestamp = Some(0L))
    )
    val deployed: InMemoryWorldStateProxy = withCode(emptyWorld, HistoryStorageAddress, HistoryStorageCode)
    Seq(BigInt(1), BigInt(5), BigInt(8191), BigInt(8192), BigInt(3) * 8191 + 17).foreach { number =>
      val numbered = block.copy(header = block.header.copy(number = BlockNumber(number)))
      val direct = InMemoryWorldStateProxy.persistState(
        exec.executeBlockTransactions(numbered, deployed)(using prague).toOption.get.worldState
      )
      val called = BlockExecution.uncheckedSystemCall(
        numbered.header,
        deployed,
        HistoryStorageAddress,
        numbered.header.parentHash.value,
        accessRecorder = None,
        precompileRelocations = Map.empty
      )(using prague)
      withClue(s"block $number: ") {
        called.stateRootHash shouldBe direct.stateRootHash
        called.stateRootHash should not be InMemoryWorldStateProxy.persistState(deployed).stateRootHash
      }
    }

  // go-ethereum runs the code at HISTORY_STORAGE_ADDRESS, whatever it is; the direct write gave this account the
  // canonical contract's slot instead. The code stores the calldata (the parent hash) at slot 0 when SYSTEM_ADDRESS
  // calls it.
  it should "run code other than the canonical contract, as go-ethereum's system call does" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val custom: ByteString = ByteStringUtils.string2hash(
      "33" + "73" + "ff" * 19 + "fe" + "14" + "6025" + "57" + "600054" + "600052" + "6020" + "6000" + "f3" +
        "5b" + "600035" + "600055" + "00"
    )
    val storage: InMemoryWorldStateProxyStorage =
      runBlock(withCode(emptyWorld, HistoryStorageAddress, custom)).getStorage(HistoryStorageAddress)
    storage.load(0) shouldBe UInt256(block.header.parentHash.value).toBigInt
    storage.load((block.header.number.value - 1) % HistoryServeWindow) shouldBe BigInt(0)

  // ETC's history contract is the client's own (ECIP-1112): its direct write does not read the code, so Olympia keeps
  // it whatever the code is.
  it should "keep ETC Olympia's direct write whatever the code" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in new TestSetup:
    val etc: BlockchainConfig = blockchainConfig
      .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = 0))
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
    val world: InMemoryWorldStateProxy = exec
      .executeBlockTransactions(block, withCode(emptyWorld, HistoryStorageAddress, someCode))(using etc)
      .toOption
      .get
      .worldState
    val storage: InMemoryWorldStateProxyStorage = world.getStorage(HistoryStorageAddress)
    storage.load((block.header.number.value - 1) % HistoryServeWindow) shouldBe
      UInt256(block.header.parentHash.value).toBigInt

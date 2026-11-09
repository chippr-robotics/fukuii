package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootContractAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderDepositQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderExitQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.ConsolidationQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.WithdrawalQueueAddress
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** #1727 PR 5 (B-131) pins block import: threading `precompileRelocations` through the system-call context, and the
  * `EmptyRequestContract` policy of `BlockExecution.requestSystemCalls`, for eth_simulateV1, must not move a request, a
  * requests hash, a receipt or a state root that block execution produces.
  *
  * Every golden below was recorded by running this spec on the tree BEFORE those parameters existed (staging
  * 74ba01b03), and it passes unchanged after. It calls only what block import calls around the request phase, through a
  * real `BlockExecution` (`executeBlockTransactions` with its EIP-4788 / EIP-2935 preamble,
  * `requireRequestPredeploysPresent`, `processPragueSystemCallsChecked`, `collectDepositRequests`) and the requests
  * hash of `computeRequestsHash`, and the new parameters have defaults, so it cannot see them; it can only see whether
  * execution moved.
  *
  * The contracts are the real ones, byte for byte from go-ethereum's devp2p test chain (`QueuePredeploys`), with real
  * requests queued by transactions, so a dequeue that changes anything moves a root. The scenarios: ETH Prague, Osaka
  * and Amsterdam (all four queues; the EIP-4788 and EIP-2935 contracts are stubs that store what they are called with),
  * a Prague block with nothing queued, and ETC at Olympia, where no request contract is ever called.
  */
// scalastyle:off magic.number
class RequestSystemCallsBlockImportPinSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private class Chain(config: BlockchainConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

    override lazy val blockValidation: BlockValidation = new BlockValidation(
      mining.withValidators(com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed),
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

  // ---- The world and the requests ----

  /** SSTORE(0, CALLDATALOAD(0)), STOP: the EIP-4788 and EIP-2935 contracts, reduced to what the system call hands them.
    */
  private val StoresCalldata = ByteString(Hex.decode("60003560005500"))

  private val ConsolidationCalldata = ByteString(Hex.decode("42" * 48 + "43" * 48))

  /** Block 36 of the fixture's chain: pubkey48 || withdrawal_credentials32 || amount8 || signature96, 184 bytes. */
  private val BuilderDepositCalldata = ByteString(
    Hex.decode("42" * 48 + "00" * 12 + "7435ed30a8b4aeb0877cef0c6e8cffe834eb865f" + "000000003b9aca00" + "24" * 96)
  )
  private val BuilderDepositValue = BigInt("1000000000000000001")
  private val BuilderExitPubkey = ByteString(Hex.decode("42" * 48))

  private def predeploy(world: InMemoryWorldStateProxy, address: Address, code: ByteString): InMemoryWorldStateProxy =
    world
      .saveAccount(address, Account(nonce = UInt256(0), balance = UInt256(1), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)

  private def praguePredeploys(chain: Chain): InMemoryWorldStateProxy =
    val funded = chain.emptyWorld.saveAccount(
      senderAddress,
      Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000")))
    )
    predeploy(
      predeploy(funded, WithdrawalQueueAddress, QueuePredeploys.WithdrawalQueueCode),
      ConsolidationQueueAddress,
      QueuePredeploys.ConsolidationQueueCode
    )

  /** Amsterdam: all four queues, and the two preamble contracts as stubs, so the EIP-4788 and EIP-2935 calls run. */
  private def amsterdamPredeploys(chain: Chain): InMemoryWorldStateProxy =
    Seq(
      BuilderDepositQueueAddress -> QueuePredeploys.BuilderDepositQueueCode,
      BuilderExitQueueAddress -> QueuePredeploys.BuilderExitQueueCode,
      BeaconRootContractAddress -> StoresCalldata,
      HistoryStorageAddress -> StoresCalldata
    ).foldLeft(praguePredeploys(chain)) { case (world, (address, code)) => predeploy(world, address, code) }

  private def requestTx(to: Address, value: BigInt, payload: ByteString, nonce: Int): SignedTransaction =
    dynamicFeeTx(Some(to), value, BigInt(1_000_000), payload, BigInt(nonce), config = amsterdamConfig)

  /** One request in each of the two Prague queues. */
  private val pragueTxs: Seq[SignedTransaction] = Seq(
    requestTx(WithdrawalQueueAddress, 1, QueuePredeploys.WithdrawalRequestCalldata, 0),
    requestTx(ConsolidationQueueAddress, 1, ConsolidationCalldata, 1)
  )

  /** ...and one in each of the two builder queues too. */
  private val amsterdamTxs: Seq[SignedTransaction] = pragueTxs ++ Seq(
    requestTx(BuilderDepositQueueAddress, BuilderDepositValue, BuilderDepositCalldata, 2),
    requestTx(BuilderExitQueueAddress, 1, BuilderExitPubkey, 3)
  )

  private def blockOf(header: BlockHeader, txs: Seq[SignedTransaction]): Block =
    Block(header, BlockBody(txs.toList, Nil))

  // ---- What is pinned ----

  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray)

  private def render(afterTx: BlockResult, requests: Seq[ByteString], stateRoot: ByteString): String =
    val receipts = afterTx.receipts.map { receipt =>
      val outcome = receipt.postTransactionStateHash match
        case SuccessOutcome => "ok"
        case FailureOutcome => "fail"
        case HashOutcome(h) => "root-" + Hex.toHexString(h.toArray)
      s"$outcome/${receipt.cumulativeGasUsed}"
    }
    def listed(items: Seq[String]): String = if items.isEmpty then "(none)" else items.mkString(" ")
    s"""receipts: ${listed(receipts)}
       |gasUsed: ${afterTx.gasUsed} execution: ${afterTx.executionGasUsed} state: ${afterTx.stateGasUsed}
       |requests (type:length): ${listed(requests.map(r => s"${r.head}:${r.length}"))}
       |requestsHash: ${hex(BlockExecution.computeRequestsHash(requests))}
       |stateRoot: ${hex(stateRoot)}""".stripMargin

  /** What block import does from the block's transactions to its state root, as `executeBlockUntimed` orders it. */
  private def importPhase(chain: Chain, block: Block, world: InMemoryWorldStateProxy): String =
    given BlockchainConfig = chain.blockchainConfig
    val afterTx: BlockResult =
      chain.exec.executeBlockTransactions(block, world).fold(e => fail(s"transactions failed: $e"), identity)
    chain.exec.requireRequestPredeploysPresent(block, afterTx.worldState) shouldBe Right(())
    val recorder = Option.when(chain.blockchainConfig.isAmsterdamTimestamp(block.header.unixTimestamp)) {
      new com.chipprbots.ethereum.vm.BlockAccessRecorder
    }
    val (afterCalls, systemRequests) = chain.exec
      .processPragueSystemCallsChecked(block, afterTx.worldState, recorder)
      .fold(e => fail(s"request system calls failed: $e"), identity)
    val deposits: Option[ByteString] =
      chain.exec.collectDepositRequests(afterTx.receipts).fold(e => fail(s"deposit requests failed: $e"), identity)
    render(
      afterTx,
      deposits.toSeq ++ systemRequests,
      InMemoryWorldStateProxy.persistState(afterCalls).stateRootHash
    )

  // ---- ETH: the Amsterdam fixture schedule (Cancun 60, Prague 120, Osaka 180, Amsterdam 360) ----

  "Block import on ETH Prague" should "run the EIP-7002 and EIP-7251 calls and commit exactly what it did before" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val chain = new Chain(amsterdamConfig)
    importPhase(chain, blockOf(preAmsterdamHeader(46, 150), pragueTxs), praguePredeploys(chain)) shouldBe
      """receipts: ok/135781 ok/294703
        |gasUsed: 294703 execution: 294703 state: 0
        |requests (type:length): 1:77 2:117
        |requestsHash: 4e56a786e6bfe036e69404f91a340fc3e2698eb3dd6e438f6a6e8d2fcbe17510
        |stateRoot: 95a2ca03118fb153184e26cdc742aad134a82e6e510672b81d8ad8b45bf4f900""".stripMargin
  }

  it should "run them on an empty queue and commit exactly what it did before" taggedAs (UnitTest, StateTest) in {
    val chain = new Chain(amsterdamConfig)
    importPhase(chain, blockOf(preAmsterdamHeader(46, 150), Nil), praguePredeploys(chain)) shouldBe
      """receipts: (none)
        |gasUsed: 0 execution: 0 state: 0
        |requests (type:length): (none)
        |requestsHash: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        |stateRoot: d92a4f452f34fd92c7ba0f96b34f359c177d913d7dbb9cd56f9c49a5245b52c3""".stripMargin
  }

  "Block import on ETH Osaka" should "run the EIP-7002 and EIP-7251 calls and commit exactly what it did before" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val chain = new Chain(amsterdamConfig)
    importPhase(chain, blockOf(preAmsterdamHeader(46, 300), pragueTxs), praguePredeploys(chain)) shouldBe
      """receipts: ok/135781 ok/294703
        |gasUsed: 294703 execution: 294703 state: 0
        |requests (type:length): 1:77 2:117
        |requestsHash: 4e56a786e6bfe036e69404f91a340fc3e2698eb3dd6e438f6a6e8d2fcbe17510
        |stateRoot: 95a2ca03118fb153184e26cdc742aad134a82e6e510672b81d8ad8b45bf4f900""".stripMargin
  }

  "Block import on ETH Amsterdam" should "run all four request calls and commit exactly what it did before" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val chain = new Chain(amsterdamConfig)
    importPhase(chain, blockOf(amsterdamHeader(46, 460), amsterdamTxs), amsterdamPredeploys(chain)) shouldBe
      """receipts: ok/575581 ok/1262223 ok/2170899 ok/2746490
        |gasUsed: 2350080 execution: 396410 state: 2350080
        |requests (type:length): 1:77 2:117 3:185 4:69
        |requestsHash: e596497e7dea1123b814bf45018465c7e39f99ce241059267350343fa6bd5c2d
        |stateRoot: 3d61431ae7c100bea186a9b72ba81575f798b9f9deb2c86fd0b42592fc7c4b95""".stripMargin
  }

  // ---- A missing contract: block import refuses the block, and the direct callers still skip ----

  "Block import on ETH Prague" should "still refuse a block whose consolidation queue has no code" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val chain = new Chain(amsterdamConfig)
    given BlockchainConfig = chain.blockchainConfig
    val block = blockOf(preAmsterdamHeader(46, 150), Nil)
    val onlyWithdrawals = predeploy(chain.emptyWorld, WithdrawalQueueAddress, QueuePredeploys.WithdrawalQueueCode)
    chain.exec.requireRequestPredeploysPresent(block, onlyWithdrawals) match
      case Left(BlockExecutionError.ValidationAfterExecError(reason)) =>
        reason should include("SYSTEM_CONTRACT_EMPTY")
        reason should include(ConsolidationQueueAddress.toString)
      case other => fail(s"expected SYSTEM_CONTRACT_EMPTY, got $other")
  }

  it should "still skip it where the empty-contract check is not made (direct callers), as before" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val chain = new Chain(amsterdamConfig)
    given BlockchainConfig = chain.blockchainConfig
    val block = blockOf(preAmsterdamHeader(46, 150), Nil)
    val onlyWithdrawals = predeploy(chain.emptyWorld, WithdrawalQueueAddress, QueuePredeploys.WithdrawalQueueCode)
    val (_, requests) = chain.exec
      .processPragueSystemCallsChecked(block, onlyWithdrawals)
      .fold(e => fail(s"request system calls failed: $e"), identity)
    requests shouldBe Nil
  }

  // ---- ETC at Olympia (ECIP-1111/1112/1121, block 1,000): no request system call exists ----

  private val etcOlympiaConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          berlinBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 900,
          olympiaBlockNumber = 1000
        )
      )

  private val etcBlock: Block = blockOf(
    amsterdamHeader(1000, 460).copy(extraFields = BlockHeader.HeaderExtraFields.HefPostOlympia(BigInt(1))),
    Nil
  )

  "Block import on ETC at Olympia" should "call no request contract in a bare world, and commit what it did before" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    val chain = new Chain(etcOlympiaConfig)
    importPhase(chain, etcBlock, chain.emptyWorld) shouldBe
      """receipts: (none)
        |gasUsed: 0 execution: 0 state: 0
        |requests (type:length): (none)
        |requestsHash: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        |stateRoot: 333f9e23a9794ed9805e6233f30fd6dfc091bc3fb92a1f68734c083667caa329""".stripMargin
  }

  it should "call no request contract in a world that has them, and commit what it did before" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    // no request is made: the Prague timestamp predicate reads None on every ETC chain
    val chain = new Chain(etcOlympiaConfig)
    importPhase(chain, etcBlock, praguePredeploys(chain)) shouldBe
      """receipts: (none)
        |gasUsed: 0 execution: 0 state: 0
        |requests (type:length): (none)
        |requestsHash: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        |stateRoot: b828a47d552a394a052a922d46e6e2b41f057f335b04bdc05338304358fdd991""".stripMargin
  }

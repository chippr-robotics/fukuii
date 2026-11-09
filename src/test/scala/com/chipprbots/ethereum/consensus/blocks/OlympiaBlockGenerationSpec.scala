package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.consensus.mining.CoinbaseProvider
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthMiningService
import com.chipprbots.ethereum.jsonrpc.EthMiningService.*
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryServeWindow
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageCode
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** B-167: fukuii's proof-of-work block production builds the block that block import accepts, at and after ETC's
  * Olympia fork (ECIP-1111).
  *
  * Before the fix a block mined at or after Olympia differed from what import computes for it in three ways:
  *   - `BlockGeneratorSkeleton.defaultPrepareHeader` never set a base fee, so the header had no `baseFeePerGas` and
  *     failed block import's header rules (`HeaderExtraFieldsError(HefEmpty)`);
  *   - `BlockPreparator.prepareBlock` paid ECIP-1111's treasury credit on the template header's gasUsed, still 0 while
  *     the block is built, so the state root left out the credit import adds;
  *   - `prepareBlock` skipped what import does before the first transaction, which at ETC's Olympia is EIP-2935's
  *     client-deployed history contract and the parent hash (ECIP-1121), so the state root left those out too.
  *
  * Every mined block here goes through block import itself (`ConsensusAdapter.evaluateBranchBlock`, the path a mined
  * block takes from the miner through regular sync): every header rule, then execution, with the state root, receipts
  * root and gas used checked against the header. The header rules are `MockedPowBlockHeaderValidator`'s, which are the
  * PoW validator's rules without the Ethash seal: no test can find a real seal for a fresh header. The seal hash the
  * external miner is handed is checked to commit to the base fee instead.
  *
  * Chains: the shipped Mordor and ETC configs with Olympia moved to block 15,000,000 on Mordor and 23,000,000 on ETC,
  * as the other Olympia specs move it, so that every earlier ETC fork is active below Olympia as on the real chains;
  * and the shipped ETH config before London. The expected base fees and balances are worked by hand from ECIP-1111,
  * EIP-1559 and ECIP-1017, not read from the code. The pre-Olympia and ETH header hashes and state roots were recorded
  * on the tree before the fix (staging 7ae643344) and pass unchanged after it.
  */
// scalastyle:off magic.number
class OlympiaBlockGenerationSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val testKitClassicSystem: ActorSystem = system.toClassic

  private val shippedConfigs = ConfigFactory.load()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedConfigs.getConfig(s"fukuii.blockchains.$name"))

  private val MordorOlympia = BigInt(15_000_000)
  private val EtcOlympia = BigInt(23_000_000)
  private val EthLondon = BigInt(12_965_000)

  private val mordorOlympia: BlockchainConfig =
    shipped("mordor").withUpdatedForkBlocks(_.copy(olympiaBlockNumber = MordorOlympia))
  private val etcOlympia: BlockchainConfig =
    shipped("etc").withUpdatedForkBlocks(_.copy(olympiaBlockNumber = EtcOlympia))
  private val eth: BlockchainConfig = shipped("eth")

  private val Gwei = BigInt(1_000_000_000)

  /** ECIP-1111 "Base Fee Constants": INITIAL_BASE_FEE (the fork block's base fee) and MIN_BASE_FEE (the floor at every
    * block), 1 gwei each.
    */
  private val InitialBaseFee = Gwei
  private val MinBaseFee = Gwei

  /** ECIP-1017 on Mordor (2,000,000-block eras): block 15,000,000 is in era 7, 5 ETC x 0.8^7. */
  private val MordorEra7Reward = BigInt("1048576000000000000")

  /** ECIP-1017 on ETC (5,000,000-block eras): block 23,000,000 is in era 4, 5 ETC x 0.8^4. */
  private val EtcEra4Reward = BigInt("2048000000000000000")

  private val SenderFunds = BigInt(10).pow(19)
  private val ParentTimestamp = 1_500_000_000L
  private val BlockTime = 13L
  private val ParentDifficulty = BigInt(1_000_000)
  private val GasLimit = BigInt(8_000_000)

  // hive's well-known funded key, 0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b
  private val senderKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(Hex.decode("45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8"))
  private val Sender: Address = Address(senderKeyPair)
  private val Recipient: Address = Address(0x5151)
  private val Miner: Address = Address(0xa11ce)
  private val ExtraData: ByteString = ByteString("forge B-167")
  private val SealNonce: ByteString = ByteString(Hex.decode("0102030405060708"))
  private val SealMixHash: ByteString = ByteString(Hex.decode("11" * 32))

  /** JUMPDEST, PUSH1 0, JUMP: init code that loops until it runs out of gas, so its transaction uses all of its gas. */
  private val BurnAllGas: ByteString = ByteString(Hex.decode("5b600056"))

  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray)

  final private class FixedClock(var now: Long) extends BlockTimestampProvider:
    override def getEpochSecond: Long = now

  /** A node on `config` whose best block is a parent saved by [[saveParent]], mining with the real PoW block generator
    * at a fixed clock, and importing with every PoW header rule except the Ethash seal.
    */
  private class Chain(config: BlockchainConfig) extends EphemBlockchainTestSetup with ApisBuilder:
    implicit override lazy val blockchainConfig: BlockchainConfig = config
    override lazy val validators: ValidatorsExecutor = ValidatorsExecutor(Protocol.MockedPow)
    override lazy val miningConfig: MiningConfig =
      buildMiningConfig().copy(headerExtraData = ExtraData, gasLimitTarget = GasLimit, coinbase = Miner)

    val clock = new FixedClock(ParentTimestamp)

    override lazy val mining: TestMining =
      val mockedSeal = buildTestMining().withValidators(validators).withVM(vm)
      mockedSeal.withBlockGenerator(mockedSeal.blockGenerator.withBlockTimestampProvider(clock))

    /** Saves block `number`, whose state holds only the funded sender, as the best block. */
    def saveParent(number: BigInt): Block =
      val world = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        config.accountStartNonce,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = config.ethCompatibleStorage
      ).saveAccount(Sender, Account(nonce = config.accountStartNonce, balance = UInt256(SenderFunds)))
      val header = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(number),
        beneficiary = Address(0xba5e).bytes,
        stateRoot = TrieRoot(InMemoryWorldStateProxy.persistState(world).stateRootHash),
        difficulty = Difficulty(ParentDifficulty),
        gasLimit = GasAmount(GasLimit),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(ParentTimestamp),
        extraFields = HefEmpty
      )
      val block = Block(header, BlockBody.empty)
      blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(ParentDifficulty), saveAsBestBlock = true)
      block

    /** Builds the next block with the PoW block generator, BlockTime seconds after the last one, and seals it. */
    def mine(parent: Block, transactions: Seq[SignedTransaction]): Block =
      clock.now += BlockTime
      val generator = mining.blockGenerator
      val pending = generator.generateBlock(parent, transactions, Miner, generator.emptyX, None).pendingBlock
      seal(pending.block)

    def seal(block: Block): Block =
      block.copy(header = block.header.copy(nonce = SealNonce, mixHash = BlockHash(SealMixHash)))

    /** Block import's header rules against `parent` (which must be stored, as import reads it from storage). */
    def validateHeader(block: Block, parent: Block): Either[BlockHeaderError, BlockHeaderValid] =
      block.header.parentHash shouldBe parent.header.hash
      validators.blockHeaderValidator.validate(block.header, h => blockchainReader.getBlockHeaderByHash(BlockHash(h)))

    def importBlock(block: Block): BlockImportResult =
      consensusAdapter.evaluateBranchBlock(block).unsafeRunSync()

    def balance(address: Address, at: Block): BigInt =
      blockchainReader
        .getAccount(blockchainReader.getBestBranch, address, at.number.value)
        .fold(BigInt(0))(_.balance.toBigInt)

    def transfer(nonce: BigInt, gasPrice: BigInt): SignedTransaction =
      SignedTransaction.sign(
        LegacyTransaction(
          nonce = nonce,
          gasPrice = GasPrice(gasPrice),
          gasLimit = GasAmount(21_000),
          receivingAddress = Recipient,
          value = Gwei,
          payload = ByteString.empty
        ),
        senderKeyPair,
        Some(config.chainId.value)
      )

    def burnAllGas(nonce: BigInt, gasPrice: BigInt, gasLimit: BigInt): SignedTransaction =
      SignedTransaction.sign(
        LegacyTransaction(
          nonce = nonce,
          gasPrice = GasPrice(gasPrice),
          gasLimit = GasAmount(gasLimit),
          receivingAddress = None,
          value = 0,
          payload = BurnAllGas
        ),
        senderKeyPair,
        Some(config.chainId.value)
      )

  /** Mines the next block, checks its header against the header rules and imports it on top. */
  private def mineAndImport(chain: Chain, parent: Block, transactions: Seq[SignedTransaction]): Block =
    val block = chain.mine(parent, transactions)
    withClue(s"block ${block.number} header ${block.header}: ") {
      chain.validateHeader(block, parent) shouldBe Right(BlockHeaderValid)
      chain.importBlock(block) shouldBe a[BlockImportedToTop]
    }
    block

  // ── The first Olympia block ─────────────────────────────────────────────────

  "A block mined at Mordor's first Olympia block" should "carry ECIP-1111's initial base fee and pass block import" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val chain = new Chain(mordorOlympia)
    val parent = chain.saveParent(MordorOlympia - 1)
    val block = mineAndImport(chain, parent, Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 2)))
    block.number.value shouldBe MordorOlympia
    block.header.extraFields shouldBe HefPostOlympia(InitialBaseFee)
    block.header.gasUsed.value shouldBe BigInt(21_000)
    // ECIP-1111: the base fee on the gas used goes to the treasury; the miner keeps the era reward and the 1 gwei tip
    chain.balance(mordorOlympia.treasuryAddress, block) shouldBe InitialBaseFee * 21_000
    chain.balance(Miner, block) shouldBe MordorEra7Reward + Gwei * 21_000
  }

  "A block mined at ETC's first Olympia block" should "carry ECIP-1111's initial base fee and pass block import" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val chain = new Chain(etcOlympia)
    val parent = chain.saveParent(EtcOlympia - 1)
    val block = mineAndImport(chain, parent, Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 2)))
    block.number.value shouldBe EtcOlympia
    block.header.extraFields shouldBe HefPostOlympia(InitialBaseFee)
    chain.balance(etcOlympia.treasuryAddress, block) shouldBe InitialBaseFee * 21_000
    chain.balance(Miner, block) shouldBe EtcEra4Reward + Gwei * 21_000
  }

  // ── The base fee from block to block ────────────────────────────────────────

  "Blocks mined from Mordor's Olympia block on" should "carry EIP-1559's base fee: 1 gwei, a rise after a full block, then the 1 gwei floor" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val chain = new Chain(mordorOlympia)
    val treasury = mordorOlympia.treasuryAddress
    val parent = chain.saveParent(MordorOlympia - 1)

    // Olympia's first block: INITIAL_BASE_FEE whatever the parent did. Its one transaction burns 6,000,000 gas, above
    // the 4,000,000 target (half the 8,000,000 gas limit).
    val b0 = mineAndImport(chain, parent, Seq(chain.burnAllGas(nonce = 0, gasPrice = Gwei * 2, gasLimit = 6_000_000)))
    b0.header.baseFee shouldBe Some(InitialBaseFee)
    b0.header.gasLimit.value shouldBe GasLimit
    b0.header.gasUsed.value shouldBe BigInt(6_000_000)
    chain.balance(treasury, b0) shouldBe InitialBaseFee * 6_000_000

    // EIP-1559 above target: parent + max(1, parentBaseFee * (gasUsed - target) / target / 8)
    //   = 1 gwei + 1 gwei * 2,000,000 / 4,000,000 / 8 = 1.0625 gwei
    val b1 = mineAndImport(chain, b0, Seq(chain.transfer(nonce = 1, gasPrice = Gwei * 3)))
    val target0 = b0.header.gasLimit.value / 2
    val baseFee1 = InitialBaseFee + (InitialBaseFee * (6_000_000 - target0) / target0 / 8).max(1)
    baseFee1 shouldBe BigInt(1_062_500_000)
    b1.header.baseFee shouldBe Some(baseFee1)
    b1.header.gasUsed.value shouldBe BigInt(21_000)
    val treasuryAfterB1 = InitialBaseFee * 6_000_000 + baseFee1 * 21_000
    chain.balance(treasury, b1) shouldBe treasuryAfterB1

    // EIP-1559 below target: parent - parentBaseFee * (target - gasUsed) / target / 8 = 0.930384766 gwei, which
    // ECIP-1111's MIN_BASE_FEE raises to 1 gwei
    val b2 = mineAndImport(chain, b1, Nil)
    val target1 = b1.header.gasLimit.value / 2
    val unclamped2 = baseFee1 - baseFee1 * (target1 - 21_000) / target1 / 8
    unclamped2 shouldBe BigInt(930_384_766)
    b2.header.baseFee shouldBe Some(MinBaseFee)

    // an empty block on a parent at the floor stays at the floor (0.875 gwei unclamped)
    val b3 = mineAndImport(chain, b2, Nil)
    b3.header.baseFee shouldBe Some(MinBaseFee)
    chain.balance(treasury, b3) shouldBe treasuryAfterB1
  }

  // ── The state the block is built on and paid on ─────────────────────────────

  "BlockPreparator.prepareBlock at Olympia" should "build the state block import computes: EIP-2935's history contract, and the treasury credit on the gas used, not on the template's 0" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val chain = new Chain(mordorOlympia)
    val treasury = mordorOlympia.treasuryAddress
    val parent = chain.saveParent(MordorOlympia - 1)
    val body = BlockBody(Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 2)), Nil)
    // what a miner hands prepareBlock: the base fee set, gasUsed not yet known
    val template = parent.header.copy(
      parentHash = parent.header.hash,
      number = BlockNumber(MordorOlympia),
      beneficiary = Miner.bytes,
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(ParentTimestamp + BlockTime),
      extraFields = HefPostOlympia(InitialBaseFee)
    )
    val prepared = chain.mining.blockPreparator.prepareBlock(
      chain.storagesInstance.storages.evmCodeStorage,
      Block(template, body),
      parent.header,
      None
    )(using mordorOlympia)
    prepared.blockResult.gasUsed shouldBe BigInt(21_000)
    prepared.updatedWorld.getAccount(treasury).map(_.balance.toBigInt) shouldBe Some(InitialBaseFee * 21_000)
    // EIP-2935 at ETC's Olympia (ECIP-1121): the client deploys the history contract and stores the parent hash at
    // slot (number - 1) % 8191 before the first transaction
    prepared.updatedWorld.getCode(HistoryStorageAddress) shouldBe HistoryStorageCode
    prepared.updatedWorld.getStorage(HistoryStorageAddress).load((MordorOlympia - 1) % HistoryServeWindow) shouldBe
      BigInt(1, parent.header.hash.value.toArray)

    // block import's state root for the same block, sealed with the gas it used
    val imported = chain.blockExecution.executeForProposer(Block(template.copy(gasUsed = GasAmount(21_000)), body))(
      using mordorOlympia
    )
    imported.map(r => hex(r.worldState.stateRootHash)) shouldBe Right(hex(prepared.stateRootHash))
  }

  // ── The external miner: eth_getWork and eth_submitWork ──────────────────────

  "eth_getWork and eth_submitWork at Mordor's first Olympia block" should "hand out and take back the header the internal miner builds, base fee included" taggedAs (
    UnitTest,
    ConsensusTest,
    RPCTest,
    OlympiaTest
  ) in {
    val chain = new Chain(mordorOlympia)
    val parent = chain.saveParent(MordorOlympia - 1)
    val transfer = chain.transfer(nonce = 0, gasPrice = Gwei * 2)
    chain.clock.now += BlockTime
    val generator = chain.mining.blockGenerator
    val internal =
      generator.generateBlock(parent, Seq(transfer), Miner, generator.emptyX, None)(using mordorOlympia).pendingBlock

    val syncController = TestProbe()
    val pendingTransactions = TestProbe()
    val ommersPool = TestProbe()
    val service = new EthMiningService(
      chain.blockchainReader,
      chain.mining,
      JsonRpcConfig(Config.config, chain.available),
      ommersPool.ref.toTyped[OmmersPool.Command],
      syncController.ref.toTyped[SyncController.Command],
      pendingTransactions.ref.toTyped[PendingTransactionsManager.Command],
      20.seconds,
      chain,
      new CoinbaseProvider(Miner),
      testKitClassicSystem
    )

    val workFuture = service.getWork(GetWorkRequest()).unsafeToFuture()(using cats.effect.unsafe.IORuntime.global)
    pendingTransactions.expectMsgPF() { case req: PendingTransactionsManager.GetPendingTransactionsReq =>
      req.replyTo ! PendingTransactionsManager.PendingTransactionsResponse(
        Seq(PendingTransactionsManager.PendingTransaction(SignedTransactionWithSender(transfer, Sender), 0L))
      )
    }
    ommersPool.expectMsgPF() { case OmmersPool.GetOmmers(_, replyTo) => replyTo ! OmmersPool.Ommers(Nil) }
    val work = Await.result(workFuture, 10.seconds).fold(err => fail(s"eth_getWork failed: $err"), identity)

    // the work package is the seal hash of the internal miner's header, and that hash commits to the base fee
    work.blockNumber shouldBe MordorOlympia
    work.powHeaderHash shouldBe ByteString(kec256(BlockHeader.getEncodedWithoutNonce(internal.block.header)))
    internal.block.header.baseFee shouldBe Some(InitialBaseFee)
    work.powHeaderHash should not be
      ByteString(kec256(BlockHeader.getEncodedWithoutNonce(internal.block.header.copy(extraFields = HefEmpty))))

    service
      .submitWork(SubmitWorkRequest(SealNonce, work.powHeaderHash, SealMixHash))
      .unsafeRunSync()(using cats.effect.unsafe.IORuntime.global) shouldBe Right(SubmitWorkResponse(true))
    val mined = syncController.expectMsgPF() {
      case SyncController.WrappedSyncProtocol(SyncProtocol.MinedBlock(block)) => block
    }
    mined.header shouldBe chain.seal(internal.block).header
    mined.body shouldBe internal.block.body
    chain.validateHeader(mined, parent) shouldBe Right(BlockHeaderValid)
    chain.importBlock(mined) shouldBe a[BlockImportedToTop]
    chain.balance(mordorOlympia.treasuryAddress, mined) shouldBe InitialBaseFee * 21_000
  }

  // ── Unchanged: before Olympia, and ETH ──────────────────────────────────────

  "A block mined before Olympia" should "be byte-identical to the block mined before B-167 (Mordor, block 14,999,999)" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val chain = new Chain(mordorOlympia)
    val parent = chain.saveParent(MordorOlympia - 2)
    val block = mineAndImport(chain, parent, Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 2)))
    block.header.extraFields shouldBe HefEmpty
    (hex(block.header.hash.value), hex(block.header.stateRoot.value)) shouldBe (
      "a4c24e1ca92271050a69d1eb9efe83f7fcc2b5b14ce4f33f30784c57a9d00c00",
      "862272ff676878b545f330bf5b5b555e8a23d355fbeb58c5677a4b03e66f0b8f"
    )
    // no base fee: the miner is paid the whole gas price, and the treasury nothing
    chain.balance(mordorOlympia.treasuryAddress, block) shouldBe BigInt(0)
    chain.balance(Miner, block) shouldBe MordorEra7Reward + Gwei * 2 * 21_000
  }

  "A block mined on ETH before London" should "be byte-identical to the block mined before B-167 (block 12,964,999)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = new Chain(eth)
    val parent = chain.saveParent(EthLondon - 2)
    val block = mineAndImport(chain, parent, Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 2)))
    block.header.extraFields shouldBe HefEmpty
    (hex(block.header.hash.value), hex(block.header.stateRoot.value)) shouldBe (
      "58cdfb84090838cf2f870cd722691d4209012ecc1d0f8dadaf302c6ec9196530",
      "676e479dbcecd4336a011d437b62cd1594a8fbaae4ceeaea6fd09d1f46d32207"
    )
    // EIP-1234's 2 ETH and the whole gas price
    chain.balance(Miner, block) shouldBe BigInt("2000000000000000000") + Gwei * 2 * 21_000
  }
// scalastyle:on magic.number

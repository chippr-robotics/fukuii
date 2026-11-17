package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** B-167: a block fukuii's proof-of-work block production builds is the block block import computes for it.
  *
  * The builder (`BlockGeneratorSkeleton` -> `BlockPreparator.prepareBlock`) reaches import's header and state by
  * calling import's code (`.claude/agent-protocols/proposer-import-parity.md`):
  *   - the template header carries EIP-1559's base fee from the fork block on (London on ETH configs), by the function
  *     import's header validation recomputes it with;
  *   - `prepareBlock` runs import's steps before the first transaction (`BlockExecution.applyBlockPreamble`), the DAO
  *     fork block's drain among them;
  *   - the rewards are paid on the header with the gas the block executed.
  *
  * Every mined block here goes through block import itself (`ConsensusAdapter.evaluateBranchBlock`, the path a mined
  * block takes from the miner through regular sync): every header rule, then execution, with the state root, receipts
  * root and gas used checked against the header. The header rules are `MockedPowBlockHeaderValidator`'s, which are the
  * PoW validator's rules without the Ethash seal: no test can find a real seal for a fresh header.
  *
  * Chains: the shipped Mordor and ETH configs. The expected base fee and balances are worked by hand from EIP-1559,
  * ECIP-1017 and the DAO fork's irregular state change, not read from the code. The Mordor and pre-London ETH header
  * hashes and state roots were recorded on the tree before B-167 (staging 7ae643344) and pass unchanged after it.
  */
// scalastyle:off magic.number
class PowBlockImportParitySpec extends AnyFlatSpec with Matchers:

  private val shippedConfigs = ConfigFactory.load()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedConfigs.getConfig(s"fukuii.blockchains.$name"))

  private val mordor: BlockchainConfig = shipped("mordor")
  private val eth: BlockchainConfig = shipped("eth")

  private val EthDaoFork = BigInt(1_920_000)
  private val EthLondon = BigInt(12_965_000)

  private val E = BigInt(10).pow(18)
  private val Gwei = BigInt(1_000_000_000)

  /** ECIP-1017 on Mordor (2,000,000-block eras): block 14,999,999 is in era 7, 5 ETC x 0.8^7. */
  private val MordorEra7Reward = BigInt("1048576000000000000")

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

  private def hex(bytes: ByteString): String = Hex.toHexString(bytes.toArray)

  final private class FixedClock(var now: Long) extends BlockTimestampProvider:
    override def getEpochSecond: Long = now

  /** A node on `config` whose best block is a parent saved by [[saveParent]], mining with the real PoW block generator
    * at a fixed clock, and importing with every PoW header rule except the Ethash seal.
    */
  private class Chain(config: BlockchainConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config
    override lazy val validators: ValidatorsExecutor = ValidatorsExecutor(Protocol.MockedPow)
    override lazy val miningConfig: MiningConfig =
      buildMiningConfig().copy(headerExtraData = ExtraData, gasLimitTarget = GasLimit, coinbase = Miner)

    val clock = new FixedClock(ParentTimestamp)

    override lazy val mining: TestMining =
      val mockedSeal = buildTestMining().withValidators(validators).withVM(vm)
      mockedSeal.withBlockGenerator(mockedSeal.blockGenerator.withBlockTimestampProvider(clock))

    /** Saves block `number` as the best block. Its state holds the funded sender and the `funded` accounts; its header
      * has `gasUsed` and `extraFields` (`HefPostOlympia` for a block with a base fee).
      */
    def saveParent(
        number: BigInt,
        gasUsed: BigInt = 0,
        extraFields: HeaderExtraFields = HefEmpty,
        funded: Seq[(Address, BigInt)] = Nil
    ): Block =
      val withSender = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
        config.accountStartNonce,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = false,
        ethCompatibleStorage = config.ethCompatibleStorage
      ).saveAccount(Sender, Account(nonce = config.accountStartNonce, balance = UInt256(SenderFunds)))
      val world = funded.foldLeft(withSender) { case (w, (address, balance)) =>
        w.saveAccount(address, Account(nonce = config.accountStartNonce, balance = UInt256(balance)))
      }
      val header = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(number),
        beneficiary = Address(0xba5e).bytes,
        stateRoot = TrieRoot(InMemoryWorldStateProxy.persistState(world).stateRootHash),
        difficulty = Difficulty(ParentDifficulty),
        gasLimit = GasAmount(GasLimit),
        gasUsed = GasAmount(gasUsed),
        unixTimestamp = Timestamp(ParentTimestamp),
        extraFields = extraFields
      )
      val block = Block(header, BlockBody.empty)
      blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(ParentDifficulty), saveAsBestBlock = true)
      block

    /** Builds the next block with the PoW block generator, BlockTime seconds after the last one, and seals it. */
    def mine(parent: Block, transactions: Seq[SignedTransaction]): Block =
      clock.now += BlockTime
      val generator = mining.blockGenerator
      val pending = generator.generateBlock(parent, transactions, Miner, generator.emptyX, None).pendingBlock
      pending.block.copy(header = pending.block.header.copy(nonce = SealNonce, mixHash = BlockHash(SealMixHash)))

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

  /** Mines the next block, checks its header against the header rules and imports it on top. */
  private def mineAndImport(chain: Chain, parent: Block, transactions: Seq[SignedTransaction]): Block =
    val block = chain.mine(parent, transactions)
    withClue(s"block ${block.number} header ${block.header}: ") {
      chain.validateHeader(block, parent) shouldBe Right(BlockHeaderValid)
      chain.importBlock(block) shouldBe a[BlockImportedToTop]
    }
    block

  // ── ETC: unchanged by B-167 ─────────────────────────────────────────────────

  "A block mined on Mordor" should "be byte-identical to the block mined before B-167, and import (block 14,999,999)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = new Chain(mordor)
    val parent = chain.saveParent(BigInt(14_999_998))
    val block = mineAndImport(chain, parent, Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 2)))
    block.header.extraFields shouldBe HefEmpty
    (hex(block.header.hash.value), hex(block.header.stateRoot.value)) shouldBe (
      "a4c24e1ca92271050a69d1eb9efe83f7fcc2b5b14ce4f33f30784c57a9d00c00",
      "862272ff676878b545f330bf5b5b555e8a23d355fbeb58c5677a4b03e66f0b8f"
    )
    // ECIP-1017's era 7 reward, and the whole gas price
    chain.balance(Miner, block) shouldBe MordorEra7Reward + Gwei * 2 * 21_000
  }

  // ── ETH configs ─────────────────────────────────────────────────────────────

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
    chain.balance(Miner, block) shouldBe E * 2 + Gwei * 2 * 21_000
  }

  "A block mined on ETH after London" should "carry EIP-1559's base fee from its parent, burn it, and import (block 12,965,001)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = new Chain(eth)
    // the London block: base fee 8 gwei, 6,000,000 gas used of 8,000,000, so 2,000,000 above the 4,000,000 target
    val parentBaseFee = Gwei * 8
    val parent = chain.saveParent(EthLondon, gasUsed = 6_000_000, extraFields = HefPostOlympia(parentBaseFee))
    val block = mineAndImport(chain, parent, Seq(chain.transfer(nonce = 0, gasPrice = Gwei * 10)))
    block.number.value shouldBe EthLondon + 1
    // EIP-1559 above target: parentBaseFee + max(1, parentBaseFee * (gasUsed - target) / target / 8)
    //   = 8 gwei + 8 gwei * 2,000,000 / 4,000,000 / 8 = 8.5 gwei
    val baseFee = BigInt(8_500_000_000L)
    parentBaseFee + (parentBaseFee * 2_000_000 / 4_000_000 / 8).max(1) shouldBe baseFee
    block.header.extraFields shouldBe HefPostOlympia(baseFee)
    block.header.gasUsed.value shouldBe BigInt(21_000)
    // the miner: EIP-1234's 2 ETH and the tip, 10 - 8.5 = 1.5 gwei on 21,000 gas; the base fee is burned
    chain.balance(Miner, block) shouldBe E * 2 + (Gwei * 10 - baseFee) * 21_000
    chain.balance(Sender, block) shouldBe SenderFunds - Gwei * 10 * 21_000 - Gwei
    chain.balance(Recipient, block) shouldBe Gwei
    val supplyAfter = chain.balance(Miner, block) + chain.balance(Sender, block) + chain.balance(Recipient, block)
    supplyAfter shouldBe SenderFunds + E * 2 - baseFee * 21_000
  }

  "A block mined at ETH's DAO fork block" should "drain the DAO accounts to the refund contract, as import does (block 1,920,000)" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val chain = new Chain(eth)
    val dao = eth.daoForkConfig.getOrElse(fail("the shipped ETH config has no DAO fork"))
    val drained = dao.drainList.head
    val refund = dao.refundContract.getOrElse(fail("the shipped ETH config has no DAO refund contract"))
    val parent = chain.saveParent(EthDaoFork - 1, funded = Seq(drained -> E * 3))
    val block = mineAndImport(chain, parent, Nil)
    block.number.value shouldBe EthDaoFork
    block.header.extraData shouldBe ByteString("dao-hard-fork")
    // the irregular state change before the first transaction: the drain-list account's balance to the refund contract
    chain.balance(drained, block) shouldBe BigInt(0)
    chain.balance(refund, block) shouldBe E * 3
    // the Frontier reward
    chain.balance(Miner, block) shouldBe E * 5
  }
// scalastyle:on magic.number

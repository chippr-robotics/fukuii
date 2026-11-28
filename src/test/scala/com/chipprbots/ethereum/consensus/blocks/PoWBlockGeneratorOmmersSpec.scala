package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.blocks.RestrictedPoWBlockGeneratorImpl
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.pow.validators.StdOmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.*
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.*

/** B-500. A proof-of-work block this node builds carries each ommer offered that passes the ommers check import makes,
  * and leaves out only the ones that fail: one already included by an ancestor no longer costs the block the other.
  * core-geth's worker commits the uncles one by one, and skips the one it refuses (`commitUncle`, miner/worker.go).
  *
  * The ommers check is the one import makes (`StdOmmersValidator`), on the chain this node holds; only the ommers'
  * seals are not checked, since they carry none. Both block generators (the plain one and the restricted one, which
  * signs its blocks), on both state layouts.
  */
class PoWBlockGeneratorOmmersSpec extends AnyFlatSpec with Matchers:

  private val keyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
  private val miner = Address(0xc0ffee)

  final private class Clock extends BlockTimestampProvider:
    @volatile var now: Long = 0L
    override def getEpochSecond: Long = now

  final private class Node(guard: Boolean, restricted: Boolean) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        homesteadBlockNumber = 1150000,
        eip155BlockNumber = 0,
        difficultyBombPauseBlockNumber = 3000000,
        difficultyBombContinueBlockNumber = 5000000,
        difficultyBombRemovalBlockNumber = 5900000
      ),
      chainId = ChainId(0x3d),
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      monetaryPolicyConfig =
        MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      ethCompatibleStorage = true
    )
    private val source = EphemDataSource()
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard

    /** Import's ommers check, but for the ommers' seals: the ommers here carry none. */
    val ommersValidator: OmmersValidator = new StdOmmersValidator(MockValidatorsAlwaysSucceed.blockHeaderValidator)
    override lazy val validators: ValidatorsExecutor = new ValidatorsExecutor:
      override def ommersValidator: OmmersValidator = Node.this.ommersValidator
      override def blockValidator: BlockValidator = powValidators.blockValidator
      override def blockHeaderValidator: BlockHeaderValidator = powValidators.blockHeaderValidator
      override def signedTransactionValidator: SignedTransactionValidator = powValidators.signedTransactionValidator

    GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages).loadGenesisData()

    val clock = new Clock
    lazy val generator: TestBlockGenerator =
      if restricted then
        new RestrictedPoWBlockGeneratorImpl(
          storagesInstance.storages.evmCodeStorage,
          validators,
          blockchainReader,
          miningConfig,
          mining.blockPreparator,
          DifficultyCalculator,
          keyPair,
          clock
        )
      else
        new PoWBlockGeneratorImpl(
          storagesInstance.storages.evmCodeStorage,
          validators,
          blockchainReader,
          miningConfig,
          mining.blockPreparator,
          DifficultyCalculator,
          clock
        )
    lazy val execution = new HeaderTrustingBlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new BlockValidation(mining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
    )

    def head: Block = blockchainReader.getBestBlock.get
    def prepare(ommers: Seq[BlockHeader]): Block =
      clock.now = head.header.unixTimestamp.toLong + 15
      // the ommers of a proof-of-work block are headers: what `X` is for these generators
      generator.generateBlock(head, Nil, miner, ommers.asInstanceOf[generator.X], None).pendingBlock.block
    def importOne(block: Block): Option[BlockExecutionError] =
      val weight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true)._2
    def ommersCheck(ommers: Seq[BlockHeader]): Either[OmmersError, OmmersValid] =
      ommersValidator.validate(head.header.hash.value, head.header.number.value + 1, ommers, blockchainReader)

  /** A header beside `of`'s, of the same parent: an ommer of the blocks two to seven above that parent, with a miner of
    * its own.
    */
  private def ommerOf(of: BlockHeader, salt: Int): BlockHeader =
    of.copy(beneficiary = ByteString(Array.fill[Byte](20)(salt.toByte)), extraData = ByteString(s"ommer $salt"))

  for
    restricted <- Seq(false, true)
    guard <- Seq(false, true)
  do
    val generatorName = if restricted then "the restricted generator" else "the generator"
    val layout = if guard then "the unified layout" else "the hash store"
    s"A block $generatorName builds on $layout" should
      "carry the valid ommer of a set where the other was already included (B-500)" taggedAs (
        UnitTest,
        ConsensusTest
      ) in {
        val node = new Node(guard, restricted)

        // block 1, and block 2 with an ommer U beside block 1
        val first = node.prepare(Nil)
        node.importOne(first) shouldBe None
        val included = ommerOf(first.header, 1)
        val second = node.prepare(Seq(included))
        second.body.uncleNodesList shouldBe Seq(included)
        node.importOne(second) shouldBe None

        // block 3 is offered U again and V, another ommer beside block 1. Together they fail the check import makes:
        // U was used before.
        val fresh = ommerOf(first.header, 2)
        node.ommersCheck(Seq(included, fresh)) shouldBe Left(OmmersError.OmmersUsedBeforeError)
        node.ommersCheck(Seq(fresh)) shouldBe Right(OmmersValid)

        // in either order, the block carries V, and only V
        node.prepare(Seq(fresh, included)).body.uncleNodesList shouldBe Seq(fresh)
        val third = node.prepare(Seq(included, fresh))
        third.body.uncleNodesList shouldBe Seq(fresh)
        node.importOne(third) shouldBe None
        node.head.header.hash shouldBe third.header.hash
        node.head.body.uncleNodesList shouldBe Seq(fresh)

        // a block offered only ommers that fail carries none
        node.prepare(Seq(included, fresh)).body.uncleNodesList shouldBe Nil
      }

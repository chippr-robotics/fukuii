package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryServeWindow
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageCode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** EIP-2935: Block hash history storage behavioral integration tests.
  *
  * Exercises [[BlockExecution.executeBlockTransactions]] at and around the Olympia activation block to verify that
  * parent hashes are stored in the history contract at the correct slot `(blockNumber − 1) % 8191`, the contract is
  * deployed at the activation block, storage is absent before activation, and the 8191-slot wrap-around works
  * correctly.
  */
class BlockHashHistorySpec extends AnyFlatSpec with Matchers {

  private val Window: BigInt = HistoryServeWindow

  trait TestSetup extends EphemBlockchainTestSetup {
    override lazy val vm: VMImpl = new Mocks.MockVM()

    val olympiaBlock: BigInt = 10

    implicit override lazy val blockchainConfig: BlockchainConfig = blockchainConfig0
      .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock))

    private lazy val blockchainConfig0: BlockchainConfig = Config.blockchains.blockchainConfig

    override lazy val blockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

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
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    def makeBlock(number: BigInt, parentHash: ByteString, isOlympia: Boolean = true): Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        number = number,
        parentHash = parentHash,
        gasLimit = 8_000_000,
        gasUsed = 0,
        extraFields = if (isOlympia) HefPostOlympia(BigInt(0)) else HefEmpty
      ),
      body = BlockBody(Nil, Nil)
    )

    def runBlock(block: Block, world: InMemoryWorldStateProxy = emptyWorld): InMemoryWorldStateProxy =
      exec.executeBlockTransactions(block, world).toOption.get.worldState
  }

  "EIP-2935 block hash history" should "store parent hash at the correct slot for the Olympia activation block" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup {
    val parentHash: ByteString = ByteString(Array.fill(32)(0xab.toByte))
    val world = runBlock(makeBlock(olympiaBlock, parentHash))

    val slot = (olympiaBlock - 1) % Window
    world.getStorage(HistoryStorageAddress).load(slot) shouldBe UInt256(parentHash).toBigInt
  }

  it should "deploy the history storage contract code at the Olympia activation block" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup {
    val world = runBlock(makeBlock(olympiaBlock, ByteString(Array.fill(32)(0x01.toByte))))
    world.getCode(HistoryStorageAddress) shouldBe HistoryStorageCode
  }

  it should "wrap around the 8191-slot window: block N and block N+8191 share the same slot" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup {
    val hashA = ByteString(Array.fill(32)(0xaa.toByte))
    val hashB = ByteString(Array.fill(32)(0xbb.toByte))

    val world1 = runBlock(makeBlock(olympiaBlock, hashA))
    val world2 = runBlock(makeBlock(olympiaBlock + Window, hashB), world1)

    val slot = (olympiaBlock - 1) % Window
    world2.getStorage(HistoryStorageAddress).load(slot) shouldBe UInt256(hashB).toBigInt
  }

  it should "NOT write history storage for blocks before Olympia activation" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup {
    val world = runBlock(
      makeBlock(olympiaBlock - 1, ByteString(Array.fill(32)(0xdd.toByte)), isOlympia = false)
    )

    val slot = (olympiaBlock - 2) % Window
    world.getStorage(HistoryStorageAddress).load(slot) shouldBe BigInt(0)
    world.getCode(HistoryStorageAddress) shouldBe ByteString.empty
  }

  "HistoryStorageCode" should "start with the CALLER opcode (0x33) per EIP-2935 spec" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    HistoryStorageCode should not be empty
    HistoryStorageCode.head shouldBe 0x33.toByte
  }
}

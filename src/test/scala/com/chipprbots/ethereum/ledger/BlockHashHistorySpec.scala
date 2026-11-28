package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryServeWindow
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageCode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** EIP-2935: Block hash history storage behavioral integration tests.
  *
  * Exercises [[BlockExecution.executeBlockTransactions]] at and around the Olympia activation block to verify that
  * parent hashes are stored in the history contract at the correct slot `(blockNumber − 1) % 8191`, nothing is written
  * before activation, and the 8191-slot wrap-around works correctly.
  *
  * B-528: ECIP-1121 adopts EIP-2935 unmodified, so the client does not deploy the history contract. It arrives by
  * EIP-2935's deploy transaction (see Eip2935DeployTransactionSpec); where it has no code, a block changes nothing, as
  * on ETH. The fixtures here hold the canonical contract as that transaction leaves it (code, nonce 1).
  */
class BlockHashHistorySpec extends AnyFlatSpec with Matchers:

  private val Window: BigInt = HistoryServeWindow

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new Mocks.MockVM()

    val olympiaBlock: BigInt = 10

    implicit override lazy val blockchainConfig: BlockchainConfig = blockchainConfig0
      .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = olympiaBlock))

    private lazy val blockchainConfig0: BlockchainConfig = Config.blockchains.blockchainConfig

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

    /** The history contract as EIP-2935's deploy transaction leaves it: the canonical code, nonce 1. */
    val deployedWorld: InMemoryWorldStateProxy = emptyWorld
      .saveAccount(
        HistoryStorageAddress,
        Account(nonce = UInt256(1), codeHash = CodeHash(kec256(HistoryStorageCode)))
      )
      .saveCode(HistoryStorageAddress, HistoryStorageCode)

    def rootOf(world: InMemoryWorldStateProxy): ByteString = InMemoryWorldStateProxy.persistState(world).stateRootHash

    def makeBlock(number: BigInt, parentHash: ByteString, isOlympia: Boolean = true): Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(number),
        parentHash = BlockHash(parentHash),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = if isOlympia then HefPostOlympia(BigInt(0)) else HefEmpty
      ),
      body = BlockBody(Nil, Nil)
    )

    def runBlock(block: Block, world: InMemoryWorldStateProxy = deployedWorld): InMemoryWorldStateProxy =
      exec.executeBlockTransactions(block, world).toOption.get.worldState

  "EIP-2935 block hash history" should "store parent hash at the correct slot for the Olympia activation block" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val parentHash: ByteString = ByteString(Array.fill(32)(0xab.toByte))
    val world: InMemoryWorldStateProxy = runBlock(makeBlock(olympiaBlock, parentHash))

    val slot: BigInt = (olympiaBlock - 1) % Window
    world.getStorage(HistoryStorageAddress).load(slot) shouldBe UInt256(parentHash).toBigInt

  it should "leave a world without the history contract untouched after activation: no account is created" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    // EIP-2935: "if no code exists at HISTORY_STORAGE_ADDRESS, the call must fail silently". The client used to deploy
    // the contract here (nonce 1, code, the slot); go-ethereum's ProcessParentBlockHash creates nothing (B-528).
    val world: InMemoryWorldStateProxy =
      runBlock(makeBlock(olympiaBlock + 1, ByteString(Array.fill(32)(0xcc.toByte))), emptyWorld)
    world.getAccount(HistoryStorageAddress) shouldBe None
    world.getCode(HistoryStorageAddress) shouldBe ByteString.empty
    rootOf(world) shouldBe rootOf(emptyWorld)

  it should "not deploy the history contract at the Olympia activation block" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy =
      runBlock(makeBlock(olympiaBlock, ByteString(Array.fill(32)(0x01.toByte))), emptyWorld)
    world.getAccount(HistoryStorageAddress) shouldBe None
    world.getCode(HistoryStorageAddress) shouldBe ByteString.empty
    rootOf(world) shouldBe rootOf(emptyWorld)

  it should "keep the deployed contract's code and nonce, and change only its slot, at the activation block" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy = runBlock(makeBlock(olympiaBlock, ByteString(Array.fill(32)(0x01.toByte))))
    world.getCode(HistoryStorageAddress) shouldBe HistoryStorageCode
    world.getAccount(HistoryStorageAddress).map(_.nonce) shouldBe Some(UInt256(1))

  it should "wrap around the 8191-slot window: block N and block N+8191 share the same slot" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val hashA: ByteString = ByteString(Array.fill(32)(0xaa.toByte))
    val hashB: ByteString = ByteString(Array.fill(32)(0xbb.toByte))

    val world1: InMemoryWorldStateProxy = runBlock(makeBlock(olympiaBlock, hashA))
    val world2: InMemoryWorldStateProxy = runBlock(makeBlock(olympiaBlock + Window, hashB), world1)

    val slot: BigInt = (olympiaBlock - 1) % Window
    world2.getStorage(HistoryStorageAddress).load(slot) shouldBe UInt256(hashB).toBigInt

  it should "NOT write history storage for blocks before Olympia activation" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in new TestSetup:
    val before: Block = makeBlock(olympiaBlock - 1, ByteString(Array.fill(32)(0xdd.toByte)), isOlympia = false)
    // With the contract deployed ahead of the fork (as the deploy transaction must be), nothing is written yet.
    val world: InMemoryWorldStateProxy = runBlock(before)
    val slot: BigInt = (olympiaBlock - 2) % Window
    world.getStorage(HistoryStorageAddress).load(slot) shouldBe BigInt(0)
    rootOf(world) shouldBe rootOf(deployedWorld)
    // Without it, nothing appears.
    runBlock(before, emptyWorld).getCode(HistoryStorageAddress) shouldBe ByteString.empty

  "HistoryStorageCode" should "start with the CALLER opcode (0x33) per EIP-2935 spec" taggedAs (
    OlympiaTest,
    ConsensusTest
  ) in {
    HistoryStorageCode should not be empty
    HistoryStorageCode.head shouldBe 0x33.toByte
  }

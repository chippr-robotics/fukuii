package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootContractAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootHistoryBufferLength
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootsCode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ForkTimestamps

/** EIP-4788: Beacon block roots system contract behavioral integration tests.
  *
  * Exercises [[BlockExecution.executeBlockTransactions]] at and around the Cancun activation timestamp to verify that
  * the beacon roots contract is deployed (code + nonce=1) on the first post-Cancun block, ring-buffer storage slots are
  * written correctly, redeployment is skipped on subsequent blocks, and the contract is absent before Cancun
  * activation.
  */
class BeaconRootsSpec extends AnyFlatSpec with Matchers:

  private val CancunTs: Long = 1_000L

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new Mocks.MockVM()

    implicit override lazy val blockchainConfig: BlockchainConfig =
      Config.blockchains.blockchainConfig.copy(
        forkTimestamps = ForkTimestamps(
          shanghaiTimestamp = Some(0L),
          cancunTimestamp = Some(CancunTs)
        )
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

    /** The canonical EIP-4788 contract as every public network actually has it: deployed by the pre-signed
      * Nick's-method transaction from 0x0B799C86a49DEeb90402691F1041aa3AF2d3C875 (EIP-4788 "Deployment") long before
      * the Cancun fork, so the account already carries code and nonce=1 by the time the first post-Cancun block is
      * processed. The client never deploys it itself — EIP-4788 Block processing: "if no code exists at
      * BEACON_ROOTS_ADDRESS, the call must fail silently" — so every storage-behaviour test must start from a world
      * where it is present.
      */
    val deployedWorld: InMemoryWorldStateProxy =
      emptyWorld
        .saveAccount(
          BeaconRootContractAddress,
          Account.empty(blockchainConfig.accountStartNonce).copy(nonce = UInt256(1))
        )
        .saveCode(BeaconRootContractAddress, BeaconRootsCode)

    def makeBlock(beaconRoot: ByteString, timestamp: Long = CancunTs): Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        unixTimestamp = Timestamp(timestamp),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = HefPostCancun(
          baseFee = BigInt(1),
          withdrawalsRoot = ByteString(Array.fill(32)(0x00.toByte)),
          blobGasUsed = BigInt(0),
          excessBlobGas = BigInt(0),
          parentBeaconBlockRoot = beaconRoot
        )
      ),
      body = BlockBody(Nil, Nil)
    )

    def runBlock(block: Block, world: InMemoryWorldStateProxy = deployedWorld): InMemoryWorldStateProxy =
      exec.executeBlockTransactions(block, world).toOption.get.worldState

  // EIP-4788 Block processing: "if no code exists at BEACON_ROOTS_ADDRESS, the call must fail
  // silently". go-ethereum's SYSTEM_ADDRESS call carries value 0, and under EIP-158 a zero-value
  // call to a non-existent account returns before CreateAccount (core/vm/evm.go Call) — so the
  // reference client leaves NO trace. Seeding code + nonce here forked the state root at the first
  // post-Cancun block of any chain whose genesis omits the account (hive ethereum/graphql, block 34).
  "EIP-4788 beacon roots" should "NOT deploy the contract when no code exists at the address" taggedAs (
    EthereumTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy =
      runBlock(makeBlock(ByteString(Array.fill(32)(0xab.toByte))), emptyWorld)

    world.getCode(BeaconRootContractAddress) shouldBe ByteString.empty
    world.getAccount(BeaconRootContractAddress) shouldBe None

    val ts: BigInt = BigInt(CancunTs) % BeaconRootHistoryBufferLength
    world.getStorage(BeaconRootContractAddress).load(ts) shouldBe BigInt(0)
    world.getStorage(BeaconRootContractAddress).load(ts + BeaconRootHistoryBufferLength) shouldBe BigInt(0)

  it should "write the timestamp and beacon root to the ring-buffer storage slots" taggedAs (
    EthereumTest,
    ConsensusTest
  ) in new TestSetup:
    val beaconRoot: ByteString = ByteString(Array.fill(32)(0xcd.toByte))
    val world: InMemoryWorldStateProxy = runBlock(makeBlock(beaconRoot, CancunTs))

    val timestampIdx: BigInt = BigInt(CancunTs) % BeaconRootHistoryBufferLength
    val rootIdx: BigInt = timestampIdx + BeaconRootHistoryBufferLength
    val storage = world.getStorage(BeaconRootContractAddress)
    storage.load(timestampIdx) shouldBe BigInt(CancunTs)
    storage.load(rootIdx) shouldBe UInt256(beaconRoot).toBigInt

  it should "leave the deployed contract's code and nonce untouched across Cancun blocks" taggedAs (
    EthereumTest,
    ConsensusTest
  ) in new TestSetup:
    val root1: ByteString = ByteString(Array.fill(32)(0x11.toByte))
    val root2: ByteString = ByteString(Array.fill(32)(0x22.toByte))

    val world1: InMemoryWorldStateProxy = runBlock(makeBlock(root1, CancunTs))
    val world2: InMemoryWorldStateProxy = runBlock(makeBlock(root2, CancunTs + 12L), world1)

    world2.getCode(BeaconRootContractAddress) shouldBe BeaconRootsCode
    world2.getAccount(BeaconRootContractAddress).map(_.nonce) shouldBe Some(UInt256(1))

  it should "NOT deploy the contract for blocks where parentBeaconBlockRoot is absent" taggedAs (
    EthereumTest,
    ConsensusTest
  ) in new TestSetup:
    // A block without parentBeaconBlockRoot (e.g. ETC or pre-Cancun) — the pattern guard fires None.
    val block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        unixTimestamp = Timestamp(CancunTs),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero
        // HefEmpty by default — no parentBeaconBlockRoot
      ),
      body = BlockBody(Nil, Nil)
    )
    val world: InMemoryWorldStateProxy = runBlock(block, emptyWorld)

    world.getCode(BeaconRootContractAddress) shouldBe ByteString.empty
    world.getAccount(BeaconRootContractAddress) shouldBe None

  "BeaconRootsCode" should "start with the CALLER opcode (0x33) per EIP-4788 spec" taggedAs (
    EthereumTest,
    ConsensusTest
  ) in {
    BeaconRootsCode should not be empty
    BeaconRootsCode.head shouldBe 0x33.toByte
  }

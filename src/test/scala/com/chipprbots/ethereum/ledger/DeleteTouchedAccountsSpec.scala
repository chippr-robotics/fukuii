package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks.MockVM
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm
import com.chipprbots.ethereum.vm.EvmConfig

class DeleteTouchedAccountsSpec extends AnyFlatSpec with Matchers:

  val blockchainConfig = Config.blockchains.blockchainConfig
  val syncConfig: SyncConfig = SyncConfig(Config.config)

  it should "delete no accounts when there are no touched accounts" taggedAs (UnitTest, StateTest) in new TestSetup:
    val newWorld: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(mining.blockPreparator.deleteEmptyTouchedAccounts(worldStatePostEIP161))
    accountAddresses.foreach(a => assert(newWorld.getAccount(a).isDefined))
    newWorld.stateRootHash shouldBe worldStatePostEIP161.stateRootHash

  it should "delete no accounts when there are no empty touched accounts" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    val worldAfterTransfer: InMemoryWorldStateProxy =
      worldStatePostEIP161.transfer(validAccountAddress, validAccountAddress2, transferBalance)
    worldAfterTransfer.touchedAccounts.size shouldEqual 2

    val newWorld: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(mining.blockPreparator.deleteEmptyTouchedAccounts(worldAfterTransfer))
    accountAddresses.foreach(a => assert(newWorld.getAccount(a).isDefined))

  it should "delete touched empty account" taggedAs (UnitTest, StateTest) in new TestSetup:
    val worldAfterTransfer: InMemoryWorldStateProxy =
      worldStatePostEIP161.transfer(validAccountAddress, validEmptyAccountAddress, zeroTransferBalance)
    worldAfterTransfer.touchedAccounts.size shouldEqual 2

    val newWorld: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(mining.blockPreparator.deleteEmptyTouchedAccounts(worldAfterTransfer))

    (accountAddresses - validEmptyAccountAddress).foreach(a => assert(newWorld.getAccount(a).isDefined))
    newWorld.getAccount(validEmptyAccountAddress) shouldBe None
    newWorld.touchedAccounts.size shouldEqual 0

  it should "delete touched empty account after transfer to self" in new TestSetup:
    val worldAfterTransfer: InMemoryWorldStateProxy =
      worldStatePostEIP161.transfer(validEmptyAccountAddress, validEmptyAccountAddress, zeroTransferBalance)
    worldAfterTransfer.touchedAccounts.size shouldEqual 1

    val newWorld: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(mining.blockPreparator.deleteEmptyTouchedAccounts(worldAfterTransfer))

    (accountAddresses - validEmptyAccountAddress).foreach(a => assert(newWorld.getAccount(a).isDefined))
    newWorld.getAccount(validEmptyAccountAddress) shouldBe None
    newWorld.touchedAccounts.size shouldEqual 0

  it should "not mark for deletion and delete any account pre EIP161" in new TestSetup:
    val worldAfterTransfer: InMemoryWorldStateProxy =
      worldStatePreEIP161.transfer(validAccountAddress, validEmptyAccountAddress, zeroTransferBalance)
    worldAfterTransfer.touchedAccounts.size shouldEqual 0

    val worldAfterPayingToMiner: InMemoryWorldStateProxy =
      mining.blockPreparator.pay(validEmptyAccountAddress1, zeroTransferBalance, withTouch = true)(
        worldAfterTransfer
      )

    worldAfterPayingToMiner.touchedAccounts.size shouldEqual 0

    val newWorld: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(mining.blockPreparator.deleteEmptyTouchedAccounts(worldAfterTransfer))

    accountAddresses.foreach(a => assert(newWorld.getAccount(a).isDefined))

  it should "delete multiple touched empty accounts" in new TestSetup:
    val worldAfterTransfer: InMemoryWorldStateProxy =
      worldStatePostEIP161.transfer(validAccountAddress, validEmptyAccountAddress, zeroTransferBalance)
    worldAfterTransfer.touchedAccounts.size shouldEqual 2

    val worldAfterPayingToMiner: InMemoryWorldStateProxy =
      mining.blockPreparator.pay(validEmptyAccountAddress1, zeroTransferBalance, withTouch = true)(
        worldAfterTransfer
      )

    worldAfterPayingToMiner.touchedAccounts.size shouldEqual 3

    val newWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      mining.blockPreparator.deleteEmptyTouchedAccounts(worldAfterPayingToMiner)
    )

    (accountAddresses -- Set(validEmptyAccountAddress, validEmptyAccountAddress1)).foreach { a =>
      assert(newWorld.getAccount(a).isDefined)
    }
    newWorld.getAccount(validEmptyAccountAddress) shouldBe None
    newWorld.getAccount(validEmptyAccountAddress1) shouldBe None
    newWorld.touchedAccounts.size shouldEqual 0

  it should "not delete touched new account resulting from contract creation (initialised)" in new TestSetup:
    val worldAfterInitAndTransfer: InMemoryWorldStateProxy =
      worldStatePostEIP161
        .initialiseAccount(validCreatedAccountAddress)
        .transfer(validAccountAddress, validCreatedAccountAddress, zeroTransferBalance)

    worldAfterInitAndTransfer.touchedAccounts.size shouldEqual 2

    val newWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      mining.blockPreparator.deleteEmptyTouchedAccounts(worldAfterInitAndTransfer)
    )

    (accountAddresses + validCreatedAccountAddress).foreach(a => assert(newWorld.getAccount(a).isDefined))
    newWorld.touchedAccounts.size shouldEqual 0

  // Pre-EIP-161 a zero-value fee payment to a non-existent miner CREATES the (empty) account: core-geth
  // `StateDB.AddBalance` -> `getOrNewStateObject`, Besu `worldState.getOrCreate(miningBeneficiary)`. Nothing clears
  // it before EIP-161, so it is in the intermediate state root a pre-Byzantium receipt commits to
  // (ethereum/legacytests callOutput*/callcodeOutput*_Frontier: gasPrice 0, coinbase absent from pre).
  it should "materialise a non-existent miner on a zero-value fee payment pre EIP161" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    worldStatePreEIP161.getAccount(nonExistentAddress) shouldBe None

    val newWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      mining.blockPreparator.pay(nonExistentAddress, zeroTransferBalance, withTouch = true)(worldStatePreEIP161)
    )

    newWorld.getAccount(nonExistentAddress) shouldBe Some(Account.empty())
    newWorld.touchedAccounts.size shouldEqual 0
    newWorld.stateRootHash shouldBe
      InMemoryWorldStateProxy
        .persistState(worldStatePreEIP161.saveAccount(nonExistentAddress, Account.empty()))
        .stateRootHash
    newWorld.stateRootHash should not be worldStatePreEIP161.stateRootHash

  it should "not materialise a non-existent miner on a zero-value fee payment post EIP161" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    val newWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      mining.blockPreparator.deleteEmptyTouchedAccounts(
        mining.blockPreparator.pay(nonExistentAddress, zeroTransferBalance, withTouch = true)(worldStatePostEIP161)
      )
    )

    newWorld.getAccount(nonExistentAddress) shouldBe None
    newWorld.stateRootHash shouldBe worldStatePostEIP161.stateRootHash

  it should "leave an existing account unchanged on a zero-value fee payment pre EIP161" taggedAs (
    UnitTest,
    StateTest
  ) in new TestSetup:
    val newWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(
      mining.blockPreparator.pay(validAccountAddress, zeroTransferBalance, withTouch = true)(worldStatePreEIP161)
    )

    newWorld.getAccount(validAccountAddress) shouldBe worldStatePreEIP161.getAccount(validAccountAddress)
    newWorld.stateRootHash shouldBe worldStatePreEIP161.stateRootHash

  // scalastyle:off magic.number
  trait TestSetup extends EphemBlockchainTestSetup:
    // + cake overrides
    override lazy val vm: VMImpl = new MockVM()

    // - cake overrides

    val conf: BlockchainConfigForEvm = BlockchainConfigForEvm(DeleteTouchedAccountsSpec.this.blockchainConfig)
    val postEip161Config: EvmConfig = EvmConfig.PostEIP161ConfigBuilder(conf)
    val postEip160Config: EvmConfig = EvmConfig.PostEIP160ConfigBuilder(conf)

    val validAccountAddress: Address = Address(0xababab)
    val validAccountBalance = 10

    val validAccountAddress2: Address = Address(0xcdcdcd)
    val validAccountAddress3: Address = Address(0xefefef)
    val validEmptyAccountAddress: Address = Address(0xaaaaaa)
    val validEmptyAccountAddress1: Address = Address(0xbbbbbb)

    val validCreatedAccountAddress: Address = Address(0xcccccc)
    val nonExistentAddress: Address = Address(0xdddddd)

    val accountAddresses: Set[Address] = Set(
      validAccountAddress,
      validAccountAddress2,
      validAccountAddress3,
      validEmptyAccountAddress,
      validEmptyAccountAddress1
    )

    val worldStateWithoutPersist: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = postEip161Config.noEmptyAccounts,
      ethCompatibleStorage = true
    )
      .saveAccount(validAccountAddress, Account(balance = validAccountBalance))
      .saveAccount(validAccountAddress2, Account(balance = 20))
      .saveAccount(validAccountAddress3, Account(balance = 30))
      .saveAccount(validEmptyAccountAddress, Account.empty())
      .saveAccount(validEmptyAccountAddress1, Account.empty())

    val worldStateWithoutPersistPreEIP161: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = postEip160Config.noEmptyAccounts,
      ethCompatibleStorage = true
    )
      .saveAccount(validAccountAddress, Account(balance = validAccountBalance))
      .saveAccount(validAccountAddress2, Account(balance = 20))
      .saveAccount(validAccountAddress3, Account(balance = 30))
      .saveAccount(validEmptyAccountAddress, Account.empty())
      .saveAccount(validEmptyAccountAddress1, Account.empty())

    val transferBalance = 5
    val zeroTransferBalance = 0

    val worldStatePostEIP161: InMemoryWorldStateProxy = InMemoryWorldStateProxy.persistState(worldStateWithoutPersist)
    val worldStatePreEIP161: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(worldStateWithoutPersistPreEIP161)

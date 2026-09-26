package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.ledger.BlockExecution.WithdrawalQueueAddress
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** The gas a SYSTEM_ADDRESS call runs with. From Amsterdam, EIP-8037 gives every system call a state-gas reservoir of
  * 16 x GAS_STORAGE_SET = 1,566,720 BESIDE its unchanged 30,000,000 execution grant (execution-specs
  * `process_unchecked_system_transaction`: `execution_gas_grant = SYSTEM_TRANSACTION_GAS`, `state_gas_reservoir =
  * STORAGE_SET * SYSTEM_MAX_SSTORES_PER_CALL`; go-ethereum `systemCallGasBudget`).
  *
  * The probes replace a system contract's code and read GAS, which is what EEST's `test_state_gas_system_calls` does.
  * Their figures are derived by hand from the opcode costs and stated with each test. The previous budget — the
  * 31,566,720 total granted as execution gas, no reservoir — gives different GAS readings in both Amsterdam cases.
  */
// scalastyle:off magic.number
class AmsterdamSystemCallSpec extends AnyFlatSpec with Matchers:

  private val AmsterdamTs: Long = 360L
  private val OsakaTs: Long = 359L

  trait Setup extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig =
      Config.blockchains.blockchainConfig
        .copy(
          networkType = NetworkType.ETH,
          forkTimestamps = ForkTimestamps(
            shanghaiTimestamp = Some(0L),
            cancunTimestamp = Some(60L),
            pragueTimestamp = Some(120L),
            osakaTimestamp = Some(180L),
            amsterdamTimestamp = Some(AmsterdamTs)
          )
        )
        .withUpdatedForkBlocks(
          _.copy(
            frontierBlockNumber = 0,
            homesteadBlockNumber = 0,
            eip106BlockNumber = 0,
            eip150BlockNumber = 0,
            eip155BlockNumber = 0,
            eip160BlockNumber = 0,
            eip161BlockNumber = 0,
            byzantiumBlockNumber = 0,
            constantinopleBlockNumber = 0,
            petersburgBlockNumber = 0,
            istanbulBlockNumber = 0,
            berlinBlockNumber = 0,
            muirGlacierBlockNumber = 0,
            olympiaBlockNumber = 0,
            atlantisBlockNumber = BigInt(Long.MaxValue),
            aghartaBlockNumber = BigInt(Long.MaxValue),
            phoenixBlockNumber = BigInt(Long.MaxValue),
            magnetoBlockNumber = BigInt(Long.MaxValue),
            mystiqueBlockNumber = BigInt(Long.MaxValue),
            spiralBlockNumber = BigInt(Long.MaxValue),
            ecip1099BlockNumber = BigInt(Long.MaxValue)
          )
        )

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

    def withCode(world: InMemoryWorldStateProxy, address: Address, code: ByteString): InMemoryWorldStateProxy =
      world
        .saveAccount(
          address,
          Account(nonce = UInt256(1), balance = UInt256.Zero, codeHash = CodeHash(crypto.kec256(code)))
        )
        .saveCode(address, code)

    private val zero32 = ByteString(Array.fill[Byte](32)(0))

    /** A 23-field header at `AmsterdamTs` or later; a 21-field one before it. */
    def block(timestamp: Long, parentBeaconBlockRoot: ByteString = zero32): Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(36),
        difficulty = Difficulty(0),
        gasLimit = GasAmount(30_000_000),
        gasUsed = GasAmount.Zero,
        beneficiary = Address(0xcafe).bytes,
        unixTimestamp = Timestamp(timestamp),
        extraFields =
          if timestamp >= AmsterdamTs then
            HefPostAmsterdam(7, zero32, 0, 0, parentBeaconBlockRoot, zero32, zero32, slotNumber = 36)
          else HefPostPrague(7, zero32, 0, 0, parentBeaconBlockRoot, zero32)
      ),
      body = BlockBody(Nil, Nil)
    )

    def storageOf(world: InMemoryWorldStateProxy, address: Address, slot: Int): BigInt =
      world.getStorage(address).load(UInt256(slot))

    /** Runs the end-of-block request system calls; only `address` has code, so only it is called. */
    def requestSystemCall(timestamp: Long, address: Address, code: ByteString): InMemoryWorldStateProxy =
      val (world, requests) =
        exec.processPragueSystemCallsChecked(block(timestamp), withCode(emptyWorld, address, code)).toOption.get
      requests shouldBe empty
      world

  // GAS PUSH1 0 SSTORE: stores the gas left after GAS itself.
  private val StoreGas = ByteString(Hex.decode("5a600055"))

  // PUSH1 1 PUSH1 0 SSTORE (a fresh slot) GAS PUSH1 1 SSTORE.
  private val SetThenStoreGas = ByteString(Hex.decode("60016000555a600155"))

  "a system call" should "have 30,000,000 of execution gas at Amsterdam, not the 31,566,720 total" taggedAs (
    UnitTest,
    StateTest
  ) in new Setup:
    // GAS costs 2: 30,000,000 - 2. The old budget pushed 31,566,718.
    storageOf(requestSystemCall(AmsterdamTs, WithdrawalQueueAddress, StoreGas), WithdrawalQueueAddress, 0) shouldBe
      BigInt(29999998)
    // Osaka: the same 30,000,000.
    storageOf(requestSystemCall(OsakaTs, WithdrawalQueueAddress, StoreGas), WithdrawalQueueAddress, 0) shouldBe
      BigInt(29999998)

  it should "fund its state charges from the reservoir, leaving the execution grant untouched" taggedAs (
    UnitTest,
    StateTest
  ) in new Setup:
    // Amsterdam: 30,000,000 - 6 (two PUSH1) - 12,100 (SSTORE: COLD_STORAGE_ACCESS 2,100 + STORAGE_WRITE 10,000; its
    // 97,920 of state gas comes out of the 1,566,720 reservoir) - 2 (GAS) = 29,987,892. With no reservoir the state
    // charge would have come out of the gas GAS reads: the old budget gives 31,566,720 - 6 - 12,100 - 97,920 - 2 =
    // 31,456,692.
    val amsterdam = requestSystemCall(AmsterdamTs, WithdrawalQueueAddress, SetThenStoreGas)
    storageOf(amsterdam, WithdrawalQueueAddress, 0) shouldBe BigInt(1)
    storageOf(amsterdam, WithdrawalQueueAddress, 1) shouldBe BigInt(29987892)
    // Osaka: no state gas; the fresh SSTORE is EIP-2929 cold 2,100 + SSTORE_SET 20,000 = 22,100.
    // 30,000,000 - 6 - 22,100 - 2 = 29,977,892.
    val osaka = requestSystemCall(OsakaTs, WithdrawalQueueAddress, SetThenStoreGas)
    storageOf(osaka, WithdrawalQueueAddress, 1) shouldBe BigInt(29977892)

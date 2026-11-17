package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.MockWorldState.*

/** #1727 PR 7a: which error a creation's code deposit reports when it fails.
  *
  * Three checks follow a creation's init code (`VM.saveNewContract`): EIP-3541's 0xEF prefix, the code size limit
  * (EIP-170; ETC from Atlantis) and the deposit's gas. They end in the same exceptional halt (all the frame's gas
  * consumed, its state and state gas rolled back), so consensus does not see which one failed. Only the RPC does, and
  * it reports go-ethereum's error, chosen in go-ethereum's order (core/vm/evm.go `initNewContract`), on ETC as on ETH:
  *   - 0xEF first, on every fork that has EIP-3541;
  *   - then, before Amsterdam, the deposit's gas (`ErrCodeStoreOutOfGas`) and then the size (`ErrMaxCodeSizeExceeded`);
  *   - at Amsterdam, the size before the deposit is charged.
  *
  * core-geth checks the size first on every fork; #1727 takes go-ethereum's order for both chains.
  *
  * Before Homestead a deposit the frame cannot pay is not an error at all: pinned by `FrontierCreateCodeDepositSpec`,
  * and checked here only to show the new names never reach it.
  */
// scalastyle:off magic.number
class CreateErrorPrecedenceSpec extends AnyFlatSpec with Matchers:

  private val vm: TestVM = new VM[MockWorldState, MockStorage]()

  /** Every ETC fork at block 0 and the code size limit at 16 bytes, which `exceedsMaxContractSize` applies from the
    * EIP-161 or the Atlantis block. The configs below differ only in the builder.
    */
  private val forEvm: BlockchainConfigForEvm = BlockchainConfigForEvm(
    frontierBlockNumber = 0,
    homesteadBlockNumber = 0,
    eip150BlockNumber = 0,
    eip160BlockNumber = 0,
    eip161BlockNumber = 0,
    byzantiumBlockNumber = 0,
    constantinopleBlockNumber = 0,
    istanbulBlockNumber = 0,
    maxCodeSize = Some(16),
    accountStartNonce = 0,
    atlantisBlockNumber = 0,
    aghartaBlockNumber = 0,
    petersburgBlockNumber = 0,
    phoenixBlockNumber = 0,
    magnetoBlockNumber = 0,
    berlinBlockNumber = 0,
    mystiqueBlockNumber = 0,
    spiralBlockNumber = 0,
    olympiaBlockNumber = 0,
    chainId = ChainId(0x3d)
  )

  private val frontier: EvmConfig = EvmConfig.FrontierConfigBuilder(forEvm)
  private val etcAtlantis: EvmConfig = EvmConfig.AtlantisConfigBuilder(forEvm) // no EIP-3541
  private val etcMystique: EvmConfig = EvmConfig.MystiqueConfigBuilder(forEvm) // EIP-3541
  private val ethLondon: EvmConfig = EvmConfig.LondonConfigBuilder(forEvm.copy(chainId = ChainId(1)))
  private val ethAmsterdam: EvmConfig = ethLondon.copy(
    opCodeList = EvmConfig.AmsterdamOpCodes,
    feeSchedule = new FeeSchedule.AmsterdamFeeSchedule,
    amsterdamEnabled = true
  )
  private val preAmsterdam: Seq[(String, EvmConfig)] =
    Seq("ETC Atlantis" -> etcAtlantis, "ETC Mystique" -> etcMystique, "ETH London" -> ethLondon)

  private val header: BlockHeader =
    ValidBlock.header.copy(number = BlockNumber(1), gasLimit = GasAmount(10000000), unixTimestamp = Timestamp(0))

  private val sender: Address = Address(0xcafebabeL)
  private val world: MockWorldState = MockWorldState().saveAccount(sender, Account(nonce = 1, balance = 1000000))
  private val newAddress: Address = world.createAddress(sender)

  /** Init code returning `size` bytes whose first is `first`: MSTORE8(0, first), RETURN(0, size). About 20 gas, plus
    * the memory for a large `size`.
    */
  private def initCode(first: Int, size: Int): ByteString =
    ByteString(
      Array(0x60, first, 0x60, 0x00, 0x53, 0x62, size >> 16, (size >> 8) & 0xff, size & 0xff, 0x60, 0x00, 0xf3)
        .map(_.toByte)
    )

  private def create(config: EvmConfig, first: Int, size: Int, gas: BigInt): PR =
    vm.run(
      ProgramContext(
        callerAddr = sender,
        originAddr = sender,
        recipientAddr = None,
        gasPrice = 1,
        startGas = gas,
        inputData = initCode(first, size),
        value = 0,
        endowment = 0,
        doTransfer = true,
        blockHeader = header,
        callDepth = 0,
        world = world,
        initialAddressesToDelete = Set(),
        evmConfig = config,
        originalWorld = world,
        warmAddresses = Set.empty,
        warmStorage = Set.empty
      )
    )

  /** The halt every deposit failure is: the named error, no gas left, no code stored. */
  private def failsWith(result: PR, error: ProgramError): Assertion =
    result.error shouldBe Some(error)
    result.gasRemaining shouldBe 0
    result.world.getCode(newAddress) shouldBe ByteString.empty
    result.codeDepositShortfall shouldBe false
    error.useWholeGas shouldBe true
    error should not be InvalidCall
    error should not be RevertOccurs

  // Pre-Amsterdam: 17 bytes break the 16-byte limit; their deposit is 200 * 17 = 3,400, 16 bytes' 3,200.
  // Amsterdam: the limit is 65,536 (EIP-7954) and the deposit 6 per word plus 1,530 state gas per byte.
  private val ShortGas: BigInt = 2000 // runs the init code, pays no deposit
  private val AmpleGas: BigInt = 1000000

  "A creation failing on its 0xEF prefix" should "report invalid code before the size and the deposit" taggedAs (
    UnitTest,
    VMTest
  ) in {
    for (name, config) <- Seq("ETC Mystique" -> etcMystique, "ETH London" -> ethLondon) do
      withClue(s"$name: ") {
        failsWith(create(config, first = 0xef, size = 17, gas = ShortGas), InvalidCode)
        failsWith(create(config, first = 0xef, size = 17, gas = AmpleGas), InvalidCode)
      }
    failsWith(create(ethAmsterdam, first = 0xef, size = 65537, gas = AmpleGas), InvalidCode)
  }

  "A creation too large to deposit and too short of gas to pay for it" should
    "report the deposit before Amsterdam, as go-ethereum charges first" taggedAs (UnitTest, VMTest) in {
      for (name, config) <- preAmsterdam do
        withClue(s"$name: ") {
          failsWith(create(config, first = 0, size = 17, gas = ShortGas), CodeStoreOutOfGas)
        }
      // without EIP-3541 a 0xEF prefix is ordinary code, so the deposit still comes first
      failsWith(create(etcAtlantis, first = 0xef, size = 17, gas = ShortGas), CodeStoreOutOfGas)
    }

  it should "report the size at Amsterdam, which checks it before charging" taggedAs (UnitTest, VMTest) in {
    failsWith(create(ethAmsterdam, first = 0, size = 65537, gas = AmpleGas), MaxCodeSizeExceeded)
  }

  "A creation too large, with the gas to pay for it" should "report the size" taggedAs (UnitTest, VMTest) in {
    for (name, config) <- preAmsterdam do
      withClue(s"$name: ") {
        failsWith(create(config, first = 0, size = 17, gas = AmpleGas), MaxCodeSizeExceeded)
      }
    failsWith(create(ethAmsterdam, first = 0, size = 65537, gas = BigInt(200000000)), MaxCodeSizeExceeded)
  }

  "A creation of allowed size short of gas for its deposit" should "report the deposit, from Homestead on" taggedAs (
    UnitTest,
    VMTest
  ) in {
    for (name, config) <- preAmsterdam :+ ("ETH Amsterdam" -> ethAmsterdam) do
      withClue(s"$name: ") {
        failsWith(create(config, first = 0, size = 16, gas = ShortGas), CodeStoreOutOfGas)
      }
  }

  it should "still succeed before Homestead, storing no code and keeping its gas" taggedAs (UnitTest, VMTest) in {
    frontier.exceptionalFailedCodeDeposit shouldBe false
    val result = create(frontier, first = 0, size = 16, gas = ShortGas)
    result.error shouldBe None
    result.codeDepositShortfall shouldBe true
    result.gasRemaining should be > BigInt(0)
    result.world.getCode(newAddress) shouldBe ByteString.empty
  }

  "A creation that passes every check" should "store its code, the control for the cases above" taggedAs (
    UnitTest,
    VMTest
  ) in {
    for (name, config) <- preAmsterdam :+ ("ETH Amsterdam" -> ethAmsterdam) do
      withClue(s"$name: ") {
        val result = create(config, first = 0, size = 16, gas = AmpleGas)
        result.error shouldBe None
        result.world.getCode(newAddress).size shouldBe 16
      }
  }

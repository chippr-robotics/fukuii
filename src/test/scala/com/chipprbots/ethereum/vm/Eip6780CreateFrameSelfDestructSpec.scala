package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

import Fixtures.blockchainConfig

/** EIP-6780 regression: SELFDESTRUCT executed *inside the initcode of the CREATE that made the account* must still
  * delete the account, because the account was created in this transaction.
  *
  * Why this is not covered by OlympiaSelfDestructSpec: that spec hand-builds `originalWorld` and never routes through
  * `VM.create`. `VM.create` replaces the frame's `originalWorld` with `originalWorld.initialiseAccount(contractAddr)`
  * (needed for EIP-1283/2200 original-value lookups), which makes the new address *exist* in `originalWorld`. The
  * EIP-6780 predicate `!state.originalWorld.accountExists(ownAddress)` then reads false and the account survives.
  *
  * Observed on hive devp2p block 8 (hivechain `tx-calltree`, timestamp 80 -> Cancun): gas matched byte-for-byte but the
  * state root diverged because the CREATE'd + self-destructed child stayed in the trie with nonce=1.
  *
  * Reference: go-ethereum core/vm/instructions.go opSelfdestruct6780 -> StateDB.SelfDestruct6780, which keys off the
  * `newContract` flag set by StateDB.CreateContract during the CREATE itself.
  */
class Eip6780CreateFrameSelfDestructSpec extends AnyWordSpec with Matchers:

  private val configEip6780: EvmConfig = EvmConfig.OlympiaConfigBuilder(blockchainConfig)
  private val configPre6780: EvmConfig = EvmConfig.SpiralConfigBuilder(blockchainConfig)

  private val header: BlockHeader =
    BlockFixtures.ValidBlock.header.copy(number = BlockNumber(Fixtures.OlympiaBlockNumber))

  private val creator: Address = Address(0xca11)
  private val beneficiary: Address = Address(0xface)

  /** initcode: PUSH20 <beneficiary>; SELFDESTRUCT — destroys the account before any code is deposited. */
  private val initCodeSelfDestruct: ByteString = Assembly(PUSH20, beneficiary.bytes, SELFDESTRUCT).code

  private def world: MockWorldState = MockWorldState(noEmptyAccountsCond = true)
    .saveAccount(creator, Account(balance = UInt256(1000), nonce = 1))
    .saveAccount(beneficiary, Account(balance = UInt256(0)))

  private def createContext(config: EvmConfig): ProgramContext[MockWorldState, MockStorage] =
    ProgramContext(
      callerAddr = creator,
      originAddr = creator,
      recipientAddr = None, // contract-creation transaction
      gasPrice = 1,
      startGas = 1_000_000,
      inputData = initCodeSelfDestruct,
      value = UInt256.Zero,
      endowment = UInt256.Zero,
      doTransfer = true,
      blockHeader = header,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = config,
      originalWorld = world,
      warmAddresses = Set(creator),
      warmStorage = Set.empty
    )

  "SELFDESTRUCT inside the creating frame's initcode" when {

    "EIP-6780 is enabled (ETH Cancun / ETC Olympia)" should {

      "mark the freshly created account for deletion" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        val expectedAddr = world.createAddress(creator)
        val result = new VM[MockWorldState, MockStorage].run(createContext(configEip6780))

        result.error shouldBe None
        withClue(s"created account $expectedAddr must be deleted (created in this tx per EIP-6780): ") {
          result.addressesToDelete should contain(expectedAddr)
        }
      }

      "leave no account behind once addressesToDelete is applied" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        val expectedAddr = world.createAddress(creator)
        val result = new VM[MockWorldState, MockStorage].run(createContext(configEip6780))

        val finalWorld = result.addressesToDelete.foldLeft(result.world)(_.deleteAccount(_))
        finalWorld.getAccount(expectedAddr) shouldBe None
      }
    }

    "reached through a nested CREATE from a pre-existing contract (the hive block-8 shape)" should {

      "delete the child and leave the parent alone" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        // calltree section 8: a long-lived contract CREATEs a child whose initcode LOGs and SELFDESTRUCTs to
        // its caller. The parent is pre-existing (NOT deletable); the child is created in this tx (deletable).
        val parent: Address = Address(0xbeef)

        // initcode: PUSH20 <parent>; SELFDESTRUCT  (22 bytes, beneficiary == the creating contract)
        val childInitCode: ByteString = Assembly(PUSH20, parent.bytes, SELFDESTRUCT).code
        val initCodeWord: ByteString = childInitCode ++ ByteString(Array.fill(32 - childInitCode.length)(0.toByte))

        // parent code: MSTORE(0, initCodeWord); CREATE(value=0, offset=0, size=22); STOP
        val parentCode: ByteString = Assembly(
          PUSH32,
          initCodeWord,
          PUSH1,
          0,
          MSTORE,
          PUSH1,
          childInitCode.length,
          PUSH1,
          0,
          PUSH1,
          0,
          CREATE,
          STOP
        ).code

        val w = MockWorldState(noEmptyAccountsCond = true)
          .saveAccount(creator, Account(balance = UInt256(1000), nonce = 1))
          .saveAccount(parent, Account(balance = UInt256(500), nonce = 1))
          .saveCode(parent, parentCode)

        // CreateOp bumps the creator's nonce *before* deriving the address (YP eq. 82 uses the pre-bump value,
        // and WorldStateProxy.createAddress reads `nonce - 1`), so derive from the post-bump world.
        val childAddr = w.increaseNonce(parent).createAddress(parent)

        val context = ProgramContext[MockWorldState, MockStorage](
          callerAddr = creator,
          originAddr = creator,
          recipientAddr = Some(parent),
          gasPrice = 1,
          startGas = 1_000_000,
          inputData = ByteString.empty,
          value = UInt256.Zero,
          endowment = UInt256.Zero,
          doTransfer = true,
          blockHeader = header,
          callDepth = 0,
          world = w,
          initialAddressesToDelete = Set(),
          evmConfig = configEip6780,
          originalWorld = w,
          warmAddresses = Set(creator, parent),
          warmStorage = Set.empty
        )

        val result = new VM[MockWorldState, MockStorage].run(context)

        result.error shouldBe None
        withClue(s"child $childAddr was created in this tx and must be deleted: ") {
          result.addressesToDelete should contain(childAddr)
        }
        withClue("pre-existing parent must survive EIP-6780: ") {
          result.addressesToDelete should not contain parent
        }
      }
    }

    "EIP-6780 is disabled (pre-Cancun / pre-Olympia)" should {
      "still delete the created account (unconditional SELFDESTRUCT)" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        val expectedAddr = world.createAddress(creator)
        val result = new VM[MockWorldState, MockStorage].run(createContext(configPre6780))

        result.error shouldBe None
        result.addressesToDelete should contain(expectedAddr)
      }
    }
  }

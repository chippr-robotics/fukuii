package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

import Fixtures.blockchainConfig

/** EIP-7610: a CREATE whose target address has non-empty STORAGE (but zero nonce and no code) is an address collision.
  * The EIP is retroactive, so on ETH it applies on every fork — ethereum/legacytests
  * RevertInCreateInInit_d0g0v0_{Byzantium,Constantinople,ConstantinopleFix} expect the whole gas limit consumed. ETC
  * follows core-geth (core/vm/evm.go create: nonce/code only) on every fork.
  */
class Eip7610CreateCollisionSpec extends AnyWordSpec with Matchers:

  private val powHeader: BlockHeader = BlockFixtures.ValidBlock.header
  private val creator: Address = Address(0xca11)
  private val startGas: BigInt = 1_000_000

  /** initcode: PUSH1 1; PUSH1 0; SSTORE; STOP — succeeds and writes storage when there is no collision. */
  private val initCode: ByteString = Assembly(PUSH1, 1, PUSH1, 0, SSTORE, STOP).code

  private def world: MockWorldState =
    val base = MockWorldState(noEmptyAccountsCond = true).saveAccount(creator, Account(balance = 1000, nonce = 1))
    // The CREATE target: zero nonce, no code, non-empty storage root.
    base.saveAccount(base.createAddress(creator), Account(storageRoot = TrieRoot(kec256(ByteString("storage")))))

  private def run(config: EvmConfig): ProgramResult[MockWorldState, MockStorage] =
    val w = world
    new VM[MockWorldState, MockStorage].run(
      ProgramContext(
        callerAddr = creator,
        originAddr = creator,
        recipientAddr = None, // contract-creation transaction
        gasPrice = 1,
        startGas = startGas,
        inputData = initCode,
        value = UInt256.Zero,
        endowment = UInt256.Zero,
        doTransfer = true,
        blockHeader = powHeader,
        callDepth = 0,
        world = w,
        initialAddressesToDelete = Set(),
        evmConfig = config,
        originalWorld = w,
        warmAddresses = Set(creator),
        warmStorage = Set.empty
      )
    )

  "CREATE onto an address with storage but no nonce/code" should {

    "collide on ETH before Paris (Byzantium rules, PoW header) and consume all gas" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      powHeader.isPoS shouldBe false
      val result = run(EvmConfig.ByzantiumConfigBuilder(blockchainConfig.copy(isEthereum = true)))
      result.error shouldBe defined
      result.gasRemaining shouldBe 0
    }

    "not collide on ETC (Atlantis rules = Byzantium), matching core-geth" taggedAs (
      UnitTest,
      VMTest,
      ConsensusTest
    ) in {
      val result = run(EvmConfig.AtlantisConfigBuilder(blockchainConfig.copy(isEthereum = false)))
      result.error shouldBe None
      result.gasRemaining should be > BigInt(0)
    }
  }

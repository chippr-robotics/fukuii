package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.testing.Tags.*

/** `InMemoryWorldStateProxy.getCode` used to answer empty code for an account whose codeHash had no stored bytecode, so
  * a contract call ran as a call to an EOA: a different state, gas and EIP-7928 access list from every other client
  * (devnet-8 block 318074). It now throws, as a missing trie node does.
  */
class MissingContractCodeSpec extends AnyFlatSpec with Matchers:

  private val code = ByteString(Array[Byte](0x60, 0x01, 0x60, 0x00, 0x55, 0x00)) // PUSH1 1 PUSH1 0 SSTORE STOP
  private val codeHash = CodeHash(kec256(code))
  private val contract = Address(ByteString(Array.fill[Byte](20)(0x16)))
  private val eoa = Address(ByteString(Array.fill[Byte](20)(0x17)))

  private class Setup extends EphemBlockchainTestSetup:
    val emptyWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    // An account that names code, with the code NOT in EvmCodeStorage: what SNAP healing left behind.
    val world: InMemoryWorldStateProxy =
      emptyWorld
        .saveAccount(contract, Account(nonce = UInt256(1), codeHash = codeHash))
        .saveAccount(eoa, Account(balance = UInt256(5)))

  "InMemoryWorldStateProxy.getCode" should "throw MissingCodeException for a non-empty codeHash whose code is not stored" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val thrown = intercept[MissingCodeException](world.getCode(contract))
    thrown.hash shouldBe codeHash.value
    thrown.accountAddress shouldBe contract.bytes

  it should "be a MissingNodeException, so every missing-state consumer (engine SYNCING, importer fetch-and-retry) handles it" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    intercept[MissingNodeException](world.getCode(contract)).hash shouldBe codeHash.value

  it should "return the stored code once it is stored" taggedAs (UnitTest, ConsensusTest) in new Setup:
    storagesInstance.storages.evmCodeStorage.put(codeHash.value, code).commit()
    world.getCode(contract) shouldBe code

  it should "return the code of a contract created in the same world without consulting storage" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val created = Address(ByteString(Array.fill[Byte](20)(0x18)))
    emptyWorld.saveAccount(created, Account(codeHash = codeHash)).saveCode(created, code).getCode(created) shouldBe code

  it should "return empty code, without throwing, for an EOA and for an absent account" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    world.getCode(eoa) shouldBe ByteString.empty
    world.getCode(Address(ByteString(Array.fill[Byte](20)(0x19)))) shouldBe ByteString.empty

package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** spec 022 review vault F4: the callback that lets `snap.heal.datadirCreated` be written says the genesis was first
  * stored here. So it fires once, on the arm that stores it, and on neither arm that finds a genesis already there: a
  * datadir an older binary created must never get the key.
  */
class GenesisDataLoaderFirstStoredSpec extends AnyFlatSpec with Matchers:

  private def genesis(extraData: Byte, alloc: Map[String, GenesisAccount] = Map.empty): GenesisData =
    GenesisData(
      nonce = ByteString(Array.fill[Byte](8)(0)),
      mixHash = None,
      difficulty = "0x1",
      extraData = ByteString(Array(extraData)),
      gasLimit = "0x1000000",
      coinbase = ByteString(Array.fill[Byte](20)(0)),
      timestamp = "0x0",
      alloc = alloc
    )

  /** An account with the given storage, as a genesis file gives it. */
  private def account(storage: Option[Map[UInt256, UInt256]]): GenesisAccount =
    GenesisAccount(precompiled = None, balance = UInt256(1), code = None, nonce = None, storage = storage)

  private def address(n: Int): String = f"$n%02x" * 20

  private val oneSlot: Option[Map[UInt256, UInt256]] = Some(Map(UInt256(1) -> UInt256(2)))
  private val zeroSlot: Option[Map[UInt256, UInt256]] = Some(Map(UInt256(1) -> UInt256.Zero))

  /** A fresh datadir's loader, how often its callback has fired, and what it said about the counts. */
  private class Datadir extends EphemBlockchainTestSetup:
    var fired = 0
    var countsExact: Option[Boolean] = None
    val loader = new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage,
      exact =>
        fired += 1; countsExact = Some(exact)
    )

  "GenesisDataLoader" should "call back once, as it first stores the genesis" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    fired shouldBe 0
    loader.loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    blockchainReader.getBlockHeaderByNumber(0) shouldBe defined
    fired shouldBe 1

  it should "say its reference counts are exact for a genesis with no storage (#1692)" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    loader.loadGenesisData(genesis(1, Map(address(1) -> account(None), address(2) -> account(None)))) shouldBe a[
      Success[?]
    ]
    countsExact shouldBe Some(true)

  it should "say they are exact for a genesis with one account that has storage" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    loader.loadGenesisData(genesis(1, Map(address(1) -> account(oneSlot), address(2) -> account(None)))) shouldBe a[
      Success[?]
    ]
    countsExact shouldBe Some(true)

  it should "say they are NOT exact for a genesis with two accounts that have storage, which may share a subtree" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    // The same storage twice: the whole trie is shared, and written once at one reference for two positions.
    loader.loadGenesisData(genesis(1, Map(address(1) -> account(oneSlot), address(2) -> account(oneSlot)))) shouldBe a[
      Success[?]
    ]
    fired shouldBe 1
    countsExact shouldBe Some(false)

  it should "count only the slots that are stored: a slot of zero is not" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    loader.loadGenesisData(
      genesis(1, Map(address(1) -> account(oneSlot), address(2) -> account(zeroSlot), address(3) -> account(zeroSlot)))
    ) shouldBe a[Success[?]]
    countsExact shouldBe Some(true)

  it should "agree with GenesisDataLoader.countsExact" taggedAs UnitTest in {
    GenesisDataLoader.countsExact(genesis(1)) shouldBe true
    GenesisDataLoader.countsExact(genesis(1, Map(address(1) -> account(oneSlot)))) shouldBe true
    GenesisDataLoader.countsExact(
      genesis(1, Map(address(1) -> account(oneSlot), address(2) -> account(oneSlot)))
    ) shouldBe
      false
    GenesisDataLoader.countsExact(
      genesis(1, Map(address(1) -> account(oneSlot), address(2) -> account(zeroSlot)))
    ) shouldBe true
  }

  it should "not call back when the genesis is already in the database" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    loader.loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    loader.loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    fired shouldBe 1

  it should "not call back when the genesis in the database is another one" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    loader.loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    loader.loadGenesisData(genesis(2)) shouldBe a[Failure[?]]
    fired shouldBe 1

  it should "not call back for a datadir whose genesis another loader stored: an older binary, say" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    ).loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    loader.loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    fired shouldBe 0

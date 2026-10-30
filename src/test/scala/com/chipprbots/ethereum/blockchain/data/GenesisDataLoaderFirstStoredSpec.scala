package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** spec 022 review vault F4: the callback that lets `snap.heal.datadirCreated` be written says the genesis was first
  * stored here. So it fires once, on the arm that stores it, and on neither arm that finds a genesis already there: a
  * datadir an older binary created must never get the key.
  */
class GenesisDataLoaderFirstStoredSpec extends AnyFlatSpec with Matchers:

  private def genesis(extraData: Byte): GenesisData =
    GenesisData(
      nonce = ByteString(Array.fill[Byte](8)(0)),
      mixHash = None,
      difficulty = "0x1",
      extraData = ByteString(Array(extraData)),
      gasLimit = "0x1000000",
      coinbase = ByteString(Array.fill[Byte](20)(0)),
      timestamp = "0x0",
      alloc = Map.empty
    )

  /** A fresh datadir's loader, and how often its callback has fired. */
  private class Datadir extends EphemBlockchainTestSetup:
    var fired = 0
    val loader = new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage,
      () => fired += 1
    )

  "GenesisDataLoader" should "call back once, as it first stores the genesis" taggedAs UnitTest in new Datadir:
    implicit val config: com.chipprbots.ethereum.utils.BlockchainConfig = blockchainConfig
    fired shouldBe 0
    loader.loadGenesisData(genesis(1)) shouldBe a[Success[?]]
    blockchainReader.getBlockHeaderByNumber(0) shouldBe defined
    fired shouldBe 1

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

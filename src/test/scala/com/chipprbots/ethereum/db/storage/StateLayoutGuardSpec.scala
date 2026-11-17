package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.pathstate.StateLayout
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The layout guard (spec 018 data-model 5.3; T194): which data directories a build opens, with
  * `fukuii.state.path-live` on and off, and that it only reads.
  */
class StateLayoutGuardSpec extends AnyFlatSpec with Matchers:

  /** A data directory, opened with the guard on or off: the storages over one source. */
  final private class Opened(val source: EphemDataSource, pathLive: Boolean) extends EphemBlockchainTestSetup:
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = pathLive
    def storages = storagesInstance.storages
    def refusal: Option[String] =
      StateLayoutGuard.refusalOf(pathLive, storages.appStateStorage, storages.blockNumberMappingStorage)
    def check(): Unit = StateLayoutGuard.check(pathLive, storages.appStateStorage, storages.blockNumberMappingStorage)
    def loadGenesis(): Unit =
      GenesisDataLoader.over(blockchainReader, blockchainWriter, storages).loadGenesisData(genesisData).get
    def marker: Option[String] = storages.appStateStorage.getStateLayout()

  private val genesisData = GenesisData(
    nonce = ByteString(new Array[Byte](8)),
    mixHash = None,
    difficulty = "0x3e8",
    extraData = ByteString.empty,
    gasLimit = "0xf4240",
    coinbase = ByteString(new Array[Byte](20)),
    timestamp = "0xf4240",
    alloc = Map("00" * 19 + "01" -> GenesisAccount(None, UInt256(BigInt(10).pow(18)), None, None, None))
  )

  "The layout guard" should "open what the rule opens and refuse what it refuses, with the guard on and off" taggedAs
    UnitTest in {
      import StateLayoutGuard.refusal
      val marked = Some(StateLayout.PathLive1)
      // the guard on
      refusal(pathLive = true, marker = marked, empty = false) shouldBe None
      refusal(pathLive = true, marker = marked, empty = true) shouldBe None
      refusal(pathLive = true, marker = None, empty = true) shouldBe None
      refusal(pathLive = true, marker = None, empty = false).get should include("earlier storage layout")
      // a marker this build does not know, whatever else
      for empty <- Seq(true, false) do
        refusal(pathLive = true, marker = Some("path-live-2"), empty).get should include("does not know")
      // the guard off: a marked directory is refused, and the flag is named; an unmarked one is the hash layout's
      for empty <- Seq(true, false) do
        refusal(pathLive = false, marker = marked, empty).get should include("fukuii.state.path-live is off")
        refusal(pathLive = false, marker = Some("path-live-2"), empty).get should include(
          "fukuii.state.path-live is off"
        )
        refusal(pathLive = false, marker = None, empty) shouldBe None
    }

  it should "open an empty directory without writing to it: the marker is the genesis commit's" taggedAs UnitTest in {
    val source = EphemDataSource()
    val fresh = new Opened(source, pathLive = true)
    fresh.refusal shouldBe None
    fresh.check()
    // the guard stamped nothing: a node killed here starts again on an empty directory
    fresh.marker shouldBe None
    fresh.storages.appStateStorage.getBestBlockInfo() shouldBe BlockInfo(ByteString.empty, 0)
    fresh.storages.blockNumberMappingStorage.get(BigInt(0)) shouldBe None

    // the genesis commit that follows stamps it, and the directory is then the unified layout's
    fresh.loadGenesis()
    fresh.marker shouldBe Some(StateLayout.PathLive1)
    new Opened(source, pathLive = true).refusal shouldBe None
    // and is refused with the guard off, by name
    val off = new Opened(source, pathLive = false)
    off.refusal.get should include("fukuii.state.path-live is off")
    (the[StateLayoutGuard.Refused] thrownBy off.check()).why shouldBe off.refusal.get
  }

  it should "refuse, with the guard on, a directory the hash layout wrote: whatever it holds" taggedAs UnitTest in {
    // a genesis and nothing else
    val source = EphemDataSource()
    val hashed = new Opened(source, pathLive = false)
    hashed.refusal shouldBe None
    hashed.loadGenesis()
    hashed.marker shouldBe None
    hashed.refusal shouldBe None
    val on = new Opened(source, pathLive = true)
    on.refusal.get should include("earlier storage layout")
    a[StateLayoutGuard.Refused] should be thrownBy on.check()
    // whoever opened it closes it, and the refusal is what is thrown, whatever the close does
    var closed = 0
    val refused = the[StateLayoutGuard.Refused] thrownBy StateLayoutGuard.checkOrClose(
      pathLive = true,
      on.storages.appStateStorage,
      on.storages.blockNumberMappingStorage
    ) {
      closed += 1
      throw new IllegalStateException("the close failed")
    }
    closed shouldBe 1
    refused.getSuppressed.map(_.getMessage) shouldBe Array("the close failed")
    // one that is opened is not closed
    StateLayoutGuard.checkOrClose(
      pathLive = false,
      hashed.storages.appStateStorage,
      hashed.storages.blockNumberMappingStorage
    )(
      fail("closed a directory that was opened")
    )

    // a best block and no block at height 0, as a sync that began from a pivot leaves it
    val pivoted = new Opened(EphemDataSource(), pathLive = true)
    pivoted.storages.appStateStorage.putBestBlockInfo(BlockInfo(ByteString(Array.fill[Byte](32)(7)), 1000)).commit()
    pivoted.refusal.get should include("earlier storage layout")

    // a block at height 0 and no best block recorded
    val headerOnly = new Opened(EphemDataSource(), pathLive = true)
    headerOnly.storages.blockNumberMappingStorage
      .put(BigInt(0), ByteString(Array.fill[Byte](32)(9)))
      .commit()
    headerOnly.refusal.get should include("earlier storage layout")
  }

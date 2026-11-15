package com.chipprbots.ethereum.nodebuilder

import java.nio.file.Files
import java.nio.file.Path

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.InstanceConfigProvider
import com.chipprbots.ethereum.utils.StateLayoutConfig

/** Spec 018 Track C, C2b: what a node advertises follows what its storage can serve.
  *
  * With the in-development guard `state.path-live` on, a node's storages hold the position store, and the node does not
  * advertise SNAP. The two were read from two places: the storages from the process-wide configuration, the
  * capabilities from the instance's. In a multi-instance run those differ, and a node could have held the position
  * store while offering SNAP, or the reverse (herald). The production `StorageBuilder` now takes the guard from its
  * instance's configuration, which this builds, on a real RocksDB in a temporary directory.
  */
class StorageBuilderStateGuardSpec extends AnyFlatSpec with Matchers:

  private def deleteTree(dir: Path): Unit =
    import scala.jdk.CollectionConverters.*
    val walk = Files.walk(dir)
    try walk.iterator.asScala.toSeq.reverse.foreach(p => Files.deleteIfExists(p))
    finally walk.close()

  /** The production `StorageBuilder` of an instance whose configuration sets the guard to `guard`. */
  private def instance(guard: Boolean, testMode: Boolean = false)(
      check: (StorageBuilder & InstanceConfigProvider) => Unit
  ): Unit =
    val dir = Files.createTempDirectory("storage-builder-guard")
    val config = ConfigFactory
      .load()
      .getConfig("fukuii")
      .withValue("db.rocksdb.path", ConfigValueFactory.fromAnyRef(dir.toString))
      .withValue("state.path-live", ConfigValueFactory.fromAnyRef(guard))
      .withValue("testmode", ConfigValueFactory.fromAnyRef(testMode))
    val builder = new StorageBuilder with InstanceConfigProvider:
      override lazy val instanceConfig: InstanceConfig = new InstanceConfig(config, s"guard-$guard")
    try check(builder)
    finally
      builder.storagesInstance.dataSource.close()
      deleteTree(dir)

  /** The production `StorageBuilder` over the data directory `dir`, with the guard `guard`. Not closed here. */
  private def over(dir: Path, guard: Boolean): StorageBuilder & InstanceConfigProvider =
    val config = ConfigFactory
      .load()
      .getConfig("fukuii")
      .withValue("db.rocksdb.path", ConfigValueFactory.fromAnyRef(dir.toString))
      .withValue("state.path-live", ConfigValueFactory.fromAnyRef(guard))
    new StorageBuilder with InstanceConfigProvider:
      override lazy val instanceConfig: InstanceConfig = new InstanceConfig(config, s"layout-$guard")

  // Spec 018 C5a part 1 (T194, T203): the layout guard is asked where the storages are built, so that whoever takes
  // them has passed it, and a directory it refuses is not held open.
  "The storages of a node" should
    "not be built over a data directory the other layout wrote, and be built over it again as it was written" taggedAs
    (UnitTest, DatabaseTest) in {
      import org.apache.pekko.util.ByteString
      import com.chipprbots.ethereum.db.storage.StateLayoutGuard
      import com.chipprbots.ethereum.domain.appstate.BlockInfo
      val dir = Files.createTempDirectory("storage-builder-layout")
      try
        // the hash layout writes to it
        val hashed = over(dir, guard = false)
        hashed.storagesInstance.storages.appStateStorage
          .putBestBlockInfo(BlockInfo(ByteString(Array.fill[Byte](32)(7)), 1000))
          .commit()
        hashed.storagesInstance.dataSource.close()

        // with the guard on it is refused as the storages are built, and released: it can be opened again at once
        val refused = intercept[StateLayoutGuard.Refused](over(dir, guard = true).storagesInstance)
        refused.why should include("earlier storage layout")

        val again = over(dir, guard = false)
        again.storagesInstance.storages.appStateStorage.getBestBlockInfo().number shouldBe BigInt(1000)
        // nothing was written by the refusal
        again.storagesInstance.storages.appStateStorage.getStateLayout() shouldBe None
        again.storagesInstance.dataSource.close()
      finally deleteTree(dir)
    }

  it should "be built over a new directory with the guard on, which is left with no marker until a genesis is committed" taggedAs
    (UnitTest, DatabaseTest) in {
      val dir = Files.createTempDirectory("storage-builder-fresh")
      try
        for _ <- 1 to 2 do
          val fresh = over(dir, guard = true)
          fresh.storagesInstance.storages.pathStateStorage shouldBe defined
          fresh.storagesInstance.storages.appStateStorage.getStateLayout() shouldBe None
          fresh.storagesInstance.dataSource.close()
      finally deleteTree(dir)
    }

  "An instance with the guard on, in a process whose own configuration has it off" should
    "hold the position store and not advertise SNAP" taggedAs (UnitTest, DatabaseTest) in {
      // the process-wide configuration, which the storages read before
      StateLayoutConfig.pathLive shouldBe false
      instance(guard = true) { node =>
        node.storagesInstance.storages.pathStateStorage shouldBe defined
        node.instanceConfig.supportedCapabilities should contain noneOf (Capability.SNAP1, Capability.SNAP2)
      }
    }

  "An instance with the guard off" should "hold no position store and advertise SNAP" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    instance(guard = false) { node =>
      node.storagesInstance.storages.pathStateStorage shouldBe None
      node.instanceConfig.supportedCapabilities should contain(Capability.SNAP1)
    }
  }

  // Spec 018 Track C, C3c: `test_setChainParams` sets a chain on a datadir that held another, and the state of that
  // one is destroyed first. Only the position store of a node in test mode does that.
  "The position store of an instance" should "destroy its state for a new genesis in test mode, and in no other" taggedAs
    (UnitTest, DatabaseTest) in {
      instance(guard = true, testMode = true) { node =>
        node.storagesInstance.storages.pathStateStorage.get.destroyStateForNewGenesis() shouldBe 0
      }
      instance(guard = true) { node =>
        val refused = intercept[IllegalStateException] {
          node.storagesInstance.storages.pathStateStorage.get.destroyStateForNewGenesis()
        }
        refused.getMessage should include("test mode")
      }
    }

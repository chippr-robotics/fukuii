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

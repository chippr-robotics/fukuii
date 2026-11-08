package com.chipprbots.ethereum.nodebuilder

import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.StoragesComponent
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.InstanceConfigProvider

/** #1692, the production wiring. `StorageBuilder` builds the storages every node runs. Whether basic pruning may delete
  * trie nodes is the datadir's stamp, read from the same app state, on the Hash scheme (ETC, Mordor) and the Path
  * scheme (ETH, Sepolia) alike: no scheme switch. This builds the real thing, on a real RocksDB in a temporary
  * directory, for each network, and prunes through it.
  */
class StorageBuilderTrustSpec extends AnyFlatSpec with Matchers:

  private val history = 64
  private val slots = 10

  private def key(i: Int): ByteString = kec256(ByteString(s"slot$i"))

  private def deleteTree(dir: Path): Unit =
    import scala.jdk.CollectionConverters.*
    val walk = Files.walk(dir)
    try walk.iterator.asScala.toSeq.reverse.foreach(p => Files.deleteIfExists(p))
    finally walk.close()

  /** The production `StorageBuilder` for `network`, its RocksDB in a directory removed afterwards. */
  private def forNetwork(network: String)(check: StoragesComponent => Unit): Unit =
    val dir = Files.createTempDirectory("storage-builder-trust")
    val config = ConfigFactory
      .load()
      .getConfig("fukuii")
      .withValue("blockchains.network", ConfigValueFactory.fromAnyRef(network))
      .withValue("db.rocksdb.path", ConfigValueFactory.fromAnyRef(dir.toString))
      .withValue("pruning.mode", ConfigValueFactory.fromAnyRef("basic"))
      .withValue("pruning.history", ConfigValueFactory.fromAnyRef(history))
    val builder = new StorageBuilder with InstanceConfigProvider:
      override lazy val instanceConfig: InstanceConfig = new InstanceConfig(config, s"storage-trust-$network")
    try check(builder.storagesInstance)
    finally
      builder.storagesInstance.dataSource.close()
      deleteTree(dir)

  /** A trie written at block 0, one slot rewritten at block 1, blocks saved past the history: can the root the rewrite
    * replaced still be read? It is dead under exact counts, so it survives only if pruning deleted nothing.
    */
  private def oldRootSurvives(comp: StoragesComponent): Boolean =
    val state = comp.storages.stateStorage
    val first = (0 until slots).foldLeft(MerklePatriciaTrie[ByteString, ByteString](state.getBackingStorage(0))) {
      (t, i) => t.put(key(i), ByteString(s"a-$i"))
    }
    val root = ByteString(first.getRootHash)
    val _ = MerklePatriciaTrie[ByteString, ByteString](root.toArray, state.getBackingStorage(1))
      .put(key(3), ByteString("b"))
    (1 to 1 + history + 1).foreach(n => state.onBlockSave(n, n - 1)(() => ()))
    Try {
      val trie = MerklePatriciaTrie[ByteString, ByteString](root.toArray, state.getReadOnlyStorage)
      (0 until slots).foreach(i => trie.get(key(i)))
    }.isSuccess

  private val networks = Seq("etc" -> "Hash", "mordor" -> "Hash", "sepolia" -> "Path", "eth" -> "Path")

  "The production StorageBuilder" should "keep every trie node of a datadir with no stamp, on either scheme" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    networks.foreach { case (network, scheme) =>
      forNetwork(network) { comp =>
        withClue(s"$network ($scheme): ")(oldRootSurvives(comp) shouldBe true)
      }
    }
  }

  it should "prune by count on a datadir the genesis stamped, on either scheme" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    networks.foreach { case (network, scheme) =>
      forNetwork(network) { comp =>
        comp.storages.appStateStorage.markDatadirCreated(countsExact = true).commitSync()
        withClue(s"$network ($scheme): ")(oldRootSurvives(comp) shouldBe false)
      }
    }
  }

  it should "keep every trie node of a datadir whose genesis could not vouch for its counts" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    networks.foreach { case (network, scheme) =>
      forNetwork(network) { comp =>
        comp.storages.appStateStorage.markDatadirCreated(countsExact = false).commitSync()
        withClue(s"$network ($scheme): ")(oldRootSurvives(comp) shouldBe true)
      }
    }
  }

package com.chipprbots.ethereum.db.components

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*

/** #1692, the defaults. Basic pruning deletes a trie node only while the datadir carries the "counts are exact" stamp,
  * on either scheme: a component nobody stamped, and a state storage built with no decision, delete nothing. This pins
  * the default (fail closed) and the wiring from the app state to the prune, through `Storages.DefaultStorages`.
  */
class StoragesTrustWiringSpec extends AnyFlatSpec with Matchers:

  private val history = 4
  private val slots = 20

  private def key(i: Int): ByteString = kec256(ByteString(s"slot$i"))

  private trait Basic extends PruningConfigBuilder with TestInstanceConfigProvider:
    override val pruningMode: PruningMode = BasicPruning(history)

  private type Component = EphemDataSourceComponent & Basic & Storages.DefaultStorages

  /** A fresh datadir: nobody has stamped it. */
  private def fresh: Component =
    new EphemDataSourceComponent with Basic with Storages.DefaultStorages with TestInstanceConfigProvider

  /** A trie written at block 0, one slot rewritten at block 1, blocks saved past the history: can the root the rewrite
    * replaced still be read? It is dead under exact counts, so it survives only if pruning deleted nothing.
    */
  private def oldRootSurvives(state: StateStorage): Boolean =
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

  "A Storages component on a datadir nobody stamped" should "delete no trie node: the default is fail closed" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    oldRootSurvives(fresh.storages.stateStorage) shouldBe true
  }

  it should "delete by count once the genesis has stamped the datadir" taggedAs (UnitTest, DatabaseTest) in {
    val comp = fresh
    comp.storages.appStateStorage.markDatadirCreated(countsExact = true).commit()
    oldRootSurvives(comp.storages.stateStorage) shouldBe false
  }

  it should "delete no trie node when the genesis could not vouch for its counts" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val comp = fresh
    comp.storages.appStateStorage.markDatadirCreated(countsExact = false).commit()
    oldRootSurvives(comp.storages.stateStorage) shouldBe true
  }

  "A stamped datadir" should "delete no trie node once a checkpoint import has begun" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val comp = fresh
    comp.storages.appStateStorage.markDatadirCreated(countsExact = true).commit()
    comp.storages.appStateStorage.beginCheckpointImport(ByteString(Array.fill[Byte](32)(1)), 0L).commitSync()
    oldRootSurvives(comp.storages.stateStorage) shouldBe true
  }

  "A state storage built with no decision" should "delete no trie node" taggedAs (UnitTest, DatabaseTest) in {
    val direct = new ReferenceCountedStateStorage(new NodeStorage(EphemDataSource()), history)
    oldRootSurvives(direct) shouldBe true
  }

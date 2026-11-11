package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.StateLayoutConfig

/** Spec 018 Track C, T044 and T047: the read side of the unified layout, as the node holds it. It serves the state of
  * the anchor and of nothing else, and it exists only behind the in-development guard.
  */
class PathStateStorageSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  final private class Fixture(dataSource: DataSource, anchored: Boolean = true):
    val hashed = new HashedState
    val rows = new PathNodeStorage(dataSource)
    writeState(rows, hashed.positions())
    val appState = new AppStateStorage(dataSource)
    val anchor: BlockRef = block(1, hashed.root)
    if anchored then appState.putStateAnchor(anchor).commit()
    val state = new PathStateStorage(rows, appState)

  dataSources.foreach { case (name, onDataSource) =>
    s"The path state storage on $name" should "take its anchor from the datadir, and serve that block's state" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          f.appState.getStateAnchor() shouldBe Some(f.anchor)
          f.state.anchor shouldBe Some(f.anchor)
          f.state.stateAvailable(f.anchor) shouldBe true

          val view = f.state.view(f.anchor)
          view.block shouldBe f.anchor
          view.isAnchorViewNow shouldBe true

          val world = worldOver(f.state.readOnly(f.anchor), f.hashed.root, f.hashed.codes)
          world.getAccount(alice) shouldBe f.hashed.world(f.hashed.root).getAccount(alice)
          world.getStorage(carol).load(otherSlots.head._1) shouldBe BigInt(otherSlots.head._2)
        }
      }

    it should "say the state of any other block is unavailable, and of every block when there is no anchor" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val sibling = block(99, f.hashed.root)
          f.state.stateAvailable(sibling) shouldBe false
          val unavailable = the[StateUnavailable] thrownBy f.state.view(sibling)
          unavailable.reason should (include("99").and(include("1")))
          a[StateUnavailable] should be thrownBy f.state.readOnly(sibling)
          a[StateUnavailable] should be thrownBy f.state.throwaway(sibling)

          val bare = new Fixture(com.chipprbots.ethereum.db.dataSource.EphemDataSource(), anchored = false)
          bare.state.anchor shouldBe None
          bare.state.stateAvailable(bare.anchor) shouldBe false
          (the[StateUnavailable] thrownBy bare.state.view(bare.anchor)).reason should include("no state anchor")
        }
      }

    it should "give an execution that commits nothing a layer of its own" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val change: World => World = withSlots(dave, Seq(1 -> 2))
        val persisted =
          InMemoryWorldStateProxy.persistState(
            change(worldOver(f.state.throwaway(f.anchor), f.hashed.root, f.hashed.codes))
          )
        persisted.stateRootHash shouldBe f.hashed.change(f.hashed.root)(change)
        an[UnsupportedOperationException] should be thrownBy InMemoryWorldStateProxy.persistState(
          change(worldOver(f.state.readOnly(f.anchor), f.hashed.root, f.hashed.codes))
        )
      }
    }

    it should "give the prefetch a reader of the anchor's state, and none of a state it cannot view" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val cache = DecodedNodeCache.withOwnBudget(1L << 20, strongValues = true)
          val reader = f.state.prefetchReader(f.anchor, Some(cache), 1L << 20, _ => ())
          reader shouldBe defined
          ByteString(reader.get.get(f.hashed.root.toArray).hash) shouldBe f.hashed.root
          reader.get.nodesLoaded shouldBe 1

          // a sibling of the head as parent: no reader, and the block executes without a prefetch
          f.state.prefetchReader(block(99, f.hashed.root), Some(cache), 1L << 20, _ => ()) shouldBe None
          val bare = new Fixture(com.chipprbots.ethereum.db.dataSource.EphemDataSource(), anchored = false)
          bare.state.prefetchReader(bare.anchor, Some(cache), 1L << 20, _ => ()) shouldBe None
        }
      }

    it should "refuse an anchor it cannot read, where an absent one is only absent" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val appState = new AppStateStorage(dataSource)
        appState.getStateAnchor() shouldBe None
        appState.put(StateAnchor.Key, "v1:not-an-anchor").commit()
        an[IllegalStateException] should be thrownBy appState.getStateAnchor()
        an[IllegalStateException] should be thrownBy new PathStateStorage(new PathNodeStorage(dataSource), appState)
      }
    }
  }

  "The rows of one storage trie" should "not be listable before the staged commit exists" taggedAs UnitTest in {
    val f = new Fixture(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
    an[UnsupportedOperationException] should be thrownBy f.state.ownerRows(owner(alice))
  }

  private trait Archive extends Storages.PruningModeComponent:
    override val pruningMode: PruningMode = ArchivePruning

  private class GuardOff extends EphemDataSourceComponent with Archive with Storages.DefaultStorages

  private class GuardOn extends EphemDataSourceComponent with Archive with Storages.DefaultStorages:
    override protected def statePathLive: Boolean = true

  "The in-development guard" should "ship off, in the node's configuration" taggedAs UnitTest in {
    val config = ConfigFactory.load()
    config.hasPath("fukuii.state.path-live") shouldBe true
    config.getBoolean("fukuii.state.path-live") shouldBe false
    StateLayoutConfig.pathLive shouldBe false
  }

  it should "leave the node without a path state storage when off, and build one when on" taggedAs UnitTest in {
    new GuardOff().storages.pathStateStorage shouldBe None

    val on = new GuardOn().storages
    on.pathStateStorage shouldBe defined
    // a fresh datadir has no state anchor yet
    on.pathStateStorage.get.anchor shouldBe None
    on.appStateStorage.putStateAnchor(block(3, EmptyRoot)).commit()
    on.appStateStorage.getStateAnchor() shouldBe Some(block(3, EmptyRoot))
  }

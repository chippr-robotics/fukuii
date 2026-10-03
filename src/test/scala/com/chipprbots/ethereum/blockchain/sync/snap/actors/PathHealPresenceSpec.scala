package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.*
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Regression coverage for SNAP state healing under the Path storage scheme: after a re-peg, only the nodes whose
  * content changed may be re-fetched.
  *
  * Bug (live Platåberget soak): `TrieNodeHealingCoordinator.isNodeInStorage` could verify only the walk ROOT under the
  * Path scheme (it always read the empty account path), and the inline child discovery / frontier-rebuild walk looked
  * every other node up in the hash-keyed `mptStorage`, which a Path-scheme node never populates. Every non-root node
  * therefore looked absent, so each re-peg re-queued and re-fetched effectively the whole trie below the new root
  * (frontier ≈ 1.7-1.9M nodes each time) and healing could never finish on a live chain.
  *
  * Fix under test: a node is present under Path ONLY when the bytes stored at its OWN path (account path, or account
  * hash plus storage path) hash to the expected keccak. Path existence alone, or the right bytes at another path, never
  * count.
  *
  * Method: spawn a REAL coordinator over a REAL `PathNodeStorage` (temp RocksDB) and let a serving fake peer answer its
  * `GetTrieNodes` from the new trie, recording every request. The requested pathset is the only external signal for
  * "was this node treated as present or absent", and it is asserted on exactly. The pre-fix code fails the Path tests
  * below on the round that follows the root (it asks for every child, changed or not); the Hash-scheme parity test
  * passes before and after.
  */
class PathHealPresenceSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit

  private def rocksDbConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  /** A throwaway `PathNodeStorage` over a temp RocksDB, destroyed afterwards. */
  private def withPathStorage[A](body: PathNodeStorage => A): A =
    val dbPath = Files.createTempDirectory("path-heal-presence-rocksdb").toAbsolutePath.toString
    val dataSource = RocksDbDataSource(rocksDbConfig(dbPath), Namespaces.nsSeq)
    try body(new PathNodeStorage(dataSource))
    finally
      dataSource.destroy()
      deleteRecursively(new File(dbPath))

  private def awaitStateHealingComplete(
      controller: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[SNAPSyncController.Command]
  ): Unit =
    controller.fishForMessage(60.seconds) {
      case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
      case _                                       => FishingOutcomes.continueAndIgnore
    }

  /** Heal against a serving peer that holds `served`, and return every `GetTrieNodes` request the coordinator sent, in
    * arrival order — each request as the list of pathsets it asked for.
    *
    * The fake peer manager answers each request from `served` the way a real SNAP peer does (each path resolved by
    * position against its own trie; paths it does not have are simply omitted), so the coordinator runs to
    * `StateHealingComplete` on its own and the test only inspects what it asked for. `trigger` starts the scenario
    * (`HealingPivotRefreshed` for a re-peg, `StartTrieNodeHealing` for a restart); the peer is registered right after.
    */
  private def healAgainst(
      served: Map[Seq[ByteString], ByteString],
      initialRoot: ByteString,
      storageScheme: StorageScheme,
      pathNodeStorage: Option[PathNodeStorage],
      mptStorage: MptStorage
  )(trigger: ActorRef[TrieNodeHealingCoordinator.Command] => Unit): List[Seq[Seq[ByteString]]] =
    val pool = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(pool)
    val requests = new ConcurrentLinkedQueue[Seq[Seq[ByteString]]]()
    val coordinatorRef = new AtomicReference[ActorRef[TrieNodeHealingCoordinator.Command]]()

    val peerManager = testKit.spawn(Behaviors.receiveMessage[NetworkPeerManagerActor.Command] {
      case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
        message.underlyingMsg match
          case req: SNAP.GetTrieNodes =>
            requests.add(req.paths)
            val nodes = req.paths.flatMap(served.get)
            coordinatorRef.get() ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
              SNAP.TrieNodes(requestId = req.requestId, nodes = nodes)
            )
          case _ => ()
        Behaviors.same
      case _ => Behaviors.same
    })
    val controller = testKit.createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = initialRoot,
      networkPeerManager = peerManager,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = mptStorage,
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      storageScheme = storageScheme,
      pathNodeStorageOpt = pathNodeStorage,
      movingRootDeltaHeal = true
    )
    coordinatorRef.set(coordinator)
    try
      trigger(coordinator)
      val peer = PeerTestHelpers.createTestPeer("path-presence-peer", testKit.createTestProbe[Any]().ref.toClassic)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(peer)
      awaitStateHealingComplete(controller)
      requests.asScala.toList
    finally
      testKit.stop(coordinator)
      testKit.stop(peerManager)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  private def pathsets(nodes: Seq[Placed]): Set[Seq[ByteString]] = nodes.map(_.pathset).toSet

  // ── Path scheme ──────────────────────────────────────────────────────────────────────────────────────────────────

  "SNAP state healing under the Path storage scheme" should
    "re-fetch only the changed spine after a re-peg, never a node the old root already placed correctly" taggedAs UnitTest in {
      val oldTrie = accountTrieOld()
      val newTrie = accountTrieNew()
      // Fixture self-check: leafX1 changed, so exactly leafX1 → brX → ext → root differ; the other four are identical.
      newTrie.changedFrom(oldTrie).map(_.label).toSet shouldBe Set("leafX1", "brX", "ext", "root")
      newTrie.unchangedFrom(oldTrie).map(_.label).toSet shouldBe Set("leafX2", "brY", "leafY0", "leafY1")

      withPathStorage { pns =>
        seedPath(pns, oldTrie.nodes) // every node healed against the OLD root, each at its own path

        val requests =
          healAgainst(newTrie.served, oldTrie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
            coordinator =>
              coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash) // the re-peg
          }

        // Round by round, top-down along the changed spine — one request each, containing ONLY the changed node. The
        // pre-fix code diverges at round 2: it asks for BOTH of the root's children (ext AND the unchanged brY).
        requests.map(_.toSet) shouldBe List(
          Set(newTrie("root").pathset),
          Set(newTrie("ext").pathset), // brY (unchanged, at [1]) is NOT requested
          Set(newTrie("brX").pathset), // reached through the extension: path [0] ++ sharedKey [5,6]
          Set(newTrie("leafX1").pathset) // leafX2 (unchanged, at [0,5,6,2]) is NOT requested
        )
        // The same fact as sets: everything requested is a changed node; nothing unchanged was requested.
        val requested = requests.flatten.toSet
        requested shouldBe pathsets(newTrie.changedFrom(oldTrie))
        requested.intersect(pathsets(newTrie.unchangedFrom(oldTrie))) shouldBe empty

        // And the path store now holds exactly the new trie (healed nodes written at their paths, the rest untouched).
        newTrie.nodes.foreach(n => readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded))
      }
    }

  it should "treat a stale node, and the right bytes at the wrong path, as absent — never trust path existence" taggedAs UnitTest in {
    val oldTrie = accountTrieOld()
    val newTrie = accountTrieNew()

    withPathStorage { pns =>
      seedPath(pns, oldTrie.nodes) // the changed nodes' STALE bytes occupy their paths (right path, wrong hash)
      // leafX2 did not change, but make it "right bytes, wrong path": same content, parked at [0,5,6,3] instead.
      val wrongPath = Array[Byte](0, 5, 6, 3)
      pns.deleteAccountNode(newTrie("leafX2").nibbles)
      pns.writeAccountNode(wrongPath, newTrie("leafX2").node.encode)
      readAtPath(pns, newTrie("leafX2")) shouldBe None

      val requests =
        healAgainst(newTrie.served, oldTrie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
          coordinator => coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash)
        }

      requests.map(_.toSet) shouldBe List(
        Set(newTrie("root").pathset), // stale root bytes at []
        Set(newTrie("ext").pathset), // stale ext at [0]
        Set(newTrie("brX").pathset), // stale brX at [0,5,6]
        // brX's two children: leafX1 is STALE at its path, leafX2's exact bytes exist only at the WRONG path — both
        // are absent where it counts. (brY and its leaves are present and correct: never requested, any round.)
        Set(newTrie("leafX1").pathset, newTrie("leafX2").pathset)
      )
      requests.flatten.toSet.intersect(
        pathsets(Seq(newTrie("brY"), newTrie("leafY0"), newTrie("leafY1")))
      ) shouldBe empty

      // One node per path slot: the stale bytes were OVERWRITTEN by the healed ones, not left beside them.
      readAtPath(pns, newTrie("leafX1")).map(ByteString(_)) shouldBe Some(newTrie("leafX1").encoded)
      readAtPath(pns, newTrie("leafX2")).map(ByteString(_)) shouldBe Some(newTrie("leafX2").encoded)
    }
  }

  it should "scope a storage-trie node's path by its account hash" taggedAs UnitTest in {
    val oldTrie = storageTrieOld()
    val newTrie = storageTrieNew()
    newTrie.changedFrom(oldTrie).map(_.label).toSet shouldBe Set("sLeaf1", "sRoot", "root")

    withPathStorage { pns =>
      seedPath(pns, oldTrie.nodes)

      val requests =
        healAgainst(newTrie.served, oldTrie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
          coordinator => coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash)
        }

      requests.map(_.toSet) shouldBe List(
        Set(newTrie("root").pathset), // the account leaf, account-trie path []
        // ARCH-LEAF-SEED: the account's new storage root, at (accountHash, []) — absent there (stale root bytes)
        Set(newTrie("sRoot").pathset),
        Set(newTrie("sLeaf1").pathset) // sLeaf0 at (accountHash, [0]) is present: NOT requested
      )
      newTrie("sLeaf1").pathset shouldBe Seq(accountHash, ByteString(HexPrefix.encode(Array[Byte](1), isLeaf = false)))
      newTrie.nodes.foreach(n => readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded))
    }
  }

  it should "need no request at all when the re-peg target root is already fully present" taggedAs UnitTest in {
    val oldTrie = accountTrieOld()
    val newTrie = accountTrieNew()

    withPathStorage { pns =>
      seedPath(pns, newTrie.nodes) // the NEW trie is already complete locally

      // FIX-BUG2-PIVOT branch: new root present ⇒ verification walk over the local trie. Path-keyed, it finds every node.
      val requests =
        healAgainst(newTrie.served, oldTrie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
          coordinator => coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash)
        }

      requests shouldBe empty
    }
  }

  it should "verify a fully present trie on restart without requesting anything" taggedAs UnitTest in {
    val trie = accountTrieNew()

    withPathStorage { pns =>
      seedPath(pns, trie.nodes)

      // HEAL-RESTART: root present ⇒ frontier-rebuild walk. It must read the trie path-keyed and find nothing missing.
      val requests = healAgainst(trie.served, trie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
        coordinator => coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      }

      requests shouldBe empty
    }
  }

  it should "find exactly the one missing node at its path when the frontier-rebuild walk runs" taggedAs UnitTest in {
    val trie = accountTrieNew()

    withPathStorage { pns =>
      seedPath(pns, trie.nodes)
      pns.deleteAccountNode(trie("leafX2").nibbles) // one hole, deep in the trie, behind an extension

      val requests = healAgainst(trie.served, trie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
        coordinator => coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      }

      requests.map(_.toSet) shouldBe List(Set(trie("leafX2").pathset))
      readAtPath(pns, trie("leafX2")).map(ByteString(_)) shouldBe Some(trie("leafX2").encoded)
    }
  }

  it should "verify BOTH locations that share a hash — identical storage tries under two accounts" taggedAs UnitTest in {
    val trie = twinStorageTrie()
    trie("s0").hash shouldBe trie("s1").hash // same node, same hash …
    trie("s0").pathset should not be trie("s1").pathset // … at two different locations

    withPathStorage { pns =>
      seedPath(pns, trie.nodes.filterNot(_.label == "s1")) // account 1's copy of the storage root is MISSING

      // A walk that de-dups by hash marks S seen at (A0, []) and never looks at (A1, []): it finds nothing missing and
      // declares the trie complete over a hole. Keyed by location, it finds exactly the missing copy.
      val requests = healAgainst(trie.served, trie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
        coordinator => coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      }

      requests.map(_.toSet) shouldBe List(Set(trie("s1").pathset))
      readAtPath(pns, trie("s1")).map(ByteString(_)) shouldBe Some(trie("s1").encoded)
    }
  }

  it should "heal BOTH locations that share a hash — neither is skipped" taggedAs UnitTest in {
    val trie = twinStorageTrie()
    withPathStorage { pns =>
      // BOTH accounts' copies of the identical storage root are missing: one hash, two locations.
      seedPath(pns, trie.nodes.filterNot(n => n.label == "s0" || n.label == "s1"))

      val requests = healAgainst(trie.served, trie.root.hash, StorageScheme.Path, Some(pns), new TestMptStorage()) {
        coordinator => coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      }

      // The queue dedups by hash, so the locations may be fetched in successive rounds; the walk keeps finding the
      // remaining one until both are present. Both must be requested and both must end up written at their paths.
      requests.flatten.toSet shouldBe pathsets(Seq(trie("s0"), trie("s1")))
      readAtPath(pns, trie("s0")).map(ByteString(_)) shouldBe Some(trie("s0").encoded)
      readAtPath(pns, trie("s1")).map(ByteString(_)) shouldBe Some(trie("s1").encoded)
    }
  }

  it should "report a force-complete as abandoned, never as a verified clean walk" taggedAs UnitTest in {
    val trie = accountTrieNew()
    withPathStorage { pns =>
      val controller = testKit.createTestProbe[SNAPSyncController.Command]()
      val coordinator = HealingTrieFixtures.spawnCoordinator(
        stateRoot = trie.root.hash,
        networkPeerManager = testKit.createTestProbe[NetworkPeerManagerActor.Command]().ref,
        requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
        mptStorage = new TestMptStorage(),
        batchSize = 16,
        snapSyncController = controller.ref,
        storageScheme = StorageScheme.Path,
        pathNodeStorageOpt = Some(pns)
      )
      coordinator ! TrieNodeHealingCoordinator.HealingForceComplete
      controller.expectMessage(SNAPSyncController.StateHealingAbandoned)
    }
  }

  // ── Hash scheme (ETC): unchanged ─────────────────────────────────────────────────────────────────────────────────

  "SNAP state healing under the Hash storage scheme" should
    "still fetch only the changed nodes after a re-peg (behaviour unchanged by the Path fix)" taggedAs UnitTest in {
      val oldTrie = accountTrieOld()
      val newTrie = accountTrieNew()
      val storage = new TestMptStorage()
      seedHash(storage, oldTrie.nodes) // content-addressed: position is irrelevant

      val requests = healAgainst(newTrie.served, oldTrie.root.hash, StorageScheme.Hash, None, storage) { coordinator =>
        coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash)
      }

      // Hash-scheme healing buffers its writes and flushes them asynchronously, so a node can legitimately be requested
      // a second time if a verification pass starts before its flush lands. That is independent of presence checking;
      // what must hold — and held before the Path fix — is that the SET of requested paths is exactly the changed nodes.
      requests.flatten.toSet shouldBe pathsets(newTrie.changedFrom(oldTrie))
    }

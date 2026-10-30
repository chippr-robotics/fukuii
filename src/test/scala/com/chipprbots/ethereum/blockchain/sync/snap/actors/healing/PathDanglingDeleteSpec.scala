package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.FishingOutcomes
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.network.p2p.messages.SNAP
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 slice 1, T008 (FR-008, D10, Path scheme), as slice 2 (D3) writes it: healing deletes the positions a node
  * leaves stale:
  *   - the inner prefixes of an extension node's `sharedKey` (P ++ key[:i], 0 < i < len(key)), which the extension
  *     skips over, so nothing may stay there (geth `trie/sync.go` `children()`);
  *   - a child position whose stored bytes hash to something other than what P references (geth `hasNode`'s
  *     "inconsistent" case): a stale node from an older root.
  *
  * Slice 2 holds a node until its whole subtree is present, so the node that finds a stale position is written later,
  * together with its subtree. The delete does not wait for it: it goes out in the write batch of the response whose
  * discovery found it, before anything is written at that position, as geth's membatch orders a `delNode` before the
  * later write there. Deferred to the parent's own commit it would delete the child written there in between.
  *
  * Method: a real coordinator over a `PathNodeStorage` whose data source records every write batch (one `update` call
  * is one RocksDB `WriteBatch`). Stale bytes are seeded at the stale positions; the test answers the fake peer one
  * level at a time, so the batches and their order are exact. On the code before slice 1 the node is written alone and
  * the stale bytes stay.
  */
class PathDanglingDeleteSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private def withPathCoordinator[A](root: ByteString, pns: PathNodeStorage, peer: ScriptedPeer)(
      body: ActorRef[TrieNodeHealingCoordinator.Command] => A
  ): A =
    val pool = Executors.newSingleThreadExecutor()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = root,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = createTestProbe[SNAPSyncController.Command]().ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = StorageScheme.Path,
      pathNodeStorageOpt = Some(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(root)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("t008-peer", createTestProbe[Any]().ref.toClassic)
      )
      body(coordinator)
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

  /** Answer each of the next `levels` requests from `served`, then wait until the coordinator has processed the last
    * answer (a progress query, answered in order, is the barrier).
    */
  private def answerLevels(
      coordinator: ActorRef[TrieNodeHealingCoordinator.Command],
      peer: ScriptedPeer,
      served: Map[Seq[ByteString], ByteString],
      levels: Seq[Set[Seq[ByteString]]]
  ): Unit =
    levels.foreach { expected =>
      val req = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      req.paths.toSet shouldBe expected
      peer.answerFrom(req, served)
    }
    val stats = createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(stats.ref)
    stats.expectMessageType[HealingStatistics]

  private def put(ns: IndexedSeq[Byte], key: ByteString): Op = Op(ns, delete = false, key)
  private def del(ns: IndexedSeq[Byte], key: ByteString): Op = Op(ns, delete = true, key)

  "Path-scheme healing" should "delete an extension's inner prefixes and a stale child in the batch of the response that found them, before the child is written (T008)" taggedAs UnitTest in {
    // root (branch) []
    //   ├─[0] ext (sharedKey 5,6)  [0]
    //   │       └─ brX (branch)    [0,5,6]  ← stale bytes there (an older brX: wrong hash)
    //   │            ├─[1] leafX1  [0,5,6,1]
    //   │            └─[2] leafX2  [0,5,6,2]
    //   └─[1] leafY                [1]
    // plus a stale node at [0,5]: an inner prefix of the extension's key, unreachable under the new extension.
    val leafX1 = plainLeaf(nibbles(0x0a), "s022/t008/leafX1")
    val leafX2 = plainLeaf(nibbles(0x0b), "s022/t008/leafX2")
    val brX = branch(1 -> leafX1, 2 -> leafX2)
    val ext = ExtensionNode(ByteString(nibbles(5, 6)), HashNode(brX.hash))
    val leafY = plainLeaf(nibbles(0x0c), "s022/t008/leafY")
    val root = branch(0 -> ext, 1 -> leafY)
    val served: Map[Seq[ByteString], ByteString] = Map(
      accountPathset() -> ByteString(root.encode),
      accountPathset(0) -> ByteString(ext.encode),
      accountPathset(0, 5, 6) -> ByteString(brX.encode),
      accountPathset(0, 5, 6, 1) -> ByteString(leafX1.encode),
      accountPathset(0, 5, 6, 2) -> ByteString(leafX2.encode),
      accountPathset(1) -> ByteString(leafY.encode)
    )
    val staleInner = plainLeaf(nibbles(0x01), "s022/t008/stale-inner").encode
    val staleBrX = branch(1 -> plainLeaf(nibbles(0x0a), "s022/t008/leafX1-old")).encode

    val ds = new RecordingDataSource
    val pns = new PathNodeStorage(ds)
    pns.writeAccountNode(nibbles(0, 5), staleInner)
    pns.writeAccountNode(nibbles(0, 5, 6), staleBrX)
    ds.forget() // only the healing writes are under test
    val peer = new ScriptedPeer(testKit)

    withPathCoordinator(ByteString(root.hash), pns, peer) { coordinator =>
      answerLevels(
        coordinator,
        peer,
        served,
        Seq(
          Set(accountPathset()),
          Set(accountPathset(0), accountPathset(1)),
          Set(accountPathset(0, 5, 6)),
          Set(accountPathset(0, 5, 6, 1), accountPathset(0, 5, 6, 2))
        )
      )
    }

    val key = (ns: Seq[Int]) => accountKey(nibbles(ns*))
    ds.batches.map(_.toSet) shouldBe Seq(
      // The response with the extension (held: brX missing) and leafY (complete): the extension's stale positions are
      // deleted now, in the batch that commits leafY.
      Set(del(AccountNs, key(Seq(0, 5))), del(AccountNs, key(Seq(0, 5, 6))), put(AccountNs, key(Seq(1)))),
      // brX's leaves complete the trie: every held node is written with them, in one batch.
      Set(
        put(AccountNs, key(Seq(0, 5, 6, 1))),
        put(AccountNs, key(Seq(0, 5, 6, 2))),
        put(AccountNs, key(Seq(0, 5, 6))),
        put(AccountNs, key(Seq(0))),
        put(AccountNs, key(Nil))
      )
    )
    pns.readAccountNode(nibbles(0, 5)) shouldBe None
    pns.readAccountNode(nibbles(0, 5, 6)).map(ByteString(_)) shouldBe Some(ByteString(brX.encode))
  }

  it should "do the same in a storage trie, and delete a stale storage root before its account leaf's storage trie is written (T008)" taggedAs UnitTest in {
    // The account trie is one account leaf at []; its storage trie:
    //   sExt (sharedKey 3,4,5)  (A, [])       ← stale bytes there (an older storage root: wrong hash)
    //     └─ sBr (branch)       (A, [3,4,5])
    //          ├─[0] s0         (A, [3,4,5,0])
    //          └─[1] s1         (A, [3,4,5,1])
    // plus stale nodes at (A, [3]) and (A, [3,4]): inner prefixes of sExt's key.
    val s0 = plainLeaf(nibbles(0x01), "s022/t008/storage/s0")
    val s1 = plainLeaf(nibbles(0x02), "s022/t008/storage/s1")
    val sBr = branch(0 -> s0, 1 -> s1)
    val sExt = ExtensionNode(ByteString(nibbles(3, 4, 5)), HashNode(sBr.hash))
    val accountNibbles = (0 until 64).map(i => ((i * 11 + 2) % 16).toByte).toArray
    val owner = packNibbles(accountNibbles)
    val account = Account(nonce = UInt256(1L), balance = UInt256(7L), storageRoot = TrieRoot(ByteString(sExt.hash)))
    val accountLeaf = LeafNode(ByteString(accountNibbles), ByteString(Account.accountSerializer.toBytes(account)))
    def storagePathset(ns: Int*): Seq[ByteString] = Seq(owner, compact(nibbles(ns*)))
    val served: Map[Seq[ByteString], ByteString] = Map(
      accountPathset() -> ByteString(accountLeaf.encode),
      storagePathset() -> ByteString(sExt.encode),
      storagePathset(3, 4, 5) -> ByteString(sBr.encode),
      storagePathset(3, 4, 5, 0) -> ByteString(s0.encode),
      storagePathset(3, 4, 5, 1) -> ByteString(s1.encode)
    )

    val ds = new RecordingDataSource
    val pns = new PathNodeStorage(ds)
    pns.writeStorageNode(owner, Array.empty[Byte], plainLeaf(nibbles(0x09), "s022/t008/storage/old-root").encode)
    pns.writeStorageNode(owner, nibbles(3), plainLeaf(nibbles(0x08), "s022/t008/storage/stale-3").encode)
    pns.writeStorageNode(owner, nibbles(3, 4), plainLeaf(nibbles(0x07), "s022/t008/storage/stale-34").encode)
    ds.forget()
    val peer = new ScriptedPeer(testKit)

    withPathCoordinator(ByteString(accountLeaf.hash), pns, peer) { coordinator =>
      answerLevels(
        coordinator,
        peer,
        served,
        Seq(
          Set(accountPathset()),
          Set(storagePathset()),
          Set(storagePathset(3, 4, 5)),
          Set(storagePathset(3, 4, 5, 0), storagePathset(3, 4, 5, 1))
        )
      )
    }

    val skey = (ns: Seq[Int]) => storageKey(owner, nibbles(ns*))
    ds.batches.map(_.toSet) shouldBe Seq(
      // The account leaf (held: its storage root is missing) deletes the stale storage root it points past.
      Set(del(StorageNs, skey(Nil))),
      // sExt (held: sBr missing) deletes its key's two inner prefixes.
      Set(del(StorageNs, skey(Seq(3))), del(StorageNs, skey(Seq(3, 4)))),
      // The slots complete the storage trie and the account: one batch, two column families.
      Set(
        put(StorageNs, skey(Seq(3, 4, 5, 0))),
        put(StorageNs, skey(Seq(3, 4, 5, 1))),
        put(StorageNs, skey(Seq(3, 4, 5))),
        put(StorageNs, skey(Nil)),
        put(AccountNs, accountKey(Array.empty[Byte]))
      )
    )
    pns.readStorageNode(owner, nibbles(3)) shouldBe None
    pns.readStorageNode(owner, nibbles(3, 4)) shouldBe None
    pns.readStorageNode(owner, Array.empty[Byte]).map(ByteString(_)) shouldBe Some(ByteString(sExt.encode))
  }

  it should "delete each alias position's own stale bytes, and write every alias position of a node in one batch (review item 7, vault note 4)" taggedAs UnitTest in {
    // The twin storage tries (one storage root S, a branch over slotA and slotB, under two accounts A0 and A1). Stale
    // bytes sit at both storage-root positions and at A1's slotA position; A0's slot positions are empty.
    val trie = twinStorageTrie(nonce = 1)
    val (a0, a1) = (trie("s0").accountHash.get, trie("s1").accountHash.get)
    val ds = new RecordingDataSource
    val pns = new PathNodeStorage(ds)
    pns.writeStorageNode(a0, Array.empty[Byte], plainLeaf(nibbles(0x0e), "s022/item7/stale-s0").encode)
    pns.writeStorageNode(a1, Array.empty[Byte], plainLeaf(nibbles(0x0e), "s022/item7/stale-s1").encode)
    pns.writeStorageNode(a1, nibbles(0), plainLeaf(nibbles(0x0e), "s022/item7/stale-s1A").encode)
    ds.forget()
    val peer = new ScriptedPeer(testKit)
    peer.serveFrom.set(Some(trie.served))
    val pool = Executors.newSingleThreadExecutor()
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ExecutionContext.fromExecutorService(pool)),
      storageScheme = StorageScheme.Path,
      pathNodeStorageOpt = Some(pns),
      movingRootDeltaHeal = true
    )
    peer.coordinator.set(coordinator)
    try
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("item7-peer", createTestProbe[Any]().ref.toClassic)
      )
      controller.fishForMessage(60.seconds) {
        case SNAPSyncController.StateHealingComplete => FishingOutcomes.complete
        case _                                       => FishingOutcomes.continueAndIgnore
      }
    finally
      testKit.stop(coordinator)
      testKit.stop(peer.ref)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

    ds.batches.map(_.toSet) shouldBe Seq(
      // The account leaves (held: S missing) each delete their own stale storage root.
      Set(del(StorageNs, storageKey(a0, Array.empty)), del(StorageNs, storageKey(a1, Array.empty))),
      // S, fetched once, fills both positions (held: the slots are missing); only A1's position finds a stale child.
      Set(del(StorageNs, storageKey(a1, nibbles(0)))),
      // The slots complete everything: every position of every node, in one batch.
      trie.nodes
        .map(n =>
          n.accountHash.fold(put(AccountNs, accountKey(n.nibbles)))(o => put(StorageNs, storageKey(o, n.nibbles)))
        )
        .toSet
    )
    fetchedMoreThanOnce(trie, peer.requests) shouldBe empty
    trie.nodes.foreach(n => readAtPath(pns, n).map(ByteString(_)) shouldBe Some(n.encoded))
  }

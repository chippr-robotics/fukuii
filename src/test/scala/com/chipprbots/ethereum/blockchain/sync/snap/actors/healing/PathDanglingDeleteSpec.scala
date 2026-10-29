package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
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

/** Spec 022 slice 1, T008 (FR-008, D10, Path scheme): a healed node written at position P removes, in the same write
  * batch, the positions that write leaves dangling:
  *   - the inner prefixes of an extension node's `sharedKey` (P ++ key[:i], 0 < i < len(key)), which the extension
  *     skips over, so nothing may stay there (geth `trie/sync.go` `children()`);
  *   - a child position whose stored bytes hash to something other than what P now references (geth `hasNode`'s
  *     "inconsistent" case): a stale node from an older root.
  *
  * Method: a real coordinator over a `PathNodeStorage` whose data source records every write batch (one `update` call
  * is one RocksDB `WriteBatch`). Stale bytes are seeded at the dangling positions; the test answers the fake peer by
  * hand up to the write under test and stops there. On the code before the fix the node is written alone and the stale
  * bytes stay.
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

  "Path-scheme healing" should "delete an extension's inner prefixes and a stale child, in the batch that writes the extension (T008)" taggedAs UnitTest in {
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

    withPathCoordinator(ByteString(root.hash), pns, peer) { _ =>
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], served) // the root
      val children = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      children.paths.toSet shouldBe Set(accountPathset(0), accountPathset(1))
      peer.answerFrom(children, served) // the extension and leafY: the write under test
      // brX is requested only after the extension's write: from here the batch log is complete for that write.
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(accountPathset(0, 5, 6))
    }

    val extBatches = ds.batchesPutting(AccountNs, accountKey(nibbles(0)))
    extBatches should have size 1
    extBatches.head.toSet shouldBe Set(
      Op(AccountNs, delete = false, accountKey(nibbles(0))), // the extension
      Op(AccountNs, delete = true, accountKey(nibbles(0, 5))), // its key's inner prefix
      Op(AccountNs, delete = true, accountKey(nibbles(0, 5, 6))) // its child's position: stale bytes, wrong hash
    )
    pns.readAccountNode(nibbles(0, 5)) shouldBe None
    pns.readAccountNode(nibbles(0, 5, 6)) shouldBe None
    // The sibling write carries no deletes: its position had nothing stale below it.
    ds.batchesPutting(AccountNs, accountKey(nibbles(1))).map(_.toSet) shouldBe Seq(
      Set(Op(AccountNs, delete = false, accountKey(nibbles(1))))
    )
  }

  it should "do the same in a storage trie, and delete a stale storage root in the batch that writes its account leaf (T008)" taggedAs UnitTest in {
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

    withPathCoordinator(ByteString(accountLeaf.hash), pns, peer) { _ =>
      peer.answerFrom(peer.manual.expectMessageType[SNAP.GetTrieNodes], served) // the account leaf
      val storageRoot = peer.manual.expectMessageType[SNAP.GetTrieNodes]
      storageRoot.paths shouldBe Seq(storagePathset())
      peer.answerFrom(storageRoot, served) // sExt: the second write under test
      peer.manual.expectMessageType[SNAP.GetTrieNodes].paths shouldBe Seq(storagePathset(3, 4, 5))
    }

    // The account leaf's batch also deletes the stale storage root it now points past (one batch, two column families).
    ds.batchesPutting(AccountNs, accountKey(Array.empty[Byte])).map(_.toSet) shouldBe Seq(
      Set(
        Op(AccountNs, delete = false, accountKey(Array.empty[Byte])),
        Op(StorageNs, delete = true, storageKey(owner, Array.empty[Byte]))
      )
    )
    // The storage extension's batch deletes its key's two inner prefixes.
    ds.batchesPutting(StorageNs, storageKey(owner, Array.empty[Byte])).map(_.toSet) shouldBe Seq(
      Set(
        Op(StorageNs, delete = false, storageKey(owner, Array.empty[Byte])),
        Op(StorageNs, delete = true, storageKey(owner, nibbles(3))),
        Op(StorageNs, delete = true, storageKey(owner, nibbles(3, 4)))
      )
    )
    pns.readStorageNode(owner, nibbles(3)) shouldBe None
    pns.readStorageNode(owner, nibbles(3, 4)) shouldBe None
    pns.readStorageNode(owner, Array.empty[Byte]).map(ByteString(_)) shouldBe Some(ByteString(sExt.encode))
  }

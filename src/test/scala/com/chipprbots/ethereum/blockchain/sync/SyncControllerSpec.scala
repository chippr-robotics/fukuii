// §8a-retro batch 5: DEFERRED — TestActorRef used for .children inspection (Classic-only API);
// migrate when SyncController test no longer needs child inspection (Wave 3 network sprint)
package com.chipprbots.ethereum.blockchain.sync

import java.nio.charset.StandardCharsets

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.ExplicitlyTriggeredScheduler
import org.apache.pekko.testkit.TestActor.AutoPilot
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.collection.immutable.ArraySeq
import scala.compiletime.asMatchable
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.LongPatience
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.HandshakedPeers
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config.SyncConfig

// scalastyle:off file.size.limit
class SyncControllerSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfter
    with MockFactory
    with Eventually
    with LongPatience:

  // ── T6-T9: runningRecovery state machine ──────────────────────────────────────────────────────
  // These tests drive SyncController through the post-SNAP recovery path.
  // A scan of a readable trie with nothing missing is a pass with no gaps, and goes straight to regular sync. One that
  // cannot run settles nothing (spec 022 T050a): see "settle nothing when the scan could not run" below.

  private val recoveryFakeStateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x55.toByte))

  private def seedSnapDoneWithRecovery(
      appState: com.chipprbots.ethereum.db.storage.AppStateStorage,
      needBytecode: Boolean = true,
      needStorage: Boolean = true,
      withStateRoot: Boolean = true
  ): Unit =
    appState.snapSyncDone().commit()
    if !needBytecode then appState.bytecodeRecoveryDone().commit()
    if !needStorage then appState.storageRecoveryDone().commit()
    if withStateRoot then
      appState.putSnapSyncStateRoot(recoveryFakeStateRoot).commit()
      appState.putSnapSyncPivotBlock(BigInt(100)).commit()

  /** As [[seedSnapDoneWithRecovery]], over a real trie: one account, nothing missing under it. */
  private def seedSnapDoneWithScannableRoot(
      testSetup: TestSetup,
      needBytecode: Boolean = true,
      needStorage: Boolean = true
  ): Unit =
    import testSetup.*
    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
    val appState = storagesInstance.storages.appStateStorage
    val root = com.chipprbots.ethereum.mpt
      .MerklePatriciaTrie[Array[Byte], Array[Byte]](blockchain.getBackingMptStorage(100))
      .put(Array.fill[Byte](32)(0x11), Account(nonce = UInt256(1)).toBytes)
      .getRootHash
    seedSnapDoneWithRecovery(appState, needBytecode, needStorage, withStateRoot = false)
    appState.putSnapSyncStateRoot(ByteString(root)).and(appState.putSnapSyncPivotBlock(BigInt(100))).commit()

  "SyncController" should "transition to regular sync after both bytecode and storage recovery complete" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  it should "clear the retired frontier column family once, at its first start (spec 022 T040)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    seedSnapDoneWithRecovery(appState, needBytecode = false, needStorage = false)
    val frontier = new com.chipprbots.ethereum.db.storage.HealingFrontierStorage(
      storagesInstance.storages.flatSlotStorage.dataSource
    )
    frontier.markComplete()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    frontier.isComplete shouldBe false
    appState.isHealingFrontierRetired() shouldBe true
  }

  // spec 022 review hunter: regular sync's on-demand fetches store a node alone, so whatever route starts it with SNAP not
  // done, the invariant marker cannot stand.
  private def regularSyncStarted(testSetup: TestSetup): Unit =
    import testSetup.*
    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }

  it should "take the invariant marker when SNAP's Done starts regular sync with SNAP not done" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    appState.stampSnapHealInvariant().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)
    withClue("it stands while SNAP runs: ")(appState.hasSnapHealInvariant() shouldBe true)

    syncController ! SyncController.WrappedExternal(SNAPSyncController.Done)
    regularSyncStarted(testSetup)
    appState.get(com.chipprbots.ethereum.db.storage.AppStateStorage.Keys.SnapHealInvariant) shouldBe None
  }

  it should "take the invariant marker when finalisation found no pivot header, whatever its version" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    val markerKey = com.chipprbots.ethereum.db.storage.AppStateStorage.Keys.SnapHealInvariant
    appState.put(markerKey, "4").commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    // `SnapFinalization`'s `case None`: the done flags were not written, and regular sync starts all the same.
    syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(BigInt(1)))
    regularSyncStarted(testSetup)
    appState.get(markerKey) shouldBe None
  }

  it should "leave the invariant marker when regular sync starts with SNAP done: a clean hand-off keeps it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    seedSnapDoneWithRecovery(appState, needBytecode = false, needStorage = false)
    appState.stampSnapHealInvariant().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncStarted(testSetup)
    appState.hasSnapHealInvariant() shouldBe true
  }

  it should "transition to regular sync when only bytecode recovery is needed (storage pre-done)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup, needStorage = false)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
  }

  it should "transition to regular sync when only storage recovery is needed (bytecode pre-done)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup, needBytecode = false)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe true
  }

  it should "skip recovery and start regular sync with nothing settled when there is no root to scan" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // snap done, no stateRoot/pivotBlock stored and no readable best root: nothing to scan, so nothing was scanned.
    // It costs nothing to come back to at the next start, where it has been marking done since the arm was written with
    // no stated reason (dc433be5f), and this test only pinned that.
    seedSnapDoneWithRecovery(
      storagesInstance.storages.appStateStorage,
      withStateRoot = false
    )

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe false
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe false
  }

  // ── PoS regular sync: ask peers for the block a CL head is missing ─────────────────────────────────────────────────
  // hive "Invalid Missing Ancestor Syncing ReOrg, StateRoot, EmptyTxs=True, CanonicalReOrg=True, Invalid P9": the only
  // peer handshook at genesis and never re-advertised, so regular sync never asked it for anything. See
  // MissingAncestorProbe.

  it should "probe peers for the unknown parent of a CL head held only by hash (PoS, regular sync)" taggedAs (
    UnitTest,
    SyncTest
  ) in withPosRegularSyncSetup(terminalTotalDifficulty = Some(BigInt(0))) { testSetup =>
    import testSetup.*
    startRegularSyncAndWait()

    val List(missingParent, clHead) = com.chipprbots.ethereum.BlockHelpers
      .generateChain(2, com.chipprbots.ethereum.BlockHelpers.genesis): @unchecked
    blockchainWriter.storeBlockByHashOnly(clHead).commit() // how engine_newPayload stores an ACCEPTED payload
    forkChoiceManager.notifyBeaconHead(
      com.chipprbots.ethereum.consensus.engine.ForkChoiceState(clHead.hash.value, zero32, zero32)
    )

    networkPeerManager.fishForMessage(10.seconds, "ProbeMissingAncestorCmd for the CL head's parent") {
      case NetworkPeerManagerActor.ProbeMissingAncestorCmd(hash, number) =>
        hash shouldBe missingParent.hash.value
        number shouldBe missingParent.number.value
        true
      case _ => false
    }
  }

  it should "never probe on a chain with no terminal total difficulty — the ETC/Mordor shape" taggedAs (
    UnitTest,
    SyncTest
  ) in withPosRegularSyncSetup(terminalTotalDifficulty = None) { testSetup =>
    import testSetup.*
    startRegularSyncAndWait()

    val List(_, clHead) = com.chipprbots.ethereum.BlockHelpers
      .generateChain(2, com.chipprbots.ethereum.BlockHelpers.genesis): @unchecked
    blockchainWriter.storeBlockByHashOnly(clHead).commit()
    // Without a TTD the SyncController never registers as the ForkChoiceManager listener, so this reaches nobody.
    forkChoiceManager.notifyBeaconHead(
      com.chipprbots.ethereum.consensus.engine.ForkChoiceState(clHead.hash.value, zero32, zero32)
    )

    val received = networkPeerManager.receiveWhile(2.seconds) { case m => m }
    received.collect { case p: NetworkPeerManagerActor.ProbeMissingAncestorCmd => p } shouldBe empty
  }

  // ── T10-T13: startup diagnostic + handler tests ───────────────────────────────────────────────
  // RLP encoding of a 32-byte hash = valid HashNode (length==MaxEncodedNodeLength → no MPTException)
  private def validMptNodeRlp(hash: ByteString): Array[Byte] = Array(0xa0.toByte) ++ hash.toArray

  private def seedMptNode(
      testSetup: TestSetup,
      hashes: ByteString*
  ): Unit =
    testSetup.storagesInstance.storages.nodeStorage.update(
      Seq.empty,
      hashes.map(h => (h, validMptNodeRlp(h)))
    )

  // A header's hash covers its stateRoot, so rewriting it forges a block, and storeBlockHeader repoints number->hash at
  // the forgery. The two tests that used to stand here (SC-1a, SC-1b) asserted exactly that rewrite; they are replaced
  // by tests that the startup leaves every header and mapping untouched.
  private def storeFullBlock(testSetup: TestSetup, header: BlockHeader): Unit =
    testSetup.blockchainWriter.storeBlock(Block(header, BlockBody.empty)).commit()

  private def snapshotOf(
      testSetup: TestSetup,
      numbers: Seq[Int]
  ): Seq[(Int, Option[ByteString], Option[ByteString])] =
    numbers.map { n =>
      val hash = testSetup.blockchainReader.getCanonicalHashByNumber(n).map(_.value)
      val headerBytes =
        hash.flatMap(h => testSetup.blockchainReader.getBlockHeaderByHash(BlockHash(h))).map(_.hash.value)
      (n, hash, headerBytes)
    }

  private def markSnapDone(testSetup: TestSetup): Unit =
    val st = testSetup.storagesInstance.storages.appStateStorage
    st.snapSyncDone().commit()
    st.bytecodeRecoveryDone().commit()
    st.storageRecoveryDone().commit()

  private def awaitRegularSync(testSetup: TestSetup): Unit =
    import testSetup.*
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }

  it should "leave headers and mappings byte-identical on restart after SNAP plus blocks above the pivot (SC-1a)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val rootPivot = ByteString(Array.fill[Byte](32)(0x11)) // pivot header's root, in the MPT
    val rootSnap = ByteString(Array.fill[Byte](32)(0x22)) // snapSyncStateRoot, differs from the pivot's, in the MPT
    val pivot = baseBlockHeader.copy(number = BlockNumber(100), stateRoot = TrieRoot(rootPivot))
    val above1 = baseBlockHeader.copy(
      number = BlockNumber(101),
      parentHash = pivot.hash,
      stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x55)))
    )
    val above2 = baseBlockHeader.copy(
      number = BlockNumber(102),
      parentHash = above1.hash,
      stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x66)))
    )
    seedMptNode(testSetup, rootPivot, rootSnap)
    storeFullBlock(testSetup, pivot)
    storeFullBlock(testSetup, above1)
    storeFullBlock(testSetup, above2)
    val st = storagesInstance.storages.appStateStorage
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(above2.hash.value, 102)).commit()
    st.putSnapSyncPivotBlock(100).commit()
    st.putSnapSyncStateRoot(rootSnap).commit()
    markSnapDone(testSetup)

    val before = snapshotOf(testSetup, 100 to 102)
    awaitRegularSync(testSetup)

    snapshotOf(testSetup, 100 to 102) shouldBe before
    blockchainReader.getBlockHeaderByNumber(102).map(_.hash) shouldBe Some(above2.hash)
    blockchainReader.getBlockHeaderByNumber(100) shouldBe Some(pivot)
    st.getBestBlockInfo().hash shouldBe above2.hash.value
  }

  it should "not rewrite the pivot header when its stateRoot is missing from the MPT and best block is the pivot (SC-1b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val rootA = ByteString(Array.fill[Byte](32)(0x33)) // in the pivot header, NOT in MPT
    val rootB = ByteString(Array.fill[Byte](32)(0x44)) // finalizedRoot, present in MPT
    val pivot = baseBlockHeader.copy(number = BlockNumber(100), stateRoot = TrieRoot(rootA))
    seedMptNode(testSetup, rootB)
    storeFullBlock(testSetup, pivot)
    val st = storagesInstance.storages.appStateStorage
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(pivot.hash.value, 100)).commit()
    st.putSnapSyncPivotBlock(100).commit()
    st.putSnapSyncStateRoot(rootB).commit()
    st.putSnapSyncFinalizedRoot(rootB).commit()
    markSnapDone(testSetup)

    val before = snapshotOf(testSetup, 100 to 100)
    awaitRegularSync(testSetup)

    snapshotOf(testSetup, 100 to 100) shouldBe before
    blockchainReader.getBlockHeaderByNumber(100).map(_.stateRoot) shouldBe Some(TrieRoot(rootA))
  }

  it should "restore a number->hash mapping that names a forged header, and the SyncController start leaves the restored index alone" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val rootSnap = ByteString(Array.fill[Byte](32)(0x22))
    val real =
      baseBlockHeader.copy(number = BlockNumber(100), stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x11))))
    val forged = real.copy(stateRoot = TrieRoot(rootSnap))
    forged.hash should not be real.hash
    seedMptNode(testSetup, real.stateRoot.value, rootSnap)
    storeFullBlock(testSetup, real)
    blockchainWriter.storeBlockHeader(forged).commit() // what the removed branch did: header only, mapping repointed
    val st = storagesInstance.storages.appStateStorage
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(real.hash.value, 100)).commit()
    st.putSnapSyncPivotBlock(100).commit()
    st.putSnapSyncStateRoot(rootSnap).commit()
    markSnapDone(testSetup)
    blockchainReader.getCanonicalHashByNumber(100).map(_.value) shouldBe Some(forged.hash.value)

    // The repair runs in StdNode.start (before any server binds); SyncController.start must not touch the index.
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) shouldBe
      BestMappingRepair.Restored(100, real.hash, forged.hash, removedOrphan = true)
    awaitRegularSync(testSetup)

    blockchainReader.getCanonicalHashByNumber(100).map(_.value) shouldBe Some(real.hash.value)
    blockchainReader.getBlockHeaderByHash(real.hash) shouldBe Some(real)
    blockchainReader.getBlockHeaderByHash(forged.hash) shouldBe None // header-only orphan removed
    blockchainReader.getBlockBodyByHash(real.hash) shouldBe Some(BlockBody.empty)
  }

  it should "repair only a mapping that disagrees with a fully stored best block, and refuse otherwise" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val st = storagesInstance.storages.appStateStorage
    val real = baseBlockHeader.copy(number = BlockNumber(100))
    val forged = real.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x22))))

    // Consistent: nothing to do.
    storeFullBlock(testSetup, real)
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(real.hash.value, 100)).commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) shouldBe BestMappingRepair.Consistent

    // Broken mapping, best header and body present: restored.
    blockchainWriter.storeBlockHeader(forged).commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) shouldBe
      BestMappingRepair.Restored(100, real.hash, forged.hash, removedOrphan = true)
    blockchainReader.getCanonicalHashByNumber(100).map(_.value) shouldBe Some(real.hash.value)
    // Idempotent.
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) shouldBe BestMappingRepair.Consistent

    // Best block's body missing: refuse, touch nothing.
    val other = baseBlockHeader.copy(number = BlockNumber(200))
    val otherForged = other.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x77))))
    blockchainWriter.storeBlockHeader(other).commit() // header, no body
    blockchainWriter.storeBlockHeader(otherForged).commit() // mapping now names otherForged
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(other.hash.value, 200)).commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) match
      case BestMappingRepair.Refused(200, h, m, true, false, _) =>
        h shouldBe other.hash
        m shouldBe otherForged.hash.value
      case unexpected => fail(s"expected Refused, got $unexpected")
    blockchainReader.getBlockHeaderByHash(otherForged.hash) shouldBe Some(otherForged)

    // Best block's header missing entirely: refuse.
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(ByteString(Array.fill[Byte](32)(0x09)), 200))
      .commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) match
      case BestMappingRepair.Refused(200, _, _, false, false, _) => succeed
      case unexpected                                            => fail(s"expected Refused, got $unexpected")
  }

  it should "refuse a mapping naming a fully stored different-parent block (interrupted reorg shape)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val st = storagesInstance.storages.appStateStorage
    val best = baseBlockHeader.copy(number = BlockNumber(100))
    val newBranch = best.copy(parentHash = BlockHash(ByteString(Array.fill[Byte](32)(0x0a))))
    storeFullBlock(testSetup, best)
    storeFullBlock(testSetup, newBranch) // mapping[100] now names the new-branch block, body stored
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(best.hash.value, 100)).commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) match
      case BestMappingRepair.Refused(100, _, _, true, true, _) => succeed
      case unexpected                                          => fail(s"expected Refused, got $unexpected")
    blockchainReader.getCanonicalHashByNumber(100).map(_.value) shouldBe Some(newBranch.hash.value)
    blockchainReader.getBlockHeaderByHash(newBranch.hash) shouldBe Some(newBranch)
  }

  it should "restore a same-parent mapped block that has a body without deleting it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val st = storagesInstance.storages.appStateStorage
    val best = baseBlockHeader.copy(number = BlockNumber(100))
    val sibling = best.copy(stateRoot = TrieRoot(ByteString(Array.fill[Byte](32)(0x22))))
    storeFullBlock(testSetup, best)
    storeFullBlock(testSetup, sibling)
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(best.hash.value, 100)).commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) shouldBe
      BestMappingRepair.Restored(100, best.hash, sibling.hash, removedOrphan = false)
    blockchainReader.getCanonicalHashByNumber(100).map(_.value) shouldBe Some(best.hash.value)
    blockchainReader.getBlockHeaderByHash(sibling.hash) shouldBe Some(sibling)
  }

  it should "refuse and not delete a header-only mapped block with a different parent (SNAP header-only entries)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val st = storagesInstance.storages.appStateStorage
    val best = baseBlockHeader.copy(number = BlockNumber(100))
    val snapHeader = best.copy(parentHash = BlockHash(ByteString(Array.fill[Byte](32)(0x0b))))
    storeFullBlock(testSetup, best)
    blockchainWriter.storeBlockHeader(snapHeader).commit() // header only, mapping repointed
    st.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(best.hash.value, 100)).commit()
    blockchainWriter.repairBestBlockNumberMapping(blockchainReader) match
      case BestMappingRepair.Refused(100, _, _, true, true, _) => succeed
      case unexpected                                          => fail(s"expected Refused, got $unexpected")
    blockchainReader.getBlockHeaderByHash(snapHeader.hash) shouldBe Some(snapHeader)
    blockchainReader.getCanonicalHashByNumber(100).map(_.value) shouldBe Some(snapHeader.hash.value)
  }

  it should "clear both done flags and restart SNAP when HealingImpossible is received" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // No snapSyncDone → start() → case (false, _, true, _) → startSnapSync()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }

    // Manually set both flags so we can verify HealingImpossible clears them
    storagesInstance.storages.appStateStorage.snapSyncDone().commit()
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.HealingImpossible)

    // HealingImpossible clears both flags synchronously
    storagesInstance.storages.appStateStorage.isSnapSyncDone() shouldBe false
    storagesInstance.storages.appStateStorage.isFastSyncDone() shouldBe false

    // startSnapSync() spawned a new generation; eventually a snap-sync child is visible
    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }
  }

  // B-304: a sample that keeps stopping the hand-off restarts SNAP twice, and the third time the sync stops; B-319:
  // the controller then answers what asks after the sync with why.
  it should "restart SNAP for the hand-off's first two samples that stop it, and stop the sync on the third, saying why" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.PathHandoffSample
    val appState = storagesInstance.storages.appStateStorage
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    def snapChild(): String =
      eventually {
        someTimePasses()
        syncController.children.find(_.path.name.startsWith("snap-sync")).get.path.name
      }
    var current = snapChild()
    val counts = PathHandoffSample.Result(sampledAccountLeaves = 4096, sampledMissing = 3)
    def sampleStopped(): Unit =
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.HandoffSampleStopped(PathHandoffSample.StopReason.StorageHole, counts, BigInt(4000))
      )

    (1 to 2).foreach { n =>
      appState.snapSyncDone().commit()
      val warnings = syncControllerLogsDuring(ch.qos.logback.classic.Level.WARN)(sampleStopped())
      withClue(s"sample $n: ")(
        warnings.exists(_.contains(s"$n of 3 since the last hand-off: restarting SNAP")) shouldBe true
      )
      // restarted as for HealingImpossible: the done flag cleared and a SNAP of the next generation
      appState.isSnapSyncDone() shouldBe false
      val next = eventually {
        someTimePasses()
        val name = snapChild()
        name should not be current
        name
      }
      current = next
    }

    val errors = syncControllerErrorsDuring(sampleStopped())
    errors.exists(e => e.contains("3 times since the last hand-off") && e.contains(counts.describe)) shouldBe true
    // B-319: the controller does not stop, so that what asks after the sync is answered, with why; and its SNAP
    // child is stopped
    eventually {
      someTimePasses()
      assert(!syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }
    syncController.underlying.isTerminated shouldBe false
    val status = TestProbe()(using system)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(status.ref.toTyped))
    status.expectMsgType[SyncProtocol.Status.Stopped].reason should (include("3 times").and(include(counts.describe)))
    // and nothing restarts it
    sampleStopped()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    someTimePasses()
    syncController.children.exists(_.path.name.startsWith("snap-sync")) shouldBe false
  }

  // B-304: the count is of samples since the last hand-off
  it should "count the hand-off's samples again from one after a hand-off was made" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.PathHandoffSample
    def sampleStopped(): List[String] =
      syncControllerLogsDuring(ch.qos.logback.classic.Level.WARN) {
        syncController ! SyncController.WrappedSyncProtocol(
          SyncProtocol.HandoffSampleStopped(
            PathHandoffSample.StopReason.AccountHole,
            PathHandoffSample.Result(),
            BigInt(4000)
          )
        )
      }
    def snapRunning(): Unit =
      eventually {
        someTimePasses()
        assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
      }
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    snapRunning()
    sampleStopped().exists(_.contains("1 of 3 since the last hand-off")) shouldBe true
    snapRunning()
    sampleStopped().exists(_.contains("2 of 3 since the last hand-off")) shouldBe true
    awaitSnapStarted(syncController)

    // a hand-off, then a re-SNAP from regular sync: the next sample that stops it is the first in a row again
    syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(BigInt(1)))
    regularSyncChild(testSetup)
    val backfill = snapChildren(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(BigInt(500), "deadbeef"))
    eventually {
      someTimePasses()
      assert((snapChildren(testSetup) -- backfill).nonEmpty)
    }
    sampleStopped().exists(_.contains("1 of 3 since the last hand-off")) shouldBe true
    syncController.underlying.isTerminated shouldBe false
  }

  it should "clear sync flags and restart SNAP with minPivotBlock when RegularSyncStuck is received (SC-4)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    // doSnapSync=false; pre-set fastSyncDone → case (_, _, Regular) → startRegularSync()
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }

    syncController ! SyncController.WrappedSyncProtocol(
      SyncProtocol.RegularSyncStuck(BigInt(24601125), "deadbeefdeadbeef")
    )

    storagesInstance.storages.appStateStorage.isSnapSyncDone() shouldBe false
    storagesInstance.storages.appStateStorage.isFastSyncDone() shouldBe false

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }
  }

  // Spec 018 C5a part 7b: the state given up. The handler decides; the controller stops the import, clears the state
  // with the record of why, counts it, and syncs again, with the pivot no lower than the refused branch's first block.
  private class EscalationPort(answers: EscalationHandler.Outcome*) extends SyncController.StateEscalationPort:
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    private val left = new java.util.concurrent.ConcurrentLinkedQueue[EscalationHandler.Outcome]()
    answers.foreach(left.add)
    val asked = new java.util.concurrent.atomic.AtomicInteger(0)
    val cleared = new java.util.concurrent.ConcurrentLinkedQueue[(StateEscalation, Option[GivenUpBranch], Boolean)]()
    val counted = new java.util.concurrent.ConcurrentLinkedQueue[EscalationKind]()
    def decide(request: EscalationWatch.Request, nowMillis: Long): EscalationHandler.Outcome =
      asked.incrementAndGet()
      Option(left.poll()).getOrElse(EscalationHandler.Outcome.Repeated)
    @volatile var clears = true
    // held while a test looks at the controller in the middle of a clear
    @volatile var holdTheClear: Option[java.util.concurrent.CountDownLatch] = None
    @volatile var clearFails: Option[RuntimeException] = None
    val clearThreads = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    def clear(record: StateEscalation, givenUpFor: Option[GivenUpBranch]): Boolean =
      clearThreads.add(Thread.currentThread.getName)
      holdTheClear.foreach(_.await(60, java.util.concurrent.TimeUnit.SECONDS))
      clearFails.foreach(throw _)
      cleared.add((record, givenUpFor, counted.isEmpty))
      clears
    def escalated(kind: EscalationKind): Unit = counted.add(kind)
    val regularSyncStarts = new java.util.concurrent.atomic.AtomicInteger(0)
    def regularSyncStarted(): Unit =
      regularSyncStarts.incrementAndGet(); ()
    val refusals = new java.util.concurrent.atomic.AtomicInteger(0)
    def clearRefused(): Unit =
      refusals.incrementAndGet(); ()
    val branchRetries = new java.util.concurrent.atomic.AtomicInteger(0)
    @volatile var branchRetryFails: Option[RuntimeException] = None
    def clearForBranchRetry(): Boolean =
      clearThreads.add(Thread.currentThread.getName)
      branchRetries.incrementAndGet()
      branchRetryFails.foreach(throw _)
      true

  private def escalationRequest: SyncProtocol.StateEscalationRequested =
    SyncProtocol.StateEscalationRequested(
      EscalationWatch.Request(
        com.chipprbots.ethereum.db.storage.pathstate.EscalationKind.Unreachable,
        None,
        None,
        "the heavier branch [4001-4050] cannot be reached"
      )
    )

  it should "give the state up when the escalation's handler says so: clear, count, and SNAP again (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record =
      StateEscalation(1763236800000L, EscalationKind.Unreachable, ByteString(Array.fill[Byte](32)(0x21)), 4001)
    val branch = GivenUpBranch(4001, record.headHash)
    val port =
      new EscalationPort(EscalationHandler.Outcome.Escalate(record, Some(branch), "a branch cannot be reached"))
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    val before = snapChildren(testSetup)

    val errors = syncControllerErrorsDuring {
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      eventually {
        someTimePasses()
        assert((snapChildren(testSetup) -- before).nonEmpty)
      }
    }
    // cleared once, with the record and the branch, and counted after the clear
    port.cleared.toArray.toList shouldBe List((record, Some(branch), true))
    port.counted.toArray.toList shouldBe List(EscalationKind.Unreachable)
    errors.exists(_.contains("[STATE-ESCALATION]")) shouldBe true
    // a verdict told again while the sync runs is not asked about: the state is being synced
    syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
    someTimePasses()
    port.asked.get() shouldBe 1
    port.cleared.size() shouldBe 1
  }

  // B-307 (c): the sync after an escalation for a branch found another chain's headers under its pivot
  it should "stop a SNAP sync that is off the branch its pivot is to be on, clear what it wrote, and SNAP again" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record =
      StateEscalation(1763236800000L, EscalationKind.Unreachable, ByteString(Array.fill[Byte](32)(0x21)), 4001)
    val port = new EscalationPort(
      EscalationHandler.Outcome
        .Escalate(record, Some(GivenUpBranch(4001, record.headHash)), "a branch cannot be reached")
    )
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    val before = snapChildren(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
    eventually {
      someTimePasses()
      assert((snapChildren(testSetup) -- before).nonEmpty)
    }
    val first = snapChildren(testSetup)

    // the requirement the clear of the escalation wrote down: the floor of every sync until one hands off
    storagesInstance.storages.appStateStorage
      .putPivotOnBranch(com.chipprbots.ethereum.db.storage.pathstate.PivotOnBranch(4001, record.headHash))
      .commit()
    val said = syncControllerLogsDuring(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.PivotOffTheBranch)
      eventually {
        someTimePasses()
        port.branchRetries.get() shouldBe 1
        // the sync that was off the branch is gone, and another runs
        assert((snapChildren(testSetup) -- first).nonEmpty)
        assert((snapChildren(testSetup) & first).isEmpty)
      }
    }
    // begun again with its pivot no lower than the branch's first block
    said.exists(_.contains("MinPivotBlock(4001)")) shouldBe true
    // off the controller's thread, as the clear of the escalation; and no second escalation is counted or recorded
    port.clearThreads.toArray.toList.map(_.toString).exists(_.contains("blocking")) shouldBe true
    port.cleared.size() shouldBe 1
    port.counted.size() shouldBe 1
  }

  // B-307: a recovery of the state that is still executing a block keeps the clear from beginning
  it should "go on with regular sync, and count nothing, when the state could not be cleared (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record = StateEscalation(1763236800000L, EscalationKind.LocalFault, ByteString(Array.fill[Byte](32)(5)), 7000)
    val port = new EscalationPort(EscalationHandler.Outcome.Escalate(record, None, "a fault"))
    port.clears = false
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)
    val errors = syncControllerErrorsDuring {
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      eventually {
        someTimePasses()
        port.cleared.size() shouldBe 1
      }
    }
    someTimePasses()
    errors.exists(_.contains("was not cleared")) shouldBe true
    port.counted.size() shouldBe 0
    port.refusals.get() shouldBe 1
    snapChildren(testSetup) shouldBe empty
    regularSyncChild(testSetup) shouldBe running
  }

  // B-307 (forge): the clear waits for a move of the head, deletes ranges and writes synced: off the controller's thread
  it should "clear off its own thread, answer meanwhile, and give up one state at a time (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record = StateEscalation(1763236800000L, EscalationKind.LocalFault, ByteString(Array.fill[Byte](32)(5)), 7000)
    val escalate = EscalationHandler.Outcome.Escalate(record, None, "a fault")
    val port = new EscalationPort(escalate, escalate)
    val release = new java.util.concurrent.CountDownLatch(1)
    port.holdTheClear = Some(release)
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)
    try
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      eventually {
        someTimePasses()
        port.clearThreads.size() shouldBe 1
      }
      // the clear is held: the controller is not, and the import is not stopped before the clear has ended
      port.clearThreads.peek() should not be Thread.currentThread.getName
      regularSyncChild(testSetup) shouldBe running
      snapChildren(testSetup) shouldBe empty
      // a second verdict meanwhile is not asked about
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      someTimePasses()
      port.asked.get() shouldBe 1
    finally release.countDown()
    eventually {
      someTimePasses()
      assert(snapChildren(testSetup).nonEmpty)
    }
    port.cleared.size() shouldBe 1
    port.counted.size() shouldBe 1
  }

  // beacon: what regular sync is told while the clear runs is told once; dropped, the sync that resumes never hears it
  it should "hand on, when the clear could not begin, what arrived while it ran (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record = StateEscalation(1763236800000L, EscalationKind.LocalFault, ByteString(Array.fill[Byte](32)(5)), 7000)
    val port = new EscalationPort(EscalationHandler.Outcome.Escalate(record, None, "a fault"))
    port.clears = false
    val release = new java.util.concurrent.CountDownLatch(1)
    port.holdTheClear = Some(release)
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    val before = snapChildren(testSetup)
    try
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      eventually {
        someTimePasses()
        port.clearThreads.size() shouldBe 1
      }
      // a second verdict meanwhile is dropped, not kept for later: it is of the clear that is under way
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      // regular sync reports itself stuck while the clear is held: not acted on now, and not lost
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(BigInt(500), "deadbeef"))
      someTimePasses()
      snapChildren(testSetup) shouldBe before
    finally release.countDown()
    // the clear could not begin: regular sync goes on, and the report is acted on then (a re-SNAP)
    eventually {
      someTimePasses()
      assert((snapChildren(testSetup) -- before).nonEmpty)
    }
    port.counted.size() shouldBe 0
    port.refusals.get() shouldBe 1
    port.asked.get() shouldBe 1
  }

  it should "stop, and start no sync, when the clear of the state fails (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record = StateEscalation(1763236800000L, EscalationKind.LocalFault, ByteString(Array.fill[Byte](32)(5)), 7000)
    val port = new EscalationPort(EscalationHandler.Outcome.Escalate(record, None, "a fault"))
    port.clearFails = Some(new IllegalStateException("the disk is full"))
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    val errors = syncControllerErrorsDuring {
      syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
      eventually {
        someTimePasses()
        port.clearThreads.size() shouldBe 1
      }
      eventually {
        someTimePasses()
        syncController.underlying.isTerminated shouldBe true
      }
    }
    errors.exists(_.contains("The clear of the state failed")) shouldBe true
    port.counted.size() shouldBe 0
  }

  // B-311: the import and the audit are stopped once the clear has ended, and the min pivot reaches the SNAP child
  it should "stop regular sync and the audit when the clear has ended, and start SNAP no lower than the branch (B-311)" taggedAs (
    UnitTest,
    SyncTest
  ) in withAuditOn() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record =
      StateEscalation(1763236800000L, EscalationKind.Unreachable, ByteString(Array.fill[Byte](32)(0x21)), 4001)
    val port = new EscalationPort(
      EscalationHandler.Outcome.Escalate(record, Some(GivenUpBranch(4001, record.headHash)), "a branch")
    )
    val release = new java.util.concurrent.CountDownLatch(1)
    port.holdTheClear = Some(release)
    stateEscalation = Some(port)
    seedSnapDoneWithScannableRoot(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)
    val audit = auditName(testSetup).getOrElse(fail("the audit is not running"))

    val said = syncControllerLogsDuring(ch.qos.logback.classic.Level.INFO) {
      try
        syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
        eventually {
          someTimePasses()
          port.clearThreads.size() shouldBe 1
        }
        // not before the clear has ended: a clear that could not begin leaves them as they were
        regularSyncChild(testSetup) shouldBe running
        auditName(testSetup) shouldBe Some(audit)
      finally release.countDown()
      eventually {
        someTimePasses()
        assert(snapChildren(testSetup).nonEmpty)
      }
    }
    childIsGone(testSetup, running)
    childIsGone(testSetup, audit)
    auditName(testSetup) shouldBe None
    // the SNAP of the first escalation is told its pivot is no lower than the branch's first block
    said.exists(_.contains("MinPivotBlock(4001)")) shouldBe true
    port.counted.size() shouldBe 1
  }

  // B-311: a verdict that arrives while SNAP's backfill still runs stops the backfill, and is handed on to regular sync
  it should "stop SNAP's backfill first, then give the state up, when the verdict arrives during the backfill (B-311)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record = StateEscalation(1763236800000L, EscalationKind.LocalFault, ByteString(Array.fill[Byte](32)(5)), 7000)
    val port = new EscalationPort(EscalationHandler.Outcome.Escalate(record, None, "a fault"))
    stateEscalation = Some(port)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)
    // SNAP hands over to regular sync and keeps running as the chain's backfill
    syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(BigInt(1)))
    regularSyncChild(testSetup)
    val backfill = snapChildren(testSetup)
    backfill should not be empty

    syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
    eventually {
      someTimePasses()
      port.cleared.size() shouldBe 1
      // the backfill is gone, and the SNAP of the sync from scratch runs
      assert((snapChildren(testSetup) & backfill).isEmpty)
      assert(snapChildren(testSetup).nonEmpty)
    }
    port.asked.get() shouldBe 1
    port.counted.size() shouldBe 1
  }

  // B-311: the clear that begins a sync again for the branch fails: the controller stops, as for the escalation's clear
  it should "stop, and start no sync, when the clear for the branch's retry fails (B-311)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
    import com.chipprbots.ethereum.db.storage.pathstate.GivenUpBranch
    import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
    val record =
      StateEscalation(1763236800000L, EscalationKind.Unreachable, ByteString(Array.fill[Byte](32)(0x21)), 4001)
    val port = new EscalationPort(
      EscalationHandler.Outcome.Escalate(record, Some(GivenUpBranch(4001, record.headHash)), "a branch")
    )
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    val before = snapChildren(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
    eventually {
      someTimePasses()
      assert((snapChildren(testSetup) -- before).nonEmpty)
    }
    val first = snapChildren(testSetup)
    port.branchRetryFails = Some(new IllegalStateException("the disk is full"))

    val errors = syncControllerErrorsDuring {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.PivotOffTheBranch)
      eventually {
        someTimePasses()
        syncController.underlying.isTerminated shouldBe true
      }
    }
    errors.exists(_.contains("[STATE-ESCALATION-PIVOT] The clear of the state failed")) shouldBe true
    port.branchRetries.get() shouldBe 1
    // no sync was begun over rows half cleared
    (snapChildren(testSetup) -- first) shouldBe empty
  }

  it should "leave regular sync as it is when the handler does not say to give the state up (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val port = new EscalationPort(
      EscalationHandler.Outcome.Suppressed,
      EscalationHandler.Outcome.Dropped,
      EscalationHandler.Outcome.Unreadable
    )
    stateEscalation = Some(port)
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)
    // the handler is told that regular sync began, so that what it answered before is forgotten
    port.regularSyncStarts.get() shouldBe 1
    (1 to 4).foreach(_ => syncController ! SyncController.WrappedSyncProtocol(escalationRequest))
    someTimePasses()
    port.asked.get() shouldBe 4
    port.cleared.size() shouldBe 0
    port.counted.size() shouldBe 0
    snapChildren(testSetup) shouldBe empty
    regularSyncChild(testSetup) shouldBe running
  }

  it should "drop a verdict to give the state up while it was not started (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val port = new EscalationPort(EscalationHandler.Outcome.Suppressed)
    stateEscalation = Some(port)
    syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
    someTimePasses()
    port.asked.get() shouldBe 0
    port.cleared.size() shouldBe 0
    syncController.children.exists(_.path.name.startsWith("snap-sync")) shouldBe false
  }

  it should "drop a verdict to give the state up on the hash layout (C5a part 7b)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(escalationRequest)
    someTimePasses()
    snapChildren(testSetup) shouldBe empty
    regularSyncChild(testSetup) shouldBe running
  }

  // H3: a stored node no fetch repairs sent regular sync to SNAP, and the next SNAP back to the same node, with no cap.
  // The first two re-SNAPs in a row with no block imported between them go at once, as before. From the third, regular
  // sync keeps running (on ETC a stopped node imports nothing while it waits) and the re-SNAP is scheduled an hour on,
  // doubling each time; an import meanwhile drops it.

  // ── Spec 018 C5a part 10: a sync on the unified state layout ─────────────────────────────────────────────────────

  /** What the port answers before SNAP, and what it was asked; the hand-off itself is never reached here. */
  private class GuardPort(answers: SyncController.SnapStartOnGuard*) extends SyncController.PathLiveSyncPort:
    val asked = new java.util.concurrent.atomic.AtomicInteger(0)
    val askedOn = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val handedOffs = new java.util.concurrent.atomic.AtomicInteger(0)
    // no SNAP child here reaches its finalisation, the one reader of the hand-off
    def handoff: com.chipprbots.ethereum.db.storage.pathstate.SyncHandoff = null
    // nor does a checkpoint import
    def checkpointTarget: com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.UnifiedTarget = null
    def beforeSnap(): SyncController.SnapStartOnGuard =
      askedOn.add(Thread.currentThread.getName)
      val n = asked.incrementAndGet()
      answers(math.min(n, answers.size) - 1)
    def handedOff(): Unit = handedOffs.incrementAndGet(): Unit
    // spec 018 C6: what the precondition counts against, and whether the node holds its genesis state alone
    @volatile var snapPeers: PathLiveSync.SnapPeerPrecondition =
      PathLiveSync.SnapPeerPrecondition(minSnapPeers = 2, powWait = 1.minute, declared = Set.empty)
    @volatile var onlyGenesis: Boolean = false
    def holdsOnlyGenesis(): Boolean = onlyGenesis

  "On the unified state layout, SyncController" should "ask the port before SNAP, off its own thread, and begin SNAP when it says so" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    pathLiveSync = Some(port)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(snapChildren(testSetup).nonEmpty)
    }
    port.asked.get() shouldBe 1
    withClue("on the blocking dispatcher: ")((port.askedOn.peek() should not).include("sync-dispatcher"))
  }

  it should "run regular sync on the state that is held when the port refuses SNAP, and say so" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Refused("this node holds the state of block 9"))
    pathLiveSync = Some(port)
    val errors = syncControllerErrorsDuring {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      regularSyncChild(testSetup)
    }
    snapChildren(testSetup) shouldBe empty
    errors.exists(e => e.contains("[STATE-PATH-LIVE]") && e.contains("block 9")) shouldBe true
  }

  it should "not re-SNAP a regular sync that is stuck, and leave it running" taggedAs (UnitTest, SyncTest) in
    withRecoveryTestSetup() { testSetup =>
      import testSetup.*
      val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = false))
      pathLiveSync = Some(port)
      storagesInstance.storages.appStateStorage.fastSyncDone().commit()
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      val running = regularSyncChild(testSetup)
      val errors = stuckReport(testSetup)
      someTimePasses()
      snapChildren(testSetup) shouldBe empty
      port.asked.get() shouldBe 0
      regularSyncChild(testSetup) shouldBe running
      errors.exists(_.contains("no SNAP re-sync starts over the state that is held")) shouldBe true
      storagesInstance.storages.appStateStorage.regularSyncStuckResnaps() shouldBe 0
    }

  it should "go to regular sync after a sync that is done, with no recovery scan, no audit and no bulk code recovery" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    pathLiveSync = Some(new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = false)))
    // a recovery flag down: on the hash layout the start runs the scan (the case above)
    seedSnapDoneWithScannableRoot(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)
    assert(!childNamed(testSetup, "combined-recovery-scan"))
    assert(!childNamed(testSetup, "background-audit"))
    syncController ! SyncController.WrappedSyncProtocol(
      SyncProtocol.MissingCodeNeedsBulkRecovery(500, ByteString(Array.fill[Byte](32)(3)))
    )
    someTimePasses()
    assert(!childNamed(testSetup, "bytecode-recovery"))
    regularSyncChild(testSetup) shouldBe running
  }

  it should "tell the port of a hand-off, so that the escalation's count begins with it" taggedAs (
    UnitTest,
    SyncTest
  ) in
    withRecoveryTestSetup() { testSetup =>
      import testSetup.*
      val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = false))
      pathLiveSync = Some(port)
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      // on proof of work the snap peers are counted first (spec 018 C6): the count that does not come is given up on
      eventually {
        someTimePasses()
        assert(snapChildren(testSetup).nonEmpty)
      }
      awaitSnapStarted(syncController)
      syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(BigInt(1)))
      regularSyncChild(testSetup)
      port.handedOffs.get() shouldBe 1
    }

  it should "ask again after a while when the port says not yet, keeping what arrived meanwhile" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(
      SyncController.SnapStartOnGuard.Wait("a recovery was executing a block"),
      SyncController.SnapStartOnGuard.Begin(clearedGenesis = true)
    )
    pathLiveSync = Some(port)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      port.asked.get() shouldBe 1
    }
    snapChildren(testSetup) shouldBe empty
    assert(!childNamed(testSetup, "regular-sync"))
    timePasses(SyncController.SnapStartRecheckDelay)
    eventually {
      someTimePasses()
      assert(snapChildren(testSetup).nonEmpty)
    }
    port.asked.get() shouldBe 2
  }

  // ── Spec 018 C6 (T241/T242): the snap-peer precondition, and regular sync from the genesis on proof of work ───────
  // The user's decisions of 2026-11-18: on proof of work a guard node at its genesis waits for snap peers and, with none,
  // regular-syncs from its genesis; a declared peer counts, and the line says how many of the count were declared.

  /** `n` handshaked peers on this chain, the first `snap` of them advertising snap, each with a node id of its own. */
  private def peersOnTheChain(testSetup: TestSetup, n: Int, snap: Int): Map[Peer, PeerInfo] =
    import testSetup.*
    (1 to n).map { i =>
      val status = peer1Status.copy(capability = Capability.ETH68, supportsSnap = i <= snap)
      val peer = peer1.copy(id = com.chipprbots.ethereum.network.PeerId(s"p$i"), nodeId = Some(nodeIdOf(i)))
      peer -> PeerInfo(status, status.chainWeight, forkAccepted = true, BigInt(100), status.bestHash)
    }.toMap

  private def nodeIdOf(i: Int): ByteString = ByteString(Array.fill[Byte](64)(i.toByte))

  private def syncControllerLines(level: ch.qos.logback.classic.Level)(f: => Unit): List[String] =
    syncControllerLogsDuring(level)(f)

  "On proof of work, on the unified layout, SyncController" should "regular-sync from the genesis when no snap peer comes in the wait, and say so once at WARN with the count" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    com.chipprbots.ethereum.blockchain.sync.snap.HeaderChainCheck.isProofOfWork(blockchainConfig) shouldBe true
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    pathLiveSync = Some(port)
    answerPeerPollsWith(peersOnTheChain(testSetup, n = 3, snap = 0))
    val warnings = syncControllerLines(ch.qos.logback.classic.Level.WARN) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      (1 to 5).foreach(_ => someTimePasses())
      withClue("inside the wait: ") {
        port.asked.get() shouldBe 0
        snapChildren(testSetup) shouldBe empty
        assert(!childNamed(testSetup, "regular-sync"))
      }
      withClue("eth_syncing is answered inside the wait, not held: ") {
        val asker = TestProbe()
        syncController ! SyncController.WrappedSyncProtocol(
          SyncProtocol.GetStatus(asker.ref.toTyped[SyncProtocol.Status])
        )
        asker.expectMsgType[SyncProtocol.Status.Syncing]
      }
      timePasses(1.minute)
      regularSyncChild(testSetup)
    }
    // the genesis state is left as it is: the check that clears it is never asked
    port.asked.get() shouldBe 0
    snapChildren(testSetup) shouldBe empty
    val fallBack = warnings.filter(_.contains("[SNAP-PEERS]"))
    fallBack should have size 1
    fallBack.head should include("0 snap-capable peer(s) on this chain")
    fallBack.head should include("of 3 connected")
    fallBack.head should include("regular sync from its genesis")
  }

  it should "begin SNAP once enough snap peers are there, saying how many and how many of them are declared" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    // the operator declared peer 2 (a local core-geth, say) and peer 9, which is not connected
    port.snapPeers = port.snapPeers.copy(declared = Set(nodeIdOf(2), nodeIdOf(9)))
    pathLiveSync = Some(port)
    answerPeerPollsWith(peersOnTheChain(testSetup, n = 4, snap = 2))
    val lines = syncControllerLines(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually {
        someTimePasses()
        assert(snapChildren(testSetup).nonEmpty)
      }
    }
    port.asked.get() shouldBe 1
    val said = lines.filter(_.contains("[SNAP-PEERS] SNAP starts"))
    said should have size 1
    said.head should include("2 snap-capable peer(s) on this chain (1 of them declared by the operator")
    said.head should include("of 4 connected")
    said.head should include("min-snap-peers = 2")
    (said.head should not).include("<")
  }

  it should "begin SNAP at the end of the wait with the one snap peer there is, a declared one" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    port.snapPeers = port.snapPeers.copy(declared = Set(nodeIdOf(1)))
    pathLiveSync = Some(port)
    answerPeerPollsWith(peersOnTheChain(testSetup, n = 2, snap = 1))
    val lines = syncControllerLines(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      (1 to 3).foreach(_ => someTimePasses())
      port.asked.get() shouldBe 0
      timePasses(1.minute)
      eventually {
        someTimePasses()
        assert(snapChildren(testSetup).nonEmpty)
      }
    }
    port.asked.get() shouldBe 1
    assert(!childNamed(testSetup, "regular-sync"))
    val said = lines.filter(_.contains("[SNAP-PEERS] SNAP starts"))
    said should have size 1
    said.head should include("1 snap-capable peer(s) on this chain (1 of them declared by the operator")
    // under the minimum, the line says so (EG-12)
    said.head should include("1 < min 2")
  }

  it should "not wait for peers, nor regular-sync from the genesis, when the node holds more than its genesis" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // a SNAP sync to resume, or an escalation's clear: the genesis state is gone, and SNAP's own wait is what follows
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = false))
    pathLiveSync = Some(port)
    answerPeerPollsWith(peersOnTheChain(testSetup, n = 1, snap = 0))
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(snapChildren(testSetup).nonEmpty)
    }
    port.asked.get() shouldBe 1
    assert(!childNamed(testSetup, "regular-sync"))
  }

  it should "run regular sync from the genesis with do-snap-sync off, without asking the port" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    syncConfig.doSnapSync shouldBe false
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    pathLiveSync = Some(port)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    port.asked.get() shouldBe 0
    snapChildren(testSetup) shouldBe empty
  }

  // The peer manager that does not answer, the final count, and the log of the wait (spec 018 C6, review of #1940)

  it should "re-ask a count that does not come, say so at WARN, and begin SNAP on an unknown count when the wait is over" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    port.snapPeers = port.snapPeers.copy(powWait = 5.minutes)
    pathLiveSync = Some(port)
    val asked = new java.util.concurrent.atomic.AtomicInteger(0)
    answerPeerPollsFrom { () =>
      asked.incrementAndGet()
      None
    }
    val lines = syncControllerLogsAtLeast(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      (1 to 12).foreach(_ => timePasses(SyncController.SnapStartRecheckDelay))
      withClue("the count is asked again, not only once: ") {
        asked.get() should be >= 12
      }
      withClue("inside the wait the node neither starts SNAP nor regular sync: ") {
        port.asked.get() shouldBe 0
        snapChildren(testSetup) shouldBe empty
      }
      // bounded: at the end of the wait (and the final count, which does not come either) SNAP begins
      timePasses(4.minutes)
      (1 to 4).foreach(_ => timePasses(SyncController.SnapStartRecheckDelay))
      eventually {
        someTimePasses()
        assert(snapChildren(testSetup).nonEmpty)
      }
    }
    port.asked.get() shouldBe 1
    val warnings = lines.filter(_.contains("[SNAP-PEERS]"))
    withClue(warnings.mkString("\n")) {
      warnings.count(_.contains("has not answered")) should be >= 2 // after 2 ticks, and once a minute
      warnings.exists(_.contains("did not answer the final count")) shouldBe true
      warnings.exists(_.contains("unknown number of peers")) shouldBe true
    }
  }

  it should "decide on a recount at the end of the wait: a snap peer that dropped meanwhile is not counted" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    // the wait ends 2 s after a tick, so that the last count before it is stale
    port.snapPeers = port.snapPeers.copy(powWait = 62.seconds)
    pathLiveSync = Some(port)
    @volatile var dropped = false
    answerPeerPollsFrom { () =>
      Some(peersOnTheChain(testSetup, n = 2, snap = if dropped then 0 else 1))
    }
    val warnings = syncControllerLines(ch.qos.logback.classic.Level.WARN) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      (1 to 12).foreach(_ => timePasses(SyncController.SnapStartRecheckDelay))
      dropped = true
      timePasses(2.seconds)
      regularSyncChild(testSetup)
    }
    port.asked.get() shouldBe 0
    snapChildren(testSetup) shouldBe empty
    warnings.exists(_.contains("regular sync from its genesis")) shouldBe true
  }

  it should "decide on a recount at the end of the wait: a snap peer that arrived meanwhile is counted" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    port.snapPeers = port.snapPeers.copy(powWait = 62.seconds)
    pathLiveSync = Some(port)
    @volatile var arrived = false
    answerPeerPollsFrom { () =>
      Some(peersOnTheChain(testSetup, n = 2, snap = if arrived then 1 else 0))
    }
    val lines = syncControllerLines(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      (1 to 12).foreach(_ => timePasses(SyncController.SnapStartRecheckDelay))
      arrived = true
      timePasses(2.seconds)
      eventually {
        someTimePasses()
        assert(snapChildren(testSetup).nonEmpty)
      }
    }
    port.asked.get() shouldBe 1
    assert(!childNamed(testSetup, "regular-sync"))
    lines.filter(_.contains("[SNAP-PEERS] SNAP starts")).head should include("1 < min 2")
  }

  it should "say once a minute at INFO how many snap peers it has while it waits" taggedAs (UnitTest, SyncTest) in
    withRecoveryTestSetup() { testSetup =>
      import testSetup.*
      val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
      port.onlyGenesis = true
      port.snapPeers = port.snapPeers.copy(powWait = 5.minutes)
      pathLiveSync = Some(port)
      answerPeerPollsWith(peersOnTheChain(testSetup, n = 3, snap = 1))
      val lines = syncControllerLines(ch.qos.logback.classic.Level.INFO) {
        syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
        // 2 min and 1 s: the lines come at the first and the second minute, not at every count
        (1 to 24).foreach(_ => timePasses(SyncController.SnapStartRecheckDelay))
        timePasses(1.second)
      }
      val minutely = lines.filter(_.contains("[SNAP-PEERS] Still waiting"))
      minutely should have size 2
      minutely.foreach(_ should include("1 snap-capable peer(s) on this chain"))
    }

  "On a chain with a terminal total difficulty, on the unified layout, SyncController" should "ask the port at once and begin SNAP with no snap peer, however long, never regular sync from the genesis" taggedAs (
    UnitTest,
    SyncTest
  ) in withGuardPosSetup() { testSetup =>
    import testSetup.*
    val port = new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = true))
    port.onlyGenesis = true
    pathLiveSync = Some(port)
    answerPeerPollsWith(peersOnTheChain(testSetup, n = 3, snap = 0))
    val lines = syncControllerLines(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually {
        port.asked.get() shouldBe 1
      }
      timePasses(2.minutes)
      someTimePasses()
    }
    port.asked.get() shouldBe 1
    assert(snapChildren(testSetup).nonEmpty)
    assert(!childNamed(testSetup, "regular-sync"))
    lines.filter(_.contains("[SNAP-PEERS]")) shouldBe empty
  }

  "On the hash layout, SyncController" should "spawn SNAP at once, with no count of the peers first (guard off, unchanged)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    pathLiveSync shouldBe None
    val lines = syncControllerLines(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    }
    // no time has passed and no peer was answered: the SNAP child is spawned within the Start itself
    snapChildren(testSetup) should not be empty
    lines.filter(_.contains("[SNAP-PEERS]")) shouldBe empty
  }

  // the user's decision 1 (2026-11-17): healing goes on, each time; from the third in a row it is an ERROR (beacon)
  it should "restart SNAP for each hand-off held for accounts left out, and say so louder from the third" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    pathLiveSync = Some(new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = false)))
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    // on proof of work the snap peers are counted first (spec 018 C6): the count that does not come is given up on
    eventually {
      someTimePasses()
      assert(snapChildren(testSetup).nonEmpty)
    }
    awaitSnapStarted(syncController)
    def held(): List[String] =
      val before = snapChildren(testSetup)
      syncControllerLogsDuring(ch.qos.logback.classic.Level.WARN) {
        syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.HandoffHeldForExcluded(BigInt(7), false))
        eventually {
          someTimePasses()
          assert((snapChildren(testSetup) -- before).nonEmpty)
        }
      }.filter(_.contains("not handing off at pivot 7"))
    held().exists(_.contains("1 in a row")) shouldBe true
    held().exists(_.contains("2 in a row")) shouldBe true
    syncControllerErrorsDuring(held(): Unit).exists(_.contains("3 in a row")) shouldBe true
  }

  "The hand-offs held for accounts left out" should "be counted since the last hand-off" taggedAs UnitTest in {
    val held = new HandoffsHeldForExcluded
    (held.held(), held.held()) shouldBe (1, 2)
    held.handedOff()
    held.held() shouldBe 1
  }

  /** The regular-sync child running now: the latest generation (a stopped one can still be listed a moment). */
  private def regularSyncChild(testSetup: TestSetup): String =
    eventually {
      testSetup.someTimePasses()
      testSetup.syncController.children
        .map(_.path.name)
        .filter(_.startsWith("regular-sync-"))
        .maxBy(_.stripPrefix("regular-sync-").toIntOption.getOrElse(-1))
    }

  private def snapChildren(testSetup: TestSetup): Set[String] =
    testSetup.syncController.children.map(_.path.name).filter(_.startsWith("snap-sync")).toSet

  private def stuckReport(testSetup: TestSetup): List[String] =
    syncControllerErrorsDuring {
      testSetup.syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.RegularSyncStuck(BigInt(500), "deadbeef")
      )
    }

  it should "re-SNAP at once twice in a row, then keep regular sync running and re-SNAP an hour later (H3)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    appState.fastSyncDone().commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    /** Regular sync reports itself stuck, the re-SNAP starts at once, and SNAP hands back to regular sync. */
    def stuckAndResnapped(): List[String] =
      regularSyncChild(testSetup)
      val before = snapChildren(testSetup)
      val errors = stuckReport(testSetup)
      eventually {
        someTimePasses()
        assert((snapChildren(testSetup) -- before).nonEmpty)
      }
      appState.snapSyncDone().commit() // what a finalisation has written by the time it says so
      syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(BigInt(1)))
      errors

    val atOnce = stuckAndResnapped() ++ stuckAndResnapped()
    val running = regularSyncChild(testSetup)
    val snapsBefore = snapChildren(testSetup)
    val third = stuckReport(testSetup)

    withClue("the first two re-SNAPs go at once: ")(atOnce.filter(_.contains("[SYNC-STUCK]")) shouldBe empty)
    third.count(_.contains("[SYNC-STUCK]")) shouldBe 1
    third.filter(_.contains("[SYNC-STUCK]")).head should (include("60 min").and(include("Regular sync keeps running")))
    appState.regularSyncStuckResnaps() shouldBe 3
    appState.resnapBaseline() shouldBe Some(appState.getBestBlockNumber())
    withClue("regular sync keeps running, and no SNAP starts yet: ") {
      timePasses(59.minutes)
      syncController.children.map(_.path.name) should contain(running)
      snapChildren(testSetup) shouldBe snapsBefore
    }

    timePasses(2.minutes)
    eventually {
      someTimePasses()
      assert((snapChildren(testSetup) -- snapsBefore).nonEmpty)
    }
    withClue("the SNAP's pivot is no import: the next regular sync sets the baseline: ") {
      appState.resnapBaseline() shouldBe None
    }
  }

  it should "count re-SNAPs from what is stored, so a restart forgets no count, and say so on the gauge (H3)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    appState.fastSyncDone().and(appState.putRegularSyncStuck(2, Some(appState.getBestBlockNumber()))).commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = regularSyncChild(testSetup)

    val errors = stuckReport(testSetup)

    appState.regularSyncStuckResnaps() shouldBe 3
    com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics.consecutiveResnaps shouldBe 3d
    errors.count(_.contains("[SYNC-STUCK]")) shouldBe 1
    syncController.children.map(_.path.name) should contain(running)
    snapChildren(testSetup) shouldBe empty
  }

  // review vault 2: imports are measured from a stored baseline, not from where regular sync last started, which each of
  // its many restarts moved, so a healthy node that restarted could still climb to the backoff.
  it should "start the re-SNAP count over after a block is imported, across a restart of regular sync (H3)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    val best = appState.getBestBlockNumber()
    appState.fastSyncDone().and(appState.putRegularSyncStuck(2, Some(best))).commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val first = regularSyncChild(testSetup)
    appState.putBestBlockNumber(best + 5).commit() // regular sync imported blocks
    system.stop(syncController.children.find(_.path.name == first).get) // and then restarted
    eventually {
      someTimePasses()
      assert(syncController.children.exists(c => c.path.name.startsWith("regular-sync") && c.path.name != first))
    }

    val errors = stuckReport(testSetup)

    appState.regularSyncStuckResnaps() shouldBe 1
    errors.filter(_.contains("[SYNC-STUCK]")) shouldBe empty
    eventually {
      someTimePasses()
      assert(snapChildren(testSetup).nonEmpty)
    }
  }

  it should "drop a scheduled re-SNAP and zero the gauge once a block is imported while it waits (H3)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    val best = appState.getBestBlockNumber()
    appState.fastSyncDone().and(appState.putRegularSyncStuck(2, Some(best))).commit()
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    regularSyncChild(testSetup)
    stuckReport(testSetup).count(_.contains("[SYNC-STUCK]")) shouldBe 1

    appState.putBestBlockNumber(best + 1).commit() // regular sync imported a block
    timePasses(SyncController.ResnapCheckInterval + 1.second)

    appState.regularSyncStuckResnaps() shouldBe 0
    com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics.consecutiveResnaps shouldBe 0d
    timePasses(2.hours)
    snapChildren(testSetup) shouldBe empty
  }

  // review: a re-SNAP whose wait ended while a bulk code recovery ran had its ResnapDue dropped there, and the wait
  // state stayed: every later stuck was taken for a repeat of a re-SNAP still waiting, with no timer left to start it.
  it should "schedule a re-SNAP again when the last one's wait ended mid-recovery (H3)" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val sc = new BulkScenario(testSetup)
    networkPeerManager.setAutoPilot(
      new AutoPilot: // no peers: the recovery waits for one, and gives up only after 10 min with none
        override def run(sender: ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) => replyTo ! HandshakedPeers(Map.empty)
            case _                                                      => ()
          this
    )
    sc.appState.putRegularSyncStuck(2, Some(sc.appState.getBestBlockNumber())).commit()
    sc.startRegularSync()
    stuckReport(testSetup).count(_.contains("[SYNC-STUCK]")) shouldBe 1 // the third in a row: the re-SNAP waits

    timePasses(57.minutes) // regular sync runs most of the wait
    syncController ! SyncController.WrappedSyncProtocol(
      SyncProtocol.MissingCodeNeedsBulkRecovery(500, sc.codeHashes.head)
    )
    val recovery = eventually {
      someTimePasses()
      syncController.children.map(_.path.name).find(_.startsWith("bytecode-recovery")).get
    }
    timePasses(4.minutes) // the wait ends during the recovery, which drops the re-SNAP's message
    withClue("the recovery still runs, and no SNAP started: ") {
      syncController.children.map(_.path.name) should contain(recovery)
      snapChildren(testSetup) shouldBe empty
    }

    system.stop(syncController.children.find(_.path.name == recovery).get) // the recovery ends
    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
    }

    withClue("the next stuck schedules a re-SNAP, the fourth in a row: ") {
      stuckReport(testSetup).count(_.contains("[SYNC-STUCK]")) shouldBe 1
      sc.appState.regularSyncStuckResnaps() shouldBe 4
    }
  }

  it should "let two re-SNAPs in a row go at once, then wait an hour, doubling, up to a day (resnapBackoff, H3)" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    import scala.concurrent.duration.*
    (1 to 10).map(SyncController.resnapBackoff) shouldBe
      Seq(Duration.Zero, Duration.Zero, 1.hour, 2.hours, 4.hours, 8.hours, 16.hours, 24.hours, 24.hours, 24.hours)
    SyncController.resnapBackoff(10000) shouldBe 24.hours
  }

  // ── Startup sync mode, and databases from before fast sync was removed ───────────────────────────────────────
  // start() picks SNAP when do-snap-sync is set and SNAP is not done, and regular sync otherwise, with one exception:
  // a node where fast sync finished (legacy FastSyncDone) and SNAP has no stake (accounts not complete, saved pivot not
  // the best block) continues in regular sync. A node upgraded mid-fast-sync starts SNAP with a pivot above the best
  // block fast sync downloaded without state. Fast sync's leftover progress record (namespace `f`) is never decoded.

  private val StrandedBest: BigInt = 1000

  /** Leave the database the way an upgrade mid-fast-sync finds it: fast sync's progress record in namespace `f`, and a
    * best block (header stored) that fast sync downloaded but never executed. The record's bytes are arbitrary: they
    * are never decoded.
    */
  private def seedStrandedFastSync(testSetup: TestSetup): Unit =
    import testSetup.*
    val recordKey = ArraySeq.unsafeWrapArray("fast-sync-state".getBytes(StandardCharsets.UTF_8))
    storagesInstance.dataSource.update(
      Seq(DataSourceUpdate(Namespaces.FastSyncStateNamespace, Nil, Seq(recordKey -> ArraySeq[Byte](1, 2, 3))))
    )
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    storagesInstance.storages.appStateStorage.putBestBlockNumber(StrandedBest).commit()

  private def childNamed(testSetup: TestSetup, prefix: String): Boolean =
    testSetup.syncController.children.exists(_.path.name.startsWith(prefix))

  /** The ERROR messages SyncController logs while `f` runs (TestActorRef handles the message on the calling thread). */
  private def syncControllerErrorsDuring(f: => Unit): List[String] =
    syncControllerLogsDuring(ch.qos.logback.classic.Level.ERROR)(f)

  private def syncControllerLogsDuring(level: ch.qos.logback.classic.Level)(f: => Unit): List[String] =
    syncControllerLogsFrom(level, exact = true)(f)

  /** Every line at `level` or above. */
  private def syncControllerLogsAtLeast(level: ch.qos.logback.classic.Level)(f: => Unit): List[String] =
    syncControllerLogsFrom(level, exact = false)(f)

  private def syncControllerLogsFrom(level: ch.qos.logback.classic.Level, exact: Boolean)(f: => Unit): List[String] =
    val logger = org.slf4j.LoggerFactory
      .getLogger("com.chipprbots.ethereum.blockchain.sync.SyncController$Impl")
      .asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    // The test logback config keeps this logger above WARN, so without this a WARN would never reach the appender.
    val levelBefore = logger.getLevel
    logger.setLevel(level)
    try f
    finally
      logger.setLevel(levelBefore)
      logger.detachAppender(appender)
    appender.list.asScala.toList
      .filter(e => if exact then e.getLevel == level else e.getLevel.isGreaterOrEqual(level))
      .map(_.getFormattedMessage)

  /** SNAP part-way through healing: pivot saved, every data phase done, best moved on by a healing pivot roll. */
  private def seedMidHeal(testSetup: TestSetup, pivot: BigInt, best: BigInt): Unit =
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(best))).commit()
    appState
      .putSnapSyncPivotBlock(pivot)
      .and(appState.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .and(appState.putSnapSyncAccountsComplete(true))
      .and(appState.putSnapSyncStorageComplete(true))
      .and(appState.putSnapSyncBytecodeComplete(true))
      .and(appState.putBestBlockNumber(best))
      .commit()

  /** Wait until the SNAP child of `controller` has handled the MinPivotBlock / Start its parent sent at spawn: SNAP
    * answers GetProgress only after them.
    *
    * B-409: the child asked is the latest generation, the one the controller spawned last. A SNAP the controller has
    * just stopped (a restart) stays listed in `children` until its stop completes on the sync dispatcher, and children
    * are listed in name order, so the first `snap-sync-` child can be the stopped one: its GetProgress went to dead
    * letters and the wait timed out whenever that stop was late, as it is under load.
    */
  private def awaitSnapStarted(controller: TestActorRef[Nothing]): Unit =
    val snapSync = eventually(
      controller.children
        .filter(_.path.name.startsWith("snap-sync-"))
        .maxBy(_.path.name.stripPrefix("snap-sync-").toLong)
    )
    val progress = TestProbe()(using controller.underlying.system)
    snapSync.toTyped[SNAPSyncController.Command] ! SNAPSyncController.GetProgress(progress.ref.toTyped)
    progress.expectMsgType[com.chipprbots.ethereum.blockchain.sync.snap.SyncProgress]

  // ---- tx gossip gate (go-ethereum's `synced` flag; see TxGossipGate) ----------------------------------------------
  //
  // A Sepolia node in SNAP sync took blob txs from peers it could not validate, kept their sidecars and was OOM-killed.
  // SNAP and state recovery must hold the gate shut whatever else says the node is synced; regular sync reopens it.

  it should "hold tx gossip from peers shut while SNAP sync runs, even once the node is marked synced" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(syncController.children.exists(_.path.name.startsWith("snap-sync")))
    }
    // e.g. a forkchoiceUpdated naming SNAP's pivot, which SNAP stores as the best block before its state is complete
    txGossipGate.markSynced("test")
    txGossipGate.acceptTxs shouldBe false
  }

  it should "reopen tx gossip from peers when regular sync takes over from state sync" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    txGossipGate.stateSyncStarted()
    txGossipGate.markSynced("test")
    txGossipGate.acceptTxs shouldBe false

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    txGossipGate.acceptTxs shouldBe true
  }

  it should "start regular sync when do-snap-sync is off" taggedAs (UnitTest, SyncTest) in withTestSetup() {
    testSetup =>
      import testSetup.*
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

      eventually {
        someTimePasses()
        assert(childNamed(testSetup, "regular-sync"))
      }
      assert(!childNamed(testSetup, "fast-sync"))
  }

  it should "continue in regular sync on a node where fast sync finished and SNAP has no stake" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // Fast sync finished here long ago; SNAP never committed a pivot. Starting SNAP would leave the node dormant, importing
    // nothing, until a snap peer appeared, and a node more than 64 blocks behind would download the whole state again.
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    storagesInstance.storages.appStateStorage
      .fastSyncDone()
      .and(storagesInstance.storages.appStateStorage.putBestBlockNumber(StrandedBest))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    assert(!childNamed(testSetup, "snap-sync"))
  }

  it should "resume SNAP on a healing node that still carries a FastSyncDone flag" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // SNAP's accounts are complete and a healing pivot roll moved best past the saved pivot: SNAP has a stake.
    storagesInstance.storages.appStateStorage.fastSyncDone().commit()
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    assert(!childNamed(testSetup, "regular-sync"))
    storagesInstance.storages.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
  }

  it should "resume SNAP, not regular sync, on a mid-SNAP node that still carries a FastSyncDone flag" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // Starting SNAP never cleared FastSyncDone, so a node that once finished fast sync can be part-way through SNAP:
    // best = SNAP's pivot, whose state is still being downloaded. Regular sync there would execute on missing state.
    val appState = storagesInstance.storages.appStateStorage
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    appState
      .fastSyncDone()
      .and(appState.putBestBlockNumber(StrandedBest))
      .and(appState.putSnapSyncPivotBlock(StrandedBest))
      .and(appState.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "snap-sync"))
    }
    assert(!childNamed(testSetup, "regular-sync"))
  }

  it should "resume SNAP on a mid-heal node when do-snap-sync is off (#1434)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    // do-snap-sync = false does not strand a node that SNAP already holds: accounts are complete, the trie is partial,
    // and regular sync there would only fetch the missing nodes one by one.
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "snap-sync"))
    }
    assert(!childNamed(testSetup, "regular-sync"))
    storagesInstance.storages.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
  }

  it should "resume SNAP on a node whose best block is SNAP's pivot when do-snap-sync is off (#1434)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    appState
      .putBestBlockNumber(StrandedBest)
      .and(appState.putSnapSyncPivotBlock(StrandedBest))
      .and(appState.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "snap-sync"))
    }
    assert(!childNamed(testSetup, "regular-sync"))
  }

  it should "not start SNAP on a finished node whose stale stake remains, when do-snap-sync is off (#1434)" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    // SnapSyncDone is the guard: a finished node keeps AccountsComplete = true and best = saved pivot forever.
    val appState = storagesInstance.storages.appStateStorage
    seedMidHeal(testSetup, pivot = StrandedBest, best = StrandedBest)
    appState.snapSyncDone().commit()
    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.getSnapSyncPivotBlock() shouldBe Some(StrandedBest)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    assert(!childNamed(testSetup, "snap-sync"))
  }

  it should "start SNAP with a pivot above the best block of a node upgraded mid-fast-sync" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)
    // A snap peer only 10 blocks ahead: SNAP's own pivot (head - 64) is below the stranded best block. Without the
    // floor SNAP reads that as "already synced" and hands the node to regular sync with no state.
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    storagesInstance.storages.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(StrandedBest + 1)
    assert(!childNamed(testSetup, "regular-sync"))
    // Handled once: fast sync's record is gone, so no later start can take a SNAP-moved best block for a stranded one.
    // The floor lives on in SNAP's state until SNAP holds a pivot at or above it.
    storagesInstance.storages.fastSyncStateStorage.hasSyncState shouldBe false
    storagesInstance.storages.appStateStorage.getSnapSyncMinPivotBlock() shouldBe Some(StrandedBest + 1)
  }

  it should "keep applying a persisted pivot floor after a restart, until SNAP holds a pivot above it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    // The node restarted after the first start recorded the floor and deleted fast sync's record, but before SNAP
    // chose a pivot. Only the persisted floor now says the best block has no state.
    blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
    storagesInstance.storages.appStateStorage
      .putBestBlockNumber(StrandedBest)
      .and(storagesInstance.storages.appStateStorage.putSnapSyncMinPivotBlock(StrandedBest + 1))
      .commit()
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    storagesInstance.storages.appStateStorage.getSnapSyncBootstrapTarget() shouldBe Some(StrandedBest + 1)
    assert(!childNamed(testSetup, "regular-sync"))
  }

  // B-307 (c2): what the pivot's bootstrap found of the branch reaches the requirement and the SNAP controller
  it should "end the wait for a peer on the branch when the pivot's bootstrap completes, and pass on the peers it found on another chain" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.PivotOnBranch
    val appState = storagesInstance.storages.appStateStorage
    seedStrandedFastSync(testSetup)
    appState
      .putBestBlockNumber(StrandedBest)
      .and(appState.putSnapSyncMinPivotBlock(StrandedBest + 1))
      .commit()
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    val bound = PivotOnBranch(StrandedBest + 1, ByteString(Array.fill[Byte](32)(0x21)))

    // every peer at the target's height was on another chain: the SNAP controller is told which, and begins the wait
    appState.putPivotOnBranch(bound).commit()
    syncController ! SyncController.WrappedExternal(
      PivotHeaderBootstrap.Failed(
        PivotHeaderBootstrap.NoPeerOnTheBranch,
        Set(com.chipprbots.ethereum.network.PeerId("on-the-other-chain"))
      )
    )
    eventually {
      someTimePasses()
      assert(appState.getPivotOnBranch().exists(_.firstMissMillis.isDefined))
    }
  }

  it should "end the wait for a peer on the branch when the pivot's bootstrap takes its header from one" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.PivotOnBranch
    val appState = storagesInstance.storages.appStateStorage
    seedStrandedFastSync(testSetup)
    appState
      .putBestBlockNumber(StrandedBest)
      .and(appState.putSnapSyncMinPivotBlock(StrandedBest + 1))
      .commit()
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    val bound = PivotOnBranch(StrandedBest + 1, ByteString(Array.fill[Byte](32)(0x21)))
    appState.putPivotOnBranch(bound.copy(firstMissMillis = Some(7L))).commit()
    syncController ! SyncController.WrappedExternal(
      PivotHeaderBootstrap.Completed(StrandedBest + 1, baseBlockHeader.copy(number = BlockNumber(StrandedBest + 1)))
    )
    appState.getPivotOnBranch() shouldBe Some(bound)
  }

  // B-310 (forge): under the branch's first block the bootstrap asks one header by number, with no check of the branch
  it should "not end the wait for a peer on the branch when the pivot's bootstrap took a header under the branch" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    import com.chipprbots.ethereum.db.storage.pathstate.PivotOnBranch
    val appState = storagesInstance.storages.appStateStorage
    seedStrandedFastSync(testSetup)
    appState
      .putBestBlockNumber(StrandedBest)
      .and(appState.putSnapSyncMinPivotBlock(StrandedBest + 1))
      .commit()
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    // the branch begins one block above the header the bootstrap was asked for
    val waiting = PivotOnBranch(StrandedBest + 2, ByteString(Array.fill[Byte](32)(0x21)), firstMissMillis = Some(7L))
    appState.putPivotOnBranch(waiting).commit()
    syncController ! SyncController.WrappedExternal(
      PivotHeaderBootstrap.Completed(StrandedBest + 1, baseBlockHeader.copy(number = BlockNumber(StrandedBest + 1)))
    )
    appState.getPivotOnBranch() shouldBe Some(waiting)
  }

  it should "clear the pivot floor when SNAP finalizes" taggedAs (UnitTest, SyncTest) in withRecoveryTestSetup() {
    testSetup =>
      import testSetup.*
      val appState = storagesInstance.storages.appStateStorage
      blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(StrandedBest))).commit()
      appState.putBestBlockNumber(StrandedBest).and(appState.putSnapSyncMinPivotBlock(StrandedBest + 1)).commit()

      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually(assert(childNamed(testSetup, "snap-sync")))
      appState.getSnapSyncMinPivotBlock() shouldBe Some(StrandedBest + 1)

      syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(StrandedBest + 1))

      appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "not re-apply the floor when the node restarts mid-heal, after SNAP took over" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    seedStrandedFastSync(testSetup)
    answerPeerPollsWith(snapPeerAt(StrandedBest + 10))

    // First start: SNAP takes the stranded node over with a pivot above its best block.
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "pivot-header-bootstrap"))
    }
    stopNode(syncController)

    // SNAP then committed that pivot, downloaded every range and started healing. A healing pivot roll moved best
    // without saving the pivot (SNAP saves it only when healing succeeds).
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    val restarted = restartedSyncController()
    restarted ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(restarted)

    // A floor above SNAP's saved pivot would trip belowEscalationHint and wipe these, restarting SNAP from scratch.
    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe true
    appState.isSnapSyncBytecodeComplete() shouldBe true
    // SNAP's saved pivot reached the floor, so the floor was spent: cleared, not applied again.
    appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "treat the pivot floor as spent once SNAP's accounts are complete, even below it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    // The floor was SNAP's first pivot. A failed pivot refresh backtracked by the pivot offset and SNAP committed, and
    // finished its accounts at, a pivot below the floor. Applying the floor again would trip belowEscalationHint.
    val floor = StrandedBest + 1
    val backtracked = floor - SNAPSyncConfig().pivotBlockOffset
    appState.putSnapSyncMinPivotBlock(floor).commit()
    seedMidHeal(testSetup, pivot = backtracked, best = backtracked)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe true
    appState.isSnapSyncBytecodeComplete() shouldBe true
    appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "leave a heal alone on a database that once fell back to fast sync" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    // SNAP fell back to fast sync once (fast sync wrote its record), later resumed and is now healing: best was moved
    // by a healing pivot roll, not by fast sync.
    seedStrandedFastSync(testSetup)
    seedMidHeal(testSetup, pivot = StrandedBest + 1, best = StrandedBest + 100)

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.isSnapSyncStorageComplete() shouldBe true
    appState.isSnapSyncBytecodeComplete() shouldBe true
    storagesInstance.storages.fastSyncStateStorage.hasSyncState shouldBe false
    appState.getSnapSyncMinPivotBlock() shouldBe None
  }

  it should "leave SNAP's resume alone once SNAP has taken over the best block of an upgraded node" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)
    // SNAP already picked the stranded block as its pivot and finished its accounts there (updateBestBlockForPivot sets
    // best = pivot). A floor of best + 1 would reject that pivot and discard the account download.
    // B-309: with the task files its storage and bytecode phases resume from. Without them SNAP's own resume restarts
    // the accounts phase (a lost task file must not complete its phase), which is not what this case is about.
    val appState = storagesInstance.storages.appStateStorage
    val storageTasks = java.nio.file.Files.createTempFile("b309-storage-tasks", ".bin")
    val codeHashes = java.nio.file.Files.createTempFile("b309-code-hashes", ".bin")
    storageTasks.toFile.deleteOnExit()
    codeHashes.toFile.deleteOnExit()
    appState
      .putSnapSyncPivotBlock(StrandedBest)
      .and(appState.putSnapSyncStateRoot(ByteString(Array.fill[Byte](32)(0x66))))
      .and(appState.putSnapSyncAccountsComplete(true))
      // a chain with no contracts: zero entries, and the count says so
      .and(appState.putSnapSyncStorageFilePath(storageTasks.toString))
      .and(appState.putSnapSyncStorageFileCount(Some(0L)))
      .and(appState.putSnapSyncCodeHashesPath(codeHashes.toString))
      .and(appState.putSnapSyncCodeHashesCount(Some(0L)))
      .commit()

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    awaitSnapStarted(syncController)

    storagesInstance.storages.appStateStorage.isSnapSyncAccountsComplete() shouldBe true
  }

  it should "start regular sync on a node upgraded mid-fast-sync when do-snap-sync is off" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)

    val errors = syncControllerErrorsDuring(syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start))

    // The ERROR has to say what the node will do: regular sync tries the missing state node by node and, when peers
    // cannot serve it, RegularSyncStuck re-runs SNAP whatever do-snap-sync says.
    errors.exists(_.contains("re-syncs with SNAP from a newer pivot, even with do-snap-sync off")) shouldBe true

    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    assert(!childNamed(testSetup, "fast-sync"))
    // The floor is kept, so switching do-snap-sync on later still starts SNAP above the stateless block.
    storagesInstance.storages.fastSyncStateStorage.hasSyncState shouldBe false
    storagesInstance.storages.appStateStorage.getSnapSyncMinPivotBlock() shouldBe Some(StrandedBest + 1)
  }

  it should "log the stranded-node ERROR once per floor, not on every restart" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    seedStrandedFastSync(testSetup)
    val stranded = "has no state behind it, and do-snap-sync is off"

    val first = syncControllerErrorsDuring(syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start))
    first.exists(_.contains(stranded)) shouldBe true
    stopNode(syncController)

    val restarted = restartedSyncController()
    val second = syncControllerErrorsDuring(restarted ! SyncController.WrappedSyncProtocol(SyncProtocol.Start))
    second.exists(_.contains(stranded)) shouldBe false
  }

  // ── Bulk bytecode recovery on a MissingCodeException at import ────────────────────────────────
  // A SNAP-synced node can lack thousands of contracts' code. One fetch per contract is one full re-execution of the
  // block each (devnet-8: ~20 s each, ~11 h for one chain). The first miss must instead run ONE recovery scan and fetch
  // every gap in batched GetByteCodes, then resume regular sync.

  /** N contracts whose accounts are in a real state trie and whose code is NOT stored, plus a network that serves it.
    * `bestRootInTrie`: the trie is written under the best block (pivot's root then names nothing); otherwise under the
    * pivot (best block's root names nothing: a pruned best root).
    */
  private class BulkScenario(
      testSetup: TestSetup,
      n: Int = 200,
      trieAtBest: Boolean = false,
      prunedEverywhere: Boolean = false,
      pivotReadableWithNoGaps: Boolean = false
  ):
    import testSetup.*
    import java.util.concurrent.ConcurrentLinkedQueue
    val appState = storagesInstance.storages.appStateStorage
    val evmCodes = storagesInstance.storages.evmCodeStorage
    val codes: Vector[ByteString] =
      (0 until n).map(i => ByteString(Array[Byte](0x60, (i >> 8).toByte, (i & 0xff).toByte, 0x00))).toVector
    val codeHashes: Vector[ByteString] = codes.map(c => com.chipprbots.ethereum.crypto.kec256(c))
    val codeByHash: Map[ByteString, ByteString] = codeHashes.zip(codes).toMap

    private val world0 = com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy(
      evmCodes,
      blockchain.getBackingMptStorage(100),
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(com.chipprbots.ethereum.mpt.MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )
    private val world = codeHashes.zipWithIndex.foldLeft(world0) { case (w, (h, i)) =>
      w.saveAccount(
        Address(ByteString(Array.fill[Byte](18)(0x07) ++ Array[Byte]((i >> 8).toByte, (i & 0xff).toByte))),
        Account(nonce = UInt256(1), codeHash = CodeHash(h))
      )
    }
    val trieRoot: ByteString = com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy.persistState(world).stateRootHash
    private val nowhere: ByteString = ByteString(Array.fill[Byte](32)(0x5a))
    codeHashes.foreach(h => evmCodes.get(h) shouldBe None)

    val cleanRoot: ByteString = com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
      .persistState(world0.saveAccount(Address(ByteString(Array.fill[Byte](20)(0x09))), Account(nonce = UInt256(1))))
      .stateRootHash

    private val pivotRoot =
      if pivotReadableWithNoGaps then cleanRoot else if trieAtBest || prunedEverywhere then nowhere else trieRoot
    private val bestRoot = if trieAtBest && !prunedEverywhere then trieRoot else nowhere
    private val pivotHeader =
      Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(100), stateRoot = TrieRoot(pivotRoot))
    private val bestHeader =
      Fixtures.Blocks.Genesis.header.copy(number = BlockNumber(900), stateRoot = TrieRoot(bestRoot))
    blockchainWriter.storeBlock(Block(pivotHeader, BlockBody.empty)).commit()
    blockchainWriter.storeBlock(Block(bestHeader, BlockBody.empty)).commit()
    appState
      .snapSyncDone()
      .and(appState.bytecodeRecoveryDone())
      .and(appState.storageRecoveryDone())
      .and(appState.putSnapSyncStateRoot(pivotRoot))
      .and(appState.putSnapSyncPivotBlock(BigInt(100)))
      .and(appState.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(bestHeader.hash.value, 900)))
      .commit()

    val requests = new ConcurrentLinkedQueue[Seq[ByteString]]()
    @volatile private var snapTarget
        : Option[TypedActorRef[com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.Command]] = None
    @volatile var doneFlagAtFirstRequest: Option[Boolean] = None
    networkPeerManager.setAutoPilot(
      new AutoPilot:
        override def run(sender: ActorRef, msg: Any): AutoPilot =
          msg.asMatchable match
            case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
              replyTo ! HandshakedPeers(snapPeerAt(100))
            case NetworkPeerManagerActor.RegisterSnapSyncControllerCmd(ref) => snapTarget = Some(ref)
            case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
              message.underlyingMsg.asMatchable match
                case com.chipprbots.ethereum.network.p2p.messages.SNAP.GetByteCodes(requestId, hashes, _) =>
                  if doneFlagAtFirstRequest.isEmpty then
                    doneFlagAtFirstRequest = Some(appState.isBytecodeRecoveryDone())
                  requests.add(hashes)
                  snapTarget.foreach(
                    _ ! SNAPSyncController.ByteCodesResponse(
                      com.chipprbots.ethereum.network.p2p.messages.SNAP
                        .ByteCodes(requestId, hashes.flatMap(codeByHash.get))
                    )
                  )
                case _ => ()
            case _ => ()
          this
    )

    def regularSyncChild: Option[String] =
      syncController.children.map(_.path.name).find(_.startsWith("regular-sync"))

    /** Start regular sync, hit a missing code (twice: the repeat must not start a second scan), and wait for regular
      * sync to be running again after the recovery.
      */
    def startRegularSync(): String =
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually {
        someTimePasses()
        assert(regularSyncChild.isDefined)
      }
      regularSyncChild.get

    /** Send the escalation request and let a few controller ticks pass; true if regular sync was left alone. */
    def requestIsIgnored(first: String): Boolean =
      syncController ! SyncController.WrappedSyncProtocol(
        SyncProtocol.MissingCodeNeedsBulkRecovery(500, codeHashes.head)
      )
      (1 to 5).foreach(_ => someTimePasses())
      regularSyncChild.contains(first) && requests.isEmpty && appState.isBytecodeRecoveryDone()

    /** Both recovery flags unset, as a finalisation that withheld them leaves them. */
    def owesTheScan(): Unit =
      appState.clearBytecodeRecoveryDone().and(appState.clearStorageRecoveryDone()).commit()

    /** The node at first nibble `n` of the trie the codes are in is gone: a hole. */
    def dropChildOfTrieRoot(n: Int): Unit =
      val hole = blockchain
        .getBackingMptStorage(100)
        .get(trieRoot.toArray)
        .asInstanceOf[com.chipprbots.ethereum.mpt.BranchNode]
        .children(n)
        .asInstanceOf[com.chipprbots.ethereum.mpt.HashNode]
      storagesInstance.storages.nodeStorage.update(Seq(ByteString(hole.hash)), Nil)
      ()

    def runEscalation(codeHash: ByteString = codeHashes.head): Unit =
      val first = startRegularSync()
      val trigger = SyncProtocol.MissingCodeNeedsBulkRecovery(500, codeHash)
      syncController ! SyncController.WrappedSyncProtocol(trigger)
      // The repeat checks that a trigger arriving while a scan runs is dropped. With no readable root the scan cannot
      // run: it ends in about a millisecond and regular sync resumes, where a repeat is a new request and a second
      // failure (#1566). So send it only where the scan is still running.
      if !prunedEverywhere then syncController ! SyncController.WrappedSyncProtocol(trigger)
      eventually {
        someTimePasses()
        assert(
          regularSyncChild.exists(_ != first),
          s"regular sync was not restarted after the recovery (children=${syncController.children.map(_.path.name).toList}, " +
            s"requests=${requests.size}, stored=${codeHashes.count(h => evmCodes.get(h).isDefined)})"
        )
      }

  private def hexOrder: Ordering[ByteString] = Ordering.by[ByteString, String](_.toArray.map("%02x".format(_)).mkString)

  "SyncController" should
    "run ONE bytecode recovery for a MissingCodeException at import: fetch all N missing codes in batches, then resume regular sync" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      val sc = new BulkScenario(testSetup, trieAtBest = false)
      sc.runEscalation()
      import scala.jdk.CollectionConverters.*
      val requested = sc.requests.asScala.toVector
      withClue(s"request sizes ${requested.map(_.size)}: ") {
        requested.map(_.size).sorted shouldBe Vector(30, 85, 85) // 200 codes in batches of 85
        requested.flatten.sorted(using hexOrder) shouldBe sc.codeHashes.sorted(using hexOrder)
      }
      requested.flatten.distinct.size shouldBe sc.codeHashes.size // each fetched exactly once: one scan, not two
      sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
      sc.doneFlagAtFirstRequest shouldBe Some(false) // un-done while the recovery was running
      sc.appState.isBytecodeRecoveryDone() shouldBe true // done again once every code is stored
      sc.appState.bulkBytecodeRecoveryFailures() shouldBe 0
    }

  it should
    "scan the best block's root for the code it is after, though a failed scan left a checkpoint on an older root" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      val sc = new BulkScenario(testSetup, trieAtBest = true)
      sc.appState
        .putRecoveryProgress(
          com.chipprbots.ethereum.db.storage.RecoveryProgress(sc.cleanRoot, 1, Set.empty, Vector.empty, Vector.empty)
        )
        .commit()
      sc.runEscalation()
      withClue("the checkpoint's root has no gaps, so a scan that kept it would ask for none of these: ") {
        sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
      }
      sc.appState.isBytecodeRecoveryDone() shouldBe true
    }

  it should
    "scan the best block's root, so a pruned SNAP pivot root does not hide the gaps" taggedAs (UnitTest, SyncTest) in
    withRecoveryTestSetup() { testSetup =>
      // The pivot's root names nothing (pruned); only the best block's root is readable.
      val sc = new BulkScenario(testSetup, trieAtBest = true)
      sc.runEscalation()
      sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
      sc.appState.isBytecodeRecoveryDone() shouldBe true
    }

  it should
    "fail loudly when no readable root shows the gaps: code still missing, done flag stays clear, failure counted" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      // Neither the best root nor the pivot root is readable: the scan sees nothing and nothing is fetched.
      val sc = new BulkScenario(testSetup, prunedEverywhere = true)
      sc.runEscalation()
      sc.codeHashes.exists(h => sc.evmCodes.get(h).isDefined) shouldBe false
      sc.appState.isBytecodeRecoveryDone() shouldBe false // so the next start scans again...
      sc.appState.bulkBytecodeRecoveryFailures() shouldBe 1 // ...up to the cap
    }

  it should
    "ignore a bulk-recovery request on a node whose state did not come from SNAP: no scan, regular sync untouched" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      val sc = new BulkScenario(testSetup)
      val first = sc.startRegularSync()
      sc.appState.clearSnapSyncDone().commit() // as on a node that synced by executing blocks
      sc.requestIsIgnored(first) shouldBe true
    }

  it should
    "ignore a bulk-recovery request once the failure cap is reached: no scan, regular sync untouched" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      val sc = new BulkScenario(testSetup)
      sc.appState.putBulkBytecodeRecoveryFailures(SyncController.MaxBulkBytecodeRecoveryFailures).commit()
      val first = sc.startRegularSync()
      sc.requestIsIgnored(first) shouldBe true
    }

  it should "allow bulk recovery only for a SNAP-synced node under the failure cap" taggedAs (UnitTest, SyncTest) in {
    SyncController.bulkCodeRecoveryAllowed(snapSyncDone = true, failures = 0) shouldBe true
    SyncController.bulkCodeRecoveryAllowed(snapSyncDone = false, failures = 0) shouldBe false
    SyncController.bulkCodeRecoveryAllowed(
      snapSyncDone = true,
      failures = SyncController.MaxBulkBytecodeRecoveryFailures
    ) shouldBe false
  }

  it should
    "stop repeating the scan once the failure cap is reached: mark bytecodeRecoveryDone and keep the count" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      val sc = new BulkScenario(testSetup)
      sc.appState.putBulkBytecodeRecoveryFailures(SyncController.MaxBulkBytecodeRecoveryFailures - 1).commit()
      // The scan ran and fetched all it found, and the code asked for was not among it: its account is not in the trie.
      sc.runEscalation(codeHash = ByteString(Array.fill[Byte](32)(0x77)))
      sc.appState.bulkBytecodeRecoveryFailures() shouldBe SyncController.MaxBulkBytecodeRecoveryFailures
      sc.appState.isBytecodeRecoveryDone() shouldBe true
    }

  it should
    "not let the failure cap settle a scan that could not run: the count stands, the flag stays clear (spec 022 T050a)" taggedAs (
      UnitTest,
      SyncTest
    ) in withRecoveryTestSetup() { testSetup =>
      val sc = new BulkScenario(testSetup, prunedEverywhere = true)
      sc.appState.putBulkBytecodeRecoveryFailures(SyncController.MaxBulkBytecodeRecoveryFailures - 1).commit()
      sc.runEscalation()
      sc.appState.bulkBytecodeRecoveryFailures() shouldBe SyncController.MaxBulkBytecodeRecoveryFailures
      sc.appState.isBytecodeRecoveryDone() shouldBe false
    }

  // ── spec 022 T050a: the recovery scan at a start, which pays what a finalisation withheld ─────────────────────────────
  // Measured, Mordor, 2026-10-31: on a clean Hash datadir under the default pruning a node stopped about 3 minutes after
  // its hand-off had imported 87 blocks past its pivot, and the next start's scan found the pivot root gone and read that
  // as no gaps, so the flag went true over nothing.

  it should "scan the best block's root at a start, with the pivot's pruned, and find a gap planted under it" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup, trieAtBest = true)
    sc.owesTheScan()
    sc.startRegularSync()
    sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
    sc.appState.isBytecodeRecoveryDone() shouldBe true
    sc.appState.isStorageRecoveryDone() shouldBe true
    sc.appState.getRecoveryProgress() shouldBe None
  }

  it should "scan the best block's root at a start even with the pivot's still readable" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup, trieAtBest = true, pivotReadableWithNoGaps = true)
    sc.owesTheScan()
    sc.startRegularSync()
    withClue("the pivot's trie has no gaps, so only a scan of the best root asks for these: ") {
      sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
    }
  }

  it should "resume a scan under way on the root it started with, though the best block has moved" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup, trieAtBest = true)
    sc.owesTheScan()
    sc.appState
      .putRecoveryProgress(
        com.chipprbots.ethereum.db.storage.RecoveryProgress(sc.cleanRoot, 1, Set.empty, Vector.empty, Vector.empty)
      )
      .commit()
    sc.startRegularSync()
    withClue("the checkpoint's root has no gaps, so only a scan of the best root asks for these: ") {
      sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe None)
    }
    sc.appState.isBytecodeRecoveryDone() shouldBe true
    sc.appState.getRecoveryProgress() shouldBe None
  }

  it should "scan the best block's root when the root of a scan under way has been pruned" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup, trieAtBest = true)
    sc.owesTheScan()
    sc.appState
      .putRecoveryProgress(
        com.chipprbots.ethereum.db.storage
          .RecoveryProgress(ByteString(Array.fill[Byte](32)(0x44)), 16, Set(3), Vector.empty, Vector.empty)
      )
      .commit()
    sc.startRegularSync()
    sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
    sc.appState.isBytecodeRecoveryDone() shouldBe true
  }

  it should "fall back to the pivot's root at a start when the best block's cannot be read" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup)
    sc.owesTheScan()
    sc.startRegularSync()
    sc.codeHashes.foreach(h => sc.evmCodes.get(h) shouldBe defined)
    sc.appState.isBytecodeRecoveryDone() shouldBe true
  }

  it should "settle nothing when the scan could not run: no flag, the scan's progress kept, regular sync started" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup, prunedEverywhere = true)
    sc.owesTheScan()
    val progress = com.chipprbots.ethereum.db.storage.RecoveryProgress(
      ByteString(Array.fill[Byte](32)(0x44)),
      shardCount = 16,
      completedShards = Set(3),
      missingBytecodes = Vector.empty,
      missingStorageTries = Vector.empty
    )
    sc.appState.putRecoveryProgress(progress).commit()

    val errors = syncControllerErrorsDuring { sc.startRegularSync(); () }

    sc.regularSyncChild shouldBe defined
    sc.appState.isBytecodeRecoveryDone() shouldBe false
    sc.appState.isStorageRecoveryDone() shouldBe false
    sc.appState.getRecoveryProgress() shouldBe Some(progress)
    errors.exists(e => e.contains("could not run") && e.contains("next start tries again")) shouldBe true
    sc.requests.isEmpty shouldBe true
  }

  it should "settle the flags on a pass with a hole, fetch what it did find, and WARN what the flags then mean" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    val sc = new BulkScenario(testSetup)
    sc.owesTheScan()
    sc.dropChildOfTrieRoot(4)

    val warnings = syncControllerLogsDuring(ch.qos.logback.classic.Level.WARN) { sc.startRegularSync(); () }

    sc.appState.isBytecodeRecoveryDone() shouldBe true
    sc.appState.isStorageRecoveryDone() shouldBe true
    val stored = sc.codeHashes.count(h => sc.evmCodes.get(h).isDefined)
    withClue(s"stored $stored of ${sc.codeHashes.size}: ") {
      stored should be > 0
      stored should be < sc.codeHashes.size
    }
    warnings.exists(w => w.contains("could not read 1 subtree(s)") && w.contains("a pass ran")) shouldBe true
  }

  // ---- spec 022 T053, decisions 9 and 14: the background audit --------------------------------------------------------

  private def auditName(testSetup: TestSetup): Option[String] =
    testSetup.syncController.children.map(_.path.name).find(_.startsWith("background-audit"))

  private def childIsGone(testSetup: TestSetup, name: String): Unit =
    eventually {
      testSetup.someTimePasses()
      assert(!testSetup.syncController.children.exists(_.path.name == name))
    }

  /** The best block on the scannable root, stamped now, so the audit's throttle has no reason to hold off. */
  private def freshBestOnPivotRoot(testSetup: TestSetup): Unit =
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    val header = Fixtures.Blocks.Genesis.header.copy(
      number = BlockNumber(100),
      stateRoot = TrieRoot(appState.getSnapSyncStateRoot().get),
      unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(System.currentTimeMillis() / 1000)
    )
    blockchainWriter.storeBlock(Block(header, BlockBody.empty)).commit()
    appState.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(header.hash.value, 100)).commit()

  // ---- B-369 (spec 018 C5b): the audit on the unified state layout ---------------------------------------------------

  /** The anchor and the rows the guard's audit walks, with a count of each kind of read, so that a spec says which
    * reader the audit used.
    */
  final private class GuardPositions extends com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs:
    import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
    import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
    @volatile var held: Option[BlockRef] = None
    @volatile var rows: Seq[(PositionKey, ByteString)] => Seq[com.chipprbots.ethereum.db.storage.RawNodeRead] =
      _.map(_ => com.chipprbots.ethereum.db.storage.RawNodeRead.Absent)
    val rowReads = new java.util.concurrent.atomic.AtomicInteger(0)
    override def anchor: Option[BlockRef] = held
    override def rawRowsAt(
        requests: Seq[(PositionKey, ByteString)]
    ): Seq[com.chipprbots.ethereum.db.storage.RawNodeRead] =
      rowReads.incrementAndGet()
      rows(requests)
    override def repairNodeAt(
        position: PositionKey,
        expectedHash: ByteString,
        bytes: Array[Byte]
    ): com.chipprbots.ethereum.db.storage.RepairOutcome = com.chipprbots.ethereum.db.storage.RepairOutcome.Written
    override def deleteBadNodeAt(
        position: PositionKey,
        expectedHash: ByteString
    ): com.chipprbots.ethereum.db.storage.BadEntryDelete = com.chipprbots.ethereum.db.storage.BadEntryDelete.NoLongerBad

  /** A guard (`pathLiveSync` set) with the audit on, whose writer holds `positions` or none. */
  private class GuardAuditSetup(val positions: Option[GuardPositions]) extends TestSetup():
    override def auditEnabled: Boolean = true
    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = true)
    pathLiveSync = Some(new GuardPort(SyncController.SnapStartOnGuard.Begin(clearedGenesis = false)))
    override lazy val blockchainWriter: BlockchainWriter =
      val s = storagesInstance.storages
      new BlockchainWriter(
        s.blockHeadersStorage,
        s.blockBodiesStorage,
        s.blockNumberMappingStorage,
        s.transactionMappingStorage,
        s.receiptStorage,
        s.chainWeightStorage,
        s.appStateStorage,
        s.blockAccessListStorage
      ):
        override def positionRepairs = positions

    /** The anchor is block 100 on the pivot's root, which the rows then give from the hash store the seed wrote. */
    def anchorOnThePivotRoot(): Unit =
      positions.foreach { p =>
        val appState = storagesInstance.storages.appStateStorage
        freshBestOnPivotRoot(this)
        p.held = Some(
          com.chipprbots.ethereum.db.storage.pathstate
            .BlockRef(appState.getBestBlockInfo().hash, 100, appState.getSnapSyncStateRoot().get)
        )
        val store = storagesInstance.storages.stateStorage.getReadOnlyStorage
        p.rows = requests => store.rawNodes(requests.map(_._2))
      }

  private def withGuardAudit(positions: Option[GuardPositions])(test: GuardAuditSetup => Any): Unit =
    val testSetup = new GuardAuditSetup(positions)
    try test(testSetup)
    finally testSetup.cleanup()

  "On the unified state layout, the audit" should "run, walk the position store and pay the withheld flags, with no scan" taggedAs (
    UnitTest,
    SyncTest
  ) in withGuardAudit(Some(new GuardPositions)) { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    anchorOnThePivotRoot()
    val appState = storagesInstance.storages.appStateStorage
    appState.isBytecodeRecoveryDone() shouldBe false
    appState.isStorageRecoveryDone() shouldBe false

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    assert(childNamed(testSetup, "regular-sync"))
    assert(!childNamed(testSetup, "combined-recovery-scan"))
    eventually {
      someTimePasses()
      appState.isBytecodeRecoveryDone() shouldBe true
      appState.isStorageRecoveryDone() shouldBe true
    }
    withClue("the pass is of the pivot, and it read the position store: ") {
      appState.getSnapAuditCleanAnchor() shouldBe Some("100")
      positions.get.rowReads.get() should be > 0
    }
    withClue("nothing is owed after that, and the audit stopped: ") {
      eventually {
        someTimePasses()
        auditName(testSetup) shouldBe None
      }
    }
  }

  it should "not run again for a pivot a clean pass covered, with both flags up" taggedAs (UnitTest, SyncTest) in
    withGuardAudit(Some(new GuardPositions)) { testSetup =>
      import testSetup.*
      seedSnapDoneWithScannableRoot(testSetup, needBytecode = false, needStorage = false)
      anchorOnThePivotRoot()
      storagesInstance.storages.appStateStorage.putSnapAuditCleanAnchor("100").commit()

      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

      assert(childNamed(testSetup, "regular-sync"))
      someTimePasses()
      auditName(testSetup) shouldBe None
      positions.get.rowReads.get() shouldBe 0
    }

  it should "run no audit where the writer holds no position store, and leave the flags down" taggedAs (
    UnitTest,
    SyncTest
  ) in withGuardAudit(None) { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    assert(childNamed(testSetup, "regular-sync"))
    someTimePasses()
    auditName(testSetup) shouldBe None
    storagesInstance.storages.appStateStorage.isStorageRecoveryDone() shouldBe false
  }

  "With the audit on, a start with a recovery flag down" should "go to regular sync at once, with no scan, and say who pays" taggedAs (
    UnitTest,
    SyncTest
  ) in withAuditOn() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    val said = syncControllerLogsDuring(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    }
    assert(childNamed(testSetup, "regular-sync"))
    assert(auditName(testSetup).isDefined)
    assert(!childNamed(testSetup, "combined-recovery-scan"))
    said.exists(_.contains("background audit pays this debt")) shouldBe true
  }

  it should "pay it: a finished pass sets both flags" taggedAs (UnitTest, SyncTest) in withAuditOn() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    freshBestOnPivotRoot(testSetup)
    val appState = storagesInstance.storages.appStateStorage

    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)

    eventually {
      someTimePasses()
      appState.isBytecodeRecoveryDone() shouldBe true
      appState.isStorageRecoveryDone() shouldBe true
    }
    appState.getSnapAuditCleanAnchor() shouldBe Some("100")
    withClue("nothing was owed after that, and the audit stopped: ") {
      eventually {
        someTimePasses()
        auditName(testSetup) shouldBe None
      }
    }
  }

  it should "reach the audit with do-snap-sync off too, where no start ever ran the scan" taggedAs (
    UnitTest,
    SyncTest
  ) in withAuditOn(
    doSnapSync = false
  ) { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    assert(childNamed(testSetup, "regular-sync"))
    assert(auditName(testSetup).isDefined)
    assert(!childNamed(testSetup, "combined-recovery-scan"))
  }

  it should "run no audit on a node that never finished SNAP" taggedAs (UnitTest, SyncTest) in withAuditOn(
    doSnapSync = false
  ) { testSetup =>
    import testSetup.*
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    assert(childNamed(testSetup, "regular-sync"))
    auditName(testSetup) shouldBe None
  }

  "With the audit off, a start with a recovery flag down" should "run the scan as before, and no audit" taggedAs (
    UnitTest,
    SyncTest
  ) in withRecoveryTestSetup() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    assert(childNamed(testSetup, "combined-recovery-scan"))
    assert(!childNamed(testSetup, "regular-sync"))
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "regular-sync"))
    }
    storagesInstance.storages.appStateStorage.isBytecodeRecoveryDone() shouldBe true
    auditName(testSetup) shouldBe None
  }

  it should "say the audit is off once, however often regular sync starts" taggedAs (
    UnitTest,
    SyncTest
  ) in withTestSetup() { testSetup =>
    import testSetup.*
    val said = syncControllerLogsDuring(ch.qos.logback.classic.Level.INFO) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      val regular = syncController.children.find(_.path.name.startsWith("regular-sync")).get
      system.stop(regular)
      eventually {
        someTimePasses()
        assert(syncController.children.exists(c => c.path.name.startsWith("regular-sync") && c != regular))
      }
    }
    said.count(_.contains("[HEAL-AUDIT] Off")) shouldBe 1
  }

  "The audit" should "stop when regular sync stops for a SNAP re-sync" taggedAs (UnitTest, SyncTest) in withAuditOn() {
    testSetup =>
      import testSetup.*
      seedSnapDoneWithScannableRoot(testSetup)
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      val running = auditName(testSetup).get
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(BigInt(500), "deadbeef"))
      childIsGone(testSetup, running)
      assert(childNamed(testSetup, "snap-sync"))
      auditName(testSetup) shouldBe None
  }

  it should "stop when regular sync stops for a bulk bytecode recovery" taggedAs (UnitTest, SyncTest) in withAuditOn() {
    testSetup =>
      import testSetup.*
      seedSnapDoneWithScannableRoot(testSetup)
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      val running = auditName(testSetup).get
      syncController ! SyncController.WrappedExternal(
        SyncProtocol.MissingCodeNeedsBulkRecovery(BigInt(500), ByteString(Array.fill[Byte](32)(0x44)))
      )
      childIsGone(testSetup, running)
  }

  it should "start afresh when regular sync restarts, and leave none of the old one" taggedAs (
    UnitTest,
    SyncTest
  ) in withAuditOn() { testSetup =>
    import testSetup.*
    seedSnapDoneWithScannableRoot(testSetup)
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    val running = auditName(testSetup).get
    system.stop(syncController.children.find(_.path.name.startsWith("regular-sync")).get)
    childIsGone(testSetup, running)
    eventually {
      someTimePasses()
      auditName(testSetup) should not be Some(running)
      auditName(testSetup) shouldBe defined
    }
  }

  it should "stop through the restart that first takes down a SNAP backfill" taggedAs (
    UnitTest,
    SyncTest
  ) in withAuditOn() { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    eventually {
      someTimePasses()
      assert(childNamed(testSetup, "snap-sync"))
    }
    appState.snapSyncDone().commit() // what a finalisation has written by the time it says so
    syncController ! SyncController.WrappedExternal(SNAPSyncController.SnapSyncFinalized(BigInt(1)))
    val running = auditName(testSetup).get
    syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.RegularSyncStuck(BigInt(500), "deadbeef"))
    childIsGone(testSetup, running)
    auditName(testSetup) shouldBe None
  }

  // ---- a refused checkpoint archive at the start (spec 018 C5c, T292 wiring; B-380) ----------------------------------
  //
  // CheckpointImportRetrySpec pins the importer's no-retry rule and recoverInterrupted. These pin the two call sites in
  // start(): the record is read before the import is decided, the operator's flag reaches the importer, and a skipped
  // archive leaves the node to SNAP. The archive is stream A, which is not this test chain's: an import that is
  // attempted is refused by its header checks, so "attempted" reads as a `failed` line, and "skipped" as a `skipped` one.

  private val checkpointArchiveBytes: Array[Byte] =
    com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2GoldenStreamSpec.golden("a")

  private val checkpointArchiveId: ByteString =
    com.chipprbots.ethereum.blockchain.checkpoint.CheckpointArchive.archiveIdOf(
      com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2Fixtures.decode(checkpointArchiveBytes)._1
    )

  private val RetryRefusedKey: String =
    com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.RetryRefusedKey

  /** A SNAP-on controller whose `checkpoint-sync-file` is stream A, written to a temp file. */
  private def withCheckpointArchive(retryRefused: Boolean)(test: TestSetup => Any): Unit =
    val archive = java.nio.file.Files.createTempFile("sync-controller-checkpoint", ".bin")
    java.nio.file.Files.write(archive, checkpointArchiveBytes)
    val testSetup = new TestSetup():
      override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
        doSnapSync = true,
        checkpointSyncFile = Some(archive),
        checkpointSyncRetryRefused = retryRefused
      )
    // the importer's trust is built from the genesis header, which a node always holds
    testSetup.blockchainWriter.storeBlockHeader(testSetup.baseBlockHeader).commit()
    try test(testSetup)
    finally
      testSetup.cleanup()
      java.nio.file.Files.deleteIfExists(archive)

  /** The `[CHECKPOINT IMPORT]` lines SyncController logs at WARN or above while it starts, once SNAP has started. */
  private def checkpointLinesAtStart(testSetup: TestSetup): List[String] =
    import testSetup.*
    val lines = syncControllerLogsAtLeast(ch.qos.logback.classic.Level.WARN) {
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
    }
    awaitSnapStarted(syncController)
    lines.filter(_.contains("[CHECKPOINT IMPORT]"))

  it should "skip a checkpoint archive this node refused before, say so, and start SNAP (T292)" taggedAs (
    UnitTest,
    SyncTest
  ) in withCheckpointArchive(retryRefused = false) { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    appState.recordRefusedCheckpoint(checkpointArchiveId).commitSync()

    val lines = checkpointLinesAtStart(testSetup)
    withClue(lines.mkString("\n")) {
      lines.count(l => l.contains("[CHECKPOINT IMPORT] skipped:") && l.contains(RetryRefusedKey)) shouldBe 1
      lines.exists(_.contains("[CHECKPOINT IMPORT] failed:")) shouldBe false
    }
    appState.getRefusedCheckpointArchives() shouldBe Seq(checkpointArchiveId)
    appState.getCheckpointImportInProgress() shouldBe None
    appState.isSnapSyncDone() shouldBe false
    appState.getBestBlockNumber() shouldBe 0
  }

  it should "attempt a refused checkpoint archive when the retry flag is set (T292)" taggedAs (
    UnitTest,
    SyncTest
  ) in withCheckpointArchive(retryRefused = true) { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    appState.recordRefusedCheckpoint(checkpointArchiveId).commitSync()

    val lines = checkpointLinesAtStart(testSetup)
    withClue(lines.mkString("\n")) {
      lines.exists(_.contains("[CHECKPOINT IMPORT] skipped:")) shouldBe false
      // past the no-retry check: the header checks refuse it, as this is not the archive's chain
      val failed = lines.filter(_.contains("[CHECKPOINT IMPORT] failed:"))
      failed should have size 1
      failed.head should include("BadFormat")
      failed.head shouldNot include("AlreadyRefused")
    }
    appState.getRefusedCheckpointArchives() shouldBe Seq(checkpointArchiveId)
    appState.isSnapSyncDone() shouldBe false
  }

  it should "attempt a checkpoint archive when only a different archive is recorded as refused (T292)" taggedAs (
    UnitTest,
    SyncTest
  ) in withCheckpointArchive(retryRefused = false) { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    val otherId = ByteString(Array.fill[Byte](32)(0x3c))
    appState.recordRefusedCheckpoint(otherId).commitSync()

    val lines = checkpointLinesAtStart(testSetup)
    withClue(lines.mkString("\n")) {
      lines.exists(_.contains("[CHECKPOINT IMPORT] skipped:")) shouldBe false
      lines.count(_.contains("[CHECKPOINT IMPORT] failed:")) shouldBe 1
    }
    // the attempt's header refusal is the archive's own fault, so it is recorded beside the other one
    appState.getRefusedCheckpointArchives() shouldBe Seq(otherId, checkpointArchiveId)
    appState.isSnapSyncDone() shouldBe false
  }

  it should "record a killed import's archive before the import is decided, and skip it in that same start (T292)" taggedAs (
    UnitTest,
    SyncTest
  ) in withCheckpointArchive(retryRefused = false) { testSetup =>
    import testSetup.*
    val appState = storagesInstance.storages.appStateStorage
    appState
      .beginCheckpointImport(ByteString(Array.fill[Byte](32)(1)), 5L, Some(checkpointArchiveId))
      .commitSync()
    appState.getRefusedCheckpointArchives() shouldBe empty

    val lines = checkpointLinesAtStart(testSetup)
    withClue(lines.mkString("\n")) {
      lines.count(_.contains("[CHECKPOINT IMPORT] skipped:")) shouldBe 1
      lines.exists(_.contains("[CHECKPOINT IMPORT] failed:")) shouldBe false
    }
    appState.getRefusedCheckpointArchives() shouldBe Seq(checkpointArchiveId)
    appState.isSnapSyncDone() shouldBe false
  }

  class TestSetup(
      _validators: Validators = new Mocks.MockValidatorsAlwaysSucceed
  ) extends EphemBlockchainTestSetup
      with TestSyncPeers
      with TestSyncConfig:

    // + cake overrides
    implicit override lazy val system: ActorSystem =
      ActorSystem("SyncControllerSpec_System", ConfigFactory.load("explicit-scheduler"))

    override lazy val vm: VMImpl = new VMImpl

    override lazy val validators: Validators = _validators

    override lazy val mining: TestMining = buildTestMining().withValidators(validators)

    // + cake overrides

    /** Off, so these scenarios keep the start's recovery scan: with the audit on the scan does not run (spec 022
      * decision 14). `withAuditOn` is the other case.
      */
    def auditEnabled: Boolean = false

    val networkPeerManager: TestProbe = TestProbe()
    val peerMessageBus: TypedActorRef[PeerEventBusActor.Command] =
      system.spawn(PeerEventBusActor.behavior(), "peer-event-bus")
    val pendingTransactionsManager: TestProbe = TestProbe()

    val ommersPool: TestProbe = TestProbe()

    val blacklist: CacheBasedBlacklist = CacheBasedBlacklist.empty(100)

    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(
      branchResolutionRequestSize = 30,
      checkForNewBlockInterval = 1.second,
      blockHeadersPerRequest = 10,
      blockBodiesPerRequest = 10,
      peersScanInterval = 1.second,
      redownloadMissingStateNodes = false,
      blacklistDuration = 1.second,
      peerResponseTimeout = 2.seconds
    )

    // SyncController is Pekko Typed (Group ROOT) — a Behavior[Any]. Spawn through PropsAdapter so this Classic spec
    // keeps `TestActorRef` child inspection (`syncController.children`). externalSchedulerOpt threads the
    // ExplicitlyTriggeredScheduler so `someTimePasses()` continues to drive the actor's inline scheduler callbacks;
    // its withTimers fire on the system scheduler (also the ExplicitlyTriggeredScheduler via explicit-scheduler.conf).
    lazy val blockTopic: org.apache.pekko.actor.typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
        "block-imported-topic"
      ),
      "block-imported-topic"
    )

    lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          this,
          externalSchedulerOpt = Some(system.scheduler),
          txGossipGate = txGossipGate,
          backgroundAuditEnabled = auditEnabled,
          // #1654: these fixtures store weights that do not follow from genesis; the pass would repair them.
          chainWeightReconcileOn = false,
          stateEscalation = stateEscalation,
          pathLiveSync = pathLiveSync
        )
      )
    )

    /** What the controller gives the state up with (spec 018 C5a part 7b): set before the controller is first used. */
    var stateEscalation: Option[SyncController.StateEscalationPort] = None

    /** A sync on the unified layout (spec 018 C5a part 10): set before the controller is first used. */
    var pathLiveSync: Option[SyncController.PathLiveSyncPort] = None

    /** The node's tx gossip gate, as NodeBuilder shares it between SyncController, the Engine API and the pool. */
    lazy val txGossipGate: com.chipprbots.ethereum.transactions.TxGossipGate =
      new com.chipprbots.ethereum.transactions.TxGossipGate()

    /** A second SyncController on the same storages and peers: the node after a restart. */
    def restartedSyncController(): TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          this,
          externalSchedulerOpt = Some(system.scheduler),
          backgroundAuditEnabled = auditEnabled,
          // #1654: these fixtures store weights that do not follow from genesis; the pass would repair them.
          chainWeightReconcileOn = false
        )
      )
    )

    /** Stop `controller` and its children, as a node shutdown would. */
    def stopNode(controller: TestActorRef[Nothing]): Unit =
      val watcher = TestProbe()
      watcher.watch(controller)
      system.stop(controller)
      watcher.expectTerminated(controller)

    val baseBlockHeader = Fixtures.Blocks.Genesis.header

    blockchainWriter.storeChainWeight(baseBlockHeader.parentHash, ChainWeight.zero).commit()

    private def testScheduler = system.scheduler.asInstanceOf[ExplicitlyTriggeredScheduler]

    def someTimePasses(): Unit =
      testScheduler.timePasses(3000.millis)

    def timePasses(duration: FiniteDuration): Unit =
      testScheduler.timePasses(duration)

    /** Answer every peer poll SyncController's children make with `peers`. */
    def answerPeerPollsWith(peers: Map[Peer, PeerInfo]): Unit =
      networkPeerManager.setAutoPilot(
        new AutoPilot:
          override def run(sender: ActorRef, msg: Any): AutoPilot =
            msg.asMatchable match
              case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) => replyTo ! HandshakedPeers(peers)
              case _                                                      => ()
            this
      )

    /** Answer every peer poll with what `peers` gives at that moment; `None` leaves the poll unanswered. */
    def answerPeerPollsFrom(peers: () => Option[Map[Peer, PeerInfo]]): Unit =
      networkPeerManager.setAutoPilot(
        new AutoPilot:
          override def run(sender: ActorRef, msg: Any): AutoPilot =
            msg.asMatchable match
              case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
                peers().foreach(p => replyTo ! HandshakedPeers(p))
              case _ => ()
            this
      )

    def snapPeerAt(height: BigInt): Map[Peer, PeerInfo] =
      val status = peer1Status.copy(capability = Capability.ETH68, supportsSnap = true)
      Map(
        peer1 -> PeerInfo(
          status,
          forkAccepted = true,
          chainWeight = status.chainWeight,
          maxBlockNumber = height,
          bestBlockHash = status.bestHash
        )
      )

    def cleanup(): Unit =
      Await.result(system.terminate(), 10.seconds)

  def withTestSetup(validators: Validators = new Mocks.MockValidatorsAlwaysSucceed)(test: TestSetup => Any): Unit =
    val testSetup = new TestSetup(validators)
    try test(testSetup)
    finally testSetup.cleanup()

  /** A SyncController that starts straight into regular sync with a real ForkChoiceManager, on a chain whose
    * terminal-total-difficulty is `terminalTotalDifficulty`. `Some` is the ETH/Sepolia shape (clPivotEnabled), `None`
    * the ETC/Mordor one.
    */
  class PosRegularSyncSetup(terminalTotalDifficulty: Option[BigInt]) extends TestSetup():
    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = false)

    lazy val forkChoiceManager: com.chipprbots.ethereum.consensus.engine.ForkChoiceManager =
      new com.chipprbots.ethereum.consensus.engine.ForkChoiceManager(blockchainReader, blockchainWriter)

    val zero32: ByteString = ByteString(new Array[Byte](32))

    private val baseChainConfig: BlockchainConfig = blockchainConfig
    private lazy val chainConfigBuilder: com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder =
      new com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        implicit override def blockchainConfig: BlockchainConfig =
          baseChainConfig.copy(terminalTotalDifficulty = terminalTotalDifficulty)

    override lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          chainConfigBuilder,
          forkChoiceManagerOpt = Some(forkChoiceManager),
          externalSchedulerOpt = Some(system.scheduler),
          backgroundAuditEnabled = auditEnabled,
          // #1654: these fixtures store weights that do not follow from genesis; the pass would repair them.
          chainWeightReconcileOn = false
        )
      )
    )

    def startRegularSyncAndWait(): Unit =
      syncController ! SyncController.WrappedSyncProtocol(SyncProtocol.Start)
      eventually {
        someTimePasses()
        assert(syncController.children.exists(_.path.name.startsWith("regular-sync")))
      }

  def withPosRegularSyncSetup(terminalTotalDifficulty: Option[BigInt])(test: PosRegularSyncSetup => Any): Unit =
    val testSetup = new PosRegularSyncSetup(terminalTotalDifficulty)
    try test(testSetup)
    finally testSetup.cleanup()

  def withAuditOn(doSnapSync: Boolean = true)(test: TestSetup => Any): Unit =
    val testSetup = new TestSetup():
      override def auditEnabled: Boolean = true
      override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = doSnapSync)
    try test(testSetup)
    finally testSetup.cleanup()

  /** A SyncController on the unified layout, SNAP on, on a chain with a terminal total difficulty (the ETH/Sepolia
    * shape): `pathLiveSync` is set before it is first used.
    */
  class GuardPosSetup extends TestSetup():
    override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = true)

    private val baseChainConfig: BlockchainConfig = blockchainConfig
    private lazy val chainConfigBuilder: com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder =
      new com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        implicit override def blockchainConfig: BlockchainConfig =
          baseChainConfig.copy(terminalTotalDifficulty = Some(BigInt(0)))

    override lazy val syncController: TestActorRef[Nothing] = TestActorRef(
      org.apache.pekko.actor.typed.scaladsl.adapter.PropsAdapter(
        SyncController(
          blockchain,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.appStateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.flatSlotStorage,
          storagesInstance.storages.fastSyncStateStorage,
          consensusAdapter,
          validators,
          peerMessageBus,
          pendingTransactionsManager.ref
            .toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          blockTopic,
          ommersPool.ref,
          networkPeerManager.ref,
          blacklist,
          syncConfig,
          chainConfigBuilder,
          externalSchedulerOpt = Some(system.scheduler),
          backgroundAuditEnabled = auditEnabled,
          chainWeightReconcileOn = false,
          pathLiveSync = pathLiveSync
        )
      )
    )

  def withGuardPosSetup()(test: GuardPosSetup => Any): Unit =
    val testSetup = new GuardPosSetup
    try test(testSetup)
    finally testSetup.cleanup()

  def withRecoveryTestSetup()(test: TestSetup => Any): Unit =
    val testSetup = new TestSetup():
      override def defaultSyncConfig: SyncConfig = super.defaultSyncConfig.copy(doSnapSync = true)
    try test(testSetup)
    finally testSetup.cleanup()

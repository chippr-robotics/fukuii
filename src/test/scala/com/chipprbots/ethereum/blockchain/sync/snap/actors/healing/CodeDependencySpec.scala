package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealedCodeStored
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.HealingBlockedTick
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Spec 022 T031 and T037 (plan D4, geth `AddCodeEntry`): an account leaf whose bytecode is missing is held, and
  * everything above it with it, until the bytecode coordinator acknowledges the code write. If the code stays
  * unavailable healing stays incomplete: it logs and asks again every minute, and after 30 minutes with no progress it
  * abandons (the lazy hand-off), never completes.
  *
  * Fixture: a root branch over two contract accounts with no storage, each with its own bytecode.
  */
class CodeDependencySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  import HealRequestFixtures.*

  private val code0 = ByteString("code-dependency/code0")
  private val code1 = ByteString("code-dependency/code1")
  private val hash0: ByteString = kec256(code0)
  private val hash1: ByteString = kec256(code1)

  private def contract(codeHash: ByteString, nonce: Long): ByteString =
    ByteString(
      Account.accountSerializer.toBytes(
        Account(nonce = UInt256(nonce), balance = UInt256(1L), codeHash = CodeHash(codeHash))
      )
    )

  private val rest0: Array[Byte] = (0 until 63).map(i => ((i * 5 + 1) % 16).toByte).toArray
  private val rest1: Array[Byte] = (0 until 63).map(i => ((i * 3 + 7) % 16).toByte).toArray

  private def trie(): Trie =
    val acct0 = LeafNode(ByteString(rest0), contract(hash0, 1L))
    val acct1 = LeafNode(ByteString(rest1), contract(hash1, 2L))
    val root = branch(0 -> acct0, 1 -> acct1)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("acct0", None, nibbles(0), acct0),
        Placed("acct1", None, nibbles(1), acct1)
      )
    )

  /** A code store whose reads throw while `failing` is set: a store the tick cannot look at. */
  final private class ReadFailingCodes extends EvmCodeStorage(new EphemDataSource(Map.empty)):
    @volatile var failing: Boolean = false
    override def get(key: ByteString): Option[ByteString] =
      if failing then throw new RuntimeException("injected code read failure")
      super.get(key)

  /** A data source whose writes, or range reads, fail on demand. */
  final private class FailingSource extends EphemDataSource(Map.empty):
    @volatile var failWrites: Boolean = false
    @volatile var failScans: Boolean = false
    override def update(updates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Unit =
      if failWrites then throw new RuntimeException("injected write failure")
      super.update(updates)
    override def updateSync(updates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Unit =
      if failWrites then throw new RuntimeException("injected write failure")
      super.updateSync(updates)
    override def scanRangeLimit(
        namespace: com.chipprbots.ethereum.db.dataSource.DataSource.Namespace,
        fromKey: Array[Byte],
        toKeyExclusive: Array[Byte],
        limit: Int
    ): Iterator[(Array[Byte], Array[Byte])] =
      if failScans then throw new RuntimeException("injected read failure")
      super.scanRangeLimit(namespace, fromKey, toKeyExclusive, limit)

  final private class Rig(
      scheme: StorageScheme,
      withSet: Boolean = false,
      val t: Trie = trie(),
      served: Option[Map[Seq[ByteString], ByteString]] = None,
      awaited: Set[ByteString] = Set(hash0, hash1),
      heldMemoryLimit: Long = TrieNodeHealingCoordinator.DefaultHeldMemoryLimit
  ):
    val ec = new ManualExecutionContext
    val hashStore = new TestMptStorage()
    val pathSource = new FailingSource
    val pns = new PathNodeStorage(pathSource)
    val codes = new ReadFailingCodes
    val progressSource = new FailingSource
    val progress = new SnapSyncProgressStorage(progressSource)
    val frontier = new com.chipprbots.ethereum.db.storage.HealingFrontierStorage(new EphemDataSource(Map.empty))
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = t.root.hash,
      networkPeerManager = peer.ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = if scheme == StorageScheme.Hash then hashStore else new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(ec),
      storageScheme = scheme,
      pathNodeStorageOpt = Option.when(scheme == StorageScheme.Path)(pns),
      movingRootDeltaHeal = true,
      evmCodeStorage = Some(codes),
      excludedStore = Option.when(withSet)(progress),
      healingFrontierStorage = Option.when(withSet)(frontier),
      frontierPersistenceEnabled = withSet,
      heldMemoryLimit = heldMemoryLimit
    )
    peer.coordinator.set(coordinator)
    peer.serveFrom.set(Some(served.getOrElse(t.served)))

    def available(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("code-peer", createTestProbe[Any]().ref.toClassic)
      )

    /** Run what comes, a peer round trip at a time, until `n` is on disk. */
    def awaitOnDisk(n: Placed): Unit =
      val deadline = 30.seconds.fromNow
      val pause = createTestProbe[Any]()
      def present =
        if scheme == StorageScheme.Hash then
          try
            hashStore.get(n.hash.toArray); true
          catch case _: Exception => false
        else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)
      while !present do
        withClue(s"${n.label} on disk within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        drive()
        pause.expectNoMessage(10.millis)
      drive()

    /** Start, and run until both account leaves have arrived and wait on their code (peer round trips included). */
    def begin(): Unit =
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(t.root.hash)
      available()
      val deadline = 30.seconds.fromNow
      def reported = heldBack.collect { case SNAPSyncController.HealedCodeHashes(h) => h }.flatten.toSet
      while reported != awaited do
        withClue("both leaves within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        heldBack ++= Seq(controller.receiveMessage(deadline.timeLeft))
      drive()

    def settle(): Unit =
      (1 to 4).foreach { _ =>
        val probe = createTestProbe[HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    def drive(steps: Int = 24): Unit =
      settle()
      var left = steps
      while left > 0 && ec.pending > 0 do
        ec.runNext()
        settle()
        left -= 1

    def onDisk(label: String): Boolean =
      val n = t(label)
      if scheme == StorageScheme.Hash then
        try
          hashStore.get(n.hash.toArray); true
        catch case _: Exception => false
      else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

    // What `begin` read while it waited for both leaves.
    private var heldBack = Seq.empty[SNAPSyncController.Command]

    /** What the controller has received so far. The probe is an actor of its own: a barrier through it, sent after what
      * is being looked for, shows it has all of that.
      */
    def seen(): Seq[SNAPSyncController.Command] =
      val barrier = SNAPSyncController.ProgressNodesHealed(Long.MinValue)
      controller.ref ! barrier
      val out = Seq.newBuilder[SNAPSyncController.Command]
      var next = controller.receiveMessage(30.seconds)
      while next != barrier do
        out += next
        next = controller.receiveMessage(30.seconds)
      val all = heldBack ++ out.result()
      heldBack = Seq.empty
      all

    def store(code: ByteString): Unit =
      codes.put(kec256(code), code).commit()

    def ack(code: ByteString): Unit =
      store(code)
      coordinator ! HealedCodeStored(Seq(kec256(code)))

    def ticks(n: Int): Unit =
      (1 to n).foreach(_ => coordinator ! HealingBlockedTick)
      settle()

    /** Run what comes until the controller hears the completion. */
    def awaitComplete(): Unit =
      val deadline = 30.seconds.fromNow
      var done = heldBack.contains(SNAPSyncController.StateHealingComplete)
      while !done do
        drive()
        withClue("no completion within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        try done = controller.receiveMessage(100.millis) == SNAPSyncController.StateHealingComplete
        catch case _: AssertionError => ()

  private val Schemes = Seq(StorageScheme.Hash, StorageScheme.Path)

  /** A newer root over the same two accounts and a third: on a re-peg the first two are on disk already. */
  private def trieWithThirdAccount(): Trie =
    val acct0 = LeafNode(ByteString(rest0), contract(hash0, 1L))
    val acct1 = LeafNode(ByteString(rest1), contract(hash1, 2L))
    val acct2 = LeafNode(ByteString(rest0), ByteString(Account.accountSerializer.toBytes(Account(nonce = UInt256(9L)))))
    val root = branch(0 -> acct0, 1 -> acct1, 2 -> acct2)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("acct0", None, nibbles(0), acct0),
        Placed("acct1", None, nibbles(1), acct1),
        Placed("acct2", None, nibbles(2), acct2)
      )
    )

  // Bytes that are no trie node, which a parent may still reference by hash: a peer serving them passes the keccak check
  // and fails to decode, so the parent's edge never closes and no fetch is coming.
  private val undecodable: ByteString = ByteString(Array.fill[Byte](40)(0x05))

  /** root -> [0] a leaf waiting on code, [1] the undecodable node. */
  private def orphaningTrie(): (Trie, Map[Seq[ByteString], ByteString]) =
    val acct0 = LeafNode(ByteString(rest0), contract(hash0, 1L))
    val slots = Array.fill[com.chipprbots.ethereum.mpt.MptNode](16)(com.chipprbots.ethereum.mpt.NullNode)
    slots(0) = com.chipprbots.ethereum.mpt.HashNode(acct0.hash)
    slots(1) = com.chipprbots.ethereum.mpt.HashNode(kec256(undecodable).toArray)
    val root = com.chipprbots.ethereum.mpt.BranchNode(slots, None)
    val t = Trie(Seq(Placed("root", None, Array.empty[Byte], root), Placed("acct0", None, nibbles(0), acct0)))
    val undecodablePath = Seq(ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(Array[Byte](1), isLeaf = false)))
    (t, t.served + (undecodablePath -> undecodable))

  for scheme <- Schemes do
    "A healing coordinator" should s"hold an account leaf with missing code, and all above it, until the code write is acknowledged, on $scheme (T031)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      val first = rig.seen()
      withClue("the code is reported for fetching: ") {
        first.collect { case SNAPSyncController.HealedCodeHashes(h) => h }.flatten.toSet shouldBe Set(hash0, hash1)
      }
      withClue("no completion: ")(first should not contain SNAPSyncController.StateHealingComplete)
      withClue("leaves and root held, not on disk: ") {
        rig.onDisk("acct0") shouldBe false
        rig.onDisk("acct1") shouldBe false
        rig.onDisk("root") shouldBe false
      }
      rig.ack(code0)
      rig.drive()
      withClue("acct0 goes with its code, the root waits for acct1: ") {
        rig.onDisk("acct0") shouldBe true
        rig.onDisk("root") shouldBe false
      }
      rig.ack(code1)
      rig.awaitComplete()
      rig.onDisk("root") shouldBe true
    }

    it should s"commit on a stored code whose acknowledgement was lost, at the next tick, on $scheme (P3)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      withClue("held: ")(rig.onDisk("root") shouldBe false)
      rig.store(code0)
      rig.store(code1)
      rig.ticks(1)
      rig.awaitComplete()
      rig.onDisk("root") shouldBe true
    }

    it should s"ask again every tick and abandon after ${TrieNodeHealingCoordinator.MaxBlockedTicks} ticks with no progress, never complete, on $scheme (T037)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - 1)
      val before = rig.seen()
      withClue("still waiting: ")(before should not contain SNAPSyncController.StateHealingAbandoned)
      withClue("it asks again: ") {
        before.count(
          _.isInstanceOf[SNAPSyncController.HealedCodeHashes]
        ) shouldBe TrieNodeHealingCoordinator.MaxBlockedTicks - 1
      }
      rig.ticks(1)
      val at = rig.seen()
      at should contain(SNAPSyncController.StateHealingAbandoned)
      at should not contain SNAPSyncController.StateHealingComplete
      withClue("nothing above the waiting leaf is on disk: ")(rig.onDisk("root") shouldBe false)
    }

    it should s"count a code write as progress, on $scheme (T037)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - 1)
      rig.ack(code0)
      rig.settle()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks) // the first of these sees the progress and resets the count
      rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
      rig.ticks(1)
      rig.seen() should contain(SNAPSyncController.StateHealingAbandoned)
    }

    // ---- The user's decision, 2026-10-30 (plan D4, FR-006, T037): when unservable bytecode is the only blocker ----------

    it should s"after ${TrieNodeHealingCoordinator.MaxBlockedTicks} idle minutes write the chains waiting only on code, walk, then hand off lazily: never a completion, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true)
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - 1)
      withClue("one minute short, nothing moved: ") {
        rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
        rig.onDisk("root") shouldBe false
        rig.progress.hasAnyExcluded shouldBe false
      }
      rig.ticks(1)
      withClue("what the chains lack is durable before anything else: ") {
        rig.progress.excludedPage(None, 10).map(e => (e.subject, e.isCodeOnly)).toSet shouldBe
          Set((hash0, true), (hash1, true))
      }
      withClue("the walk comes before the hand-off: ") {
        rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
        rig.ec.pending should be > 0
      }
      rig.drive()
      withClue("the waiting chains are written, whole: ") {
        Seq("acct0", "acct1", "root").foreach(l => withClue(s"$l: ")(rig.onDisk(l) shouldBe true))
      }
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      withClue("never a completion, and no marker: ") {
        end should not contain SNAPSyncController.StateHealingComplete
        rig.frontier.isComplete shouldBe false
      }
      rig.progress.hasExcludedCode shouldBe true
    }

    it should s"hand off the same way when a code entry already in the set is part of what blocks, on $scheme" taggedAs UnitTest in {
      val other = kec256(ByteString("code-dependency/never-served"))
      val rig = new Rig(scheme, withSet = true, awaited = Set(hash0, hash1, other))
      rig.progress.recordExcluded(Seq(ExcludedAccount(other, ExcludedReason.CodeMissing)))
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      rig.drive()
      rig.onDisk("root") shouldBe true
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      rig.progress.excludedPage(None, 10).map(_.subject).toSet shouldBe Set(hash0, hash1, other)
    }

    it should s"go back to a normal completion if all the code arrives while it walks, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true)
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      rig.ack(code0)
      rig.ack(code1)
      rig.awaitComplete()
      rig.onDisk("root") shouldBe true
      rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
      rig.progress.hasAnyExcluded shouldBe false
    }

    it should s"keep what is still missing in the set if part of the code arrives while it walks, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true)
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      rig.ack(code0)
      rig.drive()
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      rig.progress.excludedPage(None, 10).map(_.subject) shouldBe Seq(hash1)
    }

    it should s"survive a re-peg between the commit and the hand-off: nothing is claimed, and the new root hands off in its turn, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true)
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      val next = trieWithThirdAccount()
      rig.peer.serveFrom.set(Some(next.served))
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.available()
      rig.awaitOnDisk(next.root)
      withClue("the old root's walk and arming claim nothing: ") {
        val after = rig.seen()
        after should not contain SNAPSyncController.StateHealingAbandoned
        after should not contain SNAPSyncController.StateHealingComplete
      }
      rig.ticks(1) // the count is not reset by a re-peg: the next idle minute acts
      rig.drive()
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      rig.progress.hasExcludedCode shouldBe true
    }

    it should s"hold nothing back, and commit nothing, when something other than code blocks: a child no fetch can supply, on $scheme" taggedAs UnitTest in {
      val (t, served) = orphaningTrie()
      val rig = new Rig(scheme, withSet = true, t = t, served = Some(served), awaited = Set(hash0))
      rig.begin()
      rig.seen()
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      withClue("no commit: the root would stand over a child that is absent: ") {
        rig.onDisk("root") shouldBe false
        rig.onDisk("acct0") shouldBe false
      }
      withClue("no entry: nothing was written on its strength: ")(rig.progress.hasAnyExcluded shouldBe false)
      rig.ec.pending shouldBe 0
    }

    it should s"commit nothing when the set cannot be read: unknown is not only code, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true)
      rig.begin()
      rig.seen()
      rig.progressSource.failScans = true
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      rig.onDisk("root") shouldBe false
    }

    it should s"count a minute with only paused new-subtree fetches queued as idle, and treat them as more than code, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true, heldMemoryLimit = 1L)
      rig.begin()
      rig.seen()
      val elsewhere = plainLeaf(nibbles(0x0a), "code-dependency/new-subtree")
      rig.coordinator ! TrieNodeHealingCoordinator.QueueMissingNodes(
        Seq(
          (
            Seq(ByteString(com.chipprbots.ethereum.mpt.HexPrefix.encode(nibbles(0x0a), isLeaf = false))),
            ByteString(elsewhere.hash)
          )
        )
      )
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      withClue("no commit: a queued fetch is not code: ") {
        rig.onDisk("root") shouldBe false
        rig.progress.hasAnyExcluded shouldBe false
      }
    }

    if scheme == StorageScheme.Path then
      it should "commit nothing more when a commit write is failing: that is not only code" taggedAs UnitTest in {
        val rig = new Rig(scheme, withSet = true)
        rig.begin()
        rig.seen()
        rig.pathSource.failWrites = true
        rig.ack(code0) // acct0 is ready now, and its commit fails
        rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks + 1) // the first sees the progress
        val end = rig.seen()
        end should contain(SNAPSyncController.StateHealingAbandoned)
        end should not contain SNAPSyncController.StateHealingComplete
        withClue("no code entry: nothing was armed: ")(rig.progress.hasAnyExcluded shouldBe false)
        rig.pathSource.failWrites = false
      }

    it should s"stay held, and try again next minute, when the code entries cannot be written, on $scheme (P3)" taggedAs UnitTest in {
      val rig = new Rig(scheme, withSet = true)
      rig.begin()
      rig.seen()
      rig.progressSource.failWrites = true
      rig.ticks(TrieNodeHealingCoordinator.MaxBlockedTicks)
      withClue("no commit without the record: ")(rig.onDisk("root") shouldBe false)
      val waiting = rig.seen()
      waiting should not contain SNAPSyncController.StateHealingAbandoned
      waiting should not contain SNAPSyncController.StateHealingComplete
      rig.progressSource.failWrites = false
      rig.ticks(1)
      rig.drive()
      rig.onDisk("root") shouldBe true
      rig.seen() should contain(SNAPSyncController.StateHealingAbandoned)
    }

    it should s"keep a held leaf's code wait through a same-root refresh, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(rig.t.root.hash) // same root: nothing changes
      rig.drive()
      rig.onDisk("root") shouldBe false
      rig.ack(code0)
      rig.ack(code1)
      rig.awaitComplete()
    }

    it should s"not run the dead-pulse walk while a held leaf waits on code, on $scheme" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      withClue("held: ")(rig.onDisk("root") shouldBe false)
      (1 to 4).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingStagnationCheck)
      rig.settle()
      withClue("no walk: nothing is missing that a walk could find, and a mainnet walk takes hours: ") {
        rig.ec.pending shouldBe 0
      }
    }

    it should s"keep waiting through a tick that cannot read the code store, on $scheme (P3)" taggedAs UnitTest in {
      val rig = new Rig(scheme)
      rig.begin()
      rig.codes.failing = true
      rig.ticks(1)
      rig.codes.failing = false
      withClue("still held: ")(rig.onDisk("root") shouldBe false)
      rig.ack(code0)
      rig.ack(code1)
      rig.awaitComplete()
    }

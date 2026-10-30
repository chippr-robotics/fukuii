package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingStatistics
import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealingTrieFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.readAtPath
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator.Command
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.*
import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.ManualExecutionContext
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** A data source whose range reads throw while `failing` is set: an excluded set that cannot be read. */
final private class ScanFailingDataSource extends EphemDataSource(Map.empty):
  @volatile var failing: Boolean = false
  @volatile var pageReads: Int = 0 // reads of a page, not the one-entry reads that ask whether there is anything
  override def scanRangeLimit(ns: IndexedSeq[Byte], from: Array[Byte], to: Array[Byte], limit: Int) =
    if failing then throw new RuntimeException("injected excluded-set read failure")
    if limit > 1 then pageReads += 1
    super.scanRangeLimit(ns, from, to, limit)

/** Spec 022 T029 (Path) and T030 (Hash), amended FR-005 and FR-006: an account whose storage or code was left
  * incomplete never looks complete to healing. It is recorded durably, reached from the current root and completed
  * before completion is declared, whatever made it incomplete and however the root moves.
  *
  * Fixture: two accounts, `acct0` over a two-slot storage trie and `acct1` over a one-slot one.
  * {{{
  *   root (branch)              []
  *     ├─[0] acct0              [0]      account hash A0, storage root s0
  *     └─[1] acct1              [1]      account hash A1, storage root s1
  *   s0 (branch)                (A0, [])
  *     ├─[0] s0a                (A0, [0])     stored by the partial download
  *     └─[1] s0b                (A0, [1])     never downloaded
  *   s1 (leaf)                  (A1, [])
  * }}}
  */
class ExcludedAccountsSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
  implicit private val actorTestKit: ActorTestKit = testKit

  private val Schemes = Seq(StorageScheme.Path, StorageScheme.Hash)

  private val rest0: Array[Byte] = (0 until 63).map(i => ((i * 5 + 1) % 16).toByte).toArray
  private val rest1: Array[Byte] = (0 until 63).map(i => ((i * 3 + 7) % 16).toByte).toArray
  private val a0: ByteString = packNibbles(0.toByte +: rest0)
  private val a1: ByteString = packNibbles(1.toByte +: rest1)
  private val code: ByteString = ByteString("excluded-accounts/bytecode")
  private val codeHash: ByteString = kec256(code)

  private def account(storageRoot: ByteString, nonce: Long, withCode: Boolean): ByteString =
    val base = Account(nonce = UInt256(nonce), balance = UInt256(1000L), storageRoot = TrieRoot(storageRoot))
    val full = if withCode then base.copy(codeHash = com.chipprbots.ethereum.domain.CodeHash(codeHash)) else base
    ByteString(Account.accountSerializer.toBytes(full))

  private def fixture(withCode: Boolean = false, acct1Nonce: Long = 2L): Trie =
    val s0a = plainLeaf(nibbles(0x0a), "excluded/s0a")
    val s0b = plainLeaf(nibbles(0x0b), "excluded/s0b")
    val s0 = branch(0 -> s0a, 1 -> s0b)
    val s1 = plainLeaf(nibbles(0x0c), "excluded/s1")
    val acct0 = LeafNode(ByteString(rest0), account(ByteString(s0.hash), 1L, withCode))
    val acct1 = LeafNode(ByteString(rest1), account(ByteString(s1.hash), acct1Nonce, withCode = false))
    val root = branch(0 -> acct0, 1 -> acct1)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("acct0", None, nibbles(0), acct0),
        Placed("acct1", None, nibbles(1), acct1),
        Placed("s0", Some(a0), Array.empty[Byte], s0),
        Placed("s0a", Some(a0), nibbles(0), s0a),
        Placed("s0b", Some(a0), nibbles(1), s0b),
        Placed("s1", Some(a1), Array.empty[Byte], s1)
      )
    )

  final private class Rig(
      val scheme: StorageScheme,
      val trie: Trie,
      absent: Set[String],
      excluded: Seq[ExcludedAccount],
      progressSource: EphemDataSource = new EphemDataSource(Map.empty)
  ):
    val ec = new ManualExecutionContext
    val progress = new SnapSyncProgressStorage(progressSource)
    progress.recordExcluded(excluded)
    val hashStore = new TestMptStorage()
    val pns = new PathNodeStorage(new EphemDataSource(Map.empty))
    val codes = new EvmCodeStorage(new EphemDataSource(Map.empty))
    seed(trie.nodes.filterNot(n => absent(n.label)))
    val peer = new ScriptedPeer(testKit)
    val controller: TestProbe[SNAPSyncController.Command] = createTestProbe[SNAPSyncController.Command]()
    val coordinator: ActorRef[Command] = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
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
      excludedStore = Some(progress)
    )
    peer.coordinator.set(coordinator)

    def seed(nodes: Seq[Placed]): Unit =
      if scheme == StorageScheme.Hash then PathHealPresenceFixtures.seedHash(hashStore, nodes)
      else PathHealPresenceFixtures.seedPath(pns, nodes)

    def start(): Unit =
      coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
      settle()

    def peerAvailable(): Unit =
      coordinator ! TrieNodeHealingCoordinator.HealingPeerAvailable(
        PeerTestHelpers.createTestPeer("excluded-peer", createTestProbe[Any]().ref.toClassic)
      )

    def serve(t: Trie): Unit = peer.serveFrom.set(Some(t.served))

    def onDisk(n: Placed): Boolean =
      if scheme == StorageScheme.Hash then
        try
          hashStore.get(n.hash.toArray); true
        catch case _: Exception => false
      else readAtPath(pns, n).map(ByteString(_)).contains(n.encoded)

    /** Mailbox barrier, four deep: a message sent before the call, the check it sends, and what those send. */
    def settle(): Unit =
      (1 to 4).foreach { _ =>
        val probe = createTestProbe[HealingStatistics]()
        coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
        probe.receiveMessage(30.seconds)
      }

    /** Run what waits on the writer executor and what follows from it. */
    def drive(steps: Int = 32): Unit =
      settle()
      var left = steps
      while left > 0 && ec.pending > 0 do
        ec.runNext()
        settle()
        left -= 1

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
      out.result()

    /** Run what comes, a peer round trip at a time, until `n` is on disk. */
    def awaitOnDisk(n: Placed): Unit =
      val deadline = 30.seconds.fromNow
      val pause = createTestProbe[Any]()
      while !onDisk(n) do
        withClue(s"${n.label} on disk within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        drive()
        pause.expectNoMessage(10.millis)
      drive()

    /** Run what comes, a peer round trip at a time, until the controller hears the completion. */
    def awaitComplete(): Unit =
      val deadline = 30.seconds.fromNow
      var done = false
      while !done do
        drive()
        withClue("no completion within the deadline: ")(deadline.hasTimeLeft() shouldBe true)
        try done = controller.receiveMessage(100.millis) == SNAPSyncController.StateHealingComplete
        catch case _: AssertionError => ()

    def requested: Seq[Seq[ByteString]] = peer.requests.flatten

  /** The three storage reasons: what each leaves on disk. A root mismatch leaves another trie's root at `s0`'s
    * position.
    */
  private val StorageReasons = Seq(
    ("abandoned at force-complete", ExcludedReason.StorageAbandoned, false),
    ("skipped after max-empty", ExcludedReason.StorageSkipped, false),
    ("rejected for a root mismatch", ExcludedReason.StorageRootMismatch, true)
  )

  private def storageRig(scheme: StorageScheme, reason: ExcludedReason, staleRoot: Boolean): Rig =
    val trie = fixture()
    val rig = new Rig(
      scheme,
      trie,
      absent = Set("s0", "s0b"),
      excluded = Seq(ExcludedAccount(a0, reason, claimedStorageRoot = trie("s0").hash))
    )
    // The trie the mismatched download built: its root's bytes, of another hash, at `s0`'s position.
    if staleRoot then rig.seed(Seq(trie("s0").copy(node = plainLeaf(nibbles(0x01), "excluded/stale-root"))))
    rig

  for (name, reason, stale) <- StorageReasons do
    "A healing coordinator on Path" should s"delete the positions on the key of an account $name, heal it from the root and only then complete (T029)" taggedAs UnitTest in {
      val rig = storageRig(StorageScheme.Path, reason, stale)
      rig.start()
      withClue("every position on the key is gone, in one write: ") {
        readAtPath(rig.pns, rig.trie("root")) shouldBe None
        readAtPath(rig.pns, rig.trie("acct0")) shouldBe None
      }
      withClue("another account's nodes stay: ") {
        rig.onDisk(rig.trie("acct1")) shouldBe true
        rig.onDisk(rig.trie("s1")) shouldBe true
      }
      withClue("nothing is served, so no completion: ") {
        rig.peerAvailable()
        rig.drive()
        rig.seen() should not contain SNAPSyncController.StateHealingComplete
        rig.progress.hasExcluded shouldBe true
      }
      rig.serve(rig.trie)
      rig.peerAvailable()
      rig.peer.manual.receiveMessages(1, 30.seconds).foreach(rig.peer.answerFrom(_, rig.trie.served))
      rig.awaitComplete()
      withClue("the account was reached from the root: ")(rig.requested should contain(rig.trie("acct0").pathset))
      rig.trie.nodes.foreach(n => withClue(s"${n.label} on disk: ")(rig.onDisk(n) shouldBe true))
      withClue("the entry left once the account was complete: ")(rig.progress.hasExcluded shouldBe false)
    }

    "A healing coordinator on Hash" should s"seed the storage root of an account $name, resolved against the current root, and only then complete (T030)" taggedAs UnitTest in {
      val rig = storageRig(StorageScheme.Hash, reason, stale)
      rig.serve(rig.trie)
      rig.peerAvailable()
      rig.start() // the walk of the resume stays queued on the writer executor
      rig.settle()
      withClue("the seed goes out without any walk having run: ")(rig.requested should contain(rig.trie("s0").pathset))
      withClue("no completion while the entry stands: ")(
        rig.seen() should not contain SNAPSyncController.StateHealingComplete
      )
      rig.awaitComplete()
      rig.trie.nodes.foreach(n => withClue(s"${n.label} on disk: ")(rig.onDisk(n) shouldBe true))
      withClue("the entry left once the account was complete: ")(rig.progress.hasExcluded shouldBe false)
    }

  for scheme <- Schemes do
    "A healing coordinator" should s"not complete over a clean walk while a bytecode the download gave up on is missing, on $scheme (T029, T030)" taggedAs UnitTest in {
      val rig =
        new Rig(
          scheme,
          fixture(withCode = true),
          absent = Set.empty,
          Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing))
        )
      rig.start()
      rig.drive()
      val before = rig.seen()
      withClue("the code is asked for: ")(before should contain(SNAPSyncController.HealedCodeHashes(Seq(codeHash))))
      withClue("no completion, however clean the trie: ")(
        before should not contain SNAPSyncController.StateHealingComplete
      )
      rig.codes.put(codeHash, code).commit()
      rig.coordinator ! TrieNodeHealingCoordinator.HealedCodeStored(Seq(codeHash))
      rig.awaitComplete()
      rig.progress.hasExcluded shouldBe false
    }

    it should s"resolve an entry recorded before a re-peg against the new root, and not stall, on $scheme (T030)" taggedAs UnitTest in {
      val oldTrie = PathHealPresenceFixtures.storageTrieOld()
      val newTrie = PathHealPresenceFixtures.storageTrieNew()
      val rig = new Rig(scheme, oldTrie, absent = Set("sRoot", "sLeaf1"), excluded = Nil)
      rig.seed(Seq(newTrie.root)) // the new root's leaf is on disk too: it is the storage below it that is missing
      rig.start()
      rig.progress.recordExcluded(
        Seq(
          ExcludedAccount(
            PathHealPresenceFixtures.accountHash,
            ExcludedReason.StorageAbandoned,
            claimedStorageRoot = oldTrie("sRoot").hash
          )
        )
      )
      rig.serve(newTrie) // a peer at the new root: the old storage root is not served any more
      rig.peerAvailable()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(newTrie.root.hash)
      rig.awaitComplete()
      Seq("sRoot", "sLeaf0", "sLeaf1").foreach(l => withClue(s"$l on disk: ")(rig.onDisk(newTrie(l)) shouldBe true))
      rig.progress.hasExcluded shouldBe false
    }

    it should s"complete over an entry whose account the current root does not hold, and keep it dormant, on $scheme" taggedAs UnitTest in {
      val trie = PathHealPresenceFixtures.storageTrieOld()
      val gone = ByteString(Array.fill(32)(0x7a.toByte))
      val rig = new Rig(scheme, trie, absent = Set.empty, Seq(ExcludedAccount(gone, ExcludedReason.StorageSkipped)))
      rig.serve(trie)
      rig.peerAvailable()
      rig.start()
      rig.awaitComplete()
      rig.progress.hasExcluded shouldBe false
      withClue("a later root may hold it again (hunter W2): ") {
        rig.progress.excludedPage(None, 10).map(e => (e.subject, e.dormant)) shouldBe Seq((gone, true))
      }
    }

    // Spec 022 review W-1: on a live chain a re-peg comes every 14 to 26 minutes and heals the spine. Neither a re-peg nor
    // that heal is a blocker shrinking, so the count goes on through both.
    it should s"count idle minutes across a re-peg and a heal, and hand off, when only code blocks, on $scheme (W-1)" taggedAs UnitTest in {
      val rig = new Rig(
        scheme,
        fixture(withCode = true),
        absent = Set.empty,
        Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing))
      )
      rig.start()
      rig.drive()
      val half = TrieNodeHealingCoordinator.MaxBlockedTicks / 2
      def ticks(n: Int): Unit =
        (1 to n).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick)
        rig.drive()
      ticks(half)
      val next = fixture(withCode = true, acct1Nonce = 3L)
      rig.serve(next)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.peerAvailable()
      rig.awaitOnDisk(next("root"))
      rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
      ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - half)
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      rig.progress.hasExcludedCode shouldBe true
    }

    // Spec 022 round 2 review A (beacon W-1b): at each re-peg a dormant entry is seen over the absent new root, promoted,
    // then demoted once the root is written. That is no blocker shrinking.
    it should s"hand off a stuck code hash across three re-pegs with a dormant entry going up and down, on $scheme" taggedAs UnitTest in {
      val gone = ByteString(Array.fill(32)(0x7a.toByte))
      val rig = new Rig(
        scheme,
        fixture(withCode = true),
        absent = Set.empty,
        Seq(
          ExcludedAccount(codeHash, ExcludedReason.CodeMissing),
          ExcludedAccount(gone, ExcludedReason.StorageSkipped, dormant = true)
        )
      )
      rig.start()
      rig.drive()
      def ticks(n: Int): Unit =
        (1 to n).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick)
        rig.drive()
      val window = TrieNodeHealingCoordinator.MaxBlockedTicks / 3 - 1
      Seq(3L, 4L, 5L).foreach { nonce =>
        ticks(window)
        val next = fixture(withCode = true, acct1Nonce = nonce)
        rig.serve(next)
        rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
        rig.peerAvailable()
        rig.awaitOnDisk(next("root"))
        withClue("not before its 30 idle minutes: ") {
          rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
        }
      }
      ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - 3 * window)
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
      rig.progress.hasExcludedCode shouldBe true
    }

    it should s"count idle minutes across a re-peg and a heal, and abandon, when the set cannot be read, on $scheme (W-1)" taggedAs UnitTest in {
      val source = new ScanFailingDataSource
      val rig = new Rig(scheme, fixture(), absent = Set.empty, excluded = Nil, progressSource = source)
      source.failing = true
      rig.start()
      rig.drive()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.drive()
      val half = TrieNodeHealingCoordinator.MaxBlockedTicks / 2
      def ticks(n: Int): Unit =
        (1 to n).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick)
        rig.drive()
      ticks(half)
      val next = fixture(acct1Nonce = 3L)
      rig.serve(next)
      rig.coordinator ! TrieNodeHealingCoordinator.HealingPivotRefreshed(next.root.hash)
      rig.peerAvailable()
      rig.awaitOnDisk(next("root"))
      rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
      ticks(TrieNodeHealingCoordinator.MaxBlockedTicks - half)
      val end = rig.seen()
      end should contain(SNAPSyncController.StateHealingAbandoned)
      end should not contain SNAPSyncController.StateHealingComplete
    }

    it should s"not complete while the excluded set cannot be read, complete once it can, and hand off after 30 minutes of it, on $scheme (P3)" taggedAs UnitTest in {
      val source = new ScanFailingDataSource
      val rig = new Rig(scheme, fixture(), absent = Set.empty, excluded = Nil, progressSource = source)
      source.failing = true
      rig.start()
      rig.drive()
      rig.coordinator ! TrieNodeHealingCoordinator.HealingCheckCompletion
      rig.drive()
      withClue("an unreadable set keeps the gate shut: ")(
        rig.seen() should not contain SNAPSyncController.StateHealingComplete
      )
      (1 to TrieNodeHealingCoordinator.MaxBlockedTicks).foreach { _ =>
        rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick
      }
      rig.settle()
      val atFallback = rig.seen()
      withClue("the fallback is the abandonment, never a completion: ") {
        atFallback should contain(SNAPSyncController.StateHealingAbandoned)
        atFallback should not contain SNAPSyncController.StateHealingComplete
      }
      source.failing = false
      rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick // the tick tries again
      rig.awaitComplete()
    }

  "The excluded gate" should "keep the deletes of a heal start atomic per account: every position of the key in one batch" taggedAs UnitTest in {
    val trie = fixture()
    val source = new RecordingDataSource
    val pns = new PathNodeStorage(source)
    PathHealPresenceFixtures.seedPath(pns, trie.nodes)
    source.forget()
    val progress = new SnapSyncProgressStorage(new EphemDataSource(Map.empty))
    progress.recordExcluded(Seq(ExcludedAccount(a0, ExcludedReason.StorageAbandoned)))
    val controller = createTestProbe[SNAPSyncController.Command]()
    val coordinator = HealingTrieFixtures.spawnCoordinator(
      stateRoot = trie.root.hash,
      networkPeerManager = new ScriptedPeer(testKit).ref,
      requestTracker = new SNAPRequestTracker()(classicSystem.scheduler),
      mptStorage = new TestMptStorage(),
      batchSize = 16,
      snapSyncController = controller.ref,
      healingWriterEcOverride = Some(new ManualExecutionContext),
      storageScheme = StorageScheme.Path,
      pathNodeStorageOpt = Some(pns),
      movingRootDeltaHeal = true,
      excludedStore = Some(progress)
    )
    coordinator ! TrieNodeHealingCoordinator.StartTrieNodeHealing(trie.root.hash)
    val probe = createTestProbe[HealingStatistics]()
    coordinator ! TrieNodeHealingCoordinator.HealingGetProgress(probe.ref)
    probe.receiveMessage(30.seconds)
    val batches = source.batches.filter(_.exists(_.delete))
    batches should have size 1
    val keys = batches.head.map(_.key)
    keys should have size 65 // depth 0 to 64, the leaf slot included
    keys should contain(accountKey(Array.empty[Byte]))
    keys should contain(accountKey(nibbles(0)))
    keys should contain(
      accountKey(com.chipprbots.ethereum.blockchain.sync.snap.heal.ExcludedAccountSeeding.accountNibbles(a0))
    )
    keys should not contain accountKey(nibbles(1))
  }

  "The blocked tick" should "not count while a fetch is in flight: that is the stagnation paths' business" taggedAs UnitTest in {
    val rig = storageRig(StorageScheme.Hash, ExcludedReason.StorageAbandoned, staleRoot = false)
    rig.peerAvailable()
    rig.start() // the seed goes out, and nobody answers it
    withClue("the seed is in flight: ")(rig.peer.manual.receiveMessages(1, 30.seconds) should have size 1)
    (1 to 2 * TrieNodeHealingCoordinator.MaxBlockedTicks).foreach { _ =>
      rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick
    }
    rig.settle()
    rig.seen() should not contain SNAPSyncController.StateHealingAbandoned
  }

  // A walk can run for hours with the set's page reads on the actor's thread: a set of 200k entries is about 2M node reads.
  it should "not read the whole set again every minute of a walk once a pass has found nothing to do" taggedAs UnitTest in {
    val source = new ScanFailingDataSource
    val rig = new Rig(
      StorageScheme.Hash,
      fixture(withCode = true),
      absent = Set.empty,
      Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing)),
      progressSource = source
    )
    rig.start()
    rig.ec.runNext() // the resume: the rebuild walk is queued, and so running
    rig.settle()
    withClue("the walk is queued: ")(rig.ec.pending shouldBe 1)
    val afterAdoption = source.pageReads
    afterAdoption should be > 0
    (1 to 5).foreach(_ => rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick)
    rig.settle()
    source.pageReads shouldBe afterAdoption
  }

  // Spec 022 review W-1 (beacon W-1, forge F1/F2, hunter W1): a walk may take hours, and on Hash the controller's walks
  // nearly fill the window, so a walk does not stop the count. The gate acts when the walk ends.
  it should "count while a walk runs, and hand off when it ends" taggedAs UnitTest in {
    val rig = new Rig(
      StorageScheme.Hash,
      fixture(withCode = true),
      absent = Set.empty,
      Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing))
    )
    rig.start()
    rig.ec.runNext() // the resume: the rebuild walk is queued, and so running
    rig.settle()
    withClue("the walk is queued: ")(rig.ec.pending shouldBe 1)
    (1 to TrieNodeHealingCoordinator.MaxBlockedTicks).foreach { _ =>
      rig.coordinator ! TrieNodeHealingCoordinator.HealingBlockedTick
    }
    rig.settle()
    rig.drive()
    val end = rig.seen()
    end should contain(SNAPSyncController.StateHealingAbandoned)
    end should not contain SNAPSyncController.StateHealingComplete
  }

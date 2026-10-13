package com.chipprbots.ethereum.blockchain.sync.snap.actors.account

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.FiniteDuration

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnel
import com.google.common.hash.PrimitiveSink

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SnapIntakeBudget
import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
import com.chipprbots.ethereum.blockchain.sync.snap.StorageTask
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator
import com.chipprbots.ethereum.blockchain.sync.snap.actors.AccountRangeCoordinator.ReplayCarriedContracts
import com.chipprbots.ethereum.blockchain.sync.snap.controller.StateIngest
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.testing.TestMptStorage

/** Everything `ContractWorkCarry` needs, as a stub (spec 016 FR-016 (b), C-A4): its `ContractWorkCarryState`, the
  * coordinator's `AccountRangeEnv` and the `AccountIntakeGateApi` it reads. The task files are real temporary files;
  * the carried prefix is written into them before the append streams open, as the core's `copyPrefix` does. The context
  * is a `BehaviorTestKit`'s, so messages to `ctx.self` land in its self inbox and timers are recorded as effects.
  */
private[actors] class StubContractWorkCarryState(
    val ctx: ActorContext[AccountRangeCoordinator.Command],
    val timers: TimerScheduler[AccountRangeCoordinator.Command],
    val networkPeerManager: ActorRef[NetworkPeerManagerActor.Command],
    val requestTracker: SNAPRequestTracker,
    val snapSyncController: ActorRef[SNAPSyncController.Command],
    val storageScheme: StorageScheme,
    val mptStorage: MptStorage,
    val storageDone: Option[SnapStorageDoneStorage],
    carriedStorage: Seq[(ByteString, ByteString)],
    carriedCodeHashes: Seq[ByteString]
) extends ContractWorkCarryState
    with AccountRangeEnv
    with AccountIntakeGateApi:

  private def tempFile(prefix: String): Path =
    val p = Files.createTempFile(prefix, ".bin")
    p.toFile.deleteOnExit()
    p

  // ContractWorkCarryState
  val contractAccountsFile: Path = tempFile("cwc-contract-accounts-")
  val contractStorageFile: Path = tempFile("cwc-contract-storage-")
  val uniqueCodeHashesFile: Path = tempFile("cwc-unique-codehashes-")
  Files.write(contractStorageFile, carriedStorage.flatMap { case (a, r) => a ++ r }.toArray)
  Files.write(uniqueCodeHashesFile, carriedCodeHashes.flatten.toArray)
  val carriedStorageCount: Long = carriedStorage.size.toLong
  val carriedCodeHashesCount: Long = carriedCodeHashes.size.toLong
  val contractAccountsOut: BufferedOutputStream =
    new BufferedOutputStream(new FileOutputStream(contractAccountsFile.toFile), 65536)
  val contractStorageFos: FileOutputStream = new FileOutputStream(contractStorageFile.toFile, true)
  val contractStorageOut: BufferedOutputStream = new BufferedOutputStream(contractStorageFos, 65536)
  val uniqueCodeHashesFos: FileOutputStream = new FileOutputStream(uniqueCodeHashesFile.toFile, true)
  val uniqueCodeHashesOut: BufferedOutputStream = new BufferedOutputStream(uniqueCodeHashesFos, 65536)
  private object BytesFunnel extends Funnel[ByteString]:
    override def funnel(from: ByteString, into: PrimitiveSink): Unit =
      into.putBytes(from.toArray)
  val codeHashBloom: BloomFilter[ByteString] = BloomFilter.create[ByteString](BytesFunnel, 1000, 0.0001)
  var contractAccountsCount: Long = 0L
  var contractStorageCount: Long = carriedStorageCount
  var uniqueCodeHashesCount: Long = carriedCodeHashesCount

  // AccountRangeEnv (ctx, timers, networkPeerManager, requestTracker, snapSyncController, storageScheme, mptStorage and
  // storageDone are constructor vals)
  val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(classOf[StubContractWorkCarryState])
  val futureLog: org.slf4j.Logger = log
  val concurrency: Int = 1
  val initialResponseBytesConfig: Int = 524288
  val minResponseBytesConfig: Int = 102400
  val intakeBudget: Option[SnapIntakeBudget] = None
  val resumeProgress: Map[ByteString, ByteString] = Map.empty
  def stateIngest: StateIngest = throw new UnsupportedOperationException("ContractWorkCarry read stateIngest")

  // AccountIntakeGateApi
  var downstreamBackpressureActive: Boolean = false
  var intakeGateClosed: Boolean = false

/** Spec 016 C-A4 stub test (FR-016 (b), T065): `ContractWorkCarry` mixed into a stub of its state interface, the
  * coordinator environment and the intake-gate Api, inside a `BehaviorTestKit` so that the context is real. The cases
  * call the module's contract identification, carried replay (the #1487/#1498/#1501 completion-marker skip and the
  * Hash-scheme root-presence check), task-file sync and task-file reads, which read and write `ContractWorkCarryState`,
  * the env's `storageDone` and `mptStorage`, and real task files.
  */
class ContractWorkCarrySpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  /** The module on its stub, the kit that runs it, and the controller inbox it talks to. */
  final private class Harness(
      scheme: StorageScheme = StorageScheme.Hash,
      mpt: MptStorage = new TestMptStorage,
      storageDone: Option[SnapStorageDoneStorage] = None,
      carriedStorage: Seq[(ByteString, ByteString)] = Seq.empty,
      carriedCodeHashes: Seq[ByteString] = Seq.empty
  ):
    val controller: TestInbox[SNAPSyncController.Command] = TestInbox[SNAPSyncController.Command]()
    private val peerManager = TestInbox[NetworkPeerManagerActor.Command]()
    private val tracker = new SNAPRequestTracker()(system.classicSystem.scheduler)
    private var action: () => Unit = () => ()
    private var captured: StubContractWorkCarryState & ContractWorkCarry = null
    val kit: BehaviorTestKit[AccountRangeCoordinator.Command] =
      BehaviorTestKit(Behaviors.setup[AccountRangeCoordinator.Command] { ctx =>
        Behaviors.withTimers { timers =>
          captured = new StubContractWorkCarryState(
            ctx,
            timers,
            peerManager.ref,
            tracker,
            controller.ref,
            scheme,
            mpt,
            storageDone,
            carriedStorage,
            carriedCodeHashes
          ) with ContractWorkCarry
          Behaviors.receiveMessage { _ =>
            action()
            Behaviors.same
          }
        }
      })
    val module: StubContractWorkCarryState & ContractWorkCarry = captured

    /** Run `f` on the module while the kit processes a message, as the coordinator's arms do. */
    def call(f: StubContractWorkCarryState & ContractWorkCarry => Unit): Unit =
      action = () => f(module)
      kit.run(AccountRangeCoordinator.CheckCompletion)

    /** What the module sent to `ctx.self`. */
    def selfMessages(): Seq[AccountRangeCoordinator.Command] = kit.selfInbox().receiveAll()

    /** The timers the module scheduled since the last look. */
    def scheduledTimers(): Seq[(Any, Any, FiniteDuration)] =
      kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) }

  private def h32(b: Int): ByteString = ByteString(Array.fill[Byte](32)(b.toByte))

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  private def contract(code: ByteString, root: ByteString): Account =
    Account(storageRoot = TrieRoot(root), codeHash = CodeHash(code))

  "ContractWorkCarry" should "identify contract accounts into the task files and dispatch their work inline" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    val (code1, code2) = (h32(0xc1), h32(0xc2))
    val accounts = Seq(
      h32(1) -> Account(),
      h32(2) -> contract(code1, h32(0x21)),
      h32(3) -> contract(code1, emptyRoot),
      h32(4) -> contract(code2, h32(0x41))
    )

    h.call(_.identifyContractAccounts(accounts))

    m.contractAccountsCount shouldBe 3L
    m.contractStorageCount shouldBe 3L
    m.uniqueCodeHashesCount shouldBe 2L
    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.IncrementalContractData(
        Seq(code1, code2),
        Seq(StorageTask.createStorageTask(h32(2), h32(0x21)), StorageTask.createStorageTask(h32(4), h32(0x41)))
      )
    )
    m.readContractFile(m.contractStorageFile, m.contractStorageOut, m.contractStorageCount) shouldBe Seq(
      h32(2) -> h32(0x21),
      h32(3) -> emptyRoot,
      h32(4) -> h32(0x41)
    )
    m.readContractFile(m.contractAccountsFile, m.contractAccountsOut, m.contractAccountsCount) shouldBe Seq(
      h32(2) -> code1,
      h32(3) -> code1,
      h32(4) -> code2
    )
    m.readUniqueCodeHashes() shouldBe Seq(code1, code2)
  }

  it should "dispatch nothing for a response without contracts" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module

    h.call(_.identifyContractAccounts(Seq(h32(1) -> Account())))

    m.contractAccountsCount shouldBe 0L
    m.contractStorageCount shouldBe 0L
    h.controller.receiveAll() shouldBe empty
    m.readContractFile(m.contractStorageFile, m.contractStorageOut, 0L) shouldBe empty
    m.readUniqueCodeHashes() shouldBe empty
  }

  it should "replay the carried prefix, skipping marked tasks and, on the Hash scheme, present roots" taggedAs UnitTest in {
    val mpt = new TestMptStorage
    val leaf = LeafNode(ByteString(Array[Byte](1, 2)), ByteString(Array[Byte](3)))
    mpt.putNode(leaf)
    val presentRoot = ByteString(leaf.hash)
    val done = new SnapStorageDoneStorage(EphemDataSource())
    done.markDone(Seq(h32(1) -> h32(0x11))).commit()
    val h = new Harness(
      mpt = mpt,
      storageDone = Some(done),
      carriedStorage = Seq(h32(1) -> h32(0x11), h32(2) -> presentRoot, h32(3) -> h32(0x33), h32(4) -> emptyRoot),
      carriedCodeHashes = Seq(h32(0xc1), h32(0xc2))
    )
    val m = h.module
    m.replayDone shouldBe false

    h.call(_.replayCarriedChunk())

    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.IncrementalContractData(
        Seq(h32(0xc1), h32(0xc2)),
        Seq(StorageTask.createStorageTask(h32(3), h32(0x33))),
        replayed = true
      )
    )
    m.replayDone shouldBe true
    h.selfMessages() shouldBe Seq(AccountRangeCoordinator.CheckCompletion)
    h.scheduledTimers() shouldBe empty
  }

  it should "replay every carried task on the Path scheme without reading the trie store" taggedAs UnitTest in {
    val mpt = new TestMptStorage:
      override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
        throw new UnsupportedOperationException("the Path scheme read mptStorage")
    val h = new Harness(
      scheme = StorageScheme.Path,
      mpt = mpt,
      carriedStorage = Seq(h32(2) -> h32(0x22), h32(3) -> h32(0x33))
    )

    h.call(_.replayCarriedChunk())

    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.IncrementalContractData(
        Seq.empty,
        Seq(StorageTask.createStorageTask(h32(2), h32(0x22)), StorageTask.createStorageTask(h32(3), h32(0x33))),
        replayed = true
      )
    )
    h.module.replayDone shouldBe true
  }

  it should "hold the replay while the intake gate is closed, and resume when it opens" taggedAs UnitTest in {
    val h = new Harness(carriedStorage = Seq(h32(3) -> h32(0x33)))
    val m = h.module
    m.intakeGateClosed = true

    h.call(_.replayCarriedChunk())

    h.controller.receiveAll() shouldBe empty
    m.replayDone shouldBe false
    h.scheduledTimers() shouldBe Seq(
      (ReplayCarriedContracts, ReplayCarriedContracts, AccountRangeCoordinator.ReplayPausedRetry)
    )

    m.intakeGateClosed = false
    h.call(_.replayCarriedChunk())

    h.controller.receiveAll() shouldBe Seq(
      SNAPSyncController.IncrementalContractData(
        Seq.empty,
        Seq(StorageTask.createStorageTask(h32(3), h32(0x33))),
        replayed = true
      )
    )
    m.replayDone shouldBe true
  }

  it should "pace a carried prefix larger than one chunk" taggedAs UnitTest in {
    val entries = AccountRangeCoordinator.ReplayChunkEntries + 1
    val carried =
      (1 to entries).map(i => ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, i).array()) -> h32(0x55))
    val h = new Harness(carriedStorage = carried)
    val m = h.module

    h.call(_.replayCarriedChunk())

    val firstChunk = h.controller.receiveAll().collect { case d: SNAPSyncController.IncrementalContractData => d }
    firstChunk.map(_.storageTasks.size) shouldBe Seq(AccountRangeCoordinator.ReplayChunkEntries)
    m.replayDone shouldBe false
    h.scheduledTimers() shouldBe Seq(
      (ReplayCarriedContracts, ReplayCarriedContracts, AccountRangeCoordinator.ReplayChunkInterval)
    )
    h.selfMessages() shouldBe empty

    h.call(_.replayCarriedChunk())

    val lastChunk = h.controller.receiveAll().collect { case d: SNAPSyncController.IncrementalContractData => d }
    lastChunk.map(_.storageTasks.map(_.accountHash)) shouldBe Seq(Seq(carried.last._1))
    m.replayDone shouldBe true
    h.selfMessages() shouldBe Seq(AccountRangeCoordinator.CheckCompletion)
  }

  it should "make buffered task-file entries durable on syncTaskFiles" taggedAs UnitTest in {
    val h = new Harness
    val m = h.module
    m.contractStorageOut.write(Array.fill[Byte](64)(1))
    m.uniqueCodeHashesOut.write(Array.fill[Byte](32)(2))
    Files.size(m.contractStorageFile) shouldBe 0L
    Files.size(m.uniqueCodeHashesFile) shouldBe 0L

    m.syncTaskFiles()

    Files.size(m.contractStorageFile) shouldBe 64L
    Files.size(m.uniqueCodeHashesFile) shouldBe 32L
  }

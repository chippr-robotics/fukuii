package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe as TypedTestProbe
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.testing.Tags.*

/** SNAP storage-phase recovery when the persisted storage-task file is gone.
  *
  * A host reboot used to wipe the file from /tmp. Recovery then sent NoMoreStorageTasks at once and persisted
  * storage-complete without downloading any storage. Now it re-derives the tasks from the account trie (or, when the
  * trie is unreadable, restarts the accounts phase) and never marks storage complete on its own.
  */
class SNAPStorageRecoverySpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers with Eventually:

  private val Pivot = BigInt(5)

  "StorageTaskFile" should "treat a missing file or a partial entry as unusable" taggedAs UnitTest in {
    val dir = Files.createTempDirectory("snap-task-test")
    val whole = dir.resolve("whole.bin")
    Files.write(whole, new Array[Byte](128))
    val partial = dir.resolve("partial.bin")
    Files.write(partial, new Array[Byte](100))

    StorageTaskFile.isUsable(whole) shouldBe true
    StorageTaskFile.isUsable(partial) shouldBe false
    StorageTaskFile.isUsable(dir.resolve("absent.bin")) shouldBe false
  }

  it should "create task files under the given directory, not java.io.tmpdir" taggedAs UnitTest in {
    val dir = Files.createTempDirectory("snap-task-test").resolve("snap")
    val file = StorageTaskFile.createFile(Some(dir), "fukuii-contract-storage-", ".bin")
    file.getParent shouldBe dir
    Files.exists(file) shouldBe true
  }

  it should "re-derive exactly the accounts with a non-empty storage root" taggedAs UnitTest in new Fixture:
    val (root, contracts) = buildTrie()
    val out = Files.createTempFile("rederive", ".bin")
    val emitted = scala.collection.mutable.ArrayBuffer.empty[StorageTask]

    val count = StorageTaskFile.rederive(
      storagesInstance.storages.stateStorage.getBackingStorage(Pivot),
      root,
      out,
      emitted ++= _
    )

    count shouldBe contracts.size
    emitted.map(t => (t.accountHash, t.storageRoot)).toSet shouldBe contracts.toSet
    Files.size(out) shouldBe contracts.size * 64L
    StorageTaskFile.isUsable(out) shouldBe true

  "SNAPSyncController recovery" should "re-derive storage tasks, not mark storage complete, when the file is missing" taggedAs UnitTest in new Fixture:
    val (root, contracts) = buildTrie()
    val missing = taskDir.resolve("gone.bin")
    markAccountsComplete(root, Some(missing))

    spawnController() ! SNAPSyncController.Start

    // The re-derived file is written under the task dir and its path replaces the dead one.
    eventually(timeout(10.seconds)) {
      val path = appState.getSnapSyncStorageFilePath().getOrElse("")
      path should not be missing.toString
      Files.size(java.nio.file.Paths.get(path)) shouldBe contracts.size * 64L
      java.nio.file.Paths.get(path).getParent shouldBe taskDir
    }
    appState.isSnapSyncStorageComplete() shouldBe false
    appState.isSnapSyncAccountsComplete() shouldBe true

  it should "restart the accounts phase, not mark storage complete, when the file is missing and the trie is unreadable" taggedAs UnitTest in new Fixture:
    markAccountsComplete(ByteString(kec256("no such trie".getBytes)), Some(taskDir.resolve("gone.bin")))

    spawnController() ! SNAPSyncController.Start

    eventually(timeout(10.seconds))(appState.isSnapSyncAccountsComplete() shouldBe false)
    appState.isSnapSyncStorageComplete() shouldBe false

  it should "use a present task file unchanged" taggedAs UnitTest in new Fixture:
    val (root, _) = buildTrie()
    val present = taskDir.resolve("present.bin")
    Files.createDirectories(taskDir)
    Files.write(present, new Array[Byte](64)) // one all-zero entry; the reader skips it
    markAccountsComplete(root, Some(present))

    val snap = spawnController()
    snap ! SNAPSyncController.Start
    // The controller handles messages in order, so a reply to GetProgress means Start has been fully handled.
    val progress = testKit.createTestProbe[SyncProgress]()
    snap ! SNAPSyncController.GetProgress(progress.ref)
    progress.receiveMessage(10.seconds)

    // Recovery took the file branch: the path is untouched and no task file was created (re-deriving creates one
    // synchronously, before any async work).
    appState.isSnapSyncAccountsComplete() shouldBe true
    appState.getSnapSyncStorageFilePath() shouldBe Some(present.toString)
    Files.list(taskDir).iterator().asScala.toList shouldBe List(present)

  class Fixture extends EphemBlockchainTestSetup with TestSyncConfig:
    implicit override lazy val classicSystem: ActorSystem = SNAPStorageRecoverySpec.this.system.classicSystem

    val appState = storagesInstance.storages.appStateStorage
    val taskDir: Path = Files.createTempDirectory("snap-recovery-test").resolve("snap")
    private val networkPeerManager = TestProbe()
    val parent: TypedTestProbe[SyncProtocol.SyncControllerReply] =
      testKit.createTestProbe[SyncProtocol.SyncControllerReply]()

    /** A state trie with two contracts (storage root set), one EOA and one contract with an empty storage root. */
    def buildTrie(): (ByteString, Seq[(ByteString, ByteString)]) =
      val build = storagesInstance.storages.stateStorage.getBackingStorage(Pivot)
      def storageRoot(seed: Int): ByteString =
        ByteString(
          MerklePatriciaTrie[Array[Byte], Array[Byte]](build)
            .put(Array[Byte](seed.toByte, 1), Array[Byte](seed.toByte, 2))
            .getRootHash
        )
      def key(seed: Int) = ByteString(kec256(Array[Byte](seed.toByte)))
      val code = com.chipprbots.ethereum.domain.CodeHash(ByteString(kec256("code".getBytes)))
      val contracts = Seq(1, 2).map(i => (key(i), storageRoot(i)))
      val accounts =
        contracts.map { case (k, r) =>
          (k, Account(nonce = UInt256.Zero, storageRoot = TrieRoot(r), codeHash = code))
        } ++
          Seq(
            (key(3), Account.empty()),
            (key(4), Account(nonce = UInt256.Zero, storageRoot = Account.EmptyStorageRootHash, codeHash = code))
          )
      val trie = accounts.foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](build)) { case (t, (k, a)) =>
        t.put(k.toArray, a.toBytes)
      }
      (ByteString(trie.getRootHash), contracts)

    def markAccountsComplete(root: ByteString, filePath: Option[Path]): Unit =
      val updates = appState
        .putSnapSyncPivotBlock(Pivot)
        .and(appState.putSnapSyncStateRoot(root))
        .and(appState.putSnapSyncAccountsComplete(true))
        .and(appState.putSnapSyncBytecodeComplete(true))
      filePath.fold(updates)(p => updates.and(appState.putSnapSyncStorageFilePath(p.toString))).commit()

    def spawnController(): TypedActorRef[SNAPSyncController.Command] =
      given ExecutionContext = system.executionContext
      testKit.spawn(
        SNAPSyncController(
          blockchainReader,
          blockchainWriter,
          appState,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          storagesInstance.storages.flatSlotStorage,
          networkPeerManager.ref.toTyped[NetworkPeerManagerActor.Command],
          testKit.spawn(PeerEventBusActor.behavior()),
          syncConfig,
          SNAPSyncConfig(taskFileDir = Some(taskDir)),
          system.classicSystem.scheduler,
          CacheBasedBlacklist.empty(100),
          parent.ref
        )
      )

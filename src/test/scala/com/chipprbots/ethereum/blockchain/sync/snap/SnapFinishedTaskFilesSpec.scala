package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** B-523: the contract task files are deleted, and their AppState paths cleared, once `SnapSyncDone` is durable. */
class SnapFinishedTaskFilesSpec extends AnyFlatSpec with Matchers:

  private class Fixture:
    val appState = new AppStateStorage(EphemDataSource())
    val dir: Path = Files.createTempDirectory("snap-b523-")
    val storage: Path = Files.createFile(dir.resolve("fukuii-contract-storage-1.bin"))
    val code: Path = Files.createFile(dir.resolve("fukuii-unique-codehashes-1.bin"))
    val accounts: Path = Files.createFile(dir.resolve("fukuii-contract-accounts-1.bin"))
    val unrelated: Path = Files.createFile(dir.resolve("other.bin"))
    appState
      .putSnapSyncStorageFilePath(storage.toString)
      .and(appState.putSnapSyncCodeHashesPath(code.toString))
      .and(appState.putSnapSyncStorageFileCount(Some(7L)))
      .and(appState.putSnapSyncCodeHashesCount(Some(3L)))
      .commit()
    val errors: mutable.Buffer[Path] = mutable.Buffer.empty
    def discard(): Boolean = SnapTaskFileCleanup.discardFinishedTaskFiles(appState, Some(dir), (p, _) => errors += p)
    def pathsClear: Boolean =
      appState.getSnapSyncStorageFilePath().forall(_.isEmpty) &&
        appState.getSnapSyncCodeHashesPath().forall(_.isEmpty) &&
        appState.getSnapSyncStorageFileCount().isEmpty && appState.getSnapSyncCodeHashesCount().isEmpty

  "discardFinishedTaskFiles" should "do nothing while SnapSyncDone is not set: a crash there still resumes from the files" taggedAs UnitTest in {
    val f = new Fixture
    f.discard() shouldBe false
    Files.exists(f.storage) shouldBe true
    Files.exists(f.code) shouldBe true
    f.appState.getSnapSyncStorageFilePath() shouldBe Some(f.storage.toString)
    f.appState.getSnapSyncStorageFileCount() shouldBe Some(7L)
  }

  it should "delete the task files (and a crash-leftover contract-accounts file) and clear the paths once done" taggedAs UnitTest in {
    val f = new Fixture
    f.appState.snapSyncDone().commitSync()
    f.discard() shouldBe true
    Files.exists(f.storage) shouldBe false
    Files.exists(f.code) shouldBe false
    Files.exists(f.accounts) shouldBe false
    Files.exists(f.unrelated) shouldBe true
    f.pathsClear shouldBe true
    f.appState.isSnapSyncDone() shouldBe true
  }

  it should "finish the job after a kill between the deletion and the clear (paths name missing files)" taggedAs UnitTest in {
    val f = new Fixture
    f.appState.snapSyncDone().commitSync()
    Files.delete(f.storage)
    Files.delete(f.code)
    f.pathsClear shouldBe false
    f.discard() shouldBe true
    f.pathsClear shouldBe true
  }

  it should "keep the paths when a deletion fails, so the next start can retry" taggedAs UnitTest in {
    val f = new Fixture
    f.appState.snapSyncDone().commitSync()
    // A non-empty directory at the recorded path cannot be deleted: deleteIfExists throws DirectoryNotEmptyException.
    Files.delete(f.storage)
    Files.createDirectory(f.storage)
    Files.createFile(f.storage.resolve("x"))
    f.discard() shouldBe false
    f.errors should contain(f.storage)
    f.appState.getSnapSyncStorageFilePath() shouldBe Some(f.storage.toString)
    Files.delete(f.storage.resolve("x"))
    f.discard() shouldBe true
    f.pathsClear shouldBe true
  }

  it should "be idempotent" taggedAs UnitTest in {
    val f = new Fixture
    f.appState.snapSyncDone().commitSync()
    f.discard() shouldBe true
    f.discard() shouldBe true
    f.pathsClear shouldBe true
  }

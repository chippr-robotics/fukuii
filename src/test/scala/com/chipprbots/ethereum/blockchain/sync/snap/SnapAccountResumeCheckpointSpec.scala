package com.chipprbots.ethereum.blockchain.sync.snap

import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.SnapStorageDoneStorage
import com.chipprbots.ethereum.testing.Tags.*

/** #1597: how the controller reads the account resume checkpoint at the start of the account phase.
  *
  *   - A record missing only ranges that were already complete (the shape builds before the fix wrote after a range
  *     finished and the node restarted) is completed to the full layout and resumed, with its task files.
  *   - A record whose ranges are another concurrency's layout is ignored for the run but left in place, with its task
  *     files and the storage-task completion markers, and the run does not overwrite it.
  */
class SnapAccountResumeCheckpointSpec
    extends ScalaTestWithActorTestKit(SnapControllerFixture.config)
    with AnyFlatSpecLike
    with Matchers:

  private val Height = 1_000
  private val ExpectedPivot: BigInt = BigInt(Height) - SNAPSyncConfig().pivotBlockOffset

  /** Task files named like a coordinator's (so the superseded-file sweep would recognise them), holding `n` entries. */
  private def taskFiles(dir: Path, n: Int): ContractTaskFiles =
    val storage = StorageTaskFile.createFile(Some(dir), "fukuii-contract-storage-", ".bin")
    val code = StorageTaskFile.createFile(Some(dir), "fukuii-unique-codehashes-", ".bin")
    Files.write(storage, (0 until n).flatMap(i => (kec256(ByteString(s"a-$i")) ++ kec256(ByteString(s"r-$i")))).toArray)
    Files.write(code, (0 until n).flatMap(i => kec256(ByteString(s"c-$i"))).toArray)
    ContractTaskFiles(storage.toString, n.toLong, code.toString, n.toLong)

  private def seedCheckpoint(f: SnapControllerFixture, cursors: Map[ByteString, ByteString], files: ContractTaskFiles) =
    val json = AccountResumeCheckpoint.encode(
      AccountResumeCheckpoint(SnapControllerFixture.root(0x22), ExpectedPivot, cursors, files)
    )
    f.appStateStorage.putSnapAccountResumeCheckpoint(json).commit()
    json

  "The account resume checkpoint reader (#1597)" should "complete a 15-of-16 record to the full layout and resume from it with its task files" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val dir = Files.createTempDirectory("snap-1597-fill-")
    val files = taskFiles(dir, 3)
    val layout = AccountTask.createInitialTasks(ByteString.empty, 16)
    val missing = layout(4)
    val fifteen = layout.map(t => t.last -> t.next).toMap - missing.last
    seedCheckpoint(f, fifteen, files)

    val entry = f.enterAccountSync(SNAPSyncConfig(taskFileDir = Some(dir)), height = Height)

    entry.account.concurrency shouldBe 16
    entry.account.resumeProgress.size shouldBe 16
    entry.account.resumeProgress(missing.last) shouldBe missing.last // filled as complete
    entry.account.resumeProgress - missing.last shouldBe fifteen
    entry.account.carriedTaskFiles shouldBe Some(files)
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe defined
  }

  it should "ignore an 8-range record at concurrency 16 but keep the record, its task files and the completion markers" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val dir = Files.createTempDirectory("snap-1597-held-")
    val files = taskFiles(dir, 3)
    val layout8 = AccountTask.createInitialTasks(ByteString.empty, 8).map(t => t.last -> t.next).toMap
    val json = seedCheckpoint(f, layout8, files)
    val markers = new SnapStorageDoneStorage(f.storagesInstance.storages.flatSlotStorage.dataSource)
    val marker = kec256(ByteString("a-0")) -> kec256(ByteString("r-0"))
    markers.markDone(Seq(marker)).commit()

    val entry = f.enterAccountSync(SNAPSyncConfig(taskFileDir = Some(dir)), height = Height)

    // Not resumed: a fresh account phase at the configured layout, nothing carried.
    entry.account.concurrency shouldBe 16
    entry.account.resumeProgress shouldBe empty
    entry.account.carriedTaskFiles shouldBe None
    // Left in place for a run at the matching layout: the record, the markers and the task files.
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)
    markers.isDone(marker._1, marker._2) shouldBe true

    // The run's own durable checkpoint does not overwrite the record (fixed key), nor sweep the record's files.
    val ownFiles = taskFiles(dir, 1)
    entry.snap ! SNAPSyncController.AccountRangeProgressCmd(
      AccountTask.createInitialTasks(ByteString.empty, 16).map(t => t.last -> t.next).toMap,
      taskFiles = Some(ownFiles),
      durable = true,
      generation = entry.account.progressGeneration
    )
    f.awaitProcessed(entry.snap)
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe Some(json)
    Files.exists(Path.of(files.storagePath)) shouldBe true
    Files.exists(Path.of(files.codeHashesPath)) shouldBe true
    markers.isDone(marker._1, marker._2) shouldBe true
  }

  it should "still drop a record that is unusable for other reasons (a missing task file)" taggedAs UnitTest in {
    val f = new SnapControllerFixture(testKit)
    val dir = Files.createTempDirectory("snap-1597-drop-")
    val files = taskFiles(dir, 3)
    val full = AccountTask.createInitialTasks(ByteString.empty, 16).map(t => t.last -> t.next).toMap
    seedCheckpoint(f, full, files)
    Files.delete(Path.of(files.codeHashesPath))

    val entry = f.enterAccountSync(SNAPSyncConfig(taskFileDir = Some(dir)), height = Height)

    entry.account.carriedTaskFiles shouldBe None
    f.appStateStorage.getSnapAccountResumeCheckpoint() shouldBe None
  }

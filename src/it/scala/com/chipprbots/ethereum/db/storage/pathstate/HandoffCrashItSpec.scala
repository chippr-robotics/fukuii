package com.chipprbots.ethereum.db.storage.pathstate

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathHandoffSample
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The hand-off of a sync under `kill -9`, on a real RocksDB in a child JVM (spec 018 C5a part 5; T195, data-model
  * 7.4): on either side of the one synced write, what the datadir holds when it is opened again.
  *
  * Before it: no anchor and no sync that says it is done, so the sync resumes. After it: the rows, the anchor, the
  * block the state began at, the sync done and the best block, together, and the state is read from its root.
  *
  * A kill is not a loss of power: the writes the child made reached the operating system. This pins that the anchor,
  * the flag and the pointer are one write and that the rows are there with them; that a synced write is on the disk,
  * and every write before it with it, is RocksDB's, and is not tested here.
  */
class HandoffCrashItSpec extends AnyFlatSpec with Matchers:
  import HandoffCrashChild.*

  Seq("before-commit", "after-commit").foreach { point =>
    "The hand-off of a sync under kill -9" should s"leave all of a state or none of it: $point" taggedAs (
      IntegrationTest,
      DatabaseTest,
      SlowTest
    ) in {
      val dir = Files.createTempDirectory(s"handoff-crash-$point")
      val (signal, output, exit) = runAndKill(dir, point)
      withClue(s"child output:\n${output.mkString("\n")}\n") {
        signal shouldBe s"KP:$point"
        exit shouldBe 137
      }
      val source = RocksDbDataSource(rocksConfig(dir.toAbsolutePath.toString), Namespaces.nsSeq)
      try
        val appState = new AppStateStorage(source)
        val paths = new PathNodeStorage(source)
        val state = new PathStateStorage(paths, appState)
        val hashed = synced()
        val pivot = pivotOf(hashed)
        point match
          case "before-commit" =>
            appState.getStateAnchor() shouldBe None
            state.anchor shouldBe None
            appState.getStateOrigin() shouldBe None
            appState.isSnapSyncDone() shouldBe false
            appState.getBestBlockInfo() shouldBe BlockInfo(org.apache.pekko.util.ByteString.empty, 0)
            // the sync goes on from where it was, and hands off again
            state.commitHandoff(pivot, Some(appState.putBestBlockInfo(BlockInfo(pivot.hash, pivot.number))))
          case _ => ()
        appState.getStateAnchor() shouldBe Some(pivot)
        state.anchor shouldBe Some(pivot)
        appState.getStateOrigin() shouldBe Some(pivot)
        appState.isSnapSyncDone() shouldBe true
        appState.getBestBlockInfo() shouldBe BlockInfo(pivot.hash, pivot.number)
        appState.getStateLayout() shouldBe Some(StateLayout.PathLive1)
        // the rows are there with the anchor: every sampled leaf is found from the root, and a view of the state reads
        val sampled = PathHandoffSample.run(paths, pivot.stateRoot, random = new scala.util.Random(1))
        sampled.sampledAccountLeaves should be > 0
        sampled.sampledAccountMisses shouldBe 0
        sampled.sampledStorageRoots should be > 0
        sampled.sampledStorageRootsMissing shouldBe 0
        val world = PathStateTestKit.worldOver(state.readOnly(pivot), pivot.stateRoot, hashed.codes)
        world.getAccount(PathStateTestKit.dave) shouldBe defined
      finally source.close()
    }
  }

  /** Start the child, wait for its kill-point line, SIGKILL it. Returns the line, all output, and the exit status. */
  private def runAndKill(dir: Path, point: String): (String, Seq[String], Int) =
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command = Seq(
      java,
      "-Xmx512m",
      "-XX:+IgnoreUnrecognizedVMOptions",
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:-ReduceAllocationMerges",
      "-cp",
      System.getProperty("java.class.path"),
      "com.chipprbots.ethereum.db.storage.pathstate.HandoffCrashChild",
      dir.toAbsolutePath.toString,
      point
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val lines = new LinkedBlockingQueue[String]()
    val EndOfOutput = "\u0000EOF"
    val reader = new Thread(() =>
      val in = new BufferedReader(new InputStreamReader(process.getInputStream))
      // the kill closes the stream under the reader: that is the end of the output, not an error
      try Iterator.continually(in.readLine()).takeWhile(_ != null).foreach(lines.put)
      catch case _: IOException => ()
      lines.put(EndOfOutput)
    )
    reader.setDaemon(true)
    reader.start()

    val output = mutable.ArrayBuffer.empty[String]
    var signal: Option[String] = None
    while signal.isEmpty do
      // a bound on a child that hangs, not a wait for anything to happen: the signal ends the wait
      Option(lines.poll(120, TimeUnit.SECONDS)) match
        case None              => signal = Some("TIMEOUT")
        case Some(EndOfOutput) => signal = Some("EXITED")
        case Some(line) =>
          output += line
          if line.startsWith("KP:") || line.startsWith("ERR:") then signal = Some(line)
    process.destroyForcibly()
    process.waitFor()
    reader.join(TimeUnit.SECONDS.toMillis(10))
    lines.forEach(l => if l != EndOfOutput then output += l)
    (signal.get, output.toSeq, process.exitValue())

  private def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

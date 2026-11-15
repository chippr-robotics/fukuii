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

import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The clear of the state for a sync from scratch under `kill -9`, on a real RocksDB in a child JVM (spec 018 C5a part
  * 3; T165): at each point of it, what the datadir holds when it is opened again, and that a start finishes the clear
  * and leaves a datadir a sync starts on as on a new one.
  *
  * A kill is not a loss of power: the writes the child made reached the operating system. This pins the order of the
  * clear's writes and what each leaves behind; that a synced write is on the disk is RocksDB's, and is not tested here.
  */
class StateClearCrashItSpec extends AnyFlatSpec with Matchers:
  import StateClearCrashChild.*

  private def rowsOf(source: RocksDbDataSource): Map[String, Int] =
    val above = Array.fill[Byte](PathStateStorage.KeyBoundBytes)(0xff.toByte)
    Families.map((name, family) => name -> source.scanRange(family, Array.emptyByteArray, above).size).toMap

  private val NoRows = Map("v" -> 0, "t" -> 0, "u" -> 0, "p" -> 0)

  Seq("before-batch", "after-batch", "mid-ranges", "before-mark", "after-mark").foreach { point =>
    "A clear of the state under kill -9" should s"leave what each step says, and be finished by a start: $point" taggedAs (
      IntegrationTest,
      DatabaseTest,
      SlowTest
    ) in {
      val dir = Files.createTempDirectory(s"state-clear-crash-$point")
      val (signal, output, exit) = runAndKill(dir, point)
      withClue(s"child output:\n${output.mkString("\n")}\n") {
        signal shouldBe s"KP:$point"
        exit shouldBe 137
      }
      val source = RocksDbDataSource(rocksConfig(dir.toAbsolutePath.toString), Namespaces.nsSeq)
      try
        val appState = new AppStateStorage(source)
        val progress = new SnapSyncProgressStorage(source)
        val state = new PathStateStorage(new PathNodeStorage(source), appState)
        point match
          case "before-batch" =>
            // nothing has changed: the state is the node's, and a start finishes nothing
            appState.getStateAnchor() shouldBe Some(Held)
            appState.isSnapSyncDone() shouldBe true
            appState.isStateResetPending() shouldBe false
            appState.getStateEscalationLast() shouldBe None
            rowsOf(source).values.foreach(_ shouldBe 20)
            state.finishPendingClear(Some(GenesisHash)) shouldBe false
            rowsOf(source).values.foreach(_ shouldBe 20)
            state.anchor shouldBe Some(Held)
          case "after-mark" =>
            appState.isStateResetPending() shouldBe false
            rowsOf(source) shouldBe NoRows
            state.finishPendingClear(Some(GenesisHash)) shouldBe false
          case _ =>
            // in between: no state, the mark, and the record of why; never a sync that says it is done
            appState.getStateAnchor() shouldBe None
            state.anchor shouldBe None
            appState.isSnapSyncDone() shouldBe false
            appState.isSnapSyncAccountsComplete() shouldBe false
            appState.isStateResetPending() shouldBe true
            appState.getStateEscalationLast() shouldBe Some(Escalation)
            val left = rowsOf(source)
            point match
              case "after-batch" => left.values.foreach(_ shouldBe 20)
              case "mid-ranges"  => left shouldBe Map("v" -> 0, "t" -> 0, "u" -> 0, "p" -> 20)
              case _             => left shouldBe NoRows
            // the start finishes it, before anything reads the state
            state.finishPendingClear(Some(GenesisHash)) shouldBe true
        if point != "before-batch" then
          // a datadir a sync starts on as on a new one, still the unified layout's
          rowsOf(source) shouldBe NoRows
          progress.hasAnyExcluded shouldBe false
          appState.isStateResetPending() shouldBe false
          appState.getStateAnchor() shouldBe None
          appState.getSnapSyncPivotBlock() shouldBe None
          appState.getBestBlockInfo() shouldBe BlockInfo(GenesisHash, 0)
          appState.getStateLayout() shouldBe Some(StateLayout.PathLive1)
          appState.getStateEscalationLast() shouldBe Some(Escalation)
          HealInvariant.isEmpty(appState, Some(new PathNodeStorage(source))) shouldBe true
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
      "com.chipprbots.ethereum.db.storage.pathstate.StateClearCrashChild",
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

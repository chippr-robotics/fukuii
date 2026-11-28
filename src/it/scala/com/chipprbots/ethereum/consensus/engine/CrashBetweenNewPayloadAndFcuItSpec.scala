package com.chipprbots.ethereum.consensus.engine

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

import com.chipprbots.ethereum.jsonrpc.conformance.RpcCompatNode
import com.chipprbots.ethereum.testing.Tags.*

/** A node killed between `newPayload(B)` and `forkchoiceUpdated(B)`, on a real RocksDB datadir in a child JVM (spec 018
  * T156, B-255; data-model 7.5, C4d part 3).
  *
  * The child is the node's Engine API service with its real execution and state recovery on the unified layout
  * ([[CrashBetweenNewPayloadAndFcuChild]]); it is driven by calls to the service, which is what the Engine API's HTTP
  * layer calls, and killed with SIGKILL (`Process.destroyForcibly`): no shutdown hook, no close of the datadir. A fresh
  * child on the same datadir has no peers.
  *
  * What the design says (the layer of B is held in memory beside the disk, so the kill takes it): the restarted node
  * holds the state of the block before B and the stored blocks of B. A fork choice naming B is answered SYNCING and B
  * is executed again; the consensus client asks again and is answered VALID, and the head moves to B. A payload on B
  * comes first for a client that sends the next one: ACCEPTED (neither VALID nor INVALID), the same re-execution, then
  * the fork choice VALID. No resync is needed on either path.
  *
  * What this does not do: a real consensus client (EG-14, a live gate with a pinned client on a testnet). A kill is not
  * a loss of power: the writes the child made reached the operating system.
  */
class CrashBetweenNewPayloadAndFcuItSpec extends AnyFlatSpec with Matchers:
  import CrashBetweenNewPayloadAndFcuItSpec.*

  // blocks(39) is block 40: the first after the Shanghai fork at 39, a payload a consensus client sends
  private val B = 39

  "A node killed between newPayload(B) and forkchoiceUpdated(B)" should "answer SYNCING to the fork choice, execute B again, answer VALID, and hold B as its head" taggedAs (
    IntegrationTest,
    DatabaseTest,
    SlowTest
  ) in {
    val dir = Files.createTempDirectory("crash-newpayload-fcu")
    val first = killed(dir, "none", s"seed:$B,reconcile,np:$B,park", "park")
    first.facts("np:" + B) shouldBe PayloadValid

    val second = completes(dir, "none", s"reconcile,facts:${B - 1},fcu:$B,recovered,fcu:$B,facts:$B")
    second.facts("reconcile") shouldBe "Consistent"
    // the datadir is as the import of block 39 left it: the payload's layer went with the child
    holds(second, B - 1)
    second.all(s"fcu:$B") shouldBe Seq(PayloadSyncing, PayloadValid)
    second.facts("recovered") shouldBe "[Recovered(1)]"
    holds(second, B, at = 1)

    // the child exited with no close of the datadir, as a kill leaves it: it opens as it was
    val third = completes(dir, "none", s"reconcile,facts:$B,fcu:$B,facts:$B")
    third.facts("reconcile") shouldBe "Consistent"
    third.facts(s"fcu:$B") shouldBe PayloadValid
    holds(third, B, at = 0)
    holds(third, B, at = 1)
  }

  it should "answer ACCEPTED to the next payload, execute B again, and answer VALID to its fork choice" taggedAs (
    IntegrationTest,
    DatabaseTest,
    SlowTest
  ) in {
    val dir = Files.createTempDirectory("crash-newpayload-next")
    killed(dir, "none", s"seed:$B,reconcile,np:$B,park", "park").facts("np:" + B) shouldBe PayloadValid

    val restarted =
      completes(dir, "none", s"reconcile,facts:${B - 1},np:${B + 1},recovered,fcu:${B + 1},facts:${B + 1}")
    restarted.facts("reconcile") shouldBe "Consistent"
    holds(restarted, B - 1)
    // neither VALID nor INVALID: its parent's state is not held, and the stored blocks are executed again
    restarted.facts(s"np:${B + 1}") shouldBe PayloadAccepted
    restarted.facts("recovered") shouldBe "[Recovered(2)]"
    restarted.facts(s"fcu:${B + 1}") shouldBe PayloadValid
    holds(restarted, B + 1, at = 1)
  }

  Seq("before-move", "after-move").foreach { point =>
    it should s"leave the head and the state together when killed again $point, the write that moves the head" taggedAs (
      IntegrationTest,
      DatabaseTest,
      SlowTest
    ) in {
      val dir = Files.createTempDirectory(s"crash-fcu-$point")
      killed(dir, "none", s"seed:$B,reconcile,np:$B,park", "park").facts("np:" + B) shouldBe PayloadValid
      // the restarted node is killed on either side of the head move the fork choice makes
      val moving = killed(dir, point, s"reconcile,fcu:$B,recovered,fcu:$B", point)
      moving.facts(s"fcu:$B") shouldBe PayloadSyncing
      moving.facts("recovered") shouldBe "[Recovered(1)]"

      val before = point == "before-move"
      val after =
        if before then completes(dir, "none", s"reconcile,facts:${B - 1},fcu:$B,recovered,fcu:$B,facts:$B")
        else completes(dir, "none", s"reconcile,facts:$B,fcu:$B,facts:$B")
      after.facts("reconcile") shouldBe "Consistent"
      // all of the move or none of it: the state, the anchor, the best block and the index agree
      holds(after, if before then B - 1 else B, at = 0)
      // the consensus client's fork choice reaches VALID with no resync, and the head is B
      after.all(s"fcu:$B") shouldBe (if before then Seq(PayloadSyncing, PayloadValid) else Seq(PayloadValid))
      holds(after, B, at = 1)
    }
  }

  /** What the datadir holds, `facts` for `blocks(i)` as the head: its anchor, best block, index and positions. */
  private def holds(child: Child, i: Int, at: Int = 0): Unit =
    val block = RpcCompatNode.blocks(i).header
    val hash = hex(block.hash.value)
    val facts = child.factSets(at)
    withClue(s"block ${block.number.value}, child output:\n${child.output.mkString("\n")}\n") {
      facts("anchor") shouldBe hash
      facts("anchorStored") shouldBe hash
      facts("best") shouldBe block.number.value.toString
      facts("bestHash") shouldBe hash
      facts("index") shouldBe "ok"
      facts("fault") shouldBe "none"
      facts("disk") shouldBe "ok"
    }

  /** A child run until the line `KP:<point>`, then killed with SIGKILL. */
  private def killed(dir: Path, kill: String, script: String, point: String): Child =
    val ran = run(dir, kill, script)
    withClue(s"child output:\n${ran.output.mkString("\n")}\n") {
      ran.signal shouldBe s"KP:$point"
      ran.exit shouldBe 137
    }
    ran

  /** A child run to the end of its script. */
  private def completes(dir: Path, kill: String, script: String): Child =
    val ran = run(dir, kill, script)
    withClue(s"child output:\n${ran.output.mkString("\n")}\n") {
      ran.signal shouldBe "DONE"
      ran.exit shouldBe 0
    }
    ran

  private def run(dir: Path, kill: String, script: String): Child =
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command = Seq(
      java,
      "-Xmx1g",
      "-XX:+IgnoreUnrecognizedVMOptions",
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:-ReduceAllocationMerges",
      "-cp",
      System.getProperty("java.class.path"),
      "com.chipprbots.ethereum.consensus.engine.CrashBetweenNewPayloadAndFcuChild",
      dir.toAbsolutePath.toString,
      kill,
      script
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
      Option(lines.poll(300, TimeUnit.SECONDS)) match
        case None              => signal = Some("TIMEOUT")
        case Some(EndOfOutput) => signal = Some("EXITED")
        case Some(line) =>
          output += line
          if line.startsWith("KP:") || line == "DONE" || line.startsWith("ERR:") then signal = Some(line)
    // a child that is done exits on its own; every other is killed, with no hook and no close of the datadir
    if signal.contains("DONE") then
      if !process.waitFor(60, TimeUnit.SECONDS) then process.destroyForcibly()
    else process.destroyForcibly()
    process.waitFor()
    reader.join(TimeUnit.SECONDS.toMillis(10))
    lines.forEach(l => if l != EndOfOutput then output += l)
    Child(signal.get, output.toSeq, process.exitValue())

object CrashBetweenNewPayloadAndFcuItSpec:
  val PayloadValid: String = PayloadStatus.Valid.value
  val PayloadSyncing: String = PayloadStatus.Syncing.value
  val PayloadAccepted: String = PayloadStatus.Accepted.value

  def hex(b: org.apache.pekko.util.ByteString): String = b.map(x => f"$x%02x").mkString

  /** What a child said: the signal that ended the wait, every line, and its exit status. */
  final case class Child(signal: String, output: Seq[String], exit: Int):
    /** The answer of the step with this name (the last, if it ran twice): `R:<step>=<answer>`. */
    def facts(step: String): String =
      all(step).lastOption.getOrElse(fail(s"no answer for $step in:\n${output.mkString("\n")}"))

    /** Every answer of the step, in order. */
    def all(step: String): Seq[String] =
      val prefix = s"R:$step="
      output.filter(_.startsWith(prefix)).map(_.stripPrefix(prefix))

    /** The `F:` lines of the n-th `facts` step, as a map. */
    def factSets(n: Int): Map[String, String] =
      val groups = output.filter(_.startsWith("F:")).grouped(7).toSeq
      groups(n).map(_.stripPrefix("F:").split("=", 2)).map(a => a(0) -> a(1)).toMap

  private def fail(message: String): Nothing = throw new AssertionError(message)

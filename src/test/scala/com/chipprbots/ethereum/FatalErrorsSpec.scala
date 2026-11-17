package com.chipprbots.ethereum

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

import scala.collection.mutable.ListBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.NativeBackends.Backend
import com.chipprbots.ethereum.vm.NativeBackends.NativeBackendUnavailable

/** B-135 follow-up: a precompile backend found missing at call time halts the node, on every thread and executor.
  *
  * The decision cases run [[FatalErrors.decide]] and [[FatalErrors.handle]] as plain functions. The process cases start
  * a child JVM ([[com.chipprbots.ethereum.vm.NativeBackendFatalProbe]]) that raises the error inside `IO` on
  * `IORuntime.global`, as block import does, and check how that JVM ends: Cats Effect's fatal-error handling has to be
  * observed, and a fatal error there would shut down the runtimes this test JVM shares with every other suite.
  */
class FatalErrorsSpec extends AnyFlatSpec with Matchers:

  private def unavailable(cause: Option[Throwable] = None) =
    NativeBackendUnavailable(
      Backend.Bls12381,
      "the native library did not load (LibEthPairings.ENABLED is false)",
      cause
    )

  // ── The decision ───────────────────────────────────────────────────────────────────────────────────────────────────

  "An error with a NativeBackendUnavailable in its cause chain" should "halt, naming the library and the thread" taggedAs (
    UnitTest
  ) in {
    val direct = FatalErrors.decide("io-compute-3", unavailable(Some(new UnsatisfiedLinkError("eth_pairings"))))
    direct shouldBe a[FatalErrors.Action.Halt]
    val message = direct.asInstanceOf[FatalErrors.Action.Halt].message
    message should include("bls12-381")
    message should include("io-compute-3")
    message should include("LibEthPairings.ENABLED is false")
    message should include("eth_pairings")

    val wrapped = new ExecutionException(new RuntimeException("import failed", unavailable()))
    FatalErrors.decide("pekko-dispatcher-7", wrapped) shouldBe a[FatalErrors.Action.Halt]
  }

  "Another VirtualMachineError" should "be logged and reported, not halt" taggedAs UnitTest in {
    FatalErrors.decide("main", new OutOfMemoryError("Java heap space")) shouldBe a[FatalErrors.Action.Report]
  }

  "Any other error" should "be reported as the JVM reports it" taggedAs UnitTest in {
    FatalErrors.decide("main", new IllegalStateException("boom")) shouldBe FatalErrors.Action.Default
  }

  "A cause chain with a cycle" should "end the search" taggedAs UnitTest in {
    val first = new RuntimeException("first")
    val second = new RuntimeException("second", first)
    first.initCause(second)
    FatalErrors.decide("main", first) shouldBe FatalErrors.Action.Default
  }

  "Handling an error" should "halt with status 1 only for a missing backend" taggedAs UnitTest in {
    val halts = ListBuffer.empty[Int]
    val reports = ListBuffer.empty[Throwable]
    def handle(error: Throwable): Unit =
      FatalErrors.handle(Thread.currentThread(), error, halts += _, (_, e) => reports += e)

    handle(unavailable())
    halts.toList shouldBe List(1)
    reports shouldBe empty

    val oom = new OutOfMemoryError("Java heap space")
    val other = new IllegalStateException("boom")
    handle(oom)
    handle(other)
    halts.toList shouldBe List(1)
    reports.toList shouldBe List(oom, other)
  }

  // ── In a child JVM, on the Cats Effect runtime block import uses ──────────────────────────────────────────────────

  private def runProbe(hooks: String, run: String): (Int, String) =
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val output = Files.createTempFile("native-backend-fatal-probe", ".log")
    try
      val process = new ProcessBuilder(
        java,
        "-Xmx256m",
        "-cp",
        System.getProperty("java.class.path"),
        "com.chipprbots.ethereum.vm.NativeBackendFatalProbe",
        hooks,
        run
      ).redirectErrorStream(true).redirectOutput(output.toFile).start()
      val exited = process.waitFor(60, TimeUnit.SECONDS)
      if !exited then process.destroyForcibly()
      val text = Files.readString(output)
      withClue(s"probe $hooks/$run did not exit within 60 s:\n$text\n")(exited shouldBe true)
      (process.exitValue, text)
    finally
      Files.deleteIfExists(output)
      ()

  "A missing backend raised inside IO, with fukuii's hooks" should "halt the JVM with status 1 (block import)" taggedAs (
    UnitTest
  ) in {
    val (status, output) = runProbe("fukuii", "forget")
    withClue(output) {
      status shouldBe 1
      output should include("bls12-381")
      output should include("Halting")
      (output should not).include("STILL-ALIVE")
    }
  }

  it should "halt the JVM with status 1 (JSON-RPC and Engine API futures)" taggedAs UnitTest in {
    val (status, output) = runProbe("fukuii", "future")
    withClue(output) {
      status shouldBe 1
      output should include("Halting")
      (output should not).include("STILL-ALIVE")
    }
  }

  // Why the hook halts where the error is raised. These pin Cats Effect behaviour the design relies on (3.7.0 on this
  // classpath). If an upgrade changes them, the hook is still correct; re-check whether it is still needed.
  "A missing backend raised inside IO, without the call-time hook" should "leave the JVM running" taggedAs UnitTest in {
    val (status, output) = runProbe("none", "forget")
    withClue(output) {
      status shouldBe 0
      output should include("STILL-ALIVE")
      output should include("NativeBackendUnavailable")
    }
  }

  it should "not reach the JVM default uncaught-exception handler, whose halt never runs" taggedAs UnitTest in {
    val (status, output) = runProbe("uncaught-handler", "forget")
    withClue(output) {
      status shouldBe 0
      output should include("STILL-ALIVE")
      (output should not).include("Halting")
    }
  }

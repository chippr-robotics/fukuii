package com.chipprbots.ethereum.vm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext

import com.chipprbots.ethereum.FatalErrors

/** The child-JVM side of FatalErrorsSpec. It raises [[NativeBackends.NativeBackendUnavailable]] inside `IO` on
  * `IORuntime.global`, the way block import and the RPC servers run the VM. It prints `STILL-ALIVE` and exits 0 if the
  * JVM is still up five seconds later.
  *
  * Arguments:
  *   - the hooks to install: `none`, `uncaught-handler`, or `fukuii` (both, as `Fukuii.main` installs them);
  *   - how the IO runs: `forget` (`unsafeRunAndForget`, as `BlockImporter.importWith`) or `future` (`unsafeToFuture`,
  *     as the JSON-RPC and Engine API servers).
  */
object NativeBackendFatalProbe:

  private object MissingBls extends NativeBackends.BlsNative:
    def enabled: Boolean = false
    def perform(operation: Byte, input: Array[Byte]): Option[Array[Byte]] = None

  def main(args: Array[String]): Unit =
    val hooks = args(0)
    val run = args(1)
    if hooks == "uncaught-handler" || hooks == "fukuii" then FatalErrors.installUncaughtHandler()
    if hooks == "fukuii" then FatalErrors.installCallTimeHalt()

    // As BlockImporter.importWith: the recovery handles NonFatal errors only, so it never sees this one.
    val execution = IO(NativeBackends.blsOperation(MissingBls, 1, ByteString(new Array[Byte](256))))
      .handleError(_ => None)
      .void
    given IORuntime = IORuntime.global
    run match
      case "forget" => execution.unsafeRunAndForget()
      case _ =>
        execution
          .unsafeToFuture()
          .onComplete(outcome => println(s"FUTURE-COMPLETED $outcome"))(using
            ExecutionContext.parasitic
          )

    // Not a test synchronisation: the parent asserts on how this process ends, and a halt happens well within this.
    val _ = new CountDownLatch(1).await(5, TimeUnit.SECONDS)
    println("STILL-ALIVE")
    System.out.flush()
    sys.exit(0)

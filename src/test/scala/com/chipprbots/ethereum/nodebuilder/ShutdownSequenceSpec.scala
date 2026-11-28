package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.nodebuilder.ShutdownSequence.Budget
import com.chipprbots.ethereum.nodebuilder.ShutdownSequence.Result
import com.chipprbots.ethereum.nodebuilder.ShutdownSequence.Step
import com.chipprbots.ethereum.testing.Tags.*

/** A node's stop as named, timed steps (B-426): a slow step is named and the stop goes on to close the database, which
  * is waited for to its end. No sleeps: slow steps block on latches the test opens.
  */
class ShutdownSequenceSpec extends AnyFlatSpec with Matchers:

  final private class Lines extends ShutdownSequence.Report:
    val lines = new ConcurrentLinkedQueue[String]()
    def info(message: String): Unit = lines.add(s"INFO $message"): Unit
    def warn(message: String, error: Option[Throwable]): Unit =
      lines.add(s"WARN $message${error.fold("")(e => s" <${e.getMessage}>")}"): Unit
    def list: List[String] = lines.asScala.toList

  private val never = new CountDownLatch(1) // a step blocked on it never ends by itself

  "ShutdownSequence" should "name a step past its budget, go on, and still close the database" taggedAs UnitTest in {
    val report = new Lines
    val order = new ConcurrentLinkedQueue[String]()
    val dbClosed = new AtomicBoolean(false)
    val stuck = new CountDownLatch(1)
    val outcome = ShutdownSequence.run(
      Seq(
        Step("first", Budget.Bounded(5.seconds), () => order.add("first"): Unit),
        Step(
          "actor system",
          Budget.Bounded(50.millis),
          () =>
            order.add("actor system")
            stuck.await()
          ,
          onOverrun = () => Some("the actor that does not return")
        ),
        Step(
          StdNode.CloseDatabase,
          Budget.Unbounded(5.seconds),
          () =>
            order.add("close")
            dbClosed.set(true)
        )
      ),
      report
    )
    stuck.countDown()

    order.asScala.toList shouldBe List("first", "actor system", "close")
    dbClosed.get shouldBe true
    outcome.completed(StdNode.CloseDatabase) shouldBe true
    outcome.resultOf("actor system") shouldBe Some(Result.Overran(50.millis))
    outcome.troubled shouldBe Seq("actor system")
    val lines = report.list
    lines.exists(l =>
      l.startsWith("WARN [SHUTDOWN] actor system: still running after its budget of 50 ms")
    ) shouldBe true
    lines.exists(_.contains("the actor that does not return")) shouldBe true
    lines.exists(_.startsWith("INFO [SHUTDOWN] first: done in")) shouldBe true
    lines.exists(_.startsWith(s"INFO [SHUTDOWN] ${StdNode.CloseDatabase}: done in")) shouldBe true
    lines.exists(_.endsWith("failed or past their budget: actor system")) shouldBe true
  }

  it should "wait for a step with no budget to its end, saying each interval that it still runs" taggedAs UnitTest in {
    val report = new Lines
    val notices = new CountDownLatch(2)
    val closing = new CountDownLatch(1)
    // The close ends only once two notices have been logged: the stop did not give up on it.
    val noticing = new ShutdownSequence.Report:
      def info(message: String): Unit = report.info(message)
      def warn(message: String, error: Option[Throwable]): Unit =
        report.warn(message, error)
        if message.contains("still running") then
          notices.countDown()
          if notices.getCount == 0 then closing.countDown()
    val outcome = ShutdownSequence.run(
      Seq(Step(StdNode.CloseDatabase, Budget.Unbounded(20.millis), () => closing.await())),
      noticing
    )
    outcome.completed(StdNode.CloseDatabase) shouldBe true
    report.list.count(_.contains(s"${StdNode.CloseDatabase}: still running after")) should be >= 2
  }

  it should "log a failed step and run the next one" taggedAs UnitTest in {
    val report = new Lines
    val ran = new AtomicBoolean(false)
    val outcome = ShutdownSequence.run(
      Seq(
        Step("metrics", Budget.Bounded(5.seconds), () => throw new IllegalStateException("boom")),
        Step(StdNode.CloseDatabase, Budget.Unbounded(5.seconds), () => ran.set(true))
      ),
      report
    )
    ran.get shouldBe true
    outcome.resultOf("metrics").collect { case Result.Failed(_, e) => e.getMessage } shouldBe Some("boom")
    report.list.exists(l => l.startsWith("WARN [SHUTDOWN] metrics: failed after") && l.endsWith("<boom>")) shouldBe true
  }

  it should "say when a step left past its budget ends after all" taggedAs UnitTest in {
    val report = new Lines
    val release = new CountDownLatch(1)
    val ended = new CountDownLatch(1)
    // the late line is written on the step's thread once it ends
    val watching = new ShutdownSequence.Report:
      def info(message: String): Unit =
        report.info(message)
        if message.contains("slow: ended after all") then ended.countDown()
      def warn(message: String, error: Option[Throwable]): Unit = report.warn(message, error)
    val outcome = ShutdownSequence.run(Seq(Step("slow", Budget.Bounded(10.millis), () => release.await())), watching)
    outcome.resultOf("slow") shouldBe Some(Result.Overran(10.millis))
    release.countDown()
    ended.await(10, TimeUnit.SECONDS) shouldBe true
  }

  it should "count only bounded budgets in its total, and treat an infinite timeout as no budget" taggedAs UnitTest in {
    ShutdownSequence.boundedTotal(
      Seq(
        Step("a", Budget.Bounded(5.seconds), () => ()),
        Step("b", Budget.upTo(15.seconds), () => ()),
        Step("c", Budget.upTo(Duration.Inf), () => ())
      )
    ) shouldBe 20.seconds
    Budget.upTo(Duration.Inf) shouldBe a[Budget.Unbounded]
  }

  "busyThreadStacks" should "name a thread blocked in a call and skip a parked pool worker" taggedAs UnitTest in {
    def frame(cls: String, method: String) = new StackTraceElement(cls, method, null, -1)
    ShutdownSequence.isIdleWorker(
      Seq(
        frame("jdk.internal.misc.Unsafe", "park"),
        frame("java.util.concurrent.locks.LockSupport", "park"),
        frame("java.util.concurrent.ForkJoinPool", "awaitWork")
      )
    ) shouldBe true
    ShutdownSequence.isIdleWorker(
      Seq(
        frame("jdk.internal.misc.Unsafe", "park"),
        frame("java.util.concurrent.locks.LockSupport", "park"),
        frame("scala.concurrent.impl.Promise$DefaultPromise", "tryAwait0"),
        frame("scala.concurrent.Await$", "result")
      )
    ) shouldBe false

    val inside = new CountDownLatch(1)
    val t = new Thread(
      () =>
        inside.countDown()
        never.await()
      ,
      "b426-probe-busy-1"
    )
    t.setDaemon(true)
    t.start()
    inside.await()
    val stacks = ShutdownSequence.busyThreadStacks("b426-probe-")
    stacks.isDefined shouldBe true
    stacks.get should include("b426-probe-busy-1")
    stacks.get should include("CountDownLatch")
  }

  "StdNode.closeAfterImports" should "close the database under the import lock once the import under way ends" taggedAs UnitTest in {
    val lock = new ReentrantReadWriteLock()
    val exclusively: FiniteDuration => (() => Unit) => Option[Unit] = wait =>
      body =>
        if lock.writeLock().tryLock(wait.toNanos, TimeUnit.NANOSECONDS) then
          try Some(body())
          finally lock.writeLock().unlock()
        else None
    val importing = new CountDownLatch(1)
    val importDone = new CountDownLatch(1)
    val importer = new Thread(() =>
      lock.readLock().lock()
      try
        importing.countDown()
        importDone.await()
      finally lock.readLock().unlock()
    )
    importer.setDaemon(true)
    importer.start()
    importing.await()

    val closes = new AtomicInteger(0)
    val closedUnderLock = new AtomicBoolean(false)
    val report = new Lines
    val closer = new Thread(() =>
      StdNode.closeAfterImports(
        10.seconds,
        exclusively,
        () =>
          closes.incrementAndGet()
          closedUnderLock.set(lock.isWriteLockedByCurrentThread)
        ,
        report
      )
    )
    closer.setDaemon(true)
    closer.start()
    // the close is queued behind the import, and has not run
    while !lock.hasQueuedThread(closer) do Thread.onSpinWait()
    closes.get shouldBe 0
    importDone.countDown()
    closer.join(10000)
    closes.get shouldBe 1
    closedUnderLock.get shouldBe true
    report.list.exists(_.contains("No block import under way")) shouldBe true
  }

  it should "close the database regardless when an import outlasts the wait" taggedAs UnitTest in {
    val closes = new AtomicInteger(0)
    val report = new Lines
    StdNode.closeAfterImports(1.millis, _ => _ => None, () => closes.incrementAndGet(): Unit, report)
    closes.get shouldBe 1
    report.list.exists(_.contains("A block import still runs after")) shouldBe true
  }

  "StopSignal" should "exit 0 after a clean stop, 1 after an unclean one, and ignore a second signal" taggedAs UnitTest in {
    val exits = new ConcurrentLinkedQueue[Int]()
    val stops = new AtomicInteger(0)
    val handled = new AtomicBoolean(false)
    StopSignal.onSignal(
      handled,
      () =>
        stops.incrementAndGet(); true
      ,
      code => exits.add(code): Unit
    )
    StopSignal.onSignal(
      handled,
      () =>
        stops.incrementAndGet(); true
      ,
      code => exits.add(code): Unit
    )
    stops.get shouldBe 1
    exits.asScala.toList shouldBe List(StopSignal.CleanExit)

    val unclean = new ConcurrentLinkedQueue[Int]()
    StopSignal.onSignal(new AtomicBoolean(false), () => false, code => unclean.add(code): Unit)
    StopSignal.onSignal(
      new AtomicBoolean(false),
      () => throw new IllegalStateException("stop failed"),
      code => unclean.add(code): Unit
    )
    unclean.asScala.toList shouldBe List(StopSignal.UncleanExit, StopSignal.UncleanExit)
    StopSignal.CleanExit shouldBe 0
  }

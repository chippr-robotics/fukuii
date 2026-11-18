package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** A node that fails to start ends the process loudly (B-274): logged at ERROR, terminal restored before the error is
  * printed, what started shut down within a bound, exit non-zero. No hook here leaves the test JVM.
  */
class NodeStartupSpec extends AnyFlatSpec with Matchers:

  /** What the failure path did, in order. */
  final private class Recorder(shutdownBody: () => Unit = () => ()):
    val steps = new ConcurrentLinkedQueue[String]()
    val onFailure: NodeStartup.OnFailure = NodeStartup.OnFailure(
      restoreTerminal = () => steps.add("restore"): Unit,
      printToUser = message => steps.add(s"print:$message"): Unit,
      shutdown = () =>
        steps.add("shutdown")
        shutdownBody()
      ,
      shutdownBound = 5.seconds,
      exit = code => steps.add(s"exit:$code"): Unit
    )
    def list: List[String] = steps.asScala.toList

  /** The events NodeStartup logs while `f` runs. */
  private def logged[A](f: => A): (A, List[ILoggingEvent]) =
    val logger = LoggerFactory.getLogger(NodeStartup.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    val previous = logger.getLevel
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    val result =
      try f
      finally
        logger.setLevel(previous)
        logger.detachAppender(appender)
    (result, appender.list.asScala.toList)

  "NodeStartup.startOrExit" should "log at ERROR, restore the terminal, print, shut down and exit 1 when start throws" taggedAs UnitTest in {
    val recorder = new Recorder()
    val boom = new IllegalArgumentException("Unknown protocol bogus")
    val (started, events) = logged(NodeStartup.startOrExit(() => throw boom, recorder.onFailure))

    started shouldBe false
    val errors = events.filter(_.getLevel == Level.ERROR)
    errors.map(_.getFormattedMessage) should contain(s"Fukuii failed to start: $boom")
    errors.exists(e => e.getThrowableProxy != null && e.getThrowableProxy.getMessage == boom.getMessage) shouldBe true

    recorder.list shouldBe List(
      "restore",
      s"print:${NodeStartup.userMessage(boom)}",
      "shutdown",
      s"exit:${NodeStartup.FailedExitCode}"
    )
    NodeStartup.FailedExitCode should not be 0
    NodeStartup.userMessage(boom) should include("Unknown protocol bogus")
  }

  it should "print and log a startup refusal as its own one line, with no stack trace" taggedAs UnitTest in {
    val recorder = new Recorder()
    val refusal = StdNode.startupRefusal("Refusing to start: the JSON-RPC server cannot bind 127.0.0.1:8545")
    val (_, events) = logged(NodeStartup.startOrExit(() => throw refusal, recorder.onFailure))

    val errors = events.filter(_.getLevel == Level.ERROR)
    errors.map(_.getFormattedMessage) shouldBe List(refusal.getMessage)
    errors.head.getThrowableProxy shouldBe null
    recorder.list shouldBe List("restore", s"print:${refusal.getMessage}", "shutdown", "exit:1")
  }

  it should "handle a fatal error from start the same way" taggedAs UnitTest in {
    val recorder = new Recorder()
    val fatal = new UnsatisfiedLinkError("no rocksdbjni in java.library.path")
    val (started, events) = logged(NodeStartup.startOrExit(() => throw fatal, recorder.onFailure))

    started shouldBe false
    events.exists(e => e.getLevel == Level.ERROR && e.getFormattedMessage.contains("rocksdbjni")) shouldBe true
    recorder.list.last shouldBe "exit:1"
  }

  it should "neither restore, shut down nor exit when start returns" taggedAs UnitTest in {
    val recorder = new Recorder()
    var ran = false
    val (started, events) = logged(NodeStartup.startOrExit(() => ran = true, recorder.onFailure))

    ran shouldBe true
    started shouldBe true
    recorder.list shouldBe Nil
    events.filter(_.getLevel == Level.ERROR) shouldBe Nil
  }

  it should "still print, shut down and exit when restoring the terminal throws" taggedAs UnitTest in {
    val steps = new ConcurrentLinkedQueue[String]()
    val onFailure = NodeStartup.OnFailure(
      restoreTerminal = () => throw new IllegalStateException("terminal gone"),
      printToUser = _ => steps.add("print"): Unit,
      shutdown = () => steps.add("shutdown"): Unit,
      shutdownBound = 5.seconds,
      exit = code => steps.add(s"exit:$code"): Unit
    )
    val _ = logged(NodeStartup.startOrExit(() => throw new RuntimeException("x"), onFailure))
    steps.asScala.toList shouldBe List("print", "shutdown", "exit:1")
  }

  it should "exit once the shutdown overruns its bound" taggedAs UnitTest in {
    val release = new CountDownLatch(1)
    val steps = new ConcurrentLinkedQueue[String]()
    val onFailure = NodeStartup.OnFailure(
      restoreTerminal = () => (),
      printToUser = _ => (),
      shutdown = () =>
        steps.add("shutdown")
        release.await()
      ,
      shutdownBound = 50.millis,
      exit = code => steps.add(s"exit:$code"): Unit
    )
    try
      val (_, events) = logged(NodeStartup.startOrExit(() => throw new RuntimeException("x"), onFailure))
      steps.asScala.toList shouldBe List("shutdown", "exit:1")
      events.exists(e => e.getLevel == Level.ERROR && e.getFormattedMessage.contains("did not finish")) shouldBe true
    finally release.countDown()
  }

  "NodeStartup.startOrShutDown" should "shut down and return the error when start throws" taggedAs UnitTest in {
    var shutdowns = 0
    val boom = new IllegalStateException("bind failed")
    val (failure, events) =
      logged(NodeStartup.startOrShutDown(() => throw boom, () => shutdowns += 1, 5.seconds))

    failure shouldBe Some(boom)
    shutdowns shouldBe 1
    events.exists(_.getLevel == Level.ERROR) shouldBe true
  }

  it should "not shut down when start returns" taggedAs UnitTest in {
    var shutdowns = 0
    NodeStartup.startOrShutDown(() => (), () => shutdowns += 1, 5.seconds) shouldBe None
    shutdowns shouldBe 0
  }

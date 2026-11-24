package com.chipprbots.ethereum.blockchain.sync.snap

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.testing.Tags.*

/** B-256: a capture opened while another thread logs in the `snap` package must still see what is logged after it
  * opens.
  *
  * The characterization specs open a capture right after sending the controller a message, so the controller's thread
  * is logging in the package at the moment the capture opens. An appender added to the package logger then could be
  * lost: logback's appender list (`COWArrayList`) caches an array, and a logging thread that rebuilds the cache from
  * the old list while the appender is added marks the stale copy fresh, so the new appender receives nothing until the
  * list changes again (CI run 45310800026: "the last 0 captured"). The race is a window of a few instructions, so the
  * case opens a capture many times against a thread that logs without pause: with the appender design it lost a capture
  * in most runs; with the turbo filter there is no list to go stale, and it cannot fail.
  */
class SnapLogCaptureSpec extends AnyFlatSpec with Matchers:

  private val busyLogger =
    LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.sync.snap.SnapLogCaptureSpec.busy")
  private val testLogger = LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.sync.snap.SnapLogCaptureSpec")

  "SnapLogCapture" should "see every line logged after it opens while another thread logs in the package, nested or not" taggedAs UnitTest in {
    val stop = new AtomicBoolean(false)
    val started = new CountDownLatch(1)
    val busy = new Thread(
      () =>
        started.countDown()
        var i = 0L
        while !stop.get() do
          busyLogger.debug("busy {}", i)
          i += 1
      ,
      "snap-log-capture-busy"
    )
    busy.setDaemon(true)
    busy.start()
    started.await()
    val missed =
      try
        Using.resource(new SnapLogCapture) { outer =>
          (1 to 2000).count { n =>
            Using.resource(new SnapLogCapture) { inner =>
              testLogger.error("marker {}", n)
              val seen = inner.contains(s"marker $n")
              outer.clear() // keep the outer capture's memory flat; it is open only to nest the inner one
              !seen
            }
          }
        }
      finally
        stop.set(true)
        busy.join()
    missed shouldBe 0
  }

  it should "keep the message with its arguments and level, at DEBUG and up only, and see nothing once closed" taggedAs UnitTest in {
    // Other suites' actors may still log in the package; only this case's lines (prefixed "b256") are compared.
    def mine(c: SnapLogCapture) = c.events.filter(_._2.startsWith("b256"))
    val capture = new SnapLogCapture
    try
      testLogger.debug("b256 a {} line at {}", "debug", 1)
      testLogger.info("b256 an info line")
      testLogger.trace("b256 a trace line")
      LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.sync.snapshot").error("b256 outside the package")
      mine(capture).map { case (level, line) => s"$level $line" } shouldBe List(
        "DEBUG b256 a debug line at 1",
        "INFO b256 an info line"
      )
      capture.awaitLine("b256 an info") shouldBe "b256 an info line"
    finally capture.close()
    testLogger.info("b256 after close")
    mine(capture).map(_._2) should not contain "b256 after close"
  }

package com.chipprbots.ethereum.console

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import org.scalatest.OptionValues
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.testing.Tags.*

/** The lookup worker (spec 029 FR-029): nothing it does can hold up a frame. */
class LookupsSpec extends AnyFlatSpec with Matchers with OptionValues with Eventually:

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(5, Seconds), Span(5, Millis))

  private def account(n: Int) = LookupResult.Account(f"0x$n%040x", 100L, BigInt(n), BigInt(0), None)
  private def stateOf(lookups: Lookups, q: LookupQuery) = lookups.results.find(_._1 == q).map(_._2)

  "A lookup" should "be published under its query when the reader returns" taggedAs (UnitTest) in {
    val lookups = new Lookups(_ => Right(account(1)))
    try
      val q = LookupQuery.Account("0x01")
      lookups.submit(q)
      eventually(stateOf(lookups, q).value shouldBe LookupState.Done(account(1)))
    finally lookups.shutdown()
  }

  it should "say in words what was not found, and turn a reader's exception into a sentence" taggedAs (UnitTest) in {
    val lookups = new Lookups({
      case LookupQuery.Tx(_) => Left("no transaction with this hash is in the node's index")
      case _                 => throw new IllegalStateException("state pruned")
    })
    try
      lookups.submit(LookupQuery.Tx("0xaa"))
      lookups.submit(LookupQuery.Account("0xbb"))
      eventually {
        stateOf(lookups, LookupQuery.Tx("0xaa")).value shouldBe
          LookupState.Failed("no transaction with this hash is in the node's index")
        stateOf(lookups, LookupQuery.Account("0xbb")).value shouldBe
          LookupState.Failed("the node could not read it: state pruned")
      }
    finally lookups.shutdown()
  }

  "A slow reader" should "never delay the caller: the lookup shows as running until it returns" taggedAs (UnitTest) in {
    val release = new CountDownLatch(1)
    val lookups = new Lookups(_ =>
      release.await(); Right(account(2))
    )
    try
      val q = LookupQuery.LatestBlock
      val started = System.nanoTime()
      lookups.submit(q)
      lookups.results // a read
      (System.nanoTime() - started) / 1_000_000L should be < 1000L
      stateOf(lookups, q).value shouldBe a[LookupState.Running]
      release.countDown()
      eventually(stateOf(lookups, q).value shouldBe LookupState.Done(account(2)))
    finally
      release.countDown()
      lookups.shutdown()
  }

  "Cancel" should "interrupt the reader, discard its result, and leave the worker for the next lookup" taggedAs (
    UnitTest
  ) in {
    val entered = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val lookups = new Lookups({
      case LookupQuery.LatestBlock =>
        entered.countDown()
        try
          new CountDownLatch(1).await() // a read that never returns: only the interrupt ends it
          Right(account(3))
        catch
          case e: InterruptedException =>
            interrupted.countDown()
            throw e
      case _ => Right(account(4))
    })
    try
      lookups.submit(LookupQuery.LatestBlock)
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      lookups.cancel(LookupQuery.LatestBlock)
      interrupted.await(5, TimeUnit.SECONDS) shouldBe true
      stateOf(lookups, LookupQuery.LatestBlock) shouldBe None
      lookups.submit(LookupQuery.Account("0x04"))
      eventually(stateOf(lookups, LookupQuery.Account("0x04")).value shouldBe LookupState.Done(account(4)))
      stateOf(lookups, LookupQuery.LatestBlock) shouldBe None
    finally lookups.shutdown()
  }

  "A lookup already done" should "not be read again; a failed one is; and only the newest results are kept" taggedAs (
    UnitTest
  ) in {
    val reads = new java.util.concurrent.atomic.AtomicInteger(0)
    val lookups = new Lookups(
      {
        case LookupQuery.Tx(_) => reads.incrementAndGet(); Left("not found")
        case _                 => reads.incrementAndGet(); Right(account(5))
      },
      keep = 3
    )
    try
      val first = LookupQuery.Account("0x0a")
      lookups.submit(first)
      eventually(stateOf(lookups, first).value shouldBe a[LookupState.Done])
      lookups.submit(first)
      lookups.submit(LookupQuery.Tx("0x0b"))
      eventually(stateOf(lookups, LookupQuery.Tx("0x0b")).value shouldBe a[LookupState.Failed])
      reads.get shouldBe 2
      lookups.submit(LookupQuery.Tx("0x0b"))
      eventually(reads.get shouldBe 3)
      (1 to 5).foreach(n => lookups.submit(LookupQuery.BlockByNumber(n.toLong)))
      eventually(lookups.results.count(_._2.isInstanceOf[LookupState.Done]) shouldBe 3)
      lookups.results.map(_._1) shouldBe (3 to 5).map(n => LookupQuery.BlockByNumber(n.toLong))
    finally lookups.shutdown()
  }

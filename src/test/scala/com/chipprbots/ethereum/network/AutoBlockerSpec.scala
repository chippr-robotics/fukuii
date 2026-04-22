package com.chipprbots.ethereum.network

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import org.apache.pekko.actor.Scheduler
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class AutoBlockerSpec extends AnyFlatSpec with Matchers {

  /** Scheduler that records scheduled tasks but does NOT execute them automatically. Invoke `runAll()` to fire all
    * pending tasks (simulates time passing).
    */
  class ManualScheduler extends Scheduler {
    private var pending: List[Runnable] = Nil

    override def maxFrequency: Double = 1.0

    override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit
        executor: ExecutionContext
    ): org.apache.pekko.actor.Cancellable = {
      pending = runnable :: pending
      new org.apache.pekko.actor.Cancellable {
        def cancel(): Boolean = { pending = pending.filterNot(_ eq runnable); true }
        def isCancelled: Boolean = false
      }
    }

    override def schedule(
        initialDelay: FiniteDuration,
        interval: FiniteDuration,
        runnable: Runnable
    )(implicit executor: ExecutionContext): org.apache.pekko.actor.Cancellable =
      scheduleOnce(initialDelay, runnable)

    def runAll(): Unit = {
      val toRun = pending
      pending = Nil
      toRun.foreach(_.run())
    }

    def pendingCount: Int = pending.size
  }

  def makeBlocker(
      threshold: Int = 3,
      window: FiniteDuration = 5.minutes,
      udpBlock: FiniteDuration = 30.minutes,
      hardBlock: FiniteDuration = 60.minutes,
      initialIPs: Set[String] = Set.empty
  ): (AutoBlocker, BlockedIPRegistry, ManualScheduler) = {
    val registry = new BlockedIPRegistry(initialIPs)
    val scheduler = new ManualScheduler
    val blocker = new AutoBlocker(
      registry,
      scheduler,
      ExecutionContext.global,
      udpFailureThreshold = threshold,
      udpWindow = window,
      udpBlockDuration = udpBlock,
      hardFailureBlockDuration = hardBlock
    )
    (blocker, registry, scheduler)
  }

  "AutoBlocker.recordUdpFailure" should "not block before threshold is reached" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, _) = makeBlocker(threshold = 3)
    blocker.recordUdpFailure("1.2.3.4")
    blocker.recordUdpFailure("1.2.3.4")
    registry.isBlocked("1.2.3.4") shouldBe false
  }

  it should "block exactly at threshold" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, _) = makeBlocker(threshold = 3)
    blocker.recordUdpFailure("1.2.3.4")
    blocker.recordUdpFailure("1.2.3.4")
    blocker.recordUdpFailure("1.2.3.4")
    registry.isBlocked("1.2.3.4") shouldBe true
  }

  it should "not double-block an already blocked IP" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, scheduler) = makeBlocker(threshold = 3)
    // Trigger first block
    (1 to 3).foreach(_ => blocker.recordUdpFailure("1.2.3.4"))
    scheduler.pendingCount shouldBe 1 // one unblock scheduled

    // More failures — should not schedule a second unblock
    blocker.recordUdpFailure("1.2.3.4")
    blocker.recordUdpFailure("1.2.3.4")
    scheduler.pendingCount shouldBe 1
  }

  it should "auto-unblock after decay period" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, scheduler) = makeBlocker(threshold = 3)
    (1 to 3).foreach(_ => blocker.recordUdpFailure("1.2.3.4"))
    registry.isBlocked("1.2.3.4") shouldBe true

    scheduler.runAll()
    registry.isBlocked("1.2.3.4") shouldBe false
  }

  it should "track different IPs independently" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, _) = makeBlocker(threshold = 3)
    blocker.recordUdpFailure("1.1.1.1")
    blocker.recordUdpFailure("1.1.1.1")
    blocker.recordUdpFailure("1.1.1.1")
    blocker.recordUdpFailure("2.2.2.2")
    blocker.recordUdpFailure("2.2.2.2")

    registry.isBlocked("1.1.1.1") shouldBe true
    registry.isBlocked("2.2.2.2") shouldBe false
  }

  "AutoBlocker.recordHardFailure" should "block immediately on first call" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, _) = makeBlocker()
    registry.isBlocked("5.5.5.5") shouldBe false
    blocker.recordHardFailure("5.5.5.5", "IncompatibleP2pProtocolVersion")
    registry.isBlocked("5.5.5.5") shouldBe true
  }

  it should "not double-block an already blocked IP" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, scheduler) = makeBlocker()
    blocker.recordHardFailure("5.5.5.5", "IncompatibleP2pProtocolVersion")
    scheduler.pendingCount shouldBe 1

    blocker.recordHardFailure("5.5.5.5", "IncompatibleP2pProtocolVersion")
    scheduler.pendingCount shouldBe 1 // still only one unblock scheduled
  }

  it should "auto-unblock after hard-failure decay period" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, scheduler) = makeBlocker()
    blocker.recordHardFailure("5.5.5.5", "IncompatibleP2pProtocolVersion")
    registry.isBlocked("5.5.5.5") shouldBe true

    scheduler.runAll()
    registry.isBlocked("5.5.5.5") shouldBe false
  }

  it should "leave manually-blocked IPs unaffected" taggedAs (UnitTest, NetworkTest) in {
    val (blocker, registry, scheduler) = makeBlocker(initialIPs = Set("9.9.9.9"))
    // Hard failure on an already-blocked IP should be a no-op
    blocker.recordHardFailure("9.9.9.9", "IncompatibleP2pProtocolVersion")
    scheduler.pendingCount shouldBe 0 // no unblock scheduled for manually-blocked IPs
    registry.isBlocked("9.9.9.9") shouldBe true
  }

  "AutoBlocker disabled via config" should "be represented by None and not block" taggedAs (UnitTest, NetworkTest) in {
    // When autoBlocker = None, the wiring in PeerManagerActor uses .foreach() — test that pattern
    val registry: BlockedIPRegistry = new BlockedIPRegistry(Set.empty)
    val autoBlocker: Option[AutoBlocker] = None

    autoBlocker.foreach(_.recordHardFailure("6.6.6.6", "test"))
    registry.isBlocked("6.6.6.6") shouldBe false
  }
}

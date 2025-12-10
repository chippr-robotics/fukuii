package com.chipprbots.ethereum.nodebuilder

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.network.discovery.DiscoveryConfig
import com.chipprbots.ethereum.testing.Tags._

/** Tests for the PortForwardingBuilder trait to validate correct port forwarding initialization and prevent multiple
  * UPnP service allocations.
  *
  * This test suite validates the fix for the early shutdown bug where `startPortForwarding()` was incorrectly running
  * the allocation function on every invocation instead of storing the cleanup function.
  *
  * The tests ensure:
  *   1. Port forwarding is allocated exactly once on first call
  *   1. Subsequent calls return immediately without re-allocation
  *   1. Thread-safe initialization prevents race conditions
  *   1. Cleanup function is properly stored and invoked on shutdown
  *   1. Multiple allocations (the bug) are prevented
  */
class PortForwardingBuilderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(0.1, Seconds))

  // Track all builders created during tests for cleanup
  private var testBuilders: List[TestPortForwardingBuilder] = List.empty

  override def afterEach(): Unit = {
    // Ensure all port forwarding resources are cleaned up after each test
    // This is defensive cleanup - if stopPortForwarding fails, the test already
    // made assertions about the state, so we don't need to fail here
    testBuilders.foreach { builder =>
      try
        builder.stopPortForwarding().futureValue
      catch {
        case NonFatal(_) => // Ignore non-fatal cleanup errors (already stopped, timeout, etc.)
      }
    }
    testBuilders = List.empty
    super.afterEach()
  }

  private def createTestBuilder(
      allocationCount: AtomicInteger,
      cleanupCount: AtomicInteger,
      simulateDelay: Long = 0
  ): TestPortForwardingBuilder = {
    val builder = new TestPortForwardingBuilder(allocationCount, cleanupCount, simulateDelay)
    testBuilders = builder :: testBuilders
    builder
  }

  behavior.of("PortForwardingBuilder")

  it should "allocate port forwarding exactly once on first call" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // First call should trigger allocation
    val future1 = builder.startPortForwarding()
    whenReady(future1) { _ =>
      allocationCount.get() shouldBe 1
      cleanupCount.get() shouldBe 0
    }
  }

  it should "not re-allocate on subsequent calls to startPortForwarding" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // First call
    val future1 = builder.startPortForwarding()
    whenReady(future1) { _ =>
      allocationCount.get() shouldBe 1
    }

    // Second call should not trigger re-allocation
    val future2 = builder.startPortForwarding()
    whenReady(future2) { _ =>
      allocationCount.get() shouldBe 1 // Still 1, not 2
      cleanupCount.get() shouldBe 0
    }

    // Third call should also not trigger re-allocation
    val future3 = builder.startPortForwarding()
    whenReady(future3) { _ =>
      allocationCount.get() shouldBe 1 // Still 1, not 3
      cleanupCount.get() shouldBe 0
    }
  }

  it should "handle concurrent calls to startPortForwarding safely" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount, simulateDelay = 50)

    // Start multiple concurrent calls
    val futures = (1 to 10).map { _ =>
      Future {
        builder.startPortForwarding()
      }
    }

    // Wait for all to complete
    val combined = Future.sequence(futures.map(_.flatten))
    whenReady(combined) { _ =>
      // Only one allocation should have occurred despite concurrent calls
      allocationCount.get() shouldBe 1
      cleanupCount.get() shouldBe 0
    }
  }

  it should "properly store and invoke cleanup function on stopPortForwarding" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // Start port forwarding
    val startFuture = builder.startPortForwarding()
    whenReady(startFuture) { _ =>
      allocationCount.get() shouldBe 1
      cleanupCount.get() shouldBe 0
    }

    // Stop port forwarding - should invoke cleanup
    val stopFuture = builder.stopPortForwarding()
    whenReady(stopFuture) { _ =>
      allocationCount.get() shouldBe 1
      cleanupCount.get() shouldBe 1 // Cleanup invoked
    }
  }

  it should "allow stopPortForwarding to be called multiple times safely" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // Start port forwarding
    val startFuture = builder.startPortForwarding()
    whenReady(startFuture) { _ =>
      allocationCount.get() shouldBe 1
    }

    // First stop
    val stopFuture1 = builder.stopPortForwarding()
    whenReady(stopFuture1) { _ =>
      cleanupCount.get() shouldBe 1
    }

    // Second stop should be safe (no-op)
    val stopFuture2 = builder.stopPortForwarding()
    whenReady(stopFuture2) { _ =>
      cleanupCount.get() shouldBe 1 // Still 1, not 2
    }
  }

  it should "allow restart after stop" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // First cycle: start and stop
    whenReady(builder.startPortForwarding()) { _ =>
      allocationCount.get() shouldBe 1
    }
    whenReady(builder.stopPortForwarding()) { _ =>
      cleanupCount.get() shouldBe 1
    }

    // Second cycle: start and stop again
    whenReady(builder.startPortForwarding()) { _ =>
      allocationCount.get() shouldBe 2 // New allocation after stop
    }
    whenReady(builder.stopPortForwarding()) { _ =>
      cleanupCount.get() shouldBe 2
    }
  }

  it should "handle stopPortForwarding before startPortForwarding gracefully" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // Stop before start should be a no-op
    val stopFuture = builder.stopPortForwarding()
    whenReady(stopFuture) { _ =>
      allocationCount.get() shouldBe 0
      cleanupCount.get() shouldBe 0
    }

    // Start should still work after
    val startFuture = builder.startPortForwarding()
    whenReady(startFuture) { _ =>
      allocationCount.get() shouldBe 1
      cleanupCount.get() shouldBe 0
    }
  }

  it should "prevent the buggy behavior of multiple allocations on repeated calls" taggedAs (UnitTest) in {
    val allocationCount = new AtomicInteger(0)
    val cleanupCount = new AtomicInteger(0)

    val builder = createTestBuilder(allocationCount, cleanupCount)

    // Simulate the scenario that would have caused the bug:
    // Multiple rapid calls to startPortForwarding
    val futures = (1 to 5).map { _ =>
      builder.startPortForwarding()
    }

    // Wait for all
    val combined = Future.sequence(futures)
    whenReady(combined) { _ =>
      // The bug would have caused 5 allocations
      // The fix ensures only 1 allocation
      allocationCount.get() shouldBe 1
      cleanupCount.get() shouldBe 0
    }
  }

  /** Test implementation of PortForwardingBuilder that uses mock allocation/cleanup functions to track invocations
    */
  class TestPortForwardingBuilder(
      allocationCounter: AtomicInteger,
      cleanupCounter: AtomicInteger,
      simulateDelay: Long = 0
  ) extends PortForwardingBuilder
      with DiscoveryConfigBuilder {

    implicit override lazy val ioRuntime: IORuntime = IORuntime.global

    // Mock discovery config
    override lazy val discoveryConfig: DiscoveryConfig = DiscoveryConfig(
      discoveryEnabled = true,
      host = None,
      interface = "0.0.0.0",
      port = 30303,
      bootstrapNodes = Set.empty,
      reuseKnownNodes = false,
      scanInterval = 10.seconds,
      messageExpiration = 60.seconds,
      maxClockDrift = 10.seconds,
      requestTimeout = 10.seconds,
      kademliaTimeout = 10.seconds,
      kademliaBucketSize = 16,
      kademliaAlpha = 3,
      channelCapacity = 100
    )

    // Override the portForwarding to use a mock implementation
    override protected lazy val portForwarding: IO[IO[Unit]] =
      (if (simulateDelay > 0) IO.sleep(simulateDelay.millis) else IO.unit).flatMap { _ =>
        IO {
          allocationCounter.incrementAndGet()

          // Return cleanup function
          IO {
            cleanupCounter.incrementAndGet()
          }
        }
      }
  }
}

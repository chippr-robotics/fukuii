package com.chipprbots.ethereum.network.discovery

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.Ref
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Task #52: random peer candidates come from ONE lookup loop with backpressure and backoff, not one lookup per
  * request.
  */
class RandomNodeFeederSpec extends AnyFlatSpec with Matchers:

  private def node(i: Int): Node =
    Node(ByteString(Array.fill[Byte](64)(i.toByte)), InetAddress.getLoopbackAddress, 30303 + i, 30303 + i)

  // Stops the endless feeder loop after `rounds` pauses; the pauses requested are recorded.
  final private case class Stop() extends Exception with NoStackTrace

  private def recordPauses(rounds: Int, lookup: IO[Set[Node]]): (List[FiniteDuration], Int) =
    val lookups = new AtomicInteger(0)
    val program =
      for
        pauses <- Ref[IO].of(Vector.empty[FiniteDuration])
        result <- RandomNodeFeeder
          .run(
            lookup = IO(lookups.incrementAndGet()) >> lookup,
            offer = _ => IO.unit,
            sleep = d =>
              pauses.updateAndGet(_ :+ d).flatMap(ps => if ps.size >= rounds then IO.raiseError(Stop()) else IO.unit)
          )
          .attempt
        recorded <- pauses.get
      yield
        result.left.toOption.exists(_.isInstanceOf[Stop]) shouldBe true
        recorded.toList
    (program.unsafeRunSync(), lookups.get)

  behavior.of("RandomNodeFeeder.nextDelay")

  it should "reset to the minimum after a lookup that found an unseen node" taggedAs (UnitTest, NetworkTest) in {
    RandomNodeFeeder.nextDelay(32.seconds, foundUnseen = true) shouldBe RandomNodeFeeder.MinDelay
  }

  it should "double when nothing new was found, capped at the maximum" taggedAs (UnitTest, NetworkTest) in {
    RandomNodeFeeder.nextDelay(2.seconds, foundUnseen = false) shouldBe 4.seconds
    RandomNodeFeeder.nextDelay(48.seconds, foundUnseen = false) shouldBe RandomNodeFeeder.MaxDelay
    RandomNodeFeeder.nextDelay(RandomNodeFeeder.MaxDelay, foundUnseen = false) shouldBe RandomNodeFeeder.MaxDelay
  }

  behavior.of("RandomNodeFeeder.pruneSeen")

  it should "forget entries older than the TTL and keep only the newest when over the bound" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val now = 1_000_000L
    val seen = Map(node(1).id -> (now - 10L), node(2).id -> (now - 5L), node(3).id -> (now - 2_000L))
    RandomNodeFeeder.pruneSeen(seen, now, ttl = 1.second, maxSize = 10).keySet shouldBe Set(node(1).id, node(2).id)
    RandomNodeFeeder.pruneSeen(seen, now, ttl = 1.hour, maxSize = 1).keySet shouldBe Set(node(2).id)
  }

  behavior.of("RandomNodeFeeder.run")

  it should "back off exponentially while lookups only return nodes it has already offered" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (pauses, lookups) = recordPauses(rounds = 8, lookup = IO.pure(Set(node(1), node(2))))
    pauses shouldBe List(2, 4, 8, 16, 32, 64, 64, 64).map(_.seconds)
    lookups shouldBe 8
  }

  it should "keep the minimum pause while lookups keep finding new nodes" taggedAs (UnitTest, NetworkTest) in {
    val counter = new AtomicInteger(0)
    val (pauses, _) = recordPauses(rounds = 5, lookup = IO(Set(node(counter.incrementAndGet()))))
    pauses shouldBe List.fill(5)(RandomNodeFeeder.MinDelay)
  }

  it should "treat a failed lookup as finding nothing and back off instead of failing" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val (pauses, _) = recordPauses(rounds = 3, lookup = IO.raiseError(new RuntimeException("boom") with NoStackTrace))
    pauses shouldBe List(4, 8, 16).map(_.seconds)
  }

  it should "not start another lookup while the buffer is full and nobody consumes" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val lookups = new AtomicInteger(0)
    val program =
      for
        queue <- Queue.bounded[IO, Node](1)
        _ <- RandomNodeFeeder
          .run(
            lookup = IO(lookups.incrementAndGet()).as(Set(node(1), node(2), node(3))),
            offer = queue.offer,
            sleep = _ => IO.unit
          )
          .timeoutTo(300.millis, IO.unit)
        size <- queue.size
      yield size
    program.unsafeRunSync() shouldBe 1
    // The first lookup's second offer blocks on the full buffer, so no further lookup can begin.
    lookups.get shouldBe 1
  }

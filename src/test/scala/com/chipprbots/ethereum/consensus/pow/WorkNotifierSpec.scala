package com.chipprbots.ethereum.consensus.pow

import java.util.concurrent.ConcurrentLinkedQueue

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span

import com.chipprbots.ethereum.testing.Tags.*

class WorkNotifierSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers with Eventually:

  implicit private val classicActorSystem: org.apache.pekko.actor.ActorSystem = system.toClassic

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  // the URLs here are plain http, which never use it; an https URL is covered by tls.OutboundSitesSpec
  private val plain = com.chipprbots.ethereum.tls.OutboundTls.pekkoHttpsContext(javax.net.ssl.SSLContext.getDefault)

  val testWork: WorkNotifier.WorkPackage = WorkNotifier.WorkPackage(
    powHeaderHash = ByteString(Array.fill(32)(0xab.toByte)),
    dagSeed = ByteString(Array.fill(32)(0xcd.toByte)),
    target = ByteString(Array.fill(32)(0xef.toByte)),
    blockNumber = BigInt(12345)
  )

  "WorkNotifier" should "POST a 4-element JSON array to the configured URL" taggedAs UnitTest in {
    val received = new ConcurrentLinkedQueue[String]()

    val route = post {
      entity(as[String]) { body =>
        received.add(body)
        complete("ok")
      }
    }

    val binding = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route), 5.seconds)
    val port = binding.localAddress.getPort

    WorkNotifier.notify(Seq(s"http://127.0.0.1:$port"), testWork, plain)

    eventually {
      received.size() shouldEqual 1
    }

    val body = received.peek()
    body should startWith("[")
    body should endWith("]")
    // Verify 4 elements present by counting comma-separated quoted strings
    body.count(_ == '"') shouldEqual 8 // 4 pairs of quotes
    // Block number is present as 0x3039 (12345 in hex)
    body should include("0x3039")

    Await.result(binding.unbind(), 5.seconds)
  }

  it should "send independent POSTs to multiple URLs" taggedAs UnitTest in {
    val received1 = new ConcurrentLinkedQueue[String]()
    val received2 = new ConcurrentLinkedQueue[String]()

    val route1 = post {
      entity(as[String]) { b =>
        received1.add(b); complete("ok")
      }
    }
    val route2 = post {
      entity(as[String]) { b =>
        received2.add(b); complete("ok")
      }
    }

    val binding1 = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route1), 5.seconds)
    val binding2 = Await.result(Http().newServerAt("127.0.0.1", 0).bind(route2), 5.seconds)

    val port1 = binding1.localAddress.getPort
    val port2 = binding2.localAddress.getPort

    WorkNotifier.notify(Seq(s"http://127.0.0.1:$port1", s"http://127.0.0.1:$port2"), testWork, plain)

    eventually {
      received1.size() shouldEqual 1
      received2.size() shouldEqual 1
    }

    received1.peek() should include("0x3039")
    received2.peek() should include("0x3039")

    Await.result(binding1.unbind(), 5.seconds)
    Await.result(binding2.unbind(), 5.seconds)
  }

  it should "not block the caller when a URL is unreachable" taggedAs UnitTest in {
    val start = System.currentTimeMillis()
    // Port 19999 — nothing listening there
    WorkNotifier.notify(Seq("http://127.0.0.1:19999"), testWork, plain)
    val elapsed = System.currentTimeMillis() - start
    // Fire-and-forget: should return in well under 1 second
    elapsed should be < 500L
  }

  it should "skip notification when URLs list is empty" taggedAs UnitTest in {
    // No exception, no POST attempted — just a no-op
    WorkNotifier.notify(Seq.empty, testWork, plain)
    // If we reach here without exception, the test passes
    succeed
  }

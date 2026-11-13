package com.chipprbots.ethereum.nodebuilder

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives.complete
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest

import scala.concurrent.Future
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** B-246: the Engine API is checked before anything starts, and a bind that fails is fatal. */
class StdNodeEngineApiStartSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest:

  "StdNode.engineApiRefusal" should "turn a refused JWT secret into its message, with no exception" taggedAs UnitTest in {
    StdNode.engineApiRefusal(throw new IllegalStateException("Refusing to start: the file x is not usable")) shouldBe
      Some("Refusing to start: the file x is not usable")
  }

  it should "do the same for an I/O failure and for a bad argument" taggedAs UnitTest in {
    StdNode.engineApiRefusal(throw new IOException("disk gone")).exists(_.contains("disk gone")) shouldBe true
    StdNode.engineApiRefusal(throw new IllegalArgumentException("bad")).exists(_.contains("bad")) shouldBe true
  }

  it should "say nothing when the secret is fine" taggedAs UnitTest in {
    StdNode.engineApiRefusal(42) shouldBe None
  }

  it should "not catch an unrelated failure" taggedAs UnitTest in {
    an[UnsupportedOperationException] should be thrownBy StdNode.engineApiRefusal(
      throw new UnsupportedOperationException
    )
  }

  "StdNode.awaitEngineApiBind" should "return the binding when the port is free" taggedAs UnitTest in {
    StdNode.awaitEngineApiBind(Future.successful("bound"), "localhost", 8551, 5.seconds) shouldBe Right("bound")
  }

  it should "refuse, naming the port and the key to change, when the port is already bound" taggedAs UnitTest in {
    val held = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    try
      val port = held.getLocalPort
      val bind = Http().newServerAt("127.0.0.1", port).bind(complete("x"))
      val result = StdNode.awaitEngineApiBind(bind, "127.0.0.1", port, 10.seconds)
      val message = result.left.getOrElse(fail("the bind of a held port succeeded"))
      message should include(port.toString)
      message should include("network.engine-api.port")
    finally held.close()
  }

  it should "refuse a bind that does not finish in time" taggedAs UnitTest in {
    val result = StdNode.awaitEngineApiBind(Future.never, "localhost", 8551, 100.milliseconds)
    result.left.exists(_.contains("network.engine-api.port")) shouldBe true
  }

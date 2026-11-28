package com.chipprbots.ethereum.metrics

import java.net.InetAddress
import java.net.ServerSocket

import com.typesafe.config.ConfigFactory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 030 FR-016, US8 scenario 3: with no `interface`, the plaintext Prometheus server binds loopback, not the
  * wildcard address it bound before.
  */
class MetricsBindSpec extends AnyFlatSpec with Matchers:

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  "MetricsConfig" should "default interface to localhost and read it, and tls.enabled, when set" taggedAs UnitTest in {
    val default = MetricsConfig(ConfigFactory.parseString("metrics { enabled = true, port = 1 }"))
    default.interface shouldBe "localhost"
    default.tlsEnabled shouldBe false
    val set = MetricsConfig(
      ConfigFactory.parseString("""metrics { enabled = true, port = 1, interface = "0.0.0.0", tls.enabled = true }""")
    )
    set.interface shouldBe "0.0.0.0"
    set.tlsEnabled shouldBe true
  }

  "The plaintext metrics server" should "bind loopback by default, leaving the port free on other addresses" taggedAs (
    UnitTest
  ) in {
    val port = freePort()
    val metrics = Metrics("test", new SimpleMeterRegistry(), port)
    metrics.start()
    try
      // the port is taken on loopback...
      an[java.net.BindException] should be thrownBy new ServerSocket(port, 1, InetAddress.getLoopbackAddress).close()
      // ...and free on the wildcard address of a non-loopback interface, which a wildcard bind would have taken
      val nonLoopback = java.net.NetworkInterface.networkInterfaces
        .flatMap(_.inetAddresses)
        .filter(a => !a.isLoopbackAddress && a.isInstanceOf[java.net.Inet4Address])
        .findFirst()
      assume(nonLoopback.isPresent, "the host has no non-loopback IPv4 address")
      val other = new ServerSocket(port, 1, nonLoopback.get)
      other.close()
    finally metrics.close()
  }

  it should "not start at all when the endpoint is served over TLS" taggedAs UnitTest in {
    val port = freePort()
    val metrics = Metrics("test", new SimpleMeterRegistry(), port, plaintextServer = false)
    metrics.start()
    try new ServerSocket(port, 1, InetAddress.getLoopbackAddress).close()
    finally metrics.close()
  }

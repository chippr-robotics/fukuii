package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello.*
import com.chipprbots.ethereum.testing.Tags.*

/** B-199: a Hello's clientId is kept to Hello.MaxClientIdBytes at decode, so no reader holds an unbounded string. */
class HelloClientIdSpec extends AnyFlatSpec with Matchers:

  private def roundTrip(clientId: String): Hello =
    val hello = Hello(5, clientId, Seq(Capability.ETH68), 30303, ByteString(Array.fill[Byte](64)(1)))
    new HelloEnc(hello).toBytes.toHello

  "Hello decoding" should "keep an ordinary clientId whole" taggedAs (UnitTest, NetworkTest) in {
    val id = "Geth/v1.17.9-stable-a1b2c3d4/linux-amd64/go1.25.1"
    roundTrip(id).clientId shouldBe id
  }

  it should "cut a clientId longer than MaxClientIdBytes" taggedAs (UnitTest, NetworkTest) in {
    roundTrip("x" * 100_000).clientId shouldBe "x" * Hello.MaxClientIdBytes
  }

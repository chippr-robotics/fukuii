package com.chipprbots.ethereum.network.rlpx

import org.apache.pekko.util.ByteString

import scala.io.Source

import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.xerial.snappy.Snappy

import com.chipprbots.ethereum.domain.Block._
import com.chipprbots.ethereum.testing.Tags._

class MessageCompressionSpec extends AnyFlatSpec with Matchers with MockFactory {

  it should "decode block compressed by go" taggedAs (UnitTest, NetworkTest) in {
    val testURL = getClass.getResource("/block.go.snappy")
    val res = Source.fromURL(testURL)
    val str = res.getLines().mkString
    val asByteArray = Hex.decode(str)
    val payload = Snappy.uncompress(asByteArray)
    val decoded = payload.toBlock
    decoded.header.hash shouldEqual ByteString(
      Hex.decode("bd64134a158aa767120725614026cc5e614dd67a2cbbcdf72823c97981a08620")
    )
  }
  it should "decode block compressed by python" taggedAs (UnitTest, NetworkTest) in {
    val testURL = getClass.getResource("/block.py.snappy")
    val res = Source.fromURL(testURL)
    val str = res.getLines().mkString
    val asByteArray = Hex.decode(str)
    val payload = Snappy.uncompress(asByteArray)
    val decoded = payload.toBlock
    decoded.header.hash shouldEqual ByteString(
      Hex.decode("bd64134a158aa767120725614026cc5e614dd67a2cbbcdf72823c97981a08620")
    )
  }
}

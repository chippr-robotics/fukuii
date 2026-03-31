package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.MonadicJValue.jvalueToMonadic
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256

class PrestateTracerSpec extends AnyFreeSpec with Matchers {

  private val from = Address(0x1234)
  private val to = Address(0x5678)

  private def worldWith(entries: (Address, Account)*): MockWorldState =
    MockWorldState(accounts = entries.toMap)

  "PrestateTracer" - {
    "default mode should return touched account states" in {
      val account = Account(nonce = 1, balance = UInt256(1000))
      val world = worldWith(from -> account)
      val tracer = new PrestateTracer[MockWorldState, MockStorage](world)

      tracer.onTxStart(from, Some(to), gas = 21000, value = 0, input = ByteString.empty)

      val result = tracer.getResult
      result shouldBe a[JObject]
      val fields = result.asInstanceOf[JObject].obj.map(_._1)
      // from should be present (it has an account), to should not (no account in world)
      fields should have size 1
    }

    "default mode should include balance and nonce" in {
      val account = Account(nonce = 5, balance = UInt256(9999))
      val world = worldWith(from -> account)
      val tracer = new PrestateTracer[MockWorldState, MockStorage](world)

      tracer.onTxStart(from, Some(to), gas = 21000, value = 0, input = ByteString.empty)

      val result = tracer.getResult
      val obj = result.asInstanceOf[JObject]
      val accountJson = obj.obj.head._2
      (accountJson \ "nonce") shouldBe JInt(5)
      (accountJson \ "balance") shouldBe a[JString]
    }

    "diffMode should return pre and post objects" in {
      val preAccount = Account(nonce = 1, balance = UInt256(1000))
      val postAccount = Account(nonce = 2, balance = UInt256(500))
      val preWorld = worldWith(from -> preAccount)
      val postWorld = worldWith(from -> postAccount)

      val tracer = new PrestateTracer[MockWorldState, MockStorage](preWorld, diffMode = true)
      tracer.onTxStart(from, Some(to), gas = 21000, value = 500, input = ByteString.empty)
      tracer.setPostWorld(postWorld)

      val result = tracer.getResult
      (result \ "pre") shouldBe a[JObject]
      (result \ "post") shouldBe a[JObject]
    }

    "diffMode post should only include changed fields" in {
      val preAccount = Account(nonce = 1, balance = UInt256(1000))
      val postAccount = Account(nonce = 1, balance = UInt256(500)) // nonce unchanged, balance changed
      val preWorld = worldWith(from -> preAccount)
      val postWorld = worldWith(from -> postAccount)

      val tracer = new PrestateTracer[MockWorldState, MockStorage](preWorld, diffMode = true)
      tracer.onTxStart(from, Some(to), gas = 21000, value = 500, input = ByteString.empty)
      tracer.setPostWorld(postWorld)

      val result = tracer.getResult
      val postObj = result \ "post"
      val fromHex = "0x" + com.chipprbots.ethereum.utils.Hex.toHexString(from.bytes.toArray)
      val accountPost = postObj \ fromHex
      // balance changed, should be present
      (accountPost \ "balance") shouldBe a[JString]
      // nonce didn't change, should NOT be present
      (accountPost \ "nonce") shouldBe JNothing
    }

    "should track addresses from onCallEnter" in {
      val inner = Address(0xabcd)
      val account = Account(nonce = 0, balance = UInt256(100))
      val world = worldWith(from -> account, to -> account, inner -> account)

      val tracer = new PrestateTracer[MockWorldState, MockStorage](world)
      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = ByteString.empty)
      tracer.onCallEnter("CALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)

      val result = tracer.getResult
      val fields = result.asInstanceOf[JObject].obj.map(_._1)
      fields should have size 3 // from, to, inner
    }

    "should omit accounts that don't exist in world" in {
      val world = worldWith(from -> Account(nonce = 0, balance = UInt256(100)))
      val tracer = new PrestateTracer[MockWorldState, MockStorage](world)

      tracer.onTxStart(from, Some(to), gas = 21000, value = 0, input = ByteString.empty)

      val result = tracer.getResult
      val fields = result.asInstanceOf[JObject].obj.map(_._1)
      // to doesn't exist in world, should be omitted
      fields should have size 1
    }

    "diffMode should show newly created accounts in post" in {
      val preWorld = worldWith(from -> Account(nonce = 1, balance = UInt256(1000)))
      val newAccount = Account(nonce = 0, balance = UInt256(500))
      val postWorld = worldWith(from -> Account(nonce = 2, balance = UInt256(500)), to -> newAccount)

      val tracer = new PrestateTracer[MockWorldState, MockStorage](preWorld, diffMode = true)
      tracer.onTxStart(from, Some(to), gas = 100000, value = 500, input = ByteString.empty)
      tracer.setPostWorld(postWorld)

      val result = tracer.getResult
      val postObj = result \ "post"
      val toHex = "0x" + com.chipprbots.ethereum.utils.Hex.toHexString(to.bytes.toArray)
      // to was created — should appear in post
      (postObj \ toHex) should not be JNothing
    }
  }
}

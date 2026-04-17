package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.MonadicJValue.jvalueToMonadic
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address

class CallTracerSpec extends AnyFreeSpec with Matchers {

  private val from   = Address(0x1234)
  private val to     = Address(0x5678)
  private val input  = ByteString(0x12, 0x34)
  private val output = ByteString(0xab, 0xcd)

  "CallTracer" - {
    "should produce a root CALL frame for a simple transaction" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, Some(to), gas = 21000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 21000, output = output, error = None)

      val result = tracer.getResult
      (result \ "type") shouldBe JString("CALL")
      (result \ "from") shouldBe a[JString]
      (result \ "to") shouldBe a[JString]
      (result \ "gas") shouldBe JInt(21000)
      (result \ "gasUsed") shouldBe JInt(21000)
      (result \ "input") shouldBe a[JString]
      (result \ "output") shouldBe a[JString]
    }

    "should produce a CREATE frame when to is None" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, to = None, gas = 100000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 50000, output = output, error = None)

      val result = tracer.getResult
      (result \ "type") shouldBe JString("CREATE")
    }

    "should build a nested call tree" in {
      val tracer = new CallTracer()
      val inner  = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onCallEnter("STATICCALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 60000, output = output, error = None)

      val result    = tracer.getResult
      val calls     = result \ "calls"
      calls shouldBe a[JArray]
      val callArray = calls.asInstanceOf[JArray].arr
      callArray should have size 1
      (callArray.head \ "type") shouldBe JString("STATICCALL")
      (callArray.head \ "gasUsed") shouldBe JInt(10000)
    }

    "should skip sub-calls when onlyTopCall is true" in {
      val tracer = new CallTracer(onlyTopCall = true)
      val inner  = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onCallEnter("STATICCALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 60000, output = output, error = None)

      val result = tracer.getResult
      (result \ "calls") shouldBe JNothing
    }

    "should include error on failure" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 100000, output = ByteString.empty, error = Some("out of gas"))

      val result = tracer.getResult
      (result \ "error") shouldBe JString("out of gas")
    }

    "should encode gas as decimal integer, not hex" in {
      val tracer = new CallTracer()

      tracer.onTxStart(from, Some(to), gas = 1000000, value = 0, input = input)
      tracer.onTxEnd(gasUsed = 500000, output = output, error = None)

      val result = tracer.getResult
      (result \ "gas") shouldBe JInt(1000000)
      (result \ "gasUsed") shouldBe JInt(500000)
    }

    "should omit value for STATICCALL and DELEGATECALL" in {
      val tracer = new CallTracer()
      val inner  = Address(0xabcd)

      tracer.onTxStart(from, Some(to), gas = 100000, value = 0, input = input)
      tracer.onCallEnter("STATICCALL", to, inner, gas = 50000, value = 0, input = ByteString.empty)
      tracer.onCallExit(gasUsed = 10000, output = output, error = None)
      tracer.onTxEnd(gasUsed = 60000, output = output, error = None)

      val calls = (tracer.getResult \ "calls").asInstanceOf[JArray].arr
      (calls.head \ "value") shouldBe JNothing
    }

    "should return JNull when no transaction was traced" in {
      val tracer = new CallTracer()
      tracer.getResult shouldBe JNull
    }
  }
}

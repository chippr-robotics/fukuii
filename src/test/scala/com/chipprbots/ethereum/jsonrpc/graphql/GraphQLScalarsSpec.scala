package com.chipprbots.ethereum.jsonrpc.graphql

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import sangria.ast

class GraphQLScalarsSpec extends AnyFlatSpec with Matchers {

  import GraphQLScalars._

  // ------ Bytes32 ------
  "Bytes32Type" should "round-trip a 0x-prefixed 32-byte hex string" in {
    val hex = "0x" + ("01" * 32)
    val decoded = Bytes32Type.coerceUserInput(hex).toOption.get
    decoded.length shouldBe 32
    Bytes32Type.coerceOutput(decoded, Set.empty) shouldBe hex
  }

  it should "reject strings that aren't exactly 32 bytes" in {
    Bytes32Type.coerceUserInput("0x" + ("01" * 31)).isLeft shouldBe true
    Bytes32Type.coerceUserInput("0x").isLeft shouldBe true
  }

  it should "reject non-hex strings" in {
    Bytes32Type.coerceUserInput("not a hex").isLeft shouldBe true
    Bytes32Type.coerceUserInput(42).isLeft shouldBe true
  }

  it should "reject AST IntValue" in {
    Bytes32Type.coerceInput(ast.IntValue(42)).isLeft shouldBe true
  }

  // ------ Address ------
  "AddressType" should "round-trip a 20-byte address" in {
    val hex = "0x" + ("ab" * 20)
    val decoded = AddressType.coerceUserInput(hex).toOption.get
    decoded.length shouldBe 20
    AddressType.coerceOutput(decoded, Set.empty) shouldBe hex
  }

  it should "reject non-20-byte hex" in {
    AddressType.coerceUserInput("0x" + ("ab" * 21)).isLeft shouldBe true
  }

  // ------ Bytes (arbitrary length) ------
  "BytesType" should "encode empty bytes as 0x" in {
    BytesType.coerceOutput(ByteString.empty, Set.empty) shouldBe "0x"
  }

  it should "round-trip an arbitrary hex blob" in {
    val hex = "0xdeadbeef"
    val decoded = BytesType.coerceUserInput(hex).toOption.get
    decoded.toArray.toSeq shouldBe Seq(0xde.toByte, 0xad.toByte, 0xbe.toByte, 0xef.toByte)
    BytesType.coerceOutput(decoded, Set.empty) shouldBe hex
  }

  it should "reject odd-length hex" in {
    BytesType.coerceUserInput("0x123").isLeft shouldBe true
  }

  // ------ BigInt ------
  "BigIntType" should "output zero as 0x0" in {
    BigIntType.coerceOutput(BigInt(0), Set.empty) shouldBe "0x0"
  }

  it should "output values without leading zeroes" in {
    BigIntType.coerceOutput(BigInt(255), Set.empty) shouldBe "0xff"
    BigIntType.coerceOutput(BigInt(1), Set.empty) shouldBe "0x1"
  }

  it should "accept decimal strings" in {
    BigIntType.coerceUserInput("100").toOption.get shouldBe BigInt(100)
  }

  it should "accept 0x-hex strings" in {
    BigIntType.coerceUserInput("0xff").toOption.get shouldBe BigInt(255)
  }

  it should "accept integer literals" in {
    BigIntType.coerceUserInput(42: Int).toOption.get shouldBe BigInt(42)
    BigIntType.coerceUserInput(42L).toOption.get shouldBe BigInt(42L)
  }

  it should "accept AST IntValue from GraphQL queries" in {
    BigIntType.coerceInput(ast.IntValue(7)).toOption.get shouldBe BigInt(7)
  }

  // ------ Long ------
  "LongType" should "output zero as 0x0" in {
    LongType.coerceOutput(0L, Set.empty) shouldBe "0x0"
  }

  it should "output large longs" in {
    LongType.coerceOutput(1000000L, Set.empty) shouldBe "0xf4240"
  }

  it should "reject negative inputs" in {
    LongType.coerceUserInput("-1").isLeft shouldBe true
  }

  it should "accept 0x-hex input" in {
    LongType.coerceUserInput("0x10").toOption.get shouldBe 16L
  }

  it should "accept integer literal input" in {
    LongType.coerceUserInput(5: Int).toOption.get shouldBe 5L
    LongType.coerceUserInput(5L).toOption.get shouldBe 5L
  }
}

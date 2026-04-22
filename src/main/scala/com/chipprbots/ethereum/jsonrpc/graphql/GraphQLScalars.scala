package com.chipprbots.ethereum.jsonrpc.graphql

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import sangria.ast
import sangria.schema.ScalarType
import sangria.validation.ValueCoercionViolation

/** Custom scalars for the Ethereum execution-layer GraphQL schema (EIP-1767).
  *
  * All binary outputs are rendered as 0x-prefixed lowercase hex strings. BigInt inputs accept either a JSON number,
  * decimal string, or 0x-hex string; outputs are always 0x-hex. Long inputs accept number, decimal string, or 0x-hex;
  * output is 0x-hex.
  */
object GraphQLScalars {

  // ---- Violation types ----
  private case object Bytes32Violation extends ValueCoercionViolation("Expected 0x-prefixed 32-byte hex string")
  private case object AddressViolation extends ValueCoercionViolation("Expected 0x-prefixed 20-byte hex string")
  private case object BytesViolation extends ValueCoercionViolation("Expected 0x-prefixed even-length hex string")
  private case object BigIntViolation extends ValueCoercionViolation("Expected a decimal or 0x-hex integer")
  private case object LongViolation extends ValueCoercionViolation("Expected an unsigned 64-bit integer")

  // ---- Hex helpers ----
  private def stripHex(s: String): Option[String] =
    if (s.startsWith("0x") || s.startsWith("0X")) Some(s.substring(2))
    else None

  def toHex(bs: ByteString): String =
    "0x" + Hex.toHexString(bs.toArray[Byte])

  def toHexEmptyOk(bs: ByteString): String =
    if (bs.isEmpty) "0x" else toHex(bs)

  def toHexBigInt(n: BigInt): String = {
    // EIP-1767 canonical: no leading zeroes, with "0x0" for zero.
    val raw = n.toString(16)
    "0x" + (if (raw == "0") "0" else raw.dropWhile(_ == '0') match {
      case ""    => "0"
      case other => other
    })
  }

  def toHexLong(n: Long): String = toHexBigInt(BigInt(n))

  private def parseHexBytes(s: String, expectLen: Option[Int]): Option[ByteString] =
    stripHex(s).filter(h => h.length % 2 == 0).flatMap { h =>
      scala.util.Try(ByteString(Hex.decode(h))).toOption.filter(b => expectLen.forall(_ == b.length))
    }

  private def parseBigInt(s: String): Option[BigInt] = {
    val trimmed = s.trim
    stripHex(trimmed) match {
      case Some(hex) => scala.util.Try(BigInt(if (hex.isEmpty) "0" else hex, 16)).toOption
      case None      => scala.util.Try(BigInt(trimmed)).toOption
    }
  }

  // ---- Bytes32 ----
  val Bytes32Type: ScalarType[ByteString] = ScalarType[ByteString](
    name = "Bytes32",
    description = Some("A 32 byte binary string, represented as 0x-prefixed hexadecimal."),
    coerceUserInput = {
      case s: String => parseHexBytes(s, Some(32)).toRight(Bytes32Violation)
      case _         => Left(Bytes32Violation)
    },
    coerceInput = {
      case ast.StringValue(s, _, _, _, _) => parseHexBytes(s, Some(32)).toRight(Bytes32Violation)
      case _                              => Left(Bytes32Violation)
    },
    coerceOutput = (bs, _) => toHex(bs)
  )

  // ---- Address ----
  val AddressType: ScalarType[ByteString] = ScalarType[ByteString](
    name = "Address",
    description = Some("A 20 byte Ethereum address, represented as 0x-prefixed hexadecimal."),
    coerceUserInput = {
      case s: String => parseHexBytes(s, Some(20)).toRight(AddressViolation)
      case _         => Left(AddressViolation)
    },
    coerceInput = {
      case ast.StringValue(s, _, _, _, _) => parseHexBytes(s, Some(20)).toRight(AddressViolation)
      case _                              => Left(AddressViolation)
    },
    coerceOutput = (bs, _) => toHex(bs)
  )

  // ---- Bytes (arbitrary length) ----
  val BytesType: ScalarType[ByteString] = ScalarType[ByteString](
    name = "Bytes",
    description = Some("An arbitrary length binary string, represented as 0x-prefixed hexadecimal."),
    coerceUserInput = {
      case s: String => parseHexBytes(s, None).toRight(BytesViolation)
      case _         => Left(BytesViolation)
    },
    coerceInput = {
      case ast.StringValue(s, _, _, _, _) => parseHexBytes(s, None).toRight(BytesViolation)
      case _                              => Left(BytesViolation)
    },
    coerceOutput = (bs, _) => toHexEmptyOk(bs)
  )

  // ---- BigInt ----
  val BigIntType: ScalarType[BigInt] = ScalarType[BigInt](
    name = "BigInt",
    description = Some(
      "A large integer. Input is accepted as either a JSON number or a string (decimal or 0x-hex). Output values are all 0x-hex."
    ),
    coerceUserInput = {
      case s: String     => parseBigInt(s).toRight(BigIntViolation)
      case i: Int        => Right(BigInt(i))
      case l: Long       => Right(BigInt(l))
      case b: BigInt     => Right(b)
      case d: BigDecimal => Right(d.toBigInt)
      case _             => Left(BigIntViolation)
    },
    coerceInput = {
      case ast.StringValue(s, _, _, _, _) => parseBigInt(s).toRight(BigIntViolation)
      case ast.IntValue(v, _, _)          => Right(BigInt(v))
      case ast.BigIntValue(v, _, _)       => Right(v)
      case _                              => Left(BigIntViolation)
    },
    coerceOutput = (n, _) => toHexBigInt(n)
  )

  // ---- Long ----
  val LongType: ScalarType[Long] = ScalarType[Long](
    name = "Long",
    description = Some("A 64 bit unsigned integer, output as 0x-hex."),
    coerceUserInput = {
      case s: String => parseBigInt(s).filter(_ >= 0).map(_.toLong).toRight(LongViolation)
      case i: Int    => Right(i.toLong)
      case l: Long   => Right(l)
      case b: BigInt => scala.util.Try(b.toLong).toOption.toRight(LongViolation)
      case _         => Left(LongViolation)
    },
    coerceInput = {
      case ast.StringValue(s, _, _, _, _) => parseBigInt(s).filter(_ >= 0).map(_.toLong).toRight(LongViolation)
      case ast.IntValue(v, _, _)          => Right(v.toLong)
      case ast.BigIntValue(v, _, _)       => scala.util.Try(v.toLong).toOption.toRight(LongViolation)
      case _                              => Left(LongViolation)
    },
    coerceOutput = (n, _) => toHexLong(n)
  )
}

package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.util.boundary
import scala.util.boundary.break

/** The reason a revert payload states, as go-ethereum's `abi.UnpackRevert` (accounts/abi/abi.go) reads it: the string
  * of a Solidity `Error(string)` (selector 0x08c379a0), or the text of a `Panic(uint256)` (selector 0x4e487b71) for its
  * code. Any other payload (a custom error, raw bytes) has none, and the caller says only "execution reverted".
  *
  * One decoder for every place that words a revert (B-026): eth_call and eth_estimateGas (`execution reverted:
  * <reason>`, internal/ethapi `revertError`), eth_simulateV1, and callTracer's `revertReason`.
  */
object RevertReason:

  private val ErrorSelector: ByteString = ByteString(0x08, 0xc3, 0x79, 0xa0)
  private val PanicSelector: ByteString = ByteString(0x4e, 0x48, 0x7b, 0x71)

  /** go-ethereum `panicReasons`, keyed by the Solidity panic code. */
  private val PanicReasons: Map[BigInt, String] = Map(
    BigInt(0x00) -> "generic panic",
    BigInt(0x01) -> "assert(false)",
    BigInt(0x11) -> "arithmetic underflow or overflow",
    BigInt(0x12) -> "division or modulo by zero",
    BigInt(0x21) -> "enum overflow",
    BigInt(0x22) -> "invalid encoded storage byte array accessed",
    BigInt(0x31) -> "out-of-bounds array access; popping on an empty array",
    BigInt(0x32) -> "out-of-bounds access of an array or bytesN",
    BigInt(0x41) -> "out of memory",
    BigInt(0x51) -> "uninitialized function"
  )

  /** The reason `data` states, or None when it is neither an `Error(string)` nor a `Panic(uint256)`. */
  def decode(data: ByteString): Option[String] =
    if data.length < 4 then None
    else
      val selector = data.take(4)
      if selector == ErrorSelector then errorString(data.drop(4))
      else if selector == PanicSelector then panic(data.drop(4))
      else None

  /** `execution reverted`, with `: <reason>` when the payload states one (go-ethereum `revertError`). */
  def message(data: ByteString): String =
    decode(data).fold("execution reverted")(r => s"execution reverted: $r")

  // ABI: head[0] = offset (0x20 for a single string), then the length (a uint256 the contract chose) and the bytes.
  private def errorString(payload: ByteString): Option[String] =
    boundary:
      if payload.length < 64 then break(None)
      val offset = BigInt(1, payload.slice(0, 32).toArray)
      if offset != 32 then break(None)
      // Compared as a BigInt, so 2^32 + 5 is not 5 and a length near 2^31 does not overflow `64 + length` (#1727).
      val length = BigInt(1, payload.slice(32, 64).toArray)
      if length + 64 > payload.length then break(None)
      scala.util.Try(new String(payload.slice(64, 64 + length.toInt).toArray, "UTF-8")).toOption

  // go-ethereum: the reason for a known code, else "unknown panic code: %#x".
  private def panic(payload: ByteString): Option[String] =
    if payload.length < 32 then None
    else
      val code = BigInt(1, payload.slice(0, 32).toArray)
      Some(PanicReasons.getOrElse(code, s"unknown panic code: 0x${code.toString(16)}"))

package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.mpt.ByteArrayEncoder
import com.chipprbots.ethereum.utils.ByteUtils.padLeft

object Address:

  val Length = 20

  implicit val hashedAddressEncoder: ByteArrayEncoder[Address] = new ByteArrayEncoder[Address]:
    override def toBytes(addr: Address): Array[Byte] = crypto.kec256(addr.toArray)

  def apply(bytes: ByteString): Address =
    val truncated = bytes.takeRight(Length)
    val extended = padLeft(truncated, Length)
    new Address(extended)

  def apply(uint: UInt256): Address = Address(uint.bytes)

  def apply(bigInt: BigInt): Address = Address(UInt256(bigInt))

  def apply(arr: Array[Byte]): Address = Address(ByteString(arr))

  def apply(addr: Long): Address = Address(UInt256(addr))

  def apply(hexString: String): Address =
    val bytes = Hex.decode(hexString.replaceFirst("^0x", ""))
    require(bytes.nonEmpty && bytes.length <= Length, s"Invalid address: $hexString")
    Address(bytes)

  def apply(keyPair: AsymmetricCipherKeyPair): Address =
    val pub = crypto.pubKeyFromKeyPair(keyPair)
    Address(crypto.kec256(pub))

class Address private (val bytes: ByteString):

  def toArray: Array[Byte] = bytes.toArray

  def toUInt256: UInt256 = UInt256(bytes)

  override def equals(that: Any): Boolean =
    that match // §3h: FORGE-confirmed — java.lang.Object.equals signature is fixed by JVM
      case addr: Address => addr.bytes == bytes
      case _             => false

  // Computed once: `ByteString.hashCode` is a MurmurHash over the bytes that boxes each one, and execution hashes the
  // same addresses on every Set/Map lookup (accessed-address sets, world-state maps, read memos) — 15% of import CPU in
  // a profile of the Platåberget soak (2026-10-04). Same value as before, so hash-ordered iteration is unchanged.
  override val hashCode: Int =
    bytes.hashCode

  override def toString: String =
    s"0x$toUnprefixedString"

  def toUnprefixedString: String =
    Hex.toHexString(toArray)

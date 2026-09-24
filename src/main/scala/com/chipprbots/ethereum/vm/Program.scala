package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec
import scala.collection.immutable.BitSet

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.utils.ByteStringUtils.Padding

/** Holds a program's code and provides utilities for accessing it (defaulting to zeroes when out of scope)
  *
  * @param code
  *   the EVM bytecode as bytes
  */
case class Program(code: ByteString):

  /** The byte at `pc`, or 0 (STOP) outside the code. Read once per executed instruction, so it indexes the code
    * directly rather than through `code.lift(pc)`, which allocates a lifted function and an `Option` for the same
    * answer.
    */
  def getByte(pc: Int): Byte =
    if pc >= 0 && pc < length then code(pc) else 0

  def getBytes(from: Int, size: Int): ByteString =
    code.slice(from, from + size).padToByteString(size, 0.toByte)

  val length: Int = code.size

  /** The valid jump destinations of the program. See section 9.4.3 in Yellow Paper for more detail.
    *
    * A bit set, one bit per code byte, like go-ethereum's `bitvec` code analysis. Every CALL frame builds its own
    * `Program`, and a contract that recurses into itself holds one of these per frame. With a `HashSet[Int]` that cost
    * ~45 bytes per JUMPDEST: ethereum/tests `JUMPDEST_AttackwithJump` (15 KB of JUMPDESTs, 1,024 self-calls deep on
    * Homestead, which has no 63/64 rule) kept 681 MB live and died at `-Xmx512m`. Membership is unchanged.
    */
  lazy val validJumpDestinations: Set[Int] = validJumpDestinationsAfterPosition(0)

  /** Returns the valid jump destinations of the program after a given position.
    *
    * @param start
    *   from where to start searching for valid jump destinations in the code.
    */
  private def validJumpDestinationsAfterPosition(start: Int): BitSet =
    val bits = new Array[Long]((length + 63) >>> 6)
    @tailrec
    def scan(pos: Int): Unit =
      if pos >= 0 && pos < length then
        val byte = code(pos)
        // we only need to check PushOp and JUMPDEST, they are both present in Frontier
        val opCode = EvmConfig.FrontierOpCodes.opCodeFor(byte)
        opCode match
          case Some(pushOp: PushOp) => scan(pos + pushOp.i + 2)
          case Some(JUMPDEST) =>
            bits(pos >>> 6) |= 1L << pos
            scan(pos + 1)
          case _ => scan(pos + 1)
    scan(start)
    BitSet.fromBitMaskNoCopy(bits) // `bits` never escapes otherwise

  lazy val codeHash: ByteString =
    kec256(code)

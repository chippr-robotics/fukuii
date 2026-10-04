package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.collection.immutable.BitSet

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.UInt256
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

  /** The unsigned big-endian value of the `size` bytes at `from`, bytes past the end of the code reading as zero — that
    * is, `UInt256(getBytes(from, size))` — read byte by byte instead of through a slice, a padded copy and a
    * shift-and-add per byte on BigInt. PUSH1..PUSH32 decode their immediate here.
    *
    * @param size
    *   0 to 32. Up to 7 bytes the value is accumulated in a Long, which cannot overflow or go negative at that width.
    */
  def immediate(from: Int, size: Int): UInt256 =
    if size <= 7 then
      var value = 0L
      var k = 0
      while k < size do
        value = (value << 8) | (getByte(from + k) & 0xff)
        k += 1
      UInt256(value)
    else
      val bytes = new Array[Byte](size)
      var k = 0
      while k < size do
        bytes(k) = getByte(from + k)
        k += 1
      UInt256(BigInt(1, bytes))

  val length: Int = code.size

  /** The hash of `code` when the caller knows it for free (the account's `codeHash` in the world state). Set only by
    * [[Program.withCodeHash]]; it lets the JUMPDEST analysis come from [[JumpDestAnalysis.shared]]. It is not part of
    * the case class's identity: two programs with the same code are equal whether or not either carries a hash.
    */
  private var knownCodeHash: ByteString = null

  /** The executing block's analysis memo, when the world has one; set only by [[Program.withCodeHash]]. */
  private var blockMemo: JumpDestAnalysis.BlockMemo = null

  /** The valid jump destinations of the program. See section 9.4.3 in Yellow Paper for more detail.
    *
    * A bit set, one bit per code byte, like go-ethereum's `bitvec` code analysis (a HashSet cost ~45 bytes per
    * JUMPDEST: ethereum/tests `JUMPDEST_AttackwithJump` kept 681 MB live at -Xmx512m). Every CALL frame builds its own
    * `Program`, so the analysis is memoised across frames by code hash when the hash is known (see
    * [[JumpDestAnalysis]]); otherwise it is computed here, once per `Program`. Membership is identical either way.
    */
  lazy val validJumpDestinations: BitSet =
    val hash = knownCodeHash
    if hash == null then ImportProfile.scan(length)(JumpDestAnalysis.analyse(code))
    else if blockMemo != null then blockMemo.getOrCompute(hash, code)
    else JumpDestAnalysis.shared.getOrCompute(hash, code)

  lazy val codeHash: ByteString =
    kec256(code)

object Program:

  /** A program whose code is known to hash to `codeHash`, so its JUMPDEST analysis can be shared. The caller vouches
    * for the hash: pass only the `codeHash` the world state holds for exactly this code.
    */
  def withCodeHash(
      code: ByteString,
      codeHash: ByteString,
      memo: Option[JumpDestAnalysis.BlockMemo] = None
  ): Program =
    val program = Program(code)
    program.knownCodeHash = codeHash
    program.blockMemo = memo.orNull
    program

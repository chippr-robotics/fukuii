package com.chipprbots.ethereum.vm

import java.nio.charset.StandardCharsets
import java.util.Locale

import org.apache.pekko.util.ByteString

import com.sun.jna.ptr.IntByReference
import org.bouncycastle.util.encoders.Hex
import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
import org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537

/** The seven EIP-2537 operations at their final-spec addresses (0x0b-0x11), in our own numbering. Each backend maps an
  * op to its library's id, and the libraries disagree: eth_pairings still numbers the pre-final G1MUL/G2MUL ops (G1ADD
  * 1, G1MSM 3, G2ADD 4, G2MSM 6, PAIRING 7, MAP 8/9), gnark's shim numbers 1..7.
  *
  * `unit` is the whole input for a fixed-length op and one (point, scalar) or (G1, G2) pair for a variable-length one.
  */
enum Eip2537Op(val label: String, val address: Int, val unit: Int, val variableLength: Boolean):
  case G1Add extends Eip2537Op("G1ADD", 0x0b, 256, false)
  case G1Msm extends Eip2537Op("G1MSM", 0x0c, 160, true)
  case G2Add extends Eip2537Op("G2ADD", 0x0d, 512, false)
  case G2Msm extends Eip2537Op("G2MSM", 0x0e, 288, true)
  case Pairing extends Eip2537Op("PAIRING", 0x0f, 384, true)
  case MapFpToG1 extends Eip2537Op("MAP_FP_TO_G1", 0x10, 64, false)
  case MapFp2ToG2 extends Eip2537Op("MAP_FP2_TO_G2", 0x11, 128, false)

/** What one backend returned for one input. The error text is kept for the report and never compared: libraries word
  * their errors differently, and EIP-2537 only distinguishes success from failure (all gas is burned on failure).
  */
enum Eip2537Outcome:
  case Success(output: ByteString)
  case Failure(detail: String)

  def agreesWith(other: Eip2537Outcome): Boolean = (this, other) match
    case (Success(a), Success(b)) => a == b
    case (Failure(_), Failure(_)) => true
    case _                        => false

  def render: String = this match
    case Success(out)    => s"OK (${out.length} bytes) 0x${Hex.toHexString(out.toArray)}"
    case Failure(detail) => s"ERROR ($detail)"

/** One EIP-2537 implementation the differential can call. A third backend (B-151's blst) implements this and is added
  * to the spec's backend list; nothing else changes.
  */
trait Eip2537Backend:
  def name: String

  /** `None` when the native library is usable; otherwise why not. Reading it initialises the library. */
  def loadFailure: Option[String]

  def run(op: Eip2537Op, input: ByteString): Eip2537Outcome

/** What a fukuii node executes today: the `PrecompiledContracts` BLS objects, which call Matter Labs' eth_pairings
  * through `org.hyperledger.besu:bls12-381:1.0.0`. `exec` is the call `PrecompiledContract.run` makes once the gas is
  * paid; gas is BlsMsmGasSpec's job, not this one's.
  */
object ProductionEip2537Backend extends Eip2537Backend:
  val name: String = "fukuii PrecompiledContracts (eth_pairings via bls12-381:1.0.0)"

  def loadFailure: Option[String] =
    Option.unless(LibEthPairings.ENABLED)("LibEthPairings.ENABLED is false: libeth_pairings did not load")

  /** The contract object for `op`; the spec checks that each EIP-2537 address dispatches to it. */
  def contract(op: Eip2537Op): PrecompiledContracts.PrecompiledContract = op match
    case Eip2537Op.G1Add      => PrecompiledContracts.BlsG1Add
    case Eip2537Op.G1Msm      => PrecompiledContracts.BlsG1MultiExp
    case Eip2537Op.G2Add      => PrecompiledContracts.BlsG2Add
    case Eip2537Op.G2Msm      => PrecompiledContracts.BlsG2MultiExp
    case Eip2537Op.Pairing    => PrecompiledContracts.BlsPairing
    case Eip2537Op.MapFpToG1  => PrecompiledContracts.BlsMapG1
    case Eip2537Op.MapFp2ToG2 => PrecompiledContracts.BlsMapG2

  def run(op: Eip2537Op, input: ByteString): Eip2537Outcome =
    val result = op match
      case Eip2537Op.G1Add      => PrecompiledContracts.BlsG1Add.exec(input)
      case Eip2537Op.G1Msm      => PrecompiledContracts.BlsG1MultiExp.exec(input)
      case Eip2537Op.G2Add      => PrecompiledContracts.BlsG2Add.exec(input)
      case Eip2537Op.G2Msm      => PrecompiledContracts.BlsG2MultiExp.exec(input)
      case Eip2537Op.Pairing    => PrecompiledContracts.BlsPairing.exec(input)
      case Eip2537Op.MapFpToG1  => PrecompiledContracts.BlsMapG1.exec(input)
      case Eip2537Op.MapFp2ToG2 => PrecompiledContracts.BlsMapG2.exec(input)
    result match
      case Some(out) => Eip2537Outcome.Success(out)
      case None      => Eip2537Outcome.Failure("exec returned None")

/** gnark-crypto through besu-native `gnark:2.1.0`, called the way Besu's `AbstractBLS12PrecompiledContract` calls it:
  * the matter-labs-compatible shim with gnark's own op ids, zero-initialised buffers, success iff the return code is 0.
  * Besu also cuts the input to `inputLen + 1` bytes first. That never changes the verdict, because any input longer
  * than the expected length is an error either way, so the full input is passed.
  */
object GnarkEip2537Backend extends Eip2537Backend:
  val name: String = "gnark (besu-native gnark:2.1.0, LibGnarkEIP2537)"

  def loadFailure: Option[String] =
    Option.unless(LibGnarkEIP2537.ENABLED)(
      "LibGnarkEIP2537.ENABLED is false: libgnark_eip_2537 did not load (the loader's stack trace is on stderr)"
    )

  private def shimOp(op: Eip2537Op): Byte = op match
    case Eip2537Op.G1Add      => LibGnarkEIP2537.BLS12_G1ADD_OPERATION_SHIM_VALUE
    case Eip2537Op.G1Msm      => LibGnarkEIP2537.BLS12_G1MULTIEXP_OPERATION_SHIM_VALUE
    case Eip2537Op.G2Add      => LibGnarkEIP2537.BLS12_G2ADD_OPERATION_SHIM_VALUE
    case Eip2537Op.G2Msm      => LibGnarkEIP2537.BLS12_G2MULTIEXP_OPERATION_SHIM_VALUE
    case Eip2537Op.Pairing    => LibGnarkEIP2537.BLS12_PAIR_OPERATION_SHIM_VALUE
    case Eip2537Op.MapFpToG1  => LibGnarkEIP2537.BLS12_MAP_FP_TO_G1_OPERATION_SHIM_VALUE
    case Eip2537Op.MapFp2ToG2 => LibGnarkEIP2537.BLS12_MAP_FP2_TO_G2_OPERATION_SHIM_VALUE

  def run(op: Eip2537Op, input: ByteString): Eip2537Outcome =
    val in = input.toArray
    val out = new Array[Byte](LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_RESULT_BYTES)
    val err = new Array[Byte](LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_ERROR_BYTES)
    val outLen = new IntByReference(out.length)
    val errLen = new IntByReference(err.length)
    val rc = LibGnarkEIP2537.eip2537_perform_operation(shimOp(op), in, in.length, out, outLen, err, errLen)
    if rc == 0 then Eip2537Outcome.Success(ByteString.fromArray(out, 0, outLen.getValue))
    else Eip2537Outcome.Failure(new String(err, 0, errLen.getValue, StandardCharsets.UTF_8))

/** Where the gnark native library exists. besu-native gnark 2.1.0 ships `lib-native/{x86-64,aarch64}` as `.so` and
  * `.dylib` only; there is no Windows build. On other platforms the differential is cancelled; on these a load failure
  * fails it.
  */
object Eip2537Platform:
  val os: String = System.getProperty("os.name", "unknown")
  val arch: String = System.getProperty("os.arch", "unknown")

  val gnarkShipsBinary: Boolean =
    val o = os.toLowerCase(Locale.ROOT)
    val a = arch.toLowerCase(Locale.ROOT)
    (o.startsWith("linux") || o.startsWith("mac")) && Set("amd64", "x86_64", "aarch64").contains(a)

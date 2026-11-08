package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.util.control.NonFatal

import com.sun.jna.ptr.IntByReference
import ethereum.ckzg4844.CKZG4844JNI
import ethereum.ckzg4844.CKZGException
import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings

import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks

/** The native libraries behind some precompiles, and the rule that a missing one never becomes a precompile result.
  *
  * The BLS12-381 precompiles (EIP-2537, 0x0b-0x11) run on Besu's `libeth_pairings` through JNA. KZG point evaluation
  * (EIP-4844, 0x0a) runs on c-kzg-4844 through JNI, with the trusted setup loaded into a native global. Either can be
  * missing on a running node. `LibEthPairings` catches its own load failure and reports it only as `ENABLED = false`,
  * and Besu ships no Windows build of the library. c-kzg throws `RuntimeException: Trusted Setup is not loaded.` until
  * the setup is loaded. The precompiles read both as an ordinary failure: the call consumed all its gas and returned
  * nothing where every other client returns the result, so the node computed a different state root and forked itself
  * off the network without logging anything (B-135).
  *
  * Two guards close that:
  *   - at startup, [[selfCheck]] probes every backend a configured fork needs ([[required]]), and `Fukuii.main` exits
  *     with the operator message when one is not usable;
  *   - at call time, a backend that is missing or broken raises [[NativeBackendUnavailable]], never a precompile
  *     failure.
  *
  * An input the library itself rejects is still an ordinary precompile failure. With the libraries present, every
  * precompile output and gas charge is the same as before.
  */
object NativeBackends:

  /** A native library behind one or more precompiles. */
  enum Backend(val library: String, val precompiles: String):
    case Bls12381
        extends Backend(
          "bls12-381 (org.hyperledger.besu:bls12-381, native library libeth_pairings)",
          "EIP-2537 BLS12-381 precompiles 0x0b-0x11"
        )
    case Kzg
        extends Backend(
          "c-kzg-4844 (io.consensys.protocols:jc-kzg-4844, native library ckzg4844jni) with the KZG trusted setup",
          "EIP-4844 point-evaluation precompile 0x0a"
        )

  /** A precompile's native backend is missing or broken, so this node cannot compute the network's result.
    *
    * A [[LinkageError]] on purpose: a missing native library is one, and `scala.util.control.NonFatal` treats linkage
    * errors as fatal. The `case NonFatal(e)` handlers on the import and RPC paths let it through, and Pekko
    * (`jvm-exit-on-fatal-error`) and Cats Effect stop instead of carrying on with a wrong result.
    */
  final class NativeBackendUnavailable(val backend: Backend, val detail: String, cause: Option[Throwable])
      extends LinkageError(
        s"${backend.library} is unavailable: $detail. It runs the ${backend.precompiles}; without it this node " +
          "would compute different results from the network, so it stops instead of answering"
      ):
    cause.foreach(initCause)

  // ── Which backends the configured forks need ───────────────────────────────────────────────────────────────────

  /** Fork activation blocks at or above 10^18 are not scheduled. 10^18 is the genesis-JSON placeholder that ETC and
    * Mordor carry for Olympia, and Long.MaxValue is the default for an absent key. `ForkId` treats both as unscheduled
    * too.
    */
  private val NotScheduled: BigInt = BigInt(10).pow(18)

  /** The backends some configured fork's precompile set needs, decided the way [[PrecompiledContracts.getContracts]]
    * decides that set:
    *   - ETH Prague and Osaka (timestamps): BLS12-381 and KZG;
    *   - ETC Olympia (ECIP-1121), once the block dispatch reaches it at a scheduled block: the Osaka set, so both;
    *   - ETH Cancun (timestamp): KZG.
    *
    * Each fork's set contains the previous fork's, so a backend that some fork needs is needed from that fork on.
    */
  def required(config: BlockchainConfig): Set[Backend] =
    val timestamps = config.forkTimestamps
    val evm = BlockchainConfigForEvm(config)
    val olympia = config.forkBlockNumbers.all.exists(block =>
      block < NotScheduled && evm.etcForkForBlockNumber(block) >= EtcForks.Olympia
    )
    val bls = olympia || timestamps.pragueTimestamp.isDefined || timestamps.osakaTimestamp.isDefined
    val kzg = bls || timestamps.cancunTimestamp.isDefined
    Backend.values.toSet.filter {
      case Backend.Bls12381 => bls
      case Backend.Kzg      => kzg
    }

  /** The operator message for each backend in `required` whose probe fails. Empty when all of them work. */
  def selfCheck(required: Set[Backend], probe: Backend => Either[String, Unit] = probeBackend): List[String] =
    Backend.values.toList.filter(required.contains).flatMap { backend =>
      probe(backend).left.toOption.map { reason =>
        s"${backend.library} is required by this chain's fork schedule (it runs the ${backend.precompiles}) but is " +
          s"not usable: $reason. Without it the node would compute different results from the network, so it will not " +
          "start. Run fukuii on a platform the library ships a native build for."
      }
    }

  /** Runs a known-answer operation on `backend` through the same code path its precompiles use. */
  def probeBackend(backend: Backend): Either[String, Unit] =
    try
      backend match
        case Backend.Bls12381 =>
          // G1ADD of the point at infinity to itself: the point at infinity, encoded as 128 zero bytes.
          val output =
            blsOperation(
              LibEthPairingsNative,
              LibEthPairings.BLS12_G1ADD_OPERATION_RAW_VALUE,
              ByteString(new Array[Byte](256))
            )
          Either.cond(
            output.contains(ByteString(new Array[Byte](128))),
            (),
            s"G1ADD of the point at infinity to itself returned $output, not the point at infinity"
          )
        case Backend.Kzg =>
          // The zero polynomial: its commitment and its proof at any z are the point at infinity, and p(0) = 0.
          def infinity = 0xc0.toByte +: new Array[Byte](47)
          Either.cond(
            kzgProofVerifies(CkzgNative, infinity, new Array[Byte](32), new Array[Byte](32), infinity),
            (),
            "it rejected a known-valid proof (the zero polynomial at z = 0)"
          )
    catch case e: NativeBackendUnavailable => Left(Option(e.getCause).fold(e.detail)(cause => s"${e.detail}: $cause"))

  // ── BLS12-381 ──────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Besu's `libeth_pairings` entry point, behind a trait so a test can stand in a missing or failing library. */
  private[vm] trait BlsNative:
    /** False when the library did not load: `LibEthPairings` catches the load error and reports it only here. */
    def enabled: Boolean

    /** `Some(output)` when the library computed the operation, `None` when it rejected the input. */
    def perform(operation: Byte, input: Array[Byte]): Option[Array[Byte]]

  private[vm] object LibEthPairingsNative extends BlsNative:
    def enabled: Boolean = LibEthPairings.ENABLED

    def perform(operation: Byte, input: Array[Byte]): Option[Array[Byte]] =
      val result = new Array[Byte](LibEthPairings.EIP2537_PREALLOCATE_FOR_RESULT_BYTES)
      val error = new Array[Byte](LibEthPairings.EIP2537_PREALLOCATE_FOR_ERROR_BYTES)
      val resultLength = new IntByReference(result.length)
      val errorLength = new IntByReference(error.length)
      val ret =
        LibEthPairings.eip2537_perform_operation(
          operation,
          input,
          input.length,
          result,
          resultLength,
          error,
          errorLength
        )
      if ret == 0 then Some(result.take(resultLength.getValue)) else None

  /** Runs an EIP-2537 operation. `None` when the library rejects the input: an ordinary precompile failure, as before.
    * A library that did not load, or a native call that throws, raises [[NativeBackendUnavailable]].
    */
  private[vm] def blsOperation(native: BlsNative, operation: Byte, input: ByteString): Option[ByteString] =
    if !native.enabled then
      throw NativeBackendUnavailable(
        Backend.Bls12381,
        "the native library did not load (LibEthPairings.ENABLED is false)",
        None
      )
    val output =
      try native.perform(operation, input.toArray)
      catch
        case NonFatal(e)     => throw NativeBackendUnavailable(Backend.Bls12381, "the native call failed", Some(e))
        case e: LinkageError => throw NativeBackendUnavailable(Backend.Bls12381, "the native call failed", Some(e))
    output.map(ByteString(_))

  // ── KZG ────────────────────────────────────────────────────────────────────────────────────────────────────────────

  /** c-kzg-4844's proof verification, behind a trait so a test can stand in a missing or failing library. */
  private[vm] trait KzgNative:
    def verifyKzgProof(commitment: Array[Byte], z: Array[Byte], y: Array[Byte], proof: Array[Byte]): Boolean

  private[vm] object CkzgNative extends KzgNative:
    def verifyKzgProof(commitment: Array[Byte], z: Array[Byte], y: Array[Byte], proof: Array[Byte]): Boolean =
      CKZG4844JNI.verifyKzgProof(commitment, z, y, proof)

  /** Whether `proof` shows that the polynomial `commitment` commits to takes the value `y` at `z`.
    *
    * False when c-kzg ran and rejected the input: a proof that does not verify, or a [[CKZGException]] for a commitment
    * or proof that is not a valid point. That is an ordinary precompile failure, as before. Anything else c-kzg throws
    * means the backend itself is missing (`RuntimeException: Trusted Setup is not loaded.`, or an
    * `UnsatisfiedLinkError` when the library did not load) and raises [[NativeBackendUnavailable]].
    */
  private[vm] def kzgProofVerifies(
      native: KzgNative,
      commitment: Array[Byte],
      z: Array[Byte],
      y: Array[Byte],
      proof: Array[Byte]
  ): Boolean =
    try native.verifyKzgProof(commitment, z, y, proof)
    catch
      case _: CKZGException => false
      case NonFatal(e)      => throw NativeBackendUnavailable(Backend.Kzg, "c-kzg could not run", Some(e))
      case e: LinkageError  => throw NativeBackendUnavailable(Backend.Kzg, "c-kzg could not run", Some(e))

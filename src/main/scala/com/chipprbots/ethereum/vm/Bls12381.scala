package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.utils.Logger

/** Thin Scala bridge to Besu's gnark-crypto JNI library for BLS12-381 operations (EIP-2537).
  *
  * The native library is loaded from the gnark JAR which embeds platform-specific .so/.dylib files. All 7 EIP-2537
  * operations are routed through a single native entry point with an operation ID.
  */
object Bls12381 extends Logger {

  import org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537

  private val ResultBufferSize = LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_RESULT_BYTES
  private val ErrorBufferSize = LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_ERROR_BYTES

  val isAvailable: Boolean =
    try {
      val enabled = LibGnarkEIP2537.ENABLED
      if (!enabled) log.warn("BLS12-381 native library not enabled")
      enabled
    } catch {
      case e: UnsatisfiedLinkError =>
        log.warn("BLS12-381 native library not available: {}", e.getMessage)
        false
      case e: NoClassDefFoundError =>
        log.warn("BLS12-381 native library class not found: {}", e.getMessage)
        false
    }

  def g1Add(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_G1ADD_OPERATION_SHIM_VALUE, input)

  def g1MultiExp(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_G1MULTIEXP_OPERATION_SHIM_VALUE, input)

  def g2Add(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_G2ADD_OPERATION_SHIM_VALUE, input)

  def g2MultiExp(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_G2MULTIEXP_OPERATION_SHIM_VALUE, input)

  def pairing(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_PAIR_OPERATION_SHIM_VALUE, input)

  def mapFpToG1(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_MAP_FP_TO_G1_OPERATION_SHIM_VALUE, input)

  def mapFp2ToG2(input: Array[Byte]): Option[Array[Byte]] =
    perform(LibGnarkEIP2537.BLS12_MAP_FP2_TO_G2_OPERATION_SHIM_VALUE, input)

  private def perform(opId: Byte, input: Array[Byte]): Option[Array[Byte]] = {
    if (!isAvailable) return None

    val result = new Array[Byte](ResultBufferSize)
    val resultLen = new com.sun.jna.ptr.IntByReference(0)
    val error = new Array[Byte](ErrorBufferSize)
    val errorLen = new com.sun.jna.ptr.IntByReference(0)

    val rc = LibGnarkEIP2537.eip2537_perform_operation(
      opId,
      input,
      input.length,
      result,
      resultLen,
      error,
      errorLen
    )

    if (rc == 0) Some(java.util.Arrays.copyOf(result, resultLen.getValue))
    else None
  }
}

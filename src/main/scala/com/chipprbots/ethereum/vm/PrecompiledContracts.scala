package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.crypto.Secp256r1
import com.chipprbots.ethereum.crypto.zksnark.BN128.BN128G1
import com.chipprbots.ethereum.crypto.zksnark.BN128.BN128G2
import com.chipprbots.ethereum.crypto.zksnark.BN128Fp
import com.chipprbots.ethereum.crypto.zksnark.PairingCheck
import com.chipprbots.ethereum.crypto.zksnark.PairingCheck.G1G2Pair
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.utils.ByteStringUtils._
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks.EtcFork
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks.EthFork

// scalastyle:off magic.number
object PrecompiledContracts {

  val EcDsaRecAddr: Address = Address(1)
  val Sha256Addr: Address = Address(2)
  val Rip160Addr: Address = Address(3)
  val IdAddr: Address = Address(4)
  val ModExpAddr: Address = Address(5)
  val Bn128AddAddr: Address = Address(6)
  val Bn128MulAddr: Address = Address(7)
  val Bn128PairingAddr: Address = Address(8)
  val Blake2bCompressionAddr: Address = Address(9)

  // EIP-2537: BLS12-381 precompile addresses (final spec: 7 precompiles at 0x0b-0x11)
  // G1MUL/G2MUL removed — MSM at k=1 covers single-point multiplication
  val BlsG1AddAddr: Address = Address(0x0b)
  val BlsG1MultiExpAddr: Address = Address(0x0c)
  val BlsG2AddAddr: Address = Address(0x0d)
  val BlsG2MultiExpAddr: Address = Address(0x0e)
  val BlsPairingAddr: Address = Address(0x0f)
  val BlsMapG1Addr: Address = Address(0x10)
  val BlsMapG2Addr: Address = Address(0x11)

  // EIP-7951: P256VERIFY precompile address
  val P256VerifyAddr: Address = Address(0x100)

  val contracts: Map[Address, PrecompiledContract] = Map(
    EcDsaRecAddr -> EllipticCurveRecovery,
    Sha256Addr -> Sha256,
    Rip160Addr -> Ripemp160,
    IdAddr -> Identity
  )

  val byzantiumAtlantisContracts: Map[Address, PrecompiledContract] = contracts ++ Map(
    ModExpAddr -> ModExp,
    Bn128AddAddr -> Bn128Add,
    Bn128MulAddr -> Bn128Mul,
    Bn128PairingAddr -> Bn128Pairing
  )

  val istanbulPhoenixContracts: Map[Address, PrecompiledContract] = byzantiumAtlantisContracts ++ Map(
    Blake2bCompressionAddr -> Blake2bCompress
  )

  val olympiaContracts: Map[Address, PrecompiledContract] = istanbulPhoenixContracts ++ Map(
    BlsG1AddAddr -> BlsG1Add,
    BlsG1MultiExpAddr -> BlsG1MultiExp,
    BlsG2AddAddr -> BlsG2Add,
    BlsG2MultiExpAddr -> BlsG2MultiExp,
    BlsPairingAddr -> BlsPairing,
    BlsMapG1Addr -> BlsMapG1,
    BlsMapG2Addr -> BlsMapG2,
    P256VerifyAddr -> P256Verify
  )

  /** Checks whether `ProgramContext#recipientAddr` points to a precompiled contract
    */
  def isDefinedAt(context: ProgramContext[_, _]): Boolean =
    getContract(context).isDefined

  /** Runs a contract for address provided in `ProgramContext#recipientAddr` Will throw an exception if the address does
    * not point to a precompiled contract - callers should first check with `isDefinedAt`
    */
  def run[W <: WorldStateProxy[W, S], S <: Storage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] =
    getContract(context)
      .getOrElse(
        throw new IllegalStateException("Precompiled contract not found for address")
      )
      .run(context)

  private def getContract(context: ProgramContext[_, _]): Option[PrecompiledContract] =
    context.recipientAddr.flatMap { addr =>
      getContracts(context).get(addr)
    }

  def getContracts(context: ProgramContext[_, _]): Map[Address, PrecompiledContract] = {
    val ethFork = context.evmConfig.blockchainConfig.ethForkForBlockNumber(context.blockHeader.number)
    val etcFork = context.evmConfig.blockchainConfig.etcForkForBlockNumber(context.blockHeader.number)

    if (etcFork >= EtcForks.Olympia) {
      olympiaContracts
    } else if (ethFork >= EthForks.Istanbul || etcFork >= EtcForks.Phoenix) {
      istanbulPhoenixContracts
    } else if (ethFork >= EthForks.Byzantium || etcFork >= EtcForks.Atlantis) {
      // byzantium and atlantis hard fork introduce the same set of precompiled contracts
      byzantiumAtlantisContracts
    } else
      contracts
  }

  sealed trait PrecompiledContract {
    protected def exec(inputData: ByteString): Option[ByteString]
    protected def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt

    def run[W <: WorldStateProxy[W, S], S <: Storage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] = {

      val ethFork = context.evmConfig.blockchainConfig.ethForkForBlockNumber(context.blockHeader.number)
      val etcFork = context.evmConfig.blockchainConfig.etcForkForBlockNumber(context.blockHeader.number)

      val g = gas(context.inputData, etcFork, ethFork)

      val (result, error, gasRemaining): (ByteString, Option[ProgramError], BigInt) = (
        if (g <= context.startGas)
          exec(context.inputData) match {
            case Some(returnData) => (returnData, None, context.startGas - g)
            case None             => (ByteString.empty, Some(PreCompiledContractFail), BigInt(0))
          }
        else
          (ByteString.empty, Some(OutOfGas), BigInt(0))
      ): @unchecked

      ProgramResult(
        result,
        gasRemaining,
        context.world,
        Set.empty,
        Nil,
        Nil,
        0,
        error,
        Set.empty,
        Set.empty
      )
    }
  }

  object EllipticCurveRecovery extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] = {
      val data: ByteString = inputData.padToByteString(128, 0.toByte)
      val h = data.slice(0, 32)
      val v = data.slice(32, 64)
      val r = data.slice(64, 96)
      val s = data.slice(96, 128)

      if (hasOnlyLastByteSet(v)) {
        val recovered = Try(ECDSASignature(r, s, v.last).publicKey(h)).getOrElse(None)
        Some(
          recovered
            .map { bytes =>
              val hash = kec256(bytes).slice(12, 32)
              ByteUtils.padLeft(hash, 32)
            }
            .getOrElse(ByteString.empty)
        )
      } else
        Some(ByteString.empty)

    }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(3000)

    private def hasOnlyLastByteSet(v: ByteString): Boolean =
      v.dropWhile(_ == 0).size == 1
  }

  object Sha256 extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Some(sha256(inputData))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      BigInt(60) + BigInt(12) * wordsForBytes(inputData.size)
  }

  object Ripemp160 extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Some(ByteUtils.padLeft(ripemd160(inputData), 32))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      BigInt(600) + BigInt(120) * wordsForBytes(inputData.size)
  }

  object Identity extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Some(inputData)

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      BigInt(15) + BigInt(3) * wordsForBytes(inputData.size)
  }

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-198.md
  object ModExp extends PrecompiledContract {

    private val lengthBytes = 32
    private val totalLengthBytes = 3 * lengthBytes

    /** EIP-7823: Maximum operand length in bytes */
    private val maxOperandLength = 1024

    override def run[W <: WorldStateProxy[W, S], S <: Storage[S]](
        context: ProgramContext[W, S]
    ): ProgramResult[W, S] = {
      val etcFork = context.evmConfig.blockchainConfig.etcForkForBlockNumber(context.blockHeader.number)

      // EIP-7823: Post-Olympia, reject inputs with operand lengths > 1024 bytes
      if (etcFork >= EtcForks.Olympia) {
        val baseLength = getLength(context.inputData, 0)
        val expLength = getLength(context.inputData, 1)
        val modLength = getLength(context.inputData, 2)
        if (baseLength > maxOperandLength || expLength > maxOperandLength || modLength > maxOperandLength) {
          return ProgramResult(
            ByteString.empty,
            BigInt(0),
            context.world,
            Set.empty,
            Nil,
            Nil,
            0,
            Some(PreCompiledContractFail),
            Set.empty,
            Set.empty
          )
        }
      }

      super.run(context)
    }

    def exec(inputData: ByteString): Option[ByteString] = {
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val result =
        if (baseLength == 0 && modLength == 0)
          BigInt(0)
        else {
          val mod = getNumber(inputData, safeAdd(totalLengthBytes, safeAdd(baseLength, expLength)), modLength)

          if (mod == 0) {
            BigInt(0)
          } else {
            val base = getNumber(inputData, totalLengthBytes, baseLength)
            val exp = getNumber(inputData, safeAdd(totalLengthBytes, baseLength), expLength)

            base.modPow(exp, mod)
          }
        }
      Some(ByteString(ByteUtils.bigIntegerToBytes(result.bigInteger, modLength)))
    }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = {
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val expBytes =
        inputData.slice(
          safeAdd(totalLengthBytes, baseLength),
          safeAdd(safeAdd(totalLengthBytes, baseLength), expLength)
        )

      if (etcFork >= EtcForks.Olympia)
        PostEIP7883Cost.calculate(baseLength, modLength, expLength, expBytes)
      else if (ethFork >= EthForks.Berlin || etcFork >= EtcForks.Magneto)
        PostEIP2565Cost.calculate(baseLength, modLength, expLength, expBytes)
      else
        PostEIP198Cost.calculate(baseLength, modLength, expLength, expBytes)
    }

    // Spec: https://eips.ethereum.org/EIPS/eip-198
    object PostEIP198Cost {
      private val GQUADDIVISOR = 20

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt = {
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength(expBytes, expLength)
        multComplexity * math.max(adjusted, 1) / GQUADDIVISOR
      }

      private def getMultComplexity(x: BigInt): BigInt = {
        val x2 = x * x
        if (x <= 64)
          x2
        else if (x <= 1024)
          x2 / 4 + 96 * x - 3072
        else
          x2 / 16 + 480 * x - 199680
      }
    }

    // Spec: https://eips.ethereum.org/EIPS/eip-2565
    object PostEIP2565Cost {
      private val GQUADDIVISOR = 3

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt = {
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength(expBytes, expLength)
        val r = multComplexity * math.max(adjusted, 1) / GQUADDIVISOR
        if (r <= 200) 200
        else r
      }

      // ceiling(x/8)^2
      private def getMultComplexity(x: BigInt): BigInt =
        ((x + 7) / 8).pow(2)
    }

    // Spec: https://eips.ethereum.org/EIPS/eip-7883
    // EIP-7883: ModExp gas cost increase — higher multiplication complexity, no divisor, min 500
    object PostEIP7883Cost {

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt = {
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength7883(expBytes, expLength)
        val r = multComplexity * math.max(adjusted, 1)
        if (r < 500) 500
        else r
      }

      // EIP-7883 multiplication complexity:
      // For maxLen <= 32: 16 (flat constant)
      // For maxLen > 32: 2 * ceiling(maxLen/8)^2
      private def getMultComplexity(x: BigInt): BigInt =
        if (x <= 32) BigInt(16)
        else {
          val words = (x + 7) / 8
          2 * words.pow(2)
        }

      // EIP-7883 adjusted exponent length uses multiplier 16 (not 8 like EIP-2565)
      private def adjustExpLength7883(expBytes: ByteString, expLength: Int): Long = {
        val expHead =
          if (expLength <= lengthBytes)
            expBytes.padToByteString(expLength, 0.toByte)
          else
            expBytes.take(lengthBytes).padToByteString(lengthBytes, 0.toByte)

        val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

        if (expLength <= lengthBytes) {
          highestBitIndex
        } else {
          16L * (expLength - lengthBytes) + highestBitIndex
        }
      }
    }

    private def getNumber(bytes: ByteString, offset: Int, length: Int): BigInt = {
      val number = bytes.slice(offset, safeAdd(offset, length)).padToByteString(length, 0.toByte)
      ByteUtils.toBigInt(number)
    }

    private def safeAdd(a: Int, b: Int): Int =
      safeInt(BigInt(a) + BigInt(b))

    private def safeInt(value: BigInt): Int =
      if (value.isValidInt)
        value.toInt
      else
        Integer.MAX_VALUE

    private def getLength(bytes: ByteString, position: Int): Int = {
      val start = position * lengthBytes
      safeInt(ByteUtils.toBigInt(bytes.slice(start, start + lengthBytes)))
    }

    private def adjustExpLength(expBytes: ByteString, expLength: Int): Long = {
      val expHead =
        if (expLength <= lengthBytes)
          expBytes.padToByteString(expLength, 0.toByte)
        else
          expBytes.take(lengthBytes).padToByteString(lengthBytes, 0.toByte)

      val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

      if (expLength <= lengthBytes) {
        highestBitIndex
      } else {
        8L * (expLength - lengthBytes) + highestBitIndex
      }
    }
  }

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
  object Bn128Add extends PrecompiledContract {
    val expectedBytes: Int = 4 * 32

    def exec(inputData: ByteString): Option[ByteString] = {
      val paddedInput = inputData.padToByteString(expectedBytes, 0.toByte)
      val (x1, y1, x2, y2) = getCurvePointsBytes(paddedInput)

      val result = for {
        p1 <- BN128Fp.createPoint(x1, y1)
        p2 <- BN128Fp.createPoint(x2, y2)
        p3 = BN128Fp.toEthNotation(BN128Fp.add(p1, p2))
      } yield p3

      result.map { point =>
        val xBytes = ByteUtils.bigIntegerToBytes(point.x.inner.bigInteger, 32)
        val yBytes = ByteUtils.bigIntegerToBytes(point.y.inner.bigInteger, 32)
        ByteString(xBytes ++ yBytes)
      }
    }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      if (etcFork >= EtcForks.Phoenix || ethFork >= EthForks.Istanbul)
        BigInt(150) // https://eips.ethereum.org/EIPS/eip-1108
      else
        BigInt(500)

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString, ByteString) =
      (input.slice(0, 32), input.slice(32, 64), input.slice(64, 96), input.slice(96, 128))

  }

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
  object Bn128Mul extends PrecompiledContract {
    val expectedBytes: Int = 3 * 32
    val maxScalar: BigInt = BigInt(2).pow(256) - 1

    def exec(inputData: ByteString): Option[ByteString] = {
      val paddedInput = inputData.padToByteString(expectedBytes, 0.toByte)
      val (x1, y1, scalarBytes) = getCurvePointsBytes(paddedInput)

      val scalar = ByteUtils.toBigInt(scalarBytes)

      val result = for {
        p <- BN128Fp.createPoint(x1, y1)
        s <- if (scalar <= maxScalar) Some(scalar) else None
        p3 = BN128Fp.toEthNotation(BN128Fp.mul(p, s))
      } yield p3

      result.map { point =>
        val xBytes = ByteUtils.bigIntegerToBytes(point.x.inner.bigInteger, 32)
        val yBytes = ByteUtils.bigIntegerToBytes(point.y.inner.bigInteger, 32)
        ByteString(xBytes ++ yBytes)
      }
    }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      if (etcFork >= EtcForks.Phoenix || ethFork >= EthForks.Istanbul)
        BigInt(6000) // https://eips.ethereum.org/EIPS/eip-1108
      else
        BigInt(40000)

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString) =
      (input.slice(0, 32), input.slice(32, 64), input.slice(64, 96))
  }

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-197.md
  // scalastyle: off
  object Bn128Pairing extends PrecompiledContract {
    private val wordLength = 32
    private val inputLength = 6 * wordLength

    val positiveResult: ByteString = ByteUtils.padLeft(ByteString(1), wordLength)
    val negativeResult: ByteString = ByteString(Seq.fill(wordLength)(0.toByte).toArray)

    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.length % inputLength != 0) {
        None
      } else {
        getPairs(inputData.grouped(inputLength)).map { pairs =>
          if (PairingCheck.pairingCheck(pairs))
            positiveResult
          else
            negativeResult
        }
      }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = {
      val k = inputData.length / inputLength
      if (etcFork >= EtcForks.Phoenix || ethFork >= EthForks.Istanbul) { // https://eips.ethereum.org/EIPS/eip-1108
        BigInt(34000) * k + BigInt(45000)
      } else {
        BigInt(80000) * k + BigInt(100000)
      }
    }

    // Method which stops reading another points if one of earlier ones failed (had invalid coordinates, or was not on
    // BN128 curve
    private def getPairs(bytes: Iterator[ByteString]): Option[Seq[G1G2Pair]] = {
      var accum = List.empty[G1G2Pair]
      while (bytes.hasNext)
        getPair(bytes.next()) match {
          case Some(part) => accum = part :: accum
          case None       => return None // scalafix:ok DisableSyntax.return
        }
      Some(accum)
    }

    private def getPair(input: ByteString): Option[G1G2Pair] =
      for {
        g1 <- BN128G1(getBytesOnPosition(input, 0), getBytesOnPosition(input, 1))
        g2 <- BN128G2(
          getBytesOnPosition(input, 3),
          getBytesOnPosition(input, 2),
          getBytesOnPosition(input, 5),
          getBytesOnPosition(input, 4)
        )
      } yield G1G2Pair(g1, g2)

    private def getBytesOnPosition(input: ByteString, pos: Int): ByteString = {
      val from = pos * wordLength
      input.slice(from, from + wordLength)
    }
  }

  // Spec: https://eips.ethereum.org/EIPS/eip-152
  // scalastyle: off
  object Blake2bCompress extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      Blake2bCompression.blake2bCompress(inputData.toArray).map(ByteString.fromArrayUnsafe)

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = {
      val inputArray = inputData.toArray
      if (Blake2bCompression.isValidInput(inputArray)) {
        // Each round costs 1gas
        BigInt(Blake2bCompression.parseNumberOfRounds(inputArray))
      } else {
        // bad input to contract, contract will not execute, set price to zero
        BigInt(0)
      }
    }
  }

  // Spec: https://eips.ethereum.org/EIPS/eip-7951
  // EIP-7951: P256VERIFY — secp256r1 (P-256) signature verification
  object P256Verify extends PrecompiledContract {
    private val expectedInputLength = 160 // hash(32) + r(32) + s(32) + x(32) + y(32)

    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.length < expectedInputLength) {
        Some(ByteString.empty) // Invalid input — return empty (failure)
      } else {
        val hash = inputData.slice(0, 32).toArray
        val r = inputData.slice(32, 64).toArray
        val s = inputData.slice(64, 96).toArray
        val x = inputData.slice(96, 128).toArray
        val y = inputData.slice(128, 160).toArray

        if (Secp256r1.verify(hash, r, s, x, y)) {
          // Valid signature: return 0x01 left-padded to 32 bytes
          Some(ByteUtils.padLeft(ByteString(1), 32))
        } else {
          // Invalid signature: return 0x00 left-padded to 32 bytes
          Some(ByteString(new Array[Byte](32)))
        }
      }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(6900)
  }

  // ===== EIP-2537: BLS12-381 Precompiles (7 precompiles at 0x0b-0x11) =====
  // Crypto operations via Besu's gnark JNI library. Gas costs and addresses match
  // the final EIP-2537 spec. G1MUL/G2MUL removed — MSM at k=1 covers single-point multiplication.

  object BlsG1Add extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.length != 256) None
      else Bls12381.g1Add(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(375)
  }

  object BlsG1MultiExp extends PrecompiledContract {
    private val pairSize = 160 // 128-byte G1 point + 32-byte scalar

    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.isEmpty || inputData.length % pairSize != 0) None
      else Bls12381.g1MultiExp(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = {
      val k = inputData.length / pairSize
      if (k == 0) BigInt(0)
      else BigInt(12000) * k * blsG1MsmDiscount(k) / 1000
    }
  }

  object BlsG2Add extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.length != 512) None
      else Bls12381.g2Add(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(600)
  }

  object BlsG2MultiExp extends PrecompiledContract {
    private val pairSize = 288 // 256-byte G2 point + 32-byte scalar

    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.isEmpty || inputData.length % pairSize != 0) None
      else Bls12381.g2MultiExp(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = {
      val k = inputData.length / pairSize
      if (k == 0) BigInt(0)
      else BigInt(22500) * k * blsG2MsmDiscount(k) / 1000
    }
  }

  object BlsPairing extends PrecompiledContract {
    private val pairSize = 384 // 128-byte G1 + 256-byte G2

    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.isEmpty || inputData.length % pairSize != 0) None
      else Bls12381.pairing(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = {
      val k = inputData.length / pairSize
      if (k == 0) BigInt(0)
      else BigInt(32600) * k + BigInt(37700)
    }
  }

  object BlsMapG1 extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.length != 64) None
      else Bls12381.mapFpToG1(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(5500)
  }

  object BlsMapG2 extends PrecompiledContract {
    def exec(inputData: ByteString): Option[ByteString] =
      if (inputData.length != 128) None
      else Bls12381.mapFp2ToG2(inputData.toArray).map(ByteString(_))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(23800)
  }

  /** EIP-2537 G1 MSM discount table (128 entries, from geth params/protocol_params.go). */
  private def blsG1MsmDiscount(k: Int): Int = {
    val table = Array(
      1000, 949, 848, 797, 764, 750, 738, 728, 719, 712, 705, 698, 692, 687, 682, 677, 673, 669, 665, 661, 658, 654,
      651, 648, 645, 642, 640, 637, 635, 632, 630, 627, 625, 623, 621, 619, 617, 615, 613, 611, 609, 608, 606, 604, 603,
      601, 599, 598, 596, 595, 593, 592, 591, 589, 588, 586, 585, 584, 582, 581, 580, 579, 577, 576, 575, 574, 573, 572,
      570, 569, 568, 567, 566, 565, 564, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 551, 550, 549, 548,
      547, 547, 546, 545, 544, 543, 542, 541, 540, 540, 539, 538, 537, 536, 536, 535, 534, 533, 532, 532, 531, 530, 529,
      528, 528, 527, 526, 525, 525, 524, 523, 522, 522, 521, 520, 520, 519
    )
    if (k <= 0) 1000
    else if (k <= table.length) table(k - 1)
    else 519 // for k > 128
  }

  /** EIP-2537 G2 MSM discount table (128 entries, from geth params/protocol_params.go). */
  private def blsG2MsmDiscount(k: Int): Int = {
    val table = Array(
      1000, 1000, 923, 884, 855, 832, 812, 796, 782, 770, 759, 749, 740, 732, 724, 717, 711, 704, 699, 693, 688, 683,
      679, 674, 670, 666, 663, 659, 655, 652, 649, 646, 643, 640, 637, 634, 632, 629, 627, 624, 622, 620, 618, 615, 613,
      611, 609, 607, 606, 604, 602, 600, 598, 597, 595, 593, 592, 590, 589, 587, 586, 584, 583, 582, 580, 579, 578, 576,
      575, 574, 573, 571, 570, 569, 568, 567, 566, 565, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 552,
      551, 550, 549, 548, 547, 546, 545, 545, 544, 543, 542, 541, 541, 540, 539, 538, 537, 537, 536, 535, 535, 534, 533,
      532, 532, 531, 530, 530, 529, 528, 528, 527, 526, 526, 525, 524, 524
    )
    if (k <= 0) 1000
    else if (k <= table.length) table(k - 1)
    else 524 // for k > 128
  }
}

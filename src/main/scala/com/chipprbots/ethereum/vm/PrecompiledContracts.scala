package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.util.Try

import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.crypto.Secp256r1
import com.chipprbots.ethereum.crypto.zksnark.BN128.BN128G1
import com.chipprbots.ethereum.crypto.zksnark.BN128.BN128G2
import com.chipprbots.ethereum.crypto.zksnark.BN128Fp
import com.chipprbots.ethereum.crypto.zksnark.BN128Fp2
import com.chipprbots.ethereum.crypto.zksnark.Fp
import com.chipprbots.ethereum.crypto.zksnark.PairingCheck
import com.chipprbots.ethereum.crypto.zksnark.PairingCheck.G1G2Pair
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.utils.ByteStringUtils.*
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks.EtcFork
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks.EthFork

// scalastyle:off magic.number
object PrecompiledContracts:

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

  /** Cancun contracts: adds KZG point evaluation precompile (EIP-4844) */
  val KzgPointEvalAddr: Address = Address(0x0a)

  val cancunContracts: Map[Address, PrecompiledContract] = istanbulPhoenixContracts ++ Map(
    KzgPointEvalAddr -> KzgPointEvaluation
  )

  /** BLS12-381 precompiles (EIP-2537). Active at ETH Prague and ETC Olympia base set. */
  val olympiaContracts: Map[Address, PrecompiledContract] = cancunContracts ++ Map(
    BlsG1AddAddr -> BlsG1Add,
    BlsG1MultiExpAddr -> BlsG1MultiExp,
    BlsG2AddAddr -> BlsG2Add,
    BlsG2MultiExpAddr -> BlsG2MultiExp,
    BlsPairingAddr -> BlsPairing,
    BlsMapG1Addr -> BlsMapG1,
    BlsMapG2Addr -> BlsMapG2
  )

  /** ETC Olympia (block-based) and ETH Osaka (timestamp-based): BLS12-381 + P256VERIFY (EIP-7951, ECIP-1121). */
  val osakaContracts: Map[Address, PrecompiledContract] = olympiaContracts ++ Map(
    P256VerifyAddr -> P256Verify
  )

  /** Checks whether `ProgramContext#recipientAddr` points to a precompiled contract
    */
  def isDefinedAt(context: ProgramContext[?, ?]): Boolean =
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

  private def getContract(context: ProgramContext[?, ?]): Option[PrecompiledContract] =
    context.recipientAddr.flatMap { addr =>
      val baseContracts = getContracts(context)
      val relocations = context.precompileRelocations
      if relocations.isEmpty then baseContracts.get(addr)
      else if relocations.contains(addr) then
        // Address was a precompile source (moved away) — no longer a precompile
        None
      else
        // Check if this address is a relocation target
        val reverseMap = relocations.map(_.swap)
        reverseMap.get(addr) match
          case Some(originalAddr) => baseContracts.get(originalAddr) // Precompile relocated here
          case None               => baseContracts.get(addr) // Normal precompile check
    }

  /** The result a precompile frame hands back to its caller.
    *
    * EIP-8037: a precompile charges execution gas only and never touches state gas. Like any other frame it is handed
    * the caller's reservoir in full, and the caller then ADOPTS the child's counters (`mergeSuccessfulChildStateGas` /
    * `absorbFailedChildStateGas`), so the frame must hand them back. A result left at the defaults (0) wipes the
    * caller's unspent reservoir and the transaction's state gas already charged — which moves the header's `gasUsed` by
    * more than 2^24 on any Amsterdam transaction above TX_MAX_GAS_LIMIT that calls a precompile (#1437).
    *
    * The shape is what `ProgramState(vm, context, env).toResult` gives a frame that ran no opcode: the context's
    * counters on success and, on failure, the restore-to-baseline every failed frame applies (execution-specs
    * `process_call`: `restore_state_gas` then `forfeit_remaining_gas`). The restore only matters when a transaction
    * targets a precompile directly and its EIP-2780 pre-execution account-creation charge must be refilled; for a
    * sub-call nothing between frame entry and exit moved the counters.
    *
    * Before Amsterdam every counter in the context is 0, so the result is identical to the previous one on every ETH
    * fork before Amsterdam and on every ETC fork.
    *
    * EIP-1153 transient storage is handed back for the same reason: a successful CALL-family frame ADOPTS the child's
    * transient storage (`CallOp.exec`: `copy(transientStorage = result.transientStorage)`), and a precompile neither
    * reads nor writes it. Left at the default (empty), a successful precompile call wiped every TSTORE the transaction
    * had made so far — TSTORE(k, 42); CALL 0x04; TLOAD(k) gave 0 where execution-specs gives 42 (#1439). The access
    * sets, logs and deletions stay empty: the caller MERGES those, so empty is exact.
    */
  private def frameResult[W <: WorldStateProxy[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      returnData: ByteString,
      gasRemaining: BigInt,
      error: Option[ProgramError]
  ): ProgramResult[W, S] =
    val completed = ProgramResult[W, S](
      returnData = returnData,
      gasRemaining = gasRemaining,
      world = context.world,
      addressesToDelete = Set.empty,
      logs = Nil,
      internalTxs = Nil,
      gasRefund = 0,
      error = error,
      accessedAddresses = Set.empty,
      accessedStorageKeys = Set.empty,
      transientStorage = context.transientStorage,
      stateGasReservoir = context.stateGasReservoir,
      evmStateGasUsed = context.evmStateGasUsed,
      stateGasFromGasLeft = context.stateGasFromGasLeft,
      stateGasBaseline = context.stateGasBaselineOverride.getOrElse(context.stateGasReservoir)
    )
    if error.isDefined then completed.withStateGasRestoredToBaseline else completed

  /** Check if an address is a known precompile address (without relocation) */
  def isPrecompileAddress(addr: Address, context: ProgramContext[?, ?]): Boolean =
    getContracts(context).contains(addr)

  def getContracts(context: ProgramContext[?, ?]): Map[Address, PrecompiledContract] =
    getContracts(context.evmConfig, context.blockHeader)

  /** The precompile set active for `blockHeader`. Depends on nothing but the fork, which is why a caller that has no
    * frame yet (the EIP-2780 dispatch charge in `ProgramContext.apply`) can ask for it.
    */
  def getContracts(evmConfig: EvmConfig, blockHeader: BlockHeader): Map[Address, PrecompiledContract] =
    val ethFork = evmConfig.blockchainConfig.ethForkForBlockNumber(blockHeader.number.value)
    val etcFork = evmConfig.blockchainConfig.etcForkForBlockNumber(blockHeader.number.value)
    // Post-Cancun detection: check if block header has blob gas fields
    val isCancun = blockHeader.blobGasUsed.isDefined || blockHeader.excessBlobGas.isDefined
    // EIP-2537 BLS12-381 precompiles activate at Prague timestamp on ETH chains
    val isPrague = evmConfig.blockchainConfig.isPragueTimestamp(blockHeader.unixTimestamp)
    // EIP-7951 P256VERIFY activates at Osaka timestamp on ETH chains
    val isOsaka = evmConfig.blockchainConfig.isOsakaTimestamp(blockHeader.unixTimestamp)

    if isOsaka then osakaContracts
    else if etcFork >= EtcForks.Olympia then
      // ETC Olympia activates BLS12-381 + P256VERIFY (ECIP-1121). Same set as ETH Osaka.
      osakaContracts
    else if isPrague then
      // ETH Prague activates BLS12-381 (EIP-2537) but NOT P256VERIFY (that is Osaka-only).
      olympiaContracts
    else if isCancun then cancunContracts
    else if ethFork >= EthForks.Istanbul || etcFork >= EtcForks.Phoenix then istanbulPhoenixContracts
    else if ethFork >= EthForks.Byzantium || etcFork >= EtcForks.Atlantis then
      // byzantium and atlantis hard fork introduce the same set of precompiled contracts
      byzantiumAtlantisContracts
    else contracts

  /** What [[PrecompiledContract.failureReason]] answers for an input that none of go-ethereum's checks rejects.
    * go-ethereum runs such an input, so it has no text for it, and `exec` failing one would mean fukuii and go-ethereum
    * disagree about it. The precompiles that never fail (ecrecover, SHA-256, RIPEMD-160, identity, P256VERIFY) have no
    * reason at all.
    */
  val UnexplainedFailure: String = "precompiled contract failed"

  sealed trait PrecompiledContract:
    protected def exec(inputData: ByteString): Option[ByteString]
    protected def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt

    /** Why the precompile fails `inputData`, in go-ethereum v1.17.9's words: the error its `Run` returns, from its
      * checks in its order (core/vm/contracts.go). Only the RPC reads it, through [[PreCompiledContractFail]]
      * (`jsonrpc.CallErrors.vmErrorMessage`); block import never asks.
      *
      * It decides nothing: `gas` and `exec` alone decide the result, and this is meaningful only for an input `exec`
      * fails. Where go-ethereum's reason is a check on the input's bytes it is computed from the bytes. The BLS12-381
      * subgroup checks and KZG's proof check need the native library, and ask it through `NativeBackends`' probes,
      * which cannot halt the node.
      */
    def failureReason(inputData: ByteString): String

    def run[W <: WorldStateProxy[W, S], S <: Storage[S]](context: ProgramContext[W, S]): ProgramResult[W, S] =

      val ethFork = context.evmConfig.blockchainConfig.ethForkForBlockNumber(context.blockHeader.number.value)
      val etcFork = context.evmConfig.blockchainConfig.etcForkForBlockNumber(context.blockHeader.number.value)

      val g = gas(context.inputData, etcFork, ethFork)

      val (result, error, gasRemaining) = (
        if g <= context.startGas then
          exec(context.inputData) match
            case Some(returnData) => (returnData, None, context.startGas - g)
            case None => (ByteString.empty, Some(PreCompiledContractFail(this, context.inputData)), BigInt(0))
        else (ByteString.empty, Some(OutOfGas), BigInt(0))
      ): (ByteString, Option[ProgramError], BigInt)

      frameResult(context, result, gasRemaining, error)

  object EllipticCurveRecovery extends PrecompiledContract:
    private val secp256k1n: BigInt = BigInt(curve.getN)

    def exec(inputData: ByteString): Option[ByteString] =
      val data: ByteString = inputData.padToByteString(128, 0.toByte)
      val h = data.slice(0, 32)
      val v = data.slice(32, 64)
      val r = data.slice(64, 96)
      val s = data.slice(96, 128)

      // core-geth `ecrecover.Run` (core/vm/contracts.go): `crypto.ValidateSignatureValues(v, r, s, homestead =
      // false)` — r and s must lie in [1, secp256k1n - 1], on every fork. High s is accepted: the Homestead low-s rule
      // applies to transaction signatures only. Without this, s >= n reduces mod n inside the point multiplication
      // and r in [n, p) is still a valid x-coordinate, so both recover an address where the reference returns empty.
      // Precompile-scoped on purpose: transaction signatures are range-checked in StdSignedTransactionValidator.
      if hasOnlyLastByteSet(v) && inSignatureRange(r) && inSignatureRange(s) then
        // A recovery landing on the point at infinity encodes as zero bytes, not a 64-byte key: libsecp256k1 fails
        // it, so it must yield empty output rather than keccak256("")[12:].
        val recovered =
          Try(ECDSASignature(r, s, v.last).publicKey(h)).getOrElse(None).filter(_.length == PublicKeyLength)
        Some(
          recovered
            .map { bytes =>
              val hash = kec256(bytes).slice(12, 32)
              ByteUtils.padLeft(hash, 32)
            }
            .getOrElse(ByteString.empty)
        )
      else Some(ByteString.empty)

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(3000)

    /** Never fails: a bad signature returns no output (go-ethereum `ecrecover.Run` returns `nil, nil`). */
    def failureReason(inputData: ByteString): String = UnexplainedFailure

    private def hasOnlyLastByteSet(v: ByteString): Boolean =
      v.dropWhile(_ == 0).size == 1

    private val PublicKeyLength = 64

    private def inSignatureRange(word: ByteString): Boolean =
      val x = BigInt(1, word.toArray)
      x > 0 && x < secp256k1n

  object Sha256 extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Some(sha256(inputData))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      BigInt(60) + BigInt(12) * wordsForBytes(inputData.size)

    /** Never fails. */
    def failureReason(inputData: ByteString): String = UnexplainedFailure

  object Ripemp160 extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Some(ByteUtils.padLeft(ripemd160(inputData), 32))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      BigInt(600) + BigInt(120) * wordsForBytes(inputData.size)

    /** Never fails. */
    def failureReason(inputData: ByteString): String = UnexplainedFailure

  object Identity extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Some(inputData)

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      BigInt(15) + BigInt(3) * wordsForBytes(inputData.size)

    /** Never fails. */
    def failureReason(inputData: ByteString): String = UnexplainedFailure

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-198.md
  object ModExp extends PrecompiledContract:

    private val lengthBytes = 32
    private val totalLengthBytes = 3 * lengthBytes

    /** EIP-7823: Maximum operand length in bytes */
    private val maxOperandLength = 1024

    override def run[W <: WorldStateProxy[W, S], S <: Storage[S]](
        context: ProgramContext[W, S]
    ): ProgramResult[W, S] =
      val etcFork = context.evmConfig.blockchainConfig.etcForkForBlockNumber(context.blockHeader.number.value)
      val ethFork = context.evmConfig.blockchainConfig.ethForkForBlockNumber(context.blockHeader.number.value)
      val isOsaka = context.evmConfig.blockchainConfig.isOsakaTimestamp(context.blockHeader.unixTimestamp)
      val isEthereum = context.evmConfig.blockchainConfig.isEthereum
      // EIP-7823 (MODEXP input bounds, 1024-byte max) activates at:
      //   - ETH Osaka timestamp (per execution-specs prague → EIP-2565, osaka → EIP-7823/7883)
      //   - ETC Olympia (ECIP-1121) on actual ETC chains
      // On ETH chains we MUST NOT use etcFork as a proxy — hive maps London→olympiaBlockNumber,
      // so etcFork >= Olympia is true but EIP-7823 is not yet active pre-Osaka.
      val useEip7823 = isOsaka || (etcFork >= EtcForks.Olympia && !isEthereum)

      // EIP-7883: gas cost routing. On ETH chains, EIP-7883 activates at Osaka timestamp
      // (Prague keeps EIP-2565 per execution-specs); on ETC, it activates at Olympia.
      // Hive maps London→olympiaBlockNumber, which we must ignore on ETH to avoid firing
      // EIP-7883 pre-Osaka.
      val g = gasWithOsaka(context.inputData, etcFork, ethFork, isOsaka, isEthereum)

      // go-ethereum `RunPrecompiledContract` (core/vm/contracts.go) charges the price first; only a call that paid it
      // reaches `bigModExp.Run`, which rejects an operand longer than 1024 bytes (EIP-7823). So an over-long input its
      // gas cannot pay for is out of gas, like any other (#1727 PR 7b; it used to be rejected before the charge). The
      // frame is the same either way, all gas consumed and no output: only the error's name depends on the order.
      val (result, error, gasRemaining) = (
        if g > context.startGas then (ByteString.empty, Some(OutOfGas), BigInt(0))
        else if useEip7823 && exceedsEip7823(context.inputData) then
          (ByteString.empty, Some(PreCompiledContractFail(this, context.inputData)), BigInt(0))
        else
          exec(context.inputData) match
            case Some(returnData) => (returnData, None, context.startGas - g)
            case None => (ByteString.empty, Some(PreCompiledContractFail(this, context.inputData)), BigInt(0))
      ) : (ByteString, Option[ProgramError], BigInt)

      frameResult(context, result, gasRemaining, error)

    /** go-ethereum `bigModExp.Run` fails only at EIP-7823's bound, and so does `run` here: `exec` never fails. */
    def failureReason(inputData: ByteString): String =
      if exceedsEip7823(inputData) then "one or more of base/exponent/modulus length exceeded 1024 bytes"
      else UnexplainedFailure

    /** EIP-7823: a base, exponent or modulus length above 1024 bytes. A length word above 2^31 - 1 reads as 2^31 - 1
      * (`getLength`), so go-ethereum's `inputLenOverflow` (a length of more than 64 bits) is covered too.
      */
    private def exceedsEip7823(inputData: ByteString): Boolean =
      (0 to 2).exists(position => getLength(inputData, position) > maxOperandLength)

    def exec(inputData: ByteString): Option[ByteString] =
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val result =
        if baseLength == 0 && modLength == 0 then BigInt(0)
        else
          val mod = getNumber(inputData, safeAdd(totalLengthBytes, safeAdd(baseLength, expLength)), modLength)

          if mod == 0 then BigInt(0)
          else
            val base = getNumber(inputData, totalLengthBytes, baseLength)
            val exp = getNumber(inputData, safeAdd(totalLengthBytes, baseLength), expLength)

            base.modPow(exp, mod)
      Some(ByteString(ByteUtils.bigIntegerToBytes(result.bigInteger, modLength)))

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      gasWithOsaka(inputData, etcFork, ethFork, eip7883Active = false, isEthereum = false)

    /** Variant that takes an explicit EIP-7883 activation flag AND distinguishes ETC Olympia from ETH London (both
      * mapped to `olympiaBlockNumber` by hive). EIP-7883 activates on ETC Olympia (ECIP-1121) OR ETH Osaka+ (per
      * execution-specs). Prague keeps EIP-2565. Never fires on plain ETH London just because hive sets
      * olympiaBlockNumber there.
      */
    def gasWithOsaka(
        inputData: ByteString,
        etcFork: EtcFork,
        ethFork: EthFork,
        eip7883Active: Boolean,
        isEthereum: Boolean
    ): BigInt =
      val baseLength = getLength(inputData, 0)
      val expLength = getLength(inputData, 1)
      val modLength = getLength(inputData, 2)

      val expBytes =
        inputData.slice(
          safeAdd(totalLengthBytes, baseLength),
          safeAdd(safeAdd(totalLengthBytes, baseLength), expLength)
        )

      val useEip7883 = eip7883Active || (etcFork >= EtcForks.Olympia && !isEthereum)

      if useEip7883 then PostEIP7883Cost.calculate(baseLength, modLength, expLength, expBytes)
      else if ethFork >= EthForks.Berlin || etcFork >= EtcForks.Magneto then
        PostEIP2565Cost.calculate(baseLength, modLength, expLength, expBytes)
      else PostEIP198Cost.calculate(baseLength, modLength, expLength, expBytes)

    // Spec: https://eips.ethereum.org/EIPS/eip-198
    object PostEIP198Cost:
      private val GQUADDIVISOR = 20

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt =
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength(expBytes, expLength)
        multComplexity * math.max(adjusted, 1) / GQUADDIVISOR

      private def getMultComplexity(x: BigInt): BigInt =
        val x2 = x * x
        if x <= 64 then x2
        else if x <= 1024 then x2 / 4 + 96 * x - 3072
        else x2 / 16 + 480 * x - 199680

    // Spec: https://eips.ethereum.org/EIPS/eip-2565
    object PostEIP2565Cost:
      private val GQUADDIVISOR = 3

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt =
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength(expBytes, expLength)
        val r = multComplexity * math.max(adjusted, 1) / GQUADDIVISOR
        if r <= 200 then 200
        else r

      // ceiling(x/8)^2
      private def getMultComplexity(x: BigInt): BigInt =
        ((x + 7) / 8).pow(2)

    // Spec: https://eips.ethereum.org/EIPS/eip-7883
    // EIP-7883: ModExp gas cost increase — higher multiplication complexity, no divisor, min 500
    object PostEIP7883Cost:

      def calculate(baseLength: Int, modLength: Int, expLength: Int, expBytes: ByteString): BigInt =
        val multComplexity = getMultComplexity(math.max(baseLength, modLength))
        val adjusted = adjustExpLength7883(expBytes, expLength)
        val r = multComplexity * math.max(adjusted, 1)
        if r < 500 then 500
        else r

      // EIP-7883 multiplication complexity:
      // For maxLen <= 32: 16 (flat constant)
      // For maxLen > 32: 2 * ceiling(maxLen/8)^2
      private def getMultComplexity(x: BigInt): BigInt =
        if x <= 32 then BigInt(16)
        else
          val words = (x + 7) / 8
          2 * words.pow(2)

      // EIP-7883 adjusted exponent length uses multiplier 16 (not 8 like EIP-2565)
      private def adjustExpLength7883(expBytes: ByteString, expLength: Int): Long =
        val expHead =
          if expLength <= lengthBytes then expBytes.padToByteString(expLength, 0.toByte)
          else expBytes.take(lengthBytes).padToByteString(lengthBytes, 0.toByte)

        val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

        if expLength <= lengthBytes then highestBitIndex
        else 16L * (expLength - lengthBytes) + highestBitIndex

    private def getNumber(bytes: ByteString, offset: Int, length: Int): BigInt =
      val number = bytes.slice(offset, safeAdd(offset, length)).padToByteString(length, 0.toByte)
      ByteUtils.toBigInt(number)

    private def safeAdd(a: Int, b: Int): Int =
      safeInt(BigInt(a) + BigInt(b))

    private def safeInt(value: BigInt): Int =
      if value.isValidInt then value.toInt
      else Integer.MAX_VALUE

    /** A length word is read from the input right-padded with zeros (EIP-198: "the input is padded with zeros"). An
      * input that ends inside a length word keeps the bytes it has as the word's HIGH-order bytes: 31 bytes `00..01`
      * are the length 256, not 1. Reading the short slice as a number of its own would give a smaller length, a
      * different gas cost and a different result than every other client computes for the same call.
      */
    private def getLength(bytes: ByteString, position: Int): Int =
      val start = position * lengthBytes
      safeInt(ByteUtils.toBigInt(bytes.slice(start, start + lengthBytes).padToByteString(lengthBytes, 0.toByte)))

    private def adjustExpLength(expBytes: ByteString, expLength: Int): Long =
      val expHead =
        if expLength <= lengthBytes then expBytes.padToByteString(expLength, 0.toByte)
        else expBytes.take(lengthBytes).padToByteString(lengthBytes, 0.toByte)

      val highestBitIndex = math.max(ByteUtils.toBigInt(expHead).bitLength - 1, 0)

      if expLength <= lengthBytes then highestBitIndex
      else 8L * (expLength - lengthBytes) + highestBitIndex

  /** go-ethereum's checks on a BN254 point, in its order and with its texts: crypto/bn256/gnark `G1.Unmarshal` and
    * `G2.Unmarshal` (go-ethereum runs bn256 on gnark-crypto on amd64 and arm64), whose coordinate errors are
    * gnark-crypto `fp.Element.SetBytesCanonical`'s. All zeros is the point at infinity, before any other check.
    * core-geth words these differently; #1727 takes go-ethereum's text on both chains.
    *
    * Pure: the on-curve and subgroup checks are fukuii's own BN128 code, the code `exec` runs.
    */
  private object Bn254Reasons:
    private val NotCanonical = "invalid fp.Element encoding"
    private val NotOnCurve = "point is not on curve"
    private val NotInSubgroup = "point is not in correct subgroup"

    private def word(bytes: ByteString, i: Int): ByteString = bytes.slice(32 * i, 32 * (i + 1))

    private def canonical(word: ByteString): Boolean = ByteUtils.toBigInt(word) < Fp.P

    /** A 64-byte G1 point, x then y. Every point on BN254's G1 curve is in G1 (cofactor 1). */
    def g1(bytes: ByteString): Option[String] =
      if bytes.forall(_ == 0) then None
      else if !canonical(word(bytes, 0)) || !canonical(word(bytes, 1)) then Some(NotCanonical)
      else if BN128Fp.createPoint(word(bytes, 0), word(bytes, 1)).isEmpty then Some(NotOnCurve)
      else None

    /** A 128-byte G2 point in the EVM's order: x's imaginary part, x's real part, then y's. */
    def g2(bytes: ByteString): Option[String] =
      if bytes.forall(_ == 0) then None
      else if !(0 until 4).forall(i => canonical(word(bytes, i))) then Some(NotCanonical)
      else
        val (xImaginary, xReal, yImaginary, yReal) = (word(bytes, 0), word(bytes, 1), word(bytes, 2), word(bytes, 3))
        if BN128Fp2.createPoint(xReal, xImaginary, yReal, yImaginary).isEmpty then Some(NotOnCurve)
        else if BN128G2(xReal, xImaginary, yReal, yImaginary).isEmpty then Some(NotInSubgroup)
        else None

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
  object Bn128Add extends PrecompiledContract:
    val expectedBytes: Int = 4 * 32

    def exec(inputData: ByteString): Option[ByteString] =
      val paddedInput = inputData.padToByteString(expectedBytes, 0.toByte)
      val (x1, y1, x2, y2) = getCurvePointsBytes(paddedInput)

      val result = for
        p1 <- BN128Fp.createPoint(x1, y1)
        p2 <- BN128Fp.createPoint(x2, y2)
        p3 = BN128Fp.toEthNotation(BN128Fp.add(p1, p2))
      yield p3

      result.map { point =>
        val xBytes = ByteUtils.bigIntegerToBytes(point.x.inner.bigInteger, 32)
        val yBytes = ByteUtils.bigIntegerToBytes(point.y.inner.bigInteger, 32)
        ByteString(xBytes ++ yBytes)
      }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      if etcFork >= EtcForks.Phoenix || ethFork >= EthForks.Istanbul then
        BigInt(150) // https://eips.ethereum.org/EIPS/eip-1108
      else BigInt(500)

    /** go-ethereum `runBn256Add`: the first point, then the second, from the input zero-padded to 128 bytes. */
    def failureReason(inputData: ByteString): String =
      val padded = inputData.padToByteString(expectedBytes, 0.toByte)
      Bn254Reasons.g1(padded.slice(0, 64)).orElse(Bn254Reasons.g1(padded.slice(64, 128))).getOrElse(UnexplainedFailure)

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString, ByteString) =
      (input.slice(0, 32), input.slice(32, 64), input.slice(64, 96), input.slice(96, 128))

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-196.md
  object Bn128Mul extends PrecompiledContract:
    val expectedBytes: Int = 3 * 32
    val maxScalar: BigInt = BigInt(2).pow(256) - 1

    def exec(inputData: ByteString): Option[ByteString] =
      val paddedInput = inputData.padToByteString(expectedBytes, 0.toByte)
      val (x1, y1, scalarBytes) = getCurvePointsBytes(paddedInput)

      val scalar = ByteUtils.toBigInt(scalarBytes)

      val result = for
        p <- BN128Fp.createPoint(x1, y1)
        s <- if scalar <= maxScalar then Some(scalar) else None
        p3 = BN128Fp.toEthNotation(BN128Fp.mul(p, s))
      yield p3

      result.map { point =>
        val xBytes = ByteUtils.bigIntegerToBytes(point.x.inner.bigInteger, 32)
        val yBytes = ByteUtils.bigIntegerToBytes(point.y.inner.bigInteger, 32)
        ByteString(xBytes ++ yBytes)
      }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      if etcFork >= EtcForks.Phoenix || ethFork >= EthForks.Istanbul then
        BigInt(6000) // https://eips.ethereum.org/EIPS/eip-1108
      else BigInt(40000)

    /** go-ethereum `runBn256ScalarMul`: the point, from the input zero-padded to 96 bytes. Any scalar is valid. */
    def failureReason(inputData: ByteString): String =
      Bn254Reasons.g1(inputData.padToByteString(expectedBytes, 0.toByte).slice(0, 64)).getOrElse(UnexplainedFailure)

    private def getCurvePointsBytes(input: ByteString): (ByteString, ByteString, ByteString) =
      (input.slice(0, 32), input.slice(32, 64), input.slice(64, 96))

  // Spec: https://github.com/ethereum/EIPs/blob/master/EIPS/eip-197.md
  // scalastyle: off
  object Bn128Pairing extends PrecompiledContract:
    private val wordLength = 32
    private val inputLength = 6 * wordLength

    val positiveResult: ByteString = ByteUtils.padLeft(ByteString(1), wordLength)
    val negativeResult: ByteString = ByteString(Seq.fill(wordLength)(0.toByte).toArray)

    def exec(inputData: ByteString): Option[ByteString] =
      if inputData.length % inputLength != 0 then None
      else
        getPairs(inputData.grouped(inputLength)).map { pairs =>
          if PairingCheck.pairingCheck(pairs) then positiveResult
          else negativeResult
        }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      val k = inputData.length / inputLength
      if etcFork >= EtcForks.Phoenix || ethFork >= EthForks.Istanbul then // https://eips.ethereum.org/EIPS/eip-1108
        BigInt(34000) * k + BigInt(45000)
      else BigInt(80000) * k + BigInt(100000)

    /** go-ethereum `runBn256Pairing`: the length, then pair by pair the G1 point and the G2 point. */
    def failureReason(inputData: ByteString): String =
      if inputData.length % inputLength != 0 then "bad elliptic curve pairing size"
      else
        inputData
          .grouped(inputLength)
          .flatMap(pair => Bn254Reasons.g1(pair.take(64)).orElse(Bn254Reasons.g2(pair.drop(64))))
          .nextOption()
          .getOrElse(UnexplainedFailure)

    // Method which stops reading another points if one of earlier ones failed (had invalid coordinates, or was not on
    // BN128 curve
    private def getPairs(bytes: Iterator[ByteString]): Option[Seq[G1G2Pair]] =
      var accum = List.empty[G1G2Pair]
      while bytes.hasNext do
        getPair(bytes.next()) match
          case Some(part) => accum = part :: accum
          case None       => return None // scalafix:ok DisableSyntax.return
      Some(accum)

    private def getPair(input: ByteString): Option[G1G2Pair] =
      for
        g1 <- BN128G1(getBytesOnPosition(input, 0), getBytesOnPosition(input, 1))
        g2 <- BN128G2(
          getBytesOnPosition(input, 3),
          getBytesOnPosition(input, 2),
          getBytesOnPosition(input, 5),
          getBytesOnPosition(input, 4)
        )
      yield G1G2Pair(g1, g2)

    private def getBytesOnPosition(input: ByteString, pos: Int): ByteString =
      val from = pos * wordLength
      input.slice(from, from + wordLength)

  // Spec: https://eips.ethereum.org/EIPS/eip-152
  // scalastyle: off
  object Blake2bCompress extends PrecompiledContract:
    def exec(inputData: ByteString): Option[ByteString] =
      Blake2bCompression.blake2bCompress(inputData.toArray).map(ByteString.fromArrayUnsafe)

    /** go-ethereum `blake2F.RequiredGas`: one gas per round for an input of the right length, whatever its final flag,
      * and 0 for any other length. A flag other than 0 or 1 still fails the call, but only once the rounds are paid
      * for, so with too little gas it is out of gas, as in go-ethereum (#1727 PR 7b; it used to be priced at 0). The
      * frame is the same either way, all gas consumed and no output: only the error's name changes.
      */
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      if inputData.length == Blake2bCompression.MessageBytesLength then
        // Each round costs 1gas
        BigInt(Blake2bCompression.parseNumberOfRounds(inputData.toArray))
      else
        // bad input to contract, contract will not execute, set price to zero
        BigInt(0)

    /** go-ethereum `blake2F.Run`: the length (213 bytes), then the final-block flag (0 or 1). */
    def failureReason(inputData: ByteString): String =
      if inputData.length != Blake2bCompression.MessageBytesLength then "invalid input length"
      else if (inputData(Blake2bCompression.MessageBytesLength - 1) & 0xff) > 1 then "invalid final flag"
      else UnexplainedFailure

  /** EIP-7951 P256VERIFY: secp256r1 (P-256) signature verification. Spec: https://eips.ethereum.org/EIPS/eip-7951
    *
    * Follows execution-specs `osaka/vm/precompiled_contracts/p256verify.py` step for step (go-ethereum `p256Verify`
    * agrees): the 6,900 gas is charged first, whatever the input; then the call SUCCEEDS with EMPTY output unless the
    * input is exactly 160 bytes — hash, r, s, qx, qy — and the signature verifies, in which case the output is 0x01
    * left-padded to 32 bytes. There is no 32-byte zero word for "does not verify": a caller sees RETURNDATASIZE 0.
    *
    * The bound and curve checks are the reference's own, made here rather than left to the JDK's ECDSA provider so that
    * the result cannot depend on which provider answers.
    *
    * Active on ETH from Osaka (timestamp) and on ETC from Olympia (block, ECIP-1121); see [[getContracts]].
    */
  object P256Verify extends PrecompiledContract:
    private val InputLength = 160 // hash(32) + r(32) + s(32) + qx(32) + qy(32)

    /** execution-specs `SECP256R1N` / `SECP256R1P` / `SECP256R1A` / `SECP256R1B`. */
    private val N = BigInt("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val P = BigInt("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = BigInt("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
    private val B = BigInt("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    private val Verified: ByteString = ByteUtils.padLeft(ByteString(1), 32)

    def exec(inputData: ByteString): Option[ByteString] =
      Some(if verifies(inputData) then Verified else ByteString.empty)

    private def verifies(input: ByteString): Boolean =
      input.length == InputLength && {
        def word(i: Int): Array[Byte] = input.slice(32 * i, 32 * (i + 1)).toArray
        val (hash, rBytes, sBytes, xBytes, yBytes) = (word(0), word(1), word(2), word(3), word(4))
        val (r, s) = (BigInt(1, rBytes), BigInt(1, sBytes))
        val (qx, qy) = (BigInt(1, xBytes), BigInt(1, yBytes))
        r > 0 && r < N && s > 0 && s < N && // 0 < r < n and 0 < s < n
        qx < P && qy < P && // 0 <= qx < p and 0 <= qy < p
        !(qx == 0 && qy == 0) && // not the point at infinity
        (qy * qy).mod(P) == (qx * qx * qx + A * qx + B).mod(P) && // on the curve
        Secp256r1.verify(hash, rBytes, sBytes, xBytes, yBytes)
      }

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(6900)

    /** Never fails: an input that does not verify returns no output. */
    def failureReason(inputData: ByteString): String = UnexplainedFailure

  // ===== EIP-2537: BLS12-381 Precompiles (final spec: 7 precompiles at 0x0b-0x11) =====
  // Uses org.hyperledger.besu:bls12-381 native library: Matter Labs' eth_pairings via JNA (not gnark or Constantine).
  // G1MUL/G2MUL removed — MSM at k=1 covers single-point multiplication.

  /** Executes a BLS12-381 operation on the Besu native library: `Some(result)` on success, `None` when the library
    * rejects the input (invalid point, wrong input size, etc.). A library that did not load raises
    * [[NativeBackends.NativeBackendUnavailable]] instead of failing the call (B-135).
    */
  private def blsNativeOp(opByte: Byte, inputData: ByteString): Option[ByteString] =
    NativeBackends.blsOperation(NativeBackends.LibEthPairingsNative, opByte, inputData)

  sealed trait BlsPrecompile extends PrecompiledContract

  /** The BLS12-381 base field modulus p (EIP-2537 "Curve parameters"). */
  private val Bls12381FieldModulus: BigInt = BigInt(
    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab",
    16
  )

  /** go-ethereum's checks on EIP-2537 inputs, in its order and with its texts (core/vm/contracts.go
    * `decodeBLS12381FieldElement`, `decodePointG1` and `decodePointG2`, then the subgroup checks in the MSM and pairing
    * `Run`s). A coordinate's error is gnark-crypto's `fp.BigEndian.Element`.
    *
    * The encoding and curve checks are pure. Subgroup membership is not a property of the bytes alone: Besu's library
    * is asked, through [[NativeBackends.blsAccepts]], whether it accepts the point in a one-pair MSM with a zero
    * scalar. EIP-2537 makes an MSM check every point's subgroup whatever its scalar, and the point already decodes, so
    * a rejection can only be the subgroup. A library that cannot answer leaves the point reported outside the subgroup.
    */
  private object Eip2537Reasons:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings

    private val P = Bls12381FieldModulus

    val InvalidLength = "invalid input length"
    private val NotOnCurve = "invalid point: not on curve"
    private val ZeroScalar = ByteString(new Array[Byte](32))

    private def element(bytes: ByteString, i: Int): ByteString = bytes.slice(64 * i, 64 * (i + 1))
    private def value(element: ByteString): BigInt = BigInt(1, element.drop(16).toArray)

    /** A 64-byte field element: 16 zero bytes, then a big-endian integer below p. */
    def fieldElement(element: ByteString): Option[String] =
      if element.take(16).exists(_ != 0) then Some("invalid field element top bytes")
      else if value(element) >= P then Some("invalid fp.Element encoding")
      else None

    /** A 128-byte G1 point: x, y, then y^2 = x^3 + 4. (0, 0) is the point at infinity, which counts as on the curve. */
    def g1(point: ByteString): Option[String] =
      fieldElement(element(point, 0)).orElse(fieldElement(element(point, 1))).orElse {
        val (x, y) = (value(element(point, 0)), value(element(point, 1)))
        Option.unless((x == 0 && y == 0) || (y * y - x * x * x - 4).mod(P) == 0)(NotOnCurve)
      }

    /** A 256-byte G2 point: x0, x1, y0, y1, then y^2 = x^3 + 4(1 + u) over Fp2 = Fp[u]/(u^2 + 1), x = x0 + x1 u. */
    def g2(point: ByteString): Option[String] =
      (0 until 4).iterator.flatMap(i => fieldElement(element(point, i))).nextOption().orElse {
        def mul(a: (BigInt, BigInt), b: (BigInt, BigInt)): (BigInt, BigInt) =
          ((a._1 * b._1 - a._2 * b._2).mod(P), (a._1 * b._2 + a._2 * b._1).mod(P))
        val c = (0 until 4).map(i => value(element(point, i)))
        val (x, y) = ((c(0), c(1)), (c(2), c(3)))
        val xCubed = mul(mul(x, x), x)
        Option.unless(point.forall(_ == 0) || mul(y, y) == ((xCubed._1 + 4).mod(P), (xCubed._2 + 4).mod(P)))(NotOnCurve)
      }

    /** For a G1 point that decodes. */
    def g1Subgroup(point: ByteString): Option[String] =
      Option.unless(inSubgroup(LibEthPairings.BLS12_G1MULTIEXP_OPERATION_RAW_VALUE, point))(
        "g1 point is not on correct subgroup"
      )

    /** For a G2 point that decodes. */
    def g2Subgroup(point: ByteString): Option[String] =
      Option.unless(inSubgroup(LibEthPairings.BLS12_G2MULTIEXP_OPERATION_RAW_VALUE, point))(
        "g2 point is not on correct subgroup"
      )

    private def inSubgroup(msm: Byte, point: ByteString): Boolean =
      point.forall(_ == 0) ||
        NativeBackends.blsAccepts(NativeBackends.LibEthPairingsNative, msm, point ++ ZeroScalar).contains(true)

  object BlsG1Add extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_G1ADD_OPERATION_RAW_VALUE, inputData)
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(375)

    /** go-ethereum `bls12381G1Add.Run`: 256 bytes, then each point. No subgroup check (EIP-2537). */
    def failureReason(inputData: ByteString): String =
      if inputData.length != 256 then Eip2537Reasons.InvalidLength
      else
        Eip2537Reasons
          .g1(inputData.take(128))
          .orElse(Eip2537Reasons.g1(inputData.drop(128)))
          .getOrElse(UnexplainedFailure)

  object BlsG1MultiExp extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    private val pairSize = 160 // 128-byte G1 point + 32-byte scalar
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_G1MULTIEXP_OPERATION_RAW_VALUE, inputData)

    /** EIP-2537, as go-ethereum `bls12381G1MultiExp.RequiredGas`: k = floor(length / 160) pairs, and 0 when there is
      * not one. Such an input always fails; priced at 0 its error is its length, as in go-ethereum, where it used to be
      * priced as one pair and could run out of gas (#1727 PR 7b). The frame is the same either way.
      */
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      val k = inputData.length / pairSize
      val discount = blsG1MsmDiscount(k)
      BigInt(12000) * k * discount / 1000

    /** go-ethereum `bls12381G1MultiExp.Run`: a non-zero multiple of 160 bytes, then pair by pair the point and its
      * subgroup. Scalars are not checked.
      */
    def failureReason(inputData: ByteString): String =
      if inputData.isEmpty || inputData.length % pairSize != 0 then Eip2537Reasons.InvalidLength
      else
        inputData
          .grouped(pairSize)
          .flatMap { pair =>
            val point = pair.take(128)
            Eip2537Reasons.g1(point).orElse(Eip2537Reasons.g1Subgroup(point))
          }
          .nextOption()
          .getOrElse(UnexplainedFailure)

  object BlsG2Add extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_G2ADD_OPERATION_RAW_VALUE, inputData)
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(600)

    /** go-ethereum `bls12381G2Add.Run`: 512 bytes, then each point. No subgroup check (EIP-2537). */
    def failureReason(inputData: ByteString): String =
      if inputData.length != 512 then Eip2537Reasons.InvalidLength
      else
        Eip2537Reasons
          .g2(inputData.take(256))
          .orElse(Eip2537Reasons.g2(inputData.drop(256)))
          .getOrElse(UnexplainedFailure)

  object BlsG2MultiExp extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    private val pairSize = 288 // 256-byte G2 point + 32-byte scalar
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_G2MULTIEXP_OPERATION_RAW_VALUE, inputData)

    /** EIP-2537, as go-ethereum `bls12381G2MultiExp.RequiredGas`: k = floor(length / 288) pairs, and 0 when there is
      * not one (see [[BlsG1MultiExp.gas]]).
      */
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      val k = inputData.length / pairSize
      val discount = blsG2MsmDiscount(k)
      BigInt(22500) * k * discount / 1000

    /** go-ethereum `bls12381G2MultiExp.Run`: a non-zero multiple of 288 bytes, then pair by pair the point and its
      * subgroup. Scalars are not checked.
      */
    def failureReason(inputData: ByteString): String =
      if inputData.isEmpty || inputData.length % pairSize != 0 then Eip2537Reasons.InvalidLength
      else
        inputData
          .grouped(pairSize)
          .flatMap { pair =>
            val point = pair.take(256)
            Eip2537Reasons.g2(point).orElse(Eip2537Reasons.g2Subgroup(point))
          }
          .nextOption()
          .getOrElse(UnexplainedFailure)

  object BlsPairing extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    private val pairSize = 384 // 128-byte G1 + 256-byte G2
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_PAIR_OPERATION_RAW_VALUE, inputData)

    /** EIP-2537, as go-ethereum `bls12381Pairing.RequiredGas`: 37,700 plus 32,600 for each of floor(length / 384)
      * pairs. An input of fewer than one pair costs the 37,700 base, where it used to be priced as one pair (#1727 PR
      * 7b); it always fails, and the frame is the same either way.
      */
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt =
      val k = inputData.length / pairSize
      BigInt(32600) * k + BigInt(37700)

    /** go-ethereum `bls12381Pairing.Run`: a non-zero multiple of 384 bytes, then pair by pair: G1 decodes, G2 decodes,
      * G1's subgroup, G2's subgroup.
      */
    def failureReason(inputData: ByteString): String =
      if inputData.isEmpty || inputData.length % pairSize != 0 then Eip2537Reasons.InvalidLength
      else
        inputData
          .grouped(pairSize)
          .flatMap { pair =>
            val (g1, g2) = (pair.take(128), pair.drop(128))
            Eip2537Reasons
              .g1(g1)
              .orElse(Eip2537Reasons.g2(g2))
              .orElse(Eip2537Reasons.g1Subgroup(g1))
              .orElse(Eip2537Reasons.g2Subgroup(g2))
          }
          .nextOption()
          .getOrElse(UnexplainedFailure)

  object BlsMapG1 extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_MAP_FP_TO_G1_OPERATION_RAW_VALUE, inputData)
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(5500)

    /** go-ethereum `bls12381MapG1.Run`: 64 bytes, then the field element. */
    def failureReason(inputData: ByteString): String =
      if inputData.length != 64 then Eip2537Reasons.InvalidLength
      else Eip2537Reasons.fieldElement(inputData).getOrElse(UnexplainedFailure)

  object BlsMapG2 extends BlsPrecompile:
    import org.hyperledger.besu.nativelib.bls12_381.LibEthPairings
    def exec(inputData: ByteString): Option[ByteString] =
      blsNativeOp(LibEthPairings.BLS12_MAP_FP2_TO_G2_OPERATION_RAW_VALUE, inputData)
    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = BigInt(23800)

    /** go-ethereum `bls12381MapG2.Run`: 128 bytes, then c0 and c1. */
    def failureReason(inputData: ByteString): String =
      if inputData.length != 128 then Eip2537Reasons.InvalidLength
      else
        Eip2537Reasons
          .fieldElement(inputData.take(64))
          .orElse(Eip2537Reasons.fieldElement(inputData.drop(64)))
          .getOrElse(UnexplainedFailure)

  /** EIP-4844: KZG point evaluation precompile at address 0x0A. Input: versioned_hash (32) ++ z (32) ++ y (32) ++
    * commitment (48) ++ proof (48) = 192 bytes Output: FIELD_ELEMENTS_PER_BLOB (32) ++ BLS_MODULUS (32) = 64 bytes Gas:
    * 50000
    */
  object KzgPointEvaluation extends PrecompiledContract:
    private val KZG_GAS = BigInt(50000)
    private val VERSIONED_HASH_VERSION_KZG: Byte = 0x01

    // BLS_MODULUS: 0x73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001
    private val BLS_MODULUS = BigInt("52435875175126190479447740508185965837690552500527637822603658699938581184513")
    // FIELD_ELEMENTS_PER_BLOB = 4096
    private val FIELD_ELEMENTS_PER_BLOB = BigInt(4096)

    def gas(inputData: ByteString, etcFork: EtcFork, ethFork: EthFork): BigInt = KZG_GAS

    def exec(inputData: ByteString): Option[ByteString] =
      // DEFER (all returns in this method): EIP-4844 KZG point-evaluation crypto primitive.
      // These are sequential validation guards feeding a precompile result. Keep the
      // short-circuits — expression rewrites are byte-level risky for consensus crypto.
      if inputData.length != 192 then return None // scalafix:ok DisableSyntax.return

      val versionedHash = inputData.slice(0, 32)
      val z = inputData.slice(32, 64)
      val y = inputData.slice(64, 96)
      val commitment = inputData.slice(96, 144)
      val proof = inputData.slice(144, 192)

      // Verify the versioned hash matches commitment via SHA256
      if versionedHash(0) != VERSIONED_HASH_VERSION_KZG then return None // scalafix:ok DisableSyntax.return

      // Verify z < BLS_MODULUS and y < BLS_MODULUS
      val zBigInt = BigInt(1, z.toArray)
      val yBigInt = BigInt(1, y.toArray)
      if zBigInt >= BLS_MODULUS || yBigInt >= BLS_MODULUS then return None // scalafix:ok DisableSyntax.return

      // Verify the versioned hash matches SHA256(commitment)[1:] with version prefix
      val commitmentHash = java.security.MessageDigest.getInstance("SHA-256").digest(commitment.toArray)
      commitmentHash(0) = VERSIONED_HASH_VERSION_KZG
      if ByteString(commitmentHash) != versionedHash then return None // scalafix:ok DisableSyntax.return

      // Verify the KZG proof using c-kzg-4844. A proof c-kzg rejects fails the call; a c-kzg without its library or
      // trusted setup raises NativeBackends.NativeBackendUnavailable instead (B-135).
      val verified =
        NativeBackends.kzgProofVerifies(
          NativeBackends.CkzgNative,
          commitment.toArray,
          z.toArray,
          y.toArray,
          proof.toArray
        )
      if !verified then return None // scalafix:ok DisableSyntax.return

      // Return FIELD_ELEMENTS_PER_BLOB ++ BLS_MODULUS as 32-byte big-endian
      val result = ByteString(
        com.chipprbots.ethereum.utils.ByteUtils.padLeft(ByteString(FIELD_ELEMENTS_PER_BLOB.toByteArray), 32).toArray ++
          com.chipprbots.ethereum.utils.ByteUtils.padLeft(ByteString(BLS_MODULUS.toByteArray), 32).toArray
      )
      Some(result)

    /** go-ethereum `kzgPointEvaluation.Run`: 192 bytes, then the versioned hash, then `kzg4844.VerifyProof`, whose
      * error it wraps as "error verifying kzg proof: ...". go-ethereum's default KZG backend is go-eth-kzg (v1.5.0),
      * whose `Context.VerifyKZGProof` checks y, z, the commitment, the proof, and last the pairing.
      *
      * fukuii's `exec` checks in another order (z and y before the hash); any input fails both or neither.
      */
    def failureReason(inputData: ByteString): String =
      if inputData.length != 192 then "invalid input length"
      else
        val (z, y) = (inputData.slice(32, 64), inputData.slice(64, 96))
        val (commitment, proof) = (inputData.slice(96, 144), inputData.slice(144, 192))
        if versionedHash(commitment) != inputData.slice(0, 32) then "mismatched versioned hash"
        else verifyProofFailure(z, y, commitment, proof).fold(UnexplainedFailure)("error verifying kzg proof: " + _)

    /** go-ethereum `kZGToVersionedHash`: SHA-256 of the commitment with its first byte replaced by the version. */
    private def versionedHash(commitment: ByteString): ByteString =
      val hash = sha256(commitment.toArray)
      hash(0) = VERSIONED_HASH_VERSION_KZG
      ByteString(hash)

    /** go-eth-kzg's error for a proof that fails, `None` for one that verifies.
      *
      * gnark-crypto checks a point's subgroup as soon as it decodes it, so a commitment outside G1 fails before the
      * proof is read. The subgroup checks and the pairing are c-kzg's ([[NativeBackends.kzgVerdict]]): with every
      * encoding already valid, a c-kzg rejection can only be a point outside G1. It answers for the commitment alone
      * when the proof is the point at infinity. A c-kzg that cannot run leaves the pairing as the reason.
      */
    private def verifyProofFailure(
        z: ByteString,
        y: ByteString,
        commitment: ByteString,
        proof: ByteString
    ): Option[String] =
      import NativeBackends.KzgVerdict
      val subgroup = "invalid point: subgroup check failed"
      def nonCanonical(scalar: ByteString): Option[String] =
        Option.when(BigInt(1, scalar.toArray) >= BLS_MODULUS)(
          "scalar is not canonical when interpreted as a big integer in big-endian"
        )
      def verdict(c: ByteString, at: ByteString, value: ByteString, p: ByteString): KzgVerdict =
        NativeBackends.kzgVerdict(NativeBackends.CkzgNative, c.toArray, at.toArray, value.toArray, p.toArray)
      def commitmentOutsideG1: Option[String] =
        Option.when(verdict(commitment, z, y, KzgG1Encoding.Infinity) == KzgVerdict.RejectsArgument)(subgroup)
      nonCanonical(y)
        .orElse(nonCanonical(z))
        .orElse(KzgG1Encoding.decodeFailure(commitment))
        .orElse(commitmentOutsideG1)
        .orElse(KzgG1Encoding.decodeFailure(proof))
        .orElse {
          verdict(commitment, z, y, proof) match
            case KzgVerdict.Verifies                               => None
            case KzgVerdict.RejectsArgument                        => Some(subgroup)
            case KzgVerdict.DoesNotVerify | KzgVerdict.Unavailable => Some("can't verify opening proof")
        }

  /** gnark-crypto `bls12381.G1Affine.SetBytes` on 48 bytes, which go-eth-kzg's `DeserializeKZGCommitment` and
    * `DeserializeKZGProof` call: the three ZCash flag bits, then x below p, then a square root of x^3 + 4. The subgroup
    * check that follows is c-kzg's (`KzgPointEvaluation.verifyProofFailure`).
    */
  private object KzgG1Encoding:
    private val P = Bls12381FieldModulus

    val Infinity: ByteString = ByteString(0xc0.toByte) ++ ByteString(new Array[Byte](47))

    def decodeFailure(bytes: ByteString): Option[String] =
      (bytes(0) & 0xe0) match
        case 0xe0 | 0x60 | 0x20 => Some("invalid point encoding")
        // the uncompressed encodings need 96 bytes: io.ErrShortBuffer
        case 0x00 | 0x40 => Some("short buffer")
        case 0xc0 =>
          Option.when((bytes(0) & 0x1f) != 0 || bytes.drop(1).exists(_ != 0))("invalid infinity point encoding")
        case _ => // 0x80 and 0xa0: compressed, the sign of y in the flags
          val x = BigInt(1, (ByteString((bytes(0) & 0x1f).toByte) ++ bytes.drop(1)).toArray)
          if x >= P then Some("invalid fp.Element encoding")
          else
            val ySquared = (x * x * x + 4).mod(P)
            Option.unless(ySquared == 0 || ySquared.modPow((P - 1) / 2, P) == 1)(
              "invalid compressed coordinate: square root doesn't exist"
            )

  /** EIP-2537 G1 MSM discount table, k = 1..128, verbatim from the EIP ("Discounts table for G1 MSM") and identical to
    * go-ethereum params.Bls12381G1MultiExpDiscountTable. max_discount = 519 for k > 128.
    *
    * An earlier hand-entered table diverged from the EIP at 116 of 128 entries from k = 6 onward (740 where the EIP has
    * 750, ...), undercharging 47 values of k and overcharging 69. execution-spec-tests
    * test_bls12_variable_length_input_contracts.py::test_invalid_gas_g1msm calls 0x0c with (EIP cost - 1) gas for k =
    * 1..129 and expects every call to fail; the undercharged calls succeeded, and fukuii rejected the valid block.
    */
  private val BlsG1MsmDiscountTable: Array[Int] = Array(
    1000, 949, 848, 797, 764, 750, 738, 728, 719, 712, 705, 698, 692, 687, 682, 677, 673, 669, 665, 661, 658, 654, 651,
    648, 645, 642, 640, 637, 635, 632, 630, 627, 625, 623, 621, 619, 617, 615, 613, 611, 609, 608, 606, 604, 603, 601,
    599, 598, 596, 595, 593, 592, 591, 589, 588, 586, 585, 584, 582, 581, 580, 579, 577, 576, 575, 574, 573, 572, 570,
    569, 568, 567, 566, 565, 564, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 551, 550, 549, 548, 547,
    547, 546, 545, 544, 543, 542, 541, 540, 540, 539, 538, 537, 536, 536, 535, 534, 533, 532, 532, 531, 530, 529, 528,
    528, 527, 526, 525, 525, 524, 523, 522, 522, 521, 520, 520, 519
  )

  /** EIP-2537 G2 MSM discount table, k = 1..128, verbatim from the EIP ("Discounts table for G2 MSM") and identical to
    * go-ethereum params.Bls12381G2MultiExpDiscountTable. max_discount = 524 for k > 128.
    *
    * The earlier hand-entered table diverged at 117 of 128 entries from k = 12 onward, every one of them overcharging
    * (750 where the EIP has 749, ...), so a G2 MSM given exactly the EIP cost ran out of gas.
    */
  private val BlsG2MsmDiscountTable: Array[Int] = Array(
    1000, 1000, 923, 884, 855, 832, 812, 796, 782, 770, 759, 749, 740, 732, 724, 717, 711, 704, 699, 693, 688, 683, 679,
    674, 670, 666, 663, 659, 655, 652, 649, 646, 643, 640, 637, 634, 632, 629, 627, 624, 622, 620, 618, 615, 613, 611,
    609, 607, 606, 604, 602, 600, 598, 597, 595, 593, 592, 590, 589, 587, 586, 584, 583, 582, 580, 579, 578, 576, 575,
    574, 573, 571, 570, 569, 568, 567, 566, 565, 563, 562, 561, 560, 559, 558, 557, 556, 555, 554, 553, 552, 552, 551,
    550, 549, 548, 547, 546, 545, 545, 544, 543, 542, 541, 541, 540, 539, 538, 537, 537, 536, 535, 535, 534, 533, 532,
    532, 531, 530, 530, 529, 528, 528, 527, 526, 526, 525, 524, 524
  )

  private def blsG1MsmDiscount(k: Int): Int =
    if k <= 0 then 1000
    else if k <= BlsG1MsmDiscountTable.length then BlsG1MsmDiscountTable(k - 1)
    else 519 // max_discount, k > 128

  private def blsG2MsmDiscount(k: Int): Int =
    if k <= 0 then 1000
    else if k <= BlsG2MsmDiscountTable.length then BlsG2MsmDiscountTable(k - 1)
    else 524 // max_discount, k > 128

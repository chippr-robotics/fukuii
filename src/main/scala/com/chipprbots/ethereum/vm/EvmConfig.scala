package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum

import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm

import EvmConfig.*

// scalastyle:off magic.number
object EvmConfig:

  type EvmConfigBuilder = BlockchainConfigForEvm => EvmConfig

  val MaxCallDepth: Int = 1024

  val MaxMemory: UInt256 = UInt256(
    Int.MaxValue
  ) /* used to artificially limit memory usage by incurring maximum gas cost */

  /** returns the evm config that should be used for given block
    */
  def forBlock(blockNumber: BigInt, blockchainConfig: BlockchainConfig): EvmConfig =
    forBlock(blockNumber, BlockchainConfigForEvm(blockchainConfig))

  /** returns the evm config for a given block, applying timestamp-based fork overrides for post-merge ETH chains.
    */
  def forBlock(blockNumber: BigInt, timestamp: Timestamp, blockchainConfig: BlockchainConfig): EvmConfig =
    var config = forBlock(blockNumber, blockchainConfig)
    // Apply timestamp-based fork upgrades for ETH chains
    if blockchainConfig.isShanghaiTimestamp(timestamp) then
      config = config.copy(
        opCodeList = ShanghaiOpCodes, // London + PUSH0 (EIP-3855); NOT SpiralOpCodes, which lacks BASEFEE
        eip3651Enabled = true, // Warm COINBASE
        eip3860Enabled = true // Initcode metering
      )
    if blockchainConfig.isCancunTimestamp(timestamp) then
      config = config.copy(
        // Adds TSTORE/TLOAD/MCOPY/BLOBHASH/BLOBBASEFEE. NOT OlympiaOpCodes: that list carries CLZ (EIP-7939),
        // which is Osaka-only — hive consume-engine test_all_opcodes measured the leak at -49,898 gas.
        opCodeList = CancunOpCodes,
        feeSchedule = new FeeSchedule.OlympiaFeeSchedule,
        eip6780Enabled = true // SELFDESTRUCT restriction
      )
    if blockchainConfig.isPragueTimestamp(timestamp) then
      config = config.copy(
        feeSchedule = new FeeSchedule.PragueFeeSchedule, // EIP-7623: increased calldata costs
        eip7702Enabled = true // EIP-7702: delegation designators are followed
      )
    if blockchainConfig.isOsakaTimestamp(timestamp) then
      config = config.copy(
        feeSchedule = new FeeSchedule.OsakaFeeSchedule,
        opCodeList = OsakaOpCodes // EIP-7939: CLZ opcode
      )
    // Amsterdam (ETH-family only). Gated on a timestamp that no ETC-family config declares, so this
    // branch is unreachable on ETC/Mordor/Gorgoroth — asserted by ChainConfigMatrixSpec against the
    // SHIPPED config files, not left to convention.
    //
    // Adds no opcodes. What it changes is the gas model: EIP-8038 access/write repricing (fee schedule),
    // EIP-8037 state-gas metering, EIP-2780 intrinsic decomposition, EIP-7778 block accounting,
    // EIP-7708 transfer logs and EIP-7954 size limits — all behind `amsterdamEnabled`.
    if blockchainConfig.isAmsterdamTimestamp(timestamp) then
      config = config.copy(
        feeSchedule = new FeeSchedule.AmsterdamFeeSchedule,
        amsterdamEnabled = true
      )
    config

  /** returns the evm config that should be used for given block
    */
  def forBlock(blockNumber: BigInt, blockchainConfig: BlockchainConfigForEvm): EvmConfig =
    // When ETC-specific forks (Spiral, Mystique) activate AFTER Olympia, the chain follows
    // standard Ethereum fork schedule where London only activates EIP-1559/3529/3541.
    // On ETC, Spiral < Olympia in the fork sequence, so Olympia bundles all EIPs.
    val etcForksDisabled = blockchainConfig.spiralBlockNumber > blockchainConfig.olympiaBlockNumber
    val olympiaBuilder = if etcForksDisabled then LondonConfigBuilder else OlympiaConfigBuilder

    val transitionBlockToConfigWithPriorityMapping: List[(BigInt, Int, EvmConfigBuilder)] = List(
      (blockchainConfig.frontierBlockNumber, 1, FrontierConfigBuilder),
      (blockchainConfig.homesteadBlockNumber, 2, HomesteadConfigBuilder),
      (blockchainConfig.eip150BlockNumber, 3, PostEIP150ConfigBuilder),
      (blockchainConfig.eip160BlockNumber, 4, PostEIP160ConfigBuilder),
      (blockchainConfig.eip161BlockNumber, 5, PostEIP161ConfigBuilder),
      (blockchainConfig.byzantiumBlockNumber, 6, ByzantiumConfigBuilder),
      // ETC forks may intentionally share the same activation height as their ETH counterparts.
      // In that case we must prefer the ETC config, otherwise gas accounting/opcodes can diverge.
      (blockchainConfig.atlantisBlockNumber, 7, AtlantisConfigBuilder),
      (blockchainConfig.constantinopleBlockNumber, 8, ConstantinopleConfigBuilder),
      (blockchainConfig.aghartaBlockNumber, 9, AghartaConfigBuilder),
      (blockchainConfig.petersburgBlockNumber, 10, PetersburgConfigBuilder),
      (blockchainConfig.istanbulBlockNumber, 11, IstanbulConfigBuilder),
      (blockchainConfig.phoenixBlockNumber, 12, PhoenixConfigBuilder),
      (blockchainConfig.magnetoBlockNumber, 13, MagnetoConfigBuilder),
      (blockchainConfig.berlinBlockNumber, 14, BerlinConfigBuilder),
      (blockchainConfig.mystiqueBlockNumber, 15, MystiqueConfigBuilder),
      (blockchainConfig.spiralBlockNumber, 16, SpiralConfigBuilder),
      (blockchainConfig.olympiaBlockNumber, 17, olympiaBuilder)
    )

    // highest transition block that is less/equal to `blockNumber`
    val evmConfigBuilder = transitionBlockToConfigWithPriorityMapping
      .filterNot { case (number, _, _) => number > blockNumber }
      .maxBy { case (number, priority, _) => (number, priority) }
      ._3

    evmConfigBuilder(blockchainConfig)

  val FrontierOpCodes: OpCodeList = OpCodeList(OpCodes.FrontierOpCodes)
  val HomesteadOpCodes: OpCodeList = OpCodeList(OpCodes.HomesteadOpCodes)
  val ByzantiumOpCodes: OpCodeList = OpCodeList(OpCodes.ByzantiumOpCodes)
  val AtlantisOpCodes = ByzantiumOpCodes
  val ConstantinopleOpCodes: OpCodeList = OpCodeList(OpCodes.ConstantinopleOpCodes)
  val AghartaOpCodes = ConstantinopleOpCodes
  val PhoenixOpCodes: OpCodeList = OpCodeList(OpCodes.PhoenixOpCodes)
  val MagnetoOpCodes: OpCodeList = PhoenixOpCodes
  val SpiralOpCodes: OpCodeList = OpCodeList(OpCodes.SpiralOpCodes)
  val OlympiaOpCodes: OpCodeList = OpCodeList(OpCodes.OlympiaOpCodes)
  val EtcOlympiaOpCodes: OpCodeList = OpCodeList(OpCodes.EtcOlympiaOpCodes)
  // ETH-only tables — see OpCodes.LondonOpCodes for why none of these is reachable on ETC.
  val LondonOpCodes: OpCodeList = OpCodeList(OpCodes.LondonOpCodes)
  val ShanghaiOpCodes: OpCodeList = OpCodeList(OpCodes.ShanghaiOpCodes)
  val CancunOpCodes: OpCodeList = OpCodeList(OpCodes.CancunOpCodes)
  val OsakaOpCodes: OpCodeList = OpCodeList(OpCodes.OsakaOpCodes)

  val FrontierConfigBuilder: EvmConfigBuilder = config =>
    EvmConfig(
      blockchainConfig = config,
      feeSchedule = new FeeSchedule.FrontierFeeSchedule,
      opCodeList = FrontierOpCodes,
      exceptionalFailedCodeDeposit = false,
      subGasCapDivisor = None,
      chargeSelfDestructForNewAccount = false,
      traceInternalTransactions = false
    )

  val HomesteadConfigBuilder: EvmConfigBuilder = config =>
    EvmConfig(
      blockchainConfig = config,
      feeSchedule = new FeeSchedule.HomesteadFeeSchedule,
      opCodeList = HomesteadOpCodes,
      exceptionalFailedCodeDeposit = true,
      subGasCapDivisor = None,
      chargeSelfDestructForNewAccount = false,
      traceInternalTransactions = false
    )

  val PostEIP150ConfigBuilder: EvmConfigBuilder = config =>
    HomesteadConfigBuilder(config).copy(
      feeSchedule = new FeeSchedule.PostEIP150FeeSchedule,
      subGasCapDivisor = Some(64),
      chargeSelfDestructForNewAccount = true
    )

  val PostEIP160ConfigBuilder: EvmConfigBuilder = config =>
    PostEIP150ConfigBuilder(config).copy(feeSchedule = new FeeSchedule.PostEIP160FeeSchedule)

  val PostEIP161ConfigBuilder: EvmConfigBuilder = config => PostEIP160ConfigBuilder(config).copy(noEmptyAccounts = true)

  val ByzantiumConfigBuilder: EvmConfigBuilder = config =>
    PostEIP161ConfigBuilder(config).copy(
      feeSchedule = new FeeSchedule.ByzantiumFeeSchedule,
      opCodeList = ByzantiumOpCodes
    )

  val ConstantinopleConfigBuilder: EvmConfigBuilder = config =>
    ByzantiumConfigBuilder(config).copy(
      feeSchedule = new vm.FeeSchedule.ConstantionopleFeeSchedule,
      opCodeList = ConstantinopleOpCodes
    )

  val PetersburgConfigBuilder: EvmConfigBuilder = config => ConstantinopleConfigBuilder(config)

  val IstanbulConfigBuilder: EvmConfigBuilder = config => PhoenixConfigBuilder(config)

  // Ethereum classic forks only
  val AtlantisConfigBuilder: EvmConfigBuilder = config =>
    PostEIP160ConfigBuilder(config).copy(
      feeSchedule = new FeeSchedule.AtlantisFeeSchedule,
      opCodeList = AtlantisOpCodes,
      noEmptyAccounts = true
    )

  val AghartaConfigBuilder: EvmConfigBuilder = config =>
    AtlantisConfigBuilder(config).copy(
      feeSchedule = new vm.FeeSchedule.ConstantionopleFeeSchedule,
      opCodeList = AghartaOpCodes
    )

  val PhoenixConfigBuilder: EvmConfigBuilder = config =>
    AghartaConfigBuilder(config).copy(
      feeSchedule = new ethereum.vm.FeeSchedule.PhoenixFeeSchedule,
      opCodeList = PhoenixOpCodes
    )

  val MagnetoConfigBuilder: EvmConfigBuilder = config =>
    PhoenixConfigBuilder(config).copy(
      feeSchedule = new ethereum.vm.FeeSchedule.MagnetoFeeSchedule,
      opCodeList = MagnetoOpCodes
    )

  val BerlinConfigBuilder: EvmConfigBuilder = MagnetoConfigBuilder

  val MystiqueConfigBuilder: EvmConfigBuilder = config =>
    MagnetoConfigBuilder(config).copy(
      feeSchedule = new ethereum.vm.FeeSchedule.MystiqueFeeSchedule,
      eip3541Enabled = true
    )

  val SpiralConfigBuilder: EvmConfigBuilder = config =>
    MystiqueConfigBuilder(config).copy(
      opCodeList = SpiralOpCodes,
      eip3651Enabled = true,
      eip3860Enabled = true,
      eip6049DeprecationEnabled = true
    )

  /** London-only config for ETH chains. Enables EIP-1559/3529/3541 without Shanghai+ EIPs. Used when Olympia block
    * number differs from Spiral/Mystique (i.e., ETH fork schedule).
    */
  val LondonConfigBuilder: EvmConfigBuilder = config =>
    MagnetoConfigBuilder(config).copy(
      // EIP-3198 BASEFEE. MagnetoOpCodes (inherited above) is the ETC/Berlin table and has no 0x48; hive
      // consume-engine test_all_opcodes measured the omission at +15,002 gas on Paris and Shanghai.
      opCodeList = LondonOpCodes,
      feeSchedule = new ethereum.vm.FeeSchedule.MystiqueFeeSchedule, // EIP-3529 refund changes
      eip3541Enabled = true // EIP-3541: reject 0xEF contracts
    )

  val OlympiaConfigBuilder: EvmConfigBuilder = config =>
    SpiralConfigBuilder(config).copy(
      opCodeList = EtcOlympiaOpCodes,
      feeSchedule = new FeeSchedule.OlympiaFeeSchedule,
      eip6780Enabled = true,
      eip7702Enabled = true // ECIP-1121: EIP-7702 activates with Olympia, together with Type-4 tx admission
    )

  case class OpCodeList(opCodes: List[OpCode]):
    val byteToOpCode: Map[Byte, OpCode] =
      opCodes.map(op => op.code -> op).toMap

case class EvmConfig(
    blockchainConfig: BlockchainConfigForEvm,
    feeSchedule: FeeSchedule,
    opCodeList: OpCodeList,
    exceptionalFailedCodeDeposit: Boolean,
    subGasCapDivisor: Option[Long],
    chargeSelfDestructForNewAccount: Boolean,
    traceInternalTransactions: Boolean,
    noEmptyAccounts: Boolean = false,
    eip3541Enabled: Boolean = false,
    eip3651Enabled: Boolean = false,
    eip3860Enabled: Boolean = false,
    eip6049DeprecationEnabled: Boolean = false,
    eip6780Enabled: Boolean = false,
    /** EIP-7702: code of the form 0xef0100 ++ address is a delegation designator — CALL-family and tx-level calls run
      * the target's code, warm the target and (CALL family) pay its access cost. Enabled at Prague on ETH (timestamp)
      * and at Olympia on ETC (block number), the same forks that admit Type-4 transactions. Before that, such code is
      * ordinary bytecode: 0xEF is an undefined opcode, so executing it is an exceptional halt. That matters on ETC,
      * where a 23-byte 0xef0100 contract could be deployed before Mystique (EIP-3541); core-geth, which has no
      * EIP-7702, executes it as INVALID.
      */
    eip7702Enabled: Boolean = false,
    /** Amsterdam (ETH-family). Gates EIP-8037 state-gas metering, EIP-2780's intrinsic decomposition, EIP-7708
      * value-transfer logs and EIP-7954's size limits. `false` on every ETC path.
      */
    amsterdamEnabled: Boolean = false
):

  import feeSchedule.*
  import EvmConfig.*

  def opCodes: List[OpCode] =
    opCodeList.opCodes

  def byteToOpCode: Map[Byte, OpCode] =
    opCodeList.byteToOpCode

  /** Calculate gas cost of memory usage. Incur a blocking gas cost if memory usage exceeds reasonable limits.
    *
    * @param memSize
    *   current memory size in bytes
    * @param offset
    *   memory offset to be written/read
    * @param dataSize
    *   size of data to be written/read in bytes
    * @return
    *   gas cost
    */
  def calcMemCost(memSize: BigInt, offset: BigInt, dataSize: BigInt): BigInt =

    /** See YP H.1 (222) */
    def c(m: BigInt): BigInt =
      val a = wordsForBytes(m)
      G_memory * a + a * a / 512

    val memNeeded = if dataSize == 0 then BigInt(0) else offset + dataSize
    if memNeeded > MaxMemory then UInt256.MaxValue / 2
    else if memNeeded <= memSize then 0
    else c(memNeeded) - c(memSize)

  /** Calculates transaction intrinsic gas. See YP section 6.2
    *
    * `to`, `value` and `sender` exist for EIP-2780 (Amsterdam), which decomposes the flat 21,000 into named primitives
    * whose applicability depends on the destination and the value. They are required rather than defaulted on purpose:
    * a default would silently mis-price every Amsterdam transaction at a call site that forgot them, and the compiler
    * is the only thing that reliably notices. Pre-Amsterdam they are ignored.
    */
  def calcTransactionIntrinsicGas(
      txData: ByteString,
      isContractCreation: Boolean,
      accessList: Seq[AccessListItem],
      authorizationListSize: Int,
      to: Option[Address],
      value: UInt256,
      sender: Address
  ): BigInt =
    val txDataZero = txData.count(_ == 0)
    val txDataNonZero = txData.length - txDataZero

    val calldataPrice: BigInt = txDataZero * G_txdatazero + txDataNonZero * G_txdatanonzero

    val accessListPrice =
      accessList.size * G_access_list_address +
        accessList.map(_.storageKeys.size).sum * G_access_list_storage

    val initCodeCost: BigInt = if isContractCreation then calcInitCodeCost(txData) else BigInt(0)

    if amsterdamEnabled then
      // EIP-2780 replaces the flat base AND EIP-7702's flat PER_AUTH_BASE_COST. Calldata and access-list
      // metering are explicitly unchanged.
      val authListPrice: BigInt = BigInt(authorizationListSize) * AmsterdamGas.ExecutionPerAuthBaseCost
      transactionBaseCost(to, value, sender) + calldataPrice + accessListPrice + authListPrice + initCodeCost
    else
      // EIP-7702: Per-authorization intrinsic gas = PER_AUTH_BASE_COST (25000) per EIP spec
      val authListPrice: BigInt = BigInt(authorizationListSize) * BigInt(25000)
      calldataPrice + accessListPrice + authListPrice +
        (if isContractCreation then G_txcreate else 0) +
        G_transaction +
        initCodeCost

  /** EIP-2780's decomposed transaction base cost — the state-INDEPENDENT part of intrinsic gas.
    *
    * This is also the base the EIP-7623 calldata floor sits on once Amsterdam is active (EIP-2780, "Interactions with
    * other EIPs"): the floor's fixed term is no longer the stale 21,000 but this same sum, with per-authorization and
    * initcode-word charges excluded. That is not a nicety — a floor still anchored at 21,000 would drag the measured
    * 12,000 self-transfer back up to 21,000 and the measured 17,201 `tx-callrevert` up to 21,040, contradicting the
    * fixture on both.
    *
    * Pre-Amsterdam this returns the legacy flat cost, so the floor keeps its EIP-7623 meaning unchanged.
    */
  def transactionBaseCost(to: Option[Address], value: UInt256, sender: Address): BigInt =
    // Pre-Amsterdam this is EIP-7623's flat 21,000 and NOTHING else. In particular it must not include
    // `G_txcreate`: EIP-7623's floor is `21000 + tokens * 10` for every transaction shape, creation
    // included, and folding the 32,000 creation surcharge into it would raise the floor on contract
    // deployments — a live pre-Amsterdam and ETC behaviour change with no EIP behind it.
    if !amsterdamEnabled then G_transaction
    else
      val isSelfTransfer = to.contains(sender)
      val recipientCost: BigInt =
        if isSelfTransfer then 0 // no charge at all for tx.to == tx.sender
        else if to.isEmpty then AmsterdamGas.CreateAccess // deployment account access + write
        else AmsterdamGas.ColdAccountAccess // recipient touch, always at the cold rate
      val valueCost: BigInt =
        // A creation's recipient balance write is already covered by CREATE_ACCESS; a self-transfer
        // moves nothing and emits no EIP-7708 log, so it pays nothing.
        if value.isZero || isSelfTransfer || to.isEmpty then 0
        else AmsterdamGas.TxValueCost
      AmsterdamGas.TxBaseCost + recipientCost + valueCost

  /** If the initialization code completes successfully, a final contract-creation cost is paid, the code-deposit cost,
    * proportional to the size of the created contract’s code. See YP equation (96)
    *
    * @param executionResultData
    *   Transaction code initialization result
    * @return
    *   Calculated gas cost
    */
  def calcCodeDepositCost(executionResultData: ByteString): BigInt =
    G_codedeposit * executionResultData.size

  /** a helper method used for gas adjustment in CALL and CREATE opcode, see YP eq. (224)
    */
  def gasCap(g: BigInt): BigInt =
    subGasCapDivisor.map(d => g - g / d).getOrElse(g)

  /** EIP-7954 (Amsterdam) raises EIP-170's 24 KiB code limit to 64 KiB. The chain-config value is what every
    * pre-Amsterdam path — including every ETC fork — continues to read.
    */
  def maxCodeSize: Option[BigInt] =
    if amsterdamEnabled then Some(AmsterdamGas.MaxCodeSize) else blockchainConfig.maxCodeSize

  /** EIP-3860: Maximum initcode size (2 * MAX_CODE_SIZE). EIP-7954 raises it to a flat 128 KiB, which is 2 x the new
    * code limit — stated as its own constant in the EIP rather than derived, so it is stated as its own constant here.
    */
  def maxInitCodeSize: Option[BigInt] =
    if amsterdamEnabled then Some(AmsterdamGas.MaxInitCodeSize)
    else if eip3860Enabled then maxCodeSize.map(_ * 2)
    else None

  /** EIP-3860: Calculate gas cost for initcode
    * @param initCode
    *   The initialization code
    * @return
    *   Gas cost (INITCODE_WORD_COST * ceil(len(initcode) / 32))
    */
  def calcInitCodeCost(initCode: ByteString): BigInt =
    if eip3860Enabled then
      val words = wordsForBytes(initCode.size)
      feeSchedule.G_initcode_word * words
    else BigInt(0)

object FeeSchedule:

  class FrontierFeeSchedule extends FeeSchedule:
    override val G_zero = 0
    override val G_base = 2
    override val G_verylow = 3
    override val G_low = 5
    override val G_mid = 8
    override val G_high = 10
    override val G_balance = 20
    override val G_sload = 50
    override val G_jumpdest = 1
    override val G_sset = 20000
    override val G_sreset = 5000
    override val R_sclear = 15000
    override val R_selfdestruct = 24000
    override val G_selfdestruct = 0
    override val G_create = 32000
    override val G_codedeposit = 200
    override val G_call = 40
    override val G_callvalue = 9000
    override val G_callstipend = 2300
    override val G_newaccount = 25000
    override val G_exp = 10
    override val G_expbyte = 10
    override val G_memory = 3
    override val G_txcreate = 0
    override val G_txdatazero = 4
    override val G_txdatanonzero = 68
    override val G_transaction = 21000
    override val G_log = 375
    override val G_logdata = 8
    override val G_logtopic = 375
    override val G_sha3 = 30
    override val G_sha3word = 6
    override val G_copy = 3
    override val G_blockhash = 20
    override val G_extcode = 20

    // note: the access list and cold/warm access do not exist until magneto hard fork
    override val G_cold_sload = 2100
    override val G_cold_account_access = 2600
    override val G_warm_storage_read = 100
    override val G_access_list_address = 2400
    override val G_access_list_storage = 1900
    // note: initcode metering does not exist until spiral hard fork (EIP-3860)
    override val G_initcode_word = 0

  class HomesteadFeeSchedule extends FrontierFeeSchedule:
    override val G_txcreate = 32000

  class PostEIP150FeeSchedule extends HomesteadFeeSchedule:
    override val G_sload = 200
    override val G_call = 700
    override val G_balance = 400
    override val G_selfdestruct = 5000
    override val G_extcode = 700

  class PostEIP160FeeSchedule extends PostEIP150FeeSchedule:
    override val G_expbyte = 50

  class ByzantiumFeeSchedule extends PostEIP160FeeSchedule

  class ConstantionopleFeeSchedule extends ByzantiumFeeSchedule

  class AtlantisFeeSchedule extends PostEIP160FeeSchedule

  class AghartaFeeSchedule extends ByzantiumFeeSchedule

  class PhoenixFeeSchedule extends AghartaFeeSchedule:
    override val G_sload: BigInt = 800
    override val G_balance: BigInt = 700
    override val G_txdatanonzero = 16

  class MagnetoFeeSchedule extends PhoenixFeeSchedule:
    override val G_sload: BigInt = G_warm_storage_read
    override val G_sreset: BigInt = 5000 - G_cold_sload
    override val G_sset: BigInt = 20000 // EIP-2929: G_sset remains 20000, cold access cost added separately in SSTORE
    override val G_access_list_address: BigInt = 2400
    override val G_access_list_storage: BigInt = 1900

  class MystiqueFeeSchedule extends MagnetoFeeSchedule:
    // EIP-3529: Reduce refunds for SSTORE
    // R_sclear = SSTORE_RESET_GAS + ACCESS_LIST_STORAGE_KEY_COST = 2900 + 1900 = 4800
    override val R_sclear: BigInt = 4800
    // EIP-3529: Remove SELFDESTRUCT refund
    override val R_selfdestruct: BigInt = 0
    // EIP-3860: Initcode metering (activated in Spiral fork)
    override val G_initcode_word: BigInt = 2

  class OlympiaFeeSchedule extends MystiqueFeeSchedule

  /** Prague fee schedule — EIP-7623 does NOT modify G_txdatazero/G_txdatanonzero (still 4/16). Instead it adds a
    * calldata floor via `calcFloorDataGas` applied by BlockPreparator as `max(executionGasBase, 21000 + tokens * 10)`.
    * See BlockPreparator.calcFloorDataGas.
    */
  class PragueFeeSchedule extends OlympiaFeeSchedule

  /** Osaka fee schedule — same as Prague. MODEXP cost doubling (EIP-7883) and input bounds (EIP-7823) are enforced
    * inside the MODEXP precompile itself, not the fee schedule.
    */
  class OsakaFeeSchedule extends PragueFeeSchedule

  /** Amsterdam fee schedule (ETH-family only).
    *
    * Only the slots EIP-8038 actually reprices are overridden. Everything else — `COLD_STORAGE_ACCESS` 2,100,
    * `WARM_ACCESS` 100, the EIP-2929 cold/warm rules, calldata pricing, `CALL_STIPEND` — is unchanged by the EIP and
    * therefore unchanged here.
    *
    * Three of these overrides set a slot to **zero** because the charge has moved to the state-gas dimension rather
    * than disappeared: `G_newaccount` (EIP-8037 `GAS_NEW_ACCOUNT`), `G_codedeposit` (CPSB per byte) and, implicitly,
    * `G_sset`, whose SSTORE branch the Amsterdam path no longer reaches. A zero here with no corresponding state charge
    * would under-price state growth by exactly the amount the fork exists to raise, so each is paired with an explicit
    * charge site: `CallOp`/`SELFDESTRUCT`/`CreateOp` for the account leaf, `VM.saveNewContract` for the code deposit,
    * `SSTORE` for the slot.
    *
    * Validated against go-ethereum's devp2p reference chain: these values reproduce block 41's measured receipt figure
    * of 326,947 and header figure of 183,600 exactly (AmsterdamGasAccountingSpec).
    */
  class AmsterdamFeeSchedule extends OsakaFeeSchedule:
    // ── EIP-8038: access repricing ──────────────────────────────────────────
    /** COLD_ACCOUNT_ACCESS 2,600 -> 3,000 (+15%). */
    override val G_cold_account_access: BigInt = AmsterdamGas.ColdAccountAccess

    /** ACCESS_LIST_ADDRESS_COST 2,400 -> 2,900 (+21%). */
    override val G_access_list_address: BigInt = 2900

    /** ACCESS_LIST_STORAGE_KEY_COST 1,900 -> 2,000 (+5%). */
    override val G_access_list_storage: BigInt = 2000

    /** STORAGE_CLEAR_REFUND 4,800 -> 11,616 (+142%). */
    override val R_sclear: BigInt = AmsterdamGas.StorageClearRefund

    // ── EIP-8038: write surcharges folded into existing composites ──────────
    /** CALL_VALUE is redefined as ACCOUNT_WRITE (9,000) + CALL_STIPEND (2,300). The stipend itself is unchanged. */
    override val G_callvalue: BigInt = AmsterdamGas.AccountWrite + 2300

    /** GAS_CREATE 32,000 -> CREATE_ACCESS 12,000 = ACCOUNT_WRITE + COLD_ACCOUNT_ACCESS. */
    override val G_create: BigInt = AmsterdamGas.CreateAccess

    // ── EIP-8037: charges that leave the execution dimension entirely ───────
    /** GAS_NEW_ACCOUNT leaves execution gas; charged as STATE_BYTES_PER_NEW_ACCOUNT x CPSB in state gas. */
    override val G_newaccount: BigInt = 0

    /** Code deposit leaves execution gas at 200/byte; charged as CPSB per byte in state gas, with only the 6-per-word
      * hash cost remaining in execution (applied in `VM.saveNewContract`).
      */
    override val G_codedeposit: BigInt = 0

    // ── EIP-2780: the decomposed transaction base ───────────────────────────
    /** TX_BASE_COST. Read only through `EvmConfig.transactionBaseCost`, which adds the recipient and value terms. */
    override val G_transaction: BigInt = AmsterdamGas.TxBaseCost

trait FeeSchedule:
  val G_zero: BigInt
  val G_base: BigInt
  val G_verylow: BigInt
  val G_low: BigInt
  val G_mid: BigInt
  val G_high: BigInt
  val G_balance: BigInt
  val G_sload: BigInt
  val G_jumpdest: BigInt
  val G_sset: BigInt
  val G_sreset: BigInt
  val R_sclear: BigInt
  val R_selfdestruct: BigInt
  val G_selfdestruct: BigInt
  val G_create: BigInt
  val G_codedeposit: BigInt
  val G_call: BigInt
  val G_callvalue: BigInt
  val G_callstipend: BigInt
  val G_newaccount: BigInt
  val G_exp: BigInt
  val G_expbyte: BigInt
  val G_memory: BigInt
  val G_txcreate: BigInt
  val G_txdatazero: BigInt
  val G_txdatanonzero: BigInt
  val G_transaction: BigInt
  val G_log: BigInt
  val G_logdata: BigInt
  val G_logtopic: BigInt
  val G_sha3: BigInt
  val G_sha3word: BigInt
  val G_copy: BigInt
  val G_blockhash: BigInt
  val G_extcode: BigInt
  val G_cold_sload: BigInt
  val G_cold_account_access: BigInt
  val G_warm_storage_read: BigInt
  val G_access_list_address: BigInt
  val G_access_list_storage: BigInt
  val G_initcode_word: BigInt

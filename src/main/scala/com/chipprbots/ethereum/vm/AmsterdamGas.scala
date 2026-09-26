package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.TxLogEntry
import com.chipprbots.ethereum.domain.UInt256

/** Gas parameters introduced by the Amsterdam hard fork (ETH-family only).
  *
  * These live outside [[FeeSchedule]] deliberately. A `FeeSchedule` slot is a value that *varies* across forks and is
  * read by opcodes on every chain; adding members to that trait perturbs the surface every ETC fee schedule inherits.
  * Everything below is either a brand-new named parameter with no pre-Amsterdam counterpart (`AccountWrite`,
  * `StorageWrite`, `CreateAccess`, `TxValueCost`) or a state-dimension product that no pre-Amsterdam code path can
  * reach. Parameters that genuinely *reprice an existing schedule slot* — `COLD_ACCOUNT_ACCESS`, `CALL_VALUE`,
  * `GAS_CREATE`, `STORAGE_CLEAR_REFUND`, the access-list costs — are overrides in `FeeSchedule.AmsterdamFeeSchedule`
  * instead, because that is what they are.
  *
  * Sources, all read in full rather than inferred:
  *   - EIP-8037 "State Creation Gas Cost Increase" — `CPSB`, the `STATE_BYTES_*` sizes, `TX_MAX_GAS_LIMIT`, the
  *     reservoir model and the `max()` block rule.
  *   - EIP-8038 "State access gas cost increase" — `ACCOUNT_WRITE`, `STORAGE_WRITE`, `CREATE_ACCESS` and the new
  *     `COLD_ACCOUNT_ACCESS`.
  *   - EIP-2780 "Transaction Gas Decomposition" — `TX_BASE_COST`, `TX_VALUE_COST`, `EXECUTION_PER_AUTH_BASE_COST`.
  *   - EIP-7954 — the code and initcode size limits.
  *
  * Cross-checked against go-ethereum's devp2p reference chain: the products below reproduce blocks 8, 24 and 41 of that
  * chain to the gas unit (see `AmsterdamGasAccountingSpec`).
  */
object AmsterdamGas:

  /** EIP-8037: cost per byte of newly created state. */
  val Cpsb: BigInt = 1530

  /** EIP-8037: state bytes attributed to one new storage slot. */
  val StateBytesPerStorageSet: BigInt = 64

  /** EIP-8037: state bytes attributed to one new account leaf. */
  val StateBytesPerNewAccount: BigInt = 120

  /** EIP-8037: state bytes attributed to one EIP-7702 delegation indicator. */
  val StateBytesPerAuthBase: BigInt = 23

  /** State gas charged when an SSTORE creates a slot: 64 x 1530 = 97,920.
    *
    * Fixture witnesses: block 36 `gasUsed` = 783,360 = 8 x this, block 37 = 489,600 = 5 x this.
    */
  val GasStorageSet: BigInt = StateBytesPerStorageSet * Cpsb

  /** State gas charged when an account leaf is created: 120 x 1530 = 183,600.
    *
    * Fixture witness: block 41's entire header `gasUsed`, from `tx-calltree`'s single CREATE.
    */
  val GasNewAccount: BigInt = StateBytesPerNewAccount * Cpsb

  /** State gas charged for a net-new EIP-7702 delegation indicator: 23 x 1530 = 35,190. */
  val GasAuthBase: BigInt = StateBytesPerAuthBase * Cpsb

  /** EIP-8038: first write to an account's leaf values (nonce, balance, code hash). Charged per operation. */
  val AccountWrite: BigInt = 9000

  /** EIP-8038: write that moves a storage slot away from its transaction-start value. Net-metered per transaction. */
  val StorageWrite: BigInt = 10000

  /** EIP-8038: `ACCOUNT_WRITE + COLD_ACCOUNT_ACCESS`, replacing the Yellow Paper's flat `G_create` of 32,000. */
  val CreateAccess: BigInt = 12000

  /** EIP-8038: cold account touch, raised from EIP-2929's 2,600. */
  val ColdAccountAccess: BigInt = 3000

  /** EIP-8038: refund for clearing a storage slot, raised from EIP-3529's 4,800. */
  val StorageClearRefund: BigInt = 11616

  /** EIP-2780: sender cost — ECDSA recovery, sender account access, sender account write, block inclusion. */
  val TxBaseCost: BigInt = 12000

  /** EIP-2780: recipient balance write plus the EIP-7708 transfer log performed by a value transfer. */
  val TxValueCost: BigInt = 6000

  /** EIP-8037: per-authorization execution cost — calldata (101 x 16), ecRecover, cold authority access, two warm
    * writes. Replaces EIP-7702's flat 25,000 intrinsic charge.
    */
  val ExecutionPerAuthBaseCost: BigInt = 7816

  /** EIP-7825 / EIP-8037: the execution-gas budget ceiling. Gas above it seeds `state_gas_reservoir`.
    *
    * **R-1**: every transaction in the reference fixture sits below this (largest is 1,628,065), so the reservoir is
    * zero throughout and the fixture cannot distinguish a correct reservoir implementation from one that never seeds
    * it. Reservoir behaviour needs written vectors, not fixture-green.
    */
  val TxMaxGasLimit: BigInt = BigInt(2).pow(24)

  /** EIP-8037: `tx.gas` ceiling, applied to the sum of both dimensions. */
  val TxMaxTotalGasLimit: BigInt = BigInt(2).pow(32) - 1

  // ── EIP-7976 / EIP-7981: the calldata floor ─────────────────────────────────
  //
  // Values and formulas are execution-specs `forks/amsterdam` (`transactions.calculate_intrinsic_cost`,
  // `GasCosts.TX_DATA_TOKEN_*`), which the EEST fixtures are generated from. Two places where the EIP texts read
  // differently and execution-specs (and go-ethereum `FloorDataGas`) win:
  //   - EIP-7976 anchors the floor on a flat 21,000; execution-specs anchors it on EIP-2780's decomposed base, the same
  //     `TX_BASE + recipient_execution_gas` the intrinsic cost starts from;
  //   - EIP-7981 lists EIP-2930's 2,400 / 1,900 per-entry charges; at Amsterdam those are EIP-8038's 2,900 / 2,000,
  //     and the data surcharge below is added on top of them.

  /** `TX_DATA_TOKEN_STANDARD`: floor tokens per calldata byte. Under EIP-7976 every byte, zero or not, counts 4. */
  val TxDataTokenStandard: BigInt = 4

  /** EIP-7976 `TX_DATA_TOKEN_FLOOR` (`TOTAL_COST_FLOOR_PER_TOKEN`): 10 -> 16, so 64 gas per calldata byte. */
  val TxDataTokenFloor: BigInt = 16

  /** EIP-7981: floor tokens for one access-list address, 20 bytes x 4. */
  val AccessListAddressFloorTokens: BigInt = 80

  /** EIP-7981: floor tokens for one access-list storage key, 32 bytes x 4. */
  val AccessListStorageKeyFloorTokens: BigInt = 128

  /** EIP-7981 `access_list_data_cost`: 1,280 gas per address and 2,048 per storage key.
    *
    * Charged on BOTH sides of `max(intrinsic + execution, floor)` — it is part of the intrinsic execution cost and of
    * the floor — so an access list pays for its bytes whichever side decides `gas_used`. It is a surcharge: the
    * per-entry access charges (`G_access_list_address` / `G_access_list_storage`) are still paid in addition.
    */
  def accessListDataCost(accessList: Seq[AccessListItem]): BigInt =
    val addresses = BigInt(accessList.size)
    val storageKeys = accessList.foldLeft(BigInt(0))((n, item) => n + item.storageKeys.size)
    (addresses * AccessListAddressFloorTokens + storageKeys * AccessListStorageKeyFloorTokens) * TxDataTokenFloor

  /** The Amsterdam calldata floor (`calldata_floor` in execution-specs `IntrinsicGasCost`):
    * {{{
    * baseExecutionGas + len(txData) * 4 * 16 + access_list_data_cost
    * }}}
    * `baseExecutionGas` is EIP-2780's `TX_BASE + recipient_execution_gas` — `EvmConfig.transactionBaseCost` under an
    * Amsterdam config — and nothing else: initcode-word and per-authorization charges are intrinsic-only and never
    * enter the floor.
    *
    * Amsterdam-only. The EIP-7623 floor of ETH Prague/Osaka and ETC Olympia is `BlockPreparator.calcFloorDataGas`,
    * which this does not replace.
    */
  def calldataFloorGas(baseExecutionGas: BigInt, txData: ByteString, accessList: Seq[AccessListItem]): BigInt =
    baseExecutionGas + BigInt(txData.length) * TxDataTokenStandard * TxDataTokenFloor + accessListDataCost(accessList)

  /** EIP-7954: contract code size limit, 24 KiB -> 64 KiB. */
  val MaxCodeSize: BigInt = 65536

  /** EIP-7954: initcode size limit, 48 KiB -> 128 KiB. */
  val MaxInitCodeSize: BigInt = 131072

  /** EIP-8037: system-call gas limit, 30M plus a 16-slot state margin. */
  val SystemCallGasLimit: BigInt = BigInt(30000000) + GasStorageSet * 16

  /** EIP-7708 SYSTEM_ADDRESS `0xfffffffffffffffffffffffffffffffffffffffe`: the emitter of every protocol-generated
    * value-transfer log. Reused from EIP-4788 so these logs are distinguishable from contract-emitted ones.
    */
  val SystemAddress: Address = Address(ByteString(Array.fill[Byte](19)(0xff.toByte) :+ 0xfe.toByte))

  /** `keccak256("Transfer(address,address,uint256)")` — the ERC-20 Transfer event signature, per EIP-7708. */
  val TransferEventTopic: ByteString = ByteString(kec256("Transfer(address,address,uint256)".getBytes("US-ASCII")))

  private def leftPadded32(bytes: ByteString): ByteString =
    ByteString(Array.fill[Byte](32 - bytes.length)(0) ++ bytes.toArray)

  /** EIP-7708: the log emitted for any non-zero value transfer to a *different* account.
    *
    * Identical in shape to a LOG3 from SYSTEM_ADDRESS. It is an ordinary [[TxLogEntry]] for every downstream purpose —
    * it enters the receipt's log list, the receipt bloom and the block bloom unchanged. That is the whole point:
    * without it, every receipt root and every bloom after activation is wrong.
    *
    * Fixture witness: post-activation transfer blocks carry bloom popcount 12 (4 bloom items x 3 bits) where
    * pre-activation blocks carry 0, and blocks 46 and 48 reconstruct byte-exactly from this shape.
    */
  def transferLog(from: Address, to: Address, value: UInt256): TxLogEntry =
    TxLogEntry(
      loggerAddress = SystemAddress,
      logTopics = Seq(TransferEventTopic, leftPadded32(from.bytes), leftPadded32(to.bytes)),
      data = leftPadded32(ByteString(value.toBigInt.toByteArray.dropWhile(_ == 0.toByte)))
    )

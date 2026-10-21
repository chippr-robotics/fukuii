package com.chipprbots.ethereum.jsonrpc

import java.math.RoundingMode

import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.transactions.TxAdmission

/** The checks go-ethereum applies to a transaction at the RPC layer, before the pool sees it (internal/ethapi/api.go
  * `SubmitTransaction`, `SignTransaction`): the RPC transaction-fee cap and the refusal of transactions that are not
  * replay-protected. Both are node policy, the same on every chain (core-geth applies them with the same defaults).
  *
  * @param allowUnprotectedTxs
  *   `fukuii.network.rpc.allow-unprotected-txs` (go-ethereum `--rpc.allow-unprotected-txs`, default false)
  * @param txFeeCap
  *   `fukuii.network.rpc.tx-fee-cap`, in ether (go-ethereum `--rpc.txfeecap`, default 1); 0 = no cap
  */
final case class RpcTxSubmission(allowUnprotectedTxs: Boolean, txFeeCap: Double):

  /** go-ethereum `checkTxFee` (api.go:2184-2196): gas * the fee cap (`tx.GasPrice()`, the fee cap of a typed
    * transaction; blob gas is not counted), in ether, must not exceed the cap.
    */
  def feeCapRejection(tx: Transaction): Option[String] =
    RpcTxSubmission.feeCapRejection(TxAdmission.feeCap(tx), tx.gasLimit.value, txFeeCap)

  /** `SubmitTransaction`'s checks in its order (api.go:1640-1649): the fee cap, then, unless allowed, replay
    * protection. go-ethereum applies the second at every block; a legacy transaction is protected when its `v` encodes
    * a chain id (EIP-155), and every typed transaction is.
    */
  def submissionRejection(stx: SignedTransaction): Option[String] =
    feeCapRejection(stx.tx).orElse(
      Option.when(!allowUnprotectedTxs && !RpcTxSubmission.isProtected(stx))(RpcTxSubmission.UnprotectedMessage)
    )

object RpcTxSubmission:

  val AllowUnprotectedTxsPath = "network.rpc.allow-unprotected-txs"
  val TxFeeCapPath = "network.rpc.tx-fee-cap"

  /** go-ethereum's defaults: unprotected transactions refused, a 1 ether fee cap. */
  val Default: RpcTxSubmission = RpcTxSubmission(allowUnprotectedTxs = false, txFeeCap = 1.0)

  val UnprotectedMessage = "only replay-protected (EIP-155) transactions allowed over RPC"

  private val WeiPerEther = BigDecimal(BigInt(10).pow(18))

  /** From the `fukuii` config block, each key falling back to its default when absent. */
  def fromConfig(fukuiiConfig: com.typesafe.config.Config): RpcTxSubmission =
    RpcTxSubmission(
      allowUnprotectedTxs =
        if fukuiiConfig.hasPath(AllowUnprotectedTxsPath) then fukuiiConfig.getBoolean(AllowUnprotectedTxsPath)
        else Default.allowUnprotectedTxs,
      txFeeCap = if fukuiiConfig.hasPath(TxFeeCapPath) then fukuiiConfig.getDouble(TxFeeCapPath) else Default.txFeeCap
    )

  /** go-ethereum `checkTxFee`: `gasPrice * gas` wei as ether (rounded to a double, as `big.Float.Float64` does) above
    * `cap` is refused; a cap of 0 refuses nothing. The amounts print as go's `%.2f`: the double's exact value, rounded
    * half to even.
    */
  def feeCapRejection(gasPrice: BigInt, gas: BigInt, cap: Double): Option[String] =
    if cap == 0 then None
    else
      val feeEth = (BigDecimal(gasPrice * gas) / WeiPerEther).toDouble
      Option.when(feeEth > cap)(
        s"tx fee (${twoDecimals(feeEth)} ether) exceeds the configured cap (${twoDecimals(cap)} ether)"
      )

  private def twoDecimals(d: Double): String =
    new java.math.BigDecimal(d).setScale(2, RoundingMode.HALF_EVEN).toPlainString

  /** go-ethereum `Transaction.Protected`: a legacy transaction whose `v` is not 27/28 (or 0/1) carries an EIP-155 chain
    * id; typed transactions always do.
    */
  def isProtected(stx: SignedTransaction): Boolean =
    stx.tx match
      case _: LegacyTransaction =>
        val v = if stx.signature.v < 0 then stx.signature.v + 256 else stx.signature.v
        !(v == 27 || v == 28 || v == 0 || v == 1)
      case _ => true

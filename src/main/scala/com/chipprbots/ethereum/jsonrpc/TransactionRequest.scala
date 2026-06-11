package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.utils.Config

case class TransactionRequest(
    from: Address,
    to: Option[Address] = None,
    value: Option[BigInt] = None,
    gasLimit: Option[BigInt] = None,
    gasPrice: Option[BigInt] = None,
    nonce: Option[BigInt] = None,
    data: Option[ByteString] = None
) {

  private val defaultGasPrice: BigInt = 2 * BigInt(10).pow(10)
  private val defaultGasLimit: BigInt = 90000

  // Preferred overload: caller injects an oracle-derived price (e.g. from EthTxService.suggestGasPrice()).
  // The user-supplied gasPrice always wins; the oracle value is only the fallback.
  def toTransaction(defaultNonce: BigInt, suggestedGasPrice: BigInt): LegacyTransaction =
    LegacyTransaction(
      nonce = nonce.getOrElse(defaultNonce),
      gasPrice = gasPrice.getOrElse(suggestedGasPrice),
      gasLimit = gasLimit.getOrElse(defaultGasLimit),
      receivingAddress = if (Config.testmode) to.filter(_ != Address(0)) else to,
      value = value.getOrElse(BigInt(0)),
      payload = data.getOrElse(ByteString.empty)
    )

  // Bridge overload — retained for callers not yet wired to the gas oracle.
  def toTransaction(defaultNonce: BigInt): LegacyTransaction =
    toTransaction(defaultNonce, defaultGasPrice)
}

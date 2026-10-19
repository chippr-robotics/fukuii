package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO
import cats.implicits.*

import scala.collection.immutable.NumericRange

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.FukuiiService.AccountTransaction
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.utils.BlockchainConfig

object FukuiiService:
  case class GetAccountTransactionsRequest(address: Address, blocksRange: NumericRange[BigInt])
  case class GetAccountTransactionsResponse(transactions: List[AccountTransaction])

  /** One history entry with its JSON-RPC transaction object, built here because only the service knows the serving
    * node's chain config (the object's `from` depends on it, see [[TransactionResponse]]).
    */
  case class AccountTransaction(data: ExtendedTransactionData, response: TransactionResponse)

  object AccountTransaction:
    def apply(data: ExtendedTransactionData)(using blockchainConfig: BlockchainConfig): AccountTransaction =
      AccountTransaction(
        data,
        TransactionResponse(
          data.stx,
          data.minedTransactionData.map(_.header),
          data.minedTransactionData.map(_.transactionIndex)
        )
      )

class FukuiiService(
    transactionHistoryService: TransactionHistoryService,
    jsonRpcConfig: JsonRpcConfig
)(using blockchainConfig: BlockchainConfig):

  def getAccountTransactions(
      request: GetAccountTransactionsRequest
  ): ServiceResponse[GetAccountTransactionsResponse] =
    if request.blocksRange.length > jsonRpcConfig.accountTransactionsMaxBlocks then
      IO.pure(
        Left(
          JsonRpcError.InvalidParams(
            s"""Maximum number of blocks to search is ${jsonRpcConfig.accountTransactionsMaxBlocks}, requested: ${request.blocksRange.length}.
               |See: 'fukuii.network.rpc.account-transactions-max-blocks' config.""".stripMargin
          )
        )
      )
    else
      transactionHistoryService
        .getAccountTransactions(request.address, request.blocksRange)
        .map(txs => GetAccountTransactionsResponse(txs.map(AccountTransaction(_))).asRight)

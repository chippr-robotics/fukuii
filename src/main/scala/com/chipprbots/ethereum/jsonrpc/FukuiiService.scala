package com.chipprbots.ethereum.jsonrpc
import cats.effect.IO
import cats.implicits._

import scala.collection.immutable.NumericRange

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

object FukuiiService {
  case class GetAccountTransactionsRequest(address: Address, blocksRange: NumericRange[BigInt])
  case class GetAccountTransactionsResponse(transactions: List[ExtendedTransactionData])
}
class FukuiiService(transactionHistoryService: TransactionHistoryService, jsonRpcConfig: JsonRpcConfig) {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def getAccountTransactions(
      request: GetAccountTransactionsRequest
  ): ServiceResponse[GetAccountTransactionsResponse] =
    if (request.blocksRange.length > jsonRpcConfig.accountTransactionsMaxBlocks) {
      IO.pure(
        Left(
          JsonRpcError.InvalidParams(
            s"""Maximum number of blocks to search is ${jsonRpcConfig.accountTransactionsMaxBlocks}, requested: ${request.blocksRange.length}.
               |See: 'fukuii.network.rpc.account-transactions-max-blocks' config.""".stripMargin
          )
        )
      )
    } else {
      transactionHistoryService
        .getAccountTransactions(request.address, request.blocksRange)
        .map(GetAccountTransactionsResponse(_).asRight)
    }
}

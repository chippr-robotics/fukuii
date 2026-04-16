package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.implicits._

import scala.concurrent.duration._
import scala.collection.immutable.NumericRange

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.jsonrpc.FukuiiService.ResetFastSyncRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.ResetFastSyncResponse
import com.chipprbots.ethereum.jsonrpc.FukuiiService.RestartFastSyncRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.RestartFastSyncResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

object FukuiiService {
  case class GetAccountTransactionsRequest(address: Address, blocksRange: NumericRange[BigInt])
  case class GetAccountTransactionsResponse(transactions: List[ExtendedTransactionData])

  case class ResetFastSyncRequest()
  case class ResetFastSyncResponse(reset: Boolean)

  case class RestartFastSyncRequest()
  case class RestartFastSyncResponse(started: Boolean, cooldownUntilMillis: Long)
}
class FukuiiService(
    transactionHistoryService: TransactionHistoryService,
    jsonRpcConfig: JsonRpcConfig,
    syncController: ActorRef
) {

  import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
  implicit val timeout: Timeout = Timeout(10.seconds)

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

  def resetFastSync(request: ResetFastSyncRequest): ServiceResponse[ResetFastSyncResponse] =
    syncController
      .askFor[SyncProtocol.ResetFastSyncResponse](SyncProtocol.ResetFastSync)
      .map(resp => Right(ResetFastSyncResponse(resp.reset)))

  def restartFastSync(request: RestartFastSyncRequest): ServiceResponse[RestartFastSyncResponse] =
    syncController
      .askFor[SyncProtocol.RestartFastSyncResponse](SyncProtocol.RestartFastSync)
      .map(resp => Right(RestartFastSyncResponse(resp.started, resp.cooldownUntilMillis)))
}

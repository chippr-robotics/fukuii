package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.jsonrpc.FilterManager.FilterChanges
import com.chipprbots.ethereum.jsonrpc.FilterManager.FilterLogs
import com.chipprbots.ethereum.jsonrpc.FilterManager.LogFilterLogs
import com.chipprbots.ethereum.jsonrpc.{FilterManager => FM}
import com.chipprbots.ethereum.utils._

object EthFilterService {
  case class NewFilterRequest(filter: Filter)
  case class Filter(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Seq[Address]],
      topics: Seq[Seq[ByteString]],
      blockHash: Option[org.apache.pekko.util.ByteString] = None
  )

  case class NewBlockFilterRequest()
  case class NewPendingTransactionFilterRequest()

  case class NewFilterResponse(filterId: BigInt)

  case class UninstallFilterRequest(filterId: BigInt)
  case class UninstallFilterResponse(success: Boolean)

  case class GetFilterChangesRequest(filterId: BigInt)
  case class GetFilterChangesResponse(filterChanges: FilterChanges)

  case class GetFilterLogsRequest(filterId: BigInt)
  case class GetFilterLogsResponse(filterLogs: FilterLogs)

  case class GetLogsRequest(filter: Filter)
  case class GetLogsResponse(filterLogs: LogFilterLogs)
}

class EthFilterService(
    filterManager: ActorRef,
    filterConfig: FilterConfig,
    blockchainReader: com.chipprbots.ethereum.domain.BlockchainReader
) {
  import EthFilterService._
  implicit lazy val timeout: Timeout = Timeout(filterConfig.filterManagerQueryTimeout)

  def newFilter(req: NewFilterRequest): ServiceResponse[NewFilterResponse] = {
    import req.filter._

    filterManager
      .askFor[FM.NewFilterResponse](FM.NewLogFilter(fromBlock, toBlock, address, topics))
      .map { resp =>
        Right(NewFilterResponse(resp.id))
      }
  }

  def newBlockFilter(req: NewBlockFilterRequest): ServiceResponse[NewFilterResponse] =
    filterManager
      .askFor[FM.NewFilterResponse](FM.NewBlockFilter)
      .map { resp =>
        Right(NewFilterResponse(resp.id))
      }

  def newPendingTransactionFilter(req: NewPendingTransactionFilterRequest): ServiceResponse[NewFilterResponse] =
    filterManager
      .askFor[FM.NewFilterResponse](FM.NewPendingTransactionFilter)
      .map { resp =>
        Right(NewFilterResponse(resp.id))
      }

  def uninstallFilter(req: UninstallFilterRequest): ServiceResponse[UninstallFilterResponse] =
    filterManager
      .askFor[FM.UninstallFilterResponse.type](FM.UninstallFilter(req.filterId))
      .map(_ => Right(UninstallFilterResponse(success = true)))

  def getFilterChanges(req: GetFilterChangesRequest): ServiceResponse[GetFilterChangesResponse] =
    filterManager
      .askFor[FM.FilterChanges](FM.GetFilterChanges(req.filterId))
      .map { filterChanges =>
        Right(GetFilterChangesResponse(filterChanges))
      }

  def getFilterLogs(req: GetFilterLogsRequest): ServiceResponse[GetFilterLogsResponse] =
    filterManager
      .askFor[FM.FilterLogs](FM.GetFilterLogs(req.filterId))
      .map { filterLogs =>
        Right(GetFilterLogsResponse(filterLogs))
      }

  def getLogs(req: GetLogsRequest): ServiceResponse[GetLogsResponse] = {
    import req.filter._

    // Validate: blockHash cannot be combined with fromBlock/toBlock
    if (blockHash.isDefined && (fromBlock.isDefined || toBlock.isDefined)) {
      return cats.effect.IO.pure(Left(JsonRpcError.InvalidParams(
        "cannot specify both blockHash and fromBlock/toBlock")))
    }

    // Resolve block numbers for range validation
    val bestBlockNum = blockchainReader.getBestBlockNumber()
    val fromNum = fromBlock.collect { case BlockParam.WithNumber(n) => n }.getOrElse(BigInt(0))
    val toNum = toBlock.collect { case BlockParam.WithNumber(n) => n }.getOrElse(bestBlockNum)

    // Validate: block range must not exceed current head
    if (fromNum > bestBlockNum || toNum > bestBlockNum) {
      return cats.effect.IO.pure(Left(JsonRpcError.InvalidParams(
        "block range extends beyond current head block")))
    }

    // Validate: fromBlock must be <= toBlock
    if (fromNum > toNum) {
      return cats.effect.IO.pure(Left(JsonRpcError.InvalidParams(
        "invalid block range params")))
    }

    // If blockHash specified, resolve to block number and use as from=to
    val (resolvedFrom, resolvedTo) = if (blockHash.isDefined) {
      val blockNum = blockHash.flatMap(h =>
        blockchainReader.getBlockByHash(h).map(_.header.number))
      blockNum match {
        case Some(n) =>
          val bp = Some(BlockParam.WithNumber(n))
          (bp, bp)
        case None =>
          return cats.effect.IO.pure(Right(GetLogsResponse(FM.LogFilterLogs(Nil))))
      }
    } else (fromBlock, toBlock)

    filterManager
      .askFor[FM.LogFilterLogs](FM.GetLogs(resolvedFrom, resolvedTo, address, topics))
      .map { filterLogs =>
        Right(GetLogsResponse(filterLogs))
      }
  }
}

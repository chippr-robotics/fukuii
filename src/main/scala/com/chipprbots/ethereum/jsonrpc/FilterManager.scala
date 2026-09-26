package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.Random

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.BloomFilter
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.utils.FilterConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

object FilterManager:

  // ── Commands ────────────────────────────────────────────────────────────────

  sealed trait Command
  case class NewLogFilter(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Seq[Address]],
      topics: Seq[Seq[ByteString]],
      replyTo: ActorRef[NewFilterResponse]
  ) extends Command
  case class NewBlockFilter(replyTo: ActorRef[NewFilterResponse]) extends Command
  case class NewPendingTransactionFilter(replyTo: ActorRef[NewFilterResponse]) extends Command
  case class UninstallFilter(id: BigInt, replyTo: ActorRef[UninstallFilterResponse.type]) extends Command
  case class GetFilterLogs(id: BigInt, replyTo: ActorRef[FilterLogs]) extends Command
  case class GetFilterChanges(id: BigInt, replyTo: ActorRef[FilterChanges]) extends Command
  case class GetLogs(
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Seq[Address]],
      topics: Seq[Seq[ByteString]],
      replyTo: ActorRef[LogFilterLogs]
  ) extends Command
  private case class FilterTimeout(id: BigInt) extends Command

  // ── Filter model ────────────────────────────────────────────────────────────

  sealed trait Filter:
    def id: BigInt
  case class LogFilter(
      override val id: BigInt,
      fromBlock: Option[BlockParam],
      toBlock: Option[BlockParam],
      address: Option[Seq[Address]],
      topics: Seq[Seq[ByteString]]
  ) extends Filter
  case class BlockFilter(override val id: BigInt) extends Filter
  case class PendingTransactionFilter(override val id: BigInt) extends Filter

  // ── Responses ───────────────────────────────────────────────────────────────

  case class NewFilterResponse(id: BigInt)
  case object UninstallFilterResponse

  case class TxLog(
      logIndex: BigInt,
      transactionIndex: BigInt,
      transactionHash: ByteString,
      blockHash: ByteString,
      blockNumber: BigInt,
      address: Address,
      data: ByteString,
      topics: Seq[ByteString],
      blockTimestamp: Option[BigInt] = None
  )

  sealed trait FilterChanges
  case class LogFilterChanges(logs: Seq[TxLog]) extends FilterChanges
  case class BlockFilterChanges(blockHashes: Seq[ByteString]) extends FilterChanges
  case class PendingTransactionFilterChanges(txHashes: Seq[ByteString]) extends FilterChanges

  sealed trait FilterLogs
  case class LogFilterLogs(logs: Seq[TxLog]) extends FilterLogs
  case class BlockFilterLogs(blockHashes: Seq[ByteString]) extends FilterLogs
  case class PendingTransactionFilterLogs(txHashes: Seq[ByteString]) extends FilterLogs

  // ── Behavior ────────────────────────────────────────────────────────────────

  def apply(
      blockchainReader: BlockchainReader,
      blockGenerator: BlockGenerator,
      keyStore: KeyStore,
      pendingTransactionsManager: ActorRef[PendingTransactionsManager.Command],
      filterConfig: FilterConfig,
      txPoolConfig: TxPoolConfig
  ): Behavior[Command] = Behaviors.setup { ctx =>
    given ec: ExecutionContext = ctx.executionContext
    given ioRuntime: IORuntime = IORuntime.global
    given timeout: Timeout = Timeout(txPoolConfig.pendingTxManagerQueryTimeout)
    given scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    val maxBlockHashesChanges = 256

    var filters: Map[BigInt, Filter] = Map.empty
    var lastCheckBlocks: Map[BigInt, BigInt] = Map.empty
    var lastCheckTimestamps: Map[BigInt, Long] = Map.empty
    var filterTimeouts: Map[BigInt, Cancellable] = Map.empty

    def generateId(): BigInt = BigInt(Random.nextLong()).abs

    def resetTimeout(id: BigInt): Unit =
      filterTimeouts.get(id).foreach(_.cancel())
      filterTimeouts += (id -> ctx.scheduleOnce(filterConfig.filterTimeout, ctx.self, FilterTimeout(id)))

    def addFilterAndSendResponse(filter: Filter, replyTo: ActorRef[NewFilterResponse]): Unit =
      filters += (filter.id -> filter)
      lastCheckBlocks += (filter.id -> blockchainReader.getBestBlockNumber)
      lastCheckTimestamps += (filter.id -> System.currentTimeMillis())
      resetTimeout(filter.id)
      replyTo ! NewFilterResponse(filter.id)

    def doUninstallFilter(id: BigInt, replyTo: ActorRef[UninstallFilterResponse.type]): Unit =
      filters -= id
      lastCheckBlocks -= id
      lastCheckTimestamps -= id
      filterTimeouts.get(id).foreach(_.cancel())
      filterTimeouts -= id
      replyTo ! UninstallFilterResponse

    def topicsMatch(logTopics: Seq[ByteString], filterTopics: Seq[Seq[ByteString]]): Boolean =
      logTopics.size >= filterTopics.size &&
        filterTopics.zip(logTopics).forall { case (filter, log) => filter.isEmpty || filter.contains(log) }

    def getLogsFromBlock(filter: LogFilter, block: Block, receipts: Seq[Receipt]): Seq[TxLog] =
      val bytesToCheckInBloomFilter = filter.address.map(_.map(_.bytes)).getOrElse(Nil) ++ filter.topics.flatten
      var blockLogIndex = 0
      receipts.zipWithIndex.foldLeft(Nil: Seq[TxLog]) { case (logsSoFar, (receipt, txIndex)) =>
        val txLogs =
          if bytesToCheckInBloomFilter.isEmpty || BloomFilter.containsAnyOf(
              receipt.logsBloomFilter.value,
              bytesToCheckInBloomFilter
            )
          then
            receipt.logs.zipWithIndex
              .map { case (log, localIdx) => (log, blockLogIndex + localIdx) }
              .filter { case (log, _) =>
                filter.address.forall(addrs => addrs.contains(log.loggerAddress)) &&
                topicsMatch(log.logTopics, filter.topics)
              }
              .map { case (log, logIndex) =>
                val tx = block.body.transactionList(txIndex)
                TxLog(
                  logIndex = logIndex,
                  transactionIndex = txIndex,
                  transactionHash = tx.hash.value,
                  blockHash = block.header.hash.value,
                  blockNumber = block.header.number.value,
                  address = log.loggerAddress,
                  data = log.data,
                  topics = log.logTopics,
                  // uint64 bit pattern -> unsigned BigInt; see BlockResponse.scala for the sign-extension bug this avoids.
                  blockTimestamp = Some(block.header.unixTimestamp.toUnsignedBigInt)
                )
              }
          else Nil
        blockLogIndex += receipt.logs.size
        logsSoFar ++ txLogs
      }

    def resolveBlockNumber(blockParam: BlockParam, bestBlockNumber: BigInt): BigInt =
      blockParam match
        case BlockParam.WithNumber(blockNumber) => blockNumber
        case BlockParam.WithHash(hash) =>
          blockchainReader.getBlockHeaderByHash(BlockHash(hash)).map(_.number.value).getOrElse(bestBlockNumber)
        case BlockParam.Earliest  => 0
        case BlockParam.Latest    => bestBlockNumber
        case BlockParam.Safe      => bestBlockNumber
        case BlockParam.Finalized => bestBlockNumber
        case BlockParam.Pending   => bestBlockNumber

    def getLogs(filter: LogFilter, startingBlockNumber: Option[BigInt] = None): Seq[TxLog] =
      val bytesToCheckInBloomFilter = filter.address.map(_.map(_.bytes)).getOrElse(Nil) ++ filter.topics.flatten

      @tailrec
      def recur(currentBlockNumber: BigInt, toBlockNumber: BigInt, logsSoFar: Seq[TxLog]): Seq[TxLog] =
        if currentBlockNumber > toBlockNumber then logsSoFar
        else
          blockchainReader.getBlockHeaderByNumber(currentBlockNumber) match
            case Some(header)
                if bytesToCheckInBloomFilter.isEmpty || BloomFilter.containsAnyOf(
                  header.logsBloom.value,
                  bytesToCheckInBloomFilter
                ) =>
              blockchainReader.getReceiptsByHash(header.hash) match
                case Some(receipts) =>
                  val bodyOpt = blockchainReader.getBlockBodyByHash(header.hash)
                  val newLogs = bodyOpt.fold(logsSoFar) { body =>
                    logsSoFar ++ getLogsFromBlock(filter, Block(header, body), receipts)
                  }
                  recur(currentBlockNumber + 1, toBlockNumber, newLogs)
                case None => logsSoFar
            case Some(_) => recur(currentBlockNumber + 1, toBlockNumber, logsSoFar)
            case None    => logsSoFar

      val bestBlockNumber = blockchainReader.getBestBlockNumber
      val fromBlockNumber =
        startingBlockNumber.getOrElse(
          resolveBlockNumber(filter.fromBlock.getOrElse(BlockParam.Latest), bestBlockNumber)
        )
      val toBlockNumber = resolveBlockNumber(filter.toBlock.getOrElse(BlockParam.Latest), bestBlockNumber)
      val logs = recur(fromBlockNumber, toBlockNumber, Nil)

      if filter.toBlock.contains(BlockParam.Pending) then
        logs ++ blockGenerator.getPendingBlock.map(p => getLogsFromBlock(filter, p.block, p.receipts)).getOrElse(Nil)
      else logs

    def getBlockHashesAfter(blockNumber: BigInt): Seq[ByteString] =
      val bestBlock = blockchainReader.getBestBlockNumber

      @tailrec
      def recur(currentBlockNumber: BigInt, hashesSoFar: Seq[ByteString]): Seq[ByteString] =
        if currentBlockNumber > bestBlock then hashesSoFar
        else
          blockchainReader.getBlockHeaderByNumber(currentBlockNumber) match
            case Some(header) => recur(currentBlockNumber + 1, hashesSoFar :+ header.hash.value)
            case None         => hashesSoFar

      recur(blockNumber + 1, Nil)

    def getPendingTransactions(): IO[Seq[PendingTransaction]] =
      pendingTransactionsManager
        .askForTyped[PendingTransactionsManager.PendingTransactionsResponse](
          PendingTransactionsManager.GetPendingTransactionsReq(_)
        )
        .flatMap { response =>
          keyStore.listAccounts match
            case Right(accounts) =>
              IO.pure(response.pendingTransactions.filter(pt => accounts.contains(pt.stx.senderAddress)))
            case Left(_) => IO.raiseError(new RuntimeException("Cannot get account list"))
        }

    def doGetFilterLogs(id: BigInt, replyTo: ActorRef[FilterLogs]): Unit =
      val filterOpt = filters.get(id)
      filterOpt.foreach { _ =>
        lastCheckBlocks += (id -> blockchainReader.getBestBlockNumber)
        lastCheckTimestamps += (id -> System.currentTimeMillis())
      }
      resetTimeout(id)

      filterOpt match
        case Some(logFilter: LogFilter) =>
          replyTo ! LogFilterLogs(getLogs(logFilter))

        case Some(_: BlockFilter) =>
          replyTo ! BlockFilterLogs(Nil)

        case Some(_: PendingTransactionFilter) =>
          getPendingTransactions()
            .map(ptxs => PendingTransactionFilterLogs(ptxs.map(_.stx.tx.hash.value)))
            .unsafeToFuture()
            .foreach(replyTo ! _)

        case None =>
          replyTo ! LogFilterLogs(Nil)

    def doGetFilterChanges(id: BigInt, replyTo: ActorRef[FilterChanges]): Unit =
      val bestBlockNumber = blockchainReader.getBestBlockNumber
      val lastCheckBlock = lastCheckBlocks.getOrElse(id, bestBlockNumber)
      val lastCheckTimestamp = lastCheckTimestamps.getOrElse(id, System.currentTimeMillis())

      val filterOpt = filters.get(id)
      filterOpt.foreach { _ =>
        lastCheckBlocks += (id -> bestBlockNumber)
        lastCheckTimestamps += (id -> System.currentTimeMillis())
      }
      resetTimeout(id)

      filterOpt match
        case Some(logFilter: LogFilter) =>
          replyTo ! LogFilterChanges(getLogs(logFilter, Some(lastCheckBlock + 1)))

        case Some(_: BlockFilter) =>
          replyTo ! BlockFilterChanges(getBlockHashesAfter(lastCheckBlock).takeRight(maxBlockHashesChanges))

        case Some(_: PendingTransactionFilter) =>
          getPendingTransactions()
            .map { pendingTransactions =>
              val filtered = pendingTransactions.filter(_.addTimestamp > lastCheckTimestamp)
              PendingTransactionFilterChanges(filtered.map(_.stx.tx.hash.value))
            }
            .unsafeToFuture()
            .foreach(replyTo ! _)

        case None =>
          replyTo ! LogFilterChanges(Nil)

    Behaviors.receiveMessage {
      case NewLogFilter(fromBlock, toBlock, address, topics, replyTo) =>
        addFilterAndSendResponse(LogFilter(generateId(), fromBlock, toBlock, address, topics), replyTo)
        Behaviors.same

      case NewBlockFilter(replyTo) =>
        addFilterAndSendResponse(BlockFilter(generateId()), replyTo)
        Behaviors.same

      case NewPendingTransactionFilter(replyTo) =>
        addFilterAndSendResponse(PendingTransactionFilter(generateId()), replyTo)
        Behaviors.same

      case UninstallFilter(id, replyTo) =>
        doUninstallFilter(id, replyTo)
        Behaviors.same

      case GetFilterLogs(id, replyTo) =>
        doGetFilterLogs(id, replyTo)
        Behaviors.same

      case GetFilterChanges(id, replyTo) =>
        doGetFilterChanges(id, replyTo)
        Behaviors.same

      case GetLogs(fromBlock, toBlock, address, topics, replyTo) =>
        val filter = LogFilter(0, fromBlock, toBlock, address, topics)
        replyTo ! LogFilterLogs(getLogs(filter, None))
        Behaviors.same

      case FilterTimeout(id) =>
        doUninstallFilter(id, ctx.system.deadLetters)
        Behaviors.same
    }
  }

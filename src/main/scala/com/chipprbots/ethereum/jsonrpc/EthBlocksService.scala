package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.annotation.unused

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.utils.Config

object EthBlocksService:
  case class BestBlockNumberRequest()
  case class BestBlockNumberResponse(bestBlockNumber: BigInt)

  case class TxCountByBlockHashRequest(blockHash: ByteString)
  case class TxCountByBlockHashResponse(txsQuantity: Option[Int])

  case class BlockByBlockHashRequest(blockHash: ByteString, fullTxs: Boolean)
  case class BlockByBlockHashResponse(blockResponse: Option[BaseBlockResponse])

  case class BlockByNumberRequest(block: BlockParam, fullTxs: Boolean)
  case class BlockByNumberResponse(blockResponse: Option[BaseBlockResponse])

  case class GetBlockTransactionCountByNumberRequest(block: BlockParam)
  case class GetBlockTransactionCountByNumberResponse(result: BigInt)

  case class UncleByBlockHashAndIndexRequest(blockHash: ByteString, uncleIndex: BigInt)
  case class UncleByBlockHashAndIndexResponse(uncleBlockResponse: Option[BaseBlockResponse])

  case class UncleByBlockNumberAndIndexRequest(block: BlockParam, uncleIndex: BigInt)
  case class UncleByBlockNumberAndIndexResponse(uncleBlockResponse: Option[BaseBlockResponse])

  case class GetUncleCountByBlockNumberRequest(block: BlockParam)
  case class GetUncleCountByBlockNumberResponse(result: BigInt)

  case class GetUncleCountByBlockHashRequest(blockHash: ByteString)
  case class GetUncleCountByBlockHashResponse(result: BigInt)

  case class GetBlockReceiptsRequest(block: BlockParam)
  case class GetBlockReceiptsResponse(receipts: Option[Seq[TransactionReceiptResponse]])

  case class FeeHistoryRequest(blockCount: BigInt, newestBlock: BlockParam, rewardPercentiles: Option[Seq[Double]])
  case class FeeHistoryResponse(
      oldestBlock: BigInt,
      baseFeePerGas: Seq[BigInt],
      gasUsedRatio: Seq[Double],
      reward: Option[Seq[Seq[BigInt]]],
      baseFeePerBlobGas: Seq[BigInt],
      blobGasUsedRatio: Seq[Double]
  )

  case class MaxPriorityFeePerGasRequest()
  case class MaxPriorityFeePerGasResponse(maxPriorityFeePerGas: BigInt)

  case class BlobBaseFeeRequest()
  case class BlobBaseFeeResponse(blobBaseFee: BigInt)

  case class BaseFeeRequest()
  case class BaseFeeResponse(baseFee: BigInt)

  case class GetRawBlockRequest(block: BlockParam)
  case class GetRawBlockResponse(rawBlock: Option[ByteString])

  case class GetRawHeaderRequest(block: BlockParam)
  case class GetRawHeaderResponse(rawHeader: Option[ByteString])

  case class GetRawReceiptsRequest(block: BlockParam)
  case class GetRawReceiptsResponse(rawReceipts: Option[Seq[ByteString]])

  case class CapabilitiesRequest()

  /** A single resource's effective retention policy, as reported by `eth_capabilities`.
    *
    * @param oldestBlock
    *   the oldest block number for which this resource is still available.
    * @param deleteStrategy
    *   `Some(retentionBlocks)` when the resource is pruned on a sliding window, `None` when the node retains it
    *   indefinitely (archive pruning, or a resource type this node never prunes).
    */
  case class CapabilitiesResource(disabled: Boolean, oldestBlock: BigInt, deleteStrategy: Option[BigInt])
  case class CapabilitiesResponse(
      headNumber: BigInt,
      headHash: ByteString,
      state: CapabilitiesResource,
      tx: CapabilitiesResource,
      logs: CapabilitiesResource,
      receipts: CapabilitiesResource,
      blocks: CapabilitiesResource,
      stateproofs: CapabilitiesResource
  )

class EthBlocksService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    val blockQueue: BlockQueue,
    private val _forkChoiceManagerOpt: Option[ForkChoiceManager] = None,
    private val pruningMode: com.chipprbots.ethereum.db.storage.pruning.PruningMode =
      com.chipprbots.ethereum.db.storage.pruning.PruningMode.ArchivePruning,
    // Plain (non-`given`) constructor param, injectable by tests — e.g. to exercise the Olympia-activated
    // branch of eth_baseFee without needing a live chain past the fork block. `given` class members are
    // final in Scala 3 and cannot be overridden by subclassing, so this can't be a `given` itself and
    // remain testable — instead it backs an anonymous `given` below for the implicit call sites in this
    // class (TransactionReceiptResponse, SignedTransaction.getSender) that require one in scope.
    val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig
) extends ResolveBlock:
  final override def forkChoiceManagerOpt: Option[ForkChoiceManager] = _forkChoiceManagerOpt
  import EthBlocksService.*

  given BlockchainConfig = blockchainConfig

  /** ETH-family chains must not emit `totalDifficulty` — execution-apis dropped it from the Block schema after the
    * merge, and rpc-compat compares the object exactly. ETC is PoW and still reports it, so this is a gate rather than
    * a removal. See EthBlocksJsonMethodsImplicits for the measured impact.
    */
  private def emitTotalDifficulty: Boolean = blockchainConfig.networkType != NetworkType.ETH

  /** eth_blockNumber that returns the number of most recent block.
    *
    * @return
    *   Current block number the client is on.
    */
  def bestBlockNumber(@unused req: BestBlockNumberRequest): ServiceResponse[BestBlockNumberResponse] = IO {
    Right(BestBlockNumberResponse(blockchainReader.getBestBlockNumber))
  }

  /** Implements the eth_getBlockTransactionCountByHash method that fetches the number of txs that a certain block has.
    *
    * @param request
    *   with the hash of the block requested
    * @return
    *   the number of txs that the block has or None if the client doesn't have the block requested
    */
  def getBlockTransactionCountByHash(request: TxCountByBlockHashRequest): ServiceResponse[TxCountByBlockHashResponse] =
    IO {
      val txsCount = blockchainReader.getBlockBodyByHash(BlockHash(request.blockHash)).map(_.transactionList.size)
      Right(TxCountByBlockHashResponse(txsCount))
    }

  /** Implements the eth_getBlockByHash method that fetches a requested block.
    *
    * @param request
    *   with the hash of the block requested
    * @return
    *   the block requested or None if the client doesn't have the block
    */
  def getByBlockHash(request: BlockByBlockHashRequest): ServiceResponse[BlockByBlockHashResponse] = IO {
    val BlockByBlockHashRequest(blockHash, fullTxs) = request
    val blockOpt =
      blockchainReader.getBlockByHash(BlockHash(blockHash)).orElse(blockQueue.getBlockByHash(BlockHash(blockHash)))
    val weight = blockchainReader
      .getChainWeightByHash(BlockHash(blockHash))
      .orElse(blockQueue.getChainWeightByHash(BlockHash(blockHash)))

    // Hide engine-API optimistic blocks (ACCEPTED with unknown parent, stored via
    // storeBlockByHashOnly) — they skip the number→hash mapping and haven't been executed.
    // Exposing them via eth_getBlockByHash breaks hive's "Invalid NewPayload, ParentHash" test,
    // which expects the altered payload to NOT be queryable. A block is "exposed" if either
    // (a) it lives at its advertised number in the canonical index, or (b) it's a known
    // sidechain (has receipts stored, i.e. was fully executed on the fork-choice sidechain path).
    val isExposed = blockOpt.exists { b =>
      blockchainReader.getBlockHeaderByNumber(b.header.number.value).exists(_.hash == b.header.hash) ||
      blockchainReader.getReceiptsByHash(b.header.hash).isDefined
    }
    val blockResponseOpt =
      if !isExposed then None
      else
        blockOpt.map(block =>
          BlockResponse(block, weight, fullTxs = fullTxs, emitTotalDifficulty = emitTotalDifficulty)
        )
    Right(BlockByBlockHashResponse(blockResponseOpt))
  }

  /** Implements the eth_getBlockByNumber method that fetches a requested block.
    *
    * @param request
    *   with the block requested (by it's number or by tag)
    * @return
    *   the block requested or None if the client doesn't have the block
    */
  def getBlockByNumber(request: BlockByNumberRequest): ServiceResponse[BlockByNumberResponse] = IO {
    val BlockByNumberRequest(blockParam, fullTxs) = request
    val blockResponseOpt =
      resolveBlock(blockParam).toOption.map { case ResolvedBlock(block, pending) =>
        val weight = blockchainReader.getChainWeightByHash(block.header.hash)
        BlockResponse(
          block,
          weight,
          fullTxs = fullTxs,
          pendingBlock = pending.isDefined,
          emitTotalDifficulty = emitTotalDifficulty
        )
      }
    Right(BlockByNumberResponse(blockResponseOpt))
  }

  def getBlockTransactionCountByNumber(
      req: GetBlockTransactionCountByNumberRequest
  ): ServiceResponse[GetBlockTransactionCountByNumberResponse] =
    IO {
      resolveBlock(req.block).map { case ResolvedBlock(block, _) =>
        GetBlockTransactionCountByNumberResponse(block.body.transactionList.size)
      }
    }

  /** Implements the eth_getUncleByBlockHashAndIndex method that fetches an uncle from a certain index in a requested
    * block.
    *
    * @param request
    *   with the hash of the block and the index of the uncle requested
    * @return
    *   the uncle that the block has at the given index or None if the client doesn't have the block or if there's no
    *   uncle in that index
    */
  def getUncleByBlockHashAndIndex(
      request: UncleByBlockHashAndIndexRequest
  ): ServiceResponse[UncleByBlockHashAndIndexResponse] = IO {
    val UncleByBlockHashAndIndexRequest(blockHash, uncleIndex) = request
    val uncleHeaderOpt = blockchainReader
      .getBlockBodyByHash(BlockHash(blockHash))
      .flatMap { body =>
        if uncleIndex >= 0 && uncleIndex < body.uncleNodesList.size then
          Some(body.uncleNodesList.apply(uncleIndex.toInt))
        else None
      }
    val weight = uncleHeaderOpt.flatMap(uncleHeader => blockchainReader.getChainWeightByHash(uncleHeader.hash))

    // The block in the response will not have any txs or uncles
    val uncleBlockResponseOpt = uncleHeaderOpt.map { uncleHeader =>
      BlockResponse(
        Block(uncleHeader, BlockBody(Nil, Nil)),
        weight,
        pendingBlock = false,
        emitTotalDifficulty = emitTotalDifficulty
      )
    }
    Right(UncleByBlockHashAndIndexResponse(uncleBlockResponseOpt))
  }

  /** Implements the eth_getUncleByBlockNumberAndIndex method that fetches an uncle from a certain index in a requested
    * block.
    *
    * @param request
    *   with the number/tag of the block and the index of the uncle requested
    * @return
    *   the uncle that the block has at the given index or None if the client doesn't have the block or if there's no
    *   uncle in that index
    */
  def getUncleByBlockNumberAndIndex(
      request: UncleByBlockNumberAndIndexRequest
  ): ServiceResponse[UncleByBlockNumberAndIndexResponse] = IO {
    val UncleByBlockNumberAndIndexRequest(blockParam, uncleIndex) = request
    val uncleBlockResponseOpt = resolveBlock(blockParam).toOption
      .flatMap { case ResolvedBlock(block, pending) =>
        if uncleIndex >= 0 && uncleIndex < block.body.uncleNodesList.size then
          val uncleHeader = block.body.uncleNodesList.apply(uncleIndex.toInt)
          val weight = blockchainReader.getChainWeightByHash(uncleHeader.hash)

          // The block in the response will not have any txs or uncles
          Some(
            BlockResponse(
              Block(uncleHeader, BlockBody(Nil, Nil)),
              weight,
              pendingBlock = pending.isDefined,
              emitTotalDifficulty = emitTotalDifficulty
            )
          )
        else None
      }

    Right(UncleByBlockNumberAndIndexResponse(uncleBlockResponseOpt))
  }

  def getUncleCountByBlockNumber(
      req: GetUncleCountByBlockNumberRequest
  ): ServiceResponse[GetUncleCountByBlockNumberResponse] =
    IO {
      resolveBlock(req.block).map { case ResolvedBlock(block, _) =>
        GetUncleCountByBlockNumberResponse(block.body.uncleNodesList.size)
      }
    }

  def getUncleCountByBlockHash(
      req: GetUncleCountByBlockHashRequest
  ): ServiceResponse[GetUncleCountByBlockHashResponse] =
    IO {
      blockchainReader.getBlockBodyByHash(BlockHash(req.blockHash)) match
        case Some(blockBody) =>
          Right(GetUncleCountByBlockHashResponse(blockBody.uncleNodesList.size))
        case None =>
          Left(
            JsonRpcError.InvalidParams(s"Block with hash ${Hex.toHexString(req.blockHash.toArray[Byte])} not found")
          )
    }

  def getBlockReceipts(req: GetBlockReceiptsRequest): ServiceResponse[GetBlockReceiptsResponse] = IO {
    val result = resolveBlock(req.block).toOption.flatMap { case ResolvedBlock(block, _) =>
      blockchainReader.getReceiptsByHash(block.header.hash).map { receipts =>
        var baseLogIndex = 0
        block.body.transactionList.zip(receipts).zipWithIndex.map { case ((stx, receipt), idx) =>
          val gasUsed =
            if idx == 0 then receipt.cumulativeGasUsed
            else receipt.cumulativeGasUsed - receipts(idx - 1).cumulativeGasUsed
          val sender = SignedTransaction.getSender(stx).getOrElse(Address(0))
          val resp = TransactionReceiptResponse(receipt, stx, sender, idx, block.header, gasUsed, baseLogIndex)
          baseLogIndex += receipt.logs.size
          resp
        }
      }
    }
    Right(GetBlockReceiptsResponse(result))
  }

  def feeHistory(req: FeeHistoryRequest): ServiceResponse[FeeHistoryResponse] = IO {
    val bestBlock = blockchainReader.getBestBlockNumber
    val newestBlockNum = resolveBlock(req.newestBlock).toOption.map(_.block.header.number.value).getOrElse(bestBlock)
    val count = req.blockCount.min(1024).toInt
    val oldestBlock = (newestBlockNum - count + 1).max(0)

    val baseFees = (oldestBlock.toLong to (newestBlockNum + 1).toLong).map { num =>
      blockchainReader.getBlockHeaderByNumber(num).flatMap(_.baseFee).getOrElse(BigInt(0))
    }.toSeq

    val gasUsedRatios = (oldestBlock.toLong to newestBlockNum.toLong).map { num =>
      blockchainReader
        .getBlockHeaderByNumber(num)
        .map { h =>
          if h.gasLimit > GasAmount.Zero then h.gasUsed.value.toDouble / h.gasLimit.value.toDouble else 0.0
        }
        .getOrElse(0.0)
    }.toSeq

    val blobBaseFees = (oldestBlock.toLong to (newestBlockNum + 1).toLong).map { num =>
      blockchainReader
        .getBlockHeaderByNumber(num)
        .map { h =>
          h.excessBlobGas
            .map(eg =>
              com.chipprbots.ethereum.consensus.engine.BlobGasUtils
                .getBlobGasPrice(eg, h.unixTimestamp, blockchainConfig)
            )
            .getOrElse(BigInt(0))
        }
        .getOrElse(BigInt(0))
    }.toSeq

    val blobGasUsedRatios = (oldestBlock.toLong to newestBlockNum.toLong).map { num =>
      blockchainReader
        .getBlockHeaderByNumber(num)
        .map { h =>
          h.blobGasUsed
            .map { used =>
              val max = com.chipprbots.ethereum.consensus.engine.BlobGasUtils
                .maxBlobGasPerBlock(h.unixTimestamp, blockchainConfig)
              if used > 0 && max > 0 then used.toDouble / max.toDouble else 0.0
            }
            .getOrElse(0.0)
        }
        .getOrElse(0.0)
    }.toSeq

    val rewards = req.rewardPercentiles.map { _ =>
      (oldestBlock.toLong to newestBlockNum.toLong).map { _ =>
        req.rewardPercentiles.getOrElse(Seq.empty).map(_ => BigInt(0))
      }.toSeq
    }

    Right(
      FeeHistoryResponse(
        oldestBlock = oldestBlock,
        baseFeePerGas = baseFees,
        gasUsedRatio = gasUsedRatios,
        reward = rewards,
        baseFeePerBlobGas = blobBaseFees,
        blobGasUsedRatio = blobGasUsedRatios
      )
    )
  }

  def maxPriorityFeePerGas(@unused req: MaxPriorityFeePerGasRequest): ServiceResponse[MaxPriorityFeePerGasResponse] =
    IO {
      // Return the per-chain minimum tip from config rather than a hardcoded 1 gwei literal.
      // On ETC/Mordor post-Olympia: blockchainConfig.minTip = 1 gwei (ECIP-1112).
      // On ETH/Sepolia: minTip defaults to 1 gwei. Both match the reference client stub behaviour.
      Right(MaxPriorityFeePerGasResponse(blockchainConfig.minTip))
    }

  def blobBaseFee(@unused req: BlobBaseFeeRequest): ServiceResponse[BlobBaseFeeResponse] = IO {
    val fee = blockchainReader.getBestBlock
      .flatMap(b => b.header.excessBlobGas.map(eg => (eg, b.header.unixTimestamp)))
      .map { case (eg, ts) =>
        com.chipprbots.ethereum.consensus.engine.BlobGasUtils.getBlobGasPrice(eg, ts, blockchainConfig)
      }
      .getOrElse(BigInt(0))
    Right(BlobBaseFeeResponse(fee))
  }

  /** eth_baseFee — the EIP-1559 base fee per gas of the *next* block (the child of current head).
    *
    * Reuses [[com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator.calcBaseFee]] — the same function used by
    * block production and header validation — rather than re-deriving the formula. Gated on whether the next block
    * number has actually crossed the Olympia activation: `calcBaseFee` alone would return the 1 gwei `InitialBaseFee`
    * floor even for a chain that hasn't activated base fees yet, which would be a fabricated answer here. When the next
    * block wouldn't carry a base fee, report `0` — consistent with the `getOrElse(BigInt(0))` fallback already used in
    * [[feeHistory]].
    */
  def baseFee(@unused req: BaseFeeRequest): ServiceResponse[BaseFeeResponse] = IO {
    val fee = blockchainReader.getBestBlock
      .map { parent =>
        val nextBlockNumber = parent.header.number.value + 1
        if nextBlockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber then
          com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator.calcBaseFee(parent.header, blockchainConfig)
        else BigInt(0)
      }
      .getOrElse(BigInt(0))
    Right(BaseFeeResponse(fee))
  }

  /** eth_capabilities — the node's effective data-retention capabilities, for RPC-router routing decisions.
    *
    * Reports honestly from actual node configuration:
    *   - `state` / `stateproofs` are backed by the state trie, which is subject to this node's [[pruningMode]]. Under
    *     archive pruning, both are fully retained (`oldestBlock = 0`, no delete strategy). Under basic/in-memory
    *     pruning, both report a sliding window of `history` blocks.
    *   - `tx` / `logs` / `receipts` / `blocks` are not pruned by any mechanism in this node's storage layer (no
    *     `PruneSupport` implementation trims block bodies, receipts, or transaction/log indices) — they are always
    *     reported as fully retained.
    */
  def capabilities(@unused req: CapabilitiesRequest): ServiceResponse[CapabilitiesResponse] = IO {
    import com.chipprbots.ethereum.db.storage.pruning.PruningMode

    val bestBlock = blockchainReader.getBestBlock
    val headNumber = bestBlock.map(_.header.number.value).getOrElse(BigInt(0))
    val headHash = bestBlock.map(_.header.hash.value).getOrElse(ByteString(new Array[Byte](32)))

    val stateCapability = pruningMode match
      case PruningMode.ArchivePruning =>
        CapabilitiesResource(disabled = false, oldestBlock = BigInt(0), deleteStrategy = None)
      case PruningMode.BasicPruning(history) =>
        CapabilitiesResource(
          disabled = false,
          oldestBlock = (headNumber - history).max(0),
          deleteStrategy = Some(BigInt(history))
        )
      case PruningMode.InMemoryPruning(history) =>
        CapabilitiesResource(
          disabled = false,
          oldestBlock = (headNumber - history).max(0),
          deleteStrategy = Some(BigInt(history))
        )

    val unprunedCapability = CapabilitiesResource(disabled = false, oldestBlock = BigInt(0), deleteStrategy = None)

    Right(
      CapabilitiesResponse(
        headNumber = headNumber,
        headHash = headHash,
        state = stateCapability,
        tx = unprunedCapability,
        logs = unprunedCapability,
        receipts = unprunedCapability,
        blocks = unprunedCapability,
        stateproofs = stateCapability
      )
    )
  }

  def getRawBlock(req: GetRawBlockRequest): ServiceResponse[GetRawBlockResponse] = IO {
    val raw = resolveBlock(req.block).toOption.map { case ResolvedBlock(block, _) =>
      ByteString(rlp.encode(block.toRLPEncodable))
    }
    Right(GetRawBlockResponse(raw))
  }

  def getRawHeader(req: GetRawHeaderRequest): ServiceResponse[GetRawHeaderResponse] = IO {
    val raw = resolveBlock(req.block).toOption.map { case ResolvedBlock(block, _) =>
      ByteString(rlp.encode(block.header.toRLPEncodable))
    }
    Right(GetRawHeaderResponse(raw))
  }

  def getRawReceipts(req: GetRawReceiptsRequest): ServiceResponse[GetRawReceiptsResponse] = IO {
    import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*
    val raw = resolveBlock(req.block).toOption.flatMap { case ResolvedBlock(block, _) =>
      blockchainReader.getReceiptsByHash(block.header.hash).map { receipts =>
        receipts.map(r => ByteString(r.toBytes))
      }
    }
    Right(GetRawReceiptsResponse(raw))
  }

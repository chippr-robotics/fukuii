package com.chipprbots.ethereum.testmode

import akka.util.ByteString

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.jsonrpc.BaseBlockResponse
import com.chipprbots.ethereum.jsonrpc.BaseTransactionResponse
import com.chipprbots.ethereum.jsonrpc.EthBlocksService
import com.chipprbots.ethereum.jsonrpc.EthBlocksService.BlockByBlockHashResponse
import com.chipprbots.ethereum.jsonrpc.EthBlocksService.BlockByNumberResponse
import com.chipprbots.ethereum.jsonrpc.JsonRpcError
import com.chipprbots.ethereum.jsonrpc.ServiceResponse
import com.chipprbots.ethereum.jsonrpc.TransactionData
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils._
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

class TestEthBlockServiceWrapper(
    blockchain: Blockchain,
    blockchainReader: BlockchainReader,
    mining: Mining,
    blockQueue: BlockQueue
) extends EthBlocksService(blockchain, blockchainReader, mining, blockQueue)
    with Logger {

  /** Implements the eth_getBlockByHash method that fetches a requested block.
    *
    * @param request with the hash of the block requested
    * @return the block requested or None if the client doesn't have the block
    */
  override def getByBlockHash(
      request: EthBlocksService.BlockByBlockHashRequest
  ): ServiceResponse[EthBlocksService.BlockByBlockHashResponse] = super
    .getByBlockHash(request)
    .map(
      _.flatMap {

        case BlockByBlockHashResponse(None) =>
          Left(JsonRpcError.LogicError(s"EthBlockService: unable to find block for hash ${request.blockHash.toHex}"))

        case BlockByBlockHashResponse(Some(baseBlockResponse)) if baseBlockResponse.hash.isEmpty =>
          Left(JsonRpcError.LogicError(s"missing hash for block $baseBlockResponse"))

        case BlockByBlockHashResponse(Some(baseBlockResponse)) =>
          val ethResponseOpt = for {
            hash <- baseBlockResponse.hash
            fullBlock <- blockchainReader.getBlockByHash(hash).orElse(blockQueue.getBlockByHash(hash))
          } yield toEthResponse(fullBlock, baseBlockResponse)

          ethResponseOpt match {
            case None =>
              Left(
                JsonRpcError.LogicError(s"Ledger: unable to find block for hash=${baseBlockResponse.hash.get.toHex}")
              )
            case Some(_) =>
              Right(BlockByBlockHashResponse(ethResponseOpt))
          }
      }
    )

  /** Implements the eth_getBlockByNumber method that fetches a requested block.
    *
    * @param request with the block requested (by it's number or by tag)
    * @return the block requested or None if the client doesn't have the block
    */
  override def getBlockByNumber(
      request: EthBlocksService.BlockByNumberRequest
  ): ServiceResponse[EthBlocksService.BlockByNumberResponse] = super
    .getBlockByNumber(request)
    .map(
      _.map { blockByBlockResponse =>
        val bestBranch = blockchainReader.getBestBranch()
        val fullBlock = blockchainReader.getBlockByNumber(bestBranch, blockByBlockResponse.blockResponse.get.number).get
        BlockByNumberResponse(blockByBlockResponse.blockResponse.map(response => toEthResponse(fullBlock, response)))
      }
    )

  private def toEthResponse(block: Block, response: BaseBlockResponse) = EthBlockResponse(
    response.number,
    response.hash,
    if (block.header.mixHash.isEmpty) Some(UInt256.Zero.bytes) else Some(block.header.mixHash),
    response.parentHash,
    if (block.header.nonce.isEmpty) None else Some(block.header.nonce),
    response.sha3Uncles,
    response.logsBloom,
    response.transactionsRoot,
    response.stateRoot,
    response.receiptsRoot,
    response.miner,
    response.difficulty,
    response.totalDifficulty,
    response.extraData,
    response.size,
    response.gasLimit,
    response.gasUsed,
    response.timestamp,
    toEthTransaction(block, response.transactions),
    response.uncles
  )

  private def toEthTransaction(
      block: Block,
      responseTransactions: Either[Seq[ByteString], Seq[BaseTransactionResponse]]
  ): Either[Seq[ByteString], Seq[BaseTransactionResponse]] = responseTransactions.map { _ =>
    block.body.transactionList.zipWithIndex.map { case (stx, transactionIndex) =>
      EthTransactionResponse(tx = TransactionData(stx, Some(block.header), Some(transactionIndex)))
    }
  }
}

case class EthBlockResponse(
    number: BigInt,
    hash: Option[ByteString],
    mixHash: Option[ByteString],
    parentHash: ByteString,
    nonce: Option[ByteString],
    sha3Uncles: ByteString,
    logsBloom: ByteString,
    transactionsRoot: ByteString,
    stateRoot: ByteString,
    receiptsRoot: ByteString,
    miner: Option[ByteString],
    difficulty: BigInt,
    totalDifficulty: Option[BigInt],
    extraData: ByteString,
    size: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    timestamp: BigInt,
    transactions: Either[Seq[ByteString], Seq[BaseTransactionResponse]],
    uncles: Seq[ByteString]
) extends BaseBlockResponse

final case class EthTransactionResponse(
    hash: ByteString,
    nonce: BigInt,
    blockHash: Option[ByteString],
    blockNumber: Option[BigInt],
    transactionIndex: Option[BigInt],
    from: Option[ByteString],
    to: Option[ByteString],
    value: BigInt,
    gasPrice: BigInt,
    gas: BigInt,
    input: ByteString,
    r: BigInt,
    s: BigInt,
    v: BigInt
) extends BaseTransactionResponse

object EthTransactionResponse {

  implicit val blockchainConfig: BlockchainConfig = Config.blockchains.blockchainConfig

  def apply(tx: TransactionData): EthTransactionResponse =
    EthTransactionResponse(tx.stx, tx.blockHeader, tx.transactionIndex)

  def apply(
      stx: SignedTransaction,
      blockHeader: Option[BlockHeader] = None,
      transactionIndex: Option[Int] = None
  ): EthTransactionResponse =
    EthTransactionResponse(
      hash = stx.hash,
      nonce = stx.tx.nonce,
      blockHash = blockHeader.map(_.hash),
      blockNumber = blockHeader.map(_.number),
      transactionIndex = transactionIndex.map(txIndex => BigInt(txIndex)),
      from = SignedTransaction.getSender(stx).map(_.bytes),
      to = stx.tx.receivingAddress.map(_.bytes),
      value = stx.tx.value,
      gasPrice = stx.tx.gasPrice,
      gas = stx.tx.gasLimit,
      input = stx.tx.payload,
      r = stx.signature.r,
      s = stx.signature.s,
      v = stx.signature.v
    )
}

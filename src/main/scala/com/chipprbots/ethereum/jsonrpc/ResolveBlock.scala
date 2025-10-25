package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

sealed trait BlockParam

object BlockParam {
  case class WithNumber(n: BigInt) extends BlockParam
  case object Latest extends BlockParam
  case object Pending extends BlockParam
  case object Earliest extends BlockParam
}

case class ResolvedBlock(block: Block, pendingState: Option[InMemoryWorldStateProxy])

trait ResolveBlock {
  def blockchain: Blockchain
  def blockchainReader: BlockchainReader
  def mining: Mining

  def resolveBlock(blockParam: BlockParam): Either[JsonRpcError, ResolvedBlock] =
    blockParam match {
      case BlockParam.WithNumber(blockNumber) => getBlock(blockNumber).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Earliest                => getBlock(0).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Latest                  => getLatestBlock().map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Pending =>
        mining.blockGenerator.getPendingBlockAndState
          .map(pb => ResolvedBlock(pb.pendingBlock.block, pendingState = Some(pb.worldState)))
          .map(Right.apply)
          .getOrElse(resolveBlock(BlockParam.Latest)) //Default behavior in other clients
    }

  private def getBlock(number: BigInt): Either[JsonRpcError, Block] =
    blockchainReader
      .getBlockByNumber(blockchainReader.getBestBranch(), number)
      .toRight(JsonRpcError.InvalidParams(s"Block $number not found"))

  private def getLatestBlock(): Either[JsonRpcError, Block] =
    blockchainReader
      .getBestBlock()
      .toRight(JsonRpcError.InvalidParams("Latest block not found"))
}

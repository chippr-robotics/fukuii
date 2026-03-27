package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.utils.Config

sealed trait BlockParam

object BlockParam {
  case class WithNumber(n: BigInt) extends BlockParam
  case object Latest extends BlockParam
  case object Pending extends BlockParam
  case object Earliest extends BlockParam
  case object Safe extends BlockParam
  case object Finalized extends BlockParam

  /** Resolve safe/finalized to a concrete block number given best block. */
  def resolveNumber(blockParam: BlockParam, bestBlockNumber: BigInt): BigInt = blockParam match {
    case WithNumber(n) => n
    case Earliest      => 0
    case Latest        => bestBlockNumber
    case Pending       => bestBlockNumber
    case Safe =>
      val c = Config.config
      val depth = if (c.hasPath("network.rpc.safe-depth")) c.getInt("network.rpc.safe-depth") else 15
      (bestBlockNumber - depth).max(0)
    case Finalized =>
      val c = Config.config
      val depth = if (c.hasPath("network.rpc.finalized-depth")) c.getInt("network.rpc.finalized-depth") else 120
      (bestBlockNumber - depth).max(0)
  }
}

case class ResolvedBlock(block: Block, pendingState: Option[InMemoryWorldStateProxy])

trait ResolveBlock {
  def blockchain: Blockchain
  def blockchainReader: BlockchainReader
  def mining: Mining

  // Confirmation depths for PoW finality tags (EIP-1898 extension).
  // On PoW chains there is no protocol-defined finality — these map to latest minus a configurable depth.
  private lazy val finalizedDepth: BigInt = {
    val c = Config.config
    if (c.hasPath("network.rpc.finalized-depth")) c.getInt("network.rpc.finalized-depth") else 120
  }
  private lazy val safeDepth: BigInt = {
    val c = Config.config
    if (c.hasPath("network.rpc.safe-depth")) c.getInt("network.rpc.safe-depth") else 15
  }

  def resolveBlock(blockParam: BlockParam): Either[JsonRpcError, ResolvedBlock] =
    blockParam match {
      case BlockParam.WithNumber(blockNumber) => getBlock(blockNumber).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Earliest                => getBlock(0).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Latest                  => getLatestBlock().map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Safe                    => getBlockAtDepth(safeDepth).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Finalized               => getBlockAtDepth(finalizedDepth).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Pending =>
        mining.blockGenerator.getPendingBlockAndState
          .map(pb => ResolvedBlock(pb.pendingBlock.block, pendingState = Some(pb.worldState)))
          .map(Right.apply)
          .getOrElse(resolveBlock(BlockParam.Latest)) // Default behavior in other clients
    }

  private def getBlock(number: BigInt): Either[JsonRpcError, Block] =
    blockchainReader
      .getBlockByNumber(blockchainReader.getBestBranch(), number)
      .toRight(JsonRpcError.InvalidParams(s"Block $number not found"))

  private def getLatestBlock(): Either[JsonRpcError, Block] =
    blockchainReader
      .getBestBlock()
      .toRight(JsonRpcError.InvalidParams("Latest block not found"))

  private def getBlockAtDepth(depth: BigInt): Either[JsonRpcError, Block] = {
    val best = blockchainReader.getBestBlockNumber()
    val target = (best - depth).max(0)
    getBlock(target)
  }
}

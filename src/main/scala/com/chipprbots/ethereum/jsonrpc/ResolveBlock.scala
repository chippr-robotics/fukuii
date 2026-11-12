package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

sealed trait BlockParam

object BlockParam:
  case class WithNumber(n: BigInt) extends BlockParam
  case class WithHash(hash: org.apache.pekko.util.ByteString) extends BlockParam
  case object Latest extends BlockParam
  case object Pending extends BlockParam
  case object Earliest extends BlockParam
  case object Safe extends BlockParam
  case object Finalized extends BlockParam

case class ResolvedBlock(block: Block, pendingState: Option[InMemoryWorldStateProxy])

trait ResolveBlock:
  def blockchain: Blockchain
  def blockchainReader: BlockchainReader
  def mining: Mining
  def forkChoiceManagerOpt: Option[ForkChoiceManager] = None

  def resolveBlock(blockParam: BlockParam): Either[JsonRpcError, ResolvedBlock] =
    blockParam match
      case BlockParam.WithNumber(blockNumber) =>
        getBlock(blockNumber) match
          case Right(block)                                       => Right(ResolvedBlock(block, pendingState = None))
          case Left(_) if blockNumber > BigInt(Long.MaxValue) / 2 =>
            // Large number that doesn't match a block — try interpreting as a block hash
            val hashBytes = org.apache.pekko.util.ByteString(
              com.chipprbots.ethereum.utils.ByteUtils
                .bigIntToUnsignedByteArray(blockNumber)
                .reverse
                .padTo(32, 0.toByte)
                .reverse
            )
            getBlockByHash(hashBytes).map(ResolvedBlock(_, pendingState = None))
          case left => left.map(ResolvedBlock(_, pendingState = None))
      case BlockParam.WithHash(hash) => getBlockByHash(hash).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Earliest       => getBlock(0).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Latest         => getLatestBlock().map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Safe =>
        forkChoiceManagerOpt
          .flatMap(_.getSafeBlockHash)
          .flatMap(h => blockchainReader.getBlockByHash(BlockHash(h)))
          .map(b => Right(ResolvedBlock(b, pendingState = None)))
          .getOrElse(Left(JsonRpcError.InvalidParams("safe block not available")))
      case BlockParam.Finalized =>
        forkChoiceManagerOpt
          .flatMap(_.getFinalizedBlockHash)
          .flatMap(h => blockchainReader.getBlockByHash(BlockHash(h)))
          .map(b => Right(ResolvedBlock(b, pendingState = None)))
          .getOrElse(Left(JsonRpcError.InvalidParams("finalized block not available")))
      case BlockParam.Pending =>
        // On the unified state layout the pending block's state is held nowhere: the block was built on a layer that
        // was dropped, and only the state of the head is on disk. So "pending" is the head there, which is what it
        // is on any node with no pending block. With the guard off it is the block the generator last built.
        Option
          .unless(blockchainReader.holdsStateInPlace)(mining.blockGenerator.getPendingBlockAndState)
          .flatten
          .map(pb => ResolvedBlock(pb.pendingBlock.block, pendingState = Some(pb.worldState)))
          .map(Right.apply)
          .getOrElse(resolveBlock(BlockParam.Latest)) // Default behavior in other clients

  private def getBlockByHash(hash: org.apache.pekko.util.ByteString): Either[JsonRpcError, Block] =
    blockchainReader
      .getBlockByHash(BlockHash(hash))
      .toRight(JsonRpcError.InvalidParams(s"Block not found for hash"))

  private def getBlock(number: BigInt): Either[JsonRpcError, Block] =
    blockchainReader
      .getBlockByNumber(blockchainReader.getBestBranch, number)
      .toRight(JsonRpcError.InvalidParams(s"Block $number not found"))

  private def getLatestBlock(): Either[JsonRpcError, Block] =
    blockchainReader.getBestBlock
      .toRight(JsonRpcError.InvalidParams("Latest block not found"))

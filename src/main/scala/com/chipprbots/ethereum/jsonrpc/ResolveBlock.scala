package com.chipprbots.ethereum.jsonrpc

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.branch.BestBranch
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

sealed trait BlockParam

object BlockParam:
  case class WithNumber(n: BigInt) extends BlockParam

  /** A block named by hash. `requireCanonical` is EIP-1898's flag: geth and core-geth refuse a hash that is not the
    * canonical block of its height (`HeaderByNumberOrHash`, `StateAndHeaderByNumberOrHash`), `-32000 hash is not
    * currently canonical`. Only the object form `{blockHash, requireCanonical}` can set it.
    */
  case class WithHash(hash: org.apache.pekko.util.ByteString, requireCanonical: Boolean = false) extends BlockParam
  case object Latest extends BlockParam
  case object Pending extends BlockParam
  case object Earliest extends BlockParam
  case object Safe extends BlockParam
  case object Finalized extends BlockParam

case class ResolvedBlock(block: Block, pendingState: Option[InMemoryWorldStateProxy])

/** A block named by its header alone: what a state read needs (geth `HeaderByNumber` / `StateAndHeaderByNumber`). */
case class ResolvedHeader(header: BlockHeader, pendingState: Option[InMemoryWorldStateProxy])

/** How a state-reading method names its block: [[resolveHeader]] and the tags behind it. Split from [[ResolveBlock]] so
  * that a service with no [[Mining]] (`EthProofService`) resolves a block as every other state read does.
  */
trait ResolveHeader:
  def blockchainReader: BlockchainReader
  def forkChoiceManagerOpt: Option[ForkChoiceManager] = None

  /** The generator whose last build "pending" names, with its state, where the state is not held in place. */
  protected def pendingBlockGenerator: BlockGenerator

  /** The header of the block `blockParam` names, for the methods that read state: geth's `HeaderByNumber` /
    * `StateAndHeaderByNumber`. It needs the header and the number index, not the body: after a checkpoint import, or a
    * SNAP hand-off whose pivot body is not backfilled, a block has its header, index and state but no body, and
    * `eth_getBalance` at it must still answer. The tags mean what they do in `ResolveBlock.resolveBlock`, "pending"
    * included. Methods that return block or transaction content keep `resolveBlock`.
    */
  def resolveHeader(blockParam: BlockParam): Either[JsonRpcError, ResolvedHeader] =
    def plain(header: BlockHeader) = ResolvedHeader(header, pendingState = None)
    blockParam match
      case BlockParam.WithNumber(blockNumber) =>
        getHeader(blockNumber) match
          case Right(header)                                      => Right(plain(header))
          case Left(_) if blockNumber > BigInt(Long.MaxValue) / 2 =>
            // Large number that doesn't match a block — try interpreting as a block hash
            val hashBytes = org.apache.pekko.util.ByteString(
              com.chipprbots.ethereum.utils.ByteUtils
                .bigIntToUnsignedByteArray(blockNumber)
                .reverse
                .padTo(32, 0.toByte)
                .reverse
            )
            getHeaderByHash(hashBytes).map(plain)
          case left => left.map(plain)
      case BlockParam.WithHash(hash, requireCanonical) =>
        getHeaderByHash(hash).flatMap(canonicalIfRequired(requireCanonical)).map(plain)
      case BlockParam.Earliest => getHeader(0).map(plain)
      case BlockParam.Latest =>
        blockchainReader.getBestBlockHeader
          .toRight(JsonRpcError.InvalidParams("Latest block not found"))
          .map(plain)
      case BlockParam.Safe =>
        forkChoiceManagerOpt
          .flatMap(_.getSafeBlockHash)
          .flatMap(h => blockchainReader.getBlockHeaderByHash(BlockHash(h)))
          .flatMap(onTheHeadsChainHeader)
          .map(h => Right(plain(h)))
          .getOrElse(Left(JsonRpcError.InvalidParams("safe block not available")))
      case BlockParam.Finalized =>
        forkChoiceManagerOpt
          .flatMap(_.getFinalizedBlockHash)
          .flatMap(h => blockchainReader.getBlockHeaderByHash(BlockHash(h)))
          .map(h => Right(plain(h)))
          .getOrElse(Left(JsonRpcError.InvalidParams("finalized block not available")))
      case BlockParam.Pending =>
        // as resolveBlock: the head on the unified layout, else the block the generator last built, with its state
        Option
          .unless(blockchainReader.holdsStateInPlace)(pendingBlockGenerator.getPendingBlockAndState)
          .flatten
          .map(pb => ResolvedHeader(pb.pendingBlock.block.header, pendingState = Some(pb.worldState)))
          .map(Right.apply)
          .getOrElse(resolveHeader(BlockParam.Latest))

  /** `ResolveBlock.onTheHeadsChain` by headers: the header itself, or its highest ancestor on the head's chain. */
  protected def onTheHeadsChainHeader(start: BlockHeader): Option[BlockHeader] =
    if !blockchainReader.holdsStateInPlace then Some(start)
    else
      @annotation.tailrec
      def down(at: BlockHeader, left: Int): Option[BlockHeader] =
        if blockchainReader.getCanonicalHashByNumber(at.number.value).contains(at.hash) then Some(at)
        else if left == 0 || at.number.value == 0 then None
        else
          blockchainReader.getBlockHeaderByHash(at.parentHash) match
            case Some(parent) => down(parent, left - 1)
            case None         => None
      down(start, ResolveBlock.SafeAncestorSearch)

  /** The header at `number` on the best branch (the number index, as `ResolveBlock.getBlock`, but no body). A number
    * above the best block names no block, though a block imported and not yet the head may be stored at that height:
    * geth resolves a number through the canonical chain, and answers one it does not find with
    * [[ResolveBlock.HeaderNotFound]] (B-210).
    */
  private def getHeader(number: BigInt): Either[JsonRpcError, BlockHeader] =
    val header = blockchainReader.getBestBranch match
      case BestBranch(_, tip) if tip >= number && number >= 0 => blockchainReader.getBlockHeaderByNumber(number)
      case _                                                  => None
    header.toRight(ResolveBlock.HeaderNotFound)

  /** geth's `blockNrOrHash.RequireCanonical && GetCanonicalHash(header.Number) != hash`, checked after the header is
    * found: `-32000 hash is not currently canonical`.
    */
  protected def canonicalIfRequired(requireCanonical: Boolean)(header: BlockHeader): Either[JsonRpcError, BlockHeader] =
    if !requireCanonical || blockchainReader.getCanonicalHashByNumber(header.number.value).contains(header.hash) then
      Right(header)
    else Left(ResolveBlock.NotCanonical)

  private def getHeaderByHash(hash: org.apache.pekko.util.ByteString): Either[JsonRpcError, BlockHeader] =
    blockchainReader
      .getBlockHeaderByHash(BlockHash(hash))
      .toRight(ResolveBlock.HeaderForHashNotFound)

trait ResolveBlock extends ResolveHeader:
  def blockchain: Blockchain
  def mining: Mining

  protected def pendingBlockGenerator: BlockGenerator = mining.blockGenerator

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
      case BlockParam.WithHash(hash, requireCanonical) =>
        getBlockByHash(hash)
          .flatMap(block => canonicalIfRequired(requireCanonical)(block.header).map(_ => block))
          .map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Earliest => getBlock(0).map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Latest   => getLatestBlock().map(ResolvedBlock(_, pendingState = None))
      case BlockParam.Safe =>
        forkChoiceManagerOpt
          .flatMap(_.getSafeBlockHash)
          .flatMap(h => blockchainReader.getBlockByHash(BlockHash(h)))
          .flatMap(onTheHeadsChain)
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
          .unless(blockchainReader.holdsStateInPlace)(pendingBlockGenerator.getPendingBlockAndState)
          .flatten
          .map(pb => ResolvedBlock(pb.pendingBlock.block, pendingState = Some(pb.worldState)))
          .map(Right.apply)
          .getOrElse(resolveBlock(BlockParam.Latest)) // Default behavior in other clients

  /** On the unified state layout the head moves with the state, and the adoption of a long branch walks it down to the
    * fork before the consensus client names a new safe block (spec 018 C4d part 4b): a safe block the head has left is
    * not named. Its highest ancestor on the head's chain is, where the two chains meet. Elsewhere the block itself.
    */
  private def onTheHeadsChain(block: Block): Option[Block] =
    if !blockchainReader.holdsStateInPlace then Some(block)
    else
      onTheHeadsChainHeader(block.header).flatMap { header =>
        if header.hash == block.header.hash then Some(block) else blockchainReader.getBlockByHash(header.hash)
      }

  private def getBlockByHash(hash: org.apache.pekko.util.ByteString): Either[JsonRpcError, Block] =
    blockchainReader
      .getBlockByHash(BlockHash(hash))
      .toRight(ResolveBlock.HeaderForHashNotFound)

  private def getBlock(number: BigInt): Either[JsonRpcError, Block] =
    blockchainReader
      .getBlockByNumber(blockchainReader.getBestBranch, number)
      .toRight(JsonRpcError.InvalidParams(s"Block $number not found"))

  private def getLatestBlock(): Either[JsonRpcError, Block] =
    blockchainReader.getBestBlock
      .toRight(JsonRpcError.InvalidParams("Latest block not found"))

object ResolveBlock:

  /** What a method that reads a block's state answers for a block number that names no block: geth's and core-geth's
    * answer (`StateAndHeaderByNumber`), `-32000 header not found`.
    */
  val HeaderNotFound: JsonRpcError = JsonRpcError.LogicError("header not found")

  /** What a read answers for a block hash that names no stored block: geth's `errors.New("header for hash not found")`
    * (eth/api_backend.go `HeaderByNumberOrHash`, `StateAndHeaderByNumberOrHash`), a plain error, so `-32000`; it was
    * `-32602 Block not found for hash` (B-485).
    */
  val HeaderForHashNotFound: JsonRpcError = JsonRpcError.LogicError("header for hash not found")

  /** What a state read answers for `{blockHash, requireCanonical: true}` naming a stored block that is not canonical:
    * geth's `errors.New("hash is not currently canonical")` (eth/api_backend.go `HeaderByNumberOrHash` and
    * `StateAndHeaderByNumberOrHash`), a plain error, so the RPC layer's default code `-32000`.
    */
  val NotCanonical: JsonRpcError = JsonRpcError.LogicError("hash is not currently canonical")

  /** How far below a safe block the head's chain is looked for, at most: one block read each. */
  val SafeAncestorSearch: Int = 1_024

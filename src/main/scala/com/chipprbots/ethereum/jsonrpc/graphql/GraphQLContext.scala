package com.chipprbots.ethereum.jsonrpc.graphql

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.{Block, BlockHeader, Blockchain, BlockchainReader, Receipt, SignedTransaction, TxLogEntry}
import com.chipprbots.ethereum.jsonrpc.EthBlocksService
import com.chipprbots.ethereum.jsonrpc.EthFilterService
import com.chipprbots.ethereum.jsonrpc.EthInfoService
import com.chipprbots.ethereum.jsonrpc.EthTxService
import com.chipprbots.ethereum.jsonrpc.EthUserService
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Everything a GraphQL resolver needs to answer a query.
  *
  * Passed as Sangria's `Ctx` type parameter — every `resolve` function can pull services from here.
  */
case class GraphQLContext(
    blockchain: Blockchain,
    blockchainReader: BlockchainReader,
    mining: Mining,
    evmCodeStorage: EvmCodeStorage,
    blockchainConfig: BlockchainConfig,
    ethBlocksService: EthBlocksService,
    ethTxService: EthTxService,
    ethInfoService: EthInfoService,
    ethUserService: EthUserService,
    ethFilterService: EthFilterService
)

/** GraphQL wrappers that carry a bit more context than the bare domain type — for example a
  * transaction needs to know its own block to resolve `block`/`status`/`gasUsed`, and a log needs to
  * know its parent transaction plus its ordinal position.
  */
object GraphQLTypes {

  /** An account at a specific block. Resolvers fetch `balance`/`code`/`nonce`/`storage` on demand. */
  final case class GAccount(address: ByteString, blockNumber: BigInt)

  /** A block plus its canonical total difficulty (when available). */
  final case class GBlock(block: Block, totalDifficulty: Option[BigInt]) {
    def header: BlockHeader = block.header
    def number: BigInt = block.header.number
    def hash: ByteString = block.header.hash
  }

  /** A transaction in flight. `blockInfo` is present when the tx has been mined. */
  final case class GTransaction(
      stx: SignedTransaction,
      blockInfo: Option[GTxBlockInfo]
  ) {
    def hash: ByteString = stx.hash
  }

  /** Position of a mined transaction within its block, plus the block itself. */
  final case class GTxBlockInfo(block: Block, txIndex: Int)

  /** A log entry with enough context to resolve `account`, `topics`, `transaction`. */
  final case class GLog(
      parent: GTransaction,
      logIndex: Int,
      log: TxLogEntry
  )

  /** Result of an `eth_call`-shaped resolver. */
  final case class GCallResult(data: ByteString, gasUsed: Long, status: Long)

  /** Matches the SDL `SyncState` type. */
  final case class GSyncState(startingBlock: Long, currentBlock: Long, highestBlock: Long)

  /** Marker for the top-level `Pending` object; the resolver closes over the context. */
  case object GPending

  /** Receipt + index, cached together for efficient log/status resolution. */
  final case class GReceiptBundle(
      block: Block,
      txIndex: Int,
      receipt: Receipt,
      gasUsedByTx: BigInt,
      cumulativeGasUsed: BigInt,
      baseLogIndex: Int
  )
}

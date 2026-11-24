package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader

/** Reads a transaction's location by hash and follows it to a block only if that block is the canonical one.
  *
  * The tx-location index can name a block that is no longer canonical (a lookup left behind by a reorg). go-ethereum's
  * `rawdb.ReadTransaction` (core/rawdb/accessors_indexes.go) reads the lookup entry for the block NUMBER, then takes
  * `ReadCanonicalHash(db, number)` and reads the body of that block, so a stale entry never yields a transaction from a
  * side-chain block. This is the same check: the canonical header at the block's number must carry the lookup's block
  * hash (`getBlockHeaderByNumber` reads the canonical number-to-hash index).
  */
object CanonicalTxLookup:

  /** A transaction found in a canonical block: the block and the transaction's index in it. */
  final case class Found(block: Block, txIndex: Int)

  def apply(
      transactionMappingStorage: TransactionMappingStorage,
      blockchainReader: BlockchainReader,
      txHash: ByteString
  ): Option[Found] =
    for
      location <- transactionMappingStorage.get(txHash)
      block <- blockchainReader.getBlockByHash(BlockHash(location.blockHash))
      _ <- blockchainReader
        .getBlockHeaderByNumber(block.header.number.value)
        .filter(_.hash.value == location.blockHash)
      if location.txIndex >= 0 && location.txIndex < block.body.transactionList.size
    yield Found(block, location.txIndex)

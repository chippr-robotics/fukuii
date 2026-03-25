package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.vm.EvmConfig

/** Warms RocksDB's block cache by pre-reading state trie nodes for a block's transactions.
  *
  * For each transaction in the block, reads the sender account, receiver account, and (for contract calls) the
  * contract's bytecode from a read-only world state. This traverses the account trie and storage trie, populating the
  * OS page cache and RocksDB block cache so that the real execution sees cache hits instead of disk reads.
  *
  * Based on Geth's statePrefetcher pattern. All results are discarded.
  */
class StatePrefetcher(
    blockchain: Blockchain,
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage
) extends Logger {

  /** Prefetch state for a block's transactions in the background.
    *
    * @param block
    *   the block whose transaction state should be pre-read
    * @param parentStateRoot
    *   state root of the parent block (for building the read-only world state)
    */
  def prefetchAsync(block: Block, parentStateRoot: ByteString)(implicit
      blockchainConfig: BlockchainConfig,
      runtime: IORuntime
  ): Unit =
    if (block.body.transactionList.nonEmpty) {
      IO(prefetch(block, parentStateRoot)).start.unsafeRunAndForget()
    }

  private def prefetch(block: Block, parentStateRoot: ByteString)(implicit
      blockchainConfig: BlockchainConfig
  ): Unit =
    try {
      val world = InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        blockchain.getReadOnlyMptStorage(),
        (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = parentStateRoot,
        noEmptyAccounts = EvmConfig.forBlock(block.header.number, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )

      // Walk the account trie for each unique address referenced by transactions.
      // This warms ~12 trie nodes per address (account trie depth) + bytecode reads.
      val addresses = block.body.transactionList.flatMap { stx =>
        val sender = SignedTransaction.getSender(stx)
        val receiver = stx.tx.receivingAddress
        sender.toSeq ++ receiver.toSeq
      }.distinct

      addresses.foreach { address =>
        world.getAccount(address) // Warm account trie nodes
        world.getCode(address) // Warm bytecode storage
      }
    } catch {
      case _: Exception =>
      // Prefetch failures are expected (stale state, missing nodes) and harmless
    }
}

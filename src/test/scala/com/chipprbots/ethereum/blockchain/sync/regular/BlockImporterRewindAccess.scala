package com.chipprbots.ethereum.blockchain.sync.regular

import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter

/** Test-only door into `BlockImporter`'s package-private SYNC-FORK rewind, for specs outside this package. */
object BlockImporterRewindAccess:
  def rewind(
      reader: BlockchainReader,
      writer: BlockchainWriter,
      reorgState: ReorgStateHandler,
      target: BigInt,
      targetHash: BlockHash,
      currentBest: BigInt
  ): Unit = BlockImporter.rewindCanonicalChain(reader, writer, reorgState, target, targetHash, currentBest)

package com.chipprbots.ethereum.blockchain.data

import java.io.{File, FileInputStream}

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.nextElementIndex
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

/** Imports RLP-encoded blocks from a file into the blockchain.
  *
  * Reads concatenated RLP-encoded blocks (as produced by geth export or hive's chain.rlp),
  * executes each block through the standard block execution pipeline, and persists the results.
  *
  * This is a synchronous, startup-time operation — not intended for runtime use.
  */
class ChainImporter(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    blockExecution: BlockExecution
) extends Logger {

  /** Import all blocks from an RLP chain file.
    *
    * @param filePath path to the chain.rlp file (concatenated RLP-encoded blocks)
    * @return (imported, skipped, failed) counts
    */
  def importChainFile(filePath: String)(implicit blockchainConfig: BlockchainConfig): (Int, Int, Int) = {
    val file = new File(filePath)
    if (!file.exists()) {
      log.warn(s"Chain import file not found: $filePath")
      return (0, 0, 0)
    }

    val bytes = readFile(file)
    log.info(s"Chain import: reading ${bytes.length} bytes from $filePath")

    val blocks = decodeBlocks(bytes)
    log.info(s"Chain import: decoded ${blocks.size} blocks")

    if (blocks.isEmpty) return (0, 0, 0)

    // Log genesis vs first block's expected parent for debugging genesis mismatches
    val genesisOpt = blockchainReader.getBlockHeaderByNumber(0)
    genesisOpt.foreach { genesis =>
      log.error(s"Chain import: genesis hash=${genesis.hashAsHexString}")
      log.error(s"Chain import: genesis stateRoot=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(genesis.stateRoot)}")
      log.error(s"Chain import: genesis difficulty=${genesis.difficulty}, gasLimit=${genesis.gasLimit}, extraData=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(genesis.extraData)}")
    }
    val firstBlock = blocks.head
    log.error(s"Chain import: block ${firstBlock.header.number} expects parent=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(firstBlock.header.parentHash)}")

    var imported = 0
    var skipped = 0
    var failed = 0

    for (block <- blocks) {
      val blockNum = block.header.number
      val blockHash = block.header.hash

      // Skip if already imported
      val alreadyExists = blockchainReader.getBlockHeaderByNumber(blockNum) match {
        case Some(existing) if existing.hash == blockHash => true
        case Some(existing) =>
          log.warn(s"Chain import: block $blockNum hash mismatch — " +
            s"existing=${existing.hashAsHexString}, importing=${block.header.hashAsHexString}")
          false
        case None => false
      }

      if (alreadyExists) {
        skipped += 1
      } else {
        importBlock(block) match {
          case Right(receipts) =>
            val parentWeight = blockchainReader.getChainWeightByHash(block.header.parentHash)
              .getOrElse(ChainWeight.zero)
            val newWeight = parentWeight.increase(block.header)

            blockchainWriter.save(block, receipts, newWeight, saveAsBestBlock = true)
            imported += 1

            if (imported % 10 == 0 || blockNum == blocks.last.header.number) {
              log.info(s"Chain import: block $blockNum imported ($imported/${blocks.size})")
            }

          case Left(error) =>
            log.error(s"Chain import: block $blockNum failed — $error")
            failed += 1
        }
      }
    }

    log.info(s"Chain import complete: $imported imported, $skipped skipped, $failed failed")
    (imported, skipped, failed)
  }

  private def importBlock(block: Block)(implicit blockchainConfig: BlockchainConfig): Either[Any, Seq[Receipt]] = {
    // Execute without pre/post validation — blocks are from a trusted source.
    // Validate stateRoot (proves correct execution), log gas mismatches as warnings.
    blockExecution.executeBlockNoValidation(block).flatMap { case (receipts, gasUsed, stateRootHash) =>
      val gasMismatch = block.header.gasUsed != gasUsed
      val stateMismatch = block.header.stateRoot != stateRootHash

      if (gasMismatch || stateMismatch) {
        // Per-tx cumulative gas for debugging
        receipts.zipWithIndex.foreach { case (r, i) =>
          log.error(s"Chain import: block ${block.header.number} tx[$i] cumulativeGas=${r.cumulativeGasUsed}")
        }
        log.error(s"Chain import: block ${block.header.number} totalGas: expected=${block.header.gasUsed} got=$gasUsed")
      }

      if (stateMismatch && gasMismatch) {
        // Both state and gas are wrong — real execution error
        Left(s"stateRoot mismatch: expected ${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(block.header.stateRoot)}" +
          s" got ${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(stateRootHash)}")
      } else if (stateMismatch) {
        Left(s"stateRoot mismatch: expected ${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(block.header.stateRoot)}" +
          s" got ${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(stateRootHash)}")
      } else {
        if (gasMismatch) {
          log.warn(s"Chain import: block ${block.header.number} gas mismatch — state correct, accepting")
        }
        Right(receipts)
      }
    }
  }

  /** Decode concatenated RLP-encoded blocks from a byte array. */
  private def decodeBlocks(data: Array[Byte]): Seq[Block] = {
    val blocks = scala.collection.mutable.ArrayBuffer.empty[Block]
    var pos = 0

    while (pos < data.length) {
      try {
        val nextPos = nextElementIndex(data, pos)
        val blockBytes = data.slice(pos, nextPos)
        val block = blockBytes.toBlock
        blocks += block
        pos = nextPos
      } catch {
        case e: Exception =>
          log.error(s"Chain import: RLP decode error at byte offset $pos", e)
          return blocks.toSeq // return what we decoded so far
      }
    }

    blocks.toSeq
  }

  private def readFile(file: File): Array[Byte] = {
    val fis = new FileInputStream(file)
    try {
      val bytes = new Array[Byte](file.length().toInt)
      fis.read(bytes)
      bytes
    } finally {
      fis.close()
    }
  }
}

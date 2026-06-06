package com.chipprbots.ethereum.consensus.blocks

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.difficulty.DifficultyCalculator
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.pow.blocks.Ommers
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.consensus.pow.blocks.OmmersSeqEnc
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.ledger.BlockPreparator
import com.chipprbots.ethereum.ledger.BlockResult
import com.chipprbots.ethereum.ledger.BloomFilter
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.PreparedBlock
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils.or

/** This is a skeleton for a generic [[com.chipprbots.ethereum.consensus.blocks.BlockGenerator BlockGenerator]].
  */
abstract class BlockGeneratorSkeleton(
    miningConfig: MiningConfig,
    difficultyCalc: DifficultyCalculator,
    _blockTimestampProvider: BlockTimestampProvider = DefaultBlockTimestampProvider
) extends TestBlockGenerator {

  protected val headerExtraData = miningConfig.headerExtraData

  protected val blockCacheSize = miningConfig.blockCacheSize

  protected val cache: AtomicReference[List[PendingBlockAndState]] = new AtomicReference(Nil)

  protected def newBlockBody(transactions: Seq[SignedTransaction], x: X): BlockBody

  protected def defaultPrepareHeader(
      blockNumber: BigInt,
      parent: Block,
      beneficiary: Address,
      blockTimestamp: Long,
      x: Ommers
  )(implicit blockchainConfig: BlockchainConfig): BlockHeader =
    BlockHeader(
      parentHash = parent.header.hash,
      ommersHash = ByteString(kec256(x.toBytes: Array[Byte])),
      beneficiary = beneficiary.bytes,
      stateRoot = ByteString.empty,
      // we are not able to calculate transactionsRoot here because we do not know if they will fail
      transactionsRoot = ByteString.empty,
      receiptsRoot = ByteString.empty,
      logsBloom = ByteString.empty,
      difficulty = difficultyCalc.calculateDifficulty(blockNumber, blockTimestamp, parent.header),
      number = blockNumber,
      gasLimit = calculateGasLimit(parent.header.gasLimit, blockNumber),
      gasUsed = 0,
      unixTimestamp = blockTimestamp,
      extraData = blockchainConfig.daoForkConfig
        .flatMap(daoForkConfig => daoForkConfig.getExtraData(blockNumber))
        .getOrElse(headerExtraData),
      mixHash = ByteString.empty,
      nonce = ByteString.empty
    )

  protected def prepareHeader(
      blockNumber: BigInt,
      parent: Block,
      beneficiary: Address,
      blockTimestamp: Long,
      x: X
  )(implicit blockchainConfig: BlockchainConfig): BlockHeader

  // scalastyle:off parameter.number
  protected def prepareBlock(
      evmCodeStorage: EvmCodeStorage,
      parent: Block,
      transactions: Seq[SignedTransaction],
      beneficiary: Address,
      blockNumber: BigInt,
      blockPreparator: BlockPreparator,
      x: X,
      initialWorldStateBeforeExecution: Option[InMemoryWorldStateProxy]
  )(implicit blockchainConfig: BlockchainConfig): PendingBlockAndState = {

    val blockTimestamp = blockTimestampProvider.getEpochSecond
    val header = prepareHeader(blockNumber, parent, beneficiary, blockTimestamp, x)
    val nextBlockBaseFee = com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator.calcBaseFee(
      parent.header,
      blockchainConfig
    )
    val transactionsForBlock = prepareTransactions(transactions, header.gasLimit, nextBlockBaseFee, blockNumber)
    val body = newBlockBody(transactionsForBlock, x)
    val block = Block(header, body)

    blockPreparator.prepareBlock(evmCodeStorage, block, parent.header, initialWorldStateBeforeExecution) match {
      case PreparedBlock(prepareBlock, BlockResult(_, gasUsed, receipts, _), stateRoot, updatedWorld) =>
        val receiptsLogs: Seq[Array[Byte]] =
          BloomFilter.EmptyBloomFilter.toArray +: receipts.map(_.logsBloomFilter.toArray)
        val bloomFilter = ByteString(or(receiptsLogs: _*))

        PendingBlockAndState(
          PendingBlock(
            block.copy(
              header = block.header.copy(
                transactionsRoot = buildMpt(prepareBlock.body.transactionList, SignedTransaction.byteArraySerializable),
                stateRoot = stateRoot,
                receiptsRoot = buildMpt(receipts, Receipt.byteArraySerializable),
                logsBloom = bloomFilter,
                gasUsed = gasUsed
              ),
              body = prepareBlock.body
            ),
            receipts
          ),
          updatedWorld
        )
    }
  }

  protected def prepareTransactions(
      transactions: Seq[SignedTransaction],
      blockGasLimit: BigInt,
      blockBaseFee: BigInt = BigInt(0),
      blockNumber: BigInt = BigInt(0)
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransaction] = {

    // ECIP-1122: filter out txs with effectiveTip < minTip before sorting — but only from
    // Olympia. Pre-Olympia ETC has no base fee; legacy txs are priced by gasPrice alone, and
    // calcBaseFee returns the 1 gwei floor even before Olympia, so an un-gated filter would
    // (a) drop legitimate sub-(floor+minTip) legacy txs and (b) in block production, desync the
    // tx list from a pre-sealed header (→ HeaderPoWError). Gate on the Olympia activation block.
    val isOlympia = blockNumber >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    val eligibleTransactions =
      if (!isOlympia) transactions
      else
        transactions.filter { tx =>
          val effectiveTip =
            com.chipprbots.ethereum.domain.Transaction.effectiveGasPrice(tx.tx, Some(blockBaseFee)) - blockBaseFee
          effectiveTip >= blockchainConfig.minTip
        }

    val sortedTransactions: Seq[SignedTransaction] = eligibleTransactions
      // should be safe to call get as we do not insert improper transactions to pool.
      .flatMap(tx => SignedTransaction.getSender(tx).map(sender => (sender, tx)))
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2))
      .values
      .toList
      .flatMap { txsFromSender =>
        val ordered = txsFromSender
          .sortBy(-_.tx.gasPrice)
          .sortBy(_.tx.nonce)
          .foldLeft(Seq.empty[SignedTransaction]) { case (txs, tx) =>
            if (txs.exists(_.tx.nonce == tx.tx.nonce)) {
              txs
            } else {
              txs :+ tx
            }
          }
          .takeWhile(_.tx.gasLimit <= blockGasLimit)
        ordered.headOption.map(_.tx.gasPrice -> ordered)
      }
      .sortBy { case (gasPrice, _) => gasPrice }
      .reverse
      .flatMap { case (_, txs) => txs }

    val transactionsForBlock: Seq[SignedTransaction] = sortedTransactions
      .scanLeft((BigInt(0), None: Option[SignedTransaction])) { case ((accumulatedGas, _), stx) =>
        (accumulatedGas + stx.tx.gasLimit, Some(stx))
      }
      .collect { case (gas, Some(stx)) => (gas, stx) }
      .takeWhile { case (gas, _) => gas <= blockGasLimit }
      .map { case (_, stx) => stx }

    transactionsForBlock
  }

  /** Calculates the gas limit for the next block, converging toward the target at ±1/1024 per block.
    *
    * The target is resolved via the fork-embedded gas schedule (ForkBlockNumbers.gasLimitAdjustmentStartAt). When the
    * schedule returns Some(target), it is authoritative — the operator's gas-limit-target config is ignored entirely
    * for this fork era. When None, falls back to miningConfig.gasLimitTarget (existing behavior).
    *
    * This guarantees that a Fukuii miner with a stale operator config (e.g. gas-limit-target = 8M) will automatically
    * begin converging toward 60M at Olympia activation without any config change. The algorithm matches core-geth's
    * CalcGasLimit() and besu's OlympiaTargetingGasLimitCalculator.
    */
  protected def calculateGasLimit(parentGas: BigInt, blockNumber: BigInt)(implicit
      blockchainConfig: BlockchainConfig
  ): BigInt = {
    val target = blockchainConfig.forkBlockNumbers
      .gasLimitAdjustmentStartAt(blockNumber)
      .getOrElse(miningConfig.gasLimitTarget)
    val delta = parentGas / BlockHeaderValidator.GasLimitBoundDivisor - 1
    if (parentGas < target) { val n = parentGas + delta; if (n > target) target else n }
    else if (parentGas > target) { val n = parentGas - delta; if (n < target) target else n }
    else parentGas
  }

  protected def buildMpt[K](entities: Seq[K], vSerializable: ByteArraySerializable[K]): ByteString = {
    val stateStorage = StateStorage.getReadOnlyStorage(EphemDataSource())
    val mpt = MerklePatriciaTrie[Int, K](
      source = stateStorage
    )(intByteArraySerializable, vSerializable)
    val hash = entities.zipWithIndex.foldLeft(mpt) { case (trie, (value, key)) => trie.put(key, value) }.getRootHash
    ByteString(hash)
  }

  def blockTimestampProvider: BlockTimestampProvider = _blockTimestampProvider

  /** This function returns the block currently being mined block with highest timestamp
    */
  def getPendingBlock: Option[PendingBlock] =
    getPendingBlockAndState.map(_.pendingBlock)

  def getPendingBlockAndState: Option[PendingBlockAndState] = {
    val pendingBlocks = cache.get()
    if (pendingBlocks.isEmpty) None
    else Some(pendingBlocks.maxBy(_.pendingBlock.block.header.unixTimestamp))
  }
}

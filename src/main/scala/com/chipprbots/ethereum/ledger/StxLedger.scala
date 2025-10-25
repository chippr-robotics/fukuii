package com.chipprbots.ethereum.ledger

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.ledger.TxResult
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.vm.EvmConfig

class StxLedger(
    blockchain: BlockchainImpl,
    blockchainReader: BlockchainReader,
    evmCodeStorage: EvmCodeStorage,
    blockPreparator: BlockPreparator,
    configBuilder: BlockchainConfigBuilder
) {
  import configBuilder._

  def simulateTransaction(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy]
  ): TxResult = {
    val tx = stx.tx

    val world1 = world.getOrElse(
      InMemoryWorldStateProxy(
        evmCodeStorage = evmCodeStorage,
        mptStorage = blockchain.getReadOnlyMptStorage(),
        getBlockHashByNumber = (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash),
        accountStartNonce = blockchainConfig.accountStartNonce,
        stateRootHash = blockHeader.stateRoot,
        noEmptyAccounts = EvmConfig.forBlock(blockHeader.number, blockchainConfig).noEmptyAccounts,
        ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
      )
    )

    val senderAddress = stx.senderAddress
    val world2 =
      if (world1.getAccount(senderAddress).isEmpty) {
        world1.saveAccount(senderAddress, Account.empty(blockchainConfig.accountStartNonce))
      } else {
        world1
      }

    val worldForTx = blockPreparator.updateSenderAccountBeforeExecution(tx, senderAddress, world2)
    val result = blockPreparator.runVM(tx, senderAddress, blockHeader, worldForTx)
    val totalGasToRefund = blockPreparator.calcTotalGasToRefund(tx, result)

    TxResult(result.world, tx.tx.gasLimit - totalGasToRefund, result.logs, result.returnData, result.error)
  }

  def binarySearchGasEstimation(
      stx: SignedTransactionWithSender,
      blockHeader: BlockHeader,
      world: Option[InMemoryWorldStateProxy]
  ): BigInt = {
    val lowLimit = EvmConfig.forBlock(blockHeader.number, blockchainConfig).feeSchedule.G_transaction
    val tx = stx.tx
    val highLimit = tx.tx.gasLimit

    if (highLimit < lowLimit) {
      highLimit
    } else {
      StxLedger.binaryChop(lowLimit, highLimit) { gasLimit =>
        simulateTransaction(
          stx.copy(tx = tx.copy(tx = Transaction.withGasLimit(gasLimit)(tx.tx))),
          blockHeader,
          world
        ).vmError
      }
    }
  }
}

object StxLedger {

  /** Function finds minimal value in some interval for which provided function do not return error
    * If searched value is not in provided interval, function returns maximum value of searched interval
    * @param min minimum of searched interval
    * @param max maximum of searched interval
    * @param f function which return error in case to little value provided
    * @return minimal value for which provided function do not return error
    */
  @tailrec
  private[ledger] def binaryChop[Err](min: BigInt, max: BigInt)(f: BigInt => Option[Err]): BigInt = {
    assert(min <= max)

    if (min == max)
      max
    else {
      val mid = min + (max - min) / 2
      val possibleError = f(mid)
      if (possibleError.isEmpty)
        binaryChop(min, mid)(f)
      else
        binaryChop(mid + 1, max)(f)
    }
  }
}

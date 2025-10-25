package com.chipprbots.ethereum.vm.utils

import akka.util.ByteString

import com.chipprbots.ethereum.Fixtures.{Blocks => BlockFixtures}
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction

object MockVmInput {

  class MockTransaction(
      tx: LegacyTransaction,
      senderAddress: Address,
      pointSign: Byte = 0,
      signatureRandom: BigInt = 0,
      signature: BigInt = 0
  ) extends SignedTransaction(
        tx,
        ECDSASignature(v = pointSign, r = signatureRandom.bigInteger, s = signature.bigInteger)
      )

  val defaultGasPrice: BigInt = 1000

  def transaction(
      senderAddress: Address,
      payload: ByteString,
      value: BigInt,
      gasLimit: BigInt,
      gasPrice: BigInt = defaultGasPrice,
      receivingAddress: Option[Address] = None,
      nonce: BigInt = 0
  ): SignedTransaction =
    new MockTransaction(LegacyTransaction(nonce, gasPrice, gasLimit, receivingAddress, value, payload), senderAddress)

  def blockHeader: BlockHeader = BlockFixtures.ValidBlock.header

}

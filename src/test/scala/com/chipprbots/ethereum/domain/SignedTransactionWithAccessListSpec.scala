package com.chipprbots.ethereum.domain

import org.scalatest.flatspec.AnyFlatSpec

import com.chipprbots.ethereum.vm.Generators

class SignedTransactionWithAccessListSpec extends AnyFlatSpec with SignedTransactionBehavior {

  private def allowedPointSigns(chainId: Byte) = Set(0.toByte, 1.toByte)

  ("Signed TransactionWithAccessList" should behave).like(
    SignedTransactionBehavior(Generators.typedTransactionGen, allowedPointSigns)
  )
}

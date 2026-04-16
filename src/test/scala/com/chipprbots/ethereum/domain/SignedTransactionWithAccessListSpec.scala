package com.chipprbots.ethereum.domain

import org.scalatest.flatspec.AnyFlatSpec

import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.vm.Generators

class SignedTransactionWithAccessListSpec extends AnyFlatSpec with SignedTransactionBehavior {

  private def allowedPointSigns(chainId: BigInt) = Set(BigInt(0), BigInt(1))

  ("Signed TransactionWithAccessList" should behave).like(
    SignedTransactionBehavior(Generators.typedTransactionGen, allowedPointSigns)
  )
}

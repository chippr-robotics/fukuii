package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.vm.utils.EvmTestEnv
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AnyFreeSpec
import com.chipprbots.ethereum.domain.UInt256

// scalastyle:off magic.number
class ContractCallingItselfSpec extends AnyFreeSpec with Matchers {

  "EVM running ContractCallingItself contract" - {

    "should handle a call to itself" in new EvmTestEnv {
      val (_, contract) = deployContract("ContractCallingItself")

      contract.getSomeVar().call().returnData shouldBe UInt256(10).bytes

      val result = contract.callSelf().call()
      result.error shouldBe None

      contract.getSomeVar().call().returnData shouldBe UInt256(20).bytes
    }
  }

}

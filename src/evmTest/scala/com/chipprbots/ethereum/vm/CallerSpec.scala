package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.vm.utils.EvmTestEnv
import org.scalatest.matchers.should.Matchers
import org.scalatest.freespec.AnyFreeSpec
import com.chipprbots.ethereum.domain.UInt256

// scalastyle:off magic.number
class CallerSpec extends AnyFreeSpec with Matchers {

  "EVM running Caller contract" - {

    "should handle a call to Callee" in new EvmTestEnv {
      val (_, callee) = deployContract("Callee")
      val (_, caller) = deployContract("Caller")

      val callRes = caller.makeACall(callee.address, 123).call()
      callRes.error shouldBe None

      callee.getFoo().call().returnData shouldBe UInt256(123).bytes
    }
  }

}

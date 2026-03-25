package com.chipprbots.ethereum.consensus.mining

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.testing.Tags._

class MiningSpec extends AnyFlatSpec with Matchers {

  "KnownProtocols" should "have unique names" taggedAs (UnitTest, ConsensusTest) in {
    val protocols = Protocol.KnownProtocols
    val names = Protocol.KnownProtocolNames

    protocols.size shouldBe names.size
  }

  it should "contain ethash" taggedAs (UnitTest, ConsensusTest) in {
    Protocol.find(Protocol.PoW.name).isDefined shouldBe true
  }
}

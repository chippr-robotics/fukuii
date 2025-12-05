package com.chipprbots.ethereum.domain

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.NewBlock
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._

class BlockchainReaderSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks with SecureRandomBuilder {

  val chainId: Option[BigInt] = Some(BigInt(0x3d))

  "BlockchainReader" should "be able to get the best block after it was stored by BlockchainWriter" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    forAll(ObjectGenerators.newBlockGen(secureRandom, chainId)) { case NewBlock(block, weight) =>
      blockchainWriter.save(block, Nil, ChainWeight(0, weight), true)

      blockchainReader.getBestBlock() shouldBe Some(block)
    }
  }

}

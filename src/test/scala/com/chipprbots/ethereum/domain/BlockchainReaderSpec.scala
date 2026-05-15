package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.Fixtures
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
      blockchainWriter.save(block, Nil, ChainWeight(weight), true)

      blockchainReader.getBestBlock() shouldBe Some(block)
    }
  }

  "BlockchainReader.resolveETH69ChainWeight" should "return DB_LOOKUP when peer hash is in ChainWeightStorage (Tier 1)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val genesisWeight = ChainWeight.zero.increase(genesis.header)
    blockchainWriter.save(genesis, Nil, genesisWeight, saveAsBestBlock = true)

    val block1 = genesis.copy(header = genesis.header.copy(parentHash = genesis.header.hash, number = 1))
    val block1Weight = genesisWeight.increase(block1.header)
    blockchainWriter.save(block1, Nil, block1Weight, saveAsBestBlock = true)

    val (cw, source) =
      blockchainReader.resolveETH69ChainWeight(block1.header.hash, block1.header.number, isPoWChain = true)
    source shouldBe "DB_LOOKUP"
    cw.totalDifficulty shouldBe block1Weight.totalDifficulty
  }

  it should "return CANONICAL_NUMBER when peer hash is unknown but peer block number is canonical (Tier 2)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val genesisWeight = ChainWeight.zero.increase(genesis.header)
    blockchainWriter.save(genesis, Nil, genesisWeight, saveAsBestBlock = true)

    val block1 = genesis.copy(header = genesis.header.copy(parentHash = genesis.header.hash, number = 1))
    val block1Weight = genesisWeight.increase(block1.header)
    blockchainWriter.save(block1, Nil, block1Weight, saveAsBestBlock = true)

    // Peer advertises a different block hash at height 1 — not in our chain
    val unknownPeerHash = ByteString(Array.fill(32)(0xab.toByte))
    unknownPeerHash should not be block1.header.hash

    val (cw, source) =
      blockchainReader.resolveETH69ChainWeight(unknownPeerHash, block1.header.number, isPoWChain = true)
    source shouldBe "CANONICAL_NUMBER"
    cw.totalDifficulty shouldBe block1Weight.totalDifficulty
  }

  it should "return POW_SCALING fallback when neither hash nor number lookup resolves (Tier 3, startup)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val genesisWeight = ChainWeight.zero.increase(genesis.header)
    blockchainWriter.save(genesis, Nil, genesisWeight, saveAsBestBlock = true)

    // Peer is at a height we've never seen (far ahead of our genesis-only DB)
    val unknownHash = ByteString(Array.fill(32)(0xcd.toByte))
    val peerBlockNum = BigInt(5_000_000)

    val (cw, source) = blockchainReader.resolveETH69ChainWeight(unknownHash, peerBlockNum, isPoWChain = true)
    source shouldBe "POW_SCALING"
    // Startup: ourBestNum == 0 → falls to raw latestBlock
    cw.totalDifficulty shouldBe peerBlockNum
  }

  it should "return POS_PROXY block number for post-merge peers (isPoWChain = false)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val genesisWeight = ChainWeight.zero.increase(genesis.header)
    blockchainWriter.save(genesis, Nil, genesisWeight, saveAsBestBlock = true)

    val unknownHash = ByteString(Array.fill(32)(0xef.toByte))
    val peerBlockNum = BigInt(21_000_000)

    val (cw, source) = blockchainReader.resolveETH69ChainWeight(unknownHash, peerBlockNum, isPoWChain = false)
    source shouldBe "POS_PROXY"
    cw.totalDifficulty shouldBe peerBlockNum
  }

}

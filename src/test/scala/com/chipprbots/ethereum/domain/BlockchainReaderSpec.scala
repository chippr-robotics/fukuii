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

  it should "return COLD_START (TD=0) when ourBestNum=0 (DB not yet bootstrapped)" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    blockchainWriter.save(genesis, Nil, ChainWeight.zero.increase(genesis.header), saveAsBestBlock = false)

    val unknownHash = ByteString(Array.fill(32)(0xcd.toByte))
    val peerBlockNum = BigInt(5_000_000)

    val (cw, source) = blockchainReader.resolveETH69ChainWeight(unknownHash, peerBlockNum, isPoWChain = true)
    source shouldBe "COLD_START"
    cw.totalDifficulty shouldBe BigInt(0)
  }

  it should "return POW_SCALING proportional estimate when ourBestNum > 0 but peer is ahead" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val genesisWeight = ChainWeight.zero.increase(genesis.header)
    blockchainWriter.save(genesis, Nil, genesisWeight, saveAsBestBlock = true)

    val block1 = genesis.copy(header = genesis.header.copy(parentHash = genesis.header.hash, number = 1))
    val block1Weight = genesisWeight.increase(block1.header)
    blockchainWriter.save(block1, Nil, block1Weight, saveAsBestBlock = true)

    val unknownHash = ByteString(Array.fill(32)(0xcd.toByte))
    val peerBlockNum = BigInt(1_000_000)

    val (cw, source) = blockchainReader.resolveETH69ChainWeight(unknownHash, peerBlockNum, isPoWChain = true)
    source shouldBe "POW_SCALING"
    cw.totalDifficulty should be > BigInt(0)
    // head.number=1 < 10000 → insufficient-history fallback: rate = headTd / headNumber
    val ourBestTD = block1Weight.totalDifficulty
    val ourBestNum = block1.header.number
    val gap = (peerBlockNum - ourBestNum).max(BigInt(0))
    val rate = ourBestTD / ourBestNum
    cw.totalDifficulty shouldBe ourBestTD + rate * gap
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

  // ETC mainnet post-Spiral realistic anchor values
  private val etcBestTD = BigInt("24244691155597214264244")
  private val etcBestNum = BigInt(24_565_949)

  it should "POW_SCALING estimate be within 0.1% of real TD when peer is within 1000 blocks of our head" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    val gWeight = ChainWeight.zero.increase(genesis.header)
    blockchainWriter.save(genesis, Nil, gWeight, saveAsBestBlock = true)

    val pivotHeader = Fixtures.Blocks.Genesis.header.copy(parentHash = genesis.header.hash, number = etcBestNum)
    val pivotBlock = Block(pivotHeader, Fixtures.Blocks.Genesis.body)
    val pivotWeight = ChainWeight.totalDifficultyOnly(etcBestTD)
    blockchainWriter.save(pivotBlock, Nil, pivotWeight, saveAsBestBlock = true)

    // Peer is 151 blocks ahead — unknown hash, canonical lookup misses (peer ahead of our head)
    val peerBlock = etcBestNum + 151
    val unknownHash = ByteString(Array.fill(32)(0xfe.toByte))

    val (cw, source) = blockchainReader.resolveETH69ChainWeight(unknownHash, peerBlock, isPoWChain = true)
    source shouldBe "POW_SCALING"

    val estimate = cw.totalDifficulty
    val tolerance = etcBestTD / 1000 // 0.1%
    estimate should be >= (etcBestTD - tolerance)
    estimate should be <= (etcBestTD + tolerance)
  }

  it should "return exact real TD (non-inflation) via CANONICAL_NUMBER when peer is at our head height" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    val genesis = Block(Fixtures.Blocks.Genesis.header, Fixtures.Blocks.Genesis.body)
    blockchainWriter.save(genesis, Nil, ChainWeight.zero.increase(genesis.header), saveAsBestBlock = true)

    val pivotHeader = Fixtures.Blocks.Genesis.header.copy(parentHash = genesis.header.hash, number = etcBestNum)
    val pivotBlock = Block(pivotHeader, Fixtures.Blocks.Genesis.body)
    blockchainWriter.save(pivotBlock, Nil, ChainWeight.totalDifficultyOnly(etcBestTD), saveAsBestBlock = true)

    // Peer at our head height with unknown hash:
    // Tier 1 (DB_LOOKUP) misses — hash unknown
    // Tier 2 (CANONICAL_NUMBER) hits — returns exact real TD from our canonical chain
    val unknownHash = ByteString(Array.fill(32)(0xff.toByte))
    val (cw, source) = blockchainReader.resolveETH69ChainWeight(unknownHash, etcBestNum, isPoWChain = true)
    source shouldBe "CANONICAL_NUMBER"
    cw.totalDifficulty shouldBe etcBestTD // exact real TD — no inflation
  }

  it should "COLD_START TD=0 is always less than any real ETC chain TD" taggedAs (
    UnitTest,
    StateTest
  ) in new EphemBlockchainTestSetup {
    // No best block saved → ourBestNum=0 → COLD_START
    val (cw, source) = blockchainReader.resolveETH69ChainWeight(
      ByteString(Array.fill(32)(0xab.toByte)),
      BigInt(24_566_000),
      isPoWChain = true
    )
    source shouldBe "COLD_START"
    cw.totalDifficulty shouldBe BigInt(0)
    cw.totalDifficulty should be < BigInt("1000000000000000000") // << any real PoW TD
  }

}

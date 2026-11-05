package com.chipprbots.ethereum.consensus.pow.validators

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class PoWBlockHeaderValidatorSpec extends AnyFlatSpecLike with Matchers:
  import PoWBlockHeaderValidatorSpec.*

  "PoWBlockHeaderValidator" should "validate Ethash block headers" taggedAs (UnitTest, ConsensusTest, SlowTest) in {
    PoWBlockHeaderValidator.validateEvenMore(validEthashBlockHeader)(using blockchainConfig) shouldBe Right(
      BlockHeaderValid
    )
  }

object PoWBlockHeaderValidatorSpec:
  val blockchainConfig = Config.blockchains.blockchainConfig

  val validEthashBlockHeader: BlockHeader = BlockHeader(
    parentHash = BlockHash(ByteString(Hex.decode("d882d5c210bab4cb7ef0b9f3dc2130cb680959afcd9a8f9bf83ee6f13e2f9da3"))),
    ommersHash = BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
    beneficiary = ByteString(Hex.decode("95f484419881c6e9b6de7fb3f8ad03763bd49a89")),
    stateRoot = TrieRoot(ByteString(Hex.decode("634a2b20c9e02afdda7157afe384306c5acc4fb9c09b45dc0203c0fbb2fed0e6"))),
    transactionsRoot =
      TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    receiptsRoot = TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    logsBloom = BloomFilter(ByteString(Hex.decode("00" * 256))),
    difficulty = Difficulty(BigInt("989772")),
    number = BlockNumber(20),
    gasLimit = GasAmount(131620495),
    gasUsed = GasAmount.Zero,
    unixTimestamp = Timestamp(1486752441),
    extraData = ByteString(Hex.decode("d783010507846765746887676f312e372e33856c696e7578")),
    mixHash = BlockHash(ByteString(Hex.decode("6bc729364c9b682cfa923ba9480367ebdfa2a9bca2a652fe975e8d5958f696dd"))),
    nonce = ByteString(Hex.decode("797a8f3a494f937b"))
  )

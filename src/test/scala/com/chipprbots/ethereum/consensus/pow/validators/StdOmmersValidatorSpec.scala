package com.chipprbots.ethereum.consensus.pow.validators

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.ObjectGenerators
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.OmmerAncestor
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError.*
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.db.storage.pathstate.AncestorNotStored
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.*

class StdOmmersValidatorSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks with ObjectGenerators:

  it should "validate correctly a valid list of ommers" taggedAs (UnitTest, ConsensusTest) in new BlockUtils:
    ommersValidator.validate(ommersBlockParentHash, ommersBlockNumber, ommers, blockchainReader) match
      case Right(_)  => succeed
      case Left(err) => fail(s"Unexpected validation error: $err")

  it should "report failure if the list of ommers is too big" taggedAs (UnitTest, ConsensusTest) in new BlockUtils:
    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(ommer1, ommer2, ommer2),
      blockchainReader
    ) match
      case Left(OmmersLengthError) => succeed
      case Left(err)               => fail(s"Unexpected validation error: $err")
      case Right(_)                => fail("Unexpected validation success")

  it should "report failure if there is an invalid header taggedAs (UnitTest, ConsensusTest) in the list of ommers" in new BlockUtils:
    val invalidOmmer1: BlockHeader = ommer1.copy(number = ommer1.number + 1)

    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(invalidOmmer1, ommer2),
      blockchainReader
    ) match
      case Left(OmmersHeaderError(List(_))) => succeed
      case Left(err)                        => fail(s"Unexpected validation error: $err")
      case Right(_)                         => fail("Unexpected validation success")

  it should "report failure if there is an ommer that was previously used" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(block93.body.uncleNodesList.head, ommer2),
      blockchainReader
    ) match
      case Left(OmmersUsedBeforeError) => succeed
      case Left(err)                   => fail(s"Unexpected validation error: $err")
      case Right(_)                    => fail("Unexpected validation success")

  it should "report failure if there is an ommer which is also an ancestor" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(ommer1, block92.header),
      blockchainReader
    ) match
      case Left(OmmerIsAncestorError) => succeed
      case Left(err)                  => fail(s"Unexpected validation error: $err")
      case Right(_)                   => fail("Unexpected validation success")

  it should "report failure if there is an ommer which that is not parent of an ancestor" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    val getNBlocksBack: GetNBlocksBack =
      (_, n) =>
        ((ommersBlockNumber - n) until ommersBlockNumber).toList
          .flatMap(nb => blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, nb))
          .map(OmmerAncestor.of)

    ommersValidator.validateOmmersAncestors(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(ommer1, ommerInvalidBranch),
      getNBlocksBack
    ) match
      case Left(OmmerParentIsNotAncestorError) => succeed
      case err                                 => fail(err.toString)

  // B-283 (spec 018 C5a part 2). With fewer ancestors than the rule reads, an ommer whose parent is one of the missing
  // blocks reads as `OmmerParentIsNotAncestorError` and an ommer a missing block used reads as new: a valid block
  // refused, or an invalid one accepted. This node not holding its own ancestors is no verdict on the block. This case
  // pinned the verdict before, with no ancestor at all.
  it should "give no verdict, and say it is this node's gap, when it is handed fewer ancestors than it checks against" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    val none: GetNBlocksBack = (_, _) => List.empty
    val missing: AncestorNotStored = the[AncestorNotStored] thrownBy ommersValidator.validateOmmersAncestors(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(ommer1, ommerInvalidBranch),
      none
    )
    (missing.wanted, missing.found) shouldBe (6, 0)

    // five of the six: an ommer whose parent is the missing one would read as no ancestor's child
    val five: GetNBlocksBack = (_, _) => List(block92, block93, block94, block95, block96).map(OmmerAncestor.of)
    val short: AncestorNotStored = the[AncestorNotStored] thrownBy ommersValidator.validateOmmersAncestors(
      ommersBlockParentHash,
      ommersBlockNumber,
      ommers,
      five
    )
    (short.wanted, short.found) shouldBe (6, 5)

  it should "check a block near the genesis against the ancestors there are" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    // block 3 has three ancestors, and that is all of them
    var asked = 0
    val three: GetNBlocksBack = (_, n) =>
      asked = n
      List(block94, block95, block96).map(OmmerAncestor.of)
    // judged, whatever the verdict on these ommers: three ancestors are not a gap
    noException should be thrownBy ommersValidator.validateOmmersAncestors(
      ommersBlockParentHash,
      BigInt(3),
      ommers,
      three
    )
    asked shouldBe 3

  it should "end, with no verdict, when the body of an ancestor is not stored" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    // as below a pivot the node synced from: the header is there and the body is not. The walk back used to go on for
    // ever past it.
    storagesInstance.storages.blockBodiesStorage.remove(block93.header.hash.value).commit()
    val missing: AncestorNotStored = the[AncestorNotStored] thrownBy
      ommersValidator.validate(ommersBlockParentHash, ommersBlockNumber, ommers, blockchainReader)
    // the headers are all there; of the five bodies read, block 93's, which has ommers, is not stored (B-302)
    (missing.wanted, missing.found) shouldBe (5, 4)
    missing.getMessage should include("the body of block 93")
    // a block with no ommers reads no ancestor, and is judged as it was
    ommersValidator.validate(ommersBlockParentHash, ommersBlockNumber, Nil, blockchainReader) shouldBe
      Right(OmmersValid)

  // B-302 (forge W3, core-geth VerifyUncles): the body of an ancestor is read only where its header names ommers, and
  // only for the five nearest; the headers of all six are still read.
  it should "judge a block whose ancestors' bodies are missing where they have no ommers" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    Seq(block90, block91, block92, block95, block96).foreach(block =>
      storagesInstance.storages.blockBodiesStorage.remove(block.header.hash.value).commit()
    )
    ommersValidator.validate(ommersBlockParentHash, ommersBlockNumber, ommers, blockchainReader) shouldBe
      Right(OmmersValid)
    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(block93.body.uncleNodesList.head, ommer2),
      blockchainReader
    ) shouldBe Left(OmmersUsedBeforeError)

  it should "read no body of the sixth ancestor, which can have used no ommer this block could have" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    // the oldest of the six named as having ommers, and its body not held
    val sixth: OmmerAncestor = OmmerAncestor(block91.header.copy(ommersHash = block93.header.ommersHash), None)
    val ancestors: GetNBlocksBack =
      (_, _) => sixth +: List(block92, block93, block94, block95, block96).map(OmmerAncestor.of)
    val headerOf: ByteString => Option[BlockHeader] = hash => blockchainReader.getBlockHeaderByHash(BlockHash(hash))
    ommersValidator.validate(ommersBlockParentHash, ommersBlockNumber, ommers, headerOf, ancestors) shouldBe
      Right(OmmersValid)

  // The mandatory differential (B-302): the rule as it was, every body of the six read, and the rule as it is give the
  // same verdict on every set of one or two ommers drawn from the fixture, its ancestors, the ommers they used and an
  // ommer of the sixth that only the old rule reads.
  it should "give the verdicts that a read of all six bodies gives" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new BlockUtils:
    val allSix: StdOmmersValidator = new StdOmmersValidator(PoWBlockHeaderValidator):
      override protected def usedBeforeDepth: Int = OmmerGenerationLimit
    // block 91 as if it had used block 90 as an ommer: a valid chain's sixth ancestor used ommers no lower than its own
    // sixth ancestor, and none this block could name
    val sixthWithOmmers: OmmerAncestor =
      OmmerAncestor(block91.header.copy(ommersHash = block93.header.ommersHash), Some(Seq(block90.header)))
    val whole: List[OmmerAncestor] =
      sixthWithOmmers +: List(block92, block93, block94, block95, block96).map(OmmerAncestor.of)
    val headersFirst: List[OmmerAncestor] = whole.updated(0, sixthWithOmmers.copy(ommers = None))
    val pool: Seq[BlockHeader] =
      Seq(ommer1, ommer2, ommerInvalidBranch) ++ Seq(block90, block91, block92, block93, block94, block95, block96)
        .map(_.header) ++ block93.body.uncleNodesList ++ block94.body.uncleNodesList
    val sets: Seq[Seq[BlockHeader]] = pool.map(Seq(_)) ++ pool.combinations(2).toSeq
    sets.size should be > 50
    val headerOf: ByteString => Option[BlockHeader] = hash => blockchainReader.getBlockHeaderByHash(BlockHash(hash))
    sets.foreach { set =>
      withClue(s"ommers ${set.map(_.number).mkString(", ")}: ") {
        ommersValidator.validate(
          ommersBlockParentHash,
          ommersBlockNumber,
          set,
          headerOf,
          (_, _) => headersFirst
        ) shouldBe
          allSix.validate(ommersBlockParentHash, ommersBlockNumber, set, headerOf, (_, _) => whole)
      }
    }

  it should "report failure if there is an ommer that is too old" taggedAs (UnitTest, ConsensusTest) in new BlockUtils:
    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(ommer1, block90.header),
      blockchainReader
    ) match
      case Left(OmmerParentIsNotAncestorError) => succeed
      case err                                 => fail(err.toString)

  it should "report failure if there is a duplicated ommer taggedAs (UnitTest, ConsensusTest) in the ommers list" in new BlockUtils:
    ommersValidator.validate(
      ommersBlockParentHash,
      ommersBlockNumber,
      Seq(ommer1, ommer1),
      blockchainReader
    ) match
      case Left(OmmersDuplicatedError) => succeed
      case Left(err)                   => fail(s"Unexpected validation error: $err")
      case Right(_)                    => fail("Unexpected validation success")

  // scalastyle:off magic.number
  trait BlockUtils extends EphemBlockchainTestSetup:

    val ommersValidator = new StdOmmersValidator(PoWBlockHeaderValidator)

    val ommerInvalidBranch: BlockHeader = BlockHeader(
      parentHash =
        BlockHash(ByteString(Hex.decode("fd07e36cfaf327801e5696134b12345f6a89fb1e8f017f2411a29d0ae810ab8b"))),
      ommersHash =
        BlockHash(ByteString(Hex.decode("7766c4251396a6833ccbe4be86fbda3a200dccbe6a15d80ae3de5378b1540e04"))),
      beneficiary = ByteString(Hex.decode("28921e4e2c9d84f4c0f0c0ceb991f45751a0fe93")),
      stateRoot = TrieRoot(ByteString(Hex.decode("e766f9c51536e9038849e5eb0a143c3b3409b5385098359837cbf3324ad22328"))),
      transactionsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(
        ByteString(
          Hex.decode(
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
          )
        )
      ),
      difficulty = Difficulty(BigInt("17864037202")),
      number = BlockNumber(94),
      gasLimit = GasAmount(5000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1438270431),
      extraData = ByteString(Hex.decode("476574682f76312e302e302f6c696e75782f676f312e342e32")),
      mixHash = BlockHash(ByteString(Hex.decode("8c1ed8037984be0fe9065f8f8663c3baeeb6436868ac6915dd3c2cd5fd46fa96"))),
      nonce = ByteString(Hex.decode("40b0b2c0b6d14706"))
    )

    // Ommers from block 0xe9fb121a7ee5cb03b33adbf59e95321a2453f09db98068e1f31f0da79860c50c (of number 97)
    val ommer1: BlockHeader = BlockHeader(
      parentHash =
        BlockHash(ByteString(Hex.decode("fd07e36cfaf327801e5696134b36678f6a89fb1e8f017f2411a29d0ae810ab8b"))),
      ommersHash =
        BlockHash(ByteString(Hex.decode("7766c4251396a6833ccbe4be86fbda3a200dccbe6a15d80ae3de5378b1540e04"))),
      beneficiary = ByteString(Hex.decode("1b7047b4338acf65be94c1a3e8c5c9338ad7d67c")),
      stateRoot = TrieRoot(ByteString(Hex.decode("52ce0ff43d7df2cf39f8cb8832f94d2280ebe856d84d8feb7b2281d3c5cfb990"))),
      transactionsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(
        ByteString(
          Hex.decode(
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
          )
        )
      ),
      difficulty = Difficulty(BigInt("17864037202")),
      number = BlockNumber(94),
      gasLimit = GasAmount(5000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1438270431),
      extraData = ByteString(Hex.decode("426974636f696e2069732054484520426c6f636b636861696e2e")),
      mixHash = BlockHash(ByteString(Hex.decode("c6d695926546d3d679199303a6d1fc983fe3f09f44396619a24c4271830a7b95"))),
      nonce = ByteString(Hex.decode("62bc3dca012c1b27"))
    )
    val ommer2: BlockHeader = BlockHeader(
      parentHash =
        BlockHash(ByteString(Hex.decode("fd07e36cfaf327801e5696134b36678f6a89fb1e8f017f2411a29d0ae810ab8b"))),
      ommersHash =
        BlockHash(ByteString(Hex.decode("7766c4251396a6833ccbe4be86fbda3a200dccbe6a15d80ae3de5378b1540e04"))),
      beneficiary = ByteString(Hex.decode("28921e4e2c9d84f4c0f0c0ceb991f45751a0fe93")),
      stateRoot = TrieRoot(ByteString(Hex.decode("e766f9c51536e9038849e5eb0a143c3b3409b5385098359837cbf3324ad22328"))),
      transactionsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      receiptsRoot =
        TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
      logsBloom = BloomFilter(
        ByteString(
          Hex.decode(
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
          )
        )
      ),
      difficulty = Difficulty(BigInt("17864037202")),
      number = BlockNumber(94),
      gasLimit = GasAmount(5000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(1438270431),
      extraData = ByteString(Hex.decode("476574682f76312e302e302f6c696e75782f676f312e342e32")),
      mixHash = BlockHash(ByteString(Hex.decode("8c1ed8037984be0fe9065f8f8663c3baeeb6436868ac6915dd3c2cd5fd46fa96"))),
      nonce = ByteString(Hex.decode("40b0b2c0b6d14706"))
    )
    val ommers: Seq[BlockHeader] = Seq[BlockHeader](ommer1, ommer2)
    val ommersBlockNumber: BigInt = 97

    val block90: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("6da5970538eba5db93162e219182fca7e093cfe4fbd8dd0b82789adb25dcbb42"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
        beneficiary = ByteString(Hex.decode("d7e30ae310c1d1800f5b641baa7af95b2e1fd98c")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("da6f0baf5f17d201b3d711299091ecee68cf56469b1e09704713934de1c74517"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17829189056")),
        number = BlockNumber(90),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270422),
        extraData = ByteString(Hex.decode("476574682f6b6c6f737572652f76312e302e302d66633739643332642f6c696e")),
        mixHash = BlockHash(ByteString(Hex.decode("f28f1b53323dc8a6a4fe73495e71e81947366b68d8a217daa4e349b0c939401f"))),
        nonce = ByteString(Hex.decode("f91fd0cc60d6948c"))
      ),
      BlockBody(Seq.empty, Seq.empty)
    )
    val block91: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("69d2798993659c0d864d6f2824440b091368c147efc6c33410ef181036fc2bf1"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
        beneficiary = ByteString(Hex.decode("28921e4e2c9d84f4c0f0c0ceb991f45751a0fe93")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("0d528286094ba4781aed7a19a9c890e07651be9b73e999585c926810ec888198"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17837894714")),
        number = BlockNumber(91),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270425),
        extraData = ByteString(Hex.decode("476574682f76312e302e302f6c696e75782f676f312e342e32")),
        mixHash = BlockHash(ByteString(Hex.decode("eadd3fbbb336d073a33e4fb9faa97be8b0e904aeb8b65eeae243e1e35d86e6c3"))),
        nonce = ByteString(Hex.decode("20fdc1504ec955a0"))
      ),
      BlockBody(Seq.empty, Seq.empty)
    )
    val block92: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("d691ed6cf02375620d9cca9052bcf38a4a23f5c77058581c8bb54a06e2eb6ed9"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
        beneficiary = ByteString(Hex.decode("bb7b8287f3f0a933474a79eae42cbca977791171")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("a7be51c65294e0f504e781ce19ae192998fb919805cc0822d5142500204b6917"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17846604623")),
        number = BlockNumber(92),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270427),
        extraData = ByteString(Hex.decode("476574682f4c5649562f76312e302e302f6c696e75782f676f312e342e32")),
        mixHash = BlockHash(ByteString(Hex.decode("afd3d088f65607ad7404837db220add9cf54ca0f4fb107e3f6bee9d0aca18e7f"))),
        nonce = ByteString(Hex.decode("dd7e335a44c7e9c9"))
      ),
      BlockBody(Seq.empty, Seq.empty)
    )
    val block93: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("c86dcbd8ba3cd836bd9c00d9dababe81ed5a42310ade70a77700d42b5ff8b64c"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("445ccaa7ee03cf387e4835482288f6b08dc351eef4ecc94b3ed8de56afd298a6"))),
        beneficiary = ByteString(Hex.decode("bb7b8287f3f0a933474a79eae42cbca977791171")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("38f5e900f8dee62e0fdd52818dbedfe0869fa95a876357dd94669f56f25e980b"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17855318785")),
        number = BlockNumber(93),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270430),
        extraData = ByteString(Hex.decode("476574682f4c5649562f76312e302e302f6c696e75782f676f312e342e32")),
        mixHash = BlockHash(ByteString(Hex.decode("631f88fd52a9a7ee3cc3a08945eb2ab8f4da37d7cf96592dac9af514f28365bc"))),
        nonce = ByteString(Hex.decode("cdc4e60cbd67b791"))
      ),
      BlockBody(
        Seq.empty,
        Seq[BlockHeader](
          BlockHeader(
            parentHash =
              BlockHash(ByteString(Hex.decode("69d2798993659c0d864d6f2824440b091368c147efc6c33410ef181036fc2bf1"))),
            ommersHash =
              BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
            beneficiary = ByteString(Hex.decode("bb7b8287f3f0a933474a79eae42cbca977791171")),
            stateRoot =
              TrieRoot(ByteString(Hex.decode("e0ae53ea50eb6fb40b764b748ccd6d4f6184a6f3c474899c74df102310c37d6a"))),
            transactionsRoot =
              TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
            receiptsRoot =
              TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
            logsBloom = BloomFilter(
              ByteString(
                Hex.decode(
                  "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
                )
              )
            ),
            difficulty = Difficulty(BigInt("17837894714")),
            number = BlockNumber(91),
            gasLimit = GasAmount(5000),
            gasUsed = GasAmount.Zero,
            unixTimestamp = Timestamp(1438270425),
            extraData = ByteString(Hex.decode("476574682f4c5649562f76312e302e302f6c696e75782f676f312e342e32")),
            mixHash =
              BlockHash(ByteString(Hex.decode("7e0b76b9b1698947617c1ea7cb7c36f47aefc2c4095c6df90aa6e2b3da6e49ac"))),
            nonce = ByteString(Hex.decode("edcbf5efad298bb3"))
          )
        )
      )
    )
    val block94: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("fd07e36cfaf327801e5696134b36678f6a89fb1e8f017f2411a29d0ae810ab8b"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("7766c4251396a6833ccbe4be86fbda3a200dccbe6a15d80ae3de5378b1540e04"))),
        beneficiary = ByteString(Hex.decode("d7e30ae310c1d1800f5b641baa7af95b2e1fd98c")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("fcd09860439502fcd9e3f208ed043bfeffc985b3a289f69e79f9b81f32f9010e"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17864037202")),
        number = BlockNumber(94),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270431),
        extraData = ByteString(Hex.decode("476574682f6b6c6f737572652f76312e302e302d66633739643332642f6c696e")),
        mixHash = BlockHash(ByteString(Hex.decode("33fe497dae796c62f261d10304786b0c63cd59030a0f96c811a88e90e7d02b0f"))),
        nonce = ByteString(Hex.decode("36da15d93277d947"))
      ),
      BlockBody(
        Seq.empty,
        Seq[BlockHeader](
          BlockHeader(
            parentHash =
              BlockHash(ByteString(Hex.decode("6da5970538eba5db93162e219182fca7e093cfe4fbd8dd0b82789adb25dcbb42"))),
            ommersHash =
              BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
            beneficiary = ByteString(Hex.decode("bb7b8287f3f0a933474a79eae42cbca977791171")),
            stateRoot =
              TrieRoot(ByteString(Hex.decode("57a99a5a9d93104df18d3cdba5d1c84e1e5c22527c0c375e59e38944311bfe14"))),
            transactionsRoot =
              TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
            receiptsRoot =
              TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
            logsBloom = BloomFilter(
              ByteString(
                Hex.decode(
                  "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
                )
              )
            ),
            difficulty = Difficulty(BigInt("17829189056")),
            number = BlockNumber(90),
            gasLimit = GasAmount(5000),
            gasUsed = GasAmount.Zero,
            unixTimestamp = Timestamp(1438270421),
            extraData = ByteString(Hex.decode("476574682f4c5649562f76312e302e302f6c696e75782f676f312e342e32")),
            mixHash =
              BlockHash(ByteString(Hex.decode("0688a1217172b2f81b168a25459a2cad5cc2337aab1d17b30c7d803c565bf0b3"))),
            nonce = ByteString(Hex.decode("efc94c53e5ad946a"))
          )
        )
      )
    )
    val block95: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("665586bdd5aefc860f8ff2d186617544d5aa6e5ae7203bf54b26f310be372e91"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
        beneficiary = ByteString(Hex.decode("bb7b8287f3f0a933474a79eae42cbca977791171")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("1b39e9e77df97fd50ad637eea384839ba6aec610015450518936c9fd8e20efed"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17872759876")),
        number = BlockNumber(95),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270433),
        extraData = ByteString(Hex.decode("476574682f4c5649562f76312e302e302f6c696e75782f676f312e342e32")),
        mixHash = BlockHash(ByteString(Hex.decode("d1ed067b52da47010ab970117677233d9da738b22fe955899f9ed2e4360fc924"))),
        nonce = ByteString(Hex.decode("75d5ff831690242a"))
      ),
      BlockBody(Seq.empty, Seq.empty)
    )
    val block96: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("c0f772db658b2279a736f232e75d98629a53d36086e34a18f9fe65a4650d50a7"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
        beneficiary = ByteString(Hex.decode("1b7047b4338acf65be94c1a3e8c5c9338ad7d67c")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("563401469b5c16712b251e6042620838dcf4b88b6c59969a86faa94c7b4402d8"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17881486809")),
        number = BlockNumber(96),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270435),
        extraData = ByteString(Hex.decode("426974636f696e2069732054484520426c6f636b636861696e2e")),
        mixHash = BlockHash(ByteString(Hex.decode("b4f571ecf4dcebe75260f4929a01de8b2c19c161bea20dda91bfb92298f7262f"))),
        nonce = ByteString(Hex.decode("1dbc948cb756c2b9"))
      ),
      BlockBody(Seq.empty, Seq.empty)
    )

    val block89: Block = Block(
      BlockHeader(
        parentHash =
          BlockHash(ByteString(Hex.decode("ba39b4ee19e5db0f5a85c344aa2bd2e7b0cdb2404b7d5c0e6cdc08c83f85083e"))),
        ommersHash =
          BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
        beneficiary = ByteString(Hex.decode("1b7047b4338acf65be94c1a3e8c5c9338ad7d67c")),
        stateRoot =
          TrieRoot(ByteString(Hex.decode("17e5e23d006df68fd13c0f3ce71a5e510abeee28b88a4e207fce171fbc3ef60d"))),
        transactionsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        receiptsRoot =
          TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
        logsBloom = BloomFilter(
          ByteString(
            Hex.decode(
              "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
            )
          )
        ),
        difficulty = Difficulty(BigInt("17820487647")),
        number = BlockNumber(89),
        gasLimit = GasAmount(5000),
        gasUsed = GasAmount.Zero,
        unixTimestamp = Timestamp(1438270418),
        extraData = ByteString(Hex.decode("426974636f696e2069732054484520426c6f636b636861696e2e")),
        mixHash = BlockHash(ByteString(Hex.decode("d7c06ea893693857f10675f02502ce63d74fe80b3bce9749b507888f3acd0b5d"))),
        nonce = ByteString(Hex.decode("1d48377931a68d12"))
      ),
      BlockBody(Seq.empty, Seq.empty)
    )

    val ommersBlockParentHash: ByteString = block96.header.hash.value

    blockchainWriter
      .storeBlock(block89)
      .and(blockchainWriter.storeBlock(block90))
      .and(blockchainWriter.storeBlock(block91))
      .and(blockchainWriter.storeBlock(block92))
      .and(blockchainWriter.storeBlock(block93))
      .and(blockchainWriter.storeBlock(block94))
      .and(blockchainWriter.storeBlock(block95))
      .and(blockchainWriter.storeBlock(block96))
      .commit()
    blockchainWriter.saveBestKnownBlocks(block96.hash, block96.number.value)

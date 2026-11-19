package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger
import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
import com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed
import com.chipprbots.ethereum.consensus.pow.validators.PoWBlockHeaderValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderDifficultyError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderParentNotFoundError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError.HeaderPoWError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.ForkBlockNumbers

/** B-391 (the user's decision of 2026-11-19): a heavier proof-of-work branch that stands on this node's own block, by
  * the hash index, whose header is not stored. The escalation's feed judges it with the header validation of an import
  * ([[BlockValidation.firstInvalidHeaderOfNotJudged]]), real here and not stubbed: the first header by its Ethash seal
  * alone, its parent rules not checkable without its parent, and every header above it against its parent. The branch
  * is then counted as a fault of this node, and escalates as `LocalFault` (B-383). An import still refuses a header
  * whose parent it has not.
  */
class ConsensusAdapterForkHeaderHoleSpec extends AnyFlatSpec with Matchers:

  // Two consecutive proof-of-work headers, 19 and 20, with real Ethash seals (as EthashBlockHeaderValidatorSpec)
  private val header19: BlockHeader = BlockHeader(
    parentHash = BlockHash(ByteString(Hex.decode("677a5fb51d52321b03552e3c667f602cc489d15fc1d7824445aee6d94a9db2e7"))),
    ommersHash = BlockHash(ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))),
    beneficiary = ByteString(Hex.decode("95f484419881c6e9b6de7fb3f8ad03763bd49a89")),
    stateRoot = TrieRoot(ByteString(Hex.decode("cddeeb071e2f69ad765406fb7c96c0cd42ddfc6ec54535822b564906f9e38e44"))),
    transactionsRoot =
      TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    receiptsRoot = TrieRoot(ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))),
    logsBloom = BloomFilter(ByteString(Hex.decode("00" * 256))),
    difficulty = Difficulty(BigInt("989289")),
    number = BlockNumber(19),
    gasLimit = GasAmount(131749155),
    gasUsed = GasAmount.Zero,
    unixTimestamp = Timestamp(1486752440),
    extraData = ByteString(Hex.decode("d783010507846765746887676f312e372e33856c696e7578")),
    mixHash = BlockHash(ByteString(Hex.decode("7f9ac1ddeafff0f926ed9887b8cf7d50c3f919d902e618b957022c46c8b404a6"))),
    nonce = ByteString(Hex.decode("3fc7bc671f7cee70"))
  )

  private val header20: BlockHeader = BlockHeader(
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

  // the block both stand on: this node's block 18, whose header is not stored
  private val forkHash: BlockHash = header19.parentHash

  // a proof-of-work chain with the rules these headers were mined by (ETC's schedule: Frontier at block 20)
  private given BlockchainConfig = Config.blockchains.blockchainConfig.copy(
    forkBlockNumbers = ForkBlockNumbers.Empty.copy(
      frontierBlockNumber = 0,
      homesteadBlockNumber = 1150000,
      difficultyBombPauseBlockNumber = 3000000,
      difficultyBombContinueBlockNumber = 5000000,
      difficultyBombRemovalBlockNumber = 5900000,
      byzantiumBlockNumber = 4370000,
      constantinopleBlockNumber = 7280000,
      istanbulBlockNumber = 9069000,
      muirGlacierBlockNumber = 9200000,
      eip106BlockNumber = 0
    ),
    daoForkConfig = None,
    terminalTotalDifficulty = None
  )

  private class Rig(indexNamesTheFork: Boolean = true) extends EphemBlockchainTestSetup:
    override lazy val validators: Mocks.MockValidatorsAlwaysSucceed = new Mocks.MockValidatorsAlwaysSucceed:
      override val blockHeaderValidator: BlockHeaderValidator = PoWBlockHeaderValidator
    val validation = new BlockValidation(mining, blockchainReader, blockQueue)

    // this node's block 18 is in the hash index and has its weight stored; its header is not stored
    if indexNamesTheFork then storagesInstance.storages.blockNumberMappingStorage.put(18, forkHash.value).commit()
    blockchainWriter.storeChainWeight(forkHash, ChainWeight.totalDifficultyOnly(1000)).commit()

    val escalated: mutable.ArrayBuffer[EscalationKind] = mutable.ArrayBuffer.empty
    val left: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
    val messAsked: mutable.ArrayBuffer[(Long, BigInt, BigInt)] = mutable.ArrayBuffer.empty
    val messHead: BlockHeader = header20.copy(number = BlockNumber(30), unixTimestamp = Timestamp(1486752500))
    val feed = new PowEscalationFeed(
      // one block of growth escalates: the branch is told at tip 19, then at tip 20
      new EscalationWatch(new EscalationTrigger(1), () => 0L, escalated += _, (_, _) => ()),
      blockchainReader.getChainWeightByHash,
      // the head is lighter than either branch
      () => Some(ChainWeight.totalDifficultyOnly(1001)),
      hash => blockchainReader.getBlockHeaderByHash(BlockHash(hash)),
      header => blockchainReader.getCanonicalHashByNumber(header.number.value).contains(header.hash),
      // MESS is in force and would refuse anything it were asked of
      Some(
        PowEscalationFeed.Mess(
          _ => true,
          () => Some(messHead),
          (s, l, p) =>
            messAsked += ((s, l, p)); true
        )
      ),
      left += _,
      blockchainReader.getCanonicalHashByNumber
    )
    val adapter =
      new ConsensusAdapter(null, blockchainReader, blockQueue, validation, IORuntime.global, None, Some(feed))

    val unavailable: StateFault = new StateUnavailable("block 18, the block the branch leaves the chain at")

  "A heavier branch over this node's own missing fork header" should "be validated for real, its first header by its seal, and escalate as LocalFault" taggedAs UnitTest in {
    val rig = new Rig()
    // the real validation the feed is given: the first header by its seal, the second against the first
    rig.validation.firstInvalidHeaderOfNotJudged(List(header19, header20)) shouldBe None
    // told by its headers (out of reach), then grown by one block and told by its blocks (a hole no repair fills)
    rig.adapter.branchOutOfReach(List(header19), rig.unavailable)
    rig.escalated shouldBe empty
    rig.feed.refusedWeight.map(_.totalDifficulty.value) shouldBe Some(BigInt(1000) + 989289)
    rig.adapter.holeNotRepaired(
      List(Block(header19, BlockBody.empty), Block(header20, BlockBody.empty)),
      new MissingNodeException(ByteString(Array.fill[Byte](32)(9)))
    )
    rig.escalated.toList shouldBe List(EscalationKind.LocalFault)
    // MESS was not asked: it has nothing to weigh from a block whose header is not stored
    rig.messAsked shouldBe empty
    rig.left.exists(_.contains("is not valid")) shouldBe false
  }

  it should "not be counted where the hash index does not name the missing fork as this node's" taggedAs UnitTest in {
    val rig = new Rig(indexNamesTheFork = false)
    rig.validation.firstInvalidHeaderOfNotJudged(List(header19, header20)) shouldBe
      Some(header19 -> HeaderParentNotFoundError.toString)
    rig.adapter.branchOutOfReach(List(header19), rig.unavailable)
    rig.adapter.branchOutOfReach(List(header19, header20), rig.unavailable)
    rig.escalated shouldBe empty
    rig.feed.refusedWeight shouldBe None
    rig.left.last should include(HeaderParentNotFoundError.toString)
  }

  it should "not be counted where the first header's seal is not valid" taggedAs UnitTest in {
    val rig = new Rig()
    val unsealed = header19.copy(nonce = ByteString(Hex.decode("0b80f001ae0c017f")))
    rig.validation.firstInvalidHeaderOfNotJudged(List(unsealed)) shouldBe Some(unsealed -> HeaderPoWError.toString)
    rig.adapter.branchOutOfReach(List(unsealed), rig.unavailable)
    rig.escalated shouldBe empty
    rig.feed.refusedWeight shouldBe None
    rig.left.last should include(HeaderPoWError.toString)
  }

  it should "not be counted where a header above the first breaks its parent rules" taggedAs UnitTest in {
    val rig = new Rig()
    // a difficulty its parent does not give: the seal of no header is checked
    val heavier = header20.copy(difficulty = Difficulty(BigInt("989773")))
    rig.validation.firstInvalidHeaderOfNotJudged(List(header19, heavier)) shouldBe
      Some(heavier -> HeaderDifficultyError.toString)
    rig.adapter.branchOutOfReach(List(header19), rig.unavailable)
    rig.adapter.branchOutOfReach(List(header19, heavier), rig.unavailable)
    rig.escalated shouldBe empty
    rig.left.last should include(HeaderDifficultyError.toString)
  }

  "An import" should "still refuse a header whose parent it has not, where the index names the parent" taggedAs UnitTest in {
    val rig = new Rig()
    // the validation of an import, and of a fork choice below the floor: not relaxed
    rig.validation.firstInvalidHeaderOf(List(header19, header20)) shouldBe
      Some(header19 -> HeaderParentNotFoundError.toString)
    rig.adapter.firstInvalidHeaderOf(List(header19, header20)) shouldBe
      Some(header19 -> HeaderParentNotFoundError.toString)
    rig.validation.validateBlockBeforeExecution(Block(header19, BlockBody.empty)).left.map(_.toString) match
      case Left(why) => why should include("UNKNOWN_PARENT")
      case Right(_)  => fail("an import took a header whose parent it has not")
    // the second header, with its parent in the branch, passes as an import validates it
    rig.validation.firstInvalidHeaderOfNotJudged(List(header19, header20)) shouldBe None
  }

package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.matchers.should.*
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** execution-spec-tests v5.4.0 (`fixtures_stable.tar.gz`, `blockchain_tests/`) vectors that hive's consume-engine /
  * consume-rlp full pass on 33b730e75 failed, replayed through the path `ChainImporter` takes for hive's chain.rlp:
  * genesis via [[GenesisDataLoader]] (the `alloc` is the fixture's `pre`), then per block
  * `validateBlockBeforeExecution` → execute → `validateBlockAfterExecution` → `validateRequestsHash`, each block
  * accepted or rejected exactly as the fixture's `expectException` says, ending on the fixture's `lastblockhash`.
  *
  * The JSON under `src/test/resources/eest-regression/` is the fixture verbatim, trimmed to the fields replayed here
  * (`network`, `pre`, `genesisRLP`, `blocks[].rlp`, `blocks[].expectException`, `lastblockhash`); block RLP, signatures
  * and the expected head hash are byte-for-byte the release's.
  */
class EestRegressionVectorsSpec extends AnyWordSpec with Matchers:

  /** file → what it pins. */
  private val vectorFiles: Seq[(String, String)] = Seq(
    "genesis-shared-storage-nodes.json" -> "genesis storage tries that share a node both keep it",
    "eip7685-requests.json" -> "EIP-6110 deposit-log layout, EIP-7002/7251 system-call failure, EIP-7685 requestsHash",
    "eip7702-authorizations.json" -> "EIP-7702 per-tuple validity, authority warming, refunds and delegation access costs",
    "eip7702-failed-tx-rollback.json" -> "a failed Type-4 transaction keeps its authorizations and their refund",
    "mcopy-huge-offsets.json" -> "MCOPY memory expansion with offsets near 2^256"
  )

  private def hx(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))
  private def bi(s: String): BigInt = if s.stripPrefix("0x").isEmpty then 0 else BigInt(s.stripPrefix("0x"), 16)
  private def str(v: JValue): String = v.values.toString

  private class Env extends EphemBlockchainTestSetup:
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

  /** hive's post-merge ETH shape: every block fork at 0, ETC-only forks off, TTD 0, the fixture's timestamp forks. */
  private def configFor(base: BlockchainConfig, network: String): BlockchainConfig =
    val paris = base
      .copy(
        networkType = NetworkType.ETH,
        chainId = ChainId(1),
        forkTimestamps = ForkTimestamps(),
        terminalTotalDifficulty = Some(BigInt(0))
      )
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip106BlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          muirGlacierBlockNumber = 0,
          olympiaBlockNumber = 0,
          atlantisBlockNumber = BigInt(Long.MaxValue),
          aghartaBlockNumber = BigInt(Long.MaxValue),
          phoenixBlockNumber = BigInt(Long.MaxValue),
          magnetoBlockNumber = BigInt(Long.MaxValue),
          mystiqueBlockNumber = BigInt(Long.MaxValue),
          spiralBlockNumber = BigInt(Long.MaxValue),
          ecip1099BlockNumber = BigInt(Long.MaxValue)
        )
      )
    def at(shanghai: Long, cancun: Long, prague: Option[Long]) =
      paris.copy(forkTimestamps = ForkTimestamps(Some(shanghai), Some(cancun), prague))
    network match
      case "Cancun"                    => at(0L, 0L, None)
      case "ShanghaiToCancunAtTime15k" => at(0L, 15000L, None)
      case "Prague"                    => at(0L, 0L, Some(0L))
      case other                       => fail(s"network $other not modelled")

  /** Replays one fixture; returns the list of divergences (empty = the fixture passes). */
  private def replay(t: JValue): Seq[String] =
    val env = new Env
    import env.{blockQueue, blockchain, blockchainReader, blockchainWriter, mining, storagesInstance}
    given BlockchainConfig = configFor(env.blockchainConfig, str(t \ "network"))

    val genesis = hx(str(t \ "genesisRLP")).toBlock
    val JObject(pre) = t \ "pre": @unchecked
    val alloc = pre.map { case (address, acc) =>
      val code = ByteString(hx(str(acc \ "code")))
      val JObject(storage) = acc \ "storage": @unchecked
      address.stripPrefix("0x") -> GenesisAccount(
        precompiled = None,
        balance = UInt256(bi(str(acc \ "balance"))),
        code = Option.when(code.nonEmpty)(code),
        nonce = Some(UInt256(bi(str(acc \ "nonce")))),
        storage = Option.when(storage.nonEmpty)(storage.map { case (k, v) =>
          UInt256(bi(k)) -> UInt256(bi(str(v)))
        }.toMap)
      )
    }.toMap
    val gh = genesis.header
    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      storagesInstance.storages.stateStorage
    ).loadGenesisData(
      GenesisData(
        nonce = gh.nonce,
        mixHash = Some(gh.mixHash.value),
        difficulty = gh.difficulty.value.toString,
        extraData = gh.extraData,
        gasLimit = gh.gasLimit.value.toString,
        coinbase = gh.beneficiary,
        timestamp = gh.unixTimestamp.toString,
        alloc = alloc,
        baseFeePerGas = gh.baseFee.map(_.toString),
        excessBlobGas = gh.excessBlobGas.map(_.toString),
        blobGasUsed = gh.blobGasUsed.map(_.toString)
      )
    ).get
    blockchainReader.getBlockHeaderByNumber(0).map(_.stateRoot) shouldBe Some(gh.stateRoot)
    // The loader's header omits fields the fixture's genesis carries (e.g. parentBeaconBlockRoot); the STATE is what
    // is under test, so the fixture's own genesis header becomes the chain's parent.
    blockchainWriter.save(genesis, Nil, ChainWeight.zero.increase(gh), saveAsBestBlock = true)

    val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
    val blockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )
    val JArray(blocks) = t \ "blocks": @unchecked
    val blockDivergences = blocks.zipWithIndex.flatMap { case (b, i) =>
      val expectException = (b \ "expectException").toOption.map(str)
      val block = hx(str(b \ "rlp")).toBlock
      val outcome: Either[String, Seq[Receipt]] =
        blockValidation.validateBlockBeforeExecution(block).left.map(_.toString).flatMap { _ =>
          blockExecution.executeBlockNoValidationWithRequests(block).left.map(_.describe).flatMap {
            case (receipts, gasUsed, root, requests) =>
              blockValidation
                .validateBlockAfterExecution(block, root, receipts, gasUsed)
                .left
                .map(_.toString)
                .flatMap(_ => blockExecution.validateRequestsHash(block, requests).left.map(_.describe))
                .map(_ => receipts)
          }
        }
      (outcome, expectException) match
        case (Right(receipts), None) =>
          val w = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
          blockchainWriter.save(block, receipts, w.increase(block.header), saveAsBestBlock = true)
          Nil
        case (Left(_), Some(_))   => Nil
        case (Right(_), Some(ex)) => Seq(s"block[$i] accepted, fixture expects $ex")
        case (Left(err), None)    => Seq(s"block[$i] rejected: $err")
    }
    val head = blockchainReader.getBestBlock.map(b => "0x" + Hex.toHexString(b.header.hash.value.toArray))
    val expectedHead = str(t \ "lastblockhash")
    blockDivergences ++ (if head.contains(expectedHead) then Nil else Seq(s"head $head != $expectedHead"))

  "execution-spec-tests v5.4.0 regression vectors" should {
    vectorFiles.foreach { case (file, pins) =>
      s"replay $file byte-for-byte ($pins)" taggedAs (UnitTest, VMTest, ConsensusTest) in {
        val src = scala.io.Source.fromResource(s"eest-regression/$file")
        val json =
          try parse(src.mkString)
          finally src.close()
        val JObject(tests) = json: @unchecked
        tests should not be empty
        val failures = tests.flatMap { case (name, t) => replay(t).map(d => s"$name: $d") }
        failures shouldBe empty
      }
    }
  }

package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.bouncycastle.util.encoders.Hex
import org.json4s.*

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.BlockDec
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Replays one execution-specs `blockchain_test` fixture through the path `ChainImporter` takes for hive's chain.rlp:
  * genesis via [[GenesisDataLoader]] (the `alloc` is the fixture's `pre`), then per block
  * `validateBlockBeforeExecution` → execute → `validateBlockAfterExecution` → `validateRequestsHash`, each block
  * accepted or rejected exactly as the fixture's `expectException` says, ending on the fixture's `lastblockhash`.
  *
  * Only fields that are byte-for-byte the release's are replayed: `network`, `config.chainid`, `pre`, `genesisRLP`,
  * `blocks[].rlp`, `blocks[].expectException` and `lastblockhash`. The JSON-rendered `blockHeader`, `transactions` and
  * `blockAccessList` are never used to rebuild a block, so a field fukuii does not model cannot be silently dropped on
  * the way in: the block hash that comes out is the hash of the release's own RLP.
  */
object EestBlockchainReplay:

  /** Timestamp forks in activation order. A fixture network is a prefix of this list, all active at genesis, or an
    * `XToYAtTime15k` transition in which the last fork of the prefix activates at timestamp 15,000.
    */
  private val TimestampForks: Seq[String] = Seq("Shanghai", "Cancun", "Prague", "Osaka", "BPO1", "BPO2", "Amsterdam")

  private val Transition = "(\\w+)To(\\w+)AtTime15k".r

  private def schedule(active: Map[String, Long]): ForkTimestamps =
    ForkTimestamps(
      shanghaiTimestamp = active.get("Shanghai"),
      cancunTimestamp = active.get("Cancun"),
      pragueTimestamp = active.get("Prague"),
      osakaTimestamp = active.get("Osaka"),
      amsterdamTimestamp = active.get("Amsterdam"),
      bpo1Timestamp = active.get("BPO1"),
      bpo2Timestamp = active.get("BPO2")
    )

  /** The fork schedule a fixture `network` name denotes, or `None` for a name this replay does not model. Paris is the
    * base every schedule builds on, so it has no timestamp forks.
    */
  def forkTimestamps(network: String): Option[ForkTimestamps] =
    network match
      case "Paris" => Some(ForkTimestamps())
      case Transition(from, to)
          if TimestampForks.contains(to) && TimestampForks.indexOf(to) == TimestampForks.indexOf(from) + 1 =>
        val before = TimestampForks.take(TimestampForks.indexOf(to))
        Some(schedule(before.map(_ -> 0L).toMap + (to -> 15000L)))
      case fork if TimestampForks.contains(fork) =>
        Some(schedule(TimestampForks.take(TimestampForks.indexOf(fork) + 1).map(_ -> 0L).toMap))
      case _ => None

  /** hive's post-merge ETH shape: every block fork at 0, ETC-only forks off, TTD 0, the fixture's timestamp forks. */
  def configFor(base: BlockchainConfig, network: String, chainId: BigInt): Either[String, BlockchainConfig] =
    forkTimestamps(network).toRight(s"network $network not modelled").map { timestamps =>
      base
        .copy(
          networkType = NetworkType.ETH,
          chainId = ChainId(chainId),
          forkTimestamps = timestamps,
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
    }

  private def hx(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))
  private def bi(s: String): BigInt = if s.stripPrefix("0x").isEmpty then 0 else BigInt(s.stripPrefix("0x"), 16)
  private def str(v: JValue): String = v.values.toString

  private class Env extends EphemBlockchainTestSetup:
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

  /** Replays one fixture and returns its divergences from the release; empty means the fixture passes. */
  def replay(t: JValue): Seq[String] =
    val env = new Env
    import env.{blockQueue, blockchain, blockchainReader, blockchainWriter, mining, storagesInstance}
    val chainId = (t \ "config" \ "chainid").toOption.map(v => bi(str(v))).getOrElse(BigInt(1))
    configFor(env.blockchainConfig, str(t \ "network"), chainId) match
      case Left(unmodelled) => Seq(unmodelled)
      case Right(config) =>
        given BlockchainConfig = config

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
        val loadedRoot = blockchainReader.getBlockHeaderByNumber(0).map(_.stateRoot)
        if !loadedRoot.contains(gh.stateRoot) then Seq(s"genesis state root $loadedRoot != ${gh.stateRoot}")
        else
          // The loader's header omits fields the fixture's genesis carries (e.g. parentBeaconBlockRoot); the STATE is
          // what is under test, so the fixture's own genesis header becomes the chain's parent.
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
            // An invalid block's RLP need not decode (INCORRECT_BLOCK_FORMAT): failing to decode is a rejection.
            val outcome: Either[String, (Block, Seq[Receipt])] =
              Try(hx(str(b \ "rlp")).toBlock).toEither.left.map(e => s"undecodable: $e").flatMap { block =>
                blockValidation.validateBlockBeforeExecution(block).left.map(_.toString).flatMap { _ =>
                  blockExecution.executeBlockNoValidationWithRequests(block).left.map(_.describe).flatMap {
                    case (receipts, gasUsed, root, requests) =>
                      blockValidation
                        .validateBlockAfterExecution(block, root, receipts, gasUsed)
                        .left
                        .map(_.toString)
                        .flatMap(_ => blockExecution.validateRequestsHash(block, requests).left.map(_.describe))
                        .map(_ => (block, receipts))
                  }
                }
              }
            (outcome, expectException) match
              case (Right((block, receipts)), None) =>
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

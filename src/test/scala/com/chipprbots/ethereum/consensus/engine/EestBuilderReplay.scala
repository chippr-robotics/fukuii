package com.chipprbots.ethereum.consensus.engine

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
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.EestBlockchainReplay
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Rebuilds the blocks of one execution-specs `blockchain_test` fixture with the proposer's builder,
  * [[EngineApiService.buildBlockOnParent]], and compares each with the block execution-specs produced.
  *
  * Every block the fixture accepts is built on its parent from the inputs a CL and a pool give a proposer — the
  * attributes (timestamp, prevRandao, fee recipient, withdrawals, parent beacon root and, from Amsterdam, the slot),
  * the transactions in order, the extra data and the gas limit, all read off the fixture block — and must come out with
  * execution-specs' block hash. The hash covers every field the builder derives rather than copies: the header's shape,
  * the state, receipts and transactions roots, the logs bloom, gasUsed (from Amsterdam EIP-8037's maximum of two
  * dimensions), the blob gas, the requestsHash (EIP-8282's builder requests included) and the blockAccessListHash
  * (EIP-7928). The gas limit is the one input handed over verbatim: choosing it is the proposer's policy, not header
  * derivation, and is covered separately. A block execution-specs rejects (`expectException`) is not built.
  *
  * Only fields that are byte-for-byte the release's are read, as in [[EestBlockchainReplay]]: `network`,
  * `config.chainid`, `pre`, `genesisRLP`, `blocks[].rlp`, `blocks[].expectException`, `lastblockhash`. The genesis is
  * loaded exactly as [[EestBlockchainReplay.replay]] loads it.
  */
object EestBuilderReplay:

  /** How one fixture went: the blocks rebuilt, and every divergence (none means the builder reproduced the fixture). */
  final case class Outcome(blocksRebuilt: Int, divergences: Seq[String])

  private def hx(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))
  private def bi(s: String): BigInt = if s.stripPrefix("0x").isEmpty then 0 else BigInt(s.stripPrefix("0x"), 16)
  private def str(v: JValue): String = v.values.toString

  private class Env extends EphemBlockchainTestSetup:
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

  /** The header fields `built` and `fixture` disagree on, for a hash mismatch's message. */
  private def differingFields(built: BlockHeader, fixture: BlockHeader): String =
    built.productElementNames
      .zip(built.productIterator.zip(fixture.productIterator))
      .collect { case (name, (b, f)) if b != f => s"$name: built $b, execution-specs $f" }
      .mkString("; ")

  def rebuild(t: JValue): Outcome =
    val env = new Env
    import env.{blockQueue, blockchain, blockchainReader, blockchainWriter, mining, storagesInstance}
    val chainId = (t \ "config" \ "chainid").toOption.map(v => bi(str(v))).getOrElse(BigInt(1))
    EestBlockchainReplay.configFor(env.blockchainConfig, str(t \ "network"), chainId) match
      case Left(unmodelled) => Outcome(0, Seq(unmodelled))
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
        if !loadedRoot.contains(gh.stateRoot) then Outcome(0, Seq(s"genesis state root $loadedRoot != ${gh.stateRoot}"))
        else
          blockchainWriter.save(genesis, Nil, ChainWeight.zero.increase(gh), saveAsBestBlock = true)

          val blockExecution = new BlockExecution(
            blockchain,
            blockchainReader,
            blockchainWriter,
            storagesInstance.storages.evmCodeStorage,
            mining.blockPreparator,
            new BlockValidation(mining, blockchainReader, blockQueue)
          )
          // No pool, no scheduler: buildBlockOnParent is handed the transactions.
          val service = new EngineApiService(
            blockchainReader,
            blockchainWriter,
            blockExecution,
            new ForkChoiceManager(blockchainReader, blockchainWriter),
            None
          )(config, null)

          val JArray(blocks) = t \ "blocks": @unchecked
          val outcomes: Seq[Either[String, Unit]] = blocks.zipWithIndex.flatMap { case (b, i) =>
            if (b \ "expectException").toOption.isDefined then None
            else
              Some(
                Try(hx(str(b \ "rlp")).toBlock).toEither.left
                  .map(e => s"block[$i]: the fixture's RLP does not decode: $e")
                  .flatMap(block => rebuildOne(service, env, i, block))
              )
          }
          val head = blockchainReader.getBestBlock.map(b => "0x" + Hex.toHexString(b.header.hash.value.toArray))
          val expectedHead = str(t \ "lastblockhash")
          Outcome(
            outcomes.count(_.isRight),
            outcomes.collect { case Left(divergence) =>
              divergence
            } ++
              (if head.contains(expectedHead) then Nil else Seq(s"head $head != $expectedHead"))
          )

  /** Builds block `i` on its parent from the fixture block's inputs; on a match the built block becomes the head. */
  private def rebuildOne(service: EngineApiService, env: Env, i: Int, fixture: Block): Either[String, Unit] =
    import env.{blockchainReader, blockchainWriter}
    val h = fixture.header
    blockchainReader.getBlockByHash(h.parentHash).toRight(s"block[$i]: parent ${h.parentHash} was not built").flatMap {
      parent =>
        val attrs = PayloadAttributes(
          timestamp = h.unixTimestamp.toLong,
          prevRandao = h.mixHash.value,
          suggestedFeeRecipient = Address(h.beneficiary),
          withdrawals = fixture.body.withdrawals,
          parentBeaconBlockRoot = h.parentBeaconBlockRoot.map(_.value),
          slotNumber = h.slotNumber
        )
        service
          .buildBlockOnParent(parent, attrs, fixture.body.transactionList, h.extraData, h.gasLimit, strict = true)
          .left
          .map(err => s"block[$i]: the builder failed: $err")
          .flatMap { built =>
            val header = built.block.header
            if header.hash != h.hash then
              Left(s"block[$i]: built ${header.hashAsHexString} != ${h.hashAsHexString}: ${differingFields(header, h)}")
            else if header.blockAccessListHash != built.blockAccessList.map(_.hash) then
              Left(s"block[$i]: the access list the builder keeps does not hash to its header's blockAccessListHash")
            else
              val weight = blockchainReader.getChainWeightByHash(h.parentHash).getOrElse(ChainWeight.zero)
              blockchainWriter.save(built.block, built.receipts, weight.increase(header), saveAsBestBlock = true)
              Right(())
          }
    }

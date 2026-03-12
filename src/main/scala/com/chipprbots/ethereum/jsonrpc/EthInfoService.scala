package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.either._

import scala.reflect.ClassTag

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.PrecompiledContracts

object EthInfoService {
  case class ChainIdRequest()
  case class ChainIdResponse(value: BigInt)

  case class ConfigRequest()
  case class ForkConfig(
      activationBlock: BigInt,
      chainId: BigInt,
      precompiles: Map[String, Address],
      systemContracts: Map[String, Address]
  )
  case class ConfigResponse(
      current: Option[ForkConfig],
      next: Option[ForkConfig],
      last: Option[ForkConfig]
  )

  case class ProtocolVersionRequest()
  case class ProtocolVersionResponse(value: String)

  case class SyncingRequest()
  case class SyncingStatus(
      startingBlock: BigInt,
      currentBlock: BigInt,
      highestBlock: BigInt,
      knownStates: BigInt,
      pulledStates: BigInt
  )
  case class SyncingResponse(syncStatus: Option[SyncingStatus])

  case class CallTx(
      from: Option[ByteString],
      to: Option[ByteString],
      gas: Option[BigInt],
      gasPrice: BigInt,
      value: BigInt,
      data: ByteString
  )

  case class IeleCallTx(
      from: Option[ByteString],
      to: Option[ByteString],
      gas: Option[BigInt],
      gasPrice: BigInt,
      value: BigInt,
      function: Option[String] = None,
      arguments: Option[Seq[ByteString]] = None,
      contractCode: Option[ByteString]
  )

  case class CallRequest(tx: CallTx, block: BlockParam)
  case class CallResponse(returnData: ByteString)
  case class IeleCallRequest(tx: IeleCallTx, block: BlockParam)
  case class IeleCallResponse(returnData: Seq[ByteString])
  case class EstimateGasResponse(gas: BigInt)
}

class EthInfoService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    blockchainConfig: BlockchainConfig,
    val mining: Mining,
    stxLedger: StxLedger,
    keyStore: KeyStore,
    syncingController: ActorRef,
    capability: Capability,
    askTimeout: Timeout
) extends ResolveBlock {

  import EthInfoService._

  def protocolVersion(req: ProtocolVersionRequest): ServiceResponse[ProtocolVersionResponse] =
    IO.pure(Right(ProtocolVersionResponse(f"0x${capability.version}%x")))

  def chainId(req: ChainIdRequest): ServiceResponse[ChainIdResponse] =
    IO.pure(Right(ChainIdResponse(blockchainConfig.chainId)))

  /** Implements the eth_syncing method that returns syncing information if the node is syncing.
    *
    * @return
    *   The syncing status if the node is syncing or None if not
    */
  def syncing(req: SyncingRequest): ServiceResponse[SyncingResponse] =
    syncingController
      .askFor(SyncProtocol.GetStatus)(timeout = askTimeout, implicitly[ClassTag[SyncProtocol.Status]])
      .map {
        case Status.Syncing(startingBlockNumber, blocksProgress, maybeStateNodesProgress) =>
          val stateNodesProgress = maybeStateNodesProgress.getOrElse(Progress.empty)
          SyncingResponse(
            Some(
              SyncingStatus(
                startingBlock = startingBlockNumber,
                currentBlock = blocksProgress.current,
                highestBlock = blocksProgress.target,
                knownStates = stateNodesProgress.target,
                pulledStates = stateNodesProgress.current
              )
            )
          )
        case Status.NotSyncing => SyncingResponse(None)
        case Status.SyncDone   => SyncingResponse(None)
      }
      .map(_.asRight)

  def config(req: ConfigRequest): ServiceResponse[ConfigResponse] = IO {
    val fbn = blockchainConfig.forkBlockNumbers
    val chainId = blockchainConfig.chainId

    val basePrecompiles: Map[String, Address] = Map(
      "ecrecover" -> PrecompiledContracts.EcDsaRecAddr,
      "sha256" -> PrecompiledContracts.Sha256Addr,
      "ripemd160" -> PrecompiledContracts.Rip160Addr,
      "identity" -> PrecompiledContracts.IdAddr
    )
    val byzantiumPrecompiles: Map[String, Address] = basePrecompiles ++ Map(
      "modexp" -> PrecompiledContracts.ModExpAddr,
      "bn256Add" -> PrecompiledContracts.Bn128AddAddr,
      "bn256ScalarMul" -> PrecompiledContracts.Bn128MulAddr,
      "bn256Pairing" -> PrecompiledContracts.Bn128PairingAddr
    )
    val istanbulPrecompiles: Map[String, Address] = byzantiumPrecompiles ++ Map(
      "blake2f" -> PrecompiledContracts.Blake2bCompressionAddr
    )
    val olympiaPrecompiles: Map[String, Address] = istanbulPrecompiles ++ Map(
      "bls12381G1Add" -> PrecompiledContracts.BlsG1AddAddr,
      "bls12381G1MultiExp" -> PrecompiledContracts.BlsG1MultiExpAddr,
      "bls12381G2Add" -> PrecompiledContracts.BlsG2AddAddr,
      "bls12381G2MultiExp" -> PrecompiledContracts.BlsG2MultiExpAddr,
      "bls12381Pairing" -> PrecompiledContracts.BlsPairingAddr,
      "bls12381MapG1" -> PrecompiledContracts.BlsMapG1Addr,
      "bls12381MapG2" -> PrecompiledContracts.BlsMapG2Addr,
      "p256Verify" -> PrecompiledContracts.P256VerifyAddr
    )

    val noSystemContracts: Map[String, Address] = Map.empty
    val olympiaSystemContracts: Map[String, Address] = Map(
      "historyStorage" -> HistoryStorageAddress
    )

    // Build fork schedule: (name, blockNumber, precompiles, systemContracts)
    val forks: List[(String, BigInt, Map[String, Address], Map[String, Address])] = List(
      ("Frontier", fbn.frontierBlockNumber, basePrecompiles, noSystemContracts),
      ("Homestead", fbn.homesteadBlockNumber, basePrecompiles, noSystemContracts),
      ("Atlantis", fbn.atlantisBlockNumber, byzantiumPrecompiles, noSystemContracts),
      ("Agharta", fbn.aghartaBlockNumber, byzantiumPrecompiles, noSystemContracts),
      ("Phoenix", fbn.phoenixBlockNumber, istanbulPrecompiles, noSystemContracts),
      ("Magneto", fbn.magnetoBlockNumber, istanbulPrecompiles, noSystemContracts),
      ("Mystique", fbn.mystiqueBlockNumber, istanbulPrecompiles, noSystemContracts),
      ("Spiral", fbn.spiralBlockNumber, istanbulPrecompiles, noSystemContracts),
      ("Olympia", fbn.olympiaBlockNumber, olympiaPrecompiles, olympiaSystemContracts)
    ).filter(_._2 < Long.MaxValue) // exclude forks not configured
      .sortBy(_._2)
      .distinctBy(_._2) // deduplicate by block number

    val currentBlock = blockchainReader.getBestBlockNumber()

    def toForkConfig(name: String, block: BigInt, precompiles: Map[String, Address], sysContracts: Map[String, Address]): ForkConfig =
      ForkConfig(block, chainId, precompiles, sysContracts)

    // Find current fork (last fork at or before currentBlock)
    val activeForks = forks.filter(_._2 <= currentBlock)
    val futureForks = forks.filter(_._2 > currentBlock)

    val current = activeForks.lastOption.map(f => toForkConfig(f._1, f._2, f._3, f._4))
    val next = futureForks.headOption.map(f => toForkConfig(f._1, f._2, f._3, f._4))
    val last = forks.lastOption.map(f => toForkConfig(f._1, f._2, f._3, f._4))

    Right(ConfigResponse(current, next, if (futureForks.nonEmpty) last else None))
  }

  def call(req: CallRequest): ServiceResponse[CallResponse] =
    IO {
      doCall(req)(stxLedger.simulateTransaction).map(r => CallResponse(r.vmReturnData))
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  def ieleCall(req: IeleCallRequest): ServiceResponse[IeleCallResponse] = {
    import req.tx

    val args = tx.arguments.getOrElse(Nil)
    val dataEither = (tx.function, tx.contractCode) match {
      case (Some(function), None)     => Right(rlp.encode(RLPList(function, args)))
      case (None, Some(contractCode)) => Right(rlp.encode(RLPList(contractCode, args)))
      case _ => Left(JsonRpcError.InvalidParams("Iele transaction should contain either functionName or contractCode"))
    }

    dataEither match {
      case Right(data) =>
        call(CallRequest(CallTx(tx.from, tx.to, tx.gas, tx.gasPrice, tx.value, ByteString(data)), req.block))
          .map(_.map { callResponse =>
            IeleCallResponse(
              rlp.decode[Seq[ByteString]](callResponse.returnData.toArray[Byte])
            )
          })
      case Left(error) => IO.pure(Left(error))
    }
  }

  def estimateGas(req: CallRequest): ServiceResponse[EstimateGasResponse] =
    IO {
      doCall(req)(stxLedger.binarySearchGasEstimation).map(gasUsed => EstimateGasResponse(gasUsed))
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  private def doCall[A](req: CallRequest)(
      f: (SignedTransactionWithSender, BlockHeader, Option[InMemoryWorldStateProxy]) => A
  ): Either[JsonRpcError, A] = for {
    stx <- prepareTransaction(req)
    block <- resolveBlock(req.block)
  } yield f(stx, block.block.header, block.pendingState)

  private def getGasLimit(req: CallRequest): Either[JsonRpcError, BigInt] =
    req.tx.gas.map(Right.apply).getOrElse(resolveBlock(BlockParam.Latest).map(r => r.block.header.gasLimit))

  private def prepareTransaction(req: CallRequest): Either[JsonRpcError, SignedTransactionWithSender] =
    getGasLimit(req).map { gasLimit =>
      val fromAddress = req.tx.from
        .map(Address.apply) // `from` param, if specified
        .getOrElse(
          keyStore
            .listAccounts()
            .getOrElse(Nil)
            .headOption // first account, if exists and `from` param not specified
            .getOrElse(Address(0))
        ) // 0x0 default

      val toAddress = req.tx.to.map(Address.apply)

      val tx = LegacyTransaction(0, req.tx.gasPrice, gasLimit, toAddress, req.tx.value, req.tx.data)
      val fakeSignature = ECDSASignature(0, 0, 0)
      SignedTransactionWithSender(tx, fakeSignature, fromAddress)
    }

}

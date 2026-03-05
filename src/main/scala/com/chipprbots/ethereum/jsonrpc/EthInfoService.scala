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
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.PrecompiledContracts

object EthInfoService {
  case class ChainIdRequest()
  case class ChainIdResponse(value: BigInt)

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

  def config(req: ConfigRequest): ServiceResponse[ConfigResponse] = IO {
    val forks = blockchainConfig.forkBlockNumbers
    val bestBlockNumber = blockchainReader.getBestBlockNumber()
    val chainIdVal = blockchainConfig.chainId

    // Ordered list of (name, blockNumber) for all ETC forks
    // Exclude unscheduled forks (set to Long.MaxValue)
    val forkSchedule: List[(String, BigInt)] = List(
      "frontier" -> forks.frontierBlockNumber,
      "homestead" -> forks.homesteadBlockNumber,
      "eip150" -> forks.eip150BlockNumber,
      "eip155" -> forks.eip155BlockNumber,
      "eip160" -> forks.eip160BlockNumber,
      "eip161" -> forks.eip161BlockNumber,
      "byzantium" -> forks.byzantiumBlockNumber,
      "constantinople" -> forks.constantinopleBlockNumber,
      "istanbul" -> forks.istanbulBlockNumber,
      "atlantis" -> forks.atlantisBlockNumber,
      "agharta" -> forks.aghartaBlockNumber,
      "phoenix" -> forks.phoenixBlockNumber,
      "petersburg" -> forks.petersburgBlockNumber,
      "magneto" -> forks.magnetoBlockNumber,
      "berlin" -> forks.berlinBlockNumber,
      "mystique" -> forks.mystiqueBlockNumber,
      "spiral" -> forks.spiralBlockNumber
    ).filter(_._2 < BigInt(Long.MaxValue))

    // Find current, next, and last fork relative to best block
    val activeForks = forkSchedule.filter(_._2 <= bestBlockNumber)
    val futureForks = forkSchedule.filter(_._2 > bestBlockNumber)

    val currentFork = activeForks.lastOption
    val lastFork = if (activeForks.size >= 2) activeForks.init.lastOption else None
    val nextFork = futureForks.headOption

    def precompilesForBlock(blockNumber: BigInt): Map[String, Address] = {
      val base: Map[String, Address] = Map(
        "ecRecover" -> PrecompiledContracts.EcDsaRecAddr,
        "sha256" -> PrecompiledContracts.Sha256Addr,
        "ripemd160" -> PrecompiledContracts.Rip160Addr,
        "identity" -> PrecompiledContracts.IdAddr
      )
      val byzantiumAdd: Map[String, Address] = Map(
        "modExp" -> PrecompiledContracts.ModExpAddr,
        "bn128Add" -> PrecompiledContracts.Bn128AddAddr,
        "bn128Mul" -> PrecompiledContracts.Bn128MulAddr,
        "bn128Pairing" -> PrecompiledContracts.Bn128PairingAddr
      )
      val istanbulAdd: Map[String, Address] = Map(
        "blake2b" -> PrecompiledContracts.Blake2bCompressionAddr
      )

      if (blockNumber >= forks.phoenixBlockNumber || blockNumber >= forks.istanbulBlockNumber)
        base ++ byzantiumAdd ++ istanbulAdd
      else if (blockNumber >= forks.atlantisBlockNumber || blockNumber >= forks.byzantiumBlockNumber)
        base ++ byzantiumAdd
      else
        base
    }

    def systemContractsForBlock(blockNumber: BigInt): Map[String, Address] =
      Map.empty

    def buildForkConfig(name: String, blockNumber: BigInt): ForkConfig =
      ForkConfig(
        activationBlock = blockNumber,
        chainId = chainIdVal,
        precompiles = precompilesForBlock(blockNumber),
        systemContracts = systemContractsForBlock(blockNumber)
      )

    Right(ConfigResponse(
      current = currentFork.map { case (name, block) => buildForkConfig(name, block) },
      next = nextFork.map { case (name, block) => buildForkConfig(name, block) },
      last = lastFork.map { case (name, block) => buildForkConfig(name, block) }
    ))
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

package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.IO
import cats.syntax.either.*

import scala.annotation.unused

import com.chipprbots.ethereum.blockchain.sync.SyncController
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol.Status.Progress
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.PrecompiledContracts

object EthInfoService:
  case class ChainIdRequest()
  case class ChainIdResponse(value: BigInt)

  case class ConfigRequest()

  /** EIP-7892 blob parameters advertised for a fork. Blob counts, not gas. */
  case class BlobScheduleConfig(target: BigInt, max: BigInt, baseFeeUpdateFraction: BigInt)

  /** One entry of the EIP-7910 `eth_config` response.
    *
    * `activationBlock` and `activationTime` are mutually exclusive: ETH-family forks after the Merge are
    * timestamp-gated and report `activationTime`; ETC remains block-gated and reports `activationBlock`.
    * `forkId`/`blobSchedule` are ETH-family only.
    */
  case class ForkConfig(
      activationBlock: Option[BigInt],
      activationTime: Option[Long],
      chainId: BigInt,
      forkId: Option[BigInt],
      blobSchedule: Option[BlobScheduleConfig],
      precompiles: Map[String, Address],
      systemContracts: Map[String, Address]
  )

  /** Canonical EIP-7910 precompile names, keyed by address.
    *
    * These are the names the spec fixes, NOT go-ethereum's internal identifiers, and the mapping is not mechanical:
    * `ecrecover` is `ECREC`, `identity` is `ID`, the bn256 family is `BN254_*` (the curve's correct name), and the BLS
    * multi-exponentiations are `MSM`. The set of addresses is derived from `PrecompiledContracts` so the advertised
    * list cannot drift from the precompiles the EVM actually runs.
    */
  val Eip7910PrecompileNames: Map[Address, String] = Map(
    PrecompiledContracts.EcDsaRecAddr -> "ECREC",
    PrecompiledContracts.Sha256Addr -> "SHA256",
    PrecompiledContracts.Rip160Addr -> "RIPEMD160",
    PrecompiledContracts.IdAddr -> "ID",
    PrecompiledContracts.ModExpAddr -> "MODEXP",
    PrecompiledContracts.Bn128AddAddr -> "BN254_ADD",
    PrecompiledContracts.Bn128MulAddr -> "BN254_MUL",
    PrecompiledContracts.Bn128PairingAddr -> "BN254_PAIRING",
    PrecompiledContracts.Blake2bCompressionAddr -> "BLAKE2F",
    PrecompiledContracts.KzgPointEvalAddr -> "KZG_POINT_EVALUATION",
    PrecompiledContracts.BlsG1AddAddr -> "BLS12_G1ADD",
    PrecompiledContracts.BlsG1MultiExpAddr -> "BLS12_G1MSM",
    PrecompiledContracts.BlsG2AddAddr -> "BLS12_G2ADD",
    PrecompiledContracts.BlsG2MultiExpAddr -> "BLS12_G2MSM",
    PrecompiledContracts.BlsPairingAddr -> "BLS12_PAIRING_CHECK",
    PrecompiledContracts.BlsMapG1Addr -> "BLS12_MAP_FP_TO_G1",
    PrecompiledContracts.BlsMapG2Addr -> "BLS12_MAP_FP2_TO_G2",
    PrecompiledContracts.P256VerifyAddr -> "P256VERIFY"
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
      data: ByteString,
      gasPriceExplicit: Boolean = false
  )

  case class CallRequest(tx: CallTx, block: BlockParam)
  case class CallResponse(returnData: ByteString)
  case class EstimateGasResponse(gas: BigInt)
  case class CreateAccessListRequest(tx: CallTx, block: BlockParam):
    def toCallRequest: CallRequest = CallRequest(tx, block)
  case class CreateAccessListResponse(
      accessList: Seq[Map[String, Any]],
      gasUsed: BigInt,
      error: Option[String]
  )

class EthInfoService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    blockchainConfig: BlockchainConfig,
    val mining: Mining,
    stxLedger: StxLedger,
    keyStore: KeyStore,
    syncingController: TypedActorRef[SyncController.Command],
    capability: Capability,
    askTimeout: Timeout,
    scheduler: Scheduler
) extends ResolveBlock:

  import EthInfoService.*

  private given typedScheduler: Scheduler = scheduler

  def protocolVersion(@unused req: ProtocolVersionRequest): ServiceResponse[ProtocolVersionResponse] =
    IO.pure(Right(ProtocolVersionResponse(f"0x${capability.version}%x")))

  def chainId(@unused req: ChainIdRequest): ServiceResponse[ChainIdResponse] =
    IO.pure(Right(ChainIdResponse(blockchainConfig.chainId.value)))

  /** Implements the eth_syncing method that returns syncing information if the node is syncing.
    *
    * @return
    *   The syncing status if the node is syncing or None if not
    */
  def syncing(@unused req: SyncingRequest): ServiceResponse[SyncingResponse] =
    syncingController
      .askForTyped[SyncProtocol.Status](replyTo => SyncController.WrappedSyncProtocol(SyncProtocol.GetStatus(replyTo)))(
        timeout = askTimeout,
        scheduler = typedScheduler
      )
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

  /** eth_config (EIP-7910).
    *
    * ETH-family chains are timestamp-gated after the Merge, so `current`/`next`/`last` are selected from the
    * `forkTimestamps` schedule and report `activationTime`. ETC/Mordor is block-gated and keeps the block-numbered
    * schedule below unchanged.
    */
  def config(@unused req: ConfigRequest): ServiceResponse[ConfigResponse] = IO {
    val response = blockchainConfig.networkType match
      case NetworkType.ETH => ethFamilyConfig.getOrElse(blockNumberedConfig)
      case NetworkType.ETC => blockNumberedConfig
    Right(response)
  }

  /** Head timestamp used to select the active fork. Mirrors the handshake's derivation (EthNodeStatus68ExchangeState)
    * so `eth_config` and the advertised fork id cannot disagree about which fork we believe we are on.
    */
  private def headTimestamp: Long =
    val stored = blockchainReader
      .getBlockHeaderByNumber(blockchainReader.getBestBlockNumber)
      .map(_.unixTimestamp)
      .getOrElse(Timestamp.Zero)
    if stored == Timestamp.Zero then System.currentTimeMillis() / 1000 else stored.toLong

  /** EIP-7910 response for timestamp-gated (ETH-family) chains. `None` when no timestamp fork has activated yet — the
    * caller then falls back to the block-numbered schedule rather than inventing an activation time.
    */
  private def ethFamilyConfig: Option[ConfigResponse] =
    val ft = blockchainConfig.forkTimestamps
    // Sorted by activation time, not declaration order: EIP-7892 BPO forks interleave with
    // the named forks, and on some schedules two forks share a timestamp.
    val schedule: List[Long] = List(
      ft.shanghaiTimestamp,
      ft.cancunTimestamp,
      ft.pragueTimestamp,
      ft.osakaTimestamp,
      ft.bpo1Timestamp,
      ft.bpo2Timestamp,
      ft.amsterdamTimestamp
    ).flatten.distinct.sorted

    val head = headTimestamp
    val headNumber = blockchainReader.getBestBlockNumber

    schedule.filter(_ <= head).lastOption.map { currentTs =>
      val next = schedule.find(_ > head)
      ConfigResponse(
        current = Some(ethForkConfig(currentTs, headNumber)),
        next = next.map(ethForkConfig(_, headNumber)),
        // `last` describes the final scheduled fork. With nothing further scheduled there is no
        // "last" distinct from "current", and EIP-7910 wants null — emitting a fabricated entry
        // (the old 1e18 "never" sentinel) advertises a fork that does not exist.
        last = next.map(_ => ethForkConfig(schedule.last, headNumber))
      )
    }

  private def ethForkConfig(forkTimestamp: Long, headNumber: BigInt): ForkConfig =
    val ts = Timestamp(forkTimestamp)
    ForkConfig(
      activationBlock = None,
      activationTime = Some(forkTimestamp),
      chainId = blockchainConfig.chainId.value,
      // Computed with the same EIP-2124/6122 machinery the p2p handshake uses, evaluated at this
      // fork's own timestamp so each entry carries the checksum through that fork.
      forkId = Some(
        ForkId
          .create(
            blockchainReader.genesisHeader.hash.value,
            blockchainReader.genesisHeader.unixTimestamp.toLong,
            blockchainConfig
          )(headNumber, forkTimestamp)
          .hash
      ),
      blobSchedule = blobScheduleAt(ts),
      precompiles = ethPrecompilesAt(ts),
      systemContracts = ethSystemContractsAt(ts)
    )

  /** Blob parameters in BLOBS, not gas — EIP-7910 reports counts. Absent before Cancun, which is the first fork with
    * blobs at all.
    */
  private def blobScheduleAt(ts: Timestamp): Option[BlobScheduleConfig] =
    Option.when(blockchainConfig.isCancunTimestamp(ts))(
      BlobScheduleConfig(
        target = BlobGasUtils.targetBlobGasPerBlock(ts, blockchainConfig) / BlobGasUtils.GAS_PER_BLOB,
        max = BlobGasUtils.maxBlobGasPerBlock(ts, blockchainConfig) / BlobGasUtils.GAS_PER_BLOB,
        baseFeeUpdateFraction = BlobGasUtils.updateFractionFor(ts, blockchainConfig)
      )
    )

  /** Derived from the very sets `PrecompiledContracts` hands the EVM, so the advertised list is the implemented list.
    * BPO and Amsterdam forks add no precompiles, so they inherit the Osaka set.
    */
  private def ethPrecompilesAt(ts: Timestamp): Map[String, Address] =
    val active =
      if blockchainConfig.isOsakaTimestamp(ts) then PrecompiledContracts.osakaContracts
      else if blockchainConfig.isPragueTimestamp(ts) then PrecompiledContracts.olympiaContracts
      else if blockchainConfig.isCancunTimestamp(ts) then PrecompiledContracts.cancunContracts
      else PrecompiledContracts.istanbulPhoenixContracts
    active.keys.flatMap(addr => Eip7910PrecompileNames.get(addr).map(_ -> addr)).toMap

  /** EIP-4788 lands the beacon-roots contract at Cancun; EIP-2935/6110/7002/7251 land the rest at Prague. */
  private def ethSystemContractsAt(ts: Timestamp): Map[String, Address] =
    val beaconRoots = Map("BEACON_ROOTS_ADDRESS" -> BlockExecution.BeaconRootContractAddress)
    if blockchainConfig.isPragueTimestamp(ts) then
      beaconRoots ++ Map(
        "CONSOLIDATION_REQUEST_PREDEPLOY_ADDRESS" -> BlockExecution.ConsolidationQueueAddress,
        // Genesis-declared per chain (geth `config.depositContractAddress`); the mainnet
        // contract is only the fallback when a chain does not declare one. Same resolver as
        // execution, so the advertised contract is the one whose logs become deposit requests.
        "DEPOSIT_CONTRACT_ADDRESS" -> BlockExecution.depositContractFor(blockchainConfig),
        "HISTORY_STORAGE_ADDRESS" -> HistoryStorageAddress,
        "WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS" -> BlockExecution.WithdrawalQueueAddress
      )
    else if blockchainConfig.isCancunTimestamp(ts) then beaconRoots
    else Map.empty

  /** Block-numbered fork schedule. This is the ETC/Mordor path and its output is deliberately unchanged. */
  private def blockNumberedConfig: ConfigResponse =
    val fbn = blockchainConfig.forkBlockNumbers
    val chainId = blockchainConfig.chainId.value

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

    val currentBlock = blockchainReader.getBestBlockNumber

    def toForkConfig(
        @unused name: String,
        block: BigInt,
        precompiles: Map[String, Address],
        sysContracts: Map[String, Address]
    ): ForkConfig =
      ForkConfig(Some(block), None, chainId, None, None, precompiles, sysContracts)

    // Find current fork (last fork at or before currentBlock)
    val activeForks = forks.filter(_._2 <= currentBlock)
    val futureForks = forks.filter(_._2 > currentBlock)

    val current = activeForks.lastOption.map(f => toForkConfig(f._1, f._2, f._3, f._4))
    val next = futureForks.headOption.map(f => toForkConfig(f._1, f._2, f._3, f._4))
    val last = forks.lastOption.map(f => toForkConfig(f._1, f._2, f._3, f._4))

    ConfigResponse(current, next, if futureForks.nonEmpty then last else None)

  def call(req: CallRequest): ServiceResponse[CallResponse] =
    IO {
      doCall(req)(stxLedger.simulateTransaction).flatMap { r =>
        r.vmError match
          case Some(com.chipprbots.ethereum.vm.RevertOccurs) =>
            val dataHex = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(r.vmReturnData.toArray[Byte])
            Left(JsonRpcError(3, "execution reverted", Some(org.json4s.JString(dataHex))))
          case Some(_) =>
            // Other VM errors (out of gas, etc) — return empty result
            Right(CallResponse(r.vmReturnData))
          case None =>
            Right(CallResponse(r.vmReturnData))
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  def estimateGas(req: CallRequest): ServiceResponse[EstimateGasResponse] =
    IO {
      // First check if the tx reverts at the max gas limit
      val simCheck = doCall(req)(stxLedger.simulateTransaction)
      simCheck.flatMap { r =>
        r.vmError match
          case Some(com.chipprbots.ethereum.vm.RevertOccurs) =>
            val dataHex = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(r.vmReturnData.toArray[Byte])
            Left(JsonRpcError(3, "execution reverted", Some(org.json4s.JString(dataHex))))
          case _ =>
            // Tx doesn't revert — find minimum gas via binary search
            doCall(req)(stxLedger.binarySearchGasEstimation).map(gas => EstimateGasResponse(gas))
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  def createAccessList(req: CreateAccessListRequest): ServiceResponse[CreateAccessListResponse] =
    IO {
      doCall(req.toCallRequest)(stxLedger.simulateTransaction).map { result =>
        val gasUsed = result.gasUsed
        val error = result.vmError.map(_.toString)
        // Build access list from the VM's accessed addresses and storage keys
        // For now, return empty access list with gas used (partial implementation)
        CreateAccessListResponse(
          accessList = Seq.empty,
          gasUsed = gasUsed,
          error = error
        )
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  private def doCall[A](req: CallRequest)(
      f: (SignedTransactionWithSender, BlockHeader, Option[InMemoryWorldStateProxy]) => A
  ): Either[JsonRpcError, A] = for
    stx <- prepareTransaction(req)
    block <- resolveBlock(req.block)
  yield
    // EIP-1559: When no gas price is explicitly specified, use baseFee=0 so calls
    // don't need to worry about funding. Matches geth behavior for eth_call/eth_estimateGas.
    val header = if !req.tx.gasPriceExplicit && block.block.header.baseFee.isDefined then
      import BlockHeader.HeaderExtraFields.*
      val zeroBaseFeeExtra = block.block.header.extraFields match
        case HefPostOlympia(_)                    => HefPostOlympia(0)
        case HefPostShanghai(_, wr)               => HefPostShanghai(0, wr)
        case HefPostCancun(_, wr, bg, eb, pb)     => HefPostCancun(0, wr, bg, eb, pb)
        case HefPostPrague(_, wr, bg, eb, pb, rh) => HefPostPrague(0, wr, bg, eb, pb, rh)
        // Amsterdam (23 items). Without this case the `other` arm returns the header unchanged and
        // eth_call / eth_estimateGas keep a non-zero base fee on every Amsterdam block — a silent
        // degradation, not a crash, which is why it needs its own case rather than a catch-all.
        case HefPostAmsterdam(_, wr, bg, eb, pb, rh, bal, slot) =>
          HefPostAmsterdam(0, wr, bg, eb, pb, rh, bal, slot)
        case other => other
      block.block.header.copy(extraFields = zeroBaseFeeExtra)
    else block.block.header
    f(stx, header, block.pendingState)

  private def getGasLimit(req: CallRequest): Either[JsonRpcError, BigInt] =
    req.tx.gas.map(Right.apply).getOrElse(resolveBlock(BlockParam.Latest).map(r => r.block.header.gasLimit.value))

  private def prepareTransaction(req: CallRequest): Either[JsonRpcError, SignedTransactionWithSender] =
    getGasLimit(req).map { gasLimit =>
      val fromAddress = req.tx.from
        .map(Address.apply) // `from` param, if specified
        .getOrElse(
          keyStore.listAccounts
            .getOrElse(Nil)
            .headOption // first account, if exists and `from` param not specified
            .getOrElse(Address(0))
        ) // 0x0 default

      val toAddress = req.tx.to.map(Address.apply)

      val tx =
        LegacyTransaction(0, GasPrice(req.tx.gasPrice), GasAmount(gasLimit), toAddress, req.tx.value, req.tx.data)
      val fakeSignature = ECDSASignature(0, 0, 0)
      SignedTransactionWithSender(tx, fakeSignature, fromAddress)
    }

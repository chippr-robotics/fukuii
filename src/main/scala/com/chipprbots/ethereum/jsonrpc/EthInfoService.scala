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
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps.*
import com.chipprbots.ethereum.keystore.KeyStore
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.AccessListTracer
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

  /** A message call: eth_call / eth_estimateGas, with their optional state and block overrides (execution-apis; geth
    * `BlockChainAPI.Call` / `EstimateGas`). Also what GraphQL `call` / `estimateGas` send, without overrides.
    */
  case class CallRequest(
      tx: TxArgs,
      block: BlockParam,
      stateOverrides: Option[Map[Address, EthSimulateService.StateOverride]] = None,
      blockOverrides: Option[EthSimulateService.BlockOverrides] = None
  )
  case class CallResponse(returnData: ByteString)
  case class EstimateGasResponse(gas: BigInt)

  /** eth_createAccessList: the call, its block and optional state overrides (go-ethereum `CreateAccessList(args,
    * blockNrOrHash, stateOverrides)`).
    */
  case class CreateAccessListRequest(
      tx: TxArgs,
      block: BlockParam,
      stateOverrides: Option[Map[Address, EthSimulateService.StateOverride]] = None
  )
  case class CreateAccessListResponse(
      accessList: List[AccessListItem],
      gasUsed: BigInt,
      error: Option[String]
  )

  /** eth_createAccessList's bound on its runs (geth loops until the list is stable). */
  val MaxAccessListIterations: Int = 64

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
    scheduler: Scheduler,
    // go-ethereum --rpc.gascap: the gas of a call that names none, and the most any call or estimate may use. 0 = no cap
    gasCap: BigInt = CallTxBuilder.DefaultGasCap
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
        using
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

  /** Blob parameters in BLOBS, not gas — EIP-7910 reports counts — straight from the chain's `blob-schedule` (#1637),
    * the entry in force at the fork's own timestamp (go-ethereum `ChainConfig.BlobConfig(fork)`). Absent before the
    * chain's first blob fork.
    */
  private def blobScheduleAt(ts: Timestamp): Option[BlobScheduleConfig] =
    blockchainConfig
      .blobParamsAt(ts)
      .map(p => BlobScheduleConfig(target = p.target, max = p.max, baseFeeUpdateFraction = p.baseFeeUpdateFraction))

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

  /** The system contracts active at `ts`, cumulative by fork as go-ethereum's `ChainConfig.ActiveSystemContracts`
    * builds them (same names):
    *   - Cancun: EIP-4788's beacon-roots contract.
    *   - Prague: EIP-2935's history contract, EIP-6110's deposit contract, EIP-7002's and EIP-7251's request queues.
    *   - Amsterdam: EIP-8282's builder deposit and builder exit request queues. They are the addresses execution calls
    *     at the end of an Amsterdam block (`BlockExecution.systemCallTargets`), so what the node advertises is what
    *     enters its `requestsHash`.
    */
  private def ethSystemContractsAt(ts: Timestamp): Map[String, Address] =
    val cancun =
      if blockchainConfig.isCancunTimestamp(ts) then
        Map("BEACON_ROOTS_ADDRESS" -> BlockExecution.BeaconRootContractAddress)
      else Map.empty[String, Address]
    val prague =
      if blockchainConfig.isPragueTimestamp(ts) then
        Map(
          "CONSOLIDATION_REQUEST_PREDEPLOY_ADDRESS" -> BlockExecution.ConsolidationQueueAddress,
          // Genesis-declared per chain (geth `config.depositContractAddress`); the mainnet
          // contract is only the fallback when a chain does not declare one. Same resolver as
          // execution, so the advertised contract is the one whose logs become deposit requests.
          "DEPOSIT_CONTRACT_ADDRESS" -> BlockExecution.depositContractFor(blockchainConfig),
          "HISTORY_STORAGE_ADDRESS" -> HistoryStorageAddress,
          "WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS" -> BlockExecution.WithdrawalQueueAddress
        )
      else Map.empty[String, Address]
    val amsterdam =
      if blockchainConfig.isAmsterdamTimestamp(ts) then
        Map(
          "BUILDER_DEPOSIT_CONTRACT_ADDRESS" -> BlockExecution.BuilderDepositQueueAddress,
          "BUILDER_EXIT_CONTRACT_ADDRESS" -> BlockExecution.BuilderExitQueueAddress
        )
      else Map.empty[String, Address]
    cancun ++ prague ++ amsterdam

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

  /** eth_call (go-ethereum `internal/ethapi` `BlockChainAPI.Call` -> `DoCall` -> `doCall`). The message is built by
    * [[CallTxBuilder]] (CallDefaults / ToMessage) and run by `StxLedger.simulateCall` (ApplyMessage). State overrides
    * are applied to the block's state and block overrides to its header first, as `doCall` does.
    *
    * A frame that fails is an error, as geth's `Call` returns `result.Return(), result.Err`: a revert is code 3 with
    * its data, and any other VM error (out of gas, invalid opcode, ...) is -32000 with geth's message. Never an empty
    * result: a caller that reads `0x` as success would be told a message that runs out of gas succeeds.
    *
    * A pre-Homestead creation that cannot pay its code deposit succeeds in the frame (no code, gas and state kept) but
    * is go-ethereum's `ErrCodeStoreOutOfGas` here: -32000 "contract creation code storage out of gas", no data.
    */
  def call(req: CallRequest): ServiceResponse[CallResponse] =
    callOutcome(req).map(_.flatMap { outcome =>
      val r = outcome.txResult
      r.vmError match
        case Some(com.chipprbots.ethereum.vm.RevertOccurs) => Left(revertError(r.vmReturnData))
        case Some(error) => Left(JsonRpcError.LogicError(CallErrors.vmErrorMessage(error)))
        case None if outcome.codeDepositShortfall =>
          Left(JsonRpcError.LogicError(CallErrors.vmErrorMessage(com.chipprbots.ethereum.vm.CodeStoreOutOfGas)))
        case None => Right(CallResponse(r.vmReturnData))
    })

  /** The message call eth_call runs, with its VM outcome as it came out of the frame: a failed frame is a `Right` whose
    * `vmError` is set, a pre-Homestead creation short of its code deposit one whose `codeDepositShortfall` is.
    * GraphQL's `call` reads its `status` from those (geth `result.Failed()`); a `Left` is a pre-check failure (geth
    * `preCheck` / `buyGas` / intrinsic gas / the transfer check), which geth's GraphQL returns as an error.
    */
  def callOutcome(req: CallRequest): ServiceResponse[StxLedger.CallOutcome] =
    StateRead
      .reading {
        for
          // eth_call: geth applyMessage sets SkipExecutionGasCapCheck
          prepared <- prepareCall(req, CallTxBuilder.callGas(req.tx, gasCap), skipExecutionGasCap = true)
          (msg, world) = prepared
          r <- stxLedger
            .simulateCall(msg.stx, msg.header, world, msg.options, tracer = None)
            .left
            .map(callPreCheckError(msg.stx.tx.tx.gasLimit.value))
        yield r
      }
      .recover { case _: MissingNodeException =>
        Left(JsonRpcError.NodeNotFound)
      }

  /** eth_estimateGas (go-ethereum `DoEstimateGas` -> `eth/gasestimator.Estimate`). */
  def estimateGas(req: CallRequest): ServiceResponse[EstimateGasResponse] =
    StateRead
      .reading {
        for
          resolved <- resolveBlock(req.block)
          hi = CallTxBuilder.estimateGasCeiling(req.tx, resolved.block.header, gasCap)(using blockchainConfig)
          // the estimator does not skip the execution gas cap (gasestimator)
          prepared <- prepareCall(req, resolved, hi, skipExecutionGasCap = false)
          (msg, world) = prepared
          gas <- stxLedger.estimateCallGas(msg.stx, msg.header, world, msg.options).left.map {
            case StxLedger.CallEstimateFailure.Reverted(data) => revertError(data)
            case StxLedger.CallEstimateFailure.PreCheck(msg)  => JsonRpcError.LogicError(msg)
            case StxLedger.CallEstimateFailure.Failed(error) =>
              JsonRpcError.LogicError(CallErrors.vmErrorMessage(error))
          }
        yield EstimateGasResponse(gas)
      }
      .recover { case _: MissingNodeException =>
        Left(JsonRpcError.NodeNotFound)
      }

  /** eth_createAccessList (go-ethereum `internal/ethapi` `AccessList`).
    *
    * Runs the message with an [[AccessListTracer]] and again with the list it collected, until a run collects exactly
    * the list it ran with; returns that list, the gas the last run used and its VM error. The tracer leaves out the
    * sender, the recipient (for a creation, the address `from` + nonce creates, nonce defaulting to the account's) and
    * the precompiles active at the block, and starts from the caller's accessList when one is given.
    *
    * The state overrides follow geth in a way that differs from eth_call: `AccessList` applies them with no precompiles
    * at all (`Apply(db, nil)`, api.go:1366), and so does this. Every `movePrecompileToAddress` is `account <address> is
    * not a precompile`, even for a precompile the block has; no entry deletes a precompile, and the run keeps all of
    * them. The other overrides apply as in eth_call. (The tracer's exclusions are the block's precompiles all the same:
    * geth reads `vm.ActivePrecompiles(rules)` for them.)
    *
    * Differences from geth, both deliberate:
    *   - no fee defaults: when the call names any fee field geth's `setFeeDefaults` fills the missing ones from its
    *     oracle; fukuii runs with the caller's fees (absent ones 0). With no fee field both run at price 0, which
    *     execution-apis `create-al-unfunded-sender` requires;
    *   - the loop is bounded at [[MaxAccessListIterations]] runs (geth loops until it converges) and says so if it does
    *     not converge.
    */
  def createAccessList(req: CreateAccessListRequest): ServiceResponse[CreateAccessListResponse] =
    StateRead
      .reading {
        for
          _ <- req.tx.validateForCall.left.map(_.toJsonRpcError)
          resolved <- resolveBlock(req.block)
          header = resolved.block.header
          base = resolved.pendingState.getOrElse(stxLedger.worldAt(header))
          // geth applies the overrides straight after loading the state, before reading the sender's nonce, and with no
          // precompiles (`Apply(db, nil)`, api.go:1366): no move is possible and no precompile is deleted, so the
          // relocations that come back are always empty
          overridden <- req.stateOverrides.fold[Either[JsonRpcError, (InMemoryWorldStateProxy, Map[Address, Address])]](
            Right((base, Map.empty[Address, Address]))
          )(
            EthSimulateService.applyStateOverrides(base, _, Set.empty, blockchainConfig.accountStartNonce)
          )
          (world, relocations) = overridden
          gas = CallTxBuilder.callGas(req.tx, gasCap)
          first <- CallTxBuilder.build(req.tx, header, None, defaultFrom, gas, relocations, false)(using
            blockchainConfig
          )
          sender = first.stx.senderAddress
          excluded = accessListExclusions(req.tx, sender, first.header, world)
          result <- accessListFixedPoint(req.tx, header, gas, world, relocations, excluded)
        yield result
      }
      .recover { case _: MissingNodeException =>
        Left(JsonRpcError.NodeNotFound)
      }

  private def accessListFixedPoint(
      args: TxArgs,
      header: BlockHeader,
      gas: BigInt,
      world: InMemoryWorldStateProxy,
      relocations: Map[Address, Address],
      excluded: Set[Address]
  ): Either[JsonRpcError, CreateAccessListResponse] =
    @annotation.tailrec
    def loop(prev: AccessListTracer, iteration: Int): Either[JsonRpcError, CreateAccessListResponse] =
      val accessList = prev.accessList
      val withList = args.copy(accessList = Some(accessList))
      val tracer = new AccessListTracer(accessList, excluded)
      val step = for
        // geth AccessList: no SkipExecutionGasCapCheck
        msg <- CallTxBuilder.build(withList, header, None, defaultFrom, gas, relocations, false)(using blockchainConfig)
        r <- stxLedger
          .simulateCall(msg.stx, msg.header, world, msg.options, Some(tracer))
          .left
          .map(e => JsonRpcError.LogicError(s"failed to apply transaction: ${msg.stx.tx.hash.toHex} err: ${e.message}"))
      yield (msg, r)
      step match
        case Left(e) => Left(e)
        case Right((msg, outcome)) if tracer.touchedSet == prev.touchedSet =>
          val r = outcome.txResult
          // geth reports res.UsedGas, which is never below the calldata floor (EIP-7623 / EIP-7976)
          val gasUsed = stxLedger.callFloorGas(msg.stx, msg.header).fold(r.gasUsed)(r.gasUsed.max)
          // geth's vmerr, a pre-Homestead creation short of its code deposit included (ErrCodeStoreOutOfGas)
          val vmError = r.vmError.orElse(
            Option.when(outcome.codeDepositShortfall)(com.chipprbots.ethereum.vm.CodeStoreOutOfGas)
          )
          Right(CreateAccessListResponse(accessList, gasUsed, vmError.map(CallErrors.vmErrorMessage)))
        case Right(_) if iteration + 1 >= MaxAccessListIterations =>
          Left(JsonRpcError.LogicError(s"access list did not converge after $MaxAccessListIterations runs"))
        case Right(_) => loop(tracer, iteration + 1)
    loop(new AccessListTracer(args.accessList.getOrElse(Nil), excluded), 0)

  /** geth `AccessList`: the sender, the recipient (or the created contract), the active precompiles and the authorities
    * of the call's authorization list.
    */
  private def accessListExclusions(
      args: TxArgs,
      sender: Address,
      header: BlockHeader,
      world: InMemoryWorldStateProxy
  ): Set[Address] =
    val recipient = args.to.getOrElse {
      val nonce = args.nonce.getOrElse(
        world.getAccount(sender).map(_.nonce.toBigInt).getOrElse(blockchainConfig.accountStartNonce.toBigInt)
      )
      CallErrors.createAddress(sender, nonce)
    }
    val authorities = args.authorizationList.getOrElse(Nil).flatMap(stxLedger.recoverAuthority)
    Set(sender, recipient) ++ EthSimulateService.activePrecompiles(header)(using blockchainConfig) ++ authorities

  /** Resolve the block, apply the overrides and build the message, with the world the call runs on: the pending
    * block's, or the resolved block's state with the state overrides applied. The world is taken from the resolved
    * block's own header; the message's header, which the call runs under, is not the header of any block.
    */
  private def prepareCall(
      req: CallRequest,
      gas: BigInt,
      skipExecutionGasCap: Boolean
  ): Either[JsonRpcError, (CallTxBuilder.CallMessage, InMemoryWorldStateProxy)] =
    resolveBlock(req.block).flatMap(prepareCall(req, _, gas, skipExecutionGasCap))

  private def prepareCall(
      req: CallRequest,
      resolved: ResolvedBlock,
      gas: BigInt,
      skipExecutionGasCap: Boolean
  ): Either[JsonRpcError, (CallTxBuilder.CallMessage, InMemoryWorldStateProxy)] =
    val header = resolved.block.header
    for
      // GraphQL builds TxArgs itself; the JSON-RPC decoder has already run this (decodeForCall)
      _ <- req.tx.validateForCall.left.map(_.toJsonRpcError)
      overridden <- req.stateOverrides match
        case None => Right((resolved.pendingState, Map.empty[Address, Address]))
        case Some(overrides) =>
          val base = resolved.pendingState.getOrElse(stxLedger.worldAt(header))
          // geth builds the precompile rules from the block context after the block overrides (api.go:758-769)
          val active = EthSimulateService.activePrecompiles(
            CallTxBuilder.applyBlockOverrides(header, req.blockOverrides)
          )(using blockchainConfig)
          EthSimulateService
            .applyStateOverrides(base, overrides, active, blockchainConfig.accountStartNonce)
            .map { case (w, relocations) => (Some(w), relocations) }
      (world, relocations) = overridden
      msg <- CallTxBuilder.build(
        req.tx,
        header,
        req.blockOverrides,
        defaultFrom,
        gas,
        relocations,
        skipExecutionGasCap
      )(using
        blockchainConfig
      )
    yield (msg, world.getOrElse(stxLedger.callWorld(stateOf = header, executing = msg.header)))

  /** `from` when the call names none: the node's first account, else the zero address (fukuii behaviour inherited from
    * Mantis; go-ethereum always uses the zero address).
    */
  private def defaultFrom: Address =
    keyStore.listAccounts.getOrElse(Nil).headOption.getOrElse(Address(0))

  /** eth_call's message for a call refused before execution: go-ethereum `applyMessageWithEVM` (internal/ethapi/
    * api.go:838-840) wraps whatever `core.ApplyMessage` returns as `err: %w (supplied gas %d)`, the gas being the
    * message's gas limit after CallDefaults. Only eth_call (and GraphQL's `call`, which reports no message) goes
    * through it; eth_createAccessList and eth_estimateGas word their own.
    */
  private def callPreCheckError(suppliedGas: BigInt)(e: StxLedger.CallPreCheckError): JsonRpcError =
    JsonRpcError.LogicError(s"err: ${e.message} (supplied gas $suppliedGas)")

  private def revertError(data: ByteString): JsonRpcError =
    val dataHex = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(data.toArray[Byte])
    JsonRpcError(3, "execution reverted", Some(org.json4s.JString(dataHex)))

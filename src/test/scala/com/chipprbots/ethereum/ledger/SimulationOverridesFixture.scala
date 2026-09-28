package com.chipprbots.ethereum.ledger

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.BigIntegers
import org.bouncycastle.util.encoders.Hex
import org.json4s.JNothing
import org.json4s.JValue
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.jsonrpc.EthSimulateService
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.ExecutionTracer
import com.chipprbots.ethereum.vm.ProgramError

/** What [[SimulationOverridesSpec]] and [[SimulationOverridesIsolationSpec]] share: the chains, contracts and
  * transactions that exercise eth_simulateV1's per-call overrides, and a way to hold a simulate call mid-flight.
  *
  * The overrides, as `EthSimulateService` hands them to [[BlockPreparator.executeTransactionForSimulation]]:
  *   - `precompileRelocations` (`movePrecompileToAddress`): here ecrecover moves from 0x01 to [[MovedEcrecover]];
  *   - `traceTransfers`: every value-bearing CALL also leaves a synthetic Transfer log from 0xeeee…eeee;
  *   - `blobBaseFeeOverride` (`blockOverrides.blobBaseFee`): the blob gas price the sender pays.
  *
  * Each one changes what a transaction does, so none of them may apply outside the simulate call that set it.
  *
  * Everything runs on `setup.prep`, which is `mining.blockPreparator`: as on a node, one instance shared by every path
  * that executes transactions.
  */
// scalastyle:off magic.number
trait SimulationOverridesFixture extends AmsterdamFixtureVectors with Matchers:

  // ── Chains ──────────────────────────────────────────────────────────────────

  /** One chain the properties are checked on. `blobs`: whether it has EIP-4844 blob transactions. */
  final case class Chain(name: String, config: BlockchainConfig, header: BlockHeader, blobs: Boolean):
    def isEth: Boolean = config.networkType == NetworkType.ETH

  /** ETH as mainnet and Sepolia run it: Osaka, activated by timestamp (fixture schedule, Osaka at 180). */
  val EthOsaka: Chain = Chain("ETH Osaka", preAmsterdamConfig, preAmsterdamHeader(46, 180), blobs = true)

  /** ETC mainnet exactly as shipped (`etc-chain.conf`), 46 blocks past Spiral. Olympia is deferred, so these are the
    * rules ETC runs.
    */
  val EtcMainnet: Chain =
    val config = Config.blockchains.blockchains("etc")
    val header = Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(config.forkBlockNumbers.spiralBlockNumber + 46),
      gasLimit = GasAmount(8_000_000),
      gasUsed = GasAmount.Zero,
      beneficiary = Address(0xcafe).bytes,
      extraFields = HefEmpty
    )
    require(
      config.networkType == NetworkType.ETC && config.chainId.value == 61 &&
        header.number.value < config.forkBlockNumbers.olympiaBlockNumber,
      "expected the shipped ETC mainnet config, chain id 61, with Olympia not active at the test block"
    )
    Chain("ETC mainnet (Spiral, Olympia deferred)", config, header, blobs = false)

  val Chains: Seq[Chain] = Seq(EtcMainnet, EthOsaka)

  // ── ecrecover ───────────────────────────────────────────────────────────────

  /** The ecrecover precompile, on every chain here, and where the simulate calls move it. */
  val Ecrecover: Address = Address(1)
  val MovedEcrecover: Address = Address(0x1d00)

  private def word(value: BigInt): ByteString = ByteString(BigIntegers.asUnsignedByteArray(32, value.bigInteger))
  private def word(address: Address): ByteString = ByteString(Array.fill[Byte](12)(0)) ++ address.bytes

  val ZeroWord: ByteString = word(BigInt(0))

  /** A fixed signer, so ecrecover's answer is the same on every run. */
  private val signerKeyPair =
    keyPairFromPrvKey(BigInt("4646464646464646464646464646464646464646464646464646464646464646", 16))

  /** ecrecover's 128-byte input — hash, v, r, s — for a signature by the fixed signer. */
  val EcrecoverInput: ByteString =
    val hash = kec256("fukuii eth_simulateV1 overrides".getBytes("US-ASCII"))
    val signature = ECDSASignature.sign(hash, signerKeyPair)
    ByteString(hash) ++ word(signature.v) ++ word(signature.r) ++ word(signature.s)

  /** What ecrecover returns for [[EcrecoverInput]]: the signer's address, left-padded to a word. */
  val RecoveredWord: ByteString = word(Address(signerKeyPair))

  // ── Accounts and code ───────────────────────────────────────────────────────

  /** Receives the 1-wei transfers. It exists (balance 1), so a transfer to it never pays for creating an account. */
  val Sink: Address = Address(0x5117)

  /** Runs [[ExerciseCode]]. */
  val Exerciser: Address = Address(0xe8e7c)

  /** Reads `BLOCKHASH(NUMBER - 1)`, then runs [[ExerciseCode]]. The BLOCKHASH is where a simulate call can be held with
    * its transaction under way and every override already applied ([[parkingInBlockHash]]).
    */
  val Pauser: Address = Address(0x9a05e)

  private def push20(address: Address): String = "73" + Hex.toHexString(address.bytes.toArray)

  /** An inner value transfer and two ecrecover calls, whose answers it returns:
    *   - `CALL(gas, Sink, 1 wei)`;
    *   - `CALLDATACOPY(0, 0, 128)`: the ecrecover input;
    *   - `STATICCALL(gas, 0x01, mem[0,128) -> mem[128,160))`;
    *   - `STATICCALL(gas, MovedEcrecover, mem[0,128) -> mem[160,192))`;
    *   - `RETURN(mem[128,192))`.
    *
    * Without the relocation it returns [[RecoveredWord]] then 32 zero bytes (MovedEcrecover is an empty account); under
    * it, the reverse. The CALL is the value transfer `traceTransfers` turns into a synthetic log.
    */
  val ExerciseCode: String = Seq(
    // CALL(gas, Sink, 1, 0, 0, 0, 0); POP
    "6000600060006000" + "6001" + push20(Sink) + "5a" + "f1" + "50",
    // CALLDATACOPY(0, 0, 128)
    "6080" + "6000" + "6000" + "37",
    // STATICCALL(gas, 0x01, 0, 128, 128, 32); POP
    "6020" + "6080" + "6080" + "6000" + "6001" + "5a" + "fa" + "50",
    // STATICCALL(gas, MovedEcrecover, 0, 128, 160, 32); POP
    "6020" + "60a0" + "6080" + "6000" + push20(MovedEcrecover) + "5a" + "fa" + "50",
    // RETURN(128, 64)
    "6040" + "6080" + "f3"
  ).mkString

  /** `BLOCKHASH(NUMBER - 1)`; POP; then [[ExerciseCode]]. */
  val PauserCode: String = "6001" + "43" + "03" + "40" + "50" + ExerciseCode

  /** What the exercise returns without the relocation, and under it. */
  val ExercisedPlain: ByteString = RecoveredWord ++ ZeroWord
  val ExercisedRelocated: ByteString = ZeroWord ++ RecoveredWord

  val SenderBalance: BigInt = BigInt(10).pow(21)

  private def contract(world: InMemoryWorldStateProxy, address: Address, hex: String): InMemoryWorldStateProxy =
    val code = ByteString(Hex.decode(hex))
    // The code hash is set up front, as AmsterdamFixtureVectors does: `saveCode` fills only the side map, and an empty
    // code hash would make the contract look dead to a caller.
    world
      .saveAccount(address, Account(balance = UInt256(1000), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)

  /** The state every execution here starts from: the funded sender, the two contracts (1,000 wei each, for their
    * transfers) and the sink. Persisted, so it has a root that a fresh world can be opened on.
    */
  def preState: InMemoryWorldStateProxy =
    val funded = setup.emptyWorld
      .saveAccount(senderAddress, Account(balance = UInt256(SenderBalance)))
      .saveAccount(Sink, Account(balance = UInt256(1)))
    InMemoryWorldStateProxy.persistState(contract(contract(funded, Pauser, PauserCode), Exerciser, ExerciseCode))

  // ── Transactions ────────────────────────────────────────────────────────────

  /** DATA_GAS_PER_BLOB: the blob gas of one blob. */
  val BlobGas: BigInt = 131072

  private val OneBlob: List[BlobVersionedHash] =
    List(BlobVersionedHash(ByteString(0x01.toByte +: Array.fill[Byte](31)(0x07.toByte))))

  private def signed(chain: Chain, tx: Transaction): SignedTransaction =
    SignedTransaction.sign(tx, senderKeyPair, Some(chain.config.chainId.value))

  /** A call as the chain carries one: type 2 on ETH, legacy on ETC. */
  def callTx(chain: Chain, to: Address, payload: ByteString, nonce: BigInt = 0): SignedTransaction =
    if chain.isEth then
      signed(
        chain,
        TransactionWithDynamicFee(
          chainId = chain.config.chainId.value,
          nonce = nonce,
          maxPriorityFeePerGas = BigInt(0),
          maxFeePerGas = BigInt(1_000_000_000),
          gasLimit = GasAmount(500_000),
          receivingAddress = Some(to),
          value = BigInt(0),
          payload = payload,
          accessList = Nil
        )
      )
    else
      signed(chain, LegacyTransaction(nonce, GasPrice(1_000_000_000), GasAmount(500_000), Some(to), BigInt(0), payload))

  /** A type-3 call carrying one blob. ETH only. */
  def blobCallTx(chain: Chain, to: Address, payload: ByteString, nonce: BigInt = 0): SignedTransaction =
    signed(
      chain,
      BlobTransaction(
        chainId = chain.config.chainId.value,
        nonce = nonce,
        maxPriorityFeePerGas = BigInt(0),
        maxFeePerGas = BigInt(1_000_000_000),
        gasLimit = GasAmount(500_000),
        receivingAddress = Some(to),
        value = BigInt(0),
        payload = payload,
        accessList = Nil,
        maxFeePerBlobGas = BigInt(10).pow(12),
        blobVersionedHashes = OneBlob
      )
    )

  /** The inner value transfer and the inner ecrecover call. */
  def exerciseTx(chain: Chain, nonce: BigInt = 0): SignedTransaction = callTx(chain, Exerciser, EcrecoverInput, nonce)

  /** A top-level call to ecrecover. */
  def ecrecoverTx(chain: Chain, nonce: BigInt = 0): SignedTransaction = callTx(chain, Ecrecover, EcrecoverInput, nonce)

  /** The gas price the sender pays: EIP-1559's effective price on ETH (base fee 7 here), the gas price on ETC. */
  def gasPrice(chain: Chain, stx: SignedTransaction): BigInt =
    Transaction.effectiveGasPrice(stx.tx, chain.header.baseFee)

  /** The blob gas price the header derives (its excessBlobGas is 0, so the EIP-4844 minimum, 1). */
  def headerBlobFee(chain: Chain): BigInt =
    BlobGasUtils.getBlobGasPrice(
      chain.header.excessBlobGas.getOrElse(BigInt(0)),
      chain.header.unixTimestamp,
      chain.config
    )

  // ── eth_simulateV1 ──────────────────────────────────────────────────────────

  val Relocation: Map[Address, Address] = Map(Ecrecover -> MovedEcrecover)
  val BlobFeeOverride: BigInt = 1_000_000

  /** eth_simulateV1's call into the shared preparator with every override on. */
  def simulateWithOverrides(chain: Chain, stx: SignedTransaction, world: InMemoryWorldStateProxy): TxResult =
    setup.prep.executeTransactionForSimulation(
      stx,
      senderAddress,
      chain.header,
      world,
      precompileRelocations = Relocation,
      traceTransfers = true,
      blobBaseFeeOverride = Some(BlobFeeOverride)
    )(chain.config)

  /** eth_simulateV1's call into the shared preparator for a request that sets none. */
  def simulateWithoutOverrides(chain: Chain, stx: SignedTransaction, world: InMemoryWorldStateProxy): TxResult =
    setup.prep.executeTransactionForSimulation(stx, senderAddress, chain.header, world)(chain.config)

  /** The held simulate call's own transaction: a call to the [[Pauser]], carrying a blob on ETH so its fee is in play.
    */
  def simulateTx(chain: Chain): SignedTransaction =
    if chain.blobs then blobCallTx(chain, Pauser, EcrecoverInput) else callTx(chain, Pauser, EcrecoverInput)

  /** The log `traceTransfers` makes of a 1-wei CALL from `from` to [[Sink]] (the CALL opcode's synthetic log). */
  def syntheticTransfer(from: Address): TxLogEntry =
    TxLogEntry(EthSimulateService.EthTransferAddress, Seq(TransferTopic, word(from), word(Sink)), word(BigInt(1)))

  def syntheticLogs(logs: Seq[TxLogEntry]): Seq[TxLogEntry] =
    logs.filter(_.loggerAddress == EthSimulateService.EthTransferAddress)

  /** A [[simulateTx]] call with every override on ran its transaction under every one of them. */
  def shouldCarryEveryOverride(chain: Chain, result: TxResult): Unit =
    withClue("the relocation (ecrecover must have answered at MovedEcrecover and not at 0x01): ") {
      result.vmReturnData shouldBe ExercisedRelocated
    }
    withClue("traceTransfers (the 1-wei CALL must have left its synthetic log): ") {
      syntheticLogs(result.logs) shouldBe Seq(syntheticTransfer(Pauser))
    }
    if chain.blobs then
      withClue("the blob fee override (the blob must have been priced at the override): ") {
        result.worldState.getBalance(senderAddress).toBigInt shouldBe
          SenderBalance - result.gasUsed * gasPrice(chain, simulateTx(chain)) - BlobGas * BlobFeeOverride
      }

  // ── What each path produces ─────────────────────────────────────────────────

  /** Everything block import takes from one executed transaction: its receipt's inputs and the state it leaves. */
  final case class TxOutcome(
      gasUsed: BigInt,
      executionGasUsed: BigInt,
      stateGasUsed: BigInt,
      logs: Seq[TxLogEntry],
      returnData: ByteString,
      error: Option[ProgramError],
      stateRoot: ByteString
  )

  def outcome(result: TxResult): TxOutcome =
    TxOutcome(
      result.gasUsed,
      result.executionGasUsed,
      result.stateGasUsed,
      result.logs,
      result.vmReturnData,
      result.vmError,
      result.worldState.stateRootHash
    )

  /** The block import executes. ETC: an inner value transfer with an inner ecrecover call, and a top-level ecrecover
    * call. ETH adds a blob transaction.
    */
  def importedTxs(chain: Chain): Seq[SignedTransaction] =
    Seq(exerciseTx(chain, nonce = 0), ecrecoverTx(chain, nonce = 1)) ++
      (if chain.blobs then Seq(blobCallTx(chain, Sink, ByteString.empty, nonce = 2)) else Nil)

  /** What a block's execution commits to: receipts, the header's gasUsed and its two dimensions, every log, and the
    * post-state root.
    */
  final case class BlockOutcome(
      stateRoot: ByteString,
      receipts: Seq[Receipt],
      gasUsed: BigInt,
      executionGasUsed: BigInt,
      stateGasUsed: BigInt,
      logs: Seq[TxLogEntry]
  )

  /** Block import's transaction loop (`BlockExecution` -> `BlockPreparator.executeTransactions`) on the shared
    * preparator.
    */
  def importBlock(chain: Chain): BlockOutcome =
    setup.prep.executeTransactions(importedTxs(chain), preState, chain.header)(chain.config) match
      case Right(result) =>
        BlockOutcome(
          result.worldState.stateRootHash,
          result.receipts,
          result.gasUsed,
          result.executionGasUsed,
          result.stateGasUsed,
          result.receipts.flatMap(_.logs)
        )
      case Left(error) => fail(s"block execution failed: ${error.reason}")

  /** What eth_call, eth_estimateGas and the debug traces read back from the VM. */
  final case class RpcOutcome(
      returnData: ByteString,
      gasRemaining: BigInt,
      logs: Seq[TxLogEntry],
      error: Option[ProgramError]
  )

  /** A tracer that records nothing: the debug path's plumbing, with no output of its own. */
  final private class SilentTracer extends ExecutionTracer:
    def getResult: JValue = JNothing

  private def rpc(chain: Chain, traced: Boolean): RpcOutcome =
    val stx = exerciseTx(chain)
    // As StxLedger prepares a call: the sender pays upfront, then the frame runs.
    val world = setup.prep.updateSenderAccountBeforeExecution(stx, senderAddress, preState)
    val result =
      if traced then setup.prep.runVMWithTracer(stx, senderAddress, chain.header, world, new SilentTracer)(chain.config)
      else setup.prep.runVM(stx, senderAddress, chain.header, world)(chain.config)
    RpcOutcome(result.returnData, result.gasRemaining, result.logs, result.error)

  /** eth_call and eth_estimateGas: StxLedger.simulateTransaction -> BlockPreparator.runVM. */
  def ethCall(chain: Chain): RpcOutcome = rpc(chain, traced = false)

  /** debug_traceCall and trace_call: StxLedger.simulateTransactionWithTracer -> BlockPreparator.runVMWithTracer. */
  def debugTrace(chain: Chain): RpcOutcome = rpc(chain, traced = true)

  // ── Holding a simulate call mid-flight ──────────────────────────────────────

  /** How long either thread waits for the other. Only a broken test ever waits this long. */
  val TimeoutSeconds: Long = 60

  /** One point inside a simulate call where it can be held, once, while the test runs something else.
    *
    * Only the thread that armed it is held, and only the first time it arrives. Two latches order the threads; nothing
    * sleeps, and the interleaving is the same on every run.
    */
  final class Park:
    private val armedFor = new AtomicReference[Thread](null)
    private val held = new AtomicBoolean(false)
    private val arrived = new CountDownLatch(1)
    private val released = new CountDownLatch(1)

    /** The holding point, called from inside the simulate call. */
    def here(): Unit =
      if armedFor.compareAndSet(Thread.currentThread(), null) then
        held.set(true)
        arrived.countDown()
        if !released.await(TimeoutSeconds, TimeUnit.SECONDS) then
          throw new IllegalStateException("the held simulate call was never released")

    /** Runs `simulate` on a thread of its own and holds it at [[here]]; runs `meanwhile` on this thread while it is
      * held; then lets it finish. Returns both results.
      */
    def whileHeld[A](simulate: => TxResult)(meanwhile: => A): (TxResult, A) =
      val executor = Executors.newSingleThreadExecutor()
      try
        val task: Callable[TxResult] = () =>
          armedFor.set(Thread.currentThread())
          try simulate
          finally arrived.countDown() // frees the wait below if the call ends without ever being held
        val simulated = executor.submit(task)
        if !arrived.await(TimeoutSeconds, TimeUnit.SECONDS) then fail("the simulate call never started")
        val observed =
          try meanwhile
          finally released.countDown()
        val result = simulated.get(TimeoutSeconds, TimeUnit.SECONDS)
        withClue("the simulate call was never held, so nothing ran beside it: ") {
          held.get() shouldBe true
        }
        (result, observed)
      finally executor.shutdownNow()

  /** `world` with its BLOCKHASH source replaced by `hashes`; nothing else differs. */
  def withBlockHashes(world: InMemoryWorldStateProxy)(hashes: BigInt => Option[ByteString]): InMemoryWorldStateProxy =
    new InMemoryWorldStateProxy(
      world.stateStorage,
      world.accountsStateTrie,
      world.contractStorages,
      world.evmCodeStorage,
      world.accountCodes,
      hashes,
      world.accountStartNonce,
      world.touchedAccounts,
      world.noEmptyAccountsCond,
      world.ethCompatibleStorage,
      world.flatSlotStorage
    )

  /** `world`, holding a [[Pauser]] call at its BLOCKHASH: the transaction is under way and its top-level context built,
    * so every override the call carries has been applied.
    */
  def parkingInBlockHash(world: InMemoryWorldStateProxy, park: Park): InMemoryWorldStateProxy =
    withBlockHashes(world) { _ =>
      park.here()
      None
    }

  /** A fresh world on `world`'s root, holding the call at its first trie read: the sender's account, the first thing a
    * transaction reads. By then the preparator has only looked at the transaction; no override has been applied yet.
    */
  def parkingAtFirstStateRead(world: InMemoryWorldStateProxy, park: Park): InMemoryWorldStateProxy =
    val underlying = world.stateStorage
    val gated = new MptStorage:
      def get(nodeId: Array[Byte]): MptNode =
        park.here()
        underlying.get(nodeId)
      def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
        underlying.updateNodesInStorage(newRoot, toRemove)
      def persist(): Unit = underlying.persist()
    InMemoryWorldStateProxy(
      world.evmCodeStorage,
      gated,
      world.getBlockByNumber,
      world.accountStartNonce,
      world.stateRootHash,
      world.noEmptyAccountsCond,
      world.ethCompatibleStorage,
      world.flatSlotStorage
    )

package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.JString
import org.json4s.jvalue2monadic
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.SetCodeTransaction.addressToDelegation
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp.PrefixedRLPEncodable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.encode
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.vm.CallTracer
import com.chipprbots.ethereum.vm.PrestateTracer

/** WI-14 (#1430): the trace and debug replays of a stored Amsterdam block re-execute it as block import did.
  *
  * They ran each transaction on the bare VM: no EIP-7702 authorization list (so no delegation and none of EIP-2780's
  * authorization charges), no calldata floor, no refund, no coinbase fee, no EIP-8246 / EIP-161 settlement, and no
  * EIP-4788 / EIP-2935 preamble. So a replay reported other gas than the receipt, and every later transaction in the
  * block replayed on the wrong state. An Amsterdam replay now runs the preamble and
  * `BlockPreparator.executeTransaction` with the tracer attached.
  *
  * The block below exercises each piece: a transaction whose gas is its EIP-7976 floor, a Type-4 transaction whose
  * authorization is charged and applied, an EIP-2780 self-transfer, and a call that creates a storage slot (EIP-8037
  * state gas) and leaves gas unspent (a refund to the sender). The parent state holds the canonical EIP-4788 and
  * EIP-2935 contracts, so the preamble writes storage. Each replay is checked against block execution: gas per
  * transaction, the gas the tracer reports, and the state root after every transaction.
  *
  * A block before Amsterdam, and an ETC block, replays exactly as before: `replayTransaction` is the bare VM of
  * `simulateTransaction`, result for result.
  */
// scalastyle:off magic.number
class AmsterdamTraceReplaySpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val payee: Address = Address(Hex.decode("83c7e323d189f18725ac510004fdc2941f8c4a78"))
  private val Stop: Address = Address(0x5700)
  private val zero32: ByteString = ByteString(Array.fill[Byte](32)(0))
  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  /** Block 46 at 460, with a non-zero parent beacon block root so the EIP-4788 call writes it. */
  private val header: BlockHeader = amsterdamHeader(46, 460).copy(
    extraFields = HefPostAmsterdam(
      baseFee = BigInt(7),
      withdrawalsRoot = emptyRoot,
      blobGasUsed = 0,
      excessBlobGas = 0,
      parentBeaconBlockRoot = ByteString(Array.fill[Byte](32)(0x42)),
      requestsHash = zero32,
      blockAccessListHash = BlockAccessList.EmptyHash,
      slotNumber = 46
    )
  )

  private val authority: AsymmetricCipherKeyPair = generateKeyPair(setup.secureRandom)

  private def authorization(keys: AsymmetricCipherKeyPair, target: Address, nonce: BigInt): SetCodeAuthorization =
    val chainId = amsterdamConfig.chainId.value
    val sigHash = kec256(
      encode(
        PrefixedRLPEncodable(0x05, RLPList(toEncodeable(chainId), toEncodeable(target.toArray), toEncodeable(nonce)))
      )
    )
    val sig = ECDSASignature.sign(sigHash, keys)
    val yParity = if sig.v == ECDSASignature.negativePointSign then BigInt(0) else BigInt(1)
    SetCodeAuthorization(chainId, target, nonce, yParity, sig.r, sig.s)

  private def setCodeTx(nonce: BigInt, to: Address, auths: List[SetCodeAuthorization], gasLimit: BigInt) =
    SignedTransaction.sign(
      SetCodeTransaction(
        chainId = amsterdamConfig.chainId.value,
        nonce = nonce,
        maxPriorityFeePerGas = BigInt(1),
        maxFeePerGas = BigInt(1_000_000_000),
        gasLimit = GasAmount(gasLimit),
        receivingAddress = Some(to),
        value = 0,
        payload = ByteString.empty,
        accessList = Nil,
        authorizationList = auths
      ),
      senderKeyPair,
      Some(amsterdamConfig.chainId.value)
    )

  /** The block's transactions, all from `senderAddress`. */
  private val txs: Seq[SignedTransaction] = Seq(
    // EIP-7976: 1,000 non-zero bytes to an EOA, gas exactly its 79,000 floor (intrinsic 31,000).
    legacyTx(Some(payee), value = 0, gasLimit = 79000, payload = nonZeros(1000), nonce = 0, config = amsterdamConfig),
    // EIP-7702 under EIP-2780: delegates a fresh authority to `Stop`, charged NEW_ACCOUNT + ACCOUNT_WRITE + AUTH_BASE.
    setCodeTx(nonce = 1, to = Stop, auths = List(authorization(authority, Stop, 0)), gasLimit = 500000),
    // EIP-2780: a self-transfer, 12,000.
    legacyTx(Some(senderAddress), value = 1, gasLimit = 12000, nonce = 2, config = amsterdamConfig),
    // EIP-8037: `emit` creates a storage slot (state gas), with gas to spare (refunded to the sender).
    legacyTx(Some(Emit), value = 0, gasLimit = 500000, payload = nonZeros(4), nonce = 3, config = amsterdamConfig)
  )

  private class Chain(config: BlockchainConfig) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))
    override lazy val blockValidation: BlockValidation =
      new BlockValidation(mining.withValidators(MockValidatorsAlwaysSucceed), blockchainReader, blockQueue)

    lazy val exec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )

    lazy val ledger: StxLedger =
      new StxLedger(
        blockchain,
        blockchainReader,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        this
      )

    private def withCode(
        world: InMemoryWorldStateProxy,
        address: Address,
        code: ByteString,
        balance: BigInt = 0
    ): InMemoryWorldStateProxy =
      world
        .saveAccount(
          address,
          Account(nonce = UInt256(1), balance = UInt256(balance), codeHash = CodeHash(kec256(code)))
        )
        .saveCode(address, code)

    /** The parent block's state, persisted to the node storage that block execution and the replays both read. */
    val parent: InMemoryWorldStateProxy =
      val empty = InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
        UInt256.Zero,
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        noEmptyAccounts = true,
        ethCompatibleStorage = true
      )
      val funded = empty
        .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24))))
        .saveAccount(payee, Account(nonce = UInt256(0), balance = UInt256(1)))
      val contracts = Seq(
        BlockExecution.BeaconRootContractAddress -> BlockExecution.BeaconRootsCode,
        BlockExecution.HistoryStorageAddress -> BlockExecution.HistoryStorageCode,
        Stop -> ByteString(0x00)
      ).foldLeft(funded) { case (w, (address, code)) => withCode(w, address, code) }
      val emit = withCode(contracts, Emit, EmitCode, balance = 16)
      InMemoryWorldStateProxy.persistState(
        emit.saveStorage(Emit, emit.getStorage(Emit).store(UInt256(0), UInt256(8)))
      )

    def withSenders(stxs: Seq[SignedTransaction]): Seq[SignedTransactionWithSender] =
      stxs.map(SignedTransactionWithSender(_, senderAddress))

    /** Block execution of the block's first `n` transactions: the world after them, and their receipts. */
    def executed(blockHeader: BlockHeader, stxs: Seq[SignedTransaction]): BlockResult =
      exec
        .executeBlockTransactions(Block(blockHeader, BlockBody(stxs, Nil)), parent)
        .fold(err => fail(s"block execution failed: $err"), identity)

  private def gasPerTransaction(receipts: Seq[Receipt]): Seq[BigInt] =
    val cumulative = receipts.map(_.cumulativeGasUsed)
    cumulative.zip(BigInt(0) +: cumulative).map((after, before) => after - before)

  "Replaying an Amsterdam block" should "charge each transaction what block execution charged it" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain(amsterdamConfig):
    val expected = gasPerTransaction(executed(header, txs).receipts)
    expected.head shouldBe BigInt(79000) // the floor
    expected(2) shouldBe BigInt(12000) // the self-transfer

    val stxs = withSenders(txs)
    val replayed = stxs.indices.map { i =>
      val world = ledger.advanceWorldToTx(header, stxs, i, parent.stateRootHash)
      ledger.replayTransaction(stxs(i), header, world, tracer = None).gasUsed
    }
    replayed shouldBe expected

  it should "tell the tracer the gas the receipt reports" taggedAs (UnitTest, StateTest) in new Chain(amsterdamConfig):
    val expected = gasPerTransaction(executed(header, txs).receipts)
    val stxs = withSenders(txs)
    stxs.indices.foreach { i =>
      val tracer = new CallTracer(onlyTopCall = false)
      ledger.replayTransaction(
        stxs(i),
        header,
        ledger.advanceWorldToTx(header, stxs, i, parent.stateRootHash),
        Some(tracer)
      )
      tracer.getResult \ "gasUsed" shouldBe JString("0x" + expected(i).toString(16))
    }

  it should "reach block execution's state root after every transaction, preamble included" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain(amsterdamConfig):
    val stxs = withSenders(txs)
    // debug_intermediateRoots' way: the world before the first transaction, then one replay at a time.
    val roots = stxs
      .scanLeft(ledger.advanceWorldToTx(header, stxs, 0, parent.stateRootHash)) { (world, stx) =>
        ledger.replayTransaction(stx, header, world, tracer = None).worldState
      }
      .tail
      .map(_.stateRootHash)
    roots shouldBe (1 to txs.size).map(n => executed(header, txs.take(n)).worldState.stateRootHash)
    // advanceWorldToTx past the last transaction lands on the same root.
    ledger.advanceWorldToTx(header, stxs, stxs.size, parent.stateRootHash).stateRootHash shouldBe roots.last

  it should "run the EIP-4788 and EIP-2935 preamble before the first transaction" taggedAs (UnitTest, StateTest) in
    new Chain(amsterdamConfig):
      val world = ledger.advanceWorldToTx(header, withSenders(txs), 0, parent.stateRootHash)
      // timestamp 460 -> slots 460 and 460 + 8191; block 46 -> history slot 45.
      world.getStorage(BlockExecution.BeaconRootContractAddress).load(UInt256(460)) shouldBe BigInt(460)
      world.getStorage(BlockExecution.BeaconRootContractAddress).load(UInt256(460 + 8191)) shouldBe
        BigInt(1, Array.fill[Byte](32)(0x42))
      world.getStorage(BlockExecution.HistoryStorageAddress).load(UInt256(45)) shouldBe
        BigInt(1, header.parentHash.value.toArray)

  it should "apply the Type-4 transaction's delegation, which the bare VM never did" taggedAs (UnitTest, StateTest) in
    new Chain(amsterdamConfig):
      val stxs = withSenders(txs)
      val before = ledger.advanceWorldToTx(header, stxs, 1, parent.stateRootHash)
      ledger.replayTransaction(stxs(1), header, before, tracer = None).worldState.getCode(Address(authority)) shouldBe
        addressToDelegation(Stop)
      ledger.simulateTransaction(stxs(1), header, Some(before)).worldState.getCode(Address(authority)) shouldBe
        ByteString.empty

  it should "leave the pre-state readable after replaying on it, for prestateTracer and a second tracer" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain(amsterdamConfig):
    // The pre-state of the last transaction exists only in the replay's own node buffer: the preamble and the three
    // transactions before it wrote it. Each persist names the nodes it replaces for removal. Honouring that deleted the
    // root of the world a transaction ran on, so prestateTracer (which reads the pre-state after the run) and
    // trace_replay*'s vmTrace (a second run on the same pre-state) both failed with a missing trie node.
    val stxs = withSenders(txs)
    val before = ledger.advanceWorldToTx(header, stxs, 3, parent.stateRootHash)
    val prestate = new PrestateTracer[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](before)
    val first = ledger.replayTransaction(stxs(3), header, before, Some(prestate))

    // `emit` SLOADs slot 0, which held 8 before this transaction.
    val emitKey = "0x" + Hex.toHexString(Emit.bytes.toArray)
    prestate.getResult \ emitKey \ "storage" \ ("0x" + "0" * 64) shouldBe JString("0x" + "0" * 63 + "8")

    val second = ledger.replayTransaction(stxs(3), header, before, tracer = None)
    (second.gasUsed, second.worldState.stateRootHash) shouldBe (first.gasUsed, first.worldState.stateRootHash)

  it should "report the floor, which the bare VM left out" taggedAs (UnitTest, StateTest) in new Chain(amsterdamConfig):
    val stxs = withSenders(txs)
    val world = ledger.advanceWorldToTx(header, stxs, 0, parent.stateRootHash)
    ledger.replayTransaction(stxs.head, header, world, tracer = None).gasUsed shouldBe BigInt(79000)
    ledger.simulateTransaction(stxs.head, header, Some(world)).gasUsed shouldBe BigInt(31000)

  // ── Before Amsterdam, and ETC: the bare VM, as before ───────────────────────

  /** The same result, field by field: gas, logs, return data, error, and the world's state root. */
  private def sameResult(a: TxResult, b: TxResult) =
    (a.gasUsed, a.logs, a.vmReturnData, a.vmError, a.worldState.stateRootHash) shouldBe
      (b.gasUsed, b.logs, b.vmReturnData, b.vmError, b.worldState.stateRootHash)

  "Replaying a block before Amsterdam" should "be the bare VM, exactly as before" taggedAs (UnitTest, StateTest) in
    new Chain(amsterdamConfig):
      val osaka = preAmsterdamHeader(46, 300)
      val stxs = withSenders(txs.take(1) :+ txs(3))
      // No preamble before the first transaction, and each earlier one replayed on the bare VM.
      ledger.advanceWorldToTx(osaka, stxs, 0, parent.stateRootHash).stateRootHash shouldBe parent.stateRootHash
      stxs.foreach { stx =>
        sameResult(
          ledger.replayTransaction(stx, osaka, parent, tracer = None),
          ledger.simulateTransaction(stx, osaka, Some(parent))
        )
      }
      // The floor-bound call reports its execution gas, not the floor, as every pre-Amsterdam replay did.
      ledger.replayTransaction(stxs.head, osaka, parent, tracer = None).gasUsed shouldBe BigInt(37000)

  "Replaying an ETC block" should "be the bare VM, exactly as before" taggedAs (UnitTest, StateTest, OlympiaTest) in
    new Chain(Config.blockchains.blockchainConfig):
      val etcHeader = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header
      val stx = withSenders(
        Seq(
          legacyTx(Some(payee), value = 0, gasLimit = 79000, payload = nonZeros(1000), config = blockchainConfig)
        )
      ).head
      sameResult(
        ledger.replayTransaction(stx, etcHeader, parent, tracer = None),
        ledger.simulateTransaction(stx, etcHeader, Some(parent))
      )

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
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
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
  * A block before Amsterdam, and an ETC block, replays each transaction exactly as before: `replayTransaction` is the
  * bare VM of `simulateTransaction`, result for result. Since B-180 every replay starts after the block's preamble,
  * which import runs (`BlockExecution.applyBlockPreamble`): before Amsterdam the EIP-4788 / EIP-2935 direct writes.
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

  /** A node on `config`. The parent state holds the EIP-4788 and EIP-2935 contracts unless `systemContracts` is false,
    * and `funded` adds accounts with the balances given.
    */
  private class Chain(
      config: BlockchainConfig,
      systemContracts: Boolean = true,
      funded: Seq[(Address, BigInt)] = Nil
  ) extends EphemBlockchainTestSetup:
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
      val accounts = (Seq(senderAddress -> BigInt(10).pow(24), payee -> BigInt(1)) ++ funded).foldLeft(empty) {
        case (w, (address, balance)) => w.saveAccount(address, Account(nonce = UInt256(0), balance = UInt256(balance)))
      }
      val preambleContracts =
        if systemContracts then
          Seq(
            BlockExecution.BeaconRootContractAddress -> BlockExecution.BeaconRootsCode,
            BlockExecution.HistoryStorageAddress -> BlockExecution.HistoryStorageCode
          )
        else Nil
      val contracts = (preambleContracts :+ (Stop -> ByteString(0x00))).foldLeft(accounts) {
        case (w, (address, code)) => withCode(w, address, code)
      }
      val emit = withCode(contracts, Emit, EmitCode, balance = 16)
      InMemoryWorldStateProxy.persistStateAndWriteCode(
        emit.saveStorage(Emit, emit.getStorage(Emit).store(UInt256(0), UInt256(8)))
      )

    /** The parent block's header: a replay takes the parent's state by its header. */
    lazy val parentHeader: BlockHeader =
      header.copy(number = BlockNumber(header.number.value - 1), stateRoot = TrieRoot(parent.stateRootHash))

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
    val expected: Seq[BigInt] = gasPerTransaction(executed(header, txs).receipts)
    expected.head shouldBe BigInt(79000) // the floor
    expected(2) shouldBe BigInt(12000) // the self-transfer

    val stxs: Seq[SignedTransactionWithSender] = withSenders(txs)
    val replayed: IndexedSeq[BigInt] = stxs.indices.map { i =>
      val world = ledger.advanceWorldToTx(header, stxs, i, parentHeader)
      ledger.replayTransaction(stxs(i), header, world, tracer = None).gasUsed
    }
    replayed shouldBe expected

  it should "tell the tracer the gas the receipt reports" taggedAs (UnitTest, StateTest) in new Chain(amsterdamConfig):
    val expected: Seq[BigInt] = gasPerTransaction(executed(header, txs).receipts)
    val stxs: Seq[SignedTransactionWithSender] = withSenders(txs)
    stxs.indices.foreach { i =>
      val tracer = new CallTracer(onlyTopCall = false)
      ledger.replayTransaction(
        stxs(i),
        header,
        ledger.advanceWorldToTx(header, stxs, i, parentHeader),
        Some(tracer)
      )
      tracer.getResult \ "gasUsed" shouldBe JString("0x" + expected(i).toString(16))
    }

  it should "reach block execution's state root after every transaction, preamble included" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain(amsterdamConfig):
    val stxs: Seq[SignedTransactionWithSender] = withSenders(txs)
    // debug_intermediateRoots' way: the world before the first transaction, then one replay at a time.
    val roots: Seq[ByteString] = stxs
      .scanLeft(ledger.advanceWorldToTx(header, stxs, 0, parentHeader)) { (world, stx) =>
        ledger.replayTransaction(stx, header, world, tracer = None).worldState
      }
      .tail
      .map(_.stateRootHash)
    roots shouldBe (1 to txs.size).map(n => executed(header, txs.take(n)).worldState.stateRootHash)
    // advanceWorldToTx past the last transaction lands on the same root.
    ledger.advanceWorldToTx(header, stxs, stxs.size, parentHeader).stateRootHash shouldBe roots.last

  it should "run the EIP-4788 and EIP-2935 preamble before the first transaction" taggedAs (UnitTest, StateTest) in
    (new Chain(amsterdamConfig):
      val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(header, withSenders(txs), 0, parentHeader)
      // timestamp 460 -> slots 460 and 460 + 8191; block 46 -> history slot 45.
      world.getStorage(BlockExecution.BeaconRootContractAddress).load(UInt256(460)) shouldBe BigInt(460)
      world.getStorage(BlockExecution.BeaconRootContractAddress).load(UInt256(460 + 8191)) shouldBe
        BigInt(1, Array.fill[Byte](32)(0x42))
      world.getStorage(BlockExecution.HistoryStorageAddress).load(UInt256(45)) shouldBe
        BigInt(1, header.parentHash.value.toArray)
    )

  it should "apply the Type-4 transaction's delegation, which the bare VM never did" taggedAs (UnitTest, StateTest) in
    (new Chain(amsterdamConfig):
      val stxs: Seq[SignedTransactionWithSender] = withSenders(txs)
      val before: InMemoryWorldStateProxy = ledger.advanceWorldToTx(header, stxs, 1, parentHeader)
      ledger.replayTransaction(stxs(1), header, before, tracer = None).worldState.getCode(Address(authority)) shouldBe
        addressToDelegation(Stop)
      ledger.simulateTransaction(stxs(1), header, before).worldState.getCode(Address(authority)) shouldBe
        ByteString.empty
    )

  it should "leave the pre-state readable after replaying on it, for prestateTracer and a second tracer" taggedAs (
    UnitTest,
    StateTest
  ) in new Chain(amsterdamConfig):
    // The pre-state of the last transaction exists only in the replay's own node buffer: the preamble and the three
    // transactions before it wrote it. Each persist names the nodes it replaces for removal. Honouring that deleted the
    // root of the world a transaction ran on, so prestateTracer (which reads the pre-state after the run) and
    // trace_replay*'s vmTrace (a second run on the same pre-state) both failed with a missing trie node.
    val stxs: Seq[SignedTransactionWithSender] = withSenders(txs)
    val before: InMemoryWorldStateProxy = ledger.advanceWorldToTx(header, stxs, 3, parentHeader)
    val prestate = new PrestateTracer[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage](before)
    val first: TxResult = ledger.replayTransaction(stxs(3), header, before, Some(prestate))

    // `emit` SLOADs slot 0, which held 8 before this transaction.
    val emitKey: String = "0x" + Hex.toHexString(Emit.bytes.toArray)
    prestate.getResult \ emitKey \ "storage" \ ("0x" + "0" * 64) shouldBe JString("0x" + "0" * 63 + "8")

    val second: TxResult = ledger.replayTransaction(stxs(3), header, before, tracer = None)
    (second.gasUsed, second.worldState.stateRootHash) shouldBe (first.gasUsed, first.worldState.stateRootHash)

  it should "report the floor, which the bare VM left out" taggedAs (UnitTest, StateTest) in new Chain(amsterdamConfig):
    val stxs: Seq[SignedTransactionWithSender] = withSenders(txs)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(header, stxs, 0, parentHeader)
    ledger.replayTransaction(stxs.head, header, world, tracer = None).gasUsed shouldBe BigInt(79000)
    ledger.simulateTransaction(stxs.head, header, world).gasUsed shouldBe BigInt(31000)

  // ── Before Amsterdam, and ETC: the bare VM, as before ───────────────────────

  /** The same result, field by field: gas, logs, return data, error, and the world's state root. */
  private def sameResult(a: TxResult, b: TxResult) =
    (a.gasUsed, a.logs, a.vmReturnData, a.vmError, a.worldState.stateRootHash) shouldBe
      (b.gasUsed, b.logs, b.vmReturnData, b.vmError, b.worldState.stateRootHash)

  "Replaying a block before Amsterdam" should "replay each transaction on the bare VM, exactly as before" taggedAs (
    UnitTest,
    StateTest
  ) in (new Chain(amsterdamConfig):
    val osaka: BlockHeader = preAmsterdamHeader(46, 300)
    val stxs: Seq[SignedTransactionWithSender] = withSenders(txs.take(1) :+ txs(3))
    stxs.foreach { stx =>
      sameResult(
        ledger.replayTransaction(stx, osaka, parent, tracer = None),
        ledger.simulateTransaction(stx, osaka, parent)
      )
    }
    // The floor-bound call reports its execution gas, not the floor, as every pre-Amsterdam replay did.
    ledger.replayTransaction(stxs.head, osaka, parent, tracer = None).gasUsed shouldBe BigInt(37000)
  )

  // B-180 (was B-181): a replay before Amsterdam starts where block import starts its first transaction, after the
  // block's preamble (BlockExecution.applyBlockPreamble): the EIP-4788 and EIP-2935 direct storage writes. It used to
  // start on the parent's state, so a traced transaction that read either contract saw the parent's slots.
  it should "start from the parent's state with import's EIP-4788 / EIP-2935 preamble applied" taggedAs (
    UnitTest,
    StateTest
  ) in (new Chain(amsterdamConfig):
    val osaka: BlockHeader = preAmsterdamHeader(46, 300).copy(parentHash = header.parentHash)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(osaka, withSenders(txs), 0, parentHeader)
    // timestamp 300 -> slots 300 and 300 + 8191 (a zero root); block 46 -> history slot 45.
    world.getStorage(BlockExecution.BeaconRootContractAddress).load(UInt256(300)) shouldBe BigInt(300)
    world.getStorage(BlockExecution.HistoryStorageAddress).load(UInt256(45)) shouldBe
      BigInt(1, osaka.parentHash.value.toArray)
    parent.getStorage(BlockExecution.BeaconRootContractAddress).load(UInt256(300)) shouldBe BigInt(0)
    // the state import executes the block's first transaction on, to the byte
    InMemoryWorldStateProxy.persistState(world).stateRootHash shouldBe
      InMemoryWorldStateProxy.persistState(executed(osaka, Nil).worldState).stateRootHash
    InMemoryWorldStateProxy.persistState(world).stateRootHash should not be parent.stateRootHash
  )

  "Replaying an ETC block" should "be the bare VM, exactly as before" taggedAs (UnitTest, StateTest, OlympiaTest) in
    (new Chain(Config.blockchains.blockchainConfig):
      val etcHeader = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header
      val stx: SignedTransactionWithSender = withSenders(
        Seq(
          legacyTx(Some(payee), value = 0, gasLimit = 79000, payload = nonZeros(1000), config = blockchainConfig)
        )
      ).head
      sameResult(
        ledger.replayTransaction(stx, etcHeader, parent, tracer = None),
        ledger.simulateTransaction(stx, etcHeader, parent)
      )
    )

  // ── ETC (B-180): import's preamble, on ETC's block-number schedule ─────────

  private val etcMainnet: BlockchainConfig = Config.blockchains.blockchains("etc")
  private val ethMainnet: BlockchainConfig = Config.blockchains.blockchains("eth")
  private val OlympiaAt: Long = 25_000_000L
  private val etcOlympia: BlockchainConfig =
    etcMainnet.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BigInt(OlympiaAt)))
  private val DaoForkBlock: Long = 1_920_000L

  /** An ETC header (no base fee, no post-merge fields) at `number`, with the Amsterdam block's parent hash. */
  private def etcHeader(number: Long): BlockHeader =
    com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header
      .copy(number = BlockNumber(number), parentHash = header.parentHash, extraFields = HefEmpty)

  private def persistedRoot(world: InMemoryWorldStateProxy): ByteString =
    InMemoryWorldStateProxy.persistState(world).stateRootHash

  // ECIP-1121: from Olympia, import writes the parent hash at (number - 1) % 8191 in the EIP-2935 history contract
  // (BlockExecution.applyEip2935, gated by block number). A trace replay starts on that state, not on the parent's. The
  // contract is deployed by EIP-2935's deploy transaction, not by the client (B-528), so the parent holds it here.
  "Replaying an ETC Olympia block" should "start on import's EIP-2935 parent-hash write" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in (new Chain(etcOlympia):
    val history = BlockExecution.HistoryStorageAddress
    val olympia: BlockHeader = etcHeader(OlympiaAt)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(olympia, Nil, 0, parentHeader)
    parent.getCode(history) shouldBe BlockExecution.HistoryStorageCode
    world.getCode(history) shouldBe BlockExecution.HistoryStorageCode
    world.getAccount(history).map(_.nonce) shouldBe parent.getAccount(history).map(_.nonce)
    world.getStorage(history).load(UInt256((OlympiaAt - 1) % 8191)) shouldBe BigInt(1, olympia.parentHash.value.toArray)
    // the state import executes the block's first transaction on, to the byte
    persistedRoot(world) shouldBe persistedRoot(executed(olympia, Nil).worldState)
    persistedRoot(world) should not be parent.stateRootHash
  )

  // B-528: where the history contract has no code, import deploys nothing (it used to deploy it at Olympia), so the
  // replay starts on the parent's state.
  it should "start on the parent's state at Olympia where the history contract has no code" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in (new Chain(etcOlympia, systemContracts = false):
    val olympia: BlockHeader = etcHeader(OlympiaAt)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(olympia, Nil, 0, parentHeader)
    world.getAccount(BlockExecution.HistoryStorageAddress) shouldBe None
    persistedRoot(world) shouldBe parent.stateRootHash
    persistedRoot(executed(olympia, Nil).worldState) shouldBe parent.stateRootHash
  )

  it should "start on the parent's state at the block before Olympia" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in (new Chain(etcOlympia, systemContracts = false):
    val before: BlockHeader = etcHeader(OlympiaAt - 1)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(before, Nil, 0, parentHeader)
    persistedRoot(world) shouldBe parent.stateRootHash
    persistedRoot(executed(before, Nil).worldState) shouldBe parent.stateRootHash
  )

  // ETC did not take the DAO drain: its dao block names no refund contract, so BlockExecution.drainDaoForkAccounts
  // moves nothing at 1,920,000. The drained account is one of ETH's drain list, funded here.
  private val drained: Address = ethMainnet.daoForkConfig.map(_.drainList.head).getOrElse(fail("ETH has no drain list"))
  private val DrainedBalance: BigInt = BigInt(10).pow(20)

  "Replaying ETC's DAO fork block" should "start on the parent's state, with nothing drained" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in (new Chain(etcMainnet, systemContracts = false, funded = Seq(drained -> DrainedBalance)):
    etcMainnet.daoForkConfig.map(d => (d.forkBlockNumber, d.refundContract)) shouldBe Some((BigInt(DaoForkBlock), None))
    val dao: BlockHeader = etcHeader(DaoForkBlock)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(dao, Nil, 0, parentHeader)
    world.getAccount(drained).map(_.balance) shouldBe Some(UInt256(DrainedBalance))
    persistedRoot(world) shouldBe parent.stateRootHash
    persistedRoot(executed(dao, Nil).worldState) shouldBe parent.stateRootHash
  )

  // The control: on ETH's config the same replay drains the account, so the check above can fail.
  it should "differ from ETH's, where the same block drains the account" taggedAs (UnitTest, StateTest) in
    (new Chain(ethMainnet, systemContracts = false, funded = Seq(drained -> DrainedBalance)):
      val dao: BlockHeader = etcHeader(DaoForkBlock)
      val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(dao, Nil, 0, parentHeader)
      world.getAccount(drained).map(_.balance) shouldBe Some(UInt256.Zero)
      persistedRoot(world) should not be parent.stateRootHash
      persistedRoot(world) shouldBe persistedRoot(executed(dao, Nil).worldState)
    )

  // BLOCKHASH in a replay before Amsterdam walks the traced block's own ancestry, as import does
  // (BlockExecution.buildInitialWorld, AncestorBlockHashes): a traced side-chain block must not read the canonical
  // index, which names another chain above the fork point.
  "Replaying a non-canonical block before Amsterdam" should "read BLOCKHASH from the block's own ancestry" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in (new Chain(etcMainnet, systemContracts = false):
    def at(number: Long, parentHash: ByteString, salt: Int): BlockHeader =
      etcHeader(number).copy(parentHash = BlockHash(parentHash), extraData = ByteString(salt))
    val canonical44: BlockHeader = at(44, header.parentHash.value, 1)
    val canonical45: BlockHeader = at(45, canonical44.hash.value, 1)
    val side44: BlockHeader = at(44, header.parentHash.value, 2)
    val side45: BlockHeader = at(45, side44.hash.value, 2)
    blockchainWriter.storeBlockHeader(canonical44).and(blockchainWriter.storeBlockHeader(canonical45)).commit()
    blockchainWriter
      .storeBlockHeaderByHashOnly(side44)
      .and(blockchainWriter.storeBlockHeaderByHashOnly(side45))
      .commit()
    val traced: BlockHeader = at(46, side45.hash.value, 2)
    val world: InMemoryWorldStateProxy = ledger.advanceWorldToTx(traced, Nil, 0, parentHeader)
    world.getBlockHash(UInt256(45)) shouldBe Some(UInt256(side45.hash.value))
    world.getBlockHash(UInt256(44)) shouldBe Some(UInt256(side44.hash.value))
    blockchainReader.getBlockHeaderByNumber(44).map(_.hash) shouldBe Some(canonical44.hash)
  )

package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** The reference-fixture contracts and world, rebuilt so the Amsterdam gas vectors are *executed* rather than asserted
  * from a table.
  *
  * Every byte below is lifted from go-ethereum's devp2p test chain (`chain.rlp` / `headstate.json`) — the same fixture
  * `scripts/amsterdam-fixture/verify.py` re-derives its figures from. The contracts and the block-41 / block-45 world
  * shapes are measured, not invented:
  *
  *   - `calltree` (0x9dcd…27d0) — the block-41 transaction's target. Its 360 bytes drive one value-bearing CALL, one
  *     reverting CALL, two STATICCALLs, one DELEGATECALL, one CALLCODE, one identity-precompile CALL and one CREATE of
  *     a child that LOG1s and SELFDESTRUCTs. It is the single richest gas vector in the chain.
  *   - `callme` (…27d1), `blockhashes` (…27d2), `callrevert` (…27d3), `emit` (…27df) — its callees.
  *
  * Why this matters: block 41's two measured numbers (header `gasUsed` 183,600 and receipt `cumulativeGasUsed` 326,947)
  * are reproduced here to the gas unit by *running* the bytecode, which is a far stronger statement than asserting the
  * arithmetic identity `max(143,347, 183,600) = 183,600` over hand-supplied inputs.
  */
trait AmsterdamFixtureVectors:

  protected val setup: TestSetup = new TestSetup {}

  // ── Addresses, verbatim from the fixture ────────────────────────────────────

  val CallTree: Address = Address(Hex.decode("9dcd17433742f4c0ca53122ab541d0ba67fc27d0"))
  val CallMe: Address = Address(Hex.decode("9dcd17433742f4c0ca53122ab541d0ba67fc27d1"))
  val BlockHashes: Address = Address(Hex.decode("9dcd17433742f4c0ca53122ab541d0ba67fc27d2"))
  val CallRevert: Address = Address(Hex.decode("9dcd17433742f4c0ca53122ab541d0ba67fc27d3"))
  val Emit: Address = Address(Hex.decode("7dcd17433742f4c0ca53122ab541d0ba67fc27df"))

  /** EIP-7708 SYSTEM_ADDRESS — the emitter of every protocol value-transfer log. */
  val SystemAddress: Address = Address(Hex.decode("fffffffffffffffffffffffffffffffffffffffe"))

  /** keccak256("Transfer(address,address,uint256)") — ERC-20 compatible, per EIP-7708. */
  val TransferTopic: ByteString =
    ByteString(Hex.decode("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"))

  // ── Bytecode, verbatim from headstate.json ──────────────────────────────────

  private def code(hex: String): ByteString = ByteString(Hex.decode(hex))

  val CallTreeCode: ByteString = code(
    "7fff0100000000000000000000000000000000000000000000000000000000000060005260206101006002600060" +
      "01739dcd17433742f4c0ca53122ab541d0ba67fc27d161ea60f150600160005260006000602060006000739dcd17" +
      "433742f4c0ca53122ab541d0ba67fc27d361ea60f1506000600060006000739dcd17433742f4c0ca53122ab541d0" +
      "ba67fc27d261ea60fa506000600060006000737dcd17433742f4c0ca53122ab541d0ba67fc27df61ea60fa506000" +
      "6000526000600060046000737dcd17433742f4c0ca53122ab541d0ba67fc27df61ea60f4507fff01000000000000" +
      "00000000000000000000000000000000000000000000000060005260006000600260006000739dcd17433742f4c0" +
      "ca53122ab541d0ba67fc27d161ea60f25060006000600460006000600461ea60f15061015a38038061015a610200" +
      "396102006000f050637472656560006000a16002610100f35b646368696c6460006000a133ff"
  )

  val CallMeCode: ByteString = code(
    "366002146022577177726f6e672d63616c6c6461746173697a656000526012600efd5b60003560f01c61ff011460" +
      "47576d77726f6e672d63616c6c64617461600052600e6012fd5b61ffee6000526002601ef3"
  )

  val BlockHashesCode: ByteString = code(
    "60004381526020014681526020014181526020014881526020014481526020013281526020013481526020016000" +
      "f3"
  )

  val CallRevertCode: ByteString = code(
    "6000356142ff54501515603b577f4e487b7100000000000000000000000000000000000000000000000000000000" +
      "600052600160045260246000fd5b7f08c379a0000000000000000000000000000000000000000000000000000000" +
      "006000526020600452600a6024527f75736572206572726f72000000000000000000000000000000000000000000" +
      "00604452604e6000fd"
  )

  /** `emit`: CALLDATACOPY; KECCAK256 the calldata; SLOAD slot 0; SSTORE(hash, counter); SSTORE(0, counter+1); LOG2. Two
    * SSTOREs, the first keyed on the calldata hash — which is what makes it a state-creation vector.
    */
  val EmitCode: ByteString = code(
    "3680600080376000206000548082558060010160005560005263656d697460206000a2"
  )

  // ── Chain config: the fixture's forkenv.json, verbatim ──────────────────────

  /** All block-number forks at 0 (HIVE_FORK_LONDON=0 → `olympiaBlockNumber`), ETC forks disabled, timestamp forks at
    * Shanghai 0 / Cancun 60 / Prague 120 / Osaka 180 / Amsterdam 360.
    */
  val amsterdamConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(
        networkType = NetworkType.ETH,
        maxCodeSize = Some(BigInt(24576)),
        forkTimestamps = ForkTimestamps(
          shanghaiTimestamp = Some(0L),
          cancunTimestamp = Some(60L),
          pragueTimestamp = Some(120L),
          osakaTimestamp = Some(180L),
          amsterdamTimestamp = Some(360L)
        )
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
          // ETC-only forks stay disabled — this is the ETH fork schedule.
          atlantisBlockNumber = BigInt(Long.MaxValue),
          aghartaBlockNumber = BigInt(Long.MaxValue),
          phoenixBlockNumber = BigInt(Long.MaxValue),
          magnetoBlockNumber = BigInt(Long.MaxValue),
          mystiqueBlockNumber = BigInt(Long.MaxValue),
          spiralBlockNumber = BigInt(Long.MaxValue),
          ecip1099BlockNumber = BigInt(Long.MaxValue)
        )
      )

  /** The same chain one fork earlier: Amsterdam never activates. Used to show a vector moves only because Amsterdam
    * activated, not because the harness changed.
    */
  val preAmsterdamConfig: BlockchainConfig =
    amsterdamConfig.copy(forkTimestamps = amsterdamConfig.forkTimestamps.copy(amsterdamTimestamp = None))

  // ── Headers ─────────────────────────────────────────────────────────────────

  private val emptyRoot: ByteString =
    ByteString(Hex.decode("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"))

  /** Post-merge header shaped like the fixture's: zero difficulty + a base fee, so `isPoS` holds. */
  def amsterdamHeader(number: Long, timestamp: Long): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      difficulty = Difficulty(0),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount.Zero,
      beneficiary = Address(0xcafe).bytes,
      unixTimestamp = Timestamp(timestamp),
      extraFields = HefPostAmsterdam(
        baseFee = BigInt(7),
        withdrawalsRoot = emptyRoot,
        blobGasUsed = 0,
        excessBlobGas = 0,
        parentBeaconBlockRoot = ByteString(Array.fill[Byte](32)(0)),
        requestsHash = emptyRoot,
        blockAccessListHash = emptyRoot,
        slotNumber = number
      )
    )

  /** The same shape one fork earlier — 21 header fields, Osaka rules. */
  def preAmsterdamHeader(number: Long, timestamp: Long): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      difficulty = Difficulty(0),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount.Zero,
      beneficiary = Address(0xcafe).bytes,
      unixTimestamp = Timestamp(timestamp),
      extraFields = HefPostPrague(
        baseFee = BigInt(7),
        withdrawalsRoot = emptyRoot,
        blobGasUsed = 0,
        excessBlobGas = 0,
        parentBeaconBlockRoot = ByteString(Array.fill[Byte](32)(0)),
        requestsHash = emptyRoot
      )
    )

  // ── Sender ──────────────────────────────────────────────────────────────────

  val senderKeyPair: AsymmetricCipherKeyPair = generateKeyPair(setup.secureRandom)
  val senderAddress: Address = Address(senderKeyPair)

  // ── World ───────────────────────────────────────────────────────────────────

  private def withStorage(
      world: InMemoryWorldStateProxy,
      address: Address,
      slots: Map[BigInt, BigInt]
  ): InMemoryWorldStateProxy =
    val storage = slots.foldLeft(world.getStorage(address)) { case (s, (k, v)) => s.store(UInt256(k), UInt256(v)) }
    world.saveStorage(address, storage)

  private def contract(
      world: InMemoryWorldStateProxy,
      address: Address,
      contractCode: ByteString,
      balance: BigInt = 0,
      nonce: BigInt = 0,
      slots: Map[BigInt, BigInt] = Map.empty
  ): InMemoryWorldStateProxy =
    // `saveCode` only populates the side map; `Account.codeHash` is filled in by `persistState`. On a chain
    // loaded from disk the hash is already there, and `isAccountDead` reads it — so a harness that leaves it
    // empty makes every zero-balance contract look dead and hands its callers a spurious GAS_NEW_ACCOUNT.
    // That is exactly the 25,000-gas discrepancy this harness showed on block 8 before the hash was set.
    val w = world
      .saveAccount(
        address,
        Account(nonce = UInt256(nonce), balance = UInt256(balance), codeHash = CodeHash(kec256(contractCode)))
      )
      .saveCode(address, contractCode)
    withStorage(w, address, slots)

  /** keccak256 of four zero bytes — the storage slot the DELEGATECALL'd `emit` always writes, because `calltree` passes
    * exactly four zero bytes every time. Measured in `headstate.json` as
    * 0xe8e77626586f73b955364c7b4bbf0bb7f7685ebd40e852b164633a4acbd3244c.
    */
  val EmitSlotForFourZeroBytes: BigInt =
    BigInt(1, Hex.decode("e8e77626586f73b955364c7b4bbf0bb7f7685ebd40e852b164633a4acbd3244c"))

  /** The five `calltree` callees, in the shape they hold from genesis onward. Code presence is what matters: every one
    * of them has non-empty code, so none is "dead" and no CALL pays a new-account charge.
    */
  private def calltreeCallees(
      world: InMemoryWorldStateProxy,
      callMeBalance: BigInt,
      emitCounter: BigInt
  ): InMemoryWorldStateProxy =
    val w1 = contract(world, CallMe, CallMeCode, balance = callMeBalance)
    val w2 = contract(w1, BlockHashes, BlockHashesCode)
    val w3 = contract(w2, CallRevert, CallRevertCode)
    contract(w3, Emit, EmitCode, balance = 16, slots = Map(BigInt(0) -> emitCounter))

  private def funded: InMemoryWorldStateProxy =
    setup.emptyWorld.saveAccount(
      senderAddress,
      Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000")))
    )

  /** The world before block 8's `tx-calltree` — `calltree`'s FIRST invocation.
    *
    * Storage shape decides the answer: slot 0 is unset, so the DELEGATECALL'd `emit` reads counter 0, writes zero to
    * the (also unset) hash-keyed slot — a no-op SSTORE — and then creates slot 0 at GAS_STORAGE_SET. Measured total:
    * 165,447.
    */
  def block8World: InMemoryWorldStateProxy =
    val w = contract(funded, CallTree, CallTreeCode, balance = 1000, nonce = 0)
    calltreeCallees(w, callMeBalance = 0, emitCounter = 3)

  /** The world before block 24's `tx-calltree` — the second invocation. Now slot 0 = 1, so `emit` writes 1 to the
    * hash-keyed slot, *creating* it, and updates slot 0. Measured total: 168,247 — 2,800 more than block 8, purely from
    * the storage transition. That difference is what makes this pair a real check on SSTORE modelling.
    */
  def block24World: InMemoryWorldStateProxy =
    val w = contract(funded, CallTree, CallTreeCode, balance = 1000, nonce = 1, slots = Map(BigInt(0) -> BigInt(1)))
    calltreeCallees(w, callMeBalance = 1, emitCounter = 6)

  /** The world for `tx-callrevert` (blocks 23 and 40): the contract SLOADs a cold slot and then REVERTs. Measured
    * 23,201 before activation and 17,201 after — the whole 6,000 being EIP-2780's intrinsic decomposition.
    */
  def callRevertWorld: InMemoryWorldStateProxy =
    contract(funded, CallRevert, CallRevertCode)

  /** The world as it stood immediately before block 41's transaction.
    *
    * Measured constraints that the gas figure depends on:
    *   - `calltree` nonce is **2** (its CREATE at block 41 derives the child address from nonce 2 — confirmed by the
    *     receipts-root reconstruction in `verify.py`), and it holds a balance so its 1-wei CALL succeeds.
    *   - `calltree` slot 0 = 2 and the hash-keyed slot = 1, both **non-zero**: the hash-keyed slot was created at block
    *     24, so both of block 41's SSTOREs are existing-slot updates, not fresh-slot creations. This is the single
    *     assumption that decides whether the DELEGATECALL costs 25,776 or 43,876.
    *   - `emit` slot 0 is non-zero so its SLOAD is a plain cold read.
    */
  def block41World: InMemoryWorldStateProxy =
    val w = contract(
      funded,
      CallTree,
      CallTreeCode,
      balance = 1000,
      nonce = 2,
      slots = Map(BigInt(0) -> BigInt(2), EmitSlotForFourZeroBytes -> BigInt(1))
    )
    calltreeCallees(w, callMeBalance = 2, emitCounter = 8)

  /** The world before block 45's `tx-emit-legacy` — `emit` holds counter 8 and no slot for the block's calldata hash,
    * so its first SSTORE is a **fresh** slot and pulls GAS_STORAGE_SET into the state dimension.
    */
  def block45World: InMemoryWorldStateProxy =
    contract(funded, Emit, EmitCode, balance = 16, slots = Map(BigInt(0) -> BigInt(8)))

  // ── Execution ───────────────────────────────────────────────────────────────

  def execute(
      stx: SignedTransaction,
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      config: BlockchainConfig
  ): TxResult =
    setup.prep.executeTransaction(stx, senderAddress, header, world)(config)

  /** Executes a whole transaction list, so the BLOCK-level counters can be asserted. `executeTransaction` alone gives
    * the per-transaction figure; only this reaches `BlockResult.gasUsed`, which is the header's `max()`.
    */
  def executeBlock(
      txs: Seq[SignedTransaction],
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      config: BlockchainConfig
  ): BlockResult =
    setup.prep
      .executeTransactions(txs, world, header)(config)
      .fold(err => throw new AssertionError(s"block execution failed: ${err.reason}"), identity)

  /** Type-2 transaction, matching the fixture's dominant shape. */
  def dynamicFeeTx(
      to: Option[Address],
      value: BigInt,
      gasLimit: BigInt,
      payload: ByteString = ByteString.empty,
      nonce: BigInt = 0,
      accessList: List[AccessListItem] = Nil,
      config: BlockchainConfig
  ): SignedTransaction =
    val tx = TransactionWithDynamicFee(
      chainId = config.chainId.value,
      nonce = nonce,
      maxPriorityFeePerGas = BigInt(0),
      maxFeePerGas = BigInt(1_000_000_000),
      gasLimit = GasAmount(gasLimit),
      receivingAddress = to,
      value = value,
      payload = payload,
      accessList = accessList
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(config.chainId.value))

  def legacyTx(
      to: Option[Address],
      value: BigInt,
      gasLimit: BigInt,
      payload: ByteString = ByteString.empty,
      nonce: BigInt = 0,
      config: BlockchainConfig
  ): SignedTransaction =
    val tx = LegacyTransaction(
      nonce = nonce,
      gasPrice = GasPrice(1_000_000_000),
      gasLimit = GasAmount(gasLimit),
      receivingAddress = to,
      value = value,
      payload = payload
    )
    SignedTransaction.sign(tx, senderKeyPair, Some(config.chainId.value))

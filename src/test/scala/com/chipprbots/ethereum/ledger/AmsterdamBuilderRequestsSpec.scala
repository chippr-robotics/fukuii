package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.security.MessageDigest

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderDepositQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderDepositRequestType
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderExitQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderExitRequestType
import com.chipprbots.ethereum.ledger.BlockExecution.ConsolidationQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.ConsolidationRequestType
import com.chipprbots.ethereum.ledger.BlockExecution.WithdrawalQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.WithdrawalRequestType
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** EIP-8282: the end-of-block SYSTEM_ADDRESS call to the two builder predeploys.
  *
  * Every byte below is lifted from go-ethereum's devp2p test chain (`chain.rlp`, `genesis.json`, `headstate.json`,
  * `accounts.json`) — the fixture `scripts/amsterdam-fixture/verify.py` re-derives its figures from. Nothing here is
  * invented:
  *
  *   - the two predeploy runtime codes are the genesis allocs verbatim (628 and 458 bytes);
  *   - the transactions are blocks 36 and 37 of that chain, with their real calldata, value and sender;
  *   - the sender key is the fixture's own, so block 37's request — which embeds `msg.sender` — reconstructs to the
  *     hash the fixture header actually carries.
  *
  * The defect this pins: a client that executes the user path but skips the system call leaves the per-block
  * bookkeeping slots (0x01 `count`, 0x03 `tail` on the deposit predeploy) set. The dequeue path clears them. Those
  * slots are in the account's storage trie, so leaving them dirty forks storageRoot -> account RLP -> STATE ROOT.
  * Measured: fukuii produced 017c0d6e... where block 36 requires 09e99495..., and blocks 37..600 then failed
  * UNKNOWN_PARENT.
  */
class AmsterdamBuilderRequestsSpec extends AnyFlatSpec with Matchers:

  /** `amsterdamTime` from the fixture's forkenv.json. Block 36 is the first Amsterdam block at 10s spacing. */
  private val AmsterdamTs: Long = 360L

  private def sha256(b: ByteString): ByteString =
    ByteString(MessageDigest.getInstance("SHA-256").digest(b.toArray))

  /** EIP-7685 per-type commitment, in the "empty requests excluded" form the fixture headers use. */
  private def requestHash(request: ByteString): ByteString = sha256(sha256(request))

  trait Setup extends EphemBlockchainTestSetup:
    // NOTE: the real VM, deliberately. The whole point is to run the predeploy bytecode; a MockVM would
    // assert nothing about the dequeue path.

    implicit override lazy val blockchainConfig: BlockchainConfig =
      Config.blockchains.blockchainConfig
        .copy(
          networkType = NetworkType.ETH,
          forkTimestamps = ForkTimestamps(
            shanghaiTimestamp = Some(0L),
            cancunTimestamp = Some(60L),
            pragueTimestamp = Some(120L),
            osakaTimestamp = Some(180L),
            amsterdamTimestamp = Some(AmsterdamTs)
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
            atlantisBlockNumber = BigInt(Long.MaxValue),
            aghartaBlockNumber = BigInt(Long.MaxValue),
            phoenixBlockNumber = BigInt(Long.MaxValue),
            magnetoBlockNumber = BigInt(Long.MaxValue),
            mystiqueBlockNumber = BigInt(Long.MaxValue),
            spiralBlockNumber = BigInt(Long.MaxValue),
            ecip1099BlockNumber = BigInt(Long.MaxValue)
          )
        )

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

    override lazy val blockValidation: BlockValidation = new BlockValidation(
      mining.withValidators(com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed),
      blockchainReader,
      blockQueue
    )

    lazy val exec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )

    // ── The fixture's predeploys, verbatim from genesis.json ────────────────────

    val builderDepositCode: ByteString = ByteString(
      Hex.decode(
        "3373fffffffffffffffffffffffffffffffffffffffe1461011c575f54807fffffffffffffffffffffffffffffffffff" +
          "ffffffffffffffffffffffffffffff146102705760015460088111605257506058565b60089003015b60119060018202" +
          "6001905f5b5f821115607f57810190830284830290049160010191906064565b90939004925050503660b814609f5736" +
          "6102705734610270575f5260205ff35b8034106102705760383567ffffffffffffffff1680633b9aca00116102705763" +
          "3b9aca00029034031061027057600154600101600155600354806006026004015f358155600101602035815560010160" +
          "403581556001016060358155600101608035815560010160a035905560b85f5f3760b85fa0600101600355005b600354" +
          "60025480820380604011610131575060405b5f5b8181146101d7578281016006026004018160b8028154815260200181" +
          "600101548152602001816002015480825260401c67ffffffffffffffff16816010018160381c81600701538160301c81" +
          "600601538160281c81600501538160201c81600401538160181c81600301538160101c81600201538160081c81600101" +
          "5353602001816003015481526020018160040154815260200190600501549052600101610133565b91018092146101e9" +
          "57906002556101f4565b90505f6002555f6003555b36610242575f54600154817fffffffffffffffffffffffffffffff" +
          "ffffffffffffffffffffffffffffffffff1461023057600882820111610238575b50505f610264565b01600890036102" +
          "64565b7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff5b5f555f60015560b8025ff3" +
          "5b5f5ffd"
      )
    )

    val builderExitCode: ByteString = ByteString(
      Hex.decode(
        "3373fffffffffffffffffffffffffffffffffffffffe1460e1575f54807fffffffffffffffffffffffffffffffffffff" +
          "ffffffffffffffffffffffffffff146101c65760015460028111605157506057565b60029003015b6011906001820260" +
          "01905f5b5f821115607e57810190830284830290049160010191906063565b909390049250505036603014609e573661" +
          "01c657346101c6575f5260205ff35b34106101c657600154600101600155600354806003026004013381556001015f35" +
          "815560010160203590553360601b5f5260305f60143760445fa0600101600355005b6003546002548082038060101160" +
          "f5575060105b5f5b81811461012d5782810160030260040181604402815460601b815260140181600101548152602001" +
          "9060020154905260010160f7565b910180921461013f579060025561014a565b90505f6002555f6003555b3661019857" +
          "5f54600154817fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff146101865760028282" +
          "011161018e575b50505f6101ba565b01600290036101ba565b7fffffffffffffffffffffffffffffffffffffffffffff" +
          "ffffffffffffffffffff5b5f555f6001556044025ff35b5f5ffd"
      )
    )

    // The pre-existing Prague queue predeploys, also verbatim from the fixture's genesis.json (504 and 414
    // bytes). They exist here for one purpose: to prove that Amsterdam's raised SYSTEM_CALL_GAS_LIMIT does not
    // perturb the EIP-7002/7251 calls that already shipped.

    val withdrawalQueueCode: ByteString = QueuePredeploys.WithdrawalQueueCode

    val consolidationQueueCode: ByteString = QueuePredeploys.ConsolidationQueueCode

    // ── The fixture's sender: accounts.json 0x7435ed30a8b4aeb0877cef0c6e8cffe834eb865f ────

    val senderKey = crypto.keyPairFromPrvKey(
      Hex.decode("4552dbe6ca4699322b5d923d0c9bcdd24644f5db8bf89a085b67c6c49b8a1b91")
    )
    val senderAddress: Address = Address(senderKey)

    val emptyWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    private def predeploy(
        world: InMemoryWorldStateProxy,
        address: Address,
        code: ByteString
    ): InMemoryWorldStateProxy =
      // Balance 1 wei and codeHash set, exactly as genesis.json allocates them. The hash matters: `isAccountDead`
      // reads it, and an unset hash makes a zero-balance contract look dead to its callers.
      world
        .saveAccount(
          address,
          Account(nonce = UInt256(0), balance = UInt256(1), codeHash = CodeHash(crypto.kec256(code)))
        )
        .saveCode(address, code)

    /** Genesis shape: both builder predeploys deployed, queues empty, sender funded. */
    def worldWithPredeploys: InMemoryWorldStateProxy =
      val funded = emptyWorld.saveAccount(
        senderAddress,
        Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000")))
      )
      predeploy(
        predeploy(funded, BuilderDepositQueueAddress, builderDepositCode),
        BuilderExitQueueAddress,
        builderExitCode
      )

    /** Prague shape: ONLY the EIP-7002/7251 predeploys, sender funded. The builder pair is deliberately absent, so
      * `code.nonEmpty` skips it and an Amsterdam block and a pre-Amsterdam block differ in exactly one thing: the gas
      * ceiling the system call is funded with.
      */
    def worldWithQueuePredeploys: InMemoryWorldStateProxy =
      val funded = emptyWorld.saveAccount(
        senderAddress,
        Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000")))
      )
      predeploy(
        predeploy(funded, WithdrawalQueueAddress, withdrawalQueueCode),
        ConsolidationQueueAddress,
        consolidationQueueCode
      )

    def amsterdamBlock(number: Long, timestamp: Long, txs: Seq[SignedTransaction]): Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(number),
        difficulty = Difficulty(0),
        gasLimit = GasAmount(30_000_000),
        gasUsed = GasAmount.Zero,
        beneficiary = Address(0xcafe).bytes,
        unixTimestamp = Timestamp(timestamp),
        extraFields = HefPostAmsterdam(
          baseFee = BigInt(7),
          withdrawalsRoot = ByteString(Array.fill[Byte](32)(0)),
          blobGasUsed = 0,
          excessBlobGas = 0,
          parentBeaconBlockRoot = ByteString(Array.fill[Byte](32)(0)),
          requestsHash = ByteString(Array.fill[Byte](32)(0)),
          blockAccessListHash = ByteString(Array.fill[Byte](32)(0)),
          slotNumber = number
        )
      ),
      body = BlockBody(txs.toList, Nil)
    )

    def tx(to: Address, value: BigInt, payload: ByteString, nonce: BigInt = 0): SignedTransaction =
      SignedTransaction.sign(
        TransactionWithDynamicFee(
          chainId = blockchainConfig.chainId.value,
          nonce = nonce,
          maxPriorityFeePerGas = BigInt(0),
          maxFeePerGas = BigInt(1_000_000_000),
          gasLimit = GasAmount(1_000_000),
          receivingAddress = Some(to),
          value = value,
          payload = payload,
          accessList = Nil
        ),
        senderKey,
        Some(blockchainConfig.chainId.value)
      )

    def storageOf(world: InMemoryWorldStateProxy, address: Address, slot: Int): BigInt =
      world.getStorage(address).load(UInt256(slot))

  // ── Block 36: the deposit request ───────────────────────────────────────────

  /** Block 36's calldata, verbatim: pubkey48 || withdrawal_credentials32 || amount8 (BIG-endian) || signature96. */
  private val DepositCalldata: ByteString = ByteString(
    Hex.decode(
      "424242424242424242424242424242424242424242424242424242424242424242424242424242424242424242424242" +
        "0000000000000000000000007435ed30a8b4aeb0877cef0c6e8cffe834eb865f000000003b9aca002424242424242424" +
        "242424242424242424242424242424242424242424242424242424242424242424242424242424242424242424242424" +
        "24242424242424242424242424242424242424242424242424242424242424242424242424242424"
    )
  )

  /** Block 36's `value`: 1 ETH of deposit plus the 1-wei queue fee. */
  private val DepositValue: BigInt = BigInt("1000000000000000001")

  /** `requestsHash` of fixture block 36, read straight out of the header (field 20). */
  private val Block36RequestsHash: ByteString =
    ByteString(Hex.decode("2cd85ec1ca212c208e88aff182b8fe913fd70e02e9bf34a0b2c24698860cc368"))

  /** `requestsHash` of fixture block 37. */
  private val Block37RequestsHash: ByteString =
    ByteString(Hex.decode("8273382302e2575346fb59a19620ff22875bfce26ffdab75c633290458f60d04"))

  behavior of "EIP-8282 builder request system calls"

  it should "clear the deposit predeploy's per-block bookkeeping slots that the user path dirtied" taggedAs (
    UnitTest,
    StateTest
  ) in new Setup:
    val block = amsterdamBlock(36, AmsterdamTs, Seq(tx(BuilderDepositQueueAddress, DepositValue, DepositCalldata)))
    val afterTx = exec.executeBlockTransactions(block, worldWithPredeploys).toOption.get

    // The user path ran: it wrote the queue count (slot 1) and tail (slot 3) and the 6 record words (slots 4..9).
    withClue("the user path must dirty slot 1 (count) — otherwise this test proves nothing: ") {
      storageOf(afterTx.worldState, BuilderDepositQueueAddress, 1) should not be BigInt(0)
    }
    withClue("the user path must dirty slot 3 (tail): ") {
      storageOf(afterTx.worldState, BuilderDepositQueueAddress, 3) should not be BigInt(0)
    }

    val (afterSystemCall, _) = exec.processPragueSystemCalls(block, afterTx.worldState)

    // The dequeue path drains the queue and resets the bookkeeping. These two slots are the state-root defect.
    storageOf(afterSystemCall, BuilderDepositQueueAddress, 1) shouldBe BigInt(0)
    storageOf(afterSystemCall, BuilderDepositQueueAddress, 3) shouldBe BigInt(0)
    storageOf(afterSystemCall, BuilderDepositQueueAddress, 2) shouldBe BigInt(0)
    // Slot 0 is `excess`. One deposit against a target of 8 leaves it at zero, which is why headstate.json shows
    // this account holding six slots and not seven.
    storageOf(afterSystemCall, BuilderDepositQueueAddress, 0) shouldBe BigInt(0)

    // ...while the 6 record words survive, exactly as headstate.json shows them at block 600.
    val record = Seq(
      "4242424242424242424242424242424242424242424242424242424242424242",
      "424242424242424242424242424242420000000000000000000000007435ed30",
      "a8b4aeb0877cef0c6e8cffe834eb865f000000003b9aca002424242424242424",
      "2424242424242424242424242424242424242424242424242424242424242424",
      "2424242424242424242424242424242424242424242424242424242424242424",
      "2424242424242424242424242424242424242424242424240000000000000000"
    ).map(h => BigInt(1, Hex.decode(h)))
    for (expected, i) <- record.zipWithIndex do
      withClue(s"slot 0x0${i + 4}: ") {
        storageOf(afterSystemCall, BuilderDepositQueueAddress, i + 4) shouldBe expected
      }

  it should "emit 0x03 || 184 bytes with the amount LITTLE-endian, hashing to the fixture's requestsHash" taggedAs (
    UnitTest,
    StateTest
  ) in new Setup:
    val block = amsterdamBlock(36, AmsterdamTs, Seq(tx(BuilderDepositQueueAddress, DepositValue, DepositCalldata)))
    val afterTx = exec.executeBlockTransactions(block, worldWithPredeploys).toOption.get
    val (_, requests) = exec.processPragueSystemCalls(block, afterTx.worldState)

    requests should have size 1
    val request = requests.head
    request.head shouldBe BuilderDepositRequestType.toByte
    withClue("type byte || pubkey48 || wc32 || amount8 || sig96: ")(request.length shouldBe 185)

    // TRAP: the amount is big-endian in the incoming calldata and little-endian in the emitted request — the
    // predeploy byte-reverses it. eips.ethereum.org's "big-endian" prose describes the INPUT. Getting this
    // backwards still produces a well-formed 185-byte request; only the hash catches it.
    val amountBigEndianInCalldata = DepositCalldata.slice(80, 88)
    val amountInRequest = request.slice(1 + 48 + 32, 1 + 48 + 32 + 8)
    amountBigEndianInCalldata shouldBe ByteString(Hex.decode("000000003b9aca00"))
    amountInRequest shouldBe ByteString(Hex.decode("00ca9a3b00000000"))
    amountInRequest shouldBe ByteString(amountBigEndianInCalldata.reverse.toArray)

    requestHash(request) shouldBe Block36RequestsHash

  // ── Block 37: the exit request ──────────────────────────────────────────────

  it should "emit 0x04 || sender20 || pubkey48 for a builder exit, matching block 37's requestsHash" taggedAs (
    UnitTest,
    StateTest
  ) in new Setup:
    val pubkey = ByteString(Hex.decode("42" * 48))
    val block = amsterdamBlock(37, AmsterdamTs + 10, Seq(tx(BuilderExitQueueAddress, 1, pubkey)))
    val afterTx = exec.executeBlockTransactions(block, worldWithPredeploys).toOption.get
    val (afterSystemCall, requests) = exec.processPragueSystemCalls(block, afterTx.worldState)

    requests should have size 1
    val request = requests.head
    request.head shouldBe BuilderExitRequestType.toByte
    request.length shouldBe 69
    withClue("the request embeds msg.sender, which is why the fixture key is used here: ") {
      request.slice(1, 21) shouldBe senderAddress.bytes
    }
    request.drop(21) shouldBe pubkey

    requestHash(request) shouldBe Block37RequestsHash

    storageOf(afterSystemCall, BuilderExitQueueAddress, 1) shouldBe BigInt(0)
    storageOf(afterSystemCall, BuilderExitQueueAddress, 3) shouldBe BigInt(0)
    storageOf(afterSystemCall, BuilderExitQueueAddress, 2) shouldBe BigInt(0)
    storageOf(afterSystemCall, BuilderExitQueueAddress, 0) shouldBe BigInt(0)

    // ...and the record survives the drain, in the 3-word shape headstate.json shows for this predeploy:
    // slot 4 = msg.sender, slots 5..6 = the 48-byte pubkey, right-zero-padded into the second word.
    storageOf(afterSystemCall, BuilderExitQueueAddress, 4) shouldBe BigInt(1, senderAddress.bytes.toArray)
    storageOf(afterSystemCall, BuilderExitQueueAddress, 5) shouldBe BigInt(1, Hex.decode("42" * 32))
    storageOf(afterSystemCall, BuilderExitQueueAddress, 6) shouldBe BigInt(1, Hex.decode("42" * 16 + "00" * 16))

  // ── The system call is not metered into the block ───────────────────────────

  it should "contribute nothing to block gasUsed" taggedAs (UnitTest, StateTest) in new Setup:
    val block = amsterdamBlock(36, AmsterdamTs, Seq(tx(BuilderDepositQueueAddress, DepositValue, DepositCalldata)))
    val afterTx = exec.executeBlockTransactions(block, worldWithPredeploys).toOption.get

    val (afterSystemCall, requests) = exec.processPragueSystemCalls(block, afterTx.worldState)

    // The system call demonstrably ran: it produced a request and drained the queue.
    requests should have size 1
    storageOf(afterSystemCall, BuilderDepositQueueAddress, 1) shouldBe BigInt(0)

    // And it is absent from the block's gas. This is the real check, not a tautology: the call is funded with
    // AmsterdamGas.SystemCallGasLimit (~31.5M) and the dequeue path it runs does its own SSTOREs, so if any of
    // that were folded into the block counters this figure could not still be 783,360.
    //
    // 783,360 = 8 x GasStorageSet(97,920) — the state dimension of the 8 fresh SSTOREs the *transaction* performs,
    // and exactly what fixture block 36's header reports. EIP-8037 / FR-016: gasUsed is the transactions alone.
    withClue("block 36 header gasUsed, from chain.rlp: ")(afterTx.gasUsed shouldBe BigInt(783360))
    afterTx.stateGasUsed shouldBe BigInt(783360)
    withClue("execution dimension must be the smaller one, so max() picks the state dimension: ") {
      afterTx.executionGasUsed should be < BigInt(783360)
    }

  // ── Ordering and the ETC guard ──────────────────────────────────────────────

  it should "append the builder targets after 7002/7251 so EIP-7685 type order holds" taggedAs (UnitTest) in new Setup:
    BlockExecution.systemCallTargets(Timestamp(AmsterdamTs)) shouldBe Seq(
      (WithdrawalQueueAddress, WithdrawalRequestType),
      (ConsolidationQueueAddress, ConsolidationRequestType),
      (BuilderDepositQueueAddress, BuilderDepositRequestType),
      (BuilderExitQueueAddress, BuilderExitRequestType)
    )
    BlockExecution.systemCallTargets(Timestamp(AmsterdamTs)).map(_._2) shouldBe Seq(0x01, 0x02, 0x03, 0x04)

  it should "leave the pre-Amsterdam target list untouched" taggedAs (UnitTest) in new Setup:
    BlockExecution.systemCallTargets(Timestamp(AmsterdamTs - 1)) shouldBe Seq(
      (WithdrawalQueueAddress, WithdrawalRequestType),
      (ConsolidationQueueAddress, ConsolidationRequestType)
    )

  /** ETC declares no timestamp fork at all, so `isAmsterdamTimestamp` reads `None` and the builder predeploys can never
    * be reached — at any timestamp, including ones far past any ETH activation.
    */
  it should "never reach the builder predeploys on an ETC chain" taggedAs (UnitTest) in new Setup:
    val etc: BlockchainConfig = blockchainConfig.copy(
      networkType = NetworkType.ETC,
      forkTimestamps = ForkTimestamps()
    )
    for ts <- Seq(0L, AmsterdamTs, Long.MaxValue / 2) do
      withClue(s"ETC at timestamp $ts: ") {
        BlockExecution.systemCallTargets(Timestamp(ts))(etc) shouldBe Seq(
          (WithdrawalQueueAddress, WithdrawalRequestType),
          (ConsolidationQueueAddress, ConsolidationRequestType)
        )
      }

  // ── Amsterdam must not perturb the EIP-7002/7251 calls that already shipped ──

  /** Wiring the builder pair in also raised `SYSTEM_CALL_GAS_LIMIT` from a flat 30,000,000 to
    * `AmsterdamGas.SystemCallGasLimit` (31,566,720) for *every* system call, because EIP-8037 defines it as one global
    * constant rather than a per-contract one. That silently re-funds the already-shipped EIP-7002 and EIP-7251 calls,
    * so it needs an oracle rather than an argument.
    *
    * Both arms below run the fixture's own withdrawal and consolidation bytecode over a queue the user path has
    * actually filled, from one shared post-transaction world. The builder predeploys are deliberately not deployed, so
    * `code.nonEmpty` skips them and the two arms differ only in the fork the block sits in — and therefore in the gas
    * ceiling and the fee schedule handed to these two calls. Byte-identical requests and byte-identical storage is the
    * claim.
    */
  it should "leave the EIP-7002/7251 system calls byte-identical when Amsterdam raises the gas ceiling" taggedAs (
    UnitTest,
    StateTest
  ) in new Setup:
    // pubkey48 || amount8 = 56 bytes, the EIP-7002 add-request shape.
    val withdrawalCalldata = ByteString(Hex.decode("42" * 48 + "000000003b9aca00"))
    // source_pubkey48 || target_pubkey48 = 96 bytes, the EIP-7251 add-request shape.
    val consolidationCalldata = ByteString(Hex.decode("42" * 48 + "43" * 48))

    val setupBlock = amsterdamBlock(
      36,
      AmsterdamTs,
      Seq(
        tx(WithdrawalQueueAddress, 1, withdrawalCalldata, nonce = 0),
        tx(ConsolidationQueueAddress, 1, consolidationCalldata, nonce = 1)
      )
    )
    val queued = exec.executeBlockTransactions(setupBlock, worldWithQueuePredeploys).toOption.get.worldState

    // Guard against a vacuous comparison: both queues must actually be non-empty before the system call runs.
    withClue("the withdrawal queue must be non-empty, or the arms compare nothing: ") {
      storageOf(queued, WithdrawalQueueAddress, 3) should not be BigInt(0)
    }
    withClue("the consolidation queue must be non-empty: ") {
      storageOf(queued, ConsolidationQueueAddress, 3) should not be BigInt(0)
    }

    // Arm 1: Prague, ceiling 30,000,000. Arm 2: Amsterdam, ceiling 31,566,720.
    val (pragueWorld, pragueRequests) =
      exec.processPragueSystemCalls(amsterdamBlock(36, AmsterdamTs - 1, Nil), queued)
    val (amsterdamWorld, amsterdamRequests) =
      exec.processPragueSystemCalls(amsterdamBlock(36, AmsterdamTs, Nil), queued)

    withClue("both arms must emit the withdrawal (0x01) and consolidation (0x02) requests: ") {
      pragueRequests.map(_.head) shouldBe Seq(WithdrawalRequestType.toByte, ConsolidationRequestType.toByte)
    }
    amsterdamRequests shouldBe pragueRequests

    for
      address <- Seq(WithdrawalQueueAddress, ConsolidationQueueAddress)
      slot <- 0 to 12
    do
      withClue(s"$address slot 0x${slot.toHexString}: ") {
        storageOf(amsterdamWorld, address, slot) shouldBe storageOf(pragueWorld, address, slot)
      }

    withClue("and the raised ceiling must not fork the state root of the queue predeploys: ") {
      InMemoryWorldStateProxy
        .persistState(amsterdamWorld)
        .stateRootHash shouldBe InMemoryWorldStateProxy.persistState(pragueWorld).stateRootHash
    }
